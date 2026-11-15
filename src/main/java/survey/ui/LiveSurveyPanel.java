package survey.ui;

import survey.core.SurveyService;
import survey.core.SurveyView;
import survey.core.SurveyView.ParticipantView;
import survey.core.SurveyView.Status;

import javax.swing.*;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.time.Duration;
import java.time.Instant;

/**
 * The CURRENT survey: live countdown, participation stats and per-participant progress.
 * Completely separate from the global community list.
 */
final class LiveSurveyPanel extends JPanel {

    private final SurveyService surveys;
    private final Runnable showResults;
    private final CardLayout cards = new CardLayout();

    // idle card
    private final JLabel idleTitle = Ui.label("אין סקר פעיל כרגע", 20, true, Ui.MUTED);
    private final JLabel idleHint = Ui.label(" ", 14, false, Ui.MUTED);
    private final JButton resultsBtn = new JButton("📈 הצג את תוצאות הסקר האחרון");

    // live card
    private final JLabel chip = new JLabel();
    private final JLabel caption = Ui.label(" ", 14, false, Ui.MUTED);
    private final JLabel clock = Ui.label("--:--", 40, true, Ui.PRIMARY);
    private final JLabel sentBanner = Ui.banner();
    private final JButton cancelBtn = new JButton("ביטול התזמון");
    private final JLabel participantsStat = Ui.label("—", 26, true, null);
    private final JLabel doneStat = Ui.label("—", 26, true, Ui.SUCCESS);
    private final JLabel pendingStat = Ui.label("—", 26, true, Ui.WARN);
    private final JLabel tableHint = Ui.label(" ", 13, false, Ui.MUTED);
    private final DefaultTableModel model = new DefaultTableModel(new Object[]{"שם", "התקדמות", "מצב"}, 0) {
        @Override public boolean isCellEditable(int r, int c) { return false; }
    };

    private SurveyView current; // EDT only
    private final Timer ticker = new Timer(250, e -> tick());

    LiveSurveyPanel(SurveyService surveys, Runnable showResults) {
        super(new CardLayout());
        this.surveys = surveys;
        this.showResults = showResults;
        setOpaque(false);
        setLayout(cards);
        add(buildIdle(), "idle");
        add(buildLive(), "live");
        surveys.addListener((e, id) -> SwingUtilities.invokeLater(this::refresh));
        ticker.start();
        refresh();
    }

    // ================================================================== layout

    private JComponent buildIdle() {
        JPanel box = new JPanel();
        box.setOpaque(false);
        box.setLayout(new BoxLayout(box, BoxLayout.Y_AXIS));
        for (JComponent c : new JComponent[]{idleTitle, idleHint, resultsBtn}) c.setAlignmentX(Component.CENTER_ALIGNMENT);
        Ui.secondary(resultsBtn);
        resultsBtn.addActionListener(e -> showResults.run());
        resultsBtn.setVisible(false);
        box.add(idleTitle);
        box.add(Box.createVerticalStrut(8));
        box.add(idleHint);
        box.add(Box.createVerticalStrut(16));
        box.add(resultsBtn);
        JPanel center = new JPanel(new GridBagLayout());
        center.setOpaque(false);
        center.add(box);
        return center;
    }

