package survey.core;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Survey life-cycle: create -> (optional delay) -> running -> reminder -> closed.
 * <p>
 * All state changes happen under one lock; network I/O (via {@link Messenger}) is always done
 * on a separate executor and never while holding the lock.
 */
public class SurveyService {

    public static final int MIN_MEMBERS = 3;
    public static final int MAX_QUESTIONS = 3;
    public static final int MIN_OPTIONS = 2;
    public static final int MAX_OPTIONS = 4;

    public enum Event { SCHEDULED, STARTED, PROGRESS, CLOSED, CANCELLED }

    public interface Listener {
        /** Called on an arbitrary thread - UI code must hop to the EDT. */
        void onSurveyEvent(Event event, long surveyId);
    }

    public enum AnswerStatus { RECORDED, ALREADY_ANSWERED, SURVEY_CLOSED, NOT_PARTICIPANT, INVALID }

    public record AnswerOutcome(AnswerStatus status, int answered, int total, boolean completed,
                                int questionNumber, String questionText, String optionText) {
        static AnswerOutcome of(AnswerStatus s) {
            return new AnswerOutcome(s, 0, 0, false, 0, "", "");
        }
    }

    private final CommunityService community;
    private final Messenger messenger;
    private final Duration surveyDuration;
    private final Duration reminderAfter;

    private final Object lock = new Object();
    private final List<Survey> history = new ArrayList<>();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private long nextId = 1;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(daemon("survey-timer"));
    private final ExecutorService io = Executors.newFixedThreadPool(4, daemon("survey-io"));

    public SurveyService(CommunityService community, Messenger messenger) {
        this(community, messenger, Duration.ofMinutes(5), Duration.ofMinutes(3));
    }

    /** Durations are injectable so tests don't have to wait minutes. */
    public SurveyService(CommunityService community, Messenger messenger,
                         Duration surveyDuration, Duration reminderAfter) {
        this.community = community;
        this.messenger = messenger;
        this.surveyDuration = surveyDuration;
        this.reminderAfter = reminderAfter;
    }

    public Duration surveyDuration() {
        return surveyDuration;
    }

    public void addListener(Listener l) {
        listeners.add(l);
    }

    // ------------------------------------------------------------------ creation

    /**
     * Validates the questions and starts the survey now (delay = 0) or after the delay.
     *
     * @throws IllegalArgumentException invalid questions (message is user-facing, Hebrew)
     * @throws IllegalStateException    a survey is already active / fewer than 3 members (user-facing)
     */
    public SurveyView create(List<Question> questions, Duration delay) {
        validate(questions);
        Survey s;
        boolean immediate = delay == null || delay.isZero() || delay.isNegative();
        synchronized (lock) {
            if (hasActive()) {
                throw new IllegalStateException("כבר קיים סקר פעיל. ניתן להתחיל סקר חדש רק לאחר שהסקר הנוכחי ייסגר.");
            }
            int size = community.size();
            if (size < MIN_MEMBERS) {
                throw new IllegalStateException("לא ניתן להתחיל סקר: בקהילה " + size
                        + " חברים בלבד, ונדרשים לפחות " + MIN_MEMBERS + ".");
            }
            s = new Survey(nextId++, questions);
            history.add(s);
            if (immediate) {
                startLocked(s);
            } else {
                s.state = Survey.State.SCHEDULED;
                s.scheduledFor = Instant.now().plus(delay);
                final long id = s.id;
                s.startTask = scheduler.schedule(() -> startScheduled(id), delay.toMillis(), TimeUnit.MILLISECONDS);
            }
        }
        fire(immediate ? Event.STARTED : Event.SCHEDULED, s.id);
        if (immediate) dispatchSurvey(s);
        return view(s);
    }

    /** Throws IllegalArgumentException with a user-facing message if the survey is malformed. */
    public static void validate(List<Question> questions) {
        if (questions == null || questions.isEmpty() || questions.size() > MAX_QUESTIONS) {
            throw new IllegalArgumentException("סקר חייב לכלול בין שאלה אחת ל-" + MAX_QUESTIONS + " שאלות.");
        }
        for (int i = 0; i < questions.size(); i++) {
            Question q = questions.get(i);
            String where = "שאלה " + (i + 1) + ": ";
            if (q.text().isBlank()) throw new IllegalArgumentException(where + "יש לכתוב את נוסח השאלה.");
            if (q.options().size() < MIN_OPTIONS || q.options().size() > MAX_OPTIONS) {
                throw new IllegalArgumentException(where + "יש להזין בין " + MIN_OPTIONS + " ל-" + MAX_OPTIONS + " אפשרויות תשובה.");
            }
            if (q.options().stream().anyMatch(String::isBlank)) {
                throw new IllegalArgumentException(where + "אפשרות תשובה לא יכולה להיות ריקה.");
            }
            if (q.options().stream().map(String::toLowerCase).distinct().count() != q.options().size()) {
                throw new IllegalArgumentException(where + "אפשרויות התשובה חייבות להיות שונות זו מזו.");
            }
        }
    }

