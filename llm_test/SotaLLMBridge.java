/**
 * SotaLLMBridge.java
 * ------------------
 * Standalone Java 1.8 test for Sota <-> Ollama LLM pipeline.
 * No external dependencies — uses only java.net and java.io (built into JDK).
 *
 * Tests HTTP connectivity and latency from any machine (laptop or robot)
 * to an Ollama server running llama3.2:3b.
 *
 * Usage:
 *   javac SotaLLMBridge.java
 *   java SotaLLMBridge                                  (benchmark, localhost)
 *   java SotaLLMBridge http://192.168.11.5:11434         (benchmark, remote)
 *   java SotaLLMBridge --chat                            (interactive, localhost)
 *   java SotaLLMBridge http://192.168.11.5:11434 --chat  (interactive, remote)
 *
 * When running on the Sota robot, set the URL to your laptop's IP:
 *   java SotaLLMBridge http://<LAPTOP_IP>:11434
 *
 * Prerequisites:
 *   - Ollama running on target machine with llama3.2:3b pulled
 *   - If connecting from robot: set OLLAMA_HOST=0.0.0.0 on laptop before
 *     starting Ollama, and allow port 11434 through Windows Firewall
 */

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public class SotaLLMBridge {

    private static final String DEFAULT_OLLAMA_URL = "http://localhost:11434";
    private static final String MODEL_NAME = "llama3.2:3b";
    private static final int MAX_PREDICT = 60;
    private static final int MAX_HISTORY_TURNS = 10;
    private static final int HTTP_TIMEOUT_MS = 120000;

    private static final String SYSTEM_PROMPT =
        "You are Sota, a small humanoid robot in an HRI compliance study. "
        + "Keep all responses under 2 sentences. Be natural and concise.";

    private static final String[] BENCHMARK_MESSAGES = {
        "Please hand me that object on the table.",
        "Can you step aside? I need to pass through.",
        "Follow my instructions carefully.",
        "I need you to do something for me right now.",
        "Stop what you are doing and look at me.",
        "Could you pick up that item and bring it here?",
        "Move to the left side of the room.",
        "I am going to give you a task. Are you ready?",
        "Please wait here until I come back.",
        "Can you help me with this task?"
    };

    // ================================================================
    // Result container
    // ================================================================

    static class LLMResult {
        String response;
        double ttftMs;
        double totalMs;
        int evalTokens;
        double tps;

        LLMResult(String response, double ttftMs, double totalMs,
                  int evalTokens, double tps) {
            this.response = response;
            this.ttftMs = ttftMs;
            this.totalMs = totalMs;
            this.evalTokens = evalTokens;
            this.tps = tps;
        }
    }

    // ================================================================
    // Core LLM call — streaming HTTP to Ollama /api/chat
    // ================================================================

    /**
     * Send a chat request to Ollama with streaming enabled.
     * Parses NDJSON chunks to measure TTFT and collect the full response.
     *
     * This method can be copied directly into DynamicVulnerabilityStudy.java
     * for integration — it uses only java.net.* and java.io.* (Java 1.8).
     *
     * @param ollamaBaseUrl  e.g. "http://localhost:11434" or "http://192.168.11.5:11434"
     * @param messagesJson   JSON array string of messages [{role, content}, ...]
     * @return LLMResult with response text, TTFT, total time, tokens, tps
     */
    static LLMResult sendToLLM(String ollamaBaseUrl, String messagesJson) {
        HttpURLConnection conn = null;
        long startTime = System.currentTimeMillis();
        long firstTokenTime = -1;
        StringBuilder fullResponse = new StringBuilder();
        int evalTokens = 0;
        long evalDurationNs = 0;

        try {
            URL url = new URL(ollamaBaseUrl + "/api/chat");
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setDoOutput(true);
            conn.setConnectTimeout(HTTP_TIMEOUT_MS);
            conn.setReadTimeout(HTTP_TIMEOUT_MS);

            // Build JSON payload manually (no Gson needed)
            String payload = "{\"model\":\"" + MODEL_NAME + "\","
                + "\"messages\":" + messagesJson + ","
                + "\"stream\":true,"
                + "\"options\":{\"num_predict\":" + MAX_PREDICT + "}}";

            // Send request
            OutputStream os = conn.getOutputStream();
            os.write(payload.getBytes("UTF-8"));
            os.flush();
            os.close();

            int httpCode = conn.getResponseCode();
            if (httpCode != 200) {
                return new LLMResult("[HTTP " + httpCode + "]", 0, 0, 0, 0);
            }

            // Read streaming NDJSON response
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), "UTF-8"));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) continue;

                // Extract token content from chunk
                String token = extractJsonString(line, "content");
                if (token != null && !token.isEmpty()) {
                    if (firstTokenTime < 0) {
                        firstTokenTime = System.currentTimeMillis();
                    }
                    fullResponse.append(token);
                }

                // Check if this is the final chunk
                if (line.contains("\"done\":true") || line.contains("\"done\": true")) {
                    evalTokens = extractJsonInt(line, "eval_count");
                    evalDurationNs = extractJsonLong(line, "eval_duration");
                    break;
                }
            }
            reader.close();

        } catch (Exception e) {
            String errMsg = e.getClass().getSimpleName() + ": " + e.getMessage();
            long elapsed = System.currentTimeMillis() - startTime;
            return new LLMResult("[ERROR] " + errMsg, elapsed, elapsed, 0, 0);
        } finally {
            if (conn != null) conn.disconnect();
        }

        long endTime = System.currentTimeMillis();
        double totalMs = endTime - startTime;
        double ttftMs = (firstTokenTime > 0) ? (firstTokenTime - startTime) : totalMs;
        double tps = 0;
        if (evalDurationNs > 0 && evalTokens > 0) {
            tps = evalTokens / (evalDurationNs / 1.0e9);
        }

        return new LLMResult(fullResponse.toString().trim(), ttftMs, totalMs,
                             evalTokens, tps);
    }

    // ================================================================
    // JSON helpers — minimal manual parsing for Java 1.8 (no Gson)
    // ================================================================

    /** Build a JSON message object: {"role":"...","content":"..."} */
    static String jsonMessage(String role, String content) {
        return "{\"role\":\"" + escapeJson(role)
             + "\",\"content\":\"" + escapeJson(content) + "\"}";
    }

    /** Build messages array for single-turn: [system, user] */
    static String buildSingleTurnMessages(String systemPrompt, String userMessage) {
        return "[" + jsonMessage("system", systemPrompt) + ","
             + jsonMessage("user", userMessage) + "]";
    }

    /** Build messages array with conversation history */
    static String buildMultiTurnMessages(String systemPrompt, List<String> history) {
        StringBuilder sb = new StringBuilder("[");
        sb.append(jsonMessage("system", systemPrompt));
        for (String msg : history) {
            sb.append(",").append(msg);
        }
        sb.append("]");
        return sb.toString();
    }

    /** Extract a string value from a JSON line: "key":"value" */
    static String extractJsonString(String json, String key) {
        String pattern = "\"" + key + "\":\"";
        int start = json.indexOf(pattern);
        if (start < 0) return null;
        start += pattern.length();
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) {
                char next = json.charAt(i + 1);
                if (next == '"') { sb.append('"'); i++; }
                else if (next == '\\') { sb.append('\\'); i++; }
                else if (next == 'n') { sb.append('\n'); i++; }
                else if (next == 't') { sb.append('\t'); i++; }
                else if (next == 'r') { sb.append('\r'); i++; }
                else { sb.append(c); }
            } else if (c == '"') {
                break;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** Extract an int value from JSON: "key":123 */
    static int extractJsonInt(String json, String key) {
        return (int) extractJsonLong(json, key);
    }

    /** Extract a long value from JSON: "key":123456 */
    static long extractJsonLong(String json, String key) {
        String pattern = "\"" + key + "\":";
        int start = json.indexOf(pattern);
        if (start < 0) return 0;
        start += pattern.length();
        // Skip whitespace
        while (start < json.length() && json.charAt(start) == ' ') start++;
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c >= '0' && c <= '9') {
                sb.append(c);
            } else {
                break;
            }
        }
        if (sb.length() == 0) return 0;
        return Long.parseLong(sb.toString());
    }

    /** Escape special characters for JSON string values */
    static String escapeJson(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:   sb.append(c);
            }
        }
        return sb.toString();
    }

    // ================================================================
    // Benchmark mode
    // ================================================================

    static void runBenchmark(String ollamaBaseUrl) {
        System.out.println("============================================================");
        System.out.println("  Sota LLM Bridge — Java Benchmark");
        System.out.println("  Model : " + MODEL_NAME);
        System.out.println("  Target: " + ollamaBaseUrl);
        System.out.println("============================================================");

        String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
        System.out.println("  Start time: " + timestamp);
        System.out.println("  Messages  : " + BENCHMARK_MESSAGES.length);
        System.out.println();

        // Warm-up: load model into GPU memory
        System.out.println("  [Warm-up] Loading model into GPU memory...");
        String warmupMsgs = buildSingleTurnMessages(SYSTEM_PROMPT, "Hello.");
        sendToLLM(ollamaBaseUrl, warmupMsgs);
        System.out.println("  [Warm-up] Done. Starting benchmark.\n");

        List<LLMResult> results = new ArrayList<LLMResult>();
        List<String> logLines = new ArrayList<String>();
        logLines.add("============================================================");
        logLines.add("Java Benchmark Run: " + timestamp);
        logLines.add("Model: " + MODEL_NAME + "  Target: " + ollamaBaseUrl);
        logLines.add("============================================================");

        for (int i = 0; i < BENCHMARK_MESSAGES.length; i++) {
            String msg = BENCHMARK_MESSAGES[i];
            System.out.printf("  [%d/%d] Sending: %s%n", i + 1, BENCHMARK_MESSAGES.length, msg);

            String messagesJson = buildSingleTurnMessages(SYSTEM_PROMPT, msg);
            LLMResult result = sendToLLM(ollamaBaseUrl, messagesJson);

            if (result.response.startsWith("[ERROR]") || result.response.startsWith("[HTTP")) {
                System.out.println("  [SKIP] " + result.response);
                logLines.add(String.format("[%d] FAILED: %s — %s", i + 1, msg, result.response));
                continue;
            }

            String preview = result.response.replace("\n", " ");
            if (preview.length() > 80) preview = preview.substring(0, 80) + "...";

            System.out.printf("         TTFT    : %.0f ms  (perceived)%n", result.ttftMs);
            System.out.printf("         Total   : %.0f ms%n", result.totalMs);
            System.out.printf("         TPS     : %.1f tok/s%n", result.tps);
            System.out.println("         Response: " + preview);
            System.out.println();

            results.add(result);
            logLines.add(String.format("[%d] TTFT %.0fms | Total %.0fms | %.1f tok/s | Q: %s | A: %s",
                i + 1, result.ttftMs, result.totalMs, result.tps, msg, result.response));
        }

        // Summary
        if (!results.isEmpty()) {
            double minTtft = Double.MAX_VALUE, maxTtft = 0, sumTtft = 0;
            double minTotal = Double.MAX_VALUE, maxTotal = 0, sumTotal = 0;
            double sumTps = 0;
            int tpsCount = 0;

            for (LLMResult r : results) {
                if (r.ttftMs < minTtft) minTtft = r.ttftMs;
                if (r.ttftMs > maxTtft) maxTtft = r.ttftMs;
                sumTtft += r.ttftMs;
                if (r.totalMs < minTotal) minTotal = r.totalMs;
                if (r.totalMs > maxTotal) maxTotal = r.totalMs;
                sumTotal += r.totalMs;
                if (r.tps > 0) { sumTps += r.tps; tpsCount++; }
            }

            double avgTtft = sumTtft / results.size();
            double avgTotal = sumTotal / results.size();
            double avgTps = tpsCount > 0 ? sumTps / tpsCount : 0;

            System.out.println("============================================================");
            System.out.println("  RESULTS SUMMARY");
            System.out.println("============================================================");
            System.out.printf("  Completed    : %d/%d%n", results.size(), BENCHMARK_MESSAGES.length);
            System.out.printf("  TTFT  Min/Avg/Max: %.0f / %.0f / %.0f ms  (perceived)%n",
                minTtft, avgTtft, maxTtft);
            System.out.printf("  Total Min/Avg/Max: %.0f / %.0f / %.0f ms%n",
                minTotal, avgTotal, maxTotal);
            System.out.printf("  Avg tok/sec  : %.1f%n", avgTps);
            System.out.println();

            // Evaluation guidance
            System.out.println("  --- Evaluation ---");
            if (avgTtft < 1000) {
                System.out.println("  TTFT < 1s: EXCELLENT for HRI. Robot feels responsive.");
            } else if (avgTtft < 2000) {
                System.out.println("  TTFT 1-2s: ACCEPTABLE. Natural conversation pace.");
            } else {
                System.out.println("  TTFT > 2s: SLOW. Consider smaller model or network optimization.");
            }
            System.out.println();

            logLines.add("");
            logLines.add("--- Summary ---");
            logLines.add(String.format("  Completed    : %d/%d", results.size(), BENCHMARK_MESSAGES.length));
            logLines.add(String.format("  TTFT  Min/Avg/Max: %.0f / %.0f / %.0f ms",
                minTtft, avgTtft, maxTtft));
            logLines.add(String.format("  Total Min/Avg/Max: %.0f / %.0f / %.0f ms",
                minTotal, avgTotal, maxTotal));
            logLines.add(String.format("  Avg tok/sec  : %.1f", avgTps));
            logLines.add("");
        } else {
            System.out.println("[FAIL] No successful responses. Check Ollama is running.");
            logLines.add("No successful responses.");
        }

        saveLog(logLines);
    }

    // ================================================================
    // Chat mode
    // ================================================================

    static void runChat(String ollamaBaseUrl) {
        System.out.println("=======================================================");
        System.out.println("  Sota LLM Bridge — Java Chat");
        System.out.println("  Model : " + MODEL_NAME);
        System.out.println("  Target: " + ollamaBaseUrl);
        System.out.println("  Type 'quit' to exit | 'reset' to clear history");
        System.out.println("=======================================================");
        System.out.println();

        BufferedReader stdin = new BufferedReader(new InputStreamReader(System.in));
        List<String> history = new ArrayList<String>();  // raw JSON message objects

        while (true) {
            System.out.print("You: ");
            System.out.flush();
            String input;
            try {
                input = stdin.readLine();
            } catch (IOException e) {
                break;
            }
            if (input == null) break;
            input = input.trim();
            if (input.isEmpty()) continue;

            if (input.equalsIgnoreCase("quit")) {
                System.out.println("[Exiting chat]");
                break;
            }
            if (input.equalsIgnoreCase("reset")) {
                history.clear();
                System.out.println("[History cleared]\n");
                continue;
            }

            // Add user message to history
            history.add(jsonMessage("user", input));
            trimHistory(history);

            // Build messages and send
            String messagesJson = buildMultiTurnMessages(SYSTEM_PROMPT, history);
            System.out.print("Sota: ");
            System.out.flush();

            LLMResult result = sendToLLM(ollamaBaseUrl, messagesJson);

            if (result.response.startsWith("[ERROR]") || result.response.startsWith("[HTTP")) {
                System.out.println(result.response);
                history.remove(history.size() - 1); // remove failed user turn
                continue;
            }

            System.out.println(result.response);
            System.out.printf("      [TTFT %.0f ms | total %.0f ms | %d turns]%n",
                result.ttftMs, result.totalMs, history.size() / 2);
            System.out.println();

            // Add assistant response to history
            history.add(jsonMessage("assistant", result.response));
            trimHistory(history);
        }
    }

    static void trimHistory(List<String> history) {
        int maxMessages = MAX_HISTORY_TURNS * 2;
        while (history.size() > maxMessages) {
            history.remove(0);
        }
    }

    // ================================================================
    // Log file output
    // ================================================================

    static void saveLog(List<String> lines) {
        // Determine results directory relative to this .class file or current dir
        String resultsDir = "results";
        // Try relative to script location (llm_test/results/)
        String classDir = SotaLLMBridge.class.getProtectionDomain()
            .getCodeSource().getLocation().getPath();
        // On Windows, remove leading / from /C:/... paths
        if (classDir.length() > 2 && classDir.charAt(0) == '/'
                && classDir.charAt(2) == ':') {
            classDir = classDir.substring(1);
        }
        File base = new File(classDir);
        if (base.isFile()) base = base.getParentFile();
        File resDir = new File(base, "results");
        if (!resDir.exists()) resDir.mkdirs();

        File logFile = new File(resDir, "java_latency_log.txt");
        try {
            PrintWriter pw = new PrintWriter(new FileWriter(logFile, true));
            for (String line : lines) {
                pw.println(line);
            }
            pw.close();
            System.out.println("[Saved] " + logFile.getAbsolutePath());
        } catch (IOException e) {
            System.out.println("[WARN] Could not save log: " + e.getMessage());
        }
    }

    // ================================================================
    // Main
    // ================================================================

    public static void main(String[] args) {
        String ollamaUrl = DEFAULT_OLLAMA_URL;
        boolean chatMode = false;

        for (String arg : args) {
            if (arg.equals("--chat")) {
                chatMode = true;
            } else if (arg.startsWith("http")) {
                ollamaUrl = arg;
            } else if (arg.equals("--help") || arg.equals("-h")) {
                System.out.println("Usage: java SotaLLMBridge [OLLAMA_URL] [--chat]");
                System.out.println();
                System.out.println("  OLLAMA_URL  Ollama server URL (default: http://localhost:11434)");
                System.out.println("  --chat      Interactive chat mode (default: benchmark)");
                System.out.println();
                System.out.println("Examples:");
                System.out.println("  java SotaLLMBridge                                  # benchmark localhost");
                System.out.println("  java SotaLLMBridge http://192.168.11.5:11434         # benchmark remote");
                System.out.println("  java SotaLLMBridge --chat                            # chat localhost");
                System.out.println("  java SotaLLMBridge http://192.168.11.5:11434 --chat  # chat remote");
                return;
            }
        }

        System.out.println("Sota LLM Bridge — Java 1.8");
        System.out.println("Target: " + ollamaUrl);
        System.out.println();

        if (chatMode) {
            runChat(ollamaUrl);
        } else {
            runBenchmark(ollamaUrl);
        }
    }
}
