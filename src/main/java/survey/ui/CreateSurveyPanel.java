package survey.ui;

import survey.ai.OpenAiSurveyGenerator;
import survey.core.CommunityService;
import survey.core.Question;
import survey.core.SurveyService;

import javax.swing.*;
import java.awt.*;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;

/** Survey creation: manual or ChatGPT-assisted, immediate or delayed send. */
final class CreateSurveyPanel extends JPanel {

    private final SurveyService surveys;
    private final CommunityService community;
    private final OpenAiSurveyGenerator ai;

    private final JLabel stateBanner = Ui.banner();
    private final JRadioButton manualRb = new JRadioButton("כתיבה ידנית", true);
    private final JRadioButton aiRb = new JRadioButton("יצירה עם ChatGPT");
    private final JPanel aiBox = Ui.card(new GridBagLayout());
    private final JTextField topicField = new JTextField();
    private final JSpinner aiCount = new JSpinner(new SpinnerNumberModel(3, 1, SurveyService.MAX_QUESTIONS, 1));
    private final JButton generateBtn = new JButton("✨ צור שאלות");
    private final JLabel aiStatus = new JLabel(" ");

    private final JPanel editorsBox = new JPanel();
    private final List<QuestionEditor> editors = new ArrayList<>();
    private final JButton addQuestionBtn = new JButton("＋ הוספת שאלה");

    private final JRadioButton nowRb = new JRadioButton("שליחה מיידית", true);
    private final JRadioButton laterRb = new JRadioButton("שליחה בעוד");
    private final JSpinner delaySpinner = new JSpinner(new SpinnerNumberModel(2, 1, 120, 1));
    private final JLabel feedback = Ui.banner();
    private final JButton submitBtn = new JButton("🚀 שלח סקר לקהילה");

    CreateSurveyPanel(SurveyService surveys, CommunityService community, OpenAiSurveyGenerator ai) {
        super(new BorderLayout(0, 10));
        this.surveys = surveys;
        this.community = community;
        this.ai = ai;
        setOpaque(false);

        // ---- top: state banner + mode choice
        JPanel top = new JPanel();
        top.setOpaque(false);
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        stateBanner.setAlignmentX(Component.RIGHT_ALIGNMENT);
        top.add(stateBanner);
        top.add(Box.createVerticalStrut(8));
        top.add(buildModeRow());
        top.add(Box.createVerticalStrut(8));
        top.add(buildAiBox());
        add(top, BorderLayout.NORTH);

        // ---- center: question editors
        editorsBox.setLayout(new BoxLayout(editorsBox, BoxLayout.Y_AXIS));
        editorsBox.setOpaque(false);
        JPanel centerWrap = new Ui.ScrollablePanel(new BorderLayout(0, 8));
        centerWrap.add(editorsBox, BorderLayout.NORTH);
        JPanel addRow = new JPanel(new FlowLayout(FlowLayout.LEADING, 0, 0));
        addRow.setOpaque(false);
        Ui.secondary(addQuestionBtn);
        addRow.add(addQuestionBtn);
        centerWrap.add(addRow, BorderLayout.CENTER);
        JScrollPane scroll = new JScrollPane(centerWrap);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.getViewport().setBackground(Ui.PAGE);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        add(scroll, BorderLayout.CENTER);

        // ---- bottom: timing + submit
        add(buildFooter(), BorderLayout.SOUTH);

        addQuestionBtn.addActionListener(e -> addEditor(null));
        resetForm();
        refreshState();
        wireEvents();
    }

    // ================================================================== building blocks

