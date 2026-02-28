package jp.vstone.sotawhisper;

import java.awt.Color;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import jp.vstone.RobotLib.CRobotMem;
import jp.vstone.RobotLib.CRobotPose;
import jp.vstone.RobotLib.CRobotUtil;
import jp.vstone.RobotLib.CSotaMotion;
import jp.vstone.camera.CRoboCamera;
import jp.vstone.camera.FaceDetectResult;
import jp.vstone.camera.FaceDetectLib.FaceUser;

/**
 * Main FSM program: Face detect -> Recognize -> Greet -> Listen -> LLM -> Respond -> Close.
 *
 * States: IDLE -> RECOGNIZING -> GREETING -> [REGISTERING] -> LISTENING -> THINKING -> RESPONDING -> CLOSING
 *
 * Features:
 *   - Face recognition with user memory (name, origin, language, social state)
 *   - Culture/ethnicity inference from detected language
 *   - Multi-turn conversation with context
 *   - Whisper STT with hallucination filtering
 *   - Embedded StatusServer for remote GUI monitoring
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

    private static final String STATE_IDLE        = "idle";
    private static final String STATE_RECOGNIZING = "recognizing";
    private static final String STATE_GREETING    = "greeting";
    private static final String STATE_REGISTERING = "registering";
    private static final String STATE_LISTENING   = "listening";
    private static final String STATE_THINKING    = "thinking";
    private static final String STATE_RESPONDING  = "responding";
    private static final String STATE_CLOSING     = "closing";

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

    private static final int    MAX_HISTORY_MESSAGES   = 6;  // max conversation history entries

    private static final String CAMERA_DEVICE = "/dev/video0";
    private static final String DATA_DIR      = "./data";

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
    private UserMemory            userMemory;

    // ----------------------------------------------------------------
    // Runtime state
    // ----------------------------------------------------------------

    private volatile String  currentState = STATE_IDLE;
    private volatile boolean running      = false;

    private int    conversationTurn   = 0;
    private int    silenceRetryCount  = 0;
    private String lastDetectedLang   = "en";

    // Current user for this interaction session
    private UserMemory.UserProfile currentUser = null;
    private boolean isNewUser = false;

    // Conversation history for multi-turn (list of JSON message strings)
    private List conversationHistory = new ArrayList();

    // Stored for status reporting
    private String laptopIp = "";

    // ----------------------------------------------------------------
    // Main entry point
    // ----------------------------------------------------------------

    public static void main(String[] args) {
        System.out.println("========================================================");
        System.out.println("  Sota Whisper Interaction v2");
        System.out.println("  Face -> Recognize -> Greet -> Listen -> LLM -> Respond");
        System.out.println("========================================================");
        System.out.println();

        if (args.length < 1) {
            System.out.println("Usage: java -jar whisperinteraction.jar <laptop_ip> [options]");
            System.out.println();
            System.out.println("Options:");
            System.out.println("  --whisper-port <port>   Whisper server port (default: 5050)");
            System.out.println("  --ollama-port <port>    Ollama server port (default: 11434)");
            System.out.println("  --model <name>          Ollama model name (default: llama3.2:3b)");
            System.out.println("  --status-port <port>    Status server port for GUI (default: 5051)");
            System.out.println();
            System.out.println("Example:");
            System.out.println("  java -jar whisperinteraction.jar 192.168.11.32");
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

        // 2. Camera (with face search enabled for recognition)
        camera = new CRoboCamera(CAMERA_DEVICE, motion);
        camera.setEnableFaceSearch(true);
        camera.setEnableAgeSexDetect(true);
        camera.StartFaceTraking();
        log("Camera initialized (face search + age/sex detection enabled)");

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
            log("WARNING: Whisper server not reachable! STT will fail.");
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

        // 7. User memory
        userMemory = new UserMemory(DATA_DIR);
        log("UserMemory loaded: " + userMemory.getProfileCount() + " profiles");

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
                } else if (STATE_RECOGNIZING.equals(currentState)) {
                    handleRecognizing();
                } else if (STATE_GREETING.equals(currentState)) {
                    handleGreeting();
                } else if (STATE_REGISTERING.equals(currentState)) {
                    handleRegistering();
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
                resetSession();
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
                    currentState = STATE_RECOGNIZING;
                    updateState(STATE_RECOGNIZING);
                    return;
                }
            } else {
                consecutiveDetections = 0;
            }
            CRobotUtil.wait(FACE_POLL_MS);
        }
    }

    // ----------------------------------------------------------------
    // State: RECOGNIZING — identify face, load user profile
    // ----------------------------------------------------------------

    private void handleRecognizing() {
        log("--- RECOGNIZING ---");
        gestureManager.setState(STATE_RECOGNIZING);

        // Get face user from camera
        FaceDetectResult faceResult = camera.getDetectResult();
        FaceUser faceUser = null;
        if (faceResult != null && faceResult.isDetect()) {
            faceUser = camera.getUser(faceResult);
        }

        // Extract age/sex if available
        int detectedAge = 0;
        String detectedGender = "unknown";
        if (faceResult != null && faceResult.isAgeSexDetect()) {
            detectedAge = faceResult.getAge();
            Boolean isMale = faceResult.isMale();
            detectedGender = (isMale != null && isMale.booleanValue()) ? "male" : "female";
        }

        if (faceUser != null && !faceUser.isNewUser()) {
            // Known user — load profile
            String userName = faceUser.getName();
            log("Recognized known user: " + userName);
            currentUser = userMemory.getProfileByName(userName);

            if (currentUser == null) {
                // Face registered but no profile (edge case) — create one
                log("WARN: Face registered as '" + userName + "' but no profile found. Creating.");
                currentUser = new UserMemory.UserProfile(userMemory.generateUserId(), userName);
                currentUser.estimatedAge = detectedAge;
                currentUser.gender = detectedGender;
                userMemory.addProfile(currentUser);
            }

            isNewUser = false;
            updateUserStatus();
        } else {
            // New face
            log("New face detected (age~" + detectedAge + ", " + detectedGender + ")");
            currentUser = new UserMemory.UserProfile(userMemory.generateUserId(), "");
            currentUser.estimatedAge = detectedAge;
            currentUser.gender = detectedGender;
            isNewUser = true;
            updateUserStatus();
        }

        currentState = STATE_GREETING;
        updateState(STATE_GREETING);
    }

    // ----------------------------------------------------------------
    // State: GREETING — say hello (personalized or generic)
    // ----------------------------------------------------------------

    private void handleGreeting() {
        log("--- GREETING ---");
        gestureManager.setState(STATE_GREETING);
        conversationTurn = 0;
        silenceRetryCount = 0;
        conversationHistory.clear();
        statusServer.update("turn", Integer.valueOf(0));
        statusServer.update("silenceRetries", Integer.valueOf(0));

        String greeting;
        if (!isNewUser && currentUser != null && !currentUser.name.isEmpty()) {
            // Personalized greeting for returning user
            String socialLabel = currentUser.socialState;
            if (UserMemory.SOCIAL_CLOSE.equals(socialLabel)) {
                greeting = "Hey " + currentUser.name + "! Great to see you again! How's your day going?";
            } else if (UserMemory.SOCIAL_FRIENDLY.equals(socialLabel)) {
                greeting = "Hi " + currentUser.name + "! Nice to see you again! What's up?";
            } else {
                greeting = "Hello " + currentUser.name + "! Welcome back! How are you?";
            }
            log("Returning user greeting: " + greeting);
        } else {
            // Generic greeting for new user
            greeting = "Hello! I'm Sota, a friendly robot. Nice to meet you!";
            log("New user greeting");
        }

        statusServer.update("lastSotaText", greeting);
        speechManager.speak(greeting);
        CRobotUtil.wait(300);

        if (isNewUser) {
            currentState = STATE_REGISTERING;
            updateState(STATE_REGISTERING);
        } else {
            currentState = STATE_LISTENING;
            updateState(STATE_LISTENING);
        }
    }

    // ----------------------------------------------------------------
    // State: REGISTERING — ask name, detect culture, register face
    // ----------------------------------------------------------------

    private void handleRegistering() {
        log("--- REGISTERING ---");
        gestureManager.setState(STATE_REGISTERING);

        // Step 1: Ask for name
        String askName = "What's your name?";
        statusServer.update("lastSotaText", askName);
        speechManager.speak(askName);

        String name = speechManager.listenForName(LISTEN_DURATION_MS);
        if (name == null || name.isEmpty()) {
            // Retry once
            String retry = "Sorry, I couldn't hear that. Could you tell me your name again?";
            statusServer.update("lastSotaText", retry);
            speechManager.speak(retry);
            name = speechManager.listenForName(LISTEN_DURATION_MS);
        }

        if (name == null || name.isEmpty()) {
            name = "Friend";
            log("Could not get name, using default: " + name);
        }

        log("User name: " + name);
        currentUser.name = name;
        statusServer.update("userName", name);

        // Register face with camera
        FaceDetectResult faceResult = camera.getDetectResult();
        if (faceResult != null && faceResult.isDetect()) {
            FaceUser user = camera.getUser(faceResult);
            if (user != null) {
                user.setName(name);
                boolean registered = camera.addUser(user);
                if (registered) {
                    log("Face registered for: " + name);
                } else {
                    log("WARN: Face registration failed for: " + name);
                    // Retry with fresh detection
                    CRobotUtil.wait(500);
                    faceResult = camera.getDetectResult();
                    if (faceResult != null && faceResult.isDetect()) {
                        user = camera.getUser(faceResult);
                        if (user != null && user.isNewUser()) {
                            user.setName(name);
                            registered = camera.addUser(user);
                            log(registered ? "Face registered on retry" : "Face registration failed on retry");
                        }
                    }
                }
            }
        }

        // Step 2: Culture/origin detection via language
        // First, do a short listen to detect language
        String askOrigin = "Nice to meet you, " + name + "! Where are you from?";
        statusServer.update("lastSotaText", askOrigin);
        speechManager.speak(askOrigin);

        WhisperSTT.WhisperResult originResult = speechManager.listen(LISTEN_DURATION_MS);
        if (originResult.ok && !originResult.textEn.isEmpty()) {
            // Store detected language
            currentUser.detectedLanguage = originResult.language;
            lastDetectedLang = originResult.language;

            // Try to extract origin from the response
            String originText = originResult.textEn.trim();
            log("Origin response [" + originResult.language + "]: " + originText);

            // The user's answer likely contains their country/origin
            currentUser.origin = extractOrigin(originText);
            if (currentUser.origin.isEmpty()) {
                // If couldn't extract, try language-based inference
                String inferred = UserMemory.languageToCountry(originResult.language);
                if (inferred != null) {
                    currentUser.origin = inferred;
                    log("Inferred origin from language: " + inferred);
                }
            }

            currentUser.preferredLanguage = originResult.language;

            // Confirm
            if (!currentUser.origin.isEmpty()) {
                String confirm = "Oh, " + currentUser.origin + "! That's wonderful!";
                statusServer.update("lastSotaText", confirm);
                speechManager.speak(confirm);
            }
        } else {
            // Couldn't hear origin, try language inference
            String inferred = UserMemory.languageToCountry(lastDetectedLang);
            if (inferred != null) {
                currentUser.origin = inferred;
                log("No origin response, inferred from language: " + inferred);
            }
        }

        // Save profile
        currentUser.recordInteraction();
        userMemory.addProfile(currentUser);
        updateUserStatus();
        log("Profile saved: " + currentUser);

        currentState = STATE_LISTENING;
        updateState(STATE_LISTENING);
    }

    // ----------------------------------------------------------------
    // State: LISTENING — record with VAD + send to Whisper
    // ----------------------------------------------------------------

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

            gestureManager.setState(STATE_RESPONDING);
            String retryMsg = "I can't hear you. Could you say that again?";
            statusServer.update("lastSotaText", retryMsg);
            speechManager.speak(retryMsg);
            CRobotUtil.wait(200);
            return;
        }

        // Reset silence counter
        silenceRetryCount = 0;
        statusServer.update("silenceRetries", Integer.valueOf(0));
        lastWhisperResult = result;
        lastDetectedLang = result.language;

        // Update detected language on profile
        if (currentUser != null && (currentUser.detectedLanguage.isEmpty()
                || !currentUser.detectedLanguage.equals(result.language))) {
            currentUser.detectedLanguage = result.language;
            currentUser.preferredLanguage = result.language;
        }

        // Update status
        statusServer.update("lastUserText", result.text);
        statusServer.update("lastUserTextEn", result.textEn);
        statusServer.update("lastDetectedLang", result.language);

        log("Heard [" + result.language + "]: " + result.text);

        // Add to conversation history
        conversationHistory.add(LlamaClient.jsonMessage("user", result.textEn));
        trimHistory();

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
    // State: THINKING — send to LLM with context
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

        // Build system prompt with user context
        String systemPrompt = buildSystemPrompt();

        // Use multi-turn if we have history
        LlamaClient.LLMResult llmResult;
        if (conversationHistory.size() > 1) {
            llmResult = llamaClient.chatMultiTurn(systemPrompt, conversationHistory);
        } else {
            llmResult = llamaClient.chat(systemPrompt, lastWhisperResult.textEn);
        }

        if (llmResult.isError()) {
            log("LLM error: " + llmResult.response);
            pendingLLMResponse = "Sorry, I had trouble thinking about that.";
        } else {
            pendingLLMResponse = llmResult.response;
            log("LLM response (" + String.format("%.0f", llmResult.totalMs) + "ms): "
                + pendingLLMResponse);
        }

        // Add assistant response to history
        if (pendingLLMResponse != null) {
            conversationHistory.add(LlamaClient.jsonMessage("assistant", pendingLLMResponse));
            trimHistory();
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
    // State: CLOSING — say goodbye, save memory, return to IDLE
    // ----------------------------------------------------------------

    private void handleClosing() {
        log("--- CLOSING ---");
        gestureManager.setState(STATE_CLOSING);

        // Generate memory summary if we had a conversation
        if (currentUser != null && conversationHistory.size() >= 2) {
            generateMemorySummary();
            currentUser.recordInteraction();
            userMemory.updateProfile(currentUser);
            log("Profile updated: " + currentUser);
        }

        // Personalized goodbye
        String goodbye;
        if (currentUser != null && !currentUser.name.isEmpty()) {
            if ("ja".equals(lastDetectedLang)) {
                goodbye = currentUser.name + "さん、楽しかったよ！またね！";
            } else {
                goodbye = "It was great talking to you, " + currentUser.name + "! See you next time!";
            }
        } else {
            if ("ja".equals(lastDetectedLang)) {
                goodbye = "楽しかったよ！またね！";
            } else {
                goodbye = "It was nice talking to you! See you later!";
            }
        }
        statusServer.update("lastSotaText", goodbye);
        speechManager.speak(goodbye);

        CRobotUtil.wait(1000);

        // Reset
        gestureManager.moveToNeutral(600);
        resetSession();

        log("Cooldown " + COOLDOWN_MS + "ms...");
        CRobotUtil.wait(COOLDOWN_MS);

        currentState = STATE_IDLE;
        updateState(STATE_IDLE);
        log("State: IDLE -- waiting for face...");
    }

    // ----------------------------------------------------------------
    // Memory summary generation
    // ----------------------------------------------------------------

    private void generateMemorySummary() {
        if (conversationHistory.isEmpty()) return;

        // Build a brief conversation transcript for summarization
        StringBuilder transcript = new StringBuilder();
        for (int i = 0; i < conversationHistory.size(); i++) {
            String msg = (String) conversationHistory.get(i);
            // Extract role and content from JSON message
            String role = extractSimpleJsonValue(msg, "role");
            String content = extractSimpleJsonValue(msg, "content");
            if (role != null && content != null) {
                transcript.append(role).append(": ").append(content).append("\n");
            }
        }

        String summaryPrompt = "Summarize this conversation in 1-2 short sentences for future reference. "
            + "Focus on key topics discussed, user interests, and any personal information shared. "
            + "Be concise.\n\nConversation:\n" + transcript.toString();

        log("Generating memory summary...");
        LlamaClient.LLMResult result = llamaClient.chat(
            "You are a helpful assistant that creates brief conversation summaries.", summaryPrompt);

        if (!result.isError() && !result.response.isEmpty()) {
            // Append to existing summary
            String existing = currentUser.shortMemorySummary;
            if (existing != null && !existing.isEmpty()) {
                currentUser.shortMemorySummary = existing + " | " + result.response.trim();
            } else {
                currentUser.shortMemorySummary = result.response.trim();
            }
            // Limit summary length
            if (currentUser.shortMemorySummary.length() > 500) {
                currentUser.shortMemorySummary = currentUser.shortMemorySummary.substring(0, 500);
            }
            log("Memory summary: " + currentUser.shortMemorySummary);
        } else {
            log("WARN: Could not generate memory summary");
        }
    }

    // ----------------------------------------------------------------
    // System prompt builder
    // ----------------------------------------------------------------

    private String buildSystemPrompt() {
        String langName = getLanguageName(lastDetectedLang);

        StringBuilder sb = new StringBuilder();
        sb.append("You are Sota, a friendly social robot. ");

        // User context
        if (currentUser != null && !currentUser.name.isEmpty()) {
            sb.append("You are talking to ").append(currentUser.name).append(". ");

            if (!currentUser.origin.isEmpty()) {
                sb.append(currentUser.name).append(" is from ").append(currentUser.origin).append(". ");
            }

            if (currentUser.interactionCount > 1) {
                sb.append("You have met ").append(currentUser.interactionCount)
                  .append(" times before. ");
            }

            if (!currentUser.shortMemorySummary.isEmpty()) {
                sb.append("Previous conversations: ").append(currentUser.shortMemorySummary).append(" ");
            }
        }

        // Language instructions
        sb.append("The user spoke in ").append(langName);
        sb.append(" (detected: ").append(lastDetectedLang).append("). ");
        sb.append("IMPORTANT: You can ONLY speak Japanese or English (robot TTS limitation). ");

        if ("ja".equals(lastDetectedLang)) {
            sb.append("The user spoke Japanese, so respond in Japanese. ");
        } else {
            sb.append("Respond in English. ");
        }

        sb.append("Keep your response under 2 sentences. ");
        sb.append("Be warm, friendly, and natural. ");
        sb.append("Show genuine interest in the user's culture and background.");

        return sb.toString();
    }

    // ----------------------------------------------------------------
    // Session management
    // ----------------------------------------------------------------

    private void resetSession() {
        conversationTurn = 0;
        silenceRetryCount = 0;
        lastWhisperResult = null;
        pendingLLMResponse = null;
        lastDetectedLang = "en";
        currentUser = null;
        isNewUser = false;
        conversationHistory.clear();

        // Reset status
        statusServer.update("turn", Integer.valueOf(0));
        statusServer.update("silenceRetries", Integer.valueOf(0));
        statusServer.update("lastUserText", "");
        statusServer.update("lastUserTextEn", "");
        statusServer.update("lastDetectedLang", "en");
        statusServer.update("userName", "");
        statusServer.update("userOrigin", "");
        statusServer.update("userInteractions", Integer.valueOf(0));
        statusServer.update("userSocialLevel", "");
        statusServer.update("detectedLanguage", "");
    }

    /** Update status server with current user info. */
    private void updateUserStatus() {
        if (currentUser != null) {
            statusServer.update("userName", currentUser.name);
            statusServer.update("userOrigin", currentUser.origin);
            statusServer.update("userInteractions", Integer.valueOf(currentUser.interactionCount));
            statusServer.update("userSocialLevel", currentUser.socialState);
            statusServer.update("detectedLanguage", currentUser.detectedLanguage);
        }
    }

    /** Keep conversation history within bounds. */
    private void trimHistory() {
        while (conversationHistory.size() > MAX_HISTORY_MESSAGES) {
            conversationHistory.remove(0);
        }
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

    private void updateState(String state) {
        if (statusServer != null) {
            statusServer.update("state", state);
        }
    }

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
    // Helpers
    // ----------------------------------------------------------------

    /** Extract origin from user response text (English translation). */
    private String extractOrigin(String textEn) {
        if (textEn == null || textEn.isEmpty()) return "";

        String lower = textEn.toLowerCase().trim();

        // Remove common prefixes
        String[] prefixes = {
            "i'm from ", "i am from ", "i come from ", "i came from ",
            "from ", "it's ", "it is "
        };
        String cleaned = lower;
        for (int i = 0; i < prefixes.length; i++) {
            if (lower.startsWith(prefixes[i])) {
                cleaned = textEn.trim().substring(prefixes[i].length()).trim();
                break;
            }
        }

        // Remove trailing punctuation
        if (cleaned.endsWith(".") || cleaned.endsWith(",") || cleaned.endsWith("!")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1).trim();
        }

        // Capitalize first letter
        if (!cleaned.isEmpty()) {
            cleaned = cleaned.substring(0, 1).toUpperCase() + cleaned.substring(1);
        }

        return cleaned;
    }

    /** Extract a simple string value from a JSON message. */
    private String extractSimpleJsonValue(String json, String key) {
        String pattern = "\"" + key + "\":\"";
        int start = json.indexOf(pattern);
        if (start < 0) return null;
        start += pattern.length();
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) {
                char next = json.charAt(i + 1);
                if (next == '"')  { sb.append('"'); i++; }
                else if (next == '\\') { sb.append('\\'); i++; }
                else if (next == 'n')  { sb.append('\n'); i++; }
                else { sb.append(c); }
            } else if (c == '"') {
                break;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

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
        if ("ms".equals(code)) return "Malay";
        if ("tr".equals(code)) return "Turkish";
        return code;
    }

    private void log(String msg) {
        System.out.println("[" + TAG + "] " + msg);
    }
}
