package jp.vstone.sotawhisper;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;

/**
 * HTTP client for Ollama LLM server. Streaming NDJSON.
 * Copied from SotaVisionTest/LlamaClient.java with package change.
 * Java 1.8, no external dependencies.
 */
public class LlamaClient {

    private static final String TAG = "LlamaClient";

    private String ollamaBaseUrl;
    private String modelName;
    private int    maxPredict;
    private int    httpTimeoutMs;

    public static class LLMResult {
        public final String response;
        public final double ttftMs;
        public final double totalMs;
        public final int    evalTokens;
        public final double tps;

        public LLMResult(String response, double ttftMs, double totalMs,
                          int evalTokens, double tps) {
            this.response   = response;
            this.ttftMs     = ttftMs;
            this.totalMs    = totalMs;
            this.evalTokens = evalTokens;
            this.tps        = tps;
        }

        public boolean isError() {
            return response.startsWith("[ERROR]") || response.startsWith("[HTTP");
        }
    }

    public LlamaClient(String ollamaBaseUrl, String modelName,
                        int maxPredict, int httpTimeoutMs) {
        this.ollamaBaseUrl = ollamaBaseUrl;
        this.modelName     = modelName;
        this.maxPredict    = maxPredict;
        this.httpTimeoutMs = httpTimeoutMs;
    }

    public LlamaClient() {
        this("http://localhost:11434", "llama3.2:3b", 60, 120000);
    }

    public void setOllamaBaseUrl(String url)    { this.ollamaBaseUrl = url; }
    public void setModelName(String model)      { this.modelName = model; }
    public void setMaxPredict(int tokens)       { this.maxPredict = tokens; }
    public String getOllamaBaseUrl()            { return ollamaBaseUrl; }
    public String getModelName()                { return modelName; }

    /** Single-turn chat. */
    public LLMResult chat(String systemPrompt, String userMessage) {
        String messagesJson = "[" + jsonMessage("system", systemPrompt) + ","
                            + jsonMessage("user", userMessage) + "]";
        return sendToLLM(messagesJson);
    }

    /**
     * Multi-turn chat with conversation history.
     * @param systemPrompt  System prompt
     * @param history       List of pre-built JSON message objects (from jsonMessage())
     */
    public LLMResult chatMultiTurn(String systemPrompt, List history) {
        StringBuilder sb = new StringBuilder("[");
        sb.append(jsonMessage("system", systemPrompt));
        for (int i = 0; i < history.size(); i++) {
            sb.append(",").append((String) history.get(i));
        }
        sb.append("]");
        return sendToLLM(sb.toString());
    }

    private LLMResult sendToLLM(String messagesJson) {
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
            conn.setConnectTimeout(10000);  // 10s connect timeout (fail fast)
            conn.setReadTimeout(httpTimeoutMs);  // read timeout can be long for LLM generation

            String payload = "{\"model\":\"" + modelName + "\","
                + "\"messages\":" + messagesJson + ","
                + "\"stream\":true,"
                + "\"options\":{\"num_predict\":" + maxPredict + "}}";

            OutputStream os = conn.getOutputStream();
            os.write(payload.getBytes("UTF-8"));
            os.flush();
            os.close();

            int httpCode = conn.getResponseCode();
            if (httpCode != 200) {
                long elapsed = System.currentTimeMillis() - startTime;
                return new LLMResult("[HTTP " + httpCode + "]", elapsed, elapsed, 0, 0);
            }

            BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), "UTF-8"));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                String token = extractJsonString(line, "content");
                if (token != null && !token.isEmpty()) {
                    if (firstTokenTime < 0) firstTokenTime = System.currentTimeMillis();
                    fullResponse.append(token);
                }
                if (line.contains("\"done\":true") || line.contains("\"done\": true")) {
                    evalTokens = (int) extractJsonLong(line, "eval_count");
                    evalDurationNs = extractJsonLong(line, "eval_duration");
                    break;
                }
            }
            reader.close();

        } catch (Exception e) {
            String errMsg = e.getClass().getSimpleName() + ": " + e.getMessage();
            long elapsed = System.currentTimeMillis() - startTime;
            log("ERROR: " + errMsg);
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

        log("Response: TTFT=" + String.format("%.0f", ttftMs) + "ms, total="
            + String.format("%.0f", totalMs) + "ms, " + evalTokens + " tok, "
            + String.format("%.1f", tps) + " tok/s");

        return new LLMResult(fullResponse.toString().trim(), ttftMs, totalMs,
                              evalTokens, tps);
    }

    // ----------------------------------------------------------------
    // JSON helpers
    // ----------------------------------------------------------------

    public static String jsonMessage(String role, String content) {
        return "{\"role\":\"" + escapeJson(role)
             + "\",\"content\":\"" + escapeJson(content) + "\"}";
    }

    public static String escapeJson(String s) {
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

    private String extractJsonString(String json, String key) {
        String pattern = "\"" + key + "\":\"";
        int start = json.indexOf(pattern);
        if (start < 0) return null;
        start += pattern.length();
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) {
                char next = json.charAt(i + 1);
                if (next == '"')       { sb.append('"');  i++; }
                else if (next == '\\') { sb.append('\\'); i++; }
                else if (next == 'n')  { sb.append('\n'); i++; }
                else if (next == 't')  { sb.append('\t'); i++; }
                else if (next == 'r')  { sb.append('\r'); i++; }
                else if (next == 'u' && i + 5 < json.length()) {
                    // Decode JSON Unicode escape (e.g., \u3053 -> こ)
                    String hex = json.substring(i + 2, i + 6);
                    try {
                        int code = Integer.parseInt(hex, 16);
                        sb.append((char) code);
                        i += 5;
                    } catch (NumberFormatException e) {
                        sb.append("\\u").append(hex);
                        i += 5;
                    }
                }
                else { sb.append(c); }
            } else if (c == '"') {
                break;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private long extractJsonLong(String json, String key) {
        String pattern = "\"" + key + "\":";
        int start = json.indexOf(pattern);
        if (start < 0) return 0;
        start += pattern.length();
        while (start < json.length() && json.charAt(start) == ' ') start++;
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c >= '0' && c <= '9') sb.append(c);
            else break;
        }
        if (sb.length() == 0) return 0;
        return Long.parseLong(sb.toString());
    }

    private void log(String msg) {
        System.out.println("[" + TAG + "] " + msg);
    }
}
