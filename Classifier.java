import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Classifier {

    private static final String SYSTEM_PROMPT = """
            You classify one customer support message into a fixed JSON shape.

            Reply with ONLY a JSON object on a single line, with no prose, no markdown
            fences, no commentary before or after. The exact shape:

            {"category": "billing|technical|sales|unknown", "urgency": "low|medium|high", "reason": "<one short sentence>"}

            Definitions:
            - category "billing": charges, refunds, invoices, subscription payments.
            - category "technical": bugs, errors, crashes, how-to questions about the product.
            - category "sales": upgrades, plan changes, pricing questions, quotes, trials.
            - category "unknown": anything that is not a customer support request.
            - urgency "low": informational, no action needed soon.
            - urgency "medium": a normal request that should be answered today.
            - urgency "high": blocking the customer, angry tone, or service-down impact.

            Constraints:
            - Output exactly one JSON object and nothing else.
            - All three fields must be present.
            - "reason" must be one sentence (<= 20 words).""";

    private static final Set<String> CATEGORIES = Set.of("billing", "technical", "sales", "unknown");
    private static final Set<String> URGENCIES = Set.of("low", "medium", "high");
    private static final Set<String> REQUIRED_KEYS = Set.of("category", "urgency", "reason");

    // Strict match: the whole content is a single fenced block.
    private static final Pattern STRICT_FENCE = Pattern.compile("(?s)^\\s*```(?:json)?\\s*\\n?(.*?)\\n?```\\s*$");
    // Fallback: any ```json ... ``` block embedded in prose.
    private static final Pattern ANY_FENCE = Pattern.compile("(?s)```(?:json)?\\s*\\n?(\\{.*?\\})\\s*\\n?```");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static void main(String[] args) {
        if (args.length != 1) {
            System.err.println("Usage: java Classifier \"<message>\"");
            System.exit(2);
        }
        String message = args[0];

        String apiKey = System.getenv("OPENROUTER_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("error: OPENROUTER_API_KEY is not set");
            System.exit(3);
        }
        String baseUrl = System.getenv().getOrDefault("CHAT_BASE_URL", "https://openrouter.ai/api");
        String model = System.getenv().getOrDefault("CHAT_MODEL", "minimax/minimax-m3");
        String usageFile = System.getenv("CLASSIFIER_USAGE_FILE");

        String content = null;
        JsonNode usage = null;
        try {
            HttpResponse<String> resp = sendRequest(baseUrl, model, apiKey, message);
            JsonNode root = MAPPER.readTree(resp.body());
            JsonNode choices = root.path("choices");
            if (choices.isArray() && choices.size() > 0) {
                JsonNode first = choices.get(0).path("message").path("content");
                if (first.isTextual()) {
                    content = first.asText();
                }
            }
            usage = root.path("usage");
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            System.err.println("error: HTTP request interrupted");
            System.exit(4);
        } catch (JsonProcessingException jpe) {
            System.err.println("error: malformed chat response — " + jpe.getMessage());
            System.exit(6);
        } catch (java.io.IOException ioe) {
            System.err.println("error: HTTP failure — " + ioe.getMessage());
            System.exit(4);
        }

        if (content == null || content.isBlank()) {
            System.err.println("error: empty reply from model");
            System.exit(5);
        }

        // Side-channel: write usage JSON to CLASSIFIER_USAGE_FILE if set. Best effort.
        if (usageFile != null && !usageFile.isBlank()
                && usage != null && !usage.isMissingNode() && !usage.isNull()) {
            try {
                Map<String, Object> u = new LinkedHashMap<>();
                u.put("prompt_tokens", usage.path("prompt_tokens").asInt(0));
                u.put("completion_tokens", usage.path("completion_tokens").asInt(0));
                Files.writeString(Path.of(usageFile), MAPPER.writeValueAsString(u));
            } catch (Exception e) {
                System.err.println("warning: could not write usage file — " + e.getMessage());
            }
        }

        String stripped = stripFences(content);
        JsonNode parsed = null;
        try {
            parsed = MAPPER.readTree(stripped);
        } catch (JsonProcessingException jpe) {
            System.err.println("error: malformed JSON — " + truncate(stripped));
            System.exit(6);
        }

        if (!parsed.isObject()) {
            System.err.println("error: response is not a JSON object");
            System.exit(7);
        }

        Set<String> keys = new TreeSet<>();
        parsed.fieldNames().forEachRemaining(keys::add);
        if (!keys.equals(REQUIRED_KEYS)) {
            if (keys.size() != REQUIRED_KEYS.size()) {
                System.err.println("error: expected 3 keys, got " + keys);
            } else {
                System.err.println("error: unexpected keys " + keys);
            }
            System.exit(7);
        }

        JsonNode catNode = parsed.path("category");
        JsonNode urgNode = parsed.path("urgency");
        JsonNode rsnNode = parsed.path("reason");
        if (!catNode.isTextual() || !urgNode.isTextual() || !rsnNode.isTextual()) {
            System.err.println("error: a field was not a string");
            System.exit(7);
        }

        String category = catNode.asText();
        String urgency = urgNode.asText();
        String reason = rsnNode.asText();

        if (!CATEGORIES.contains(category)) {
            System.err.println("error: invalid field category=" + category);
            System.exit(7);
        }
        if (!URGENCIES.contains(urgency)) {
            System.err.println("error: invalid field urgency=" + urgency);
            System.exit(7);
        }
        if (reason.isEmpty()) {
            System.err.println("error: invalid field reason=<empty>");
            System.exit(7);
        }

        ObjectNode out = MAPPER.createObjectNode();
        out.put("category", category);
        out.put("urgency", urgency);
        out.put("reason", reason);
        try {
            System.out.println(MAPPER.writeValueAsString(out));
        } catch (JsonProcessingException jpe) {
            System.err.println("error: could not serialize output — " + jpe.getMessage());
            System.exit(7);
        }
        System.exit(0);
    }

    private static HttpResponse<String> sendRequest(String baseUrl, String model,
                                                    String apiKey, String message)
            throws java.io.IOException, InterruptedException {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", model);
        ArrayNode messages = MAPPER.createArrayNode();
        ObjectNode sys = MAPPER.createObjectNode();
        sys.put("role", "system");
        sys.put("content", SYSTEM_PROMPT);
        messages.add(sys);
        ObjectNode usr = MAPPER.createObjectNode();
        usr.put("role", "user");
        usr.put("content", message);
        messages.add(usr);
        body.set("messages", messages);
        String bodyJson = MAPPER.writeValueAsString(body);

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/v1/chat/completions"))
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(bodyJson))
                .build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            throw new java.io.IOException("HTTP " + resp.statusCode() + " — " + truncate(resp.body()));
        }
        return resp;
    }

    private static String stripFences(String content) {
        String trimmed = content.trim();
        Matcher m = STRICT_FENCE.matcher(trimmed);
        if (m.matches()) {
            return m.group(1).trim();
        }
        Matcher m2 = ANY_FENCE.matcher(trimmed);
        if (m2.find()) {
            return m2.group(1).trim();
        }
        return trimmed;
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() <= 200 ? s : s.substring(0, 200) + "...";
    }
}
