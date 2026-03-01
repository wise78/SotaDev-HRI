package jp.vstone.sotawhisper;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * HTTP client for DeepFace race/ethnicity detection.
 * Sends JPEG image to the whisper_server's /analyze_face endpoint.
 *
 * Server returns: {"ok":true, "dominant_race":"asian", "confidence":87, "all_races":{...}}
 *
 * Java 1.8, no external dependencies.
 */
public class DeepFaceClient {

    private static final String TAG = "DeepFace";

    private static final int CONNECT_TIMEOUT_MS = 10000;
    private static final int READ_TIMEOUT_MS    = 30000;

    private final String serverIp;
    private final int port;

    // ----------------------------------------------------------------
    // Result container
    // ----------------------------------------------------------------

    public static class FaceAnalysisResult {
        public final String  dominantRace;
        public final int     confidence;
        public final boolean ok;
        public final long    processingMs;

        public FaceAnalysisResult(String dominantRace, int confidence,
                                   boolean ok, long processingMs) {
            this.dominantRace = dominantRace;
            this.confidence   = confidence;
            this.ok           = ok;
            this.processingMs = processingMs;
        }

        public String toString() {
            return "FaceAnalysis{ok=" + ok + ", race=" + dominantRace
                + ", conf=" + confidence + "%, ms=" + processingMs + "}";
        }
    }

    // ----------------------------------------------------------------
    // Constructor
    // ----------------------------------------------------------------

    public DeepFaceClient(String serverIp, int port) {
        this.serverIp = serverIp;
        this.port     = port;
        log("Initialized -> http://" + serverIp + ":" + port + "/analyze_face");
    }

    // ----------------------------------------------------------------
    // Public API
    // ----------------------------------------------------------------

    /**
     * Send JPEG image bytes to /analyze_face for race detection.
     * @param jpegBytes  JPEG-encoded image data
     * @return FaceAnalysisResult with dominant race and confidence
     */
    public FaceAnalysisResult analyzeRace(byte[] jpegBytes) {
        if (jpegBytes == null || jpegBytes.length == 0) {
            return new FaceAnalysisResult("", 0, false, 0);
        }

        long startTime = System.currentTimeMillis();
        String boundary = "----DeepFaceBoundary" + System.currentTimeMillis();

        byte[] body;
        try {
            body = buildMultipartBody(boundary, "face.jpg", jpegBytes);
        } catch (IOException e) {
            log("ERROR: Cannot build request: " + e.getMessage());
            return new FaceAnalysisResult("", 0, false, 0);
        }

        HttpURLConnection conn = null;
        try {
            URL url = new URL("http://" + serverIp + ":" + port + "/analyze_face");
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type",
                "multipart/form-data; boundary=" + boundary);
            conn.setRequestProperty("Content-Length", String.valueOf(body.length));
            conn.setDoOutput(true);
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);

            OutputStream os = conn.getOutputStream();
            os.write(body);
            os.flush();
            os.close();

            int httpCode = conn.getResponseCode();
            if (httpCode != 200) {
                long elapsed = System.currentTimeMillis() - startTime;
                String errBody = readResponseBody(conn.getErrorStream());
                log("ERROR: HTTP " + httpCode + " -- " + errBody);
                return new FaceAnalysisResult("", 0, false, elapsed);
            }

            String responseBody = readResponseBody(conn.getInputStream());
            long elapsed = System.currentTimeMillis() - startTime;

            boolean ok          = extractJsonBool(responseBody, "ok");
            String dominantRace = extractJsonString(responseBody, "dominant_race");
            int confidence      = (int) extractJsonLong(responseBody, "confidence");
            long serverMs       = extractJsonLong(responseBody, "processing_ms");

            if (dominantRace == null) dominantRace = "";

            log("Result: " + dominantRace + " (" + confidence + "%), "
                + "server=" + serverMs + "ms, total=" + elapsed + "ms");

            return new FaceAnalysisResult(dominantRace, confidence, ok, serverMs);

        } catch (java.net.SocketTimeoutException e) {
            long elapsed = System.currentTimeMillis() - startTime;
            log("Timeout after " + elapsed + "ms");
            return new FaceAnalysisResult("", 0, false, elapsed);
        } catch (java.net.ConnectException e) {
            log("Cannot connect: " + e.getMessage());
            return new FaceAnalysisResult("", 0, false, 0);
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - startTime;
            log("ERROR: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return new FaceAnalysisResult("", 0, false, elapsed);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    // ----------------------------------------------------------------
    // Multipart body construction
    // ----------------------------------------------------------------

    private byte[] buildMultipartBody(String boundary, String filename,
                                       byte[] imageBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        String header = "--" + boundary + "\r\n"
            + "Content-Disposition: form-data; name=\"image\"; filename=\""
            + filename + "\"\r\n"
            + "Content-Type: image/jpeg\r\n\r\n";
        out.write(header.getBytes("UTF-8"));
        out.write(imageBytes);

        String footer = "\r\n--" + boundary + "--\r\n";
        out.write(footer.getBytes("UTF-8"));

        return out.toByteArray();
    }

    // ----------------------------------------------------------------
    // Response reading
    // ----------------------------------------------------------------

    private String readResponseBody(InputStream is) throws IOException {
        if (is == null) return "";
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(is, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            return sb.toString();
        } finally {
            if (reader != null) {
                try { reader.close(); } catch (IOException ignored) {}
            }
        }
    }

    // ----------------------------------------------------------------
    // JSON helpers (manual, Java 1.8 compatible)
    // ----------------------------------------------------------------

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
                else if (next == 'r')  { sb.append('\r'); i++; }
                else if (next == 't')  { sb.append('\t'); i++; }
                else if (next == 'u' && i + 5 < json.length()) {
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

    private boolean extractJsonBool(String json, String key) {
        String pattern = "\"" + key + "\":";
        int start = json.indexOf(pattern);
        if (start < 0) return false;
        start += pattern.length();
        while (start < json.length() && json.charAt(start) == ' ') start++;
        return json.length() >= start + 4
            && json.charAt(start) == 't'
            && json.charAt(start + 1) == 'r'
            && json.charAt(start + 2) == 'u'
            && json.charAt(start + 3) == 'e';
    }

    private void log(String msg) {
        System.out.println("[" + TAG + "] " + msg);
    }
}
