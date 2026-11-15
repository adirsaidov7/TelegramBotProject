package survey.ui;

import com.formdev.flatlaf.FlatLightLaf;
import survey.ai.OpenAiSurveyGenerator;
import survey.core.CommunityService;
import survey.core.SurveyService;
import survey.core.SurveyService.Event;

import javax.swing.*;
import java.awt.*;

/** The operator's console: community on one side, survey tools (create / live / results) on the other. */
public final class MainFrame extends JFrame {

    private final SurveyService surveys;
    private final JTabbedPane tabs = new JTabbedPane();
    private final JLabel status = new JLabel(" ");
    private boolean pendingStartNotice; // true when the running survey was a delayed one

    private static final int TAB_CREATE = 0, TAB_LIVE = 1, TAB_RESULTS = 2;

    public static void installLookAndFeel() {
        try {
            UIManager.put("defaultFont", new Font("Segoe UI", Font.PLAIN, 14));
            UIManager.put("Button.arc", 12);
            UIManager.put("Component.arc", 10);
            UIManager.put("TextComponent.arc", 10);
            UIManager.put("ProgressBar.arc", 10);
            UIManager.put("TabbedPane.tabHeight", 40);
            UIManager.put("OptionPane.okButtonText", "אישור");
            UIManager.put("OptionPane.yesButtonText", "כן");
            UIManager.put("OptionPane.noButtonText", "לא");
            UIManager.put("OptionPane.cancelButtonText", "ביטול");
            FlatLightLaf.setup();
        } catch (Exception e) {
            System.err.println("Could not install FlatLaf: " + e.getMessage());
        }
    }

    public MainFrame(CommunityService community, SurveyService surveys, OpenAiSurveyGenerator ai,
                     String botUsername, String botStatus) {
        super("מערכת סקרים לקהילת טלגרם");
        this.surveys = surveys;
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(1180, 760);
        setMinimumSize(new Dimension(980, 640));
        setLocationRelativeTo(null);

        JPanel root = new JPanel(new BorderLayout(0, 12));
        root.setBackground(Ui.PAGE);
        root.setBorder(BorderFactory.createEmptyBorder(14, 16, 8, 16));

        // header
        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        header.add(Ui.label("📊 ניהול סקרים לקהילת טלגרם", 22, true, null), BorderLayout.LINE_START);
        if (botUsername != null) {
            header.add(Ui.label("הבוט: " + Ui.ltr("@" + botUsername), 13, false, Ui.MUTED), BorderLayout.LINE_END);
        }
        root.add(header, BorderLayout.NORTH);

        // body: community (always visible) | survey tools
        CommunityPanel communityPanel = new CommunityPanel(community, botUsername);
        communityPanel.setMinimumSize(new Dimension(330, 200));
        LiveSurveyPanel live = new LiveSurveyPanel(surveys, () -> tabs.setSelectedIndex(TAB_RESULTS));
        tabs.addTab("✏ יצירת סקר", pad(new CreateSurveyPanel(surveys, community, ai)));
        tabs.addTab("📡 סקר פעיל", pad(live));
        tabs.addTab("📈 תוצאות", pad(new ResultsPanel(surveys)));
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, communityPanel, tabs);
        split.setResizeWeight(0.34);
        split.setBorder(null);
        split.setOpaque(false);
        split.setDividerSize(10);
        root.add(split, BorderLayout.CENTER);

        // status bar
        status.setForeground(Ui.MUTED);
        status.setText(botStatus);
        root.add(status, BorderLayout.SOUTH);

        setContentPane(root);
        Ui.rtl(this);
        surveys.addListener((e, id) -> SwingUtilities.invokeLater(() -> onSurveyEvent(e, id)));
    }

    private static JComponent pad(JComponent c) {
        JPanel p = new JPanel(new BorderLayout());
        p.setBorder(BorderFactory.createEmptyBorder(12, 4, 4, 4));
        p.setOpaque(false);
        p.add(c);
        return p;
    }

    /** Moves the operator to the relevant tab and confirms the important transitions clearly. */
    private void onSurveyEvent(Event e, long id) {
        switch (e) {
            case SCHEDULED -> {
                pendingStartNotice = true;
                tabs.setSelectedIndex(TAB_LIVE);
                status.setText("⏱ הסקר תוזמן - ממתין לשליחה.");
            }
            case STARTED -> {
                tabs.setSelectedIndex(TAB_LIVE);
                var v = surveys.activeView();
                int n = v == null ? 0 : v.participantsCount();
                status.setText("✅ הסקר נשלח ל-" + n + " משתתפים (" + Ui.hms(java.time.Instant.now()) + ").");
                if (pendingStartNotice) { // the countdown just finished - say so explicitly
                    pendingStartNotice = false;
                    Ui.message(this, "הסקר נשלח", "הספירה לאחור הסתיימה, והסקר <b>נשלח</b> ל-" + n
                            + " משתתפים.<br>אפשר לעקוב אחר ההתקדמות בלשונית \"סקר פעיל\".", JOptionPane.INFORMATION_MESSAGE);
                }
            }
            case CANCELLED -> {
                pendingStartNotice = false;
                tabs.setSelectedIndex(TAB_CREATE);
                status.setText("הסקר המתוזמן בוטל.");
            }
            case CLOSED -> {
                tabs.setSelectedIndex(TAB_RESULTS);
                var last = surveys.latestView();
                status.setText("🏁 הסקר נסגר" + (last != null && last.allCompleted()
                        ? " - כל המשתתפים השלימו." : " - הזמן הסתיים.") + " התוצאות מוצגות בלשונית \"תוצאות\".");
            }
            default -> { }
        }
    }
}
