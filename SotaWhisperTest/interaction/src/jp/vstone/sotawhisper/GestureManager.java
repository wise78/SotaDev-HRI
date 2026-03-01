package jp.vstone.sotawhisper;

import java.awt.Color;
import java.util.Random;
import jp.vstone.RobotLib.CRobotPose;
import jp.vstone.RobotLib.CRobotUtil;
import jp.vstone.RobotLib.CSotaMotion;

/**
 * Manages dynamic gestures, LED colors, and motion based on interaction state.
 * Runs gesture + LED loops in background daemon threads.
 *
 * Servo IDs (Sota):
 *   1=HEAD_Y (Yaw/L-R),  2=HEAD_P (Pitch/Up-Down),  3=HEAD_R (Roll/Tilt)
 *   4=BODY_Y (Rotation)
 *   5=L_SHOULDER_P,  6=R_SHOULDER_P,  7=L_ELBOW_P,  8=R_ELBOW_P
 *
 * Safe servo ranges:
 *   HEAD_Y  : -700..700   (neg=left,   pos=right)
 *   HEAD_P  : -700..300   (neg=down,   pos=up/confident)
 *   HEAD_R  : -150..150   (neg=tilt-L, pos=tilt-R)
 *   BODY_Y  : -500..900   (neg=rot-L,  pos=rot-R)
 *   SHOULDER: -900..100   (very-neg=arm-raised, 0=natural, pos=arm-back)
 *   ELBOW   : -900..70    (neg=bent,   hardware max +80, safe margin 70)
 *
 * Improvements over v1:
 *   - waitChecked(): polls every 150ms so state changes are picked up fast
 *   - pendingListenInvite: one-shot "your turn" gesture when entering LISTENING
 *   - gestureSpeaking(): 7 patterns (was 5), fluid two-phase motions
 *   - gestureThinking(): 3 alternating phases instead of one repeated pose
 *   - gestureListening(): more pronounced forward lean + nodding
 *   - gestureIdle(): full-body subtle breathing with occasional head scan
 *
 * Java 1.8, no lambda.
 */
public class GestureManager {

    private static final String TAG = "Gesture";

    // ----------------------------------------------------------------
    // Servo IDs
    // ----------------------------------------------------------------
    private static final byte SV_HEAD_Y       = 1;
    private static final byte SV_HEAD_P       = 2;
    private static final byte SV_HEAD_R       = 3;
    private static final byte SV_BODY_Y       = 4;
    private static final byte SV_L_SHOULDER_P = 5;
    private static final byte SV_R_SHOULDER_P = 6;
    private static final byte SV_L_ELBOW_P    = 7;
    private static final byte SV_R_ELBOW_P    = 8;

    private static final int SPEAKING_PATTERN_COUNT = 7;

    private final CSotaMotion motion;
    private final Random random = new Random();

    private volatile String  currentState        = "idle";
    private volatile boolean running             = false;
    private volatile boolean pendingListenInvite = false;

    private Thread gestureThread;
    private Thread ledThread;

    private int ledErrorCount = 0;

    // Tracks current thinking phase so we cycle through them
    private int thinkPhase = 0;

    // ----------------------------------------------------------------
    // Lifecycle
    // ----------------------------------------------------------------

    public GestureManager(CSotaMotion motion) {
        this.motion = motion;
    }

    public void start() {
        if (running) return;
        running = true;

        gestureThread = new Thread(new Runnable() {
            public void run() { gestureLoop(); }
        }, "GestureLoop");
        gestureThread.setDaemon(true);
        gestureThread.start();

        ledThread = new Thread(new Runnable() {
            public void run() { ledLoop(); }
        }, "LEDLoop");
        ledThread.setDaemon(true);
        ledThread.start();

        log("Started gesture + LED threads");
    }

    public void stop() {
        running = false;
        try {
            if (gestureThread != null) gestureThread.join(2000);
            if (ledThread     != null) ledThread.join(2000);
        } catch (InterruptedException e) { /* ignore */ }
        moveToNeutral(500);
        log("Stopped");
    }

    /**
     * Change the current interaction state.
     * When transitioning INTO "listening", schedules a one-shot invite gesture
     * so the user clearly sees the robot is ready to receive speech.
     */
    public void setState(String state) {
        if ("listening".equals(state) && !"listening".equals(this.currentState)) {
            pendingListenInvite = true;
        }
        this.currentState = state;
    }

