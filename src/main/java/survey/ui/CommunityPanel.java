package survey.ui;

import survey.core.CommunityService;
import survey.core.Member;

import javax.swing.*;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.util.List;

/** Always-visible global community list. Updates itself whenever someone joins - no refresh button. */
final class CommunityPanel extends JPanel {

    private final CommunityService community;
    private final DefaultTableModel model = new DefaultTableModel(new Object[]{"שם", "Telegram", "הצטרפות"}, 0) {
        @Override public boolean isCellEditable(int r, int c) { return false; }
    };
    private final JLabel count = Ui.label("0 חברים", 13, true, Ui.PRIMARY);
    private final JTable table = new JTable(model);
    private final CardLayout cards = new CardLayout();
    private final JPanel body = new JPanel(cards);

    CommunityPanel(CommunityService community, String botUsername) {
        super(new BorderLayout(0, 10));
        this.community = community;
        setOpaque(false);

        // header: title + member count
        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        header.add(Ui.label("👥 הקהילה", 18, true, null), BorderLayout.LINE_START);
        count.setOpaque(true);
        count.setBackground(Ui.INFO_BG);
        count.setBorder(BorderFactory.createEmptyBorder(4, 12, 4, 12));
        header.add(count, BorderLayout.LINE_END);
        add(header, BorderLayout.NORTH);

        // table
        table.setRowHeight(32);
        table.setShowVerticalLines(false);
        table.setFillsViewportHeight(true);
        table.getTableHeader().setReorderingAllowed(false);
        table.setSelectionBackground(Ui.INFO_BG);
        table.setSelectionForeground(Color.BLACK);
        DefaultTableCellRenderer lead = new DefaultTableCellRenderer();
        lead.setHorizontalAlignment(SwingConstants.LEADING);
        table.setDefaultRenderer(Object.class, lead);
        table.getColumnModel().getColumn(2).setMaxWidth(110);
        table.getColumnModel().getColumn(1).setMaxWidth(150);

        JLabel empty = new JLabel("<html><div style='text-align:center'>עדיין אין חברים בקהילה.<br>"
                + (botUsername == null ? "שלחו " : "שלחו <b>Hi</b> או <b>היי</b> אל @" + botUsername + "<br>או לחצו על Start.")
                + "</div></html>", SwingConstants.CENTER);
        empty.setForeground(Ui.MUTED);
        body.add(new JScrollPane(table), "table");
        body.add(empty, "empty");
        JPanel card = Ui.card(new BorderLayout());
        card.setBorder(BorderFactory.createLineBorder(Ui.LINE, 1, true));
        card.add(body);
        add(card, BorderLayout.CENTER);

        community.addListener((m, size) -> SwingUtilities.invokeLater(this::reload));
        reload();
    }

    void reload() {
        List<Member> members = community.members();
        model.setRowCount(0);
        for (Member m : members) {
            model.addRow(new Object[]{m.name(), Ui.ltr(m.usernameLabel()), Ui.joined(m.joinedAt())});
        }
        count.setText(members.size() + (members.size() == 1 ? " חבר" : " חברים"));
        cards.show(body, members.isEmpty() ? "empty" : "table");
        if (!members.isEmpty()) {
            table.scrollRectToVisible(table.getCellRect(members.size() - 1, 0, true)); // newest member visible
        }
    }
}
