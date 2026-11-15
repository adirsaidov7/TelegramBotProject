package survey;

import survey.core.CommunityService;
import survey.core.Member;
import survey.core.Messenger;
import survey.core.Question;
import survey.core.SurveyService;
import survey.core.SurveyView;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Demo mode (--demo): lets you try the whole Swing console without Telegram.
 * Four fake members are created and, while a survey runs, they answer at random every few seconds.
 */
public final class DemoMode {

    private static final String[][] PEOPLE = {{"דני כהן", "danny"}, {"יעל לוי", "yael"}, {"אורי ישראלי", "uri"}, {"מיכל אברהם", null}};
    public static final long FIRST_ID = 1000;

    private DemoMode() {}

    /** Messenger that sends nothing. */
    public static final class Silent implements Messenger {
        @Override public int[] sendSurvey(long id, Member m, List<Question> q, Duration d) { return new int[q.size()]; }
        @Override public void sendReminder(Member m, int a, int t, Duration r) { System.out.println("[demo] reminder -> " + m.name()); }
        @Override public void sendClosed(Member m, List<Question> q, Map<Integer, Integer> a, int[] ids, boolean c, boolean all) { }
    }

    public static void start(CommunityService community, SurveyService surveys) {
        long id = FIRST_ID;
        for (String[] p : PEOPLE) community.join(id++, p[0], p[1]);

        Random rnd = new Random();
        surveys.addListener((event, surveyId) -> {
            if (event != SurveyService.Event.STARTED) return;
            Thread t = new Thread(() -> {
                try {
                    while (true) {
                        Thread.sleep(2500);
                        SurveyView v = surveys.activeView();
                        if (v == null || v.id() != surveyId) return;
                        long user = FIRST_ID + rnd.nextInt(PEOPLE.length);
                        int q = rnd.nextInt(v.questions().size());
                        surveys.recordAnswer(surveyId, user, q, rnd.nextInt(v.questions().get(q).options().size()));
                    }
                } catch (InterruptedException ignored) {
                    // stop
                }
            }, "demo-users");
            t.setDaemon(true);
            t.start();
        });
    }
}