    // ----------------------------------------------------------------
    // Responsiveness helper
    // Breaks out of the wait early when the state changes, so gesture
    // transitions are near-instantaneous instead of waiting up to several
    // seconds for the previous sleep to expire.
    // ----------------------------------------------------------------

    private void waitChecked(int ms) {
        String stateAtStart = currentState;
        int waited = 0;
        while (running && waited < ms) {
            int step = Math.min(150, ms - waited);
            CRobotUtil.wait(step);
            waited += step;
            if (!currentState.equals(stateAtStart)) return;
        }
    }

    // ----------------------------------------------------------------
    // Gesture Loop
    // ----------------------------------------------------------------

    private void gestureLoop() {
        while (running) {
            try {
                String state = currentState;

                if ("greeting".equals(state) || "responding".equals(state)) {
                    gestureSpeaking();
                } else if ("recognizing".equals(state)) {
                    gestureRecognizing();
                } else if ("registering".equals(state)) {
                    gestureRegistering();
                } else if ("listening".equals(state)) {
                    gestureListening();
                } else if ("thinking".equals(state)) {
                    gestureThinking();
                } else if ("closing".equals(state)) {
                    gestureWaveGoodbye();
                    waitChecked(2000);
                } else {
                    gestureIdle();
                }
            } catch (Exception e) {
                log("WARN gesture: " + e.getMessage());
                CRobotUtil.wait(200);
            }
        }
    }

    // ================================================================
    // SPEAKING — 7 dynamic patterns
    // Runs throughout TTS playback (gesture thread is independent of TTS).
    // ================================================================

    private void gestureSpeaking() {
        int pattern = random.nextInt(SPEAKING_PATTERN_COUNT);
        switch (pattern) {
            case 0:  gestureSpeakNodAndArms();       break;
            case 1:  gestureSpeakBothHandsOpen();    break;
            case 2:  gestureSpeakLeftEmphasis();      break;
            case 3:  gestureSpeakRightEmphasis();     break;
            case 4:  gestureSpeakBodySway();          break;
            case 5:  gestureSpeakEnthusiastic();      break;
            case 6:  gestureSpeakBeatRhythm();        break;
            default: gestureSpeakNodAndArms();        break;
        }
    }

    /** Pattern 0: Nod with both arms moving — general conversational gesture. */
    private void gestureSpeakNodAndArms() {
        safePlayPose(
            new Byte[]  { SV_HEAD_P, SV_HEAD_Y,
                          SV_L_SHOULDER_P, SV_R_SHOULDER_P,
                          SV_L_ELBOW_P, SV_R_ELBOW_P },
            new Short[] { (short) randomRange(-100, 150),
                          (short) randomRange(-200, 200),
                          (short) randomRange(-400, -100),
                          (short) randomRange(-400, -100),
                          (short) randomRange(-300, -50),
                          (short) randomRange(-300, -50) },
            500 + random.nextInt(400));
        waitChecked(150);
        // Settle back slightly
        safePlayPose(
            new Byte[]  { SV_HEAD_P, SV_L_SHOULDER_P, SV_R_SHOULDER_P },
            new Short[] { (short) randomRange(0, 80),
                          (short) randomRange(-200, -50),
                          (short) randomRange(-200, -50) },
            400);
    }

    /** Pattern 1: Both arms open outward — "explaining / presenting" gesture. */
    private void gestureSpeakBothHandsOpen() {
        short shoulderOut = (short) randomRange(-600, -350);
        short elbowOut    = (short) randomRange(-350, -150);
        safePlayPose(
            new Byte[]  { SV_HEAD_P, SV_HEAD_Y,
                          SV_L_SHOULDER_P, SV_R_SHOULDER_P,
                          SV_L_ELBOW_P,    SV_R_ELBOW_P },
            new Short[] { (short) randomRange(30, 100),
                          (short) randomRange(-100, 100),
                          shoulderOut, shoulderOut,
                          elbowOut,    elbowOut },
            600 + random.nextInt(300));
        waitChecked(200);
        // Return arms inward naturally
        safePlayPose(
            new Byte[]  { SV_L_SHOULDER_P, SV_R_SHOULDER_P,
                          SV_L_ELBOW_P,    SV_R_ELBOW_P },
            new Short[] { (short) randomRange(-250, -50),
                          (short) randomRange(-250, -50),
                          (short) randomRange(-150, 0),
                          (short) randomRange(-150, 0) },
            500);
    }

