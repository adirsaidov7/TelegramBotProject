package survey.core;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ScheduledFuture;

/**
 * Mutable internal state of ONE survey. Only {@link SurveyService} touches it (under its lock).
 * Answer state lives here, per participant - never on the {@link Member}.
 */
final class Survey {

    enum State { SCHEDULED, RUNNING, CLOSED }

    /** A participant's progress inside this survey only. */
    static final class Progress {
        final Member member;
        final Map<Integer, Integer> answers = new TreeMap<>(); // question -> option
        boolean reminded;
        int[] messageIds;

        Progress(Member member, int questions) {
            this.member = member;
            this.messageIds = new int[questions];
        }

        boolean completed(int totalQuestions) {
            return answers.size() >= totalQuestions;
        }
    }

    final long id;
    final List<Question> questions;
    State state;
    boolean allCompleted;      // closed early because everybody finished
    boolean cancelled;
    Instant scheduledFor;      // when the survey is (was) due to be sent
    Instant startedAt;
    Instant endsAt;
    Instant closedAt;
    /** The participants - frozen at the moment the survey starts. */
    final Map<Long, Progress> participants = new LinkedHashMap<>();
    ScheduledFuture<?> startTask, reminderTask, closeTask;

    Survey(long id, List<Question> questions) {
        this.id = id;
        this.questions = List.copyOf(questions);
    }

    boolean everyoneCompleted() {
        return !participants.isEmpty()
                && participants.values().stream().allMatch(p -> p.completed(questions.size()));
    }
}
