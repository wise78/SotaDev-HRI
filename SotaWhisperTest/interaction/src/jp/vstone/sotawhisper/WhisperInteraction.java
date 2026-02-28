package jp.vstone.sotawhisper;

import java.awt.Color;
import java.net.HttpURLConnection;
import java.net.URL;

import jp.vstone.RobotLib.CRobotMem;
import jp.vstone.RobotLib.CRobotPose;
import jp.vstone.RobotLib.CRobotUtil;
import jp.vstone.RobotLib.CSotaMotion;
import jp.vstone.camera.CRoboCamera;
import jp.vstone.camera.FaceDetectResult;

/**
 * Main FSM program: Face detect -> Greet -> Listen (Whisper) -> LLM -> Respond (TTS + Motion).
 *
 * States: IDLE -> GREETING -> LISTENING -> THINKING -> RESPONDING -> CLOSING
 *
 * Includes embedded StatusServer for remote GUI monitoring.
 *
 * Usage:
 *   java -jar whisperinteraction.jar <laptop_ip>
 *   java -jar whisperinteraction.jar <laptop_ip> --status-port 5051
 *
 * Java 1.8, no lambda.
 */
public class WhisperInteraction {

    private static final String TAG = "WhisperInteraction";

    // ----------------------------------------------------------------
    // States
    // ----------------------------------------------------------------

    private static final String STATE_IDLE       = "idle";
    private static final String STATE_GREETING   = "greeting";
    private static final String STATE_LISTENING  = "listening";
    private static final String STATE_THINKING   = "thinking";
    private static final String STATE_RESPONDING = "responding";
    private static final String STATE_CLOSING    = "closing";

    // ----------------------------------------------------------------
    // Configuration defaults
    // ----------------------------------------------------------------

    private static final int    WHISPER_PORT        = 5050;
    private static final int    OLLAMA_PORT         = 11434;
    private static final String OLLAMA_MODEL        = "llama3.2:3b";
    private static final int    LLM_MAX_PREDICT     = 80;
    private static final int    LLM_TIMEOUT_MS      = 120000;
    private static final int    STATUS_PORT         = 5051;

    private static final int    MAX_CONVERSATION_TURNS = 8;
    private static final int    MAX_SILENCE_RETRIES    = 2;
    private static final int    LISTEN_DURATION_MS     = 15000;
    private static final int    COOLDOWN_MS            = 3000;
    private static final int    FACE_POLL_MS           = 300;
    private static final int    FACE_DETECT_THRESHOLD  = 3;

    private static final String CAMERA_DEVICE = "/dev/video0";

    // ----------------------------------------------------------------
    // Modules
    // ----------------------------------------------------------------

    private CRobotMem            mem;
    private CSotaMotion           motion;
    private CRoboCamera           camera;
    private WhisperSpeechManager  speechManager;
    private LlamaClient           llamaClient;
    private GestureManager        gestureManager;
    private StatusServer          statusServer;

    // ----------------------------------------------------------------
    // Runtime state
    // ----------------------------------------------------------------

    private volatile String  currentState = STATE_IDLE;
    private volatile boolean running      = false;

    private int   conversationTurn   = 0;
    private int   silenceRetryCount  = 0;
    private String lastDetectedLang  = "en";

    // Stored for status reporting
    private String laptopIp = "";

    // ----------------------------------------------------------------
    // Main entry point
    // ----------------------------------------------------------------

