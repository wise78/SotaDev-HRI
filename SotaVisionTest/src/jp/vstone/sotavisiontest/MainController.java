package jp.vstone.sotavisiontest;

import java.awt.Color;

import jp.vstone.RobotLib.CRobotMem;
import jp.vstone.RobotLib.CRobotPose;
import jp.vstone.RobotLib.CRobotUtil;
import jp.vstone.RobotLib.CSotaMotion;
import jp.vstone.camera.FaceDetectResult;
import jp.vstone.camera.FaceDetectLib.FaceUser;

/**
 * Main orchestrator for the Sota Social Interaction System.
 *
 * Program flow:
 *   IDLE
 *   -> Face detected
 *   -> Check if recognized
 *       YES -> greet by name
 *       NO  -> ask name, estimate age, save to memory
 *   -> Start LLaMA-based conversation
 *   -> Update interaction count
 *   -> Update social state
 *   -> Return to IDLE
 *
 * Coordinates all modules:
 *   FaceManager, SpeechManager, MemoryManager,
 *   ConversationManager, LlamaClient, SocialStateMachine
 */
public class MainController {

    private static final String TAG = "MainController";

    // ----------------------------------------------------------------
    // System states
    // ----------------------------------------------------------------

    private enum SystemState {
        IDLE,
        FACE_DETECTED,
        RECOGNIZING,
        GREETING,
        REGISTERING,
        CONVERSING,
        UPDATING,
        SHUTTING_DOWN
    }

    // ----------------------------------------------------------------
    // Configuration
    // ----------------------------------------------------------------

    // Ollama server URL — change to laptop IP when running on Sota robot
    // e.g. "http://192.168.11.5:11434"
    private static final String DEFAULT_OLLAMA_URL = "http://localhost:11434";
    private static final String MODEL_NAME         = "llama3.2:3b";
    private static final int    MAX_PREDICT         = 80;
    private static final int    HTTP_TIMEOUT_MS     = 120000;

    // Data directory for user profiles JSON
    private static final String DATA_DIR = "./data";

    // Max conversation turns before ending
    private static final int MAX_CONVERSATION_TURNS = 5;

    // Time to wait between main loop cycles (ms)
    private static final int LOOP_INTERVAL_MS = 500;

    // Time to wait after conversation before returning to IDLE (ms)
    private static final int COOLDOWN_MS = 3000;

    // Language: "ja" (Japanese) or "en" (English)
    private static final String DEFAULT_LANGUAGE = "ja";

    // ----------------------------------------------------------------
    // Module instances
    // ----------------------------------------------------------------

    private CRobotMem          mem;
    private CSotaMotion        motion;
    private FaceManager        faceManager;
    private SpeechManager      speechManager;
    private MemoryManager      memoryManager;
    private LlamaClient        llamaClient;
    private ConversationManager conversationManager;
    private SocialStateMachine socialStateMachine;

    // ----------------------------------------------------------------
    // Runtime state
    // ----------------------------------------------------------------

    private volatile SystemState currentState = SystemState.IDLE;
    private volatile boolean running = false;

    // Currently active user during a conversation
    private UserProfile activeProfile = null;

    // Language setting
    private String language = DEFAULT_LANGUAGE;

    // ----------------------------------------------------------------
    // Main entry point
    // ----------------------------------------------------------------

    public static void main(String[] args) {
        System.out.println("========================================================");
        System.out.println("  Sota Social Interaction System — SotaVisionTest");
        System.out.println("========================================================");
        System.out.println();

        // Parse command line: [ollama_url] [language]
        // e.g. java MainController http://192.168.11.5:11434 en
        String ollamaUrl = DEFAULT_OLLAMA_URL;
        String lang = DEFAULT_LANGUAGE;
        for (String arg : args) {
            if (arg.startsWith("http")) {
                ollamaUrl = arg;
            } else if ("en".equalsIgnoreCase(arg) || "ja".equalsIgnoreCase(arg)) {
                lang = arg.toLowerCase();
            }
        }

        System.out.println("  Language: " + ("en".equals(lang) ? "English" : "Japanese"));
        System.out.println();

        MainController controller = new MainController();
        controller.language = lang;
        controller.initialize(ollamaUrl);
        controller.run();
    }

