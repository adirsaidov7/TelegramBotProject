package survey.ui;

import survey.core.Question;
import survey.core.SurveyService;
import survey.core.SurveyView;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.IntStream;

/** Results of finished surveys: per question, options sorted by votes (highest first) with percentages. */
final class ResultsPanel extends JPanel {

    private final SurveyService surveys;
    private final JComboBox<SurveyView> picker = new JComboBox<>();
    private final JPanel content = new JPanel();
    private boolean updating;

    ResultsPanel(SurveyService surveys) {
        super(new BorderLayout(0, 10));
        this.surveys = surveys;
        setOpaque(false);

        JPanel top = new JPanel(new BorderLayout(10, 0));
        top.setOpaque(false);
        top.add(Ui.label("📈 תוצאות סקרים", 18, true, null), BorderLayout.LINE_START);
        picker.setRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean s, boolean f) {
                SurveyView sv = (SurveyView) v;
                return super.getListCellRendererComponent(l, sv == null ? "" : "סקר #" + sv.id() + "  ·  "
                        + Ui.hm(sv.startedAt()) + "  ·  " + sv.participantsCount() + " משתתפים", i, s, f);
            }
        });
        picker.addActionListener(e -> {
            if (!updating) show((SurveyView) picker.getSelectedItem());
        });
        top.add(picker, BorderLayout.LINE_END);
        add(top, BorderLayout.NORTH);

        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setOpaque(false);
        JPanel wrap = new Ui.ScrollablePanel(new BorderLayout());
        wrap.add(content, BorderLayout.NORTH);
        JScrollPane scroll = new JScrollPane(wrap);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.getViewport().setBackground(Ui.PAGE);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        add(scroll, BorderLayout.CENTER);

        surveys.addListener((e, id) -> {
            if (e == SurveyService.Event.CLOSED) SwingUtilities.invokeLater(this::reload);
        });
        reload();
    }

    /** Reloads the list of finished surveys and selects the newest one. */
    void reload() {
        List<SurveyView> closed = surveys.closedViews();
        updating = true;
        picker.removeAllItems();
        closed.forEach(picker::addItem);
        updating = false;
        picker.setVisible(!closed.isEmpty());
        if (closed.isEmpty()) {
            show(null);
        } else {
            picker.setSelectedIndex(0);
            show(closed.get(0));
        }
    }

    private void show(SurveyView v) {
        content.removeAll();
        if (v == null) {
            JLabel l = Ui.label("התוצאות יופיעו כאן לאחר שסקר ייסגר.", 15, false, Ui.MUTED);
            l.setBorder(BorderFactory.createEmptyBorder(40, 0, 0, 0));
            content.add(l);
        } else {
            content.add(summary(v));
            for (int q = 0; q < v.questions().size(); q++) {
                content.add(Box.createVerticalStrut(10));
                content.add(questionCard(v, q));
            }
        }
        content.revalidate();
        content.repaint();
    }

    private JComponent summary(SurveyView v) {
        int pct = v.participantsCount() == 0 ? 0 : (int) Math.round(100.0 * v.completedCount() / v.participantsCount());
        String why = v.allCompleted() ? "כל המשתתפים השלימו - הסקר נסגר מוקדם." : "הזמן הסתיים - הסקר נסגר.";
        JLabel l = Ui.banner();
        Ui.showBanner(l, "<b>סקר #" + v.id() + " הסתיים.</b> " + why + "<br>"
                + v.completedCount() + " מתוך " + v.participantsCount() + " משתתפים השלימו את כל השאלות (" + pct + "%).",
                Ui.PRIMARY, Ui.INFO_BG);
        l.setAlignmentX(Component.RIGHT_ALIGNMENT);
        l.setMaximumSize(new Dimension(Integer.MAX_VALUE, l.getPreferredSize().height));
        return l;
    }

    private JComponent questionCard(SurveyView v, int q) {
        Question question = v.questions().get(q);
        int total = v.votesFor(q);

        // option indexes sorted by votes, highest first (stable for ties)
        List<Integer> order = new ArrayList<>(IntStream.range(0, question.options().size()).boxed().toList());
        order.sort(Comparator.comparingInt((Integer o) -> -v.counts()[q][o]));

        JPanel card = Ui.card(new GridBagLayout());
        card.setAlignmentX(Component.RIGHT_ALIGNMENT);
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4);
        c.fill = GridBagConstraints.HORIZONTAL;
        c.gridx = 0;
        c.gridy = 0;
        c.gridwidth = 3;
        c.weightx = 1;
        card.add(Ui.label("<html>שאלה " + (q + 1) + ": " + escape(question.text()) + "</html>", 15, true, null), c);
        c.gridy++;
        card.add(Ui.label(total + (total == 1 ? " תשובה" : " תשובות") + " מתוך " + v.participantsCount() + " משתתפים",
                12, false, Ui.MUTED), c);

        int rank = 0;
        for (int o : order) {
            int votes = v.counts()[q][o];
            double percent = total == 0 ? 0 : 100.0 * votes / total;
            boolean winner = rank++ == 0 && votes > 0;
            c.gridy++;
            c.gridwidth = 1;
            c.gridx = 0;
            c.weightx = 0;
            JLabel name = new JLabel("<html><div style='width:170px'>" + (winner ? "🏆 " : "") + escape(question.options().get(o)) + "</div></html>");
            if (winner) name.setFont(name.getFont().deriveFont(Font.BOLD));
            card.add(name, c);
            c.gridx = 1;
            c.weightx = 1;
            JProgressBar bar = new JProgressBar(0, 1000);
            bar.setValue((int) Math.round(percent * 10));
            bar.setStringPainted(true);
            bar.setString(Ui.ltr(String.format("%.1f%%", percent)));
            bar.setForeground(winner ? Ui.PRIMARY : new Color(0x94A3B8));
            bar.setPreferredSize(new Dimension(200, 24));
            card.add(bar, c);
            c.gridx = 2;
            c.weightx = 0;
            card.add(Ui.label(votes + (votes == 1 ? " הצבעה" : " הצבעות"), 13, false, Ui.MUTED), c);
        }
        card.setMaximumSize(new Dimension(Integer.MAX_VALUE, card.getPreferredSize().height));
        return card;
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