    public static void main(String[] args) {
        System.out.println("========================================================");
        System.out.println("  Sota Whisper Interaction");
        System.out.println("  Face -> Greet -> Listen -> LLM -> Respond");
        System.out.println("========================================================");
        System.out.println();

        if (args.length < 1) {
            System.out.println("Usage: java -jar whisperinteraction.jar <laptop_ip> [options]");
            System.out.println();
            System.out.println("Options:");
            System.out.println("  --whisper-port <port>   Whisper server port (default: 5050)");
            System.out.println("  --ollama-port <port>    Ollama server port (default: 11434)");
            System.out.println("  --model <name>          Ollama model name (default: llama3.2:3b)");
            System.out.println("  --status-port <port>    Status server port for GUI monitoring (default: 5051)");
            System.out.println();
            System.out.println("Example:");
            System.out.println("  java -jar whisperinteraction.jar 192.168.11.32");
            System.out.println("  java -jar whisperinteraction.jar 192.168.11.32 --status-port 5051");
            System.exit(1);
        }

        // Parse arguments
        String laptopIp    = args[0];
        int    whisperPort = WHISPER_PORT;
        int    ollamaPort  = OLLAMA_PORT;
        String modelName   = OLLAMA_MODEL;
        int    statusPort  = STATUS_PORT;

        for (int i = 1; i < args.length; i++) {
            if ("--whisper-port".equals(args[i]) && i + 1 < args.length) {
                whisperPort = Integer.parseInt(args[++i]);
            } else if ("--ollama-port".equals(args[i]) && i + 1 < args.length) {
                ollamaPort = Integer.parseInt(args[++i]);
            } else if ("--model".equals(args[i]) && i + 1 < args.length) {
                modelName = args[++i];
            } else if ("--status-port".equals(args[i]) && i + 1 < args.length) {
                statusPort = Integer.parseInt(args[++i]);
            }
        }

        System.out.println("  Laptop IP    : " + laptopIp);
        System.out.println("  Whisper      : http://" + laptopIp + ":" + whisperPort);
        System.out.println("  Ollama       : http://" + laptopIp + ":" + ollamaPort);
        System.out.println("  Model        : " + modelName);
        System.out.println("  Status       : http://0.0.0.0:" + statusPort + "/status");
        System.out.println();

        WhisperInteraction app = new WhisperInteraction();
        app.initialize(laptopIp, whisperPort, ollamaPort, modelName, statusPort);
        app.run();
    }

    // ----------------------------------------------------------------
    // Initialization
    // ----------------------------------------------------------------

    public void initialize(String laptopIp, int whisperPort, int ollamaPort,
                            String modelName, int statusPort) {
        this.laptopIp = laptopIp;
        log("Initializing...");

        // 0. Status server (start early so GUI can connect during init)
        statusServer = new StatusServer(statusPort);
        statusServer.update("state", "init");
        statusServer.start();

        // 1. Robot connection
        mem = new CRobotMem();
        if (!mem.Connect()) {
            log("FATAL: Cannot connect to robot. Exiting.");
            statusServer.update("state", "error");
            statusServer.update("lastSotaText", "FATAL: Cannot connect to robot");
            System.exit(1);
        }
        motion = new CSotaMotion(mem);
        motion.InitRobot_Sota();
        log("Robot connected. Firmware: " + mem.FirmwareRev.get());

        // Servo on + neutral pose
        motion.ServoOn();
        CRobotPose initPose = new CRobotPose();
        initPose.SetPose(
            new Byte[]  {1, 2, 3, 4, 5, 6, 7, 8},
            new Short[] {0, 0, 0, 0, 0, 0, 0, 0}
        );
        initPose.setLED_Sota(Color.WHITE, Color.WHITE, 200, Color.WHITE);
        motion.play(initPose, 500);
        CRobotUtil.wait(500);

        // 2. Camera
        camera = new CRoboCamera(CAMERA_DEVICE, motion);
        camera.setEnableFaceSearch(true);
        camera.StartFaceTraking();
        log("Camera initialized");

        // 3. Speech manager (Whisper STT + TTS)
        speechManager = new WhisperSpeechManager(motion, laptopIp, whisperPort);
        log("WhisperSpeechManager ready");

        // Set VAD listener for StatusServer reporting
        speechManager.setVadListener(new WhisperSpeechManager.VadListener() {
            public void onVadUpdate(int level, boolean vadWorking, boolean isRecording,
                                     long elapsedMs, boolean isSpeech) {
                statusServer.update("vadLevel", Integer.valueOf(level));
                statusServer.update("vadWorking", Boolean.valueOf(vadWorking));
                statusServer.update("isRecording", Boolean.valueOf(isRecording));
                statusServer.update("recordingDurationMs", Long.valueOf(elapsedMs));
                statusServer.update("isSpeech", Boolean.valueOf(isSpeech));
            }
        });

        // 4. Check Whisper server
        boolean whisperOk = speechManager.isWhisperAlive();
        statusServer.update("whisperAlive", Boolean.valueOf(whisperOk));
        if (!whisperOk) {
            log("WARNING: Whisper server not reachable! Speech-to-text will fail.");
            log("         Make sure start_server.bat is running on laptop.");
        } else {
            log("Whisper server OK");
        }

        // 5. LLM client
        String ollamaUrl = "http://" + laptopIp + ":" + ollamaPort;
        llamaClient = new LlamaClient(ollamaUrl, modelName, LLM_MAX_PREDICT, LLM_TIMEOUT_MS);
        log("LlamaClient ready -> " + ollamaUrl + " / " + modelName);

        // Check Ollama
        boolean ollamaOk = checkOllama(ollamaUrl);
        statusServer.update("ollamaAlive", Boolean.valueOf(ollamaOk));
        if (!ollamaOk) {
            log("WARNING: Ollama not reachable at " + ollamaUrl);
        } else {
            log("Ollama OK");
        }

        // 6. Gesture manager
        gestureManager = new GestureManager(motion);
        gestureManager.start();
        log("GestureManager started");

        // Update status
        statusServer.update("maxTurns", Integer.valueOf(MAX_CONVERSATION_TURNS));

        log("All subsystems initialized.");
        log("========================================================");
    }

