package survey.core;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/** Tests of the rules in the assignment, with a fake messenger (no Telegram needed). Timings are shortened. */
class SurveyServiceTest {

    /** Records everything the service asks the bot to send. */
    static final class FakeMessenger implements Messenger {
        final List<Long> surveyRecipients = new CopyOnWriteArrayList<>();
        final List<Long> reminded = new CopyOnWriteArrayList<>();
        final List<Long> closedNotes = new CopyOnWriteArrayList<>();

        @Override public int[] sendSurvey(long id, Member m, List<Question> q, Duration d) {
            surveyRecipients.add(m.id());
            return new int[q.size()];
        }
        @Override public void sendReminder(Member m, int answered, int total, Duration remaining) { reminded.add(m.id()); }
        @Override public void sendClosed(Member m, List<Question> q, Map<Integer, Integer> a, int[] ids, boolean c, boolean all) {
            closedNotes.add(m.id());
        }
    }

    private final CommunityService community = new CommunityService(null);
    private final FakeMessenger bot = new FakeMessenger();

    private static final List<Question> TWO_QUESTIONS = List.of(
            new Question("Q1?", List.of("a", "b", "c")),
            new Question("Q2?", List.of("x", "y")));

    private void join(long... ids) {
        for (long id : ids) community.join(id, "user" + id, id % 2 == 0 ? null : "u" + id);
    }

    private SurveyService service(long surveyMs, long reminderMs) {
        return new SurveyService(community, bot, Duration.ofMillis(surveyMs), Duration.ofMillis(reminderMs));
    }

    private static void await(BooleanSupplier cond) throws InterruptedException {
        long end = System.currentTimeMillis() + 4000;
        while (!cond.getAsBoolean()) {
            if (System.currentTimeMillis() > end) throw new AssertionError("condition not reached in time");
            Thread.sleep(20);
        }
    }

    // ---------------------------------------------------------------- community

    @Test
    void joiningTwiceDoesNotDuplicateAndSizeIsReported() {
        assertTrue(community.join(1, "Dani", "danny").isPresent());
        assertTrue(community.join(1, "Dani", "danny").isEmpty());
        assertEquals(1, community.size());
        community.join(2, "Yael", null);
        assertEquals("—", community.members().get(1).usernameLabel());
        assertEquals("@danny", community.members().get(0).usernameLabel());
    }

    @Test
    void communityListenerGetsNewSize() {
        int[] seen = {0};
        community.addListener((m, size) -> seen[0] = size);
        join(1, 2, 3);
        assertEquals(3, seen[0]);
    }

    // ---------------------------------------------------------------- creation rules

    @Test
    void surveyCannotStartWithFewerThanThreeMembers() {
        join(1, 2);
        SurveyService s = service(5000, 3000);
        assertThrows(IllegalStateException.class, () -> s.create(TWO_QUESTIONS, Duration.ZERO));
        assertFalse(s.hasActiveSurvey());
        assertTrue(bot.surveyRecipients.isEmpty());
    }

    @Test
    void onlyOneActiveSurveyAtATime() {
        join(1, 2, 3);
        SurveyService s = service(5000, 3000);
        s.create(TWO_QUESTIONS, Duration.ZERO);
        assertThrows(IllegalStateException.class, () -> s.create(TWO_QUESTIONS, Duration.ZERO));
        assertThrows(IllegalStateException.class, () -> s.create(TWO_QUESTIONS, Duration.ofMinutes(1)));
    }

    @Test
    void questionValidation() {
        assertThrows(IllegalArgumentException.class, () -> SurveyService.validate(List.of()));
        assertThrows(IllegalArgumentException.class, () -> SurveyService.validate(List.of(
                new Question("1", List.of("a", "b")), new Question("2", List.of("a", "b")),
                new Question("3", List.of("a", "b")), new Question("4", List.of("a", "b")))));
        assertThrows(IllegalArgumentException.class, () -> SurveyService.validate(List.of(new Question("q", List.of("only one")))));
        assertThrows(IllegalArgumentException.class, () -> SurveyService.validate(List.of(new Question("q", List.of("1", "2", "3", "4", "5")))));
        assertThrows(IllegalArgumentException.class, () -> SurveyService.validate(List.of(new Question(" ", List.of("a", "b")))));
        assertThrows(IllegalArgumentException.class, () -> SurveyService.validate(List.of(new Question("q", List.of("same", "SAME")))));
        SurveyService.validate(TWO_QUESTIONS); // valid -> no exception
    }

    // ---------------------------------------------------------------- participants

    @Test
    void participantsAreFrozenAtStartAndLateJoinersAreExcluded() throws Exception {
        join(1, 2, 3);
        SurveyService s = service(5000, 3000);
        long id = s.create(TWO_QUESTIONS, Duration.ZERO).id();
        join(4); // joins while the survey is running
        assertEquals(3, s.activeView().participantsCount());
        assertEquals(SurveyService.AnswerStatus.NOT_PARTICIPANT, s.recordAnswer(id, 4, 0, 0).status());
        assertEquals(4, community.size());
        await(() -> bot.surveyRecipients.size() == 3);
        assertFalse(bot.surveyRecipients.contains(4L));
    }

    @Test
    void delayedSurveyFreezesParticipantsWhenItActuallyStarts() throws Exception {
        join(1, 2, 3);
        SurveyService s = service(5000, 3000);
        SurveyView scheduled = s.create(TWO_QUESTIONS, Duration.ofMillis(300));
        assertEquals(SurveyView.State.SCHEDULED, scheduled.state());
        assertEquals(0, scheduled.participantsCount());
        join(4); // joins during the delay -> member at start time -> participant
        await(() -> s.activeView().state() == SurveyView.State.RUNNING);
        assertEquals(4, s.activeView().participantsCount());
    }

