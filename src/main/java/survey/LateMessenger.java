package survey;

import survey.core.Member;
import survey.core.Messenger;
import survey.core.Question;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/** Breaks the construction cycle bot -> SurveyService -> bot: the real target is set after both exist. */
final class LateMessenger implements Messenger {
    volatile Messenger target;

    @Override public int[] sendSurvey(long id, Member m, List<Question> q, Duration d) { return target.sendSurvey(id, m, q, d); }

    @Override public void sendReminder(Member m, int a, int t, Duration r) { target.sendReminder(m, a, t, r); }

    @Override public void sendClosed(Member m, List<Question> q, Map<Integer, Integer> a, int[] ids, boolean c, boolean all) {
        target.sendClosed(m, q, a, ids, c, all);
    }
}