    // ----------------------------------------------------------------
    // Main loop
    // ----------------------------------------------------------------

    public void run() {
        running = true;
        currentState = STATE_IDLE;
        updateState(STATE_IDLE);

        // Shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            public void run() { shutdown(); }
        }));

        log("Entering main loop. Press Ctrl+C to stop.");
        log("State: IDLE -- waiting for face...");

        while (running) {
            try {
                if (STATE_IDLE.equals(currentState)) {
                    handleIdle();
                } else if (STATE_GREETING.equals(currentState)) {
                    handleGreeting();
                } else if (STATE_LISTENING.equals(currentState)) {
                    handleListening();
                } else if (STATE_THINKING.equals(currentState)) {
                    handleThinking();
                } else if (STATE_RESPONDING.equals(currentState)) {
                    handleResponding();
                } else if (STATE_CLOSING.equals(currentState)) {
                    handleClosing();
                }
            } catch (Exception e) {
                log("ERROR in main loop: " + e.getMessage());
                e.printStackTrace();
                updateState(STATE_IDLE);
                currentState = STATE_IDLE;
                conversationTurn = 0;
            }

            CRobotUtil.wait(50);
        }

        shutdown();
    }

    // ----------------------------------------------------------------
    // State: IDLE — poll camera for face
    // ----------------------------------------------------------------

    private void handleIdle() {
        gestureManager.setState(STATE_IDLE);

        int consecutiveDetections = 0;
        while (running && STATE_IDLE.equals(currentState)) {
            FaceDetectResult result = camera.getDetectResult();
            if (result != null && result.isDetect()) {
                consecutiveDetections++;
                if (consecutiveDetections >= FACE_DETECT_THRESHOLD) {
                    log("Face detected! (" + consecutiveDetections + " consecutive)");
                    currentState = STATE_GREETING;
                    updateState(STATE_GREETING);
                    return;
                }
            } else {
                consecutiveDetections = 0;
            }
            CRobotUtil.wait(FACE_POLL_MS);
        }
    }

    // ----------------------------------------------------------------
    // State: GREETING — say hello
    // ----------------------------------------------------------------

    private void handleGreeting() {
        log("--- GREETING ---");
        gestureManager.setState(STATE_GREETING);
        conversationTurn = 0;
        silenceRetryCount = 0;
        statusServer.update("turn", Integer.valueOf(0));
        statusServer.update("silenceRetries", Integer.valueOf(0));

        String greeting = "Hello! I'm Sota. Let's talk!";
        statusServer.update("lastSotaText", greeting);
        speechManager.speak(greeting);
        CRobotUtil.wait(300);

        currentState = STATE_LISTENING;
        updateState(STATE_LISTENING);
    }

    // ----------------------------------------------------------------
    // State: LISTENING — record with VAD + send to Whisper
    // ----------------------------------------------------------------

    // Stored between LISTENING -> THINKING -> RESPONDING
    private WhisperSTT.WhisperResult lastWhisperResult = null;

    private void handleListening() {
        log("--- LISTENING (turn " + (conversationTurn + 1) + "/" + MAX_CONVERSATION_TURNS + ") ---");
        gestureManager.setState(STATE_LISTENING);
        statusServer.update("turn", Integer.valueOf(conversationTurn + 1));

        WhisperSTT.WhisperResult result = speechManager.listen(LISTEN_DURATION_MS);

        if (!result.ok || result.textEn.isEmpty()) {
            silenceRetryCount++;
            statusServer.update("silenceRetries", Integer.valueOf(silenceRetryCount));
            log("No speech detected (retry " + silenceRetryCount + "/" + MAX_SILENCE_RETRIES + ")");

            if (silenceRetryCount >= MAX_SILENCE_RETRIES) {
                log("Max silence retries reached. Going to CLOSING.");
                currentState = STATE_CLOSING;
                updateState(STATE_CLOSING);
                return;
            }

            // Prompt the user
            gestureManager.setState(STATE_RESPONDING);
            String retryMsg = "I can't hear you. Could you say that again?";
            statusServer.update("lastSotaText", retryMsg);
            speechManager.speak(retryMsg);
            CRobotUtil.wait(200);
            // Stay in LISTENING
            return;
        }

        // Reset silence counter on successful speech
        silenceRetryCount = 0;
        statusServer.update("silenceRetries", Integer.valueOf(0));
        lastWhisperResult = result;
        lastDetectedLang = result.language;

        // Update status with transcription
        statusServer.update("lastUserText", result.text);
        statusServer.update("lastUserTextEn", result.textEn);
        statusServer.update("lastDetectedLang", result.language);

        log("Heard [" + result.language + "]: " + result.text);
        if (!result.textEn.equals(result.text)) {
            log("English: " + result.textEn);
        }

        // Check for goodbye
        if (isGoodbye(result.textEn)) {
            log("Goodbye detected");
            currentState = STATE_CLOSING;
            updateState(STATE_CLOSING);
            return;
        }

        currentState = STATE_THINKING;
        updateState(STATE_THINKING);
    }

    // ----------------------------------------------------------------
    // State: THINKING — send to LLM
    // ----------------------------------------------------------------

    private String pendingLLMResponse = null;

    private void handleThinking() {
        log("--- THINKING ---");
        gestureManager.setState(STATE_THINKING);

        if (lastWhisperResult == null) {
            currentState = STATE_LISTENING;
            updateState(STATE_LISTENING);
            return;
        }

        // Build system prompt with language context
        String langName = getLanguageName(lastDetectedLang);
        String systemPrompt = buildSystemPrompt(langName, lastDetectedLang);

        // Send English translation to LLM (reliable input for any language)
        String userText = lastWhisperResult.textEn;
        log("LLM input: " + userText);

        LlamaClient.LLMResult llmResult = llamaClient.chat(systemPrompt, userText);

        if (llmResult.isError()) {
            log("LLM error: " + llmResult.response);
            pendingLLMResponse = "Sorry, I had trouble thinking about that.";
        } else {
            pendingLLMResponse = llmResult.response;
            log("LLM response (" + String.format("%.0f", llmResult.totalMs) + "ms): "
                + pendingLLMResponse);
        }

        currentState = STATE_RESPONDING;
        updateState(STATE_RESPONDING);
    }

    // ----------------------------------------------------------------
    // State: RESPONDING — speak LLM response with gestures
    // ----------------------------------------------------------------

    private void handleResponding() {
        log("--- RESPONDING ---");
        gestureManager.setState(STATE_RESPONDING);

        if (pendingLLMResponse != null) {
            statusServer.update("lastSotaText", pendingLLMResponse);
            speechManager.speak(pendingLLMResponse);
            pendingLLMResponse = null;
        }

        CRobotUtil.wait(300);
        conversationTurn++;
        statusServer.update("turn", Integer.valueOf(conversationTurn));

        // Check turn limit
        if (conversationTurn >= MAX_CONVERSATION_TURNS) {
            log("Max conversation turns reached (" + MAX_CONVERSATION_TURNS + ")");
            currentState = STATE_CLOSING;
            updateState(STATE_CLOSING);
            return;
        }

        currentState = STATE_LISTENING;
        updateState(STATE_LISTENING);
    }

    // ----------------------------------------------------------------
    // State: CLOSING — say goodbye, return to IDLE
    // ----------------------------------------------------------------

    private void handleClosing() {
        log("--- CLOSING ---");
        gestureManager.setState(STATE_CLOSING);

        // Choose goodbye based on last detected language
        String goodbye;
        if ("ja".equals(lastDetectedLang)) {
            goodbye = "楽しかったよ！またね！";
        } else {
            goodbye = "It was nice talking to you! See you later!";
        }
        statusServer.update("lastSotaText", goodbye);
        speechManager.speak(goodbye);

        CRobotUtil.wait(1000);

        // Reset
        gestureManager.moveToNeutral(600);
        conversationTurn = 0;
        silenceRetryCount = 0;
        lastWhisperResult = null;
        pendingLLMResponse = null;
        lastDetectedLang = "en";

        // Reset status
        statusServer.update("turn", Integer.valueOf(0));
        statusServer.update("silenceRetries", Integer.valueOf(0));
        statusServer.update("lastUserText", "");
        statusServer.update("lastUserTextEn", "");
        statusServer.update("lastDetectedLang", "en");

        log("Cooldown " + COOLDOWN_MS + "ms...");
        CRobotUtil.wait(COOLDOWN_MS);

        currentState = STATE_IDLE;
        updateState(STATE_IDLE);
        log("State: IDLE -- waiting for face...");
    }

    // ----------------------------------------------------------------
    // Shutdown
    // ----------------------------------------------------------------

    private void shutdown() {
        log("Shutting down...");
        running = false;

        if (statusServer != null) {
            statusServer.update("state", "shutdown");
            statusServer.stop();
        }

        if (gestureManager != null) {
            gestureManager.stop();
        }

        if (motion != null) {
            try { motion.ServoOff(); } catch (Exception e) { /* ignore */ }
        }

        log("Shutdown complete.");
    }

    // ----------------------------------------------------------------
    // Status helpers
    // ----------------------------------------------------------------

    /** Update state in StatusServer. */
    private void updateState(String state) {
        if (statusServer != null) {
            statusServer.update("state", state);
        }
    }

    /** Check if Ollama is reachable. */
    private boolean checkOllama(String ollamaUrl) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(ollamaUrl + "/api/tags");
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            int code = conn.getResponseCode();
            return code == 200;
        } catch (Exception e) {
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    // ----------------------------------------------------------------
    // LLM System Prompt
    // ----------------------------------------------------------------

    private String buildSystemPrompt(String languageName, String languageCode) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are Sota, a friendly social robot. ");
        sb.append("The user spoke in ").append(languageName);
        sb.append(" (detected: ").append(languageCode).append("). ");
        sb.append("IMPORTANT: You can ONLY speak Japanese or English ");
        sb.append("(robot TTS limitation). ");

        if ("ja".equals(languageCode)) {
            sb.append("The user spoke Japanese, so respond in Japanese. ");
        } else {
            sb.append("Respond in English. ");
        }

        sb.append("Keep your response under 2 sentences. ");
        sb.append("Be warm, friendly, and natural. ");
        sb.append("Use knowledge of the user's language to understand their cultural context.");

        return sb.toString();
    }

    // ----------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------

    private boolean isGoodbye(String textEn) {
        if (textEn == null) return false;
        String lower = textEn.toLowerCase().trim();
        String[] goodbyePhrases = {
            "goodbye", "good bye", "bye bye", "bye-bye", "bye",
            "see you", "see ya", "gotta go", "i have to go",
            "talk to you later", "catch you later",
            "farewell", "take care"
        };
        for (int i = 0; i < goodbyePhrases.length; i++) {
            if (lower.contains(goodbyePhrases[i])) return true;
        }
        return false;
    }

    private String getLanguageName(String code) {
        if (code == null) return "Unknown";
        if ("ja".equals(code)) return "Japanese";
        if ("en".equals(code)) return "English";
        if ("id".equals(code)) return "Indonesian";
        if ("ko".equals(code)) return "Korean";
        if ("zh".equals(code)) return "Chinese";
        if ("fr".equals(code)) return "French";
        if ("de".equals(code)) return "German";
        if ("es".equals(code)) return "Spanish";
        if ("pt".equals(code)) return "Portuguese";
        if ("ar".equals(code)) return "Arabic";
        if ("hi".equals(code)) return "Hindi";
        if ("th".equals(code)) return "Thai";
        if ("vi".equals(code)) return "Vietnamese";
        if ("ru".equals(code)) return "Russian";
        return code;
    }

    private void log(String msg) {
        System.out.println("[" + TAG + "] " + msg);
    }
}
