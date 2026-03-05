package jp.vstone.sotatest;

import jp.vstone.RobotLib.*;

/**
 * Minimal servo test: moves each servo one at a time with logging.
 * Tests: BODY_Y(1), L_SHOULDER(2), L_ELBOW(3), R_SHOULDER(4), R_ELBOW(5),
 *        HEAD_Y(6), HEAD_P(7), HEAD_R(8)
 */
public class ServoTest {
    public static void main(String[] args) {
        System.out.println("[ServoTest] Starting...");

        CRobotMem mem = new CRobotMem();
        if (!mem.Connect()) {
            System.out.println("[ServoTest] FATAL: Cannot connect to robot!");
            System.exit(1);
        }
        CSotaMotion motion = new CSotaMotion(mem);
        motion.InitRobot_Sota();
        System.out.println("[ServoTest] Connected. Firmware: " + mem.FirmwareRev.get());

        // Servo on
        motion.ServoOn();
        System.out.println("[ServoTest] Servos ON");
        CRobotUtil.wait(500);

        // ---- Step 1: Go to natural pose ----
        System.out.println("\n[ServoTest] === Step 1: Natural pose {0,-900,0,900,0,0,0,0} ===");
        CRobotPose pose = new CRobotPose();
        pose.SetPose(
            new Byte[]  {1,    2,     3,  4,   5,  6,  7,  8},
            new Short[] {0, -900,     0, 900,  0,  0,  0,  0}
        );
        boolean ok = motion.play(pose, 1000);
        System.out.println("[ServoTest] play() returned: " + ok);
        CRobotUtil.wait(1500);
        System.out.println("[ServoTest] Natural pose set. Arms should hang down.");

        // ---- Step 2: Read current servo positions ----
        System.out.println("\n[ServoTest] === Step 2: Reading servo positions ===");
        readPositions(motion);

        // ---- Step 3: Test HEAD servos (these should work) ----
        System.out.println("\n[ServoTest] === Step 3: HEAD test (nod) ===");
        pose = new CRobotPose();
        pose.SetPose(new Byte[]{7}, new Short[]{200});  // HEAD_P up
        motion.play(pose, 500);
        CRobotUtil.wait(700);
        System.out.println("[ServoTest] Head pitch up (200)");
        readPositions(motion);

        pose = new CRobotPose();
        pose.SetPose(new Byte[]{7}, new Short[]{-100});  // HEAD_P down
        motion.play(pose, 500);
        CRobotUtil.wait(700);
        System.out.println("[ServoTest] Head pitch down (-100)");

        // ---- Step 4: Test BODY_Y ----
        System.out.println("\n[ServoTest] === Step 4: BODY rotate ===");
        pose = new CRobotPose();
        pose.SetPose(new Byte[]{1}, new Short[]{300});  // BODY_Y rotate right
        motion.play(pose, 800);
        CRobotUtil.wait(1000);
        System.out.println("[ServoTest] Body rotated right (300)");
        readPositions(motion);

        pose = new CRobotPose();
        pose.SetPose(new Byte[]{1}, new Short[]{0});  // back to center
        motion.play(pose, 800);
        CRobotUtil.wait(1000);

        // ---- Step 5: Test L_SHOULDER (ID=2) ----
        System.out.println("\n[ServoTest] === Step 5: LEFT SHOULDER raise ===");
        pose = new CRobotPose();
        pose.SetPose(new Byte[]{2}, new Short[]{-300});  // L shoulder raise from -900
        motion.play(pose, 800);
        CRobotUtil.wait(1000);
        System.out.println("[ServoTest] L_SHOULDER set to -300 (should raise left arm)");
        readPositions(motion);

        // Return L shoulder
        pose = new CRobotPose();
        pose.SetPose(new Byte[]{2}, new Short[]{-900});
        motion.play(pose, 800);
        CRobotUtil.wait(1000);

        // ---- Step 6: Test R_SHOULDER (ID=4) ----
        System.out.println("\n[ServoTest] === Step 6: RIGHT SHOULDER raise ===");
        pose = new CRobotPose();
        pose.SetPose(new Byte[]{4}, new Short[]{300});  // R shoulder raise from 900
        motion.play(pose, 800);
        CRobotUtil.wait(1000);
        System.out.println("[ServoTest] R_SHOULDER set to 300 (should raise right arm)");
        readPositions(motion);

        // Return R shoulder
        pose = new CRobotPose();
        pose.SetPose(new Byte[]{4}, new Short[]{900});
        motion.play(pose, 800);
        CRobotUtil.wait(1000);

        // ---- Step 7: Test L_ELBOW (ID=3) ----
        System.out.println("\n[ServoTest] === Step 7: LEFT ELBOW bend ===");
        pose = new CRobotPose();
        pose.SetPose(new Byte[]{3}, new Short[]{-400});  // L elbow bend
        motion.play(pose, 800);
        CRobotUtil.wait(1000);
        System.out.println("[ServoTest] L_ELBOW set to -400 (should bend left arm)");
        readPositions(motion);

        // Return
        pose = new CRobotPose();
        pose.SetPose(new Byte[]{3}, new Short[]{0});
        motion.play(pose, 800);
        CRobotUtil.wait(1000);

        // ---- Step 8: Test R_ELBOW (ID=5) ----
        System.out.println("\n[ServoTest] === Step 8: RIGHT ELBOW bend ===");
        pose = new CRobotPose();
        pose.SetPose(new Byte[]{5}, new Short[]{400});  // R elbow bend (mirrored)
        motion.play(pose, 800);
        CRobotUtil.wait(1000);
        System.out.println("[ServoTest] R_ELBOW set to 400 (should bend right arm)");
        readPositions(motion);

        // Return
        pose = new CRobotPose();
        pose.SetPose(new Byte[]{5}, new Short[]{0});
        motion.play(pose, 800);
        CRobotUtil.wait(1000);

        // ---- Step 9: Both arms up simultaneously ----
        System.out.println("\n[ServoTest] === Step 9: BOTH arms raise ===");
        pose = new CRobotPose();
        pose.SetPose(
            new Byte[]  {2,    3,    4,   5},
            new Short[] {-200, -300, 200, 300}
        );
        motion.play(pose, 800);
        CRobotUtil.wait(1200);
        System.out.println("[ServoTest] Both arms raised");
        readPositions(motion);

        // ---- Step 10: Return to natural ----
        System.out.println("\n[ServoTest] === Step 10: Return to natural ===");
        pose = new CRobotPose();
        pose.SetPose(
            new Byte[]  {1,    2,     3,  4,   5,  6,  7,  8},
            new Short[] {0, -900,     0, 900,  0,  0,  0,  0}
        );
        motion.play(pose, 1000);
        CRobotUtil.wait(1500);
        System.out.println("[ServoTest] Back to natural");
        readPositions(motion);

        System.out.println("\n[ServoTest] === TEST COMPLETE ===");
        motion.ServoOff();
        System.out.println("[ServoTest] Servos OFF");
    }

    private static void readPositions(CSotaMotion motion) {
        try {
            String[] names = {"BODY_Y", "L_SHOULDER", "L_ELBOW", "R_SHOULDER", "R_ELBOW",
                              "HEAD_Y", "HEAD_P", "HEAD_R"};
            Short[] positions = motion.getReadpos();
            if (positions == null) {
                System.out.println("  getReadpos() returned null");
                return;
            }
            System.out.println("  getReadpos() length=" + positions.length);
            for (int i = 0; i < positions.length && i < names.length; i++) {
                System.out.println("  [" + (i+1) + "] " + names[i] + " = " +
                    (positions[i] != null ? positions[i].toString() : "null"));
            }
            // Also print play() return value
            System.out.println("  isEndInterpAll=" + motion.isEndInterpAll());
        } catch (Exception e) {
            System.out.println("  readPositions error: " + e.getClass().getName() + ": " + e.getMessage());
            e.printStackTrace();
        }
    }
}
