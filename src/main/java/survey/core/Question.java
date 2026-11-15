package survey.core;

import java.util.List;

/** One survey question with 2-4 answer options. */
public record Question(String text, List<String> options) {
    public Question {
        text = text == null ? "" : text.trim();
        options = options == null ? List.of() : options.stream().map(String::trim).toList();
    }
}