    /** Pattern 2: Left arm emphasis — body and head turn left, left arm raised. */
    private void gestureSpeakLeftEmphasis() {
        safePlayPose(
            new Byte[]  { SV_HEAD_Y, SV_HEAD_P, SV_BODY_Y,
                          SV_L_SHOULDER_P, SV_L_ELBOW_P,
                          SV_R_SHOULDER_P },
            new Short[] { (short) randomRange(-250, -80),
                          (short) randomRange(30, 120),
                          (short) randomRange(-250, -80),
                          (short) randomRange(-700, -450),
                          (short) randomRange(-500, -250),
                          (short) randomRange(-150, 50) },
            600 + random.nextInt(300));
        waitChecked(200);
        // Ease back to centre
        safePlayPose(
            new Byte[]  { SV_HEAD_Y, SV_BODY_Y },
            new Short[] { 0, 0 },
            500);
    }

    /** Pattern 3: Right arm emphasis — body and head turn right, right arm raised. */
    private void gestureSpeakRightEmphasis() {
        safePlayPose(
            new Byte[]  { SV_HEAD_Y, SV_HEAD_P, SV_BODY_Y,
                          SV_R_SHOULDER_P, SV_R_ELBOW_P,
                          SV_L_SHOULDER_P },
            new Short[] { (short) randomRange(80, 250),
                          (short) randomRange(30, 120),
                          (short) randomRange(80, 250),
                          (short) randomRange(-700, -450),
                          (short) randomRange(-500, -250),
                          (short) randomRange(-150, 50) },
            600 + random.nextInt(300));
        waitChecked(200);
        safePlayPose(
            new Byte[]  { SV_HEAD_Y, SV_BODY_Y },
            new Short[] { 0, 0 },
            500);
    }

    /** Pattern 4: Body sway — torso rotation with synchronized arms. */
    private void gestureSpeakBodySway() {
        short bodyDir = (short) (random.nextBoolean()
            ? randomRange(200, 450) : randomRange(-450, -200));
        safePlayPose(
            new Byte[]  { SV_BODY_Y, SV_HEAD_Y,
                          SV_L_SHOULDER_P, SV_R_SHOULDER_P,
                          SV_L_ELBOW_P,    SV_R_ELBOW_P },
            new Short[] { bodyDir,
                          (short) (-bodyDir / 3),
                          (short) randomRange(-400, -150),
                          (short) randomRange(-400, -150),
                          (short) randomRange(-300, -80),
                          (short) randomRange(-300, -80) },
            700 + random.nextInt(300));
        waitChecked(150);
        // Sway back
        safePlayPose(
            new Byte[]  { SV_BODY_Y, SV_HEAD_Y },
            new Short[] { (short) (-bodyDir / 2), (short) (bodyDir / 6) },
            500);
    }

    /**
     * Pattern 5: Enthusiastic — both arms raised wide and open.
     * Used for excited or emphatic statements.
     */
    private void gestureSpeakEnthusiastic() {
        safePlayPose(
            new Byte[]  { SV_HEAD_P, SV_HEAD_Y, SV_BODY_Y,
                          SV_L_SHOULDER_P, SV_R_SHOULDER_P,
                          SV_L_ELBOW_P,    SV_R_ELBOW_P },
            new Short[] { (short) randomRange(80, 150),
                          (short) randomRange(-150, 150),
                          (short) randomRange(-100, 100),
                          -650, -650,
                          -350, -350 },
            500);
        waitChecked(300);
        // Bring down to a relaxed-open position
        safePlayPose(
            new Byte[]  { SV_L_SHOULDER_P, SV_R_SHOULDER_P,
                          SV_L_ELBOW_P,    SV_R_ELBOW_P },
            new Short[] { (short) randomRange(-350, -200),
                          (short) randomRange(-350, -200),
                          (short) randomRange(-200, -50),
                          (short) randomRange(-200, -50) },
            450);
    }