    private JComponent buildLive() {
        JPanel root = new JPanel(new BorderLayout(0, 12));
        root.setOpaque(false);

        // --- top card: status chip, clock, banner
        JPanel top = Ui.card(new BorderLayout(16, 6));
        chip.setOpaque(true);
        chip.setBorder(BorderFactory.createEmptyBorder(4, 14, 4, 14));
        chip.setFont(chip.getFont().deriveFont(Font.BOLD, 13f));
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEADING, 12, 0));
        left.setOpaque(false);
        left.add(chip);
        left.add(caption);
        top.add(left, BorderLayout.LINE_START);
        JPanel right = new JPanel(new FlowLayout(FlowLayout.TRAILING, 12, 0));
        right.setOpaque(false);
        Ui.secondary(cancelBtn);
        cancelBtn.addActionListener(e -> {
            if (JOptionPane.showConfirmDialog(this, "לבטל את הסקר המתוזמן?", "ביטול תזמון",
                    JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) surveys.cancelScheduled();
        });
        right.add(cancelBtn);
        right.add(clock);
        top.add(right, BorderLayout.LINE_END);
        sentBanner.setBorder(BorderFactory.createEmptyBorder(9, 14, 9, 14));
        top.add(sentBanner, BorderLayout.SOUTH);

        // --- stats row
        JPanel stats = new JPanel(new GridLayout(1, 3, 12, 0));
        stats.setOpaque(false);
        stats.add(stat("משתתפים בסקר", participantsStat));
        stats.add(stat("השלימו", doneStat));
        stats.add(stat("טרם השלימו", pendingStat));

        JPanel north = new JPanel(new BorderLayout(0, 12));
        north.setOpaque(false);
        north.add(top, BorderLayout.NORTH);
        north.add(stats, BorderLayout.CENTER);
        root.add(north, BorderLayout.NORTH);

        // --- table
        JTable table = new JTable(model);
        table.setRowHeight(34);
        table.setShowVerticalLines(false);
        table.setFillsViewportHeight(true);
        table.getTableHeader().setReorderingAllowed(false);
        table.setRowSelectionAllowed(false);
        table.getColumnModel().getColumn(1).setCellRenderer(new ProgressRenderer());
        table.getColumnModel().getColumn(2).setCellRenderer(new StatusRenderer());
        table.getColumnModel().getColumn(1).setPreferredWidth(160);
        table.getColumnModel().getColumn(2).setPreferredWidth(110);
        DefaultTableCellRenderer lead = new DefaultTableCellRenderer();
        lead.setHorizontalAlignment(SwingConstants.LEADING);
        table.getColumnModel().getColumn(0).setCellRenderer(lead);

        JPanel tableCard = new JPanel(new BorderLayout(0, 6));
        tableCard.setOpaque(false);
        JPanel head = new JPanel(new BorderLayout());
        head.setOpaque(false);
        head.add(Ui.label("מצב המענה של המשתתפים", 15, true, null), BorderLayout.LINE_START);
        head.add(tableHint, BorderLayout.LINE_END);
        tableCard.add(head, BorderLayout.NORTH);
        tableCard.add(new JScrollPane(table), BorderLayout.CENTER);
        root.add(tableCard, BorderLayout.CENTER);
        return root;
    }

    private static JComponent stat(String title, JLabel value) {
        JPanel p = Ui.card(new BorderLayout(0, 2));
        p.add(Ui.label(title, 13, false, Ui.MUTED), BorderLayout.NORTH);
        p.add(value, BorderLayout.CENTER);
        return p;
    }

    // ================================================================== updating

    /** Re-reads the whole survey state. Called on every survey event (always on the EDT). */
    void refresh() {
        current = surveys.activeView();
        if (current == null) {
            showIdle();
            return;
        }
        SurveyView v = current;
        cards.show(this, "live");
        model.setRowCount(0);
        for (ParticipantView p : v.participants()) {
            model.addRow(new Object[]{p.name(), p, p.status()});
        }
        participantsStat.setText(v.state() == SurveyView.State.SCHEDULED ? "—" : String.valueOf(v.participantsCount()));
        doneStat.setText(v.state() == SurveyView.State.SCHEDULED ? "—" : String.valueOf(v.completedCount()));
        pendingStat.setText(v.state() == SurveyView.State.SCHEDULED ? "—" : String.valueOf(v.notCompletedCount()));

        if (v.state() == SurveyView.State.SCHEDULED) {
            chip.setText("⏱ מתוזמן");
            chip.setBackground(Ui.WARN_BG);
            chip.setForeground(Ui.WARN);
            cancelBtn.setVisible(true);
            tableHint.setText("רשימת המשתתפים תיקבע ברגע שליחת הסקר (חברי הקהילה באותו רגע).");
            Ui.showBanner(sentBanner, "הסקר (" + v.questions().size() + " שאלות) עדיין לא נשלח - הוא יישלח אוטומטית בסיום הספירה לאחור.",
                    Ui.WARN, Ui.WARN_BG);
        } else {
            chip.setText("● פעיל");
            chip.setBackground(Ui.SUCCESS_BG);
            chip.setForeground(Ui.SUCCESS);
            cancelBtn.setVisible(false);
            tableHint.setText("מתעדכן אוטומטית בזמן אמת");
            Ui.showBanner(sentBanner, "✅ <b>הסקר נשלח</b> ל-" + v.participantsCount() + " משתתפים בשעה "
                    + Ui.hms(v.startedAt()) + ". התשובות נאספות עכשיו.", Ui.SUCCESS, Ui.SUCCESS_BG);
        }
        tick();
    }

    private void showIdle() {
        cards.show(this, "idle");
        SurveyView last = surveys.latestView();
        boolean hasResults = last != null && last.state() == SurveyView.State.CLOSED;
        resultsBtn.setVisible(hasResults);
        idleHint.setText(hasResults
                ? "הסקר האחרון הסתיים. אפשר להתחיל סקר חדש מלשונית \"יצירת סקר\"."
                : "צרו סקר חדש בלשונית \"יצירת סקר\" - התקדמות המשתתפים תוצג כאן בזמן אמת.");
    }

    /** Runs 4x per second: keeps the countdown / remaining-time label exact. */
    private void tick() {
        SurveyView v = current;
        if (v == null) return;
        Instant now = Instant.now();
        if (v.state() == SurveyView.State.SCHEDULED) {
            caption.setText("הסקר יישלח בעוד");
            clock.setText(Ui.mmss(Duration.between(now, v.scheduledFor())));
            clock.setForeground(Ui.WARN);
        } else if (v.state() == SurveyView.State.RUNNING) {
            caption.setText("זמן שנותר לסקר");
            Duration left = Duration.between(now, v.endsAt());
            clock.setText(Ui.mmss(left));
            clock.setForeground(left.toSeconds() < 60 ? Ui.DANGER : Ui.PRIMARY);
        }
    }

    // ================================================================== renderers

    private static final class ProgressRenderer implements javax.swing.table.TableCellRenderer {
        private final JProgressBar bar = new JProgressBar();

        ProgressRenderer() {
            bar.setStringPainted(true);
            bar.setBorder(BorderFactory.createEmptyBorder(8, 6, 8, 6));
        }

        @Override
        public Component getTableCellRendererComponent(JTable t, Object value, boolean sel, boolean focus, int row, int col) {
            ParticipantView p = (ParticipantView) value;
            bar.setMaximum(Math.max(1, p.total()));
            bar.setValue(p.answered());
            bar.setString(Ui.ltr(p.answered() + "/" + p.total()));
            bar.setForeground(p.status() == Status.COMPLETED ? Ui.SUCCESS : Ui.PRIMARY);
            Ui.rtl(bar);
            return bar;
        }
    }

    private static final class StatusRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable t, Object value, boolean sel, boolean focus, int row, int col) {
            JLabel l = (JLabel) super.getTableCellRendererComponent(t, "", false, false, row, col);
            Status s = (Status) value;
            l.setHorizontalAlignment(SwingConstants.CENTER);
            l.setFont(l.getFont().deriveFont(Font.BOLD));
            switch (s) {
                case COMPLETED -> { l.setText("✔ השלים"); l.setForeground(Ui.SUCCESS); }
                case IN_PROGRESS -> { l.setText("… בתהליך"); l.setForeground(Ui.WARN); }
                default -> { l.setText("טרם ענה"); l.setForeground(Ui.MUTED); }
            }
            return l;
        }
    }
}