    // ----------------------------------------------------------------
    // Initialization
    // ----------------------------------------------------------------

    /**
     * Initialize all subsystems.
     */
    public void initialize(String ollamaUrl) {
        log("Initializing subsystems...");

        // 1. Robot connection
        mem = new CRobotMem();
        if (!mem.Connect()) {
            log("FATAL: Cannot connect to robot. Exiting.");
            System.exit(1);
        }
        motion = new CSotaMotion(mem);
        motion.InitRobot_Sota();
        log("Robot connected. Firmware: " + mem.FirmwareRev.get());

        // Servo on + initial pose
        motion.ServoOn();
        CRobotPose initPose = new CRobotPose();
        initPose.SetPose(
            new Byte[]  {1,    2,    3,    4,    5,    6,    7,    8},
            new Short[] {0, -900,    0,  900,    0,    0,    0,    0}
        );
        initPose.setLED_Sota(Color.BLUE, Color.BLUE, 255, Color.GREEN);
        motion.play(initPose, 500);
        CRobotUtil.wait(500);

        // 2. Face manager
        faceManager = new FaceManager(motion);
        faceManager.initCamera();
        log("FaceManager ready");

        // 3. Speech manager
        speechManager = new SpeechManager(motion);
        log("SpeechManager ready");

        // 4. Memory manager
        memoryManager = new MemoryManager(DATA_DIR);
        log("MemoryManager ready (" + memoryManager.getAllProfiles().size() + " profiles loaded)");

        // 5. LLaMA client
        llamaClient = new LlamaClient(ollamaUrl, MODEL_NAME, MAX_PREDICT, HTTP_TIMEOUT_MS);
        log("LlamaClient ready -> " + ollamaUrl + " / " + MODEL_NAME);

        // 6. Social state machine
        socialStateMachine = new SocialStateMachine();

        // 7. Conversation manager
        conversationManager = new ConversationManager(llamaClient, socialStateMachine, language);

        log("All subsystems initialized.");
        log("========================================================");
    }

    // ----------------------------------------------------------------
    // Main loop
    // ----------------------------------------------------------------