    /** Cancels a survey that is still waiting for its delayed start. */
    public boolean cancelScheduled() {
        long id;
        synchronized (lock) {
            Survey s = active();
            if (s == null || s.state != Survey.State.SCHEDULED) return false;
            if (s.startTask != null) s.startTask.cancel(false);
            s.state = Survey.State.CLOSED;
            s.cancelled = true;
            s.closedAt = Instant.now();
            history.remove(s);
            id = s.id;
        }
        fire(Event.CANCELLED, id);
        return true;
    }

    // ------------------------------------------------------------------ life-cycle

    private void startScheduled(long id) {
        Survey s;
        synchronized (lock) {
            s = find(id);
            if (s == null || s.state != Survey.State.SCHEDULED) return;
            startLocked(s);
        }
        fire(Event.STARTED, id);
        dispatchSurvey(s);
    }

    /** Freezes the participant list and arms the reminder / close timers. Caller holds the lock. */
    private void startLocked(Survey s) {
        s.state = Survey.State.RUNNING;
        s.startedAt = Instant.now();
        s.endsAt = s.startedAt.plus(surveyDuration);
        for (Member m : community.members()) {
            s.participants.put(m.id(), new Survey.Progress(m, s.questions.size()));
        }
        final long id = s.id;
        s.reminderTask = scheduler.schedule(() -> sendReminders(id), reminderAfter.toMillis(), TimeUnit.MILLISECONDS);
        s.closeTask = scheduler.schedule(() -> close(id, false), surveyDuration.toMillis(), TimeUnit.MILLISECONDS);
    }

    private void dispatchSurvey(Survey s) {
        List<Survey.Progress> targets;
        synchronized (lock) {
            targets = new ArrayList<>(s.participants.values());
        }
        for (Survey.Progress p : targets) {
            io.submit(() -> {
                int[] ids = messenger.sendSurvey(s.id, p.member, s.questions, surveyDuration);
                synchronized (lock) {
                    if (ids != null) p.messageIds = ids;
                }
            });
        }
    }

    private void sendReminders(long id) {
        List<Survey.Progress> due = new ArrayList<>();
        Survey s;
        Duration remaining;
        synchronized (lock) {
            s = find(id);
            if (s == null || s.state != Survey.State.RUNNING) return;
            for (Survey.Progress p : s.participants.values()) {
                if (!p.completed(s.questions.size()) && !p.reminded) {
                    p.reminded = true; // at most one reminder per user per survey
                    due.add(p);
                }
            }
            remaining = Duration.between(Instant.now(), s.endsAt);
        }
        final Duration left = remaining.isNegative() ? Duration.ZERO : remaining;
        for (Survey.Progress p : due) {
            int answered;
            synchronized (lock) {
                answered = p.answers.size();
            }
            io.submit(() -> messenger.sendReminder(p.member, answered, s.questions.size(), left));
        }
    }

    private void close(long id, boolean allCompleted) {
        Survey s;
        List<Survey.Progress> targets;
        synchronized (lock) {
            s = find(id);
            if (s == null || s.state != Survey.State.RUNNING) return;
            s.state = Survey.State.CLOSED;
            s.allCompleted = allCompleted;
            s.closedAt = Instant.now();
            if (s.reminderTask != null) s.reminderTask.cancel(false);
            if (s.closeTask != null) s.closeTask.cancel(false);
            targets = new ArrayList<>(s.participants.values());
        }
        fire(Event.CLOSED, id);
        for (Survey.Progress p : targets) {
            Map<Integer, Integer> answers;
            int[] ids;
            boolean done;
            synchronized (lock) {
                answers = new TreeMap<>(p.answers);
                ids = p.messageIds.clone();
                done = p.completed(s.questions.size());
            }
            io.submit(() -> messenger.sendClosed(p.member, s.questions, answers, ids, done, allCompleted));
        }
    }