    /**
     * Pattern 6: Beat / rhythm — alternating arm beats like conducting music.
     * Creates a sense that the robot is pacing speech with gesture.
     */
    private void gestureSpeakBeatRhythm() {
        // Phase A: left arm leads
        safePlayPose(
            new Byte[]  { SV_HEAD_P, SV_HEAD_Y,
                          SV_L_SHOULDER_P, SV_L_ELBOW_P,
                          SV_R_SHOULDER_P },
            new Short[] { (short) randomRange(30, 100),
                          (short) randomRange(-80, 0),
                          -400, -280,
                          (short) randomRange(-150, 0) },
            300);
        waitChecked(120);
        // Phase B: right arm answers
        safePlayPose(
            new Byte[]  { SV_HEAD_Y,
                          SV_R_SHOULDER_P, SV_R_ELBOW_P,
                          SV_L_SHOULDER_P },
            new Short[] { (short) randomRange(0, 80),
                          -400, -280,
                          (short) randomRange(-150, 0) },
            300);
        waitChecked(120);
        // Settle: both arms relax
        safePlayPose(
            new Byte[]  { SV_L_SHOULDER_P, SV_R_SHOULDER_P,
                          SV_L_ELBOW_P,    SV_R_ELBOW_P },
            new Short[] { (short) randomRange(-200, -80),
                          (short) randomRange(-200, -80),
                          (short) randomRange(-150, 0),
                          (short) randomRange(-150, 0) },
            400);
    }

    // ================================================================
    // LISTENING — invite + attentive loop
    // ================================================================

    private void gestureListening() {
        // One-shot invite gesture when first entering this state
        if (pendingListenInvite) {
            pendingListenInvite = false;
            gestureListenInvite();
            return;
        }

        // Attentive hold: lean forward, occasional nod, subtle head shifts
        int choice = random.nextInt(3);
        if (choice == 0) {
            // Nod — confirms the robot is paying attention
            safePlayPose(
                new Byte[]  { SV_HEAD_P, SV_HEAD_Y, SV_BODY_Y },
                new Short[] { (short) randomRange(100, 160),
                              (short) randomRange(-60, 60),
                              (short) randomRange(-30, 30) },
                500);
            waitChecked(300);
            safePlayPose(
                new Byte[]  { SV_HEAD_P },
                new Short[] { (short) randomRange(60, 100) },
                350);
        } else if (choice == 1) {
            // Slight head tilt — curious / engaged
            safePlayPose(
                new Byte[]  { SV_HEAD_P, SV_HEAD_Y, SV_HEAD_R },
                new Short[] { (short) randomRange(60, 120),
                              (short) randomRange(-50, 50),
                              (short) (random.nextBoolean() ? 60 : -60) },
                600);
        } else {
            // Hold attentive: head up, looking at speaker
            safePlayPose(
                new Byte[]  { SV_HEAD_P, SV_HEAD_Y, SV_HEAD_R, SV_BODY_Y },
                new Short[] { (short) randomRange(60, 110),
                              (short) randomRange(-40, 40),
                              (short) randomRange(-30, 30),
                              (short) randomRange(-20, 20) },
                700);
        }
        waitChecked(700 + random.nextInt(500));
    }

    /**
     * One-shot "your turn" invite gesture played when the robot first enters
     * LISTENING state.  The clear lean-forward + arms-open pose signals to
     * the user that the robot is ready to hear them speak.
     */
    private void gestureListenInvite() {
        // Lean toward the person with arms slightly open — open/welcoming
        safePlayPose(
            new Byte[]  { SV_HEAD_P, SV_HEAD_Y, SV_HEAD_R, SV_BODY_Y,
                          SV_L_SHOULDER_P, SV_R_SHOULDER_P,
                          SV_L_ELBOW_P,    SV_R_ELBOW_P },
            new Short[] { 150, 0, 0, 0,
                          -280, -280,
                          -120, -120 },
            500);
        waitChecked(250);
        // Gently settle arms down — hold head up
        safePlayPose(
            new Byte[]  { SV_L_SHOULDER_P, SV_R_SHOULDER_P,
                          SV_L_ELBOW_P,    SV_R_ELBOW_P },
            new Short[] { -80, -80, -30, -30 },
            400);
    }

    // ================================================================
    // RECOGNIZING — alert, scanning posture
    // ================================================================

    private void gestureRecognizing() {
        safePlayPose(
            new Byte[]  { SV_HEAD_P, SV_HEAD_R, SV_HEAD_Y, SV_BODY_Y },
            new Short[] { (short) randomRange(50, 100),
                          (short) 0,
                          (short) randomRange(-80, 80),
                          (short) 0 },
            500);
        waitChecked(700);
    }

    // ================================================================
    // REGISTERING — curious head tilt
    // ================================================================

