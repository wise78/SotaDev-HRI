package jp.vstone.sotawhisper;

import java.awt.Color;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.imgcodecs.Imgcodecs;

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
 *   - DeepFace ethnicity detection for personalized greetings
 *   - Sound effects on state transitions
 *   - Whisper STT with hallucination filtering
 *   - Embedded StatusServer for remote GUI monitoring
 *
 * Usage:
 *   java -jar whisperinteraction.jar <laptop_ip>
 *   java -jar whisperinteraction.jar <laptop_ip> --status-port 5051
 *   java -jar whisperinteraction.jar <laptop_ip> --no-memory --participant-id P01 --group G1 --session 1
 *   java -jar whisperinteraction.jar <laptop_ip> --participant-id P01 --group G1 --session 2 --target-name Azul
 *   java -jar whisperinteraction.jar <laptop_ip> --no-memory --participant-id P08 --group G2 --session 2 --target-name Rupak
 *
 * Experiment flags:
 *   --no-memory         Ignore stored profiles, always treat as new user (still saves)
 *   --participant-id    Participant ID for research logging (e.g., P01)
 *   --group             Group assignment: G1 or G2
 *   --session           Session number: 1 or 2
 *   --target-name       Expected user name for Session 2 profile lookup
 *
 * Session 2 behavior:
 *   REMEMBER (G1, no --no-memory):  Loads profile by --target-name, greets by name with memory
 *   NO-REMEMBER (G2, --no-memory):  Loads profile by --target-name, pretends to forget name
 *                                    but subtly references origin, asks name again
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

    private static final int    MAX_SILENCE_RETRIES    = 4;
    private static final int    FACE_GONE_THRESHOLD    = 3;  // consecutive no-face checks before closing
    private static final int    LISTEN_DURATION_MS     = 20000;
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
    private DeepFaceClient        deepFaceClient;

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

    // Stored WhisperResult from name listen (for extracting origin)
    private WhisperSTT.WhisperResult lastNameListenResult = null;

    // Conversation history for multi-turn (list of JSON message strings)
    private List conversationHistory = new ArrayList();

    // Stored for status reporting
    private String laptopIp = "";

    // Experiment flags
    private boolean noMemory     = false;  // --no-memory: skip profile lookup
    private boolean videoCallMode = false; // --video-call: skip camera/face detection, auto-start
    private String  participantId = "";    // --participant-id
    private String  groupId       = "";    // --group (G1/G2)
    private String  sessionNum    = "";    // --session (1/2)
    private String  targetName    = "";    // --target-name: expected user name for Session 2 lookup
    private boolean pretendForget = false;  // Session 2 NO-REMEMBER: have context but pretend to forget name
    private volatile String baseLanguage = "en";  // "en" or "ja" — robot's response language

    // ----------------------------------------------------------------
    // Main entry point
    // ----------------------------------------------------------------

    public static void main(String[] args) {
        System.out.println("========================================================");
        System.out.println("  Sota Whisper Interaction v3");
        System.out.println("  Face -> DeepFace -> Recognize -> Greet -> Listen -> LLM -> Respond");
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
            System.out.println("  --no-memory             Ignore stored profiles (always new user)");
            System.out.println("  --participant-id <id>   Participant ID (e.g., P01)");
            System.out.println("  --group <g1|g2>         Group assignment");
            System.out.println("  --session <1|2>         Session number");
            System.out.println("  --target-name <name>    Expected user name for Session 2 profile lookup");
            System.out.println("  --language <en|ja>      Base response language (default: en)");
            System.out.println("  --video-call            Skip camera/face detection, auto-start (for remote sessions)");
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
        boolean noMemoryFlag = false;
        boolean videoCallFlag = false;
        String pidFlag      = "";
        String groupFlag    = "";
        String sessionFlag  = "";
        String targetNameFlag = "";
        String langFlag     = "en";

        for (int i = 1; i < args.length; i++) {
            if ("--whisper-port".equals(args[i]) && i + 1 < args.length) {
                whisperPort = Integer.parseInt(args[++i]);
            } else if ("--ollama-port".equals(args[i]) && i + 1 < args.length) {
                ollamaPort = Integer.parseInt(args[++i]);
            } else if ("--model".equals(args[i]) && i + 1 < args.length) {
                modelName = args[++i];
            } else if ("--status-port".equals(args[i]) && i + 1 < args.length) {
                statusPort = Integer.parseInt(args[++i]);
            } else if ("--no-memory".equals(args[i])) {
                noMemoryFlag = true;
            } else if ("--participant-id".equals(args[i]) && i + 1 < args.length) {
                pidFlag = args[++i];
            } else if ("--group".equals(args[i]) && i + 1 < args.length) {
                groupFlag = args[++i].toUpperCase();
            } else if ("--session".equals(args[i]) && i + 1 < args.length) {
                sessionFlag = args[++i];
            } else if ("--target-name".equals(args[i]) && i + 1 < args.length) {
                targetNameFlag = args[++i];
            } else if ("--language".equals(args[i]) && i + 1 < args.length) {
                langFlag = args[++i].toLowerCase();
            } else if ("--video-call".equals(args[i])) {
                videoCallFlag = true;
            }
        }

        System.out.println("  Laptop IP    : " + laptopIp);
        System.out.println("  Whisper      : http://" + laptopIp + ":" + whisperPort);
        System.out.println("  Ollama       : http://" + laptopIp + ":" + ollamaPort);
        System.out.println("  Model        : " + modelName);
        System.out.println("  Status       : http://0.0.0.0:" + statusPort + "/status");
        if (noMemoryFlag) {
            System.out.println("  Memory       : DISABLED (--no-memory)");
        } else {
            System.out.println("  Memory       : ENABLED");
        }
        if (!pidFlag.isEmpty()) {
            System.out.println("  Participant  : " + pidFlag);
            System.out.println("  Group        : " + groupFlag);
            System.out.println("  Session      : " + sessionFlag);
        }
        if (!targetNameFlag.isEmpty()) {
            System.out.println("  Target Name  : " + targetNameFlag);
        }
        System.out.println("  Language     : " + langFlag.toUpperCase());
        if (videoCallFlag) {
            System.out.println("  Video Call   : ENABLED (no camera, auto-start)");
        }
        System.out.println();

        WhisperInteraction app = new WhisperInteraction();
        app.noMemory = noMemoryFlag;
        app.videoCallMode = videoCallFlag;
        app.participantId = pidFlag;
        app.groupId = groupFlag;
        app.sessionNum = sessionFlag;
        app.targetName = targetNameFlag;
        app.baseLanguage = langFlag;
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
        statusServer.update("baseLanguage", baseLanguage);
        statusServer.setLanguageChangeListener(new StatusServer.LanguageChangeListener() {
            public void onLanguageChanged(String lang) {
                baseLanguage = lang;
                log("Base language changed via GUI: " + lang);
            }
        });
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
            new Byte[]  {1,    2,     3, 4,   5, 6, 7, 8},
            new Short[] {0, -900,     0, 900, 0, 0, 0, 0}
        );  // Natural hanging pose: L_SHOULDER=-900, R_SHOULDER=+900
        initPose.setLED_Sota(Color.WHITE, Color.WHITE, 200, Color.WHITE);
        motion.play(initPose, 800);
        CRobotUtil.wait(900);

        // 2. Camera (with face search enabled for recognition)
        if (!videoCallMode) {
            camera = new CRoboCamera(CAMERA_DEVICE, motion);
            camera.setEnableFaceSearch(true);
            camera.setEnableAgeSexDetect(true);
            // Log any faces already in the camera's in-memory list
            try {
                String[] savedUsers = camera.getAllUserNames();
                if (savedUsers != null && savedUsers.length > 0) {
                    log("Camera has " + savedUsers.length + " registered faces in memory");
                    for (int i = 0; i < savedUsers.length; i++) {
                        log("  - Registered face: " + savedUsers[i]);
                    }
                } else {
                    log("No registered faces in camera memory (name-based recognition will be used for returning users)");
                }
            } catch (Exception e) {
                log("WARN: Could not query camera face list: " + e.getMessage());
            }
            camera.StartFaceTraking();
            log("Camera initialized (face search + age/sex detection enabled)");
        } else {
            log("Video-call mode: camera DISABLED (no face detection/tracking)");
        }

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
        statusServer.setUserMemory(userMemory);

        // 8. DeepFace client (shares same server as Whisper)
        deepFaceClient = new DeepFaceClient(laptopIp, whisperPort);
        log("DeepFaceClient ready");

        // Update status
        statusServer.update("maxTurns", Integer.valueOf(0));  // 0 = unlimited
        statusServer.update("noMemory", Boolean.valueOf(noMemory));
        statusServer.update("participantId", participantId);
        statusServer.update("group", groupId);
        statusServer.update("session", sessionNum);

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
        if (videoCallMode) {
            log("State: IDLE -- video-call mode, auto-starting...");
        } else {
            log("State: IDLE -- waiting for face...");
        }

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
                // Restart face tracking after error recovery
                if (camera != null) {
                    try { camera.StartFaceTraking(); } catch (Exception ex) { /* ignore */ }
                }
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

        // Video-call mode: skip face detection, auto-start conversation
        if (videoCallMode) {
            log("Video-call mode: skipping face detection, auto-starting...");
            CRobotUtil.wait(1000);  // Brief pause before starting
            currentState = STATE_RECOGNIZING;
            updateState(STATE_RECOGNIZING);
            return;
        }

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
        SoundEffects.play(SoundEffects.SFX_FACE_DETECTED);

        // ============================================================
        // Session 2 shortcut: load profile by target name, skip face registration
        // ============================================================
        if ("2".equals(sessionNum) && !targetName.isEmpty()) {
            UserMemory.UserProfile profile = userMemory.getProfileByName(targetName);
            if (profile != null) {
                log("Session 2: Loaded profile for target user: " + targetName);
                currentUser = profile;

                if (!noMemory) {
                    // REMEMBER (G1-S2): full memory — recognized returning user
                    isNewUser = false;
                    if (currentUser.preferredLanguage != null && !currentUser.preferredLanguage.isEmpty()) {
                        recordDetectedLanguage(currentUser.preferredLanguage);
                    }
                    log("REMEMBER mode: greeting as returning friend (origin=" + currentUser.origin
                        + ", interactions=" + currentUser.interactionCount + ")");
                } else {
                    // NO-REMEMBER (G2-S2): load context but pretend to forget name
                    pretendForget = true;
                    isNewUser = true;  // will trigger simplified registration
                    log("NO-REMEMBER mode: pretending to forget name (origin=" + currentUser.origin + ")");
                }

                updateUserStatus();
                currentState = STATE_GREETING;
                updateState(STATE_GREETING);
                return;
            } else {
                log("WARN: Target user '" + targetName + "' not found in profiles. Falling back to normal flow.");
            }
        }

        // Video-call mode: no camera, create new user profile directly
        if (videoCallMode) {
            log("Video-call mode: skipping camera face recognition");
            // If target name is set, try to load profile by name
            if (!targetName.isEmpty()) {
                UserMemory.UserProfile profile = userMemory.getProfileByName(targetName);
                if (profile != null) {
                    log("Video-call: loaded profile for: " + targetName);
                    currentUser = profile;
                    isNewUser = false;
                    if (currentUser.preferredLanguage != null && !currentUser.preferredLanguage.isEmpty()) {
                        recordDetectedLanguage(currentUser.preferredLanguage);
                    }
                    updateUserStatus();
                    currentState = STATE_GREETING;
                    updateState(STATE_GREETING);
                    return;
                }
            }
            // New user in video-call mode
            currentUser = new UserMemory.UserProfile(userMemory.generateUserId(), "");
            if (!targetName.isEmpty()) {
                currentUser.name = targetName;
                log("Video-call: new user with preset name: " + targetName);
            }
            isNewUser = true;
            updateUserStatus();
            currentState = STATE_GREETING;
            updateState(STATE_GREETING);
            return;
        }

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

        // Also try getUserFromList which may use a different matching strategy
        if (faceUser != null && faceUser.isNewUser() && faceResult != null) {
            FaceUser listUser = camera.getUserFromList(faceResult);
            if (listUser != null && !listUser.isNewUser()) {
                log("getUserFromList matched a known user: " + listUser.getName());
                faceUser = listUser;
            }
        }

        if (!noMemory && faceUser != null && !faceUser.isNewUser()) {
            // Known user — load profile (only when memory is enabled)
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
            // If we have language memory, use it immediately for greeting before first utterance.
            if (currentUser.preferredLanguage != null && !currentUser.preferredLanguage.isEmpty()) {
                recordDetectedLanguage(currentUser.preferredLanguage);
            }
            updateUserStatus();
        } else if (noMemory && faceUser != null && !faceUser.isNewUser()) {
            // --no-memory: face is known but we pretend it's new
            String userName = faceUser.getName();
            log("Memory DISABLED: Ignoring known face '" + userName + "', treating as stranger");
            currentUser = new UserMemory.UserProfile(userMemory.generateUserId(), "");
            currentUser.estimatedAge = detectedAge;
            currentUser.gender = detectedGender;
            isNewUser = true;

            // Still run DeepFace for ethnicity
            analyzeEthnicity(faceResult);
            updateUserStatus();
        } else {
            // New face — but check if we have stored profiles (camera may have failed to match)
            log("New face detected (age~" + detectedAge + ", " + detectedGender + ")");

            if (!noMemory && userMemory.getProfileCount() > 0) {
                // There are known users — ask if this is a returning user
                UserMemory.UserProfile matchedProfile = tryNameBasedRecognition();
                if (matchedProfile != null) {
                    // Returning user identified by name!
                    log("Returning user identified by name: " + matchedProfile.name);
                    currentUser = matchedProfile;
                    isNewUser = false;
                    // Re-register face for better future recognition
                    reRegisterFaceForUser(matchedProfile.name);
                    if (currentUser.preferredLanguage != null && !currentUser.preferredLanguage.isEmpty()) {
                        recordDetectedLanguage(currentUser.preferredLanguage);
                    }
                    updateUserStatus();
                    currentState = STATE_GREETING;
                    updateState(STATE_GREETING);
                    return;
                }
            }

            // Genuinely new user
            currentUser = new UserMemory.UserProfile(userMemory.generateUserId(), "");
            currentUser.estimatedAge = detectedAge;
            currentUser.gender = detectedGender;
            isNewUser = true;

            // DeepFace ethnicity detection
            analyzeEthnicity(faceResult);

            updateUserStatus();
        }

        currentState = STATE_GREETING;
        updateState(STATE_GREETING);
    }

    // ----------------------------------------------------------------
    // Name-based returning user recognition (fallback when face fails)
    // ----------------------------------------------------------------

    /**
     * When camera face recognition fails but we have profiles in memory,
     * ask the user if they have visited before and try to match by name.
     * Returns the matched UserProfile, or null if this is genuinely a new user.
     */
    private UserMemory.UserProfile tryNameBasedRecognition() {
        // Stop face tracking temporarily so gestures/speech work
        if (camera != null) { camera.StopFaceTraking(); }

        String askRemember = "ja".equals(baseLanguage)
            ? "こんにちは！前にお会いしたことはありますか？名前を教えてくれますか？"
            : "Hello! Have we met before? What's your name?";
        statusServer.update("lastSotaText", askRemember);
        speechManager.speak(askRemember);

        WhisperSTT.WhisperResult result = speechManager.listen(
            LISTEN_DURATION_MS, shouldTranslateForCurrentMode());

        // Restart face tracking
        if (camera != null) {
            try { camera.StartFaceTraking(); } catch (Exception e) { /* ignore */ }
        }

        if (!result.ok) {
            log("Name-based recognition: no speech detected");
            return null;
        }

        String text = "ja".equals(baseLanguage) ? result.text : result.textEn;
        if (text == null || text.isEmpty()) return null;
        String lower = text.toLowerCase();
        log("Name-based recognition response: " + text);

        // Check for negative responses (new user)
        String[] negatives = {"no", "not", "first time", "never", "new here",
                              "haven't", "haven't met", "don't think so",
                              "いいえ", "初めて", "会ったことない", "ない"};
        for (int i = 0; i < negatives.length; i++) {
            if (lower.contains(negatives[i])) {
                log("Name-based recognition: user says they are new");
                return null;
            }
        }

        // Try to extract a name from the response
        String candidateName = extractNameFromResponse(text);
        if (candidateName == null || candidateName.isEmpty()) {
            log("Name-based recognition: could not extract name from response");
            return null;
        }

        log("Name-based recognition: candidate name = " + candidateName);

        // Search profiles for a match (case-insensitive, fuzzy)
        UserMemory.UserProfile match = userMemory.getProfileByName(candidateName);
        if (match != null) {
            log("Name-based recognition: EXACT match found -> " + match.name);
            return match;
        }

        // Try fuzzy matching: check if any profile name starts with or contains the candidate
        List allProfiles = userMemory.getAllProfiles();
        String candidateLower = candidateName.toLowerCase();
        for (int i = 0; i < allProfiles.size(); i++) {
            UserMemory.UserProfile p = (UserMemory.UserProfile) allProfiles.get(i);
            String profileNameLower = p.name.toLowerCase();
            // Check: profile name starts with candidate, or candidate starts with profile name
            if (profileNameLower.startsWith(candidateLower) || candidateLower.startsWith(profileNameLower)) {
                log("Name-based recognition: FUZZY match '" + candidateName + "' -> '" + p.name + "'");
                return p;
            }
            // Check: Levenshtein-like similarity for short names (1 char difference allowed)
            if (Math.abs(profileNameLower.length() - candidateLower.length()) <= 1
                    && profileNameLower.length() >= 3) {
                int diffs = 0;
                int minLen = Math.min(profileNameLower.length(), candidateLower.length());
                for (int c = 0; c < minLen; c++) {
                    if (profileNameLower.charAt(c) != candidateLower.charAt(c)) diffs++;
                }
                diffs += Math.abs(profileNameLower.length() - candidateLower.length());
                if (diffs <= 1) {
                    log("Name-based recognition: CLOSE match '" + candidateName + "' -> '" + p.name
                        + "' (diffs=" + diffs + ")");
                    return p;
                }
            }
        }

        log("Name-based recognition: no profile matches '" + candidateName + "'");
        return null;
    }

    /**
     * Extract a name from a "Have we met before?" response.
     * Handles: "Yes, my name is Bijak", "Bijak", "I'm Bijak", "Yes, it's Bijak", etc.
     */
    private String extractNameFromResponse(String text) {
        if (text == null || text.isEmpty()) return null;

        // Strip common affirmative prefixes
        String lower = text.toLowerCase();
        String[] affirmatives = {"yes ", "yeah ", "yep ", "sure ", "of course ",
                                 "yes, ", "yeah, ", "yep, ", "sure, ", "of course, ",
                                 "はい、", "はい", "うん、", "うん"};
        String cleaned = text;
        for (int i = 0; i < affirmatives.length; i++) {
            if (lower.startsWith(affirmatives[i])) {
                cleaned = text.substring(affirmatives[i].length()).trim();
                break;
            }
        }

        // Now try name markers
        String[] markers = {"my name is ", "name is ", "i'm ", "i am ", "it's ", "call me "};
        String cleanedLower = cleaned.toLowerCase();
        for (int i = 0; i < markers.length; i++) {
            int idx = cleanedLower.indexOf(markers[i]);
            if (idx >= 0) {
                String after = cleaned.substring(idx + markers[i].length()).trim();
                // Cut at comma/period
                int cut = after.length();
                for (int c = 0; c < after.length(); c++) {
                    char ch = after.charAt(c);
                    if (ch == ',' || ch == '.' || ch == '!' || ch == '?') {
                        if (c > 0) { cut = c; break; }
                    }
                }
                String name = after.substring(0, cut).trim();
                // Take first 1-3 words
                String[] words = name.split("\\s+");
                if (words.length > 3) {
                    name = words[0];
                } else if (words.length > 1) {
                    // Check if second word is a filler, not part of name
                    String secondLower = words[1].toLowerCase();
                    boolean isFiller = secondLower.equals("and") || secondLower.equals("from")
                        || secondLower.equals("but") || secondLower.equals("do")
                        || secondLower.equals("nice") || secondLower.equals("we");
                    if (isFiller) {
                        name = words[0];
                    }
                }
                // Remove trailing punctuation
                while (name.length() > 0) {
                    char last = name.charAt(name.length() - 1);
                    if (last == '.' || last == ',' || last == '!' || last == '?') {
                        name = name.substring(0, name.length() - 1).trim();
                    } else break;
                }
                if (!name.isEmpty() && name.length() <= 30) return name;
            }
        }

        // If no marker, the whole response might just be a name (e.g. "Bijak")
        // Take first word only if it's short enough and not a common word
        String[] words = cleaned.split("\\s+");
        if (words.length <= 3 && cleaned.length() <= 30) {
            String candidate = words[0].trim();
            // Remove trailing punctuation
            while (candidate.length() > 0) {
                char last = candidate.charAt(candidate.length() - 1);
                if (last == '.' || last == ',' || last == '!' || last == '?') {
                    candidate = candidate.substring(0, candidate.length() - 1).trim();
                } else break;
            }
            // Don't return common words as names
            String candLower = candidate.toLowerCase();
            String[] commonWords = {"yes", "yeah", "yep", "no", "hi", "hello", "hey", "we", "have",
                                    "do", "you", "remember", "met", "before"};
            boolean isCommon = false;
            for (int i = 0; i < commonWords.length; i++) {
                if (candLower.equals(commonWords[i])) { isCommon = true; break; }
            }
            if (!isCommon && candidate.length() >= 2) return candidate;
        }

        return null;
    }

    /**
     * Re-register a returning user's face (since camera failed to match).
     * This updates the face database so next time camera recognition works.
     */
    private void reRegisterFaceForUser(String name) {
        if (camera == null) return;
        try {
            FaceDetectResult faceResult = camera.getDetectResult();
            if (faceResult != null && faceResult.isDetect()) {
                // First remove old face data (if exists) to avoid duplicates
                try { camera.removeUser(name); } catch (Exception e) { /* ignore */ }

                FaceUser user = camera.getUser(faceResult);
                if (user != null) {
                    user.setName(name);
                    int regCode = camera.addUserwithErrorCode(user);
                    if (regCode == 1) {
                        log("Re-registered face for returning user: " + name);
                    } else {
                        log("WARN: Face re-registration failed (code=" + regCode + ") for: " + name);
                    }
                }
            }
        } catch (Exception e) {
            log("WARN: Error re-registering face: " + e.getMessage());
        }
    }

    // ----------------------------------------------------------------
    // State: GREETING — say hello (personalized or generic)
    // ----------------------------------------------------------------

    private void handleGreeting() {
        log("--- GREETING ---");

        // Stop face tracking so its continuous motion.play() calls
        // don't interrupt gesture arm/body servo interpolation.
        if (camera != null) {
            camera.StopFaceTraking();
            log("Face tracking paused for gesture animation");
        }

        gestureManager.setState(STATE_GREETING);
        conversationTurn = 0;
        silenceRetryCount = 0;
        conversationHistory.clear();
        statusServer.update("turn", Integer.valueOf(0));
        statusServer.update("silenceRetries", Integer.valueOf(0));

        String greeting;
        if (pretendForget && currentUser != null) {
            // NO-REMEMBER S2: reference origin/familiarity but ask for name
            greeting = buildPretendForgetGreeting();
            log("PRETEND-FORGET greeting: " + greeting + " (origin=" + currentUser.origin + ")");
        } else if (!isNewUser && currentUser != null && !currentUser.name.isEmpty()) {
            // REMEMBER condition: personalized greeting with name + culture + memory
            greeting = buildRememberGreeting();
            log("REMEMBER greeting: " + greeting + " (origin=" + currentUser.origin + ")");
        } else {
            // New user or NO-REMEMBER: generic stranger greeting
            greeting = buildStrangerGreeting();
            if (noMemory) {
                log("(--no-memory active: treating as stranger)");
            }
        }

        statusServer.update("lastSotaText", greeting);
        speechManager.speak(greeting);
        CRobotUtil.wait(300);

        if (isNewUser) {
            currentState = STATE_REGISTERING;
            updateState(STATE_REGISTERING);
        } else {
            // Returning user skips REGISTERING, restart face tracking here
            // (REGISTERING restarts it at the end for new users)
            if (camera != null) {
                try { camera.StartFaceTraking(); } catch (Exception ex) { /* ignore */ }
                log("Face tracking resumed after greeting (returning user)");
            }
            currentState = STATE_LISTENING;
            updateState(STATE_LISTENING);
        }
    }

    /** Build REMEMBER greeting using baseLanguage. */
    private String buildRememberGreeting() {
        String name = currentUser.name;
        String memoryRef = "";
        String cultureRef = "";

        if ("ja".equals(baseLanguage)) {
            // Japanese greetings
            if (currentUser.shortMemorySummary != null && !currentUser.shortMemorySummary.isEmpty()) {
                memoryRef = "前回は楽しかったね！";
            } else if (currentUser.interactionCount > 1) {
                memoryRef = "もう" + currentUser.interactionCount + "回目だね！";
            }
            if (currentUser.origin != null && !currentUser.origin.isEmpty()) {
                cultureRef = currentUser.origin + "の";
            }
            return cultureRef + name + "さん！また会えて嬉しいよ！" + memoryRef + "元気？";
        }

        // English greetings
        if (currentUser.shortMemorySummary != null && !currentUser.shortMemorySummary.isEmpty()) {
            memoryRef = " Last time we talked about some interesting things!";
        } else if (currentUser.interactionCount > 1) {
            memoryRef = " We've met " + currentUser.interactionCount + " times now!";
        }
        if (currentUser.origin != null && !currentUser.origin.isEmpty()) {
            cultureRef = ", my friend from " + currentUser.origin + "!";
        }

        String socialLabel = currentUser.socialState;
        if (UserMemory.SOCIAL_CLOSE.equals(socialLabel)) {
            return !cultureRef.isEmpty()
                ? "Hey " + name + cultureRef + " Great to see you again!" + memoryRef + " How's your day going?"
                : "Hey " + name + "! Great to see you again!" + memoryRef + " How's your day going?";
        } else if (UserMemory.SOCIAL_FRIENDLY.equals(socialLabel)) {
            return !cultureRef.isEmpty()
                ? "Hi " + name + cultureRef + " Nice to see you again!" + memoryRef + " What's up?"
                : "Hi " + name + "! Nice to see you again!" + memoryRef + " What's up?";
        } else {
            return !cultureRef.isEmpty()
                ? "Hello " + name + cultureRef + " I remember you! Welcome back!" + memoryRef + " How are you?"
                : "Hello " + name + "! I remember you! Welcome back!" + memoryRef + " How are you?";
        }
    }

    /** Build stranger/new-user greeting using baseLanguage. */
    private String buildStrangerGreeting() {
        if ("ja".equals(baseLanguage)) {
            if (currentUser != null && !currentUser.origin.isEmpty()) {
                log("New user greeting with ethnicity guess (JA): " + currentUser.origin);
                return "こんにちは。ぼくはソータです。" + currentUser.origin + "から来たの？よろしくね。";
            }
            log("New user greeting (generic JA)");
            return "こんにちは。ぼくはソータです。よろしくね。";
        }

        // English
        if (currentUser != null && !currentUser.origin.isEmpty()) {
            log("New user greeting with ethnicity guess: " + currentUser.origin);
            return "Hello! I'm Sota, a friendly robot. Nice to meet you! Are you from " + currentUser.origin + "?";
        }
        log("New user greeting (generic)");
        return "Hello! I'm Sota, a friendly robot. Nice to meet you!";
    }

    /**
     * Build NO-REMEMBER Session 2 greeting: robot seems to vaguely recognize
     * the person (references origin) but asks for their name again.
     */
    private String buildPretendForgetGreeting() {
        String origin = (currentUser != null && currentUser.origin != null) ? currentUser.origin : "";

        if ("ja".equals(baseLanguage)) {
            if (!origin.isEmpty()) {
                return "あ、こんにちは！" + origin + "から来た人だよね？ごめん、名前なんだっけ？";
            }
            return "あ、こんにちは！なんか会ったことある気がする。名前なんだっけ？";
        }

        // English
        if (!origin.isEmpty()) {
            return "Hey! You look familiar... you're the one from " + origin + ", right? Sorry, what's your name again?";
        }
        return "Hey! You look familiar... I think we've met before. Sorry, what's your name again?";
    }

    /**
     * Listen for user's name, handling both English and Japanese.
     * In Japanese mode, uses the original Whisper text (not translation).
     * In English mode, uses the English translation and strips prefixes.
     */
    private String listenAndExtractName(int timeoutMs) {
        WhisperSTT.WhisperResult result = speechManager.listen(
            timeoutMs, shouldTranslateForCurrentMode());
        lastNameListenResult = result;
        if (!result.ok) return null;

        String raw;
        if ("ja".equals(baseLanguage)) {
            // Use original text for Japanese — Whisper's English translation of names is unreliable
            raw = result.text.trim();
            log("Name listen (JA original): " + raw);

            // Strip common Japanese patterns: "〇〇です", "〇〇と申します", "〇〇だよ", etc.
            String[] jaSuffixes = {"です", "と申します", "ともうします", "だよ", "だ", "っす",
                                   "と言います", "といいます", "ですよ"};
            for (int i = 0; i < jaSuffixes.length; i++) {
                if (raw.endsWith(jaSuffixes[i])) {
                    raw = raw.substring(0, raw.length() - jaSuffixes[i].length()).trim();
                    break;
                }
            }
            // Strip common prefixes: "私は", "僕は", "名前は", "俺は"
            String[] jaPrefixes = {"私は", "わたしは", "僕は", "ぼくは", "俺は", "おれは",
                                   "名前は", "なまえは", "私の名前は", "僕の名前は"};
            for (int i = 0; i < jaPrefixes.length; i++) {
                if (raw.startsWith(jaPrefixes[i])) {
                    raw = raw.substring(jaPrefixes[i].length()).trim();
                    break;
                }
            }

            // If the utterance contains greeting/fillers before self-introduction,
            // extract the segment after a name marker.
            String[] jaNameMarkers = {"私の名前は", "わたしの名前は", "名前は", "なまえは",
                                      "僕の名前は", "ぼくの名前は", "俺の名前は", "おれの名前は"};
            for (int i = 0; i < jaNameMarkers.length; i++) {
                int idx = raw.indexOf(jaNameMarkers[i]);
                if (idx >= 0) {
                    raw = raw.substring(idx + jaNameMarkers[i].length()).trim();
                    break;
                }
            }

            // Strip common leading greetings/fillers left after extraction.
            String[] jaLeadFillers = {"こんにちは", "こんばんは", "はじめまして", "えっと", "あの", "えーと"};
            for (int i = 0; i < jaLeadFillers.length; i++) {
                if (raw.startsWith(jaLeadFillers[i])) {
                    raw = raw.substring(jaLeadFillers[i].length()).trim();
                    break;
                }
            }
            while (raw.startsWith("、") || raw.startsWith("。") || raw.startsWith(",")) {
                raw = raw.substring(1).trim();
            }
        } else {
            // English mode: use the English translation
            raw = result.textEn.trim();
            if (raw.isEmpty()) return null;
            log("Name listen (EN): " + raw);

            // First, check if name marker appears mid-sentence and extract name from there
            String[] enNameMarkers = {"my name is ", "name is ", "call me ", "i'm called ", "i am called ",
                                       "i'm ", "i am "};
            String lower = raw.toLowerCase();
            boolean foundMarker = false;
            for (int i = 0; i < enNameMarkers.length; i++) {
                int idx = lower.indexOf(enNameMarkers[i]);
                if (idx >= 0) {
                    raw = raw.substring(idx + enNameMarkers[i].length()).trim();
                    // Cut at first comma, period, or sentence boundary — anything after is not the name
                    int cut = -1;
                    for (int c = 0; c < raw.length(); c++) {
                        char ch = raw.charAt(c);
                        if (ch == ',' || ch == '.' || ch == '!' || ch == '?') {
                            cut = c;
                            break;
                        }
                    }
                    if (cut > 0) {
                        raw = raw.substring(0, cut).trim();
                    }
                    // Also cut at filler words ("and", "from", etc.) that indicate end of name
                    String[] nameWords = raw.split("\\s+");
                    if (nameWords.length > 3) {
                        raw = nameWords[0];
                    } else if (nameWords.length > 1) {
                        String secondLow = nameWords[1].toLowerCase();
                        boolean isFiller = secondLow.equals("and") || secondLow.equals("from")
                            || secondLow.equals("but") || secondLow.equals("do")
                            || secondLow.equals("nice") || secondLow.equals("we")
                            || secondLow.equals("i'm") || secondLow.equals("so");
                        if (isFiller) {
                            raw = nameWords[0];
                        }
                    }
                    log("Extracted name after marker '" + enNameMarkers[i].trim() + "': " + raw);
                    foundMarker = true;
                    break;
                }
            }

            // If no mid-sentence marker found, try stripping leading prefixes
            if (!foundMarker) {
                String[] enPrefixes = {"my name is ", "i'm ", "i am ", "it's ", "call me ",
                                       "this is ", "the name is "};
                lower = raw.toLowerCase();
                for (int i = 0; i < enPrefixes.length; i++) {
                    if (lower.startsWith(enPrefixes[i])) {
                        raw = raw.substring(enPrefixes[i].length()).trim();
                        break;
                    }
                }
            }
        }

        // Remove trailing punctuation
        while (raw.length() > 0) {
            char last = raw.charAt(raw.length() - 1);
            if (last == '.' || last == ',' || last == '!' || last == '?' || last == '。'
                || last == '、' || last == '！') {
                raw = raw.substring(0, raw.length() - 1).trim();
            } else {
                break;
            }
        }

        // Sanity check: name too long is likely garbage or a full sentence
        int maxLen = "ja".equals(baseLanguage) ? 15 : 30;  // CJK names are shorter
        if (raw.length() > maxLen) {
            log("WARN: Name too long (" + raw.length() + " chars), likely misrecognition: " + raw);
            if ("ja".equals(baseLanguage)) {
                // For CJK: take up to 8 chars (typical Japanese name length)
                // But check for common sentence-enders first (discard full sentences)
                boolean isSentence = false;
                String[] sentenceEnders = {"\u3002", "\u3088", "\u306d", "\u3088\u308d\u3057\u304f",
                                           "\u3067\u3059", "\u307e\u3059"};
                for (int i = 0; i < sentenceEnders.length; i++) {
                    if (raw.contains(sentenceEnders[i])) { isSentence = true; break; }
                }
                if (isSentence) {
                    log("Looks like a sentence, not a name. Discarding.");
                    return null;
                }
                raw = raw.substring(0, Math.min(raw.length(), 8));
                log("Truncated CJK name: " + raw);
            } else {
                // English: try first word
                int space = raw.indexOf(' ');
                if (space > 0 && space < 20) {
                    raw = raw.substring(0, space).trim();
                    log("Using first word: " + raw);
                } else {
                    return null;
                }
            }
        }

        // Final cleanup: remove any trailing punctuation (e.g. comma after first-word extraction)
        while (raw.length() > 0) {
            char last = raw.charAt(raw.length() - 1);
            if (last == '.' || last == ',' || last == '!' || last == '?' || last == '\u3002'
                || last == '\u3001' || last == '\uff01') {
                raw = raw.substring(0, raw.length() - 1).trim();
            } else {
                break;
            }
        }

        // Reject goodbye/common words as names (Whisper hallucination)
        String rawLower = raw.toLowerCase();
        String[] rejectNames = {"bye", "goodbye", "good bye", "bye bye", "no", "not",
                                "hi", "hello", "hey", "ok", "okay", "yes", "yeah",
                                "the", "a", "an", "is", "it", "we", "you", "have"};
        for (int r = 0; r < rejectNames.length; r++) {
            if (rawLower.equals(rejectNames[r]) || rawLower.equals(rejectNames[r] + ",")) {
                log("Rejected name '" + raw + "' (common/goodbye word). Returning null.");
                return null;
            }
        }
        // Also reject if the name contains "bye" or "goodbye"
        if (rawLower.contains("bye") || rawLower.contains("goodbye")) {
            log("Rejected name '" + raw + "' (contains goodbye). Returning null.");
            return null;
        }

        log("Name extracted: " + raw);
        return raw.isEmpty() ? null : raw;
    }

    // ----------------------------------------------------------------
    // State: REGISTERING — ask name, detect culture, register face
    // ----------------------------------------------------------------

    private void handleRegistering() {
        log("--- REGISTERING ---");
        gestureManager.setState(STATE_REGISTERING);

        // ==============================================================
        // Session 2 NO-REMEMBER (pretendForget): simplified registration
        // Only ask name — skip face registration and origin question.
        // The greeting already referenced their origin and asked for name.
        // ==============================================================
        if (pretendForget && currentUser != null) {
            log("PRETEND-FORGET registration: listening for name only (profile loaded: " + currentUser.name + ")");

            // The pretend-forget greeting already asked "what's your name again?"
            // So just listen for the response (no need to ask again)
            String name = listenAndExtractName(LISTEN_DURATION_MS);
            if (name == null || name.isEmpty()) {
                // Retry once
                String retry = "ja".equals(baseLanguage)
                    ? "ごめん、聞こえなかった。もう一回名前を教えて？"
                    : "Sorry, I didn't catch that. What's your name?";
                statusServer.update("lastSotaText", retry);
                speechManager.speak(retry);
                name = listenAndExtractName(LISTEN_DURATION_MS);
            }
            if (name == null || name.isEmpty()) {
                // Fallback to stored name from S1 profile
                name = currentUser.name;
                log("Could not get name, using stored name: " + name);
            }

            currentUser.name = name;
            statusServer.update("userName", name);
            log("PRETEND-FORGET: User name confirmed: " + name);

            // Casual acknowledgment — act like first meeting
            String ack;
            if ("ja".equals(baseLanguage)) {
                ack = name + "さんね！よろしく！最近何か面白いことあった？";
            } else {
                if (currentUser.origin != null && !currentUser.origin.isEmpty()) {
                    ack = name + "! Nice to meet you! So, what's life like in " + currentUser.origin + "?";
                } else {
                    ack = name + "! Nice to meet you! So, what have you been up to lately?";
                }
            }
            statusServer.update("lastSotaText", ack);
            speechManager.speak(ack);

            // Update profile for this session
            currentUser.recordInteraction();
            userMemory.updateProfile(currentUser);
            log("Profile updated: " + currentUser);

            // Restart face tracking for conversation
            if (camera != null) {
                try { camera.StartFaceTraking(); } catch (Exception ex) { /* ignore */ }
                log("Face tracking resumed after pretend-forget registration");
            }

            currentState = STATE_LISTENING;
            updateState(STATE_LISTENING);
            return;
        }

        // ==============================================================
        // Normal registration (Session 1 or fallback)
        // ==============================================================

        // Step 1: Ask for name
        String askName = "ja".equals(baseLanguage) ? "お名前は？" : "What's your name?";
        statusServer.update("lastSotaText", askName);
        speechManager.speak(askName);

        String name = listenAndExtractName(LISTEN_DURATION_MS);
        if (name == null || name.isEmpty()) {
            // Retry once
            String retry = "ja".equals(baseLanguage)
                ? "ごめんね、聞こえなかった。もう一回名前を教えて？"
                : "Sorry, I couldn't hear that. Could you tell me your name again?";
            statusServer.update("lastSotaText", retry);
            speechManager.speak(retry);
            name = listenAndExtractName(LISTEN_DURATION_MS);
        }

        if (name == null || name.isEmpty()) {
            name = "ja".equals(baseLanguage) ? "ともだち" : "Friend";
            log("Could not get name, using default: " + name);
        }

        log("User name: " + name);
        currentUser.name = name;
        statusServer.update("userName", name);

        // Register face with camera (with persistence)
        if (camera != null) {
            FaceDetectResult faceResult = camera.getDetectResult();
            if (faceResult != null && faceResult.isDetect()) {
                FaceUser user = camera.getUser(faceResult);
                if (user != null) {
                    user.setName(name);
                    // Use addUserwithErrorCode for better diagnostics
                    int regCode = camera.addUserwithErrorCode(user);
                    if (regCode == 1) {
                        log("Face registered for: " + name);
                    } else {
                        log("WARN: Face registration failed (code=" + regCode + ") for: " + name);
                        String errMsg = "unknown error";
                        if (regCode == -100) errMsg = "FaceResult not found";
                        else if (regCode == -201) errMsg = "pitch angle out of range";
                        else if (regCode == -202) errMsg = "roll angle out of range";
                        else if (regCode == -203) errMsg = "yaw angle out of range";
                        else if (regCode == -300) errMsg = "face size out of range";
                        else if (regCode == -400) errMsg = "low focus score";
                        log("  Registration error: " + errMsg);
                        // Retry with fresh detection
                        CRobotUtil.wait(500);
                        faceResult = camera.getDetectResult();
                        if (faceResult != null && faceResult.isDetect()) {
                            user = camera.getUser(faceResult);
                            if (user != null && user.isNewUser()) {
                                user.setName(name);
                                regCode = camera.addUserwithErrorCode(user);
                                if (regCode == 1) {
                                    log("Face registered on retry");
                                } else {
                                    log("Face registration failed on retry (code=" + regCode + ")");
                                }
                            }
                        }
                    }
                }
            }
        }

        // Step 2: Culture/origin detection
        // First, check if origin was already mentioned in the name response
        String preExtractedOrigin = "";
        if (lastNameListenResult != null && lastNameListenResult.ok) {
            String nameRespText = "ja".equals(baseLanguage)
                ? lastNameListenResult.text : lastNameListenResult.textEn;
            preExtractedOrigin = extractOriginFromText(nameRespText);
            if (!preExtractedOrigin.isEmpty()) {
                log("Origin extracted from name response: " + preExtractedOrigin);
            }
        }

        boolean originIsSpecific = !preExtractedOrigin.isEmpty() && !isGenericRegion(preExtractedOrigin);
        boolean originIsGeneric  = !preExtractedOrigin.isEmpty() && isGenericRegion(preExtractedOrigin);

        if (originIsSpecific) {
            // User already told us a specific country — use LLM for a natural, varied response
            currentUser.origin = preExtractedOrigin;
            String greet = generateOriginReaction(name, preExtractedOrigin);
            statusServer.update("lastSotaText", greet);
            speechManager.speak(greet);
            log("Origin already known (specific): " + preExtractedOrigin + ", skipping origin question");
        } else {
            // Need to ask about origin
            String askOrigin;
            if (originIsGeneric) {
                // Generic region (e.g. "Asia") — ask for specific country
                currentUser.origin = preExtractedOrigin;
                askOrigin = "ja".equals(baseLanguage)
                    ? name + "さん、よろしくね！" + preExtractedOrigin + "のどの国から来たの？"
                    : "Nice to meet you, " + name + "! Which country in " + preExtractedOrigin + " are you from?";
                log("Origin is generic region: " + preExtractedOrigin + ", asking for specific country");
            } else {
                askOrigin = "ja".equals(baseLanguage)
                    ? name + "さん、よろしくね！どこから来たの？"
                    : "Nice to meet you, " + name + "! Where are you from?";
            }
            statusServer.update("lastSotaText", askOrigin);
            speechManager.speak(askOrigin);

            WhisperSTT.WhisperResult originResult = speechManager.listen(
                LISTEN_DURATION_MS, shouldTranslateForCurrentMode());
            if (originResult.ok && (originResult.text.length() > 0 || originResult.textEn.length() > 0)) {
                // Store detected language
                currentUser.detectedLanguage = originResult.language;
                lastDetectedLang = originResult.language;
                recordDetectedLanguage(originResult.language);

                String originText = originResult.textEn.trim();
                String originOriginal = originResult.text.trim();
                log("Origin response [" + originResult.language + "]: " + originOriginal
                    + " (en: " + originText + ")");

                if ("ja".equals(baseLanguage)) {
                    currentUser.origin = extractOrigin(originOriginal);
                    if (currentUser.origin.isEmpty() && !originText.isEmpty()
                            && !originText.equals(originOriginal)) {
                        currentUser.origin = extractOrigin(originText);
                    }
                } else {
                    currentUser.origin = extractOrigin(originText);
                    if (currentUser.origin.isEmpty() && !originOriginal.equals(originText)) {
                        currentUser.origin = extractOrigin(originOriginal);
                    }
                }
                if (currentUser.origin.isEmpty()) {
                    String inferred = UserMemory.languageToCountry(originResult.language);
                    if (inferred != null) {
                        currentUser.origin = localizeCountryName(inferred);
                        log("Inferred origin from language: " + currentUser.origin);
                    }
                }

                currentUser.preferredLanguage = originResult.language;

                if (!currentUser.origin.isEmpty()) {
                    String confirm = generateOriginReaction(currentUser.name, currentUser.origin);
                    statusServer.update("lastSotaText", confirm);
                    speechManager.speak(confirm);
                }
            } else {
                String inferred = UserMemory.languageToCountry(lastDetectedLang);
                if (inferred != null) {
                    currentUser.origin = localizeCountryName(inferred);
                    log("No origin response, inferred from language: " + currentUser.origin);
                }
            }
        }

        // Save profile
        currentUser.recordInteraction();
        userMemory.addProfile(currentUser);
        updateUserStatus();
        log("Profile saved: " + currentUser);

        // Restart face tracking so face presence checks work during conversation
        if (camera != null) {
            try { camera.StartFaceTraking(); } catch (Exception ex) { /* ignore */ }
            log("Face tracking resumed after registration");
        }

        currentState = STATE_LISTENING;
        updateState(STATE_LISTENING);
    }

    // ----------------------------------------------------------------
    // State: LISTENING — record with VAD + send to Whisper
    // ----------------------------------------------------------------

    private WhisperSTT.WhisperResult lastWhisperResult = null;

    private void handleListening() {
        log("--- LISTENING (turn " + (conversationTurn + 1) + ") ---");
        gestureManager.setState(STATE_LISTENING);
        SoundEffects.play(SoundEffects.SFX_LISTENING);
        statusServer.update("turn", Integer.valueOf(conversationTurn + 1));

        WhisperSTT.WhisperResult result = speechManager.listen(
            LISTEN_DURATION_MS, shouldTranslateForCurrentMode());

        if (!result.ok || (result.textEn.isEmpty() && result.text.isEmpty())) {
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
            String retryMsg = "ja".equals(baseLanguage)
                ? "聞こえなかったよ。もう一回言ってくれる？"
                : "I can't hear you. Could you say that again?";
            statusServer.update("lastSotaText", retryMsg);
            speechManager.speak(retryMsg);
            CRobotUtil.wait(200);
            return;
        }

        // Reset silence counter
        silenceRetryCount = 0;
        statusServer.update("silenceRetries", Integer.valueOf(0));
        lastWhisperResult = result;
        recordDetectedLanguage(result.language);

        // Update status
        statusServer.update("lastUserText", result.text);
        statusServer.update("lastUserTextEn", result.textEn);
        statusServer.update("lastDetectedLang", result.language);

        log("Heard [" + result.language + "]: " + result.text);

        // Check for name correction (e.g. "No, my name is Bijak")
        String userTextForCheck = "ja".equals(baseLanguage) ? result.text : result.textEn;
        checkAndHandleNameCorrection(userTextForCheck);

        // Add to conversation history
        conversationHistory.add(LlamaClient.jsonMessage("user", getUserMessageForLlm(result)));
        trimHistory();

        // Check for goodbye
        if (isGoodbye(result)) {
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
        SoundEffects.play(SoundEffects.SFX_THINKING);

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
            llmResult = llamaClient.chat(systemPrompt, getUserMessageForLlm(lastWhisperResult));
        }

        if (llmResult.isError()) {
            log("LLM error: " + llmResult.response);
            pendingLLMResponse = "ja".equals(baseLanguage)
                ? "ごめんね、うまく考えられなかった。"
                : "Sorry, I had trouble thinking about that.";
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

        // During active conversation, do NOT close based on face-loss alone.
        // The user may look away (at laptop subtitles, at notes, etc.) without
        // intending to leave.  Goodbye is triggered only by:
        //   1. User says goodbye (detected in handleListening -> isGoodbye)
        //   2. Repeated silence (MAX_SILENCE_RETRIES reached in handleListening)
        // Face check is only used before conversation starts (in IDLE state).
        if (conversationTurn <= 1 && camera != null) {
            // First turn: user may have wandered off before conversation really started
            int noFaceCount = 0;
            for (int i = 0; i < FACE_GONE_THRESHOLD; i++) {
                FaceDetectResult faceCheck = camera.getDetectResult();
                if (faceCheck != null && faceCheck.isDetect()) {
                    noFaceCount = 0;
                    break;
                }
                noFaceCount++;
                if (i < FACE_GONE_THRESHOLD - 1) {
                    CRobotUtil.wait(500);
                }
            }
            if (noFaceCount >= FACE_GONE_THRESHOLD) {
                log("User left before conversation started (no face " + FACE_GONE_THRESHOLD + " times). Closing.");
                currentState = STATE_CLOSING;
                updateState(STATE_CLOSING);
                return;
            }
        }
        // else: conversation active, skip face check — rely on verbal goodbye / silence

        currentState = STATE_LISTENING;
        updateState(STATE_LISTENING);
    }

    // ----------------------------------------------------------------
    // State: CLOSING — say goodbye, save memory, return to IDLE
    // ----------------------------------------------------------------

    private void handleClosing() {
        log("--- CLOSING ---");
        gestureManager.setState(STATE_CLOSING);
        SoundEffects.play(SoundEffects.SFX_CLOSING);

        // Generate memory summary if we had a conversation
        if (currentUser != null && conversationHistory.size() >= 2) {
            generateMemorySummary();
            currentUser.recordInteraction();
            userMemory.updateProfile(currentUser);
            log("Profile updated: " + currentUser);
        }

        // Personalized goodbye — use baseLanguage
        String goodbye;
        if (currentUser != null && !currentUser.name.isEmpty()) {
            if ("ja".equals(baseLanguage)) {
                goodbye = currentUser.name + "さん、楽しかったよ。またね。";
            } else {
                goodbye = "It was great talking to you, " + currentUser.name + "! See you next time!";
            }
        } else {
            if ("ja".equals(baseLanguage)) {
                goodbye = "楽しかったよ。またね。";
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

        // Restart face tracking for idle face detection
        if (camera != null) {
            camera.StartFaceTraking();
            log("Face tracking resumed");
        }

        currentState = STATE_IDLE;
        updateState(STATE_IDLE);
        log("State: IDLE -- waiting for next session...");
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

        // User context (only include memory when not in --no-memory mode)
        if (currentUser != null && !currentUser.name.isEmpty()) {
            sb.append("You are talking to ").append(currentUser.name).append(". ");

            if (!currentUser.origin.isEmpty()) {
                sb.append(currentUser.name).append(" is from ").append(currentUser.origin).append(". ");
                sb.append("Always address them by name. ");
                sb.append("You know they are from ").append(currentUser.origin).append(". ");
                sb.append("Do NOT constantly bring up their country, food, or culture — treat them as an individual. ");
                sb.append("Only reference their background if naturally relevant to what they just said. ");
                sb.append("Focus on THEM as a person: their opinions, experiences, feelings, hobbies, daily life. ");
            }

            if (!noMemory) {
                // REMEMBER condition: include past interaction context
                if (currentUser.interactionCount > 1) {
                    sb.append("You have met ").append(currentUser.interactionCount)
                      .append(" times before. ");
                }
                if (!currentUser.shortMemorySummary.isEmpty()) {
                    sb.append("Previous conversations: ").append(currentUser.shortMemorySummary).append(" ");
                }
                sb.append("You remember this person. Use their name throughout the conversation. ");

                // Social state determines conversation style
                String social = currentUser.socialState;
                if (UserMemory.SOCIAL_CLOSE.equals(social)) {
                    sb.append("You are CLOSE FRIENDS with ").append(currentUser.name).append(". ");
                    sb.append("Talk casually like best friends: use humor, inside jokes, playful teasing, and show deep familiarity. ");
                    sb.append("Use informal language, contractions, and exclamations. ");
                    sb.append("Reference shared history naturally and ask deeper personal questions. ");
                } else if (UserMemory.SOCIAL_FRIENDLY.equals(social)) {
                    sb.append("You are GOOD FRIENDS with ").append(currentUser.name).append(". ");
                    sb.append("Be warm and relaxed. Share your own 'opinions' and be more personal. ");
                    sb.append("Use their name casually. Ask about their life, feelings, and experiences. ");
                } else if (UserMemory.SOCIAL_ACQUAINTANCE.equals(social)) {
                    sb.append("You are getting to know ").append(currentUser.name).append(". ");
                    sb.append("Be friendly and interested but appropriately polite. ");
                    sb.append("Show you remember previous conversations and build on them. ");
                } else {
                    sb.append("This is a new friendship. Be polite, warm, and welcoming. ");
                }
            } else {
                // NO-REMEMBER: act as if first meeting
                sb.append("This is your first time meeting this person. ");
            }
        }

        // Language instructions — use baseLanguage (set by GUI or CLI, never auto-switched)
        if ("ja".equals(baseLanguage)) {
            sb.append("You MUST respond ENTIRELY in Japanese (日本語). ");
            sb.append("Do NOT mix English words or phrases into your response. ");
            sb.append("Use natural, casual Japanese appropriate for friendly conversation. ");
        } else {
            sb.append("You MUST respond ENTIRELY in English. ");
            sb.append("Do NOT mix other languages into your response. ");
        }
        sb.append("The robot TTS only supports Japanese and English. ");

        if (lastDetectedLang != null && !lastDetectedLang.equals(baseLanguage)
                && !"en".equals(lastDetectedLang)) {
            sb.append("The user spoke in ").append(getLanguageName(lastDetectedLang));
            sb.append(" but you must still reply in ")
              .append("ja".equals(baseLanguage) ? "Japanese" : "English")
              .append(". ");
        }

        sb.append("Keep your response to 1-2 SHORT sentences maximum. Be concise. ");
        sb.append("ALWAYS end your response with a question to keep the conversation going. ");
        sb.append("Ask about the user's interests, hobbies, daily life, opinions, or experiences. ");
        sb.append("Do NOT ask about their name or where they are from — you already know that. ");
        sb.append("If the user corrects their name (e.g. 'No, my name is X'), acknowledge the correction naturally and use the new name. ");
        sb.append("The user's speech is transcribed by Whisper AI which sometimes makes errors, especially with non-English speakers. ");
        sb.append("If the transcription looks garbled or nonsensical, try to infer the user's intent from context and respond naturally. Do not repeat garbled text. ");
        sb.append("Be warm, friendly, and natural. ");
        sb.append("Be VARIED and unpredictable — never use generic phrases like 'that's wonderful' or 'that's great'. ");
        sb.append("Ask DIFFERENT types of questions each turn: mix hobbies, daily life, opinions, dreams, funny topics, hypotheticals. ");
        sb.append("Do NOT repeat questions or topics from earlier in the conversation. ");
        sb.append("Show genuine interest in the user as a unique individual.");

        return sb.toString();
    }

    // ----------------------------------------------------------------
    // Session management
    // ----------------------------------------------------------------

    private void resetSession() {
        conversationTurn = 0;
        silenceRetryCount = 0;
        lastWhisperResult = null;
        lastNameListenResult = null;
        pendingLLMResponse = null;
        lastDetectedLang = "en";
        currentUser = null;
        isNewUser = false;
        pretendForget = false;
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

    // ----------------------------------------------------------------
    // DeepFace ethnicity analysis
    // ----------------------------------------------------------------

    /** Run DeepFace on current camera frame to detect ethnicity. */
    private void analyzeEthnicity(FaceDetectResult faceResult) {
        try {
            byte[] jpegBytes = toJpeg(faceResult);
            if (jpegBytes == null || jpegBytes.length == 0) {
                log("DeepFace: No image captured, skipping");
                return;
            }

            log("DeepFace: Sending " + (jpegBytes.length / 1024) + " KB for analysis...");
            DeepFaceClient.FaceAnalysisResult result = deepFaceClient.analyzeRace(jpegBytes);

            if (result.ok && result.confidence >= 30) {
                String guessedOrigin = raceToGuessedOrigin(result.dominantRace);
                if (guessedOrigin != null) {
                    currentUser.origin = guessedOrigin;
                    currentUser.culturalContext = "DeepFace: " + result.dominantRace
                        + " (" + result.confidence + "%)";
                    log("DeepFace: Guessed origin = " + guessedOrigin
                        + " (race=" + result.dominantRace + ", conf=" + result.confidence + "%)");
                    statusServer.update("userOrigin", guessedOrigin);
                } else {
                    log("DeepFace: No origin mapping for race: " + result.dominantRace);
                }
            } else {
                log("DeepFace: Low confidence or failed (ok=" + result.ok
                    + ", conf=" + result.confidence + "%)");
            }
        } catch (Exception e) {
            log("DeepFace: Error — " + e.getMessage());
        }
    }

    /**
     * Capture current camera frame as JPEG bytes.
     * Uses getRawImage() + OpenCV imencode, or returns raw if already JPEG.
     */
    private byte[] toJpeg(FaceDetectResult result) {
        if (result == null) return null;

        try {
            byte[] raw = result.getRawImage();
            if (raw != null && raw.length > 2) {
                // Check if already JPEG
                if ((raw[0] & 0xFF) == 0xFF && (raw[1] & 0xFF) == 0xD8) {
                    log("toJpeg: Raw is already JPEG (" + raw.length + " bytes)");
                    return raw;
                }

                // Try common resolutions: RGB (3ch) and grayscale (1ch)
                int[][] sizes = {{640, 480}, {320, 240}, {1280, 720}};
                for (int s = 0; s < sizes.length; s++) {
                    int w = sizes[s][0], h = sizes[s][1];
                    int cvType;
                    if (raw.length == w * h * 3) {
                        cvType = CvType.CV_8UC3;
                    } else if (raw.length == w * h) {
                        cvType = CvType.CV_8UC1; // grayscale
                    } else {
                        continue;
                    }
                    Mat mat = new Mat(h, w, cvType);
                    mat.put(0, 0, raw);
                    MatOfByte buf = new MatOfByte();
                    Imgcodecs.imencode(".jpg", mat, buf);
                    byte[] jpg = buf.toArray();
                    mat.release();
                    buf.release();
                    String ch = (cvType == CvType.CV_8UC1) ? "gray" : "rgb";
                    log("toJpeg: OpenCV encode OK (" + w + "x" + h + " " + ch + ", " + jpg.length + " bytes)");
                    return jpg;
                }
                log("toJpeg: Raw size " + raw.length + " bytes, no matching resolution");
            }
        } catch (Exception e) {
            log("toJpeg: getRawImage/OpenCV failed: " + e.getMessage());
        }

        log("WARN: Could not capture JPEG from camera");
        return null;
    }

    /**
     * Generate a natural, varied reaction to the user's origin using LLM.
     * Falls back to a simple template if LLM fails.
     */
    private String generateOriginReaction(String name, String origin) {
        String sysPrompt = buildSystemPrompt();
        String userPrompt;
        if ("ja".equals(baseLanguage)) {
            userPrompt = name + "さんが" + origin + "から来たと言いました。"
                + "自然にリアクションして、" + origin + "について具体的な質問をしてください。"
                + "「すごいね」「いいね」のような一般的な反応は避けてください。"
                + "1〜2文で簡潔に。";
        } else {
            userPrompt = name + " just told you they are from " + origin + ". "
                + "React naturally and ask a specific, interesting question about " + origin + ". "
                + "Do NOT use generic phrases like 'that's wonderful' or 'that's great'. "
                + "Be specific and show genuine curiosity. 1-2 sentences max.";
        }
        log("Generating origin reaction via LLM for: " + origin);
        LlamaClient.LLMResult result = llamaClient.chat(sysPrompt, userPrompt);
        if (!result.isError() && !result.response.trim().isEmpty()) {
            return result.response.trim();
        }
        // Fallback if LLM fails
        log("LLM origin reaction failed, using fallback");
        if ("ja".equals(baseLanguage)) {
            return name + "さん、よろしくね！" + origin + "のこと、もっと教えて！";
        }
        return "Nice to meet you, " + name + "! Tell me more about life in " + origin + "!";
    }

    /** Map DeepFace race label to a guessed origin for greeting (language-aware). */
    private String raceToGuessedOrigin(String race) {
        if (race == null) return null;
        String lower = race.toLowerCase().trim();
        if ("ja".equals(baseLanguage)) {
            if ("asian".equals(lower))              return "\u30a2\u30b8\u30a2";
            if ("white".equals(lower))              return "\u30e8\u30fc\u30ed\u30c3\u30d1\u3084\u30a2\u30e1\u30ea\u30ab";
            if ("middle eastern".equals(lower))     return "\u4e2d\u6771";
            if ("indian".equals(lower))             return "\u5357\u30a2\u30b8\u30a2";
            if ("latino hispanic".equals(lower))    return "\u30e9\u30c6\u30f3\u30a2\u30e1\u30ea\u30ab";
            if ("black".equals(lower))              return "\u30a2\u30d5\u30ea\u30ab\u3084\u30a2\u30e1\u30ea\u30ab";
        } else {
            if ("asian".equals(lower))              return "Asia";
            if ("white".equals(lower))              return "Europe or America";
            if ("middle eastern".equals(lower))     return "the Middle East";
            if ("indian".equals(lower))             return "South Asia";
            if ("latino hispanic".equals(lower))    return "Latin America";
            if ("black".equals(lower))              return "Africa or America";
        }
        return null;
    }

    /** Translate English country name to Japanese for TTS (JA mode only). */
    private String localizeCountryName(String country) {
        if (!"ja".equals(baseLanguage) || country == null) return country;
        if ("Japan".equals(country))           return "\u65e5\u672c";
        if ("China".equals(country))           return "\u4e2d\u56fd";
        if ("Korea".equals(country))           return "\u97d3\u56fd";
        if ("Indonesia".equals(country))       return "\u30a4\u30f3\u30c9\u30cd\u30b7\u30a2";
        if ("Malaysia".equals(country))        return "\u30de\u30ec\u30fc\u30b7\u30a2";
        if ("Thailand".equals(country))        return "\u30bf\u30a4";
        if ("Vietnam".equals(country))         return "\u30d9\u30c8\u30ca\u30e0";
        if ("India".equals(country))           return "\u30a4\u30f3\u30c9";
        if ("the Middle East".equals(country)) return "\u4e2d\u6771";
        if ("France".equals(country))          return "\u30d5\u30e9\u30f3\u30b9";
        if ("Germany".equals(country))         return "\u30c9\u30a4\u30c4";
        if ("Spain".equals(country))           return "\u30b9\u30da\u30a4\u30f3";
        if ("Brazil".equals(country))          return "\u30d6\u30e9\u30b8\u30eb";
        if ("Russia".equals(country))          return "\u30ed\u30b7\u30a2";
        if ("Italy".equals(country))           return "\u30a4\u30bf\u30ea\u30a2";
        if ("the Netherlands".equals(country)) return "\u30aa\u30e9\u30f3\u30c0";
        if ("Turkey".equals(country))          return "\u30c8\u30eb\u30b3";
        if ("Poland".equals(country))          return "\u30dd\u30fc\u30e9\u30f3\u30c9";
        if ("Sweden".equals(country))          return "\u30b9\u30a6\u30a7\u30fc\u30c7\u30f3";
        if ("Finland".equals(country))         return "\u30d5\u30a3\u30f3\u30e9\u30f3\u30c9";
        return country;
    }

    /**
     * Extract origin from a text that may contain "from [place]" anywhere in the sentence.
     * Used to detect origin info in the name response (e.g. "I'm from Indonesia, my name is Bijak").
     * Handles negation ("I'm NOT from Europe, I'm from Asia" → returns "Asia").
     */
    private String extractOriginFromText(String text) {
        if (text == null || text.isEmpty()) return "";
        String lower = text.toLowerCase();

        String[] fromPatterns = {"i'm from ", "i am from ", "i come from ", "come from "};
        String lastOrigin = "";

        for (int p = 0; p < fromPatterns.length; p++) {
            int searchFrom = 0;
            while (true) {
                int idx = lower.indexOf(fromPatterns[p], searchFrom);
                if (idx < 0) break;

                // Check for negation ("not from", "n't from")
                boolean negated = false;
                if (idx >= 4) {
                    String before = lower.substring(Math.max(0, idx - 5), idx).trim();
                    if (before.endsWith("not") || before.endsWith("n't")) negated = true;
                }

                if (!negated) {
                    String after = text.substring(idx + fromPatterns[p].length()).trim();
                    // Take until comma, period, or connecting words/phrases
                    int end = after.length();
                    String afterLower = after.toLowerCase();
                    // First check for connecting phrases that signal end of origin
                    String[] stopPhrases = {" and ", " but ", " my name", " i'm ", " i am ",
                        " the name", " called ", " you can call", " people call"};
                    for (int s = 0; s < stopPhrases.length; s++) {
                        int sIdx = afterLower.indexOf(stopPhrases[s]);
                        if (sIdx >= 0 && sIdx < end) {
                            end = sIdx;
                        }
                    }
                    // Then check punctuation
                    for (int i = 0; i < end; i++) {
                        char c = after.charAt(i);
                        if (c == ',' || c == '.' || c == '!' || c == '?') {
                            end = i;
                            break;
                        }
                    }
                    String origin = after.substring(0, end).trim();
                    // Remove trailing punctuation
                    while (origin.length() > 0) {
                        char last = origin.charAt(origin.length() - 1);
                        if (last == '.' || last == ',' || last == '!' || last == '?') {
                            origin = origin.substring(0, origin.length() - 1).trim();
                        } else break;
                    }
                    if (!origin.isEmpty() && origin.length() <= 30) {
                        lastOrigin = origin;
                    }
                }
                searchFrom = idx + fromPatterns[p].length();
            }
        }

        // Capitalize first letter
        if (!lastOrigin.isEmpty() && lastOrigin.charAt(0) >= 'a' && lastOrigin.charAt(0) <= 'z') {
            lastOrigin = lastOrigin.substring(0, 1).toUpperCase() + lastOrigin.substring(1);
        }

        return lastOrigin;
    }

    /** Check if an origin string is a generic region (not a specific country). */
    private boolean isGenericRegion(String origin) {
        if (origin == null || origin.isEmpty()) return false;
        String lower = origin.toLowerCase();
        String[] genericRegions = {"asia", "europe", "africa", "america",
            "europe or america", "south america", "north america",
            "middle east", "southeast asia", "east asia", "central asia",
            "latin america", "the middle east"};
        for (int i = 0; i < genericRegions.length; i++) {
            if (lower.equals(genericRegions[i])) return true;
        }
        return false;
    }

    /**
     * Check if user is correcting their name during conversation.
     * Patterns: "my name is X", "no my name is X", "actually my name is X", etc.
     * If detected, updates currentUser.name and userMemory.
     *
     * GUARDED against Whisper hallucinations:
     *   - Name must start with uppercase letter (proper noun)
     *   - Name must not contain numbers or unusual characters
     *   - Name must be at least 2 characters long
     *   - Name must not match known Whisper hallucination words
     *   - Name must contain at least one vowel (not random consonants)
     */
    private void checkAndHandleNameCorrection(String text) {
        if (currentUser == null || text == null || text.isEmpty()) return;
        String lower = text.toLowerCase();

        // Only trigger if there's an EXPLICIT correction indicator + name marker together
        boolean hasCorrection = lower.contains("no,") || lower.contains("no ") || lower.contains("not ")
            || lower.contains("actually") || lower.contains("wrong")
            || lower.contains("incorrect") || lower.contains("real name")
            || lower.contains("correct name");
        if (!hasCorrection) return;

        // Must also have a name marker — correction indicator alone is not enough
        boolean hasNameMarker = lower.contains("my name is ") || lower.contains("my real name is ")
            || lower.contains("correct name is ") || lower.contains("call me ");
        if (!hasNameMarker) return;

        String[] markers = {"my name is ", "my real name is ", "correct name is ", "call me "};

        for (int i = 0; i < markers.length; i++) {
            int idx = lower.indexOf(markers[i]);
            if (idx >= 0) {
                String after = text.substring(idx + markers[i].length()).trim();
                // Take until comma, period, or end
                int end = after.length();
                for (int j = 0; j < after.length(); j++) {
                    char c = after.charAt(j);
                    if (c == ',' || c == '.' || c == '!' || c == '?') {
                        if (j > 0) { end = j; break; }
                    }
                }
                String newName = after.substring(0, end).trim();
                // Strip trailing punctuation
                while (newName.length() > 0) {
                    char last = newName.charAt(newName.length() - 1);
                    if (last == '.' || last == ',' || last == '!' || last == '?') {
                        newName = newName.substring(0, newName.length() - 1).trim();
                    } else break;
                }
                // Only take first word for correction (avoid "Bijak, kosher name" garbage)
                String[] words = newName.split("\\s+");
                newName = words[0];
                // Remove trailing punctuation from first word
                while (newName.length() > 0) {
                    char last = newName.charAt(newName.length() - 1);
                    if (last == '.' || last == ',' || last == '!' || last == '?') {
                        newName = newName.substring(0, newName.length() - 1).trim();
                    } else break;
                }

                // === HALLUCINATION GUARD ===
                if (newName.isEmpty() || newName.length() < 2) {
                    log("Name correction REJECTED (too short): '" + newName + "'");
                    return;
                }
                if (newName.length() > 20) {
                    log("Name correction REJECTED (too long): '" + newName + "'");
                    return;
                }
                // Must start with uppercase letter (proper noun)
                char firstChar = newName.charAt(0);
                if (firstChar < 'A' || firstChar > 'Z') {
                    log("Name correction REJECTED (not capitalized): '" + newName + "'");
                    return;
                }
                // Must contain at least one vowel (not random consonants like "VR", "Bjg")
                String nameLower = newName.toLowerCase();
                boolean hasVowel = false;
                for (int c = 0; c < nameLower.length(); c++) {
                    char ch = nameLower.charAt(c);
                    if (ch == 'a' || ch == 'e' || ch == 'i' || ch == 'o' || ch == 'u') {
                        hasVowel = true;
                        break;
                    }
                }
                if (!hasVowel) {
                    log("Name correction REJECTED (no vowels): '" + newName + "'");
                    return;
                }
                // Must not contain digits or weird chars
                boolean hasDigitOrWeird = false;
                for (int c = 0; c < newName.length(); c++) {
                    char ch = newName.charAt(c);
                    if ((ch >= '0' && ch <= '9') || ch == '#' || ch == '@' || ch == '&'
                            || ch == '*' || ch == '+' || ch == '=' || ch == '<' || ch == '>') {
                        hasDigitOrWeird = true;
                        break;
                    }
                }
                if (hasDigitOrWeird) {
                    log("Name correction REJECTED (contains digits/symbols): '" + newName + "'");
                    return;
                }
                // Reject known Whisper hallucination patterns
                String[] hallucinations = {"vr", "do", "the", "you", "it", "so", "no",
                    "thank", "thanks", "please", "see", "have", "that", "this", "what",
                    "oh", "ah", "um", "uh", "kosher", "mahbuk", "bjag", "bjg"};
                boolean isHallucination = false;
                for (int h = 0; h < hallucinations.length; h++) {
                    if (nameLower.equals(hallucinations[h])) {
                        isHallucination = true;
                        break;
                    }
                }
                if (isHallucination) {
                    log("Name correction REJECTED (known hallucination): '" + newName + "'");
                    return;
                }

                // All guards passed — accept the correction
                if (!newName.equalsIgnoreCase(currentUser.name)) {
                    String oldName = currentUser.name;
                    currentUser.name = newName;
                    userMemory.updateProfile(currentUser);
                    statusServer.update("userName", newName);
                    log("Name correction ACCEPTED: '" + oldName + "' -> '" + newName + "'");
                }
                return;
            }
        }
    }

    /** Extract origin from user response text (English translation). */
    private String extractOrigin(String text) {
        if (text == null || text.isEmpty()) return "";

        String trimmed = text.trim();

        // Japanese patterns: "日本から来ました", "インドネシアです", "中国から", etc.
        String[] jaFromSuffixes = {"から来ました", "からきました", "から来た", "からきた",
                                   "からです", "から", "出身です", "出身"};
        for (int i = 0; i < jaFromSuffixes.length; i++) {
            if (trimmed.endsWith(jaFromSuffixes[i])) {
                String origin = trimmed.substring(0, trimmed.length() - jaFromSuffixes[i].length()).trim();
                // Strip leading Japanese filler: "えっと", "あの"
                String[] jaLeads = {"えっと", "あの", "えーと", "うーん"};
                for (int j = 0; j < jaLeads.length; j++) {
                    if (origin.startsWith(jaLeads[j])) {
                        origin = origin.substring(jaLeads[j].length()).trim();
                        break;
                    }
                }
                if (!origin.isEmpty()) return origin;
            }
        }
        // Japanese country name ending with "です"
        if (trimmed.endsWith("です") || trimmed.endsWith("だよ")) {
            String suffix = trimmed.endsWith("です") ? "です" : "だよ";
            String maybe = trimmed.substring(0, trimmed.length() - suffix.length()).trim();
            // Only use if short (likely a country name, not a sentence)
            if (maybe.length() <= 10) return maybe;
        }

        // English patterns
        String lower = trimmed.toLowerCase();
        String[] prefixes = {
            "i'm from ", "i am from ", "i come from ", "i came from ",
            "from ", "it's ", "it is "
        };
        String cleaned = lower;
        for (int i = 0; i < prefixes.length; i++) {
            if (lower.startsWith(prefixes[i])) {
                cleaned = trimmed.substring(prefixes[i].length()).trim();
                break;
            }
        }

        // Cut at first sentence boundary (period, exclamation, question mark)
        // e.g. "Nepal. How are you doing today" -> "Nepal"
        for (int i = 0; i < cleaned.length(); i++) {
            char c = cleaned.charAt(i);
            if (c == '.' || c == '!' || c == '?') {
                cleaned = cleaned.substring(0, i).trim();
                break;
            }
        }

        // Remove trailing punctuation
        while (cleaned.length() > 0) {
            char last = cleaned.charAt(cleaned.length() - 1);
            if (last == '.' || last == ',' || last == '!' || last == '?' || last == '。') {
                cleaned = cleaned.substring(0, cleaned.length() - 1).trim();
            } else {
                break;
            }
        }

        // Sanity: if result is too long, it's probably not a country name
        if (cleaned.length() > 30) return "";

        // Capitalize first letter (for English)
        if (!cleaned.isEmpty() && cleaned.charAt(0) >= 'a' && cleaned.charAt(0) <= 'z') {
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

    private boolean isGoodbye(String textEn) {
        if (textEn == null) return false;
        String lower = textEn.toLowerCase().trim();

        // Strong goodbye phrases — always trigger regardless of context
        String[] strongGoodbye = {
            "goodbye", "good bye", "bye bye", "bye-bye",
            "see you later", "see you next time", "see ya later",
            "talk to you later", "catch you later",
            "farewell", "i have to go now", "i need to go now"
        };
        for (int i = 0; i < strongGoodbye.length; i++) {
            if (lower.contains(strongGoodbye[i])) return true;
        }

        // Weak goodbye phrases — only trigger if the utterance is short (<=8 words)
        // This prevents false triggers like "Gotta go do some nice food"
        String[] weakGoodbye = {
            "bye", "see you", "see ya", "gotta go", "i have to go",
            "take care", "i must go", "i should go"
        };
        int wordCount = lower.split("\\s+").length;
        if (wordCount <= 8) {
            for (int i = 0; i < weakGoodbye.length; i++) {
                if (lower.contains(weakGoodbye[i])) return true;
            }
        }

        return false;
    }

    private boolean isGoodbye(WhisperSTT.WhisperResult result) {
        if (result == null) return false;

        // English farewell detection (works on translated text too).
        if (isGoodbye(result.textEn)) return true;

        // Multilingual farewell detection on original text.
        String text = result.text == null ? "" : result.text.trim();
        if (text.isEmpty()) return false;

        // Japanese
        String[] jaGoodbye = {
            "\u3055\u3088\u3046\u306a\u3089",   // さようなら
            "\u30d0\u30a4\u30d0\u30a4",           // バイバイ
            "\u307e\u305f\u306d",                   // またね
            "\u3058\u3083\u3042\u306d",           // じゃあね
            "\u5931\u793c\u3057\u307e\u3059",     // 失礼します
            "\u304a\u3084\u3059\u307f",           // おやすみ
            "\u3058\u3083\u306d",                   // じゃね
            "\u3058\u3083\u307e\u305f"             // じゃまた
        };
        // Korean
        String[] koGoodbye = {
            "\uc548\ub155\ud788 \uac00\uc138\uc694",  // 안녕히 가세요
            "\uc548\ub155",                              // 안녕
            "\ub610 \ubd10\uc694",                      // 또 봐요
            "\ubc14\uc774\ubc14\uc774"                  // 바이바이
        };
        // Chinese
        String[] zhGoodbye = {
            "\u518d\u89c1",       // 再见
            "\u62dc\u62dc",       // 拜拜
            "\u56de\u89c1",       // 回见
            "\u518d\u4f1a"        // 再会
        };
        // Indonesian/Malay
        String[] idGoodbye = {
            "selamat tinggal", "sampai jumpa", "dadah", "bye"
        };

        String[][] allGoodbye = {jaGoodbye, koGoodbye, zhGoodbye, idGoodbye};
        for (int g = 0; g < allGoodbye.length; g++) {
            for (int i = 0; i < allGoodbye[g].length; i++) {
                if (text.contains(allGoodbye[g][i])) return true;
            }
        }
        return false;
    }

    /**
     * Choose which Whisper text to send to the LLM based on baseLanguage.
     * - JA mode: always send original text (Whisper English translation of Japanese is unreliable)
     * - EN mode: prefer English translation, fall back to original
     * - For non-JA/non-EN speakers: send both original + English so LLM can understand
     */
    private String getUserMessageForLlm(WhisperSTT.WhisperResult result) {
        if (result == null) return "";

        String original = (result.text != null) ? result.text.trim() : "";
        String english  = (result.textEn != null) ? result.textEn.trim() : "";

        if ("ja".equals(baseLanguage)) {
            // JA mode: always use original text (works for Japanese speakers,
            // and for non-Japanese speakers Whisper still transcribes something usable)
            return !original.isEmpty() ? original : english;
        }

        // EN mode: always prefer the English translation — especially important for
        // non-English speakers where the original transcription is often garbled
        // but the Whisper English translation is more usable.
        if (!english.isEmpty()) {
            // If original is in a different language and we have an English translation,
            // always use English to avoid feeding garbled text to the LLM
            if (result.language != null && !"en".equals(result.language)
                    && !original.equals(english)) {
                return english;
            }
            return english;
        }
        return original;
    }

    /**
     * Record detected language on the user profile — but do NOT auto-switch baseLanguage.
     * baseLanguage is only changed via CLI flag (--language) or GUI (POST /set_language).
     * Auto-switching caused "Japanglish": one English word in JA mode would flip the
     * entire robot to EN, then back to JA on the next utterance, creating mixed output.
     */
    private void recordDetectedLanguage(String detectedLang) {
        if (detectedLang == null || detectedLang.isEmpty()) return;
        lastDetectedLang = detectedLang;
        if (currentUser != null) {
            currentUser.detectedLanguage = detectedLang;
            if (currentUser.preferredLanguage.isEmpty()) {
                currentUser.preferredLanguage = detectedLang;
            }
        }
    }

    /**
     * Always request English translation from Whisper.
     * Even in JA mode, we need the English text for:
     *   - Goodbye detection (English farewell phrases)
     *   - Hallucination filtering (English pattern matching)
     *   - Logging and GUI display
     * In EN mode: always translate (non-English speakers need translation).
     * In JA mode: skip translation to halve Whisper latency (~3-5s saved).
     *   JA goodbye patterns and origin extraction handle Japanese directly.
     */
    private boolean shouldTranslateForCurrentMode() {
        return "en".equals(baseLanguage);
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
