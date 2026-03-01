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
 *   1=HEAD_Y, 2=HEAD_P, 3=HEAD_R, 4=BODY_Y,
 *   5=L_SHOULDER_P, 6=R_SHOULDER_P, 7=L_ELBOW_P, 8=R_ELBOW_P
 *
 * Safe servo ranges:
 *   HEAD_Y: -700..700, HEAD_P: -700..300, HEAD_R: -150..150
 *   BODY_Y: -500..900
 *   L/R_SHOULDER: -900..100, L/R_ELBOW: -900..70
 *
 * Java 1.8, no lambda.
 */
public class GestureManager {

    private static final String TAG = "Gesture";

    // Servo IDs
    private static final byte SV_HEAD_Y       = 1;
    private static final byte SV_HEAD_P       = 2;
    private static final byte SV_HEAD_R       = 3;
    private static final byte SV_BODY_Y       = 4;
    private static final byte SV_L_SHOULDER_P = 5;
    private static final byte SV_R_SHOULDER_P = 6;
    private static final byte SV_L_ELBOW_P    = 7;
    private static final byte SV_R_ELBOW_P    = 8;

    private static final int SPEAKING_PATTERN_COUNT = 5;

    private final CSotaMotion motion;
    private final Random random = new Random();

    private volatile String currentState = "idle";
    private volatile boolean running = false;

    private Thread gestureThread;
    private Thread ledThread;

