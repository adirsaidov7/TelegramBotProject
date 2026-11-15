package survey.core;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * The outgoing side of the bot, as seen by {@link SurveyService}.
 * Keeping it an interface lets the core logic be tested without Telegram.
 * All methods are called from a background thread (never the Swing EDT).
 */
public interface Messenger {

    /** Delivers the whole survey to one participant. Returns the message id of each question (0 = failed). */
    int[] sendSurvey(long surveyId, Member member, List<Question> questions, Duration timeLimit);

    /** The single reminder for a participant who has not finished. */
    void sendReminder(Member member, int answered, int total, Duration remaining);

    /**
     * Tells a participant the survey is over: disables unanswered questions and sends a closing note.
     *
     * @param answers map question index -> chosen option index
     */
    void sendClosed(Member member, List<Question> questions, Map<Integer, Integer> answers,
                    int[] messageIds, boolean completed, boolean allCompleted);
}
