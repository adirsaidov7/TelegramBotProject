package survey.telegram;

import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import survey.core.CommunityService;
import survey.core.Member;
import survey.core.Messenger;
import survey.core.Question;
import survey.core.SurveyService;
import survey.core.SurveyService.AnswerOutcome;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The Telegram side. Users join with /start, "Hi" or "היי"; surveys are delivered as ordinary
 * messages with inline answer buttons (one message per question).
 */
public class PollBot extends TelegramLongPollingBot implements Messenger {

    private volatile String username;
    private final CommunityService community;
    private final SurveyService surveys;
    private final ExecutorService broadcastPool = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "broadcast");
        t.setDaemon(true);
        return t;
    });

    public PollBot(String token, String username, CommunityService community, SurveyService surveys) {
        super(token);
        this.username = username == null ? "SurveyBot" : username;
        this.community = community;
        this.surveys = surveys;
    }

    @Override
    public String getBotUsername() {
        return username;
    }

    /** Asks Telegram who we are. Also proves the token is valid. */
    public String fetchUsername() throws TelegramApiException {
        username = execute(new org.telegram.telegrambots.meta.api.methods.GetMe()).getUserName();
        return username;
    }

    // ================================================================== incoming

    @Override
    public void onUpdateReceived(Update update) {
        try {
            if (update.hasCallbackQuery()) {
                onAnswerClicked(update.getCallbackQuery());
            } else if (update.hasMessage() && update.getMessage().hasText()) {
                onText(update.getMessage());
            }
        } catch (Exception e) {
            System.err.println("Error handling update: " + e);
        }
    }

    private void onText(Message msg) throws TelegramApiException {
        if (!msg.getChat().isUserChat() || msg.getFrom() == null) return;
        String text = msg.getText().trim();
        long chatId = msg.getChatId();
        if (isJoinRequest(text)) {
            join(msg.getFrom(), chatId);
        } else if (community.isMember(msg.getFrom().getId())) {
            say(chatId, surveys.isParticipantOfRunning(msg.getFrom().getId())
                    ? "📊 יש לך סקר פתוח - לחצו על כפתורי התשובה שמתחת לשאלות."
                    : "👋 אתם חלק מהקהילה! כשיתחיל סקר חדש הוא יגיע לכאן אוטומטית.");
        } else {
            say(chatId, "👋 שלום! כדי להצטרף לקהילה שלחו <b>היי</b> או <b>Hi</b>, או לחצו על Start.");
        }
    }

    /** Exactly: /start, Hi (any case), היי. Any other text never joins. */
    static boolean isJoinRequest(String text) {
        return text.equals("/start") || text.startsWith("/start ") || text.startsWith("/start@")
                || text.equalsIgnoreCase("hi") || text.equals("היי");
    }

    private void join(User user, long chatId) throws TelegramApiException {
        String name = displayName(user);
        Optional<Member> created = community.join(user.getId(), name, user.getUserName());
        if (created.isEmpty()) {
            say(chatId, "😊 אתם כבר חברים בקהילה, אין צורך להצטרף שוב. נעדכן אתכם כשיתחיל סקר חדש.");
            return;
        }
        int size = community.size();
        say(chatId, "🎉 <b>ברוכים הבאים, " + esc(name) + "!</b>\nהצטרפתם לקהילה. כרגע יש בה <b>" + size
                + "</b> חברים.\nסקרים חדשים יגיעו אליכם לכאן אוטומטית.");
        // tell everybody else (in the background, so the join handler stays fast)
        String note = "👤 <b>" + esc(name) + "</b> הצטרף/ה לקהילה!\nגודל הקהילה כעת: <b>" + size + "</b>";
        for (Member other : community.members()) {
            if (other.id() == user.getId()) continue;
            broadcastPool.submit(() -> trySay(other.id(), note));
        }
    }

    private void onAnswerClicked(CallbackQuery cq) throws TelegramApiException {
        String[] parts = cq.getData() == null ? new String[0] : cq.getData().split(":");
        if (parts.length != 4 || !parts[0].equals("v")) {
            toast(cq, "פעולה לא מוכרת", false);
            return;
        }
        long surveyId = Long.parseLong(parts[1]);
        int q = Integer.parseInt(parts[2]);
        int o = Integer.parseInt(parts[3]);
        AnswerOutcome r = surveys.recordAnswer(surveyId, cq.getFrom().getId(), q, o);
        long chatId = cq.getMessage().getChatId();
        int messageId = cq.getMessage().getMessageId();

        switch (r.status()) {
            case RECORDED -> {
                toast(cq, "✅ התשובה נשמרה (" + r.answered() + "/" + r.total() + ")", false);
                edit(chatId, messageId, questionText(r.questionNumber(), r.total(), r.questionText())
                        + "\n\n✅ <b>התשובה שלך:</b> " + esc(r.optionText()), null);
                if (r.completed()) {
                    say(chatId, "🎉 <b>תודה!</b> ענית על כל השאלות. נעדכן כשהסקר ייסגר.");
                }
            }
            case ALREADY_ANSWERED -> toast(cq, "כבר ענית על השאלה הזו - לא ניתן לשנות תשובה.", true);
            case SURVEY_CLOSED -> {
                toast(cq, "⏰ הסקר כבר נסגר ולא ניתן לענות עוד.", true);
                String oldText = cq.getMessage() instanceof Message m && m.getText() != null ? m.getText() : "";
                edit(chatId, messageId, esc(oldText) + "\n\n⏰ <b>הסקר נסגר</b>", null);            }
            case NOT_PARTICIPANT -> toast(cq, "לא נכללת בסקר הזה (הצטרפת אחרי שהתחיל). תוכלו להשתתף בסקר הבא!", true);
            default -> toast(cq, "משהו השתבש, נסו שוב.", true);
        }
    }

    // ================================================================== outgoing (Messenger)

    @Override
    public int[] sendSurvey(long surveyId, Member member, List<Question> questions, Duration timeLimit) {
        int[] ids = new int[questions.size()];
        try {
            say(member.id(), "📊 <b>סקר חדש!</b>\nהסקר כולל <b>" + questions.size() + "</b> "
                    + (questions.size() == 1 ? "שאלה" : "שאלות") + " ואפשר לענות עליו במשך <b>"
                    + timeLimit.toMinutes() + " דקות</b>.\nבחרו תשובה אחת בכל שאלה (לא ניתן לשנות אחרי הבחירה) 👇");
            for (int q = 0; q < questions.size(); q++) {
                Message sent = execute(SendMessage.builder()
                        .chatId(String.valueOf(member.id()))
                        .text(questionText(q + 1, questions.size(), questions.get(q).text()))
                        .parseMode("HTML")
                        .replyMarkup(keyboard(surveyId, q, questions.get(q)))
                        .build());
                ids[q] = sent.getMessageId();
            }
        } catch (TelegramApiException e) {
            System.err.println("Could not deliver survey to " + member.name() + ": " + e.getMessage());
        }
        return ids;
    }

    @Override
    public void sendReminder(Member member, int answered, int total, Duration remaining) {
        long mins = Math.max(1, Math.round(remaining.toSeconds() / 60.0));
        String progress = answered == 0 ? "עוד לא התחלתם לענות" : "ענית על " + answered + " מתוך " + total + " שאלות";
        trySay(member.id(), "⏰ <b>תזכורת:</b> הסקר עדיין פתוח - נשארו כ-" + mins + " דקות.\n" + progress
                + ". גללו למעלה ולחצו על התשובות 👆");
    }

    @Override
    public void sendClosed(Member member, List<Question> questions, Map<Integer, Integer> answers,
                           int[] messageIds, boolean completed, boolean allCompleted) {
        // switch off the buttons of unanswered questions so the closed state is obvious
        for (int q = 0; q < questions.size(); q++) {
            if (answers.containsKey(q) || q >= messageIds.length || messageIds[q] == 0) continue;
            try {
                edit(member.id(), messageIds[q], questionText(q + 1, questions.size(), questions.get(q).text())
                        + "\n\n⏰ <b>הסקר נסגר</b>", null);
            } catch (TelegramApiException ignored) {
                // message may have been deleted by the user - nothing to do
            }
        }
        if (completed) {
            trySay(member.id(), "🏁 הסקר נסגר. תודה שהשתתפתם!");
        } else {
            trySay(member.id(), "🏁 הסקר נסגר" + (answers.isEmpty() ? " ולא נקלטו תשובות שלכם." : ". נקלטו " + answers.size()
                    + " מתוך " + questions.size() + " תשובות שלכם.") + " נשמח לראותכם בסקר הבא!");
        }
    }

    // ================================================================== helpers

    private static InlineKeyboardMarkup keyboard(long surveyId, int q, Question question) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (int o = 0; o < question.options().size(); o++) {
            rows.add(List.of(InlineKeyboardButton.builder()
                    .text(question.options().get(o))
                    .callbackData("v:" + surveyId + ":" + q + ":" + o)
                    .build()));
        }
        return InlineKeyboardMarkup.builder().keyboard(rows).build();
    }

    private static String questionText(int number, int total, String text) {
        return "<b>שאלה " + number + " מתוך " + total + "</b>\n" + esc(text);
    }

    private void say(long chatId, String html) throws TelegramApiException {
        execute(SendMessage.builder().chatId(String.valueOf(chatId)).text(html).parseMode("HTML").build());
    }

    private void trySay(long chatId, String html) {
        try {
            say(chatId, html);
        } catch (TelegramApiException e) {
            System.err.println("Could not message " + chatId + ": " + e.getMessage()); // e.g. user blocked the bot
        }
    }

    private void edit(long chatId, int messageId, String html, InlineKeyboardMarkup keyboard) throws TelegramApiException {
        execute(EditMessageText.builder().chatId(String.valueOf(chatId)).messageId(messageId)
                .text(html).parseMode("HTML").replyMarkup(keyboard).build());
    }

    private void toast(CallbackQuery cq, String text, boolean alert) throws TelegramApiException {
        execute(AnswerCallbackQuery.builder().callbackQueryId(cq.getId()).text(text).showAlert(alert).build());
    }

    private static String displayName(User u) {
        String n = ((u.getFirstName() == null ? "" : u.getFirstName()) + " "
                + (u.getLastName() == null ? "" : u.getLastName())).trim();
        if (n.isEmpty()) n = u.getUserName() != null ? u.getUserName() : "משתמש";
        return n;
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