    private int ledErrorCount = 0;

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
            if (ledThread != null) ledThread.join(2000);
        } catch (InterruptedException e) { /* ignore */ }
        moveToNeutral(500);
        log("Stopped");
    }

    public void setState(String state) {
        this.currentState = state;
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
                    CRobotUtil.wait(2000);
                } else {
                    gestureIdle();
                }
            } catch (Exception e) {
                log("WARN gesture: " + e.getMessage());
                CRobotUtil.wait(200);
            }
        }
    }

    // ----------------------------------------------------------------
    // Speaking — 5 dynamic patterns with both hands
    // ----------------------------------------------------------------

    private void gestureSpeaking() {
        int pattern = random.nextInt(SPEAKING_PATTERN_COUNT);
        switch (pattern) {
            case 0:  gestureSpeakNodAndArms();     break;
            case 1:  gestureSpeakBothHandsOpen();  break;
            case 2:  gestureSpeakLeftEmphasis();    break;
            case 3:  gestureSpeakRightEmphasis();   break;
            case 4:  gestureSpeakBodySway();        break;
            default: gestureSpeakNodAndArms();      break;
        }
    }

    /** Pattern 0: Nod with both arms moving randomly. */
    private void gestureSpeakNodAndArms() {
        safePlayPose(
            new Byte[]  { SV_HEAD_P, SV_HEAD_Y,
                          SV_L_SHOULDER_P, SV_R_SHOULDER_P,
                          SV_L_ELBOW_P, SV_R_ELBOW_P },
            new Short[] { (short) randomRange(-150, 150),
                          (short) randomRange(-200, 200),
                          (short) randomRange(-400, 50),
                          (short) randomRange(-400, 50),
                          (short) randomRange(-300, 50),
                          (short) randomRange(-300, 50) },
            500 + random.nextInt(500));
    }

    /** Pattern 1: Both arms open outward — "explaining" gesture. */
    private void gestureSpeakBothHandsOpen() {
        short shoulderVal = (short) randomRange(-600, -300);
        short elbowVal    = (short) randomRange(-400, -100);
        safePlayPose(
            new Byte[]  { SV_HEAD_P, SV_HEAD_Y,
                          SV_L_SHOULDER_P, SV_R_SHOULDER_P,
                          SV_L_ELBOW_P, SV_R_ELBOW_P },
            new Short[] { (short) randomRange(-50, 80),
                          (short) randomRange(-100, 100),
                          shoulderVal, shoulderVal,
                          elbowVal, elbowVal },
            700 + random.nextInt(300));
        CRobotUtil.wait(200);
        safePlayPose(
            new Byte[]  { SV_L_SHOULDER_P, SV_R_SHOULDER_P,
                          SV_L_ELBOW_P, SV_R_ELBOW_P },
            new Short[] { (short) randomRange(-200, 0),
                          (short) randomRange(-200, 0),
                          (short) randomRange(-100, 0),
                          (short) randomRange(-100, 0) },
            500);
    }

    /** Pattern 2: Left arm emphasis — body turns left. */
    private void gestureSpeakLeftEmphasis() {
        safePlayPose(
            new Byte[]  { SV_HEAD_Y, SV_HEAD_P, SV_BODY_Y,
                          SV_L_SHOULDER_P, SV_L_ELBOW_P,
                          SV_R_SHOULDER_P },
            new Short[] { (short) randomRange(-200, -50),
                          (short) randomRange(-50, 100),
                          (short) randomRange(-200, -50),
                          (short) randomRange(-700, -400),
                          (short) randomRange(-500, -200),
                          (short) randomRange(-100, 50) },
            600 + random.nextInt(400));
    }

    /** Pattern 3: Right arm emphasis — body turns right. */
    private void gestureSpeakRightEmphasis() {
        safePlayPose(
            new Byte[]  { SV_HEAD_Y, SV_HEAD_P, SV_BODY_Y,
                          SV_R_SHOULDER_P, SV_R_ELBOW_P,
                          SV_L_SHOULDER_P },
            new Short[] { (short) randomRange(50, 200),
                          (short) randomRange(-50, 100),
                          (short) randomRange(50, 200),
                          (short) randomRange(-700, -400),
                          (short) randomRange(-500, -200),
                          (short) randomRange(-100, 50) },
            600 + random.nextInt(400));
    }

    /** Pattern 4: Body sway — torso rotation with synchronized arms. */
    private void gestureSpeakBodySway() {
        short bodyDir = (short) (random.nextBoolean()
            ? randomRange(200, 450) : randomRange(-450, -200));
        safePlayPose(
            new Byte[]  { SV_BODY_Y, SV_HEAD_Y,
                          SV_L_SHOULDER_P, SV_R_SHOULDER_P,
                          SV_L_ELBOW_P, SV_R_ELBOW_P },
            new Short[] { bodyDir,
                          (short) (-bodyDir / 3),
                          (short) randomRange(-400, -100),
                          (short) randomRange(-400, -100),
                          (short) randomRange(-300, -50),
                          (short) randomRange(-300, -50) },
            700 + random.nextInt(400));
    }

    // ----------------------------------------------------------------
    // Recognizing: alert posture
    // ----------------------------------------------------------------

    private void gestureRecognizing() {
        safePlayPose(
            new Byte[]  { SV_HEAD_P, SV_HEAD_R, SV_BODY_Y },
            new Short[] { (short) randomRange(30, 80), (short) 0, (short) 0 },
            500);
        CRobotUtil.wait(800);
    }

    // ----------------------------------------------------------------
    // Registering: curious tilt
    // ----------------------------------------------------------------

    private void gestureRegistering() {
        short roll = (short) (random.nextBoolean() ? 120 : -120);
        safePlayPose(
            new Byte[]  { SV_HEAD_R, SV_HEAD_P, SV_HEAD_Y },
            new Short[] { roll, (short) 50, (short) randomRange(-80, 80) },
            700);
        CRobotUtil.wait(1200);
    }

    // ----------------------------------------------------------------
    // Listening: lean forward, attentive nod
    // ----------------------------------------------------------------

    private void gestureListening() {
        safePlayPose(
            new Byte[]  { SV_HEAD_Y, SV_HEAD_P, SV_BODY_Y },
            new Short[] { (short) randomRange(-50, 50), (short) 40,
                          (short) randomRange(-30, 30) },
            800);
        CRobotUtil.wait(1500 + random.nextInt(1000));
        safePlayPose(
            new Byte[]  { SV_HEAD_P },
            new Short[] { (short) randomRange(20, 80) },
            400);
    }

    // ----------------------------------------------------------------
    // Thinking: head tilt
    // ----------------------------------------------------------------

    private void gestureThinking() {
        short roll = (short) (random.nextBoolean() ? 200 : -200);
        safePlayPose(
            new Byte[]  { SV_HEAD_R, SV_HEAD_P },
            new Short[] { roll, (short) -50 },
            800);
        CRobotUtil.wait(500);
    }

    // ----------------------------------------------------------------
    // Idle: subtle breathing
    // ----------------------------------------------------------------

    private void gestureIdle() {
        safePlayPose(
            new Byte[]  { SV_HEAD_Y, SV_HEAD_P, SV_HEAD_R },
            new Short[] { (short) randomRange(-80, 80),
                          (short) randomRange(-30, 60), (short) 0 },
            1200);
        CRobotUtil.wait(2000 + random.nextInt(2000));
    }

    // ----------------------------------------------------------------
    // Wave goodbye
    // ----------------------------------------------------------------

    private void gestureWaveGoodbye() {
        safePlayPose(
            new Byte[]  { SV_HEAD_P, SV_R_SHOULDER_P, SV_R_ELBOW_P },
            new Short[] { 50, (short) -900, (short) -400 },
            600);
        CRobotUtil.wait(300);
        for (int i = 0; i < 3; i++) {
            safePlayPose(
                new Byte[]  { SV_R_ELBOW_P },
                new Short[] { (short) -200 },
                250);
            CRobotUtil.wait(250);
            safePlayPose(
                new Byte[]  { SV_R_ELBOW_P },
                new Short[] { (short) -500 },
                250);
            CRobotUtil.wait(250);
        }
        moveToNeutral(600);
    }

    // ----------------------------------------------------------------
    // LED Loop
    // ----------------------------------------------------------------

    private void ledLoop() {
        int breath = 100;
        int delta  = 12;
        int pulse  = 0;

        while (running) {
            try {
                String state = currentState;
                CRobotPose ledPose = new CRobotPose();

                if ("idle".equals(state)) {
                    breath += delta;
                    if (breath >= 220) { breath = 220; delta = -Math.abs(delta); }
                    else if (breath <= 100) { breath = 100; delta = Math.abs(delta); }
                    ledPose.setLED_Sota(Color.WHITE, Color.WHITE, breath, Color.WHITE);
                    motion.play(ledPose, 100);
                    CRobotUtil.wait(120);

                } else if ("recognizing".equals(state)) {
                    ledPose.setLED_Sota(Color.ORANGE, Color.ORANGE, 220, Color.ORANGE);
                    motion.play(ledPose, 100);
                    CRobotUtil.wait(150);

                } else if ("registering".equals(state)) {
                    ledPose.setLED_Sota(new Color(218, 165, 32), new Color(218, 165, 32),
                                        200, new Color(218, 165, 32));
                    motion.play(ledPose, 100);
                    CRobotUtil.wait(150);

                } else if ("greeting".equals(state) || "responding".equals(state)) {
                    ledPose.setLED_Sota(Color.GREEN, Color.GREEN, 255, Color.GREEN);
                    motion.play(ledPose, 120);
                    CRobotUtil.wait(120);

                } else if ("listening".equals(state)) {
                    if ((pulse++ % 2) == 0) {
                        ledPose.setLED_Sota(Color.CYAN, Color.CYAN, 220, Color.CYAN);
                    } else {
                        ledPose.setLED_Sota(new Color(0, 100, 200), new Color(0, 100, 200),
                                            180, new Color(0, 100, 200));
                    }
                    motion.play(ledPose, 90);
                    CRobotUtil.wait(300);

                } else if ("thinking".equals(state)) {
                    ledPose.setLED_Sota(Color.YELLOW, Color.YELLOW, 200, Color.ORANGE);
                    motion.play(ledPose, 120);
                    CRobotUtil.wait(200);

                } else if ("closing".equals(state)) {
                    ledPose.setLED_Sota(Color.WHITE, Color.WHITE, 150, Color.WHITE);
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

    // ----------------------------------------------------------------
    // Motion helpers
    // ----------------------------------------------------------------

    public void moveToNeutral(int timeMs) {
        safePlayPose(
            new Byte[]  { SV_HEAD_Y, SV_HEAD_P, SV_HEAD_R, SV_BODY_Y,
                          SV_L_SHOULDER_P, SV_R_SHOULDER_P, SV_L_ELBOW_P, SV_R_ELBOW_P },
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
        return min + random.nextInt(max - min + 1);
    }

    private void log(String msg) {
        System.out.println("[" + TAG + "] " + msg);
    }
}
