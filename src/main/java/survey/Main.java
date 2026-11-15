package survey;

import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;
import survey.ai.OpenAiSurveyGenerator;
import survey.core.CommunityService;
import survey.core.SurveyService;
import survey.telegram.PollBot;
import survey.ui.MainFrame;
import survey.util.Config;

import javax.swing.*;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * Entry point.
 * <pre>
 *   java -jar survey-bot.jar            real mode (needs a bot token)
 *   java -jar survey-bot.jar --demo     no Telegram: fake members who answer by themselves
 * </pre>
 */
public final class Main {

    public static void main(String[] args) {
        boolean demo = Arrays.asList(args).contains("--demo");
        Config config = new Config();
        OpenAiSurveyGenerator ai = new OpenAiSurveyGenerator(config.openAiKey(), config.openAiModel());

        // The bot needs the survey service and vice versa -> late-bound messenger.
        LateMessenger messenger = new LateMessenger();
        CommunityService community = new CommunityService(demo ? null : Path.of("community.txt"));
        SurveyService surveys = new SurveyService(community, messenger);

        String botUsername = null;
        String botStatus;
        if (demo) {
            messenger.target = new DemoMode.Silent();
            DemoMode.start(community, surveys);
            botStatus = "🧪 מצב הדגמה - אין חיבור לטלגרם; משתמשים מדומים עונים באופן אוטומטי.";
        } else {
            String token = config.telegramToken();
            if (token == null) {
                fail("לא הוגדר טוקן לבוט טלגרם (המאפיין telegram.token בקובץ pom.xml).");
                return;
            }
            try {
                PollBot bot = new PollBot(token, null, community, surveys);
                botUsername = bot.fetchUsername(); // also validates the token
                messenger.target = bot;
                new TelegramBotsApi(DefaultBotSession.class).registerBot(bot);
                botStatus = "🟢 הבוט מחובר ופעיל.";
            } catch (TelegramApiException e) {
                fail("ההתחברות לטלגרם נכשלה:\n" + e.getMessage() + "\n\nבדקו את הטוקן ואת החיבור לאינטרנט.");
                return;
            }
        }

        final String username = botUsername;
        final String status = botStatus;
        SwingUtilities.invokeLater(() -> {
            MainFrame.installLookAndFeel();
            new MainFrame(community, surveys, ai, username, status).setVisible(true);
        });
    }

    private static void fail(String message) {
        System.err.println(message);
        try {
            SwingUtilities.invokeAndWait(() -> JOptionPane.showMessageDialog(null, message, "שגיאת הפעלה", JOptionPane.ERROR_MESSAGE));
        } catch (Exception ignored) {
            // headless - the message was already printed
        }
        System.exit(1);
    }
}