    private void gestureRegistering() {
        short roll = (short) (random.nextBoolean() ? 120 : -120);
        safePlayPose(
            new Byte[]  { SV_HEAD_R, SV_HEAD_P, SV_HEAD_Y },
            new Short[] { roll, (short) 60, (short) randomRange(-80, 80) },
            700);
        waitChecked(1000);
    }

    // ================================================================
    // THINKING — 3 alternating phases (cycles: left tilt → right tilt → look-up)
    // ================================================================

    private void gestureThinking() {
        thinkPhase = (thinkPhase + 1) % 3;

        if (thinkPhase == 0) {
            // Head tilts left, glances slightly upward (recalling)
            safePlayPose(
                new Byte[]  { SV_HEAD_R, SV_HEAD_P, SV_HEAD_Y, SV_BODY_Y },
                new Short[] { -130, (short) randomRange(-80, 0),
                              (short) randomRange(-100, -30), 0 },
                700);
        } else if (thinkPhase == 1) {
            // Head tilts right, slight upward gaze
            safePlayPose(
                new Byte[]  { SV_HEAD_R, SV_HEAD_P, SV_HEAD_Y, SV_BODY_Y },
                new Short[] { 130, (short) randomRange(-80, 0),
                              (short) randomRange(30, 100), 0 },
                700);
        } else {
            // Head looks upward, centered — "hmm" pose
            safePlayPose(
                new Byte[]  { SV_HEAD_R, SV_HEAD_P, SV_HEAD_Y,
                              SV_L_SHOULDER_P, SV_R_SHOULDER_P },
                new Short[] { 0, (short) randomRange(-150, -50), 0,
                              (short) randomRange(-100, -30),
                              (short) randomRange(-100, -30) },
                700);
        }
        waitChecked(500);
    }

    // ================================================================
    // IDLE — subtle breathing + occasional head scan
    // ================================================================

    private void gestureIdle() {
        int choice = random.nextInt(4);
        if (choice == 0) {
            // Gentle head scan left-right, as if looking around
            safePlayPose(
                new Byte[]  { SV_HEAD_Y, SV_HEAD_P, SV_HEAD_R },
                new Short[] { (short) randomRange(-300, -100),
                              (short) randomRange(-50, 60), 0 },
                1000);
            waitChecked(600);
            safePlayPose(
                new Byte[]  { SV_HEAD_Y },
                new Short[] { (short) randomRange(100, 300) },
                1000);
            waitChecked(600);
            safePlayPose(
                new Byte[]  { SV_HEAD_Y, SV_HEAD_P },
                new Short[] { 0, (short) randomRange(-20, 50) },
                800);
        } else {
            // Breathing: very subtle random head micro-movement
            safePlayPose(
                new Byte[]  { SV_HEAD_Y, SV_HEAD_P, SV_HEAD_R },
                new Short[] { (short) randomRange(-60, 60),
                              (short) randomRange(-30, 50), 0 },
                1200);
        }
        waitChecked(2000 + random.nextInt(2000));
    }

    // ================================================================
    // CLOSING — wave goodbye
    // ================================================================

    private void gestureWaveGoodbye() {
        // Raise right arm
        safePlayPose(
            new Byte[]  { SV_HEAD_P, SV_HEAD_Y,
                          SV_R_SHOULDER_P, SV_R_ELBOW_P },
            new Short[] { 80, 150,
                          -900, -400 },
            600);
        CRobotUtil.wait(300);
        // Wave elbow 3 times
        for (int i = 0; i < 3; i++) {
            safePlayPose(
                new Byte[]  { SV_R_ELBOW_P },
                new Short[] { (short) -180 },
                220);
            CRobotUtil.wait(220);
            safePlayPose(
                new Byte[]  { SV_R_ELBOW_P },
                new Short[] { (short) -500 },
                220);
            CRobotUtil.wait(220);
        }
        moveToNeutral(600);
    }

    // ================================================================
    // LED Loop
    // Each state has a distinct colour/animation so users can see
    // at a glance what the robot is doing.
    //
    //   IDLE        → white breathing (soft pulse)
    //   RECOGNIZING → orange solid
    //   REGISTERING → gold solid
    //   GREETING /
    //   RESPONDING  → green solid
    //   LISTENING   → cyan fast pulse  ← most important: "speak now"
    //   THINKING    → yellow-to-orange slow pulse
    //   CLOSING     → white fade
    // ================================================================