    /**
     * Main program loop. Runs the state machine until interrupted.
     */
    public void run() {
        running = true;
        currentState = SystemState.IDLE;

        // Start face tracking
        faceManager.startTracking();

        // Set up face event callback
        faceManager.setFaceEventCallback(new FaceManager.FaceEventCallback() {
            @Override
            public void onFaceDetected(FaceDetectResult result) {
                if (currentState == SystemState.IDLE) {
                    log("Face detected! Transitioning to FACE_DETECTED");
                    currentState = SystemState.FACE_DETECTED;
                }
            }

            @Override
            public void onUserRecognized(FaceUser user) {
                log("Recognized user: " + user.getName());
            }

            @Override
            public void onNewFaceDetected(FaceDetectResult result) {
                log("New face detected (unknown user)");
            }

            @Override
            public void onAgeEstimated(int age, String gender) {
                log("Age estimated: " + age + ", gender: " + gender);
            }

            @Override
            public void onFaceLost() {
                // Only return to idle if not in conversation
                if (currentState == SystemState.FACE_DETECTED
                    || currentState == SystemState.RECOGNIZING) {
                    log("Face lost. Returning to IDLE");
                    currentState = SystemState.IDLE;
                }
            }
        });

        log("Entering main loop. Press Ctrl+C to stop.");
        log("State: IDLE — waiting for face...");

        // Add shutdown hook for clean exit
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            @Override
            public void run() {
                shutdown();
            }
        }));

        // Main state machine loop
        while (running) {
            try {
                switch (currentState) {
                    case IDLE:
                        handleIdle();
                        break;

                    case FACE_DETECTED:
                        handleFaceDetected();
                        break;

                    case RECOGNIZING:
                        handleRecognizing();
                        break;

                    case GREETING:
                        handleGreeting();
                        break;

                    case REGISTERING:
                        handleRegistering();
                        break;

                    case CONVERSING:
                        handleConversing();
                        break;

                    case UPDATING:
                        handleUpdating();
                        break;

                    case SHUTTING_DOWN:
                        running = false;
                        break;
                }
            } catch (Exception e) {
                log("ERROR in main loop: " + e.getMessage());
                e.printStackTrace();
                faceManager.resumeCallbacks();
                activeProfile = null;
                currentState = SystemState.IDLE;
            }

            CRobotUtil.wait(LOOP_INTERVAL_MS);
        }

        shutdown();
    }

    // ----------------------------------------------------------------
    // State handlers
    // ----------------------------------------------------------------

    /** IDLE: Wait for face detection. LED = blue. */
    private void handleIdle() {
        // LED: calm blue — waiting
        setLED(Color.BLUE, Color.BLUE, Color.GREEN);
        // Detection callback will transition us to FACE_DETECTED
    }

    /** FACE_DETECTED: A face was detected — pause detection, move to recognition. */
    private void handleFaceDetected() {
        log("--- FACE_DETECTED ---");
        setLED(Color.CYAN, Color.CYAN, Color.YELLOW);
        // Pause detection callbacks so they don't spam during interaction
        faceManager.pauseCallbacks();
        currentState = SystemState.RECOGNIZING;
    }

    /** RECOGNIZING: Check if the face is known or new. */
    private void handleRecognizing() {
        log("--- RECOGNIZING ---");

        FaceUser user = faceManager.getLatestUser();

        if (user != null && !user.isNewUser()) {
            // Known user — find their profile
            String faceName = user.getName();
            log("Known face recognized: " + faceName);

            // Look up in memory
            activeProfile = memoryManager.getProfileByName(faceName);
            if (activeProfile == null) {
                // Face is in camera DB but not in our JSON memory — create profile
                log("Face known to camera but no JSON profile. Creating...");
                activeProfile = new UserProfile(
                    memoryManager.generateUserId(),
                    faceName,
                    faceManager.getDetectedAge(),
                    faceManager.getDetectedGender()
                );
                memoryManager.addProfile(activeProfile);
            }

            currentState = SystemState.GREETING;

        } else if (user != null && user.isNewUser()) {
            // New face — need to register
            log("New face detected. Starting registration.");
            currentState = SystemState.REGISTERING;

        } else {
            // No user data yet — wait for face manager
            log("Waiting for face recognition data...");
            CRobotUtil.wait(500);

            // If we've been waiting too long without a result, try anyway
            if (!faceManager.isFaceDetected()) {
                log("Face lost during recognition. Returning to IDLE.");
                faceManager.resumeCallbacks();
                currentState = SystemState.IDLE;
            }
        }
    }

    /** REGISTERING: Ask for name, estimate age, register face, save profile. */
    private void handleRegistering() {
        log("--- REGISTERING ---");
        setLED(Color.ORANGE, Color.ORANGE, Color.YELLOW);

        // Ask for name
        speechManager.speak(isEnglish()
                ? "Nice to meet you! I'm Sota. What's your name?"
                : "はじめまして！僕はSotaです。あなたの名前はなんですか？",
                SocialState.STRANGER);

        // Listen for name
        String name = speechManager.listenForName();
        if (name == null) {
            // Retry once
            speechManager.speak(isEnglish()
                    ? "Sorry, I didn't catch that. Could you say your name again?"
                    : "ごめん、聞き取れなかった。もう一回教えてくれる？",
                    SocialState.STRANGER);
            name = speechManager.listenForName();
        }

        if (name == null) {
            // Give up
            speechManager.speak(isEnglish()
                    ? "Sorry about that. Maybe next time!"
                    : "ごめんね。また今度教えてね。", SocialState.STRANGER);
            faceManager.resumeCallbacks();
            currentState = SystemState.IDLE;
            return;
        }

        // Confirm name
        speechManager.speak(isEnglish()
                ? "Did you say " + name + "? Is that right?"
                : name + "さんでまちがいない？", SocialState.STRANGER);
        String confirm = speechManager.listenForYesNo();

        if (confirm == null || !"yes".equals(confirm)) {
            if ("no".equals(confirm)) {
                speechManager.speak(isEnglish()
                        ? "Oh, I got it wrong. Sorry!"
                        : "ちがった。ごめんね。", SocialState.STRANGER);
            } else {
                speechManager.speak(isEnglish()
                        ? "Sorry, I couldn't understand."
                        : "ごめん、わからなかったよ。", SocialState.STRANGER);
            }
            faceManager.resumeCallbacks();
            currentState = SystemState.IDLE;
            return;
        }

        // Get age estimation from camera
        int estimatedAge = faceManager.getDetectedAge();
        String gender = faceManager.getDetectedGender();
        log("Age estimation: " + estimatedAge + ", gender: " + gender);

        // Register face with camera
        boolean registered = faceManager.registerFace(name);
        if (registered) {
            speechManager.speak(isEnglish()
                    ? "Okay! I've memorized your face, " + name + "!"
                    : "オッケー！" + name + "の顔は覚えたよ！",
                    SocialState.STRANGER);
        } else {
            speechManager.speak(isEnglish()
                    ? name + ", I couldn't save your face, but I'll remember your name!"
                    : name + "、覚えようとしたけどうまくいかなかった。でも名前は覚えたよ！",
                    SocialState.STRANGER);
        }

        // Create and save profile
        String userId = memoryManager.generateUserId();
        activeProfile = new UserProfile(userId, name, estimatedAge, gender);
        memoryManager.addProfile(activeProfile);
        log("New profile created: " + activeProfile);

        currentState = SystemState.GREETING;
    }

    /** GREETING: Greet the user with a social-state-appropriate message. */
    private void handleGreeting() {
        log("--- GREETING ---");
        if (activeProfile == null) {
            log("ERROR: No active profile for greeting. Returning to IDLE.");
            faceManager.resumeCallbacks();
            currentState = SystemState.IDLE;
            return;
        }

        setLED(Color.GREEN, Color.GREEN, Color.GREEN);

        // Generate and speak greeting
        String greeting = conversationManager.generateGreeting(activeProfile);
        speechManager.speak(greeting, activeProfile.getSocialState());
        log("Greeted " + activeProfile.getName() + " as "
            + activeProfile.getSocialState().getLabel());

        currentState = SystemState.CONVERSING;
    }

    /** CONVERSING: Multi-turn conversation with LLaMA. */
    private void handleConversing() {
        log("--- CONVERSING ---");
        if (activeProfile == null) {
            currentState = SystemState.IDLE;
            return;
        }

        setLED(Color.GREEN, Color.GREEN, Color.YELLOW);

        // Start the conversation
        String llmResponse = conversationManager.startConversation(activeProfile);
        if (llmResponse != null) {
            speechManager.speak(llmResponse, activeProfile.getSocialState());
        }

        // Conversation loop
        int turnCount = 0;
        while (running && turnCount < MAX_CONVERSATION_TURNS) {
            turnCount++;
            log("Conversation turn " + turnCount + "/" + MAX_CONVERSATION_TURNS);

            // Listen for user's response
            setLED(Color.CYAN, Color.CYAN, Color.GREEN);  // listening indicator
            String userSpeech = speechManager.listen();

            if (userSpeech == null) {
                // No speech detected — end conversation
                log("No speech detected. Ending conversation.");
                break;
            }

            // Check for conversation-ending phrases
            if (containsEndPhrase(userSpeech)) {
                log("End phrase detected: " + userSpeech);
                speechManager.speak(isEnglish()
                        ? "See you later! Let's talk again!"
                        : "じゃあね！また話そうね！",
                        activeProfile.getSocialState());
                break;
            }

            // Get LLM response
            setLED(Color.GREEN, Color.GREEN, Color.YELLOW);  // thinking indicator
            String response = conversationManager.chat(activeProfile, userSpeech);

            if (response != null) {
                speechManager.speak(response, activeProfile.getSocialState());
            } else {
                speechManager.speak(isEnglish()
                        ? "Sorry, I didn't quite get that."
                        : "ごめん、ちょっとわからなかった。",
                        activeProfile.getSocialState());
                break;
            }
        }

        // Say goodbye if we hit the turn limit
        if (turnCount >= MAX_CONVERSATION_TURNS) {
            speechManager.speak(isEnglish()
                    ? "That was fun! See you later, " + activeProfile.getName() + "!"
                    : "楽しかった！またね、" + activeProfile.getName() + "！",
                    activeProfile.getSocialState());
        }

        currentState = SystemState.UPDATING;
    }

    /** UPDATING: Update interaction count, social state, memory summary. */
    private void handleUpdating() {
        log("--- UPDATING ---");
        if (activeProfile == null) {
            currentState = SystemState.IDLE;
            return;
        }

        setLED(Color.YELLOW, Color.YELLOW, Color.GREEN);

        // 1. Update interaction count
        memoryManager.updateInteraction(activeProfile.getUserId());
        log("Interaction count -> " + activeProfile.getInteractionCount());

        // 2. Update social state
        boolean stateChanged = socialStateMachine.updateState(activeProfile);
        if (stateChanged) {
            memoryManager.updateSocialState(activeProfile.getUserId(),
                                             activeProfile.getSocialState());
            log("Social state updated -> " + activeProfile.getSocialState().getLabel());
        }

        // 3. Generate and save memory summary
        String summary = conversationManager.generateMemorySummary(activeProfile);
        if (summary != null && !summary.isEmpty()) {
            memoryManager.updateMemorySummary(activeProfile.getUserId(), summary);
            log("Memory summary updated: " + summary);
        }

        // 4. End conversation
        conversationManager.endConversation();

        // Print status
        log("=== Interaction Complete ===");
        log("  User: " + activeProfile.getName());
        log("  Interactions: " + activeProfile.getInteractionCount());
        log("  Social state: " + activeProfile.getSocialState().getLabel());
        int remaining = socialStateMachine.interactionsUntilNextState(activeProfile);
        if (remaining > 0) {
            log("  Until next state: " + remaining + " interactions");
        }
        log("============================");

        // Clear active profile
        activeProfile = null;

        // Cooldown before next interaction
        log("Cooldown " + COOLDOWN_MS + "ms before returning to IDLE...");
        CRobotUtil.wait(COOLDOWN_MS);

        // Resume face detection callbacks for next encounter
        faceManager.resumeCallbacks();

        currentState = SystemState.IDLE;
        log("State: IDLE — waiting for face...");
    }

    // ----------------------------------------------------------------
    // Shutdown
    // ----------------------------------------------------------------

    private void shutdown() {
        log("Shutting down...");

        // Stop face tracking
        if (faceManager != null) {
            faceManager.stopTracking();
        }

        // Save any pending data
        if (memoryManager != null) {
            memoryManager.saveProfiles();
        }

        // Servo off
        if (motion != null) {
            try {
                motion.ServoOff();
            } catch (Exception e) {
                // ignore
            }
        }

        log("Shutdown complete.");
    }

    // ----------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------

    /** Set Sota's LED colors (left eye, right eye, power button). Mouth = 255. */
    private void setLED(Color leftEye, Color rightEye, Color power) {
        try {
            CRobotPose pose = new CRobotPose();
            pose.setLED_Sota(leftEye, rightEye, 255, power);
            motion.play(pose, 100, "LED_UPDATE");
        } catch (Exception e) {
            // LED failure is non-critical
        }
    }

    /** Check if current language is English. */
    private boolean isEnglish() {
        return "en".equals(language);
    }

    /** Check if user speech contains a conversation-ending phrase. */
    private boolean containsEndPhrase(String text) {
        if (text == null) return false;
        String lower = text.toLowerCase();
        // Both languages checked regardless of setting (user may switch)
        return lower.contains("さようなら")
            || lower.contains("ばいばい")
            || lower.contains("じゃあね")
            || lower.contains("おわり")
            || lower.contains("bye")
            || lower.contains("goodbye")
            || lower.contains("see you");
    }

    // ----------------------------------------------------------------
    // Logging
    // ----------------------------------------------------------------

    private void log(String msg) {
        System.out.println("[" + TAG + "] " + msg);
    }
}