    // ------------------------------------------------------------------ answers

    /** Records one answer. Each (user, question) can be answered once; nothing is accepted after close. */
    public AnswerOutcome recordAnswer(long surveyId, long userId, int question, int option) {
        AnswerOutcome outcome;
        boolean closeNow = false;
        synchronized (lock) {
            Survey s = find(surveyId);
            if (s == null) return AnswerOutcome.of(AnswerStatus.INVALID);
            if (s.state != Survey.State.RUNNING) return AnswerOutcome.of(AnswerStatus.SURVEY_CLOSED);
            Survey.Progress p = s.participants.get(userId);
            if (p == null) return AnswerOutcome.of(AnswerStatus.NOT_PARTICIPANT);
            if (question < 0 || question >= s.questions.size()
                    || option < 0 || option >= s.questions.get(question).options().size()) {
                return AnswerOutcome.of(AnswerStatus.INVALID);
            }
            int total = s.questions.size();
            Question q = s.questions.get(question);
            if (p.answers.containsKey(question)) {
                return new AnswerOutcome(AnswerStatus.ALREADY_ANSWERED, p.answers.size(), total,
                        p.completed(total), question + 1, q.text(), q.options().get(p.answers.get(question)));
            }
            p.answers.put(question, option);
            outcome = new AnswerOutcome(AnswerStatus.RECORDED, p.answers.size(), total, p.completed(total),
                    question + 1, q.text(), q.options().get(option));
            closeNow = s.everyoneCompleted();
        }
        fire(Event.PROGRESS, surveyId);
        if (closeNow) close(surveyId, true); // everybody answered EVERY question -> close immediately
        return outcome;
    }

    // ------------------------------------------------------------------ queries

    /** The scheduled/running survey, if any. */
    public SurveyView activeView() {
        synchronized (lock) {
            Survey s = active();
            return s == null ? null : view(s);
        }
    }

    /** The most recent survey (active or closed), or null. */
    public SurveyView latestView() {
        synchronized (lock) {
            return history.isEmpty() ? null : view(history.get(history.size() - 1));
        }
    }

    /** All finished surveys, newest first. */
    public List<SurveyView> closedViews() {
        synchronized (lock) {
            List<SurveyView> out = new ArrayList<>();
            for (int i = history.size() - 1; i >= 0; i--) {
                if (history.get(i).state == Survey.State.CLOSED) out.add(view(history.get(i)));
            }
            return out;
        }
    }

    public boolean hasActiveSurvey() {
        synchronized (lock) {
            return hasActive();
        }
    }

    /** True if this user is a participant of the currently running survey. */
    public boolean isParticipantOfRunning(long userId) {
        synchronized (lock) {
            Survey s = active();
            return s != null && s.state == Survey.State.RUNNING && s.participants.containsKey(userId);
        }
    }

    public void shutdown() {
        scheduler.shutdownNow();
        io.shutdown();
    }

    // ------------------------------------------------------------------ internals

    private boolean hasActive() {
        return active() != null;
    }

    private Survey active() {
        for (int i = history.size() - 1; i >= 0; i--) {
            if (history.get(i).state != Survey.State.CLOSED) return history.get(i);
        }
        return null;
    }

    private Survey find(long id) {
        for (Survey s : history) if (s.id == id) return s;
        return null;
    }

    private SurveyView view(Survey s) {
        List<SurveyView.ParticipantView> rows = new ArrayList<>();
        int[][] counts = new int[s.questions.size()][];
        for (int q = 0; q < counts.length; q++) counts[q] = new int[s.questions.get(q).options().size()];
        int completed = 0;
        for (Survey.Progress p : s.participants.values()) {
            rows.add(new SurveyView.ParticipantView(p.member.name(), p.member.usernameLabel(),
                    p.answers.size(), s.questions.size()));
            if (p.completed(s.questions.size())) completed++;
            p.answers.forEach((q, o) -> counts[q][o]++);
        }
        return new SurveyView(s.id, s.questions, SurveyView.State.valueOf(s.state.name()), s.allCompleted,
                s.scheduledFor, s.startedAt, s.endsAt, List.copyOf(rows), counts, rows.size(), completed);
    }

    private void fire(Event e, long id) {
        for (Listener l : listeners) l.onSurveyEvent(e, id);
    }

    private static java.util.concurrent.ThreadFactory daemon(String name) {
        return r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        };
    }
}