    private void ledLoop() {
        int breath = 100;
        int delta  = 12;
        int pulse  = 0;
        int thinkBrightness = 160;
        int thinkDelta      = 8;

        while (running) {
            try {
                String state = currentState;
                CRobotPose ledPose = new CRobotPose();

                if ("idle".equals(state)) {
                    // Slow white breath
                    breath += delta;
                    if (breath >= 220) { breath = 220; delta = -Math.abs(delta); }
                    else if (breath <= 80) { breath = 80; delta = Math.abs(delta); }
                    ledPose.setLED_Sota(Color.WHITE, Color.WHITE, breath, Color.WHITE);
                    motion.play(ledPose, 100);
                    CRobotUtil.wait(120);

                } else if ("recognizing".equals(state)) {
                    ledPose.setLED_Sota(Color.ORANGE, Color.ORANGE, 220, Color.ORANGE);
                    motion.play(ledPose, 100);
                    CRobotUtil.wait(150);

                } else if ("registering".equals(state)) {
                    Color gold = new Color(218, 165, 32);
                    ledPose.setLED_Sota(gold, gold, 200, gold);
                    motion.play(ledPose, 100);
                    CRobotUtil.wait(150);

                } else if ("greeting".equals(state) || "responding".equals(state)) {
                    ledPose.setLED_Sota(Color.GREEN, Color.GREEN, 255, Color.GREEN);
                    motion.play(ledPose, 100);
                    CRobotUtil.wait(120);

                } else if ("listening".equals(state)) {
                    // Fast cyan pulse — unmistakable "speak now" signal
                    boolean bright = ((pulse++ % 3) != 0);
                    Color c = bright ? Color.CYAN : new Color(0, 120, 180);
                    int   b = bright ? 240 : 160;
                    ledPose.setLED_Sota(c, c, b, c);
                    motion.play(ledPose, 80);
                    CRobotUtil.wait(200);

                } else if ("thinking".equals(state)) {
                    // Slow yellow ↔ orange pulse
                    thinkBrightness += thinkDelta;
                    if (thinkBrightness >= 230) { thinkBrightness = 230; thinkDelta = -Math.abs(thinkDelta); }
                    else if (thinkBrightness <= 120) { thinkBrightness = 120; thinkDelta = Math.abs(thinkDelta); }
                    Color c = (thinkBrightness > 180) ? Color.YELLOW : Color.ORANGE;
                    ledPose.setLED_Sota(c, c, thinkBrightness, c);
                    motion.play(ledPose, 120);
                    CRobotUtil.wait(200);

                } else if ("closing".equals(state)) {
                    ledPose.setLED_Sota(Color.WHITE, Color.WHITE, 130, Color.WHITE);
                    motion.play(ledPose, 200);
                    CRobotUtil.wait(200);

                } else {
                    CRobotUtil.wait(150);
                }

            } catch (Exception e) {
                ledErrorCount++;
                if (ledErrorCount == 1 || ledErrorCount % 100 == 0) {
                    log("WARN LED (" + ledErrorCount + "x): " + e.getMessage());
                }
                CRobotUtil.wait(ledErrorCount > 20 ? 1500 : 400);
            }
        }
    }

    // ================================================================
    // Motion helpers
    // ================================================================

    public void moveToNeutral(int timeMs) {
        safePlayPose(
            new Byte[]  { SV_HEAD_Y, SV_HEAD_P, SV_HEAD_R, SV_BODY_Y,
                          SV_L_SHOULDER_P, SV_R_SHOULDER_P,
                          SV_L_ELBOW_P,    SV_R_ELBOW_P },
            new Short[] { 0, 0, 0, 0, 0, 0, 0, 0 },
            timeMs);
    }

    private void safePlayPose(Byte[] ids, Short[] vals, int timeMs) {
        try {
            CRobotPose pose = new CRobotPose();
            pose.SetPose(ids, vals);
            motion.play(pose, timeMs);
        } catch (Exception e) {
            // Silently ignore motion errors (servo busy, etc.)
        }
        CRobotUtil.wait(timeMs + 50);
    }

    private int randomRange(int min, int max) {
        if (max <= min) return min;
        return min + random.nextInt(max - min + 1);
    }

    private void log(String msg) {
        System.out.println("[" + TAG + "] " + msg);
    }
}
