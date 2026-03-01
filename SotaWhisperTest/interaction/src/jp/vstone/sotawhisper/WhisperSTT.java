package jp.vstone.sotawhisper;

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

/**
 * Java 1.8 HTTP client for the Whisper STT server.
 * Sends WAV audio via multipart/form-data, receives text + language + English translation.
 * No external dependencies.
 */
public class WhisperSTT {

    private static final String TAG = "WhisperSTT";

    private final String serverIp;
    private final int port;

    private static final int CONNECT_TIMEOUT_MS = 30000;
    private static final int READ_TIMEOUT_MS    = 120000;

    // ----------------------------------------------------------------
    // Result container
    // ----------------------------------------------------------------

    public static class WhisperResult {
        public final String  text;       // original language text
        public final String  textEn;     // English translation (or same as text if EN)
        public final String  language;   // detected language code ("ja", "en", "id", etc.)
        public final boolean ok;
        public final long    processingMs;

        public WhisperResult(String text, String textEn, String language,
                             boolean ok, long processingMs) {
            this.text         = text;
            this.textEn       = textEn;
            this.language     = language;
            this.ok           = ok;
            this.processingMs = processingMs;
        }

        public String toString() {
            return "WhisperResult{ok=" + ok
                + ", lang=" + language
                + ", text='" + text + "'"
                + ", textEn='" + textEn + "'"
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
     * Transcribe a WAV file. Also requests English translation.
     * @param wavFilePath  Path to .wav file
     * @return WhisperResult with text, textEn, language
     */
    public WhisperResult transcribe(String wavFilePath) {
        return transcribe(wavFilePath, true);
    }

    /**
     * Transcribe a WAV file.
     * @param wavFilePath  Path to .wav file
     * @param translate    If true, also return English translation (text_en)
     * @return WhisperResult
     */
    public WhisperResult transcribe(String wavFilePath, boolean translate) {
        long startTime = System.currentTimeMillis();

        File wavFile = new File(wavFilePath);
        if (!wavFile.exists() || !wavFile.isFile() || wavFile.length() == 0) {
            log("ERROR: Invalid file: " + wavFilePath);
            return new WhisperResult("", "", "", false, 0);
        }

        byte[] wavBytes;
        try {
            wavBytes = readFileBytes(wavFile);
        } catch (IOException e) {
            log("ERROR: Cannot read file: " + e.getMessage());
            return new WhisperResult("", "", "", false, 0);
        }

        log("Sending " + wavFile.getName() + " (" + (wavBytes.length / 1024)
            + " KB, translate=" + translate + ")...");

        String boundary = "----JavaWhisperBoundary" + System.currentTimeMillis();
        byte[] body;
        try {
            body = buildMultipartBody(boundary, wavFile.getName(), wavBytes, translate);
        } catch (IOException e) {
            log("ERROR: Cannot build request: " + e.getMessage());
            return new WhisperResult("", "", "", false, 0);
        }

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

            OutputStream os = conn.getOutputStream();
            os.write(body);
            os.flush();
            os.close();

            int httpCode = conn.getResponseCode();
            if (httpCode != 200) {
                String errBody = readResponseBody(conn.getErrorStream());
                log("ERROR: HTTP " + httpCode + " — " + errBody);
                return new WhisperResult("", "", "", false,
                    System.currentTimeMillis() - startTime);
            }

            String responseBody = readResponseBody(conn.getInputStream());
            long elapsed = System.currentTimeMillis() - startTime;

            String text     = extractJsonString(responseBody, "text");
            String textEn   = extractJsonString(responseBody, "text_en");
            String language  = extractJsonString(responseBody, "language");
            boolean ok       = extractJsonBool(responseBody, "ok");
            long serverMs    = extractJsonLong(responseBody, "processing_ms");

            if (text == null)     text = "";
            if (textEn == null)   textEn = text;
            if (language == null)  language = "";

            log("Result: lang=" + language + ", text='" + truncate(text, 50)
                + "', textEn='" + truncate(textEn, 50)
                + "', server=" + serverMs + "ms, total=" + elapsed + "ms");

            return new WhisperResult(text, textEn, language, ok, serverMs);

        } catch (java.net.SocketTimeoutException e) {
            long elapsed = System.currentTimeMillis() - startTime;
            log("ERROR: Timeout after " + elapsed + "ms");
            return new WhisperResult("", "", "", false, elapsed);
        } catch (java.net.ConnectException e) {
            log("ERROR: Cannot connect: " + e.getMessage());
            return new WhisperResult("", "", "", false, 0);
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - startTime;
            log("ERROR: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return new WhisperResult("", "", "", false, elapsed);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** Check if server is reachable. */
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
            }
            log("Health FAIL: HTTP " + code);
            return false;
        } catch (Exception e) {
            log("Health FAIL: " + e.getMessage());
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    // ----------------------------------------------------------------
    // Multipart body construction
    // ----------------------------------------------------------------

    private byte[] buildMultipartBody(String boundary, String filename,
                                       byte[] fileBytes, boolean translate) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        // Audio file part
        String header = "--" + boundary + "\r\n"
            + "Content-Disposition: form-data; name=\"audio\"; filename=\""
            + filename + "\"\r\n"
            + "Content-Type: audio/wav\r\n\r\n";
        out.write(header.getBytes("UTF-8"));
        out.write(fileBytes);

        // Translate part
        if (translate) {
            String translatePart = "\r\n--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"translate\"\r\n\r\n"
                + "true";
            out.write(translatePart.getBytes("UTF-8"));
        }

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
    // JSON helpers (manual, no Gson — same pattern as LlamaClient)
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
                        // Keep raw text if malformed escape.
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

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        if (s.length() <= maxLen) return s;
        return s.substring(0, maxLen) + "...";
    }

    private void log(String msg) {
        System.out.println("[" + TAG + "] " + msg);
    }
}