    @Test
    void cancelScheduledFreesTheSlot() {
        join(1, 2, 3);
        SurveyService s = service(5000, 3000);
        s.create(TWO_QUESTIONS, Duration.ofMinutes(10));
        assertTrue(s.cancelScheduled());
        assertFalse(s.hasActiveSurvey());
        s.create(TWO_QUESTIONS, Duration.ZERO); // allowed again
    }

    // ---------------------------------------------------------------- answers

    @Test
    void oneAnswerPerQuestionAndStateIsPerSurvey() {
        join(1, 2, 3);
        SurveyService s = service(5000, 3000);
        long id = s.create(TWO_QUESTIONS, Duration.ZERO).id();
        assertEquals(SurveyService.AnswerStatus.RECORDED, s.recordAnswer(id, 1, 0, 2).status());
        assertEquals(SurveyService.AnswerStatus.ALREADY_ANSWERED, s.recordAnswer(id, 1, 0, 1).status());
        assertEquals(SurveyService.AnswerStatus.INVALID, s.recordAnswer(id, 1, 5, 0).status());
        assertEquals(SurveyService.AnswerStatus.INVALID, s.recordAnswer(id, 1, 1, 7).status());
        SurveyView v = s.activeView();
        assertEquals(1, v.counts()[0][2]);
        assertEquals(0, v.counts()[0][1]); // the rejected second answer did not count
        assertEquals(1, v.votesFor(0));
        assertEquals(0, v.completedCount());
        assertEquals(SurveyView.Status.IN_PROGRESS, v.participants().get(0).status());
        assertEquals(SurveyView.Status.NOT_STARTED, v.participants().get(1).status());
    }

    @Test
    void closesEarlyOnlyWhenEveryoneAnsweredEveryQuestion() throws Exception {
        join(1, 2, 3);
        SurveyService s = service(10_000, 5000);
        long id = s.create(TWO_QUESTIONS, Duration.ZERO).id();
        for (long u : new long[]{1, 2, 3}) s.recordAnswer(id, u, 0, 0); // everybody answered Q1 only
        assertTrue(s.hasActiveSurvey());
        s.recordAnswer(id, 1, 1, 0);
        s.recordAnswer(id, 2, 1, 1);
        assertTrue(s.hasActiveSurvey()); // user 3 still missing Q2
        s.recordAnswer(id, 3, 1, 0);
        assertFalse(s.hasActiveSurvey());
        SurveyView v = s.latestView();
        assertEquals(SurveyView.State.CLOSED, v.state());
        assertTrue(v.allCompleted());
        assertEquals(3, v.completedCount());
        await(() -> bot.closedNotes.size() == 3);
    }

    @Test
    void timeoutClosesSurveyAndRejectsLateAnswers() throws Exception {
        join(1, 2, 3);
        SurveyService s = service(300, 150);
        long id = s.create(TWO_QUESTIONS, Duration.ZERO).id();
        s.recordAnswer(id, 1, 0, 0);
        await(() -> !s.hasActiveSurvey());
        assertFalse(s.latestView().allCompleted());
        assertEquals(SurveyService.AnswerStatus.SURVEY_CLOSED, s.recordAnswer(id, 2, 0, 0).status());
        assertEquals(1, s.latestView().votesFor(0));
        assertEquals(3, community.size()); // the community is untouched by the end of the survey
        s.create(TWO_QUESTIONS, Duration.ZERO); // a new survey may start now
    }

    // ---------------------------------------------------------------- reminders

    @Test
    void reminderGoesOnlyToUnfinishedUsersAndOnlyOnce() throws Exception {
        join(1, 2, 3);
        SurveyService s = service(900, 200);
        long id = s.create(TWO_QUESTIONS, Duration.ZERO).id();
        s.recordAnswer(id, 1, 0, 0);
        s.recordAnswer(id, 1, 1, 0); // user 1 finished
        s.recordAnswer(id, 2, 0, 0); // user 2 partial, user 3 nothing
        await(() -> bot.reminded.size() >= 2);
        await(() -> !s.hasActiveSurvey());
        assertEquals(2, bot.reminded.size());
        assertTrue(bot.reminded.contains(2L));
        assertTrue(bot.reminded.contains(3L));
        assertFalse(bot.reminded.contains(1L));
    }

    @Test
    void noRemindersIfSurveyClosedEarly() throws Exception {
        join(1, 2, 3);
        SurveyService s = service(2000, 400);
        long id = s.create(TWO_QUESTIONS, Duration.ZERO).id();
        for (long u : new long[]{1, 2, 3}) {
            s.recordAnswer(id, u, 0, 0);
            s.recordAnswer(id, u, 1, 0);
        }
        assertFalse(s.hasActiveSurvey());
        Thread.sleep(700); // well past the reminder time
        assertTrue(bot.reminded.isEmpty());
    }

    // ---------------------------------------------------------------- history

    @Test
    void closedSurveysAreKeptNewestFirst() {
        join(1, 2, 3);
        SurveyService s = service(5000, 3000);
        for (int i = 0; i < 2; i++) {
            long id = s.create(TWO_QUESTIONS, Duration.ZERO).id();
            for (long u : new long[]{1, 2, 3}) {
                s.recordAnswer(id, u, 0, 0);
                s.recordAnswer(id, u, 1, 1);
            }
        }
        List<SurveyView> closed = s.closedViews();
        assertEquals(2, closed.size());
        assertTrue(closed.get(0).id() > closed.get(1).id());
    }
}
