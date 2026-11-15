package survey.ui;

import javax.swing.*;
import javax.swing.border.Border;
import java.awt.*;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/** Shared look & feel helpers so every panel is visually consistent. */
final class Ui {
    static final Color PRIMARY = new Color(0x2563EB);
    static final Color SUCCESS = new Color(0x15803D);
    static final Color SUCCESS_BG = new Color(0xDCFCE7);
    static final Color WARN = new Color(0xB45309);
    static final Color WARN_BG = new Color(0xFEF3C7);
    static final Color DANGER = new Color(0xB91C1C);
    static final Color DANGER_BG = new Color(0xFEE2E2);
    static final Color INFO_BG = new Color(0xDBEAFE);
    static final Color MUTED = new Color(0x6B7280);
    static final Color LINE = new Color(0xE5E7EB);
    static final Color PAGE = new Color(0xF3F4F6);

    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter HMS = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter DM_HM = DateTimeFormatter.ofPattern("dd/MM HH:mm").withZone(ZoneId.systemDefault());

    private Ui() {}

    /** Panel for use inside a JScrollPane that follows the viewport width (so content stretches instead of staying narrow). */
    static final class ScrollablePanel extends JPanel implements Scrollable {
        ScrollablePanel(LayoutManager lm) {
            super(lm);
            setOpaque(false);
        }
        @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        @Override public int getScrollableUnitIncrement(Rectangle r, int o, int d) { return 16; }
        @Override public int getScrollableBlockIncrement(Rectangle r, int o, int d) { return 64; }
        @Override public boolean getScrollableTracksViewportWidth() { return true; }
        @Override public boolean getScrollableTracksViewportHeight() { return false; }
    }

    static JLabel label(String text, float size, boolean bold, Color color) {
        JLabel l = new JLabel(text);
        l.setFont(l.getFont().deriveFont(bold ? Font.BOLD : Font.PLAIN, size));
        if (color != null) l.setForeground(color);
        return l;
    }

    /** White rounded "card" container. */
    static JPanel card(LayoutManager layout) {
        JPanel p = new JPanel(layout);
        p.setBackground(Color.WHITE);
        p.setBorder(cardBorder());
        return p;
    }

    static Border cardBorder() {
        return BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(LINE, 1, true),
                BorderFactory.createEmptyBorder(14, 16, 14, 16));
    }

    static void primary(JButton b) {
        b.putClientProperty("FlatLaf.style", "background: #2563EB; foreground: #ffffff; arc: 12; font: bold +1; margin: 8,18,8,18");
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
    }

    static void secondary(JButton b) {
        b.putClientProperty("FlatLaf.style", "arc: 12; margin: 6,14,6,14");
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
    }

    /** Coloured message strip (success / warning / error / info). */
    static JLabel banner() {
        JLabel l = new JLabel(" ");
        l.setOpaque(true);
        l.setBorder(BorderFactory.createEmptyBorder(9, 14, 9, 14));
        l.setVisible(false);
        return l;
    }

    static void showBanner(JLabel l, String text, Color fg, Color bg) {
        l.setText("<html>" + text + "</html>");
        l.setForeground(fg);
        l.setBackground(bg);
        l.setVisible(true);
    }

    static void rtl(Component c) {
        c.applyComponentOrientation(ComponentOrientation.RIGHT_TO_LEFT);
    }

    /** Wraps text that must stay left-to-right (usernames, ratios) so bidi does not scramble it. */
    static String ltr(String s) {
        return "‎" + s + "‎";
    }

    static void setEnabledDeep(Component c, boolean enabled) {
        c.setEnabled(enabled);
        if (c instanceof Container ct) for (Component child : ct.getComponents()) setEnabledDeep(child, enabled);
    }

    static String hm(Instant t) {
        return HM.format(t);
    }

    static String hms(Instant t) {
        return HMS.format(t);
    }

    /** "HH:mm" for today, "dd/MM HH:mm" otherwise. */
    static String joined(Instant t) {
        boolean today = t.atZone(ZoneId.systemDefault()).toLocalDate().equals(java.time.LocalDate.now());
        return (today ? HM : DM_HM).format(t);
    }

    static String mmss(Duration d) {
        long s = Math.max(0, (d.toMillis() + 999) / 1000);
        return String.format("%02d:%02d", s / 60, s % 60);
    }

    static void message(Component parent, String title, String text, int type) {
        JOptionPane pane = new JOptionPane("<html><div style='width:320px'>" + text + "</div></html>", type);
        pane.setOptions(new Object[]{"אישור"});
        rtl(pane);
        JDialog d = pane.createDialog(parent, title);
        rtl(d);
        d.setVisible(true);
        d.dispose();
    }
}
