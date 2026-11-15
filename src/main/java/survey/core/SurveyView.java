package survey.core;

import java.time.Instant;
import java.util.List;

/** Immutable, thread-safe snapshot of a survey, handed to the UI. */
public record SurveyView(
        long id,
        List<Question> questions,
        State state,
        boolean allCompleted,
        Instant scheduledFor,
        Instant startedAt,
        Instant endsAt,
        List<ParticipantView> participants,
        /** counts[q][o] = number of votes for option o of question q */
        int[][] counts,
        int participantsCount,
        int completedCount) {

    public enum State { SCHEDULED, RUNNING, CLOSED }

    public enum Status { NOT_STARTED, IN_PROGRESS, COMPLETED }

    public record ParticipantView(String name, String username, int answered, int total) {
        public Status status() {
            if (answered >= total) return Status.COMPLETED;
            return answered == 0 ? Status.NOT_STARTED : Status.IN_PROGRESS;
        }
    }

    public boolean active() {
        return state != State.CLOSED;
    }

    public int notCompletedCount() {
        return participantsCount - completedCount;
    }

    /** Total votes cast for question q. */
    public int votesFor(int q) {
        int sum = 0;
        for (int c : counts[q]) sum += c;
        return sum;
    }
}