    private JComponent buildModeRow() {
        ButtonGroup g = new ButtonGroup();
        g.add(manualRb);
        g.add(aiRb);
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEADING, 14, 0));
        row.setOpaque(false);
        row.add(Ui.label("איך ליצור את הסקר?", 14, true, null));
        row.add(manualRb);
        row.add(aiRb);
        row.setAlignmentX(Component.RIGHT_ALIGNMENT);
        manualRb.addActionListener(e -> updateMode());
        aiRb.addActionListener(e -> updateMode());
        return row;
    }

    private JComponent buildAiBox() {
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4);
        c.fill = GridBagConstraints.HORIZONTAL;
        c.gridy = 0;
        c.gridx = 0;
        aiBox.add(new JLabel("נושא הסקר:"), c);
        c.gridx = 1;
        c.weightx = 1;
        topicField.putClientProperty("JTextField.placeholderText", "למשל: העדפות טכנולוגיות בקרב מהנדסי תוכנה");
        aiBox.add(topicField, c);
        c.gridx = 2;
        c.weightx = 0;
        aiBox.add(new JLabel("מספר שאלות:"), c);
        c.gridx = 3;
        aiBox.add(aiCount, c);
        c.gridx = 4;
        Ui.secondary(generateBtn);
        aiBox.add(generateBtn, c);
        c.gridx = 0;
        c.gridy = 1;
        c.gridwidth = 5;
        aiStatus.setForeground(Ui.MUTED);
        aiBox.add(aiStatus, c);
        aiBox.setVisible(false);
        aiBox.setAlignmentX(Component.RIGHT_ALIGNMENT);

        if (!ai.isConfigured()) {
            aiStatus.setText("מפתח OpenAI לא הוגדר ב-pom.xml - אפשר לעבוד במצב ידני.");
            aiStatus.setForeground(Ui.WARN);
            generateBtn.setEnabled(false);
        }
        generateBtn.addActionListener(e -> generate());
        topicField.addActionListener(e -> generate());
        return aiBox;
    }

    private JComponent buildFooter() {
        ButtonGroup g = new ButtonGroup();
        g.add(nowRb);
        g.add(laterRb);
        JPanel timing = new JPanel(new FlowLayout(FlowLayout.LEADING, 10, 0));
        timing.setOpaque(false);
        timing.add(Ui.label("מועד שליחה:", 14, true, null));
        timing.add(nowRb);
        timing.add(laterRb);
        timing.add(delaySpinner);
        timing.add(new JLabel("דקות"));
        delaySpinner.setEnabled(false);
        nowRb.addActionListener(e -> updateTiming());
        laterRb.addActionListener(e -> updateTiming());

        Ui.primary(submitBtn);
        submitBtn.addActionListener(e -> submit());

        JPanel bottom = new JPanel(new BorderLayout(12, 8));
        bottom.setOpaque(false);
        JPanel stack = new JPanel();
        stack.setOpaque(false);
        stack.setLayout(new BoxLayout(stack, BoxLayout.Y_AXIS));
        feedback.setAlignmentX(Component.RIGHT_ALIGNMENT);
        stack.add(feedback);
        stack.add(Box.createVerticalStrut(8));
        JPanel line = new JPanel(new BorderLayout());
        line.setOpaque(false);
        line.add(timing, BorderLayout.LINE_START);
        line.add(submitBtn, BorderLayout.LINE_END);
        line.setAlignmentX(Component.RIGHT_ALIGNMENT);
        stack.add(line);
        bottom.add(stack);
        bottom.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, Ui.LINE));
        return bottom;
    }

    private void wireEvents() {
        surveys.addListener((e, id) -> SwingUtilities.invokeLater(this::refreshState));
        community.addListener((m, size) -> SwingUtilities.invokeLater(this::refreshState));
    }

    // ================================================================== behaviour

    private void updateMode() {
        aiBox.setVisible(aiRb.isSelected());
        if (aiRb.isSelected()) topicField.requestFocusInWindow();
        revalidate();
    }

    private void updateTiming() {
        delaySpinner.setEnabled(laterRb.isSelected() && !surveys.hasActiveSurvey());
        submitBtn.setText(laterRb.isSelected() ? "⏱ תזמן סקר" : "🚀 שלח סקר לקהילה");
    }

    /** Disables the form while a survey is active and shows how many members the community has. */
    private void refreshState() {
        boolean active = surveys.hasActiveSurvey();
        int size = community.size();
        feedback.setVisible(false); // an old error message must not outlive the situation that caused it
        Ui.setEnabledDeep(this, true);
        if (!ai.isConfigured()) generateBtn.setEnabled(false);
        if (active) {
            Ui.setEnabledDeep(aiBox, false);
            Ui.setEnabledDeep(editorsBox, false);
            Ui.setEnabledDeep(addQuestionBtn, false);
            Ui.setEnabledDeep(submitBtn, false);
            Ui.setEnabledDeep(nowRb, false);
            Ui.setEnabledDeep(laterRb, false);
            Ui.setEnabledDeep(delaySpinner, false);
            Ui.showBanner(stateBanner, "⏳ קיים סקר פעיל. ניתן להתחיל סקר חדש רק לאחר שיסתיים (ראו את הלשונית \"סקר פעיל\").",
                    Ui.WARN, Ui.WARN_BG);
        } else if (size < SurveyService.MIN_MEMBERS) {
            Ui.showBanner(stateBanner, "👥 בקהילה " + size + " חברים. כדי להתחיל סקר נדרשים לפחות "
                    + SurveyService.MIN_MEMBERS + ".", Ui.WARN, Ui.WARN_BG);
        } else {
            Ui.showBanner(stateBanner, "✅ בקהילה " + size + " חברים - הסקר יישלח לכולם.", Ui.SUCCESS, Ui.SUCCESS_BG);
        }
        updateTiming();
        updateRemoveButtons();
    }

    private void generate() {
        String topic = topicField.getText().trim();
        if (topic.isEmpty()) {
            aiStatus.setText("יש להזין נושא לסקר.");
            aiStatus.setForeground(Ui.DANGER);
            topicField.putClientProperty("JComponent.outline", "error");
            return;
        }
        topicField.putClientProperty("JComponent.outline", null);
        int count = (Integer) aiCount.getValue();
        generateBtn.setEnabled(false);
        aiStatus.setText("⏳ ChatGPT מנסח את השאלות...");
        aiStatus.setForeground(Ui.MUTED);
        new SwingWorker<List<Question>, Void>() {
            @Override protected List<Question> doInBackground() throws Exception {
                return ai.generate(topic, count);
            }
            @Override protected void done() {
                generateBtn.setEnabled(ai.isConfigured() && !surveys.hasActiveSurvey());
                try {
                    fill(get());
                    aiStatus.setText("✅ השאלות נוצרו. אפשר לערוך אותן לפני השליחה.");
                    aiStatus.setForeground(Ui.SUCCESS);
                } catch (ExecutionException ex) {
                    aiStatus.setText("❌ " + ex.getCause().getMessage());
                    aiStatus.setForeground(Ui.DANGER);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }
        }.execute();
    }

    private void submit() {
        feedback.setVisible(false);
        editors.forEach(QuestionEditor::clearErrors);
        List<Question> questions = new ArrayList<>();
        for (QuestionEditor ed : editors) {
            String err = ed.check();
            if (err != null) {
                Ui.showBanner(feedback, "❌ " + err, Ui.DANGER, Ui.DANGER_BG);
                return;
            }
            questions.add(ed.toQuestion());
        }
        Duration delay = laterRb.isSelected() ? Duration.ofMinutes((Integer) delaySpinner.getValue()) : Duration.ZERO;
        try {
            surveys.create(questions, delay);
            resetForm(); // the window now jumps to the live tab, which confirms the send / the countdown
        } catch (IllegalStateException ex) { // active survey / too few members
            Ui.showBanner(feedback, "❌ " + ex.getMessage(), Ui.DANGER, Ui.DANGER_BG);
            Ui.message(this, "לא ניתן להתחיל סקר", ex.getMessage(), JOptionPane.WARNING_MESSAGE);
        } catch (IllegalArgumentException ex) {
            Ui.showBanner(feedback, "❌ " + ex.getMessage(), Ui.DANGER, Ui.DANGER_BG);
        }
    }

    // ================================================================== editors

    private void resetForm() {
        editorsBox.removeAll();
        editors.clear();
        addEditor(null);
        topicField.setText("");
        manualRb.setSelected(true);
        nowRb.setSelected(true);
        updateMode();
        updateTiming();
    }

    private void fill(List<Question> questions) {
        editorsBox.removeAll();
        editors.clear();
        for (Question q : questions) addEditor(q);
        updateRemoveButtons();
    }

    private void addEditor(Question q) {
        if (editors.size() >= SurveyService.MAX_QUESTIONS) return;
        QuestionEditor ed = new QuestionEditor(this::removeEditor);
        if (q != null) ed.set(q);
        editors.add(ed);
        editorsBox.add(ed);
        editorsBox.add(Box.createVerticalStrut(10));
        updateRemoveButtons();
        editorsBox.revalidate();
        editorsBox.repaint();
    }

    private void removeEditor(QuestionEditor ed) {
        if (editors.size() <= 1) return;
        int i = editors.indexOf(ed);
        editors.remove(ed);
        editorsBox.remove(i * 2 + 1); // spacer
        editorsBox.remove(ed);
        updateRemoveButtons();
        editorsBox.revalidate();
        editorsBox.repaint();
    }

    private void updateRemoveButtons() {
        boolean active = surveys.hasActiveSurvey();
        for (int i = 0; i < editors.size(); i++) {
            editors.get(i).setNumber(i + 1);
            editors.get(i).setRemovable(editors.size() > 1 && !active);
        }
        addQuestionBtn.setEnabled(editors.size() < SurveyService.MAX_QUESTIONS && !active);
        addQuestionBtn.setToolTipText(editors.size() >= SurveyService.MAX_QUESTIONS
                ? "ניתן להוסיף עד " + SurveyService.MAX_QUESTIONS + " שאלות" : null);
    }

    /** One question card: text + up to 4 options (the first two are mandatory). */
    private static final class QuestionEditor extends JPanel {
        private final JLabel title = Ui.label("שאלה", 14, true, null);
        private final JButton remove = new JButton("🗑 הסר");
        private final JTextField text = new JTextField();
        private final JTextField[] options = new JTextField[SurveyService.MAX_OPTIONS];

        QuestionEditor(java.util.function.Consumer<QuestionEditor> onRemove) {
            super(new GridBagLayout());
            setBackground(Color.WHITE);
            setBorder(Ui.cardBorder());
            setAlignmentX(Component.RIGHT_ALIGNMENT);
            GridBagConstraints c = new GridBagConstraints();
            c.insets = new Insets(3, 3, 3, 3);
            c.fill = GridBagConstraints.HORIZONTAL;
            c.gridx = 0;
            c.weightx = 1;
            c.gridy = 0;
            JPanel head = new JPanel(new BorderLayout());
            head.setOpaque(false);
            head.add(title, BorderLayout.LINE_START);
            Ui.secondary(remove);
            remove.addActionListener(e -> onRemove.accept(this));
            head.add(remove, BorderLayout.LINE_END);
            add(head, c);
            c.gridy = 1;
            text.putClientProperty("JTextField.placeholderText", "נוסח השאלה");
            add(text, c);
            for (int i = 0; i < options.length; i++) {
                options[i] = new JTextField();
                options[i].putClientProperty("JTextField.placeholderText",
                        "אפשרות תשובה " + (i + 1) + (i < SurveyService.MIN_OPTIONS ? " (חובה)" : " (לא חובה)"));
                c.gridy = 2 + i;
                add(options[i], c);
            }
            Ui.rtl(this); // editors are created after the window was built, so apply the orientation here
            setMaximumSize(new Dimension(Integer.MAX_VALUE, getPreferredSize().height));
        }

        void setNumber(int n) { title.setText("שאלה " + n); }

        void setRemovable(boolean v) { remove.setVisible(v); }

        void set(Question q) {
            text.setText(q.text());
            for (int i = 0; i < options.length; i++) options[i].setText(i < q.options().size() ? q.options().get(i) : "");
        }

        void clearErrors() {
            text.putClientProperty("JComponent.outline", null);
            for (JTextField f : options) f.putClientProperty("JComponent.outline", null);
        }

        /** @return an error message (and marks the offending field) or null if fine */
        String check() {
            String n = title.getText() + ": ";
            if (text.getText().isBlank()) {
                text.putClientProperty("JComponent.outline", "error");
                text.requestFocusInWindow();
                return n + "יש לכתוב את נוסח השאלה.";
            }
            for (int i = 0; i < SurveyService.MIN_OPTIONS; i++) {
                if (options[i].getText().isBlank()) {
                    options[i].putClientProperty("JComponent.outline", "error");
                    options[i].requestFocusInWindow();
                    return n + "יש להזין לפחות " + SurveyService.MIN_OPTIONS + " אפשרויות תשובה.";
                }
            }
            // an optional 4th option may not be filled while the 3rd is empty
            if (options[3].getText().isBlank() == false && options[2].getText().isBlank()) {
                options[2].putClientProperty("JComponent.outline", "error");
                return n + "מלאו את אפשרות 3 לפני אפשרות 4.";
            }
            return null;
        }

        Question toQuestion() {
            List<String> opts = new ArrayList<>();
            for (JTextField f : options) if (!f.getText().isBlank()) opts.add(f.getText());
            return new Question(text.getText(), opts);
        }
    }
}
