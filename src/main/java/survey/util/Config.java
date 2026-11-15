package survey.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/**
 * Program settings (bot token, OpenAI key and model).
 * <p>
 * The values are defined in the {@code <properties>} block of pom.xml. When Maven builds the project it
 * copies them into {@code app.properties} (resource filtering), and this class reads that file from the classpath.
 * Nothing is read from environment variables or from files next to the program.
 */
public final class Config {

    private static final String DEFAULT_MODEL = "gpt-4o-mini";

    private final Properties props = new Properties();

    public Config() {
        try (InputStream in = Config.class.getResourceAsStream("/app.properties")) {
            if (in != null) props.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            System.err.println("Could not read app.properties: " + e.getMessage());
        }
    }

    /** Blank values, and placeholders that Maven did not replace (such as ${telegram.token}), count as "not set". */
    private String get(String key) {
        String v = props.getProperty(key);
        if (v == null) return null;
        v = v.trim();
        return v.isEmpty() || v.startsWith("${") ? null : v;
    }

    public String telegramToken() {
        return get("telegram.token");
    }

    public String openAiKey() {
        return get("openai.apiKey");
    }

    public String openAiModel() {
        String model = get("openai.model");
        return model == null ? DEFAULT_MODEL : model;
    }
}
