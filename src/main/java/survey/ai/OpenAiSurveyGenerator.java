package survey.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import survey.core.Question;
import survey.core.SurveyService;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** Asks the ChatGPT (OpenAI Chat Completions) API to draft survey questions for a topic. */
public class OpenAiSurveyGenerator {

    private static final URI ENDPOINT = URI.create("https://api.openai.com/v1/chat/completions");

    private final String apiKey;
    private final String model;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    private final ObjectMapper json = new ObjectMapper();

    public OpenAiSurveyGenerator(String apiKey, String model) {
        this.apiKey = apiKey;
        this.model = model;
    }

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    /**
     * Blocking call - run it off the Swing EDT.
     *
     * @throws IOException with a user-facing (Hebrew) message on any failure
     */
    public List<Question> generate(String topic, int questionCount) throws IOException {
        if (!isConfigured()) {
            throw new IOException("לא הוגדר מפתח OpenAI ב-pom.xml. ניתן לכתוב את הסקר ידנית.");
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(ENDPOINT)
                    .timeout(Duration.ofSeconds(60))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(buildBody(topic, questionCount)))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 401) throw new IOException("מפתח ה-OpenAI אינו תקין (401).");
            if (response.statusCode() == 429) throw new IOException("חרגת ממכסת הבקשות או מהמכסה בחשבון OpenAI (429).");
            if (response.statusCode() / 100 != 2) {
                throw new IOException("שגיאה משרת OpenAI (קוד " + response.statusCode() + ").");
            }
            return parse(response.body(), questionCount);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("הפנייה ל-ChatGPT הופסקה.");
        } catch (java.net.http.HttpTimeoutException e) {
            throw new IOException("ChatGPT לא הגיב בזמן. נסו שוב.");
        } catch (java.net.ConnectException e) {
            throw new IOException("אין חיבור לאינטרנט / לשרת OpenAI.");
        }
    }

    private String buildBody(String topic, int count) throws IOException {
        String system = "You design short multiple-choice opinion surveys. Reply with JSON only, in exactly this shape: "
                + "{\"questions\":[{\"text\":\"...\",\"options\":[\"...\",\"...\"]}]}. "
                + "Rules: exactly " + count + " question(s); each question has between 2 and 4 distinct, short options; "
                + "write in the same language as the topic (Hebrew if the topic is Hebrew); "
                + "no numbering, no explanations, no markdown.";
        ObjectNode body = json.createObjectNode();
        body.put("model", model);
        body.put("temperature", 0.7);
        body.putObject("response_format").put("type", "json_object");
        ArrayNode messages = body.putArray("messages");
        messages.addObject().put("role", "system").put("content", system);
        messages.addObject().put("role", "user").put("content", "Survey topic: " + topic);
        return json.writeValueAsString(body);
    }

    /** Package-private for testing: turns the raw API response into validated questions. */
    List<Question> parse(String responseBody, int maxQuestions) throws IOException {
        try {
            String content = json.readTree(responseBody).path("choices").path(0).path("message").path("content").asText();
            JsonNode qs = json.readTree(content).path("questions");
            List<Question> out = new ArrayList<>();
            for (JsonNode qn : qs) {
                List<String> options = new ArrayList<>();
                for (JsonNode on : qn.path("options")) {
                    String o = on.asText().trim();
                    if (!o.isEmpty() && options.size() < SurveyService.MAX_OPTIONS) options.add(o);
                }
                if (out.size() < Math.min(maxQuestions, SurveyService.MAX_QUESTIONS)) {
                    out.add(new Question(qn.path("text").asText(), options));
                }
            }
            SurveyService.validate(out);
            return out;
        } catch (IllegalArgumentException | IOException e) {
            throw new IOException("ChatGPT החזיר תשובה שאינה בפורמט תקין. נסו שוב או כתבו את הסקר ידנית.");
        }
    }
}
