import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

public class Eval {

    private static final String CASES_PATH = "eval-cases.json";
    private static final Set<String> CATEGORIES = Set.of("billing", "technical", "sales", "unknown");
    private static final Set<String> URGENCIES = Set.of("low", "medium", "high");
    private static final Set<String> REQUIRED_KEYS = Set.of("category", "urgency", "reason");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        Path casesFile = Path.of(CASES_PATH);
        if (!Files.exists(casesFile)) {
            System.err.println("error: cases file not found at " + casesFile.toAbsolutePath());
            System.exit(2);
        }

        List<Map<String, Object>> cases;
        try {
            cases = MAPPER.readValue(casesFile.toFile(), new TypeReference<>() {});
        } catch (Exception e) {
            System.err.println("error: could not parse cases file — " + e.getMessage());
            System.exit(2);
            return;
        }

        long totalPromptTokens = 0;
        long totalCompletionTokens = 0;
        int passed = 0;

        for (int i = 0; i < cases.size(); i++) {
            Map<String, Object> c = cases.get(i);
            Object idObj = c.get("id");
            int id = (idObj instanceof Number) ? ((Number) idObj).intValue() : (i + 1);
            String message = String.valueOf(c.get("message"));
            @SuppressWarnings("unchecked")
            List<String> expected = (List<String>) c.get("expected");

            Path usagePath = Path.of(".eval-usage-" + ProcessHandle.current().pid() + "-" + i + ".json");
            try {
                Result r = runClassifier(message, usagePath);
                totalPromptTokens += r.promptTokens;
                totalCompletionTokens += r.completionTokens;

                if (r.exitCode != 0) {
                    String why = r.stderrFirstLine.isEmpty() ? "(no stderr)" : r.stderrFirstLine;
                    System.out.println("Case " + id + ": FAIL — " + why);
                    continue;
                }

                String why = validateStdout(r.stdout, expected);
                if (why == null) {
                    passed++;
                    System.out.println("Case " + id + ": PASS — category=" + extractCategory(r.stdout)
                            + ", urgency=" + extractUrgency(r.stdout));
                } else {
                    System.out.println("Case " + id + ": FAIL — " + why);
                }
            } finally {
                try { Files.deleteIfExists(usagePath); } catch (IOException ignored) {}
            }
        }

        int total = cases.size();
        System.out.println("Summary: " + passed + "/" + total + " passed. Tokens: "
                + totalPromptTokens + " prompt + " + totalCompletionTokens + " completion.");
        System.exit(passed == total ? 0 : 1);
    }

    private static Result runClassifier(String message, Path usagePath) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(
                "java", "-cp", ".;lib/*", "Classifier", message);
        pb.redirectErrorStream(false);
        Map<String, String> env = pb.environment();
        // Inherit OPENROUTER_API_KEY, CHAT_BASE_URL, CHAT_MODEL from parent.
        env.put("CLASSIFIER_USAGE_FILE", usagePath.toString());

        Process p = pb.start();
        StringBuilder out = new StringBuilder();
        StringBuilder err = new StringBuilder();
        Thread tOut = new Thread(() -> drain(p.getInputStream(), out));
        Thread tErr = new Thread(() -> drain(p.getErrorStream(), err));
        tOut.start(); tErr.start();

        boolean finished = p.waitFor(60, java.util.concurrent.TimeUnit.SECONDS);
        if (!finished) {
            p.destroyForcibly();
            throw new RuntimeException("Classifier timed out after 60s");
        }
        tOut.join(); tErr.join();

        Result r = new Result();
        r.exitCode = p.exitValue();
        r.stdout = out.toString();
        r.stderrFirstLine = firstLine(err.toString());
        r.promptTokens = readToken(usagePath, "prompt_tokens");
        r.completionTokens = readToken(usagePath, "completion_tokens");
        return r;
    }

    private static void drain(java.io.InputStream in, StringBuilder sink) {
        try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(in))) {
            String line;
            while ((line = br.readLine()) != null) sink.append(line).append('\n');
        } catch (IOException ignored) {}
    }

    private static String firstLine(String s) {
        int i = s.indexOf('\n');
        return i < 0 ? s : s.substring(0, i);
    }

    private static long readToken(Path usagePath, String field) {
        if (!Files.exists(usagePath)) return 0;
        for (int attempt = 0; attempt < 20; attempt++) {
            try {
                JsonNode n = MAPPER.readTree(usagePath.toFile());
                JsonNode v = n.get(field);
                if (v != null && v.isNumber()) return v.asLong();
                return 0;
            } catch (IOException e) {
                try { Thread.sleep(100); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return 0; }
            }
        }
        return 0;
    }

    private static String validateStdout(String stdout, List<String> expected) {
        String trimmed = stdout.trim();
        if (trimmed.isEmpty()) return "stdout empty";
        JsonNode parsed;
        try {
            parsed = MAPPER.readTree(trimmed);
        } catch (Exception e) {
            return "stdout not valid JSON: " + truncate(trimmed);
        }
        if (!parsed.isObject()) return "stdout is not a JSON object";
        Set<String> keys = new TreeSet<>();
        parsed.fieldNames().forEachRemaining(keys::add);
        if (!keys.equals(REQUIRED_KEYS)) return "stdout has wrong keys: " + keys;

        JsonNode cat = parsed.get("category");
        JsonNode urg = parsed.get("urgency");
        JsonNode rsn = parsed.get("reason");
        if (cat == null || !cat.isTextual()) return "category not a string";
        if (urg == null || !urg.isTextual()) return "urgency not a string";
        if (rsn == null || !rsn.isTextual()) return "reason not a string";
        if (!CATEGORIES.contains(cat.asText())) return "category not in valid set: " + cat.asText();
        if (!URGENCIES.contains(urg.asText())) return "urgency not in valid set: " + urg.asText();
        if (!expected.contains(cat.asText())) {
            return "got category=" + cat.asText() + ", expected one of " + expected;
        }
        return null;
    }

    private static String extractCategory(String stdout) {
        try {
            return MAPPER.readTree(stdout.trim()).get("category").asText();
        } catch (Exception e) { return "?"; }
    }

    private static String extractUrgency(String stdout) {
        try {
            return MAPPER.readTree(stdout.trim()).get("urgency").asText();
        } catch (Exception e) { return "?"; }
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() <= 200 ? s : s.substring(0, 200) + "...";
    }

    private static class Result {
        int exitCode;
        String stdout;
        String stderrFirstLine;
        long promptTokens;
        long completionTokens;
    }
}
