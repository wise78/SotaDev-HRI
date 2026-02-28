/**
 * WhisperSTT.java — Java 1.8 HTTP client for the Whisper STT server.
 *
 * Sends WAV audio files to a remote Whisper server via HTTP POST (multipart/form-data)
 * and receives transcribed text + detected language as JSON.
 *
 * No external dependencies — pure java.net + java.io (Java 1.8.0_40 compatible).
 * No lambdas, no var, no Stream.toList(), no method references.
 *
 * Usage:
 *   WhisperSTT stt = new WhisperSTT("192.168.11.5", 5050);
 *   if (stt.isServerAlive()) {
 *       WhisperSTT.WhisperResult result = stt.transcribe("/path/to/audio.wav");
 *       if (result.ok) {
 *           System.out.println("Text: " + result.text);
 *           System.out.println("Language: " + result.language);
 *       }
 *   }
 */

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class WhisperSTT {

    private static final String TAG = "WhisperSTT";

    private final String serverIp;
    private final int port;

    private static final int CONNECT_TIMEOUT_MS = 30000;  // 30 seconds
    private static final int READ_TIMEOUT_MS    = 60000;  // 60 seconds (Whisper processing)

    // ----------------------------------------------------------------
    // Result container
    // ----------------------------------------------------------------

    public static class WhisperResult {
        public final String  text;
        public final String  language;
        public final boolean ok;
        public final long    processingMs;

        public WhisperResult(String text, String language, boolean ok, long processingMs) {
            this.text         = text;
            this.language     = language;
            this.ok           = ok;
            this.processingMs = processingMs;
        }

        public String toString() {
            return "WhisperResult{ok=" + ok
                + ", lang=" + language
                + ", text='" + text + "'"
                + ", ms=" + processingMs + "}";
        }
    }

    // ----------------------------------------------------------------
    // Constructor
    // ----------------------------------------------------------------

    public WhisperSTT(String serverIp, int port) {
        this.serverIp = serverIp;
        this.port     = port;
        log("Initialized -> http://" + serverIp + ":" + port);
    }

    // ----------------------------------------------------------------
    // Public API
    // ----------------------------------------------------------------

    /**
     * Transcribe a WAV file by uploading it to the Whisper server.
     * Constructs multipart/form-data body manually and sends via HTTP POST.
     *
     * @param wavFilePath  Path to the .wav file (absolute or relative)
     * @return WhisperResult — always non-null; check result.ok for success
     */
    public WhisperResult transcribe(String wavFilePath) {
        long startTime = System.currentTimeMillis();

        // Validate file exists
        File wavFile = new File(wavFilePath);
        if (!wavFile.exists()) {
            log("ERROR: File not found: " + wavFilePath);
            return new WhisperResult("", "", false, 0);
        }
        if (!wavFile.isFile()) {
            log("ERROR: Not a file: " + wavFilePath);
            return new WhisperResult("", "", false, 0);
        }
        if (wavFile.length() == 0) {
            log("ERROR: File is empty: " + wavFilePath);
            return new WhisperResult("", "", false, 0);
        }

        // Read WAV file into memory
        byte[] wavBytes;
        try {
            wavBytes = readFileBytes(wavFile);
        } catch (IOException e) {
            log("ERROR: Cannot read file: " + e.getMessage());
            return new WhisperResult("", "", false, 0);
        }

        log("Sending " + wavFile.getName() + " (" + (wavBytes.length / 1024) + " KB)...");

        // Build multipart body
        String boundary = "----JavaWhisperBoundary" + System.currentTimeMillis();
        byte[] body;
        try {
            body = buildMultipartBody(boundary, wavFile.getName(), wavBytes);
        } catch (IOException e) {
            log("ERROR: Cannot build request body: " + e.getMessage());
            return new WhisperResult("", "", false, 0);
        }

        // Send HTTP POST
        HttpURLConnection conn = null;
        try {
            URL url = new URL("http://" + serverIp + ":" + port + "/transcribe");
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type",
                "multipart/form-data; boundary=" + boundary);
            conn.setRequestProperty("Content-Length", String.valueOf(body.length));
            conn.setDoOutput(true);
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);

            // Write body
            OutputStream os = conn.getOutputStream();
            os.write(body);
            os.flush();
            os.close();

            // Check response code
            int httpCode = conn.getResponseCode();
            if (httpCode != 200) {
                String errBody = readResponseBody(conn.getErrorStream());
                log("ERROR: HTTP " + httpCode + " — " + errBody);
                return new WhisperResult("", "", false,
                    System.currentTimeMillis() - startTime);
            }

            // Read and parse response JSON
            String responseBody = readResponseBody(conn.getInputStream());
            long elapsed = System.currentTimeMillis() - startTime;

            String text     = extractJsonString(responseBody, "text");
            String language = extractJsonString(responseBody, "language");
            boolean ok      = extractJsonBool(responseBody, "ok");
            long serverMs   = extractJsonLong(responseBody, "processing_ms");

            if (text == null)     text = "";
            if (language == null) language = "";

            log("Result: lang=" + language + ", text='" + truncate(text, 60)
                + "', server=" + serverMs + "ms, total=" + elapsed + "ms");

            return new WhisperResult(text, language, ok, serverMs);

        } catch (java.net.SocketTimeoutException e) {
            long elapsed = System.currentTimeMillis() - startTime;
            log("ERROR: Timeout after " + elapsed + "ms");
            return new WhisperResult("", "", false, elapsed);
        } catch (java.net.ConnectException e) {
            log("ERROR: Cannot connect to server: " + e.getMessage());
            return new WhisperResult("", "", false, 0);
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - startTime;
            log("ERROR: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return new WhisperResult("", "", false, elapsed);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /**
     * Check if the Whisper server is reachable.
     *
     * @return true if GET /health returns HTTP 200
     */
    public boolean isServerAlive() {
        HttpURLConnection conn = null;
        try {
            URL url = new URL("http://" + serverIp + ":" + port + "/health");
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(5000);
            int code = conn.getResponseCode();
            if (code == 200) {
                String body = readResponseBody(conn.getInputStream());
                log("Health OK: " + body);
                return true;
            } else {
                log("Health FAIL: HTTP " + code);
                return false;
            }
        } catch (Exception e) {
            log("Health FAIL: " + e.getMessage());
            return false;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    // ----------------------------------------------------------------
    // Multipart/form-data construction
    // ----------------------------------------------------------------

    private byte[] buildMultipartBody(String boundary, String filename,
                                       byte[] fileBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        // Part header
        String header = "--" + boundary + "\r\n"
            + "Content-Disposition: form-data; name=\"audio\"; filename=\""
            + filename + "\"\r\n"
            + "Content-Type: audio/wav\r\n"
            + "\r\n";
        out.write(header.getBytes("UTF-8"));

        // File bytes (raw binary, no Base64)
        out.write(fileBytes);

        // Closing boundary
        String footer = "\r\n--" + boundary + "--\r\n";
        out.write(footer.getBytes("UTF-8"));

        return out.toByteArray();
    }

    // ----------------------------------------------------------------
    // File I/O
    // ----------------------------------------------------------------

    private byte[] readFileBytes(File file) throws IOException {
        FileInputStream fis = null;
        try {
            fis = new FileInputStream(file);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = fis.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } finally {
            if (fis != null) {
                try { fis.close(); } catch (IOException ignored) {}
            }
        }
    }

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
    // JSON helpers (manual parsing, no external libs)
    // Same pattern as LlamaClient.java in SotaVisionTest
    // ----------------------------------------------------------------

    /**
     * Extract a string value: "key":"value"
     * Handles basic escape sequences (\", \\, \n, \t, \r).
     */
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
                else { sb.append(c); }
            } else if (c == '"') {
                break;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** Extract a long value: "key":12345 */
    private long extractJsonLong(String json, String key) {
        String pattern = "\"" + key + "\":";
        int start = json.indexOf(pattern);
        if (start < 0) return 0;
        start += pattern.length();
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

    /** Extract a boolean value: "key":true or "key":false */
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

    // ----------------------------------------------------------------
    // Utilities
    // ----------------------------------------------------------------

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        if (s.length() <= maxLen) return s;
        return s.substring(0, maxLen) + "...";
    }

    // ----------------------------------------------------------------
    // Logging
    // ----------------------------------------------------------------

    private void log(String msg) {
        System.out.println("[" + TAG + "] " + msg);
    }
}
