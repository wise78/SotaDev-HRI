package jp.vstone.sotawhisper;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map;

/**
 * Tiny embedded HTTP status server for remote monitoring.
 *
 * Serves JSON status on a configurable port (default 5051).
 * Uses plain ServerSocket (guaranteed on all JRE 1.8, no com.sun.* deps).
 *
 * Endpoints:
 *   GET /status  -> full JSON status
 *   GET /health  -> {"ok":true}
 *
 * Thread-safe: update() can be called from any thread.
 * Java 1.8, no lambda.
 */
public class StatusServer {

    private static final String TAG = "StatusServer";

    private final int port;
    private final HashMap data;  // String key -> Object value
    private ServerSocket serverSocket;
    private Thread serverThread;
    private volatile boolean running = false;
    private final long startTime;

    public StatusServer(int port) {
        this.port = port;
        this.data = new HashMap();
        this.startTime = System.currentTimeMillis();

        // Default values
        update("state", "init");
        update("turn", Integer.valueOf(0));
        update("maxTurns", Integer.valueOf(8));
        update("silenceRetries", Integer.valueOf(0));
        update("lastDetectedLang", "en");
        update("vadWorking", Boolean.FALSE);
        update("vadLevel", Integer.valueOf(-1));
        update("isRecording", Boolean.FALSE);
        update("recordingDurationMs", Integer.valueOf(0));
        update("lastUserText", "");
        update("lastUserTextEn", "");
        update("lastSotaText", "");
        update("whisperAlive", Boolean.FALSE);
        update("ollamaAlive", Boolean.FALSE);
    }

    /** Update a status field. Thread-safe. */
    public void update(String key, Object value) {
        synchronized (data) {
            data.put(key, value);
        }
    }

    /** Start the server in a daemon thread. */
    public void start() {
        if (running) return;
        running = true;

        serverThread = new Thread(new Runnable() {
            public void run() {
                acceptLoop();
            }
        });
        serverThread.setDaemon(true);
        serverThread.setName("StatusServer-" + port);
        serverThread.start();
        log("Started on port " + port);
    }

    /** Stop the server. */
    public void stop() {
        running = false;
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (Exception e) { /* ignore */ }
        log("Stopped");
    }

    // ----------------------------------------------------------------
    // Accept loop
    // ----------------------------------------------------------------

    private void acceptLoop() {
        try {
            serverSocket = new ServerSocket(port);
            serverSocket.setReuseAddress(true);
            serverSocket.setSoTimeout(1000); // 1s timeout for clean shutdown
        } catch (Exception e) {
            log("ERROR: Cannot bind port " + port + ": " + e.getMessage());
            running = false;
            return;
        }

        while (running) {
            Socket client = null;
            try {
                client = serverSocket.accept();
                client.setSoTimeout(2000);
                handleRequest(client);
            } catch (java.net.SocketTimeoutException e) {
                // Normal timeout, loop again
            } catch (Exception e) {
                if (running) {
                    // Only log errors while we're supposed to be running
                }
            } finally {
                if (client != null) {
                    try { client.close(); } catch (Exception e) { /* ignore */ }
                }
            }
        }

        try { serverSocket.close(); } catch (Exception e) { /* ignore */ }
    }

    // ----------------------------------------------------------------
    // HTTP request handler
    // ----------------------------------------------------------------

    private void handleRequest(Socket client) {
        InputStream is = null;
        OutputStream os = null;
        try {
            is = client.getInputStream();
            os = client.getOutputStream();

            // Read first line only: "GET /path HTTP/1.x"
            StringBuilder line = new StringBuilder();
            int ch;
            while ((ch = is.read()) != -1) {
                if (ch == '\r' || ch == '\n') break;
                line.append((char) ch);
                if (line.length() > 512) break; // safety
            }

            String requestLine = line.toString();
            String path = "/";
            if (requestLine.startsWith("GET ")) {
                int spaceIdx = requestLine.indexOf(' ', 4);
                if (spaceIdx > 0) {
                    path = requestLine.substring(4, spaceIdx);
                } else {
                    path = requestLine.substring(4).trim();
                }
            }

            // Drain remaining headers (read until empty line)
            StringBuilder headerBuf = new StringBuilder();
            boolean lastWasNewline = false;
            while ((ch = is.read()) != -1) {
                if (ch == '\n') {
                    if (lastWasNewline) break;
                    lastWasNewline = true;
                } else if (ch != '\r') {
                    lastWasNewline = false;
                }
                headerBuf.append((char) ch);
                if (headerBuf.length() > 4096) break;
            }

            // Route
            String responseJson;
            if ("/status".equals(path)) {
                responseJson = buildStatusJson();
            } else if ("/health".equals(path)) {
                responseJson = "{\"ok\":true}";
            } else {
                responseJson = "{\"error\":\"Not found. Use /status or /health\"}";
            }

            // CORS headers for browser access
            String httpResponse = "HTTP/1.1 200 OK\r\n"
                + "Content-Type: application/json; charset=UTF-8\r\n"
                + "Access-Control-Allow-Origin: *\r\n"
                + "Connection: close\r\n"
                + "Content-Length: " + responseJson.getBytes("UTF-8").length + "\r\n"
                + "\r\n"
                + responseJson;

            os.write(httpResponse.getBytes("UTF-8"));
            os.flush();

        } catch (Exception e) {
            // Client disconnected or read error — ignore
        } finally {
            try { if (os != null) os.close(); } catch (Exception e) { /* ignore */ }
            try { if (is != null) is.close(); } catch (Exception e) { /* ignore */ }
        }
    }

    // ----------------------------------------------------------------
    // JSON builder
    // ----------------------------------------------------------------

    private String buildStatusJson() {
        long uptimeMs = System.currentTimeMillis() - startTime;

        HashMap snapshot;
        synchronized (data) {
            snapshot = new HashMap(data);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("{");

        // Add uptime
        sb.append("\"uptime\":").append(uptimeMs);

        // Add all data entries
        Object[] keys = snapshot.keySet().toArray();
        for (int i = 0; i < keys.length; i++) {
            String key = (String) keys[i];
            Object val = snapshot.get(key);
            sb.append(",");
            sb.append("\"").append(escapeJson(key)).append("\":");

            if (val == null) {
                sb.append("null");
            } else if (val instanceof Boolean) {
                sb.append(val.toString());
            } else if (val instanceof Number) {
                sb.append(val.toString());
            } else {
                sb.append("\"").append(escapeJson(val.toString())).append("\"");
            }
        }

        sb.append("}");
        return sb.toString();
    }

    private String escapeJson(String s) {
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

    private void log(String msg) {
        System.out.println("[" + TAG + "] " + msg);
    }
}
