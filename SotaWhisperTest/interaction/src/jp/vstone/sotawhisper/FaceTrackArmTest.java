package jp.vstone.sotawhisper;

import jp.vstone.RobotLib.*;
import jp.vstone.camera.CRoboCamera;

/**
 * Test: Does face tracking interfere with arm servo movement?
 * 
 * Phase 1: Arms ONLY (no face tracking) - should work
 * Phase 2: Start face tracking, then move arms - test if arms still work
 * Phase 3: Stop face tracking, then move arms - verify recovery
 */
public class FaceTrackArmTest {
    
    // Servo IDs
    static final byte BODY  = 1;
    static final byte L_SH  = 2;
    static final byte L_EL  = 3;
    static final byte R_SH  = 4;
    static final byte R_EL  = 5;
    static final byte H_Y   = 6;
    static final byte H_P   = 7;
    static final byte H_R   = 8;

    public static void main(String[] args) {
        System.out.println("=== Face Tracking + Arm Movement Test ===");
        
        CRobotMem mem = new CRobotMem();
        CSotaMotion motion = new CSotaMotion(mem);
        
        if (mem.Connect()) {
            System.out.println("Connected.");
            motion.InitRobot_Sota();
            CRobotUtil.wait(500);
            motion.ServoOn();
            CRobotUtil.wait(300);
            
            // Set initial neutral pose
            CRobotPose init = new CRobotPose();
            init.SetPose(
                new Byte[]  {BODY, L_SH, L_EL, R_SH, R_EL, H_Y, H_P, H_R},
                new Short[] {0, -900, 0, 900, 0, 0, 0, 0}
            );
            motion.play(init, 500);
            CRobotUtil.wait(600);
            
            // ============================================================
            // PHASE 1: Arms only — no face tracking
            // ============================================================
            System.out.println("\n--- PHASE 1: No face tracking, move arms ---");
            
            // Read initial position
            readAllPositions(motion, "Before arm move");
            
            // Move L_SHOULDER up (-400) and R_SHOULDER up (400)
            CRobotPose armPose = new CRobotPose();
            armPose.SetPose(
                new Byte[]  {L_SH, R_SH, L_EL, R_EL},
                new Short[] {-400, 400, -200, 200}
            );
            boolean ok = motion.play(armPose, 600);
            System.out.println("play(arms) returned: " + ok);
            CRobotUtil.wait(700);
            
            readAllPositions(motion, "After arm move (no face track)");
            
            // Return to neutral
            motion.play(init, 500);
            CRobotUtil.wait(600);
            
            // ============================================================
            // PHASE 2: Start face tracking, then move arms
            // ============================================================
            System.out.println("\n--- PHASE 2: WITH face tracking, move arms ---");
            
            CRoboCamera camera = new CRoboCamera("/dev/video0", motion);
            camera.setEnableFaceSearch(true);
            camera.StartFaceTraking();
            System.out.println("Face tracking STARTED");
            CRobotUtil.wait(2000); // Let face tracking settle
            
            readAllPositions(motion, "Before arm move (face tracking ON)");
            
            // Try to move arms while face tracking
            CRobotPose armPose2 = new CRobotPose();
            armPose2.SetPose(
                new Byte[]  {L_SH, R_SH, L_EL, R_EL},
                new Short[] {-400, 400, -200, 200}
            );
            ok = motion.play(armPose2, 600);
            System.out.println("play(arms during face track) returned: " + ok);
            CRobotUtil.wait(700);
            
            readAllPositions(motion, "After arm move (face tracking ON)");
            
            // Try again with BODY servo too
            CRobotPose bodyArm = new CRobotPose();
            bodyArm.SetPose(
                new Byte[]  {BODY, L_SH, R_SH},
                new Short[] {300, -200, 200}
            );
            ok = motion.play(bodyArm, 600);
            System.out.println("play(body+arms during face track) returned: " + ok);
            CRobotUtil.wait(700);
            
            readAllPositions(motion, "After body+arm move (face tracking ON)");
            
            // ============================================================
            // PHASE 3: Stop face tracking, then move arms
            // ============================================================
            System.out.println("\n--- PHASE 3: STOPPED face tracking, move arms ---");
            camera.StopFaceTraking();
            System.out.println("Face tracking STOPPED");
            CRobotUtil.wait(500);
            
            // Return to neutral first
            motion.play(init, 500);
            CRobotUtil.wait(600);
            
            CRobotPose armPose3 = new CRobotPose();
            armPose3.SetPose(
                new Byte[]  {L_SH, R_SH, L_EL, R_EL},
                new Short[] {-400, 400, -200, 200}
            );
            ok = motion.play(armPose3, 600);
            System.out.println("play(arms after stopping face track) returned: " + ok);
            CRobotUtil.wait(700);
            
            readAllPositions(motion, "After arm move (face tracking OFF)");
            
            // ============================================================
            // PHASE 4: Restart face tracking + try rapid arm commands
            // ============================================================
            System.out.println("\n--- PHASE 4: Face tracking ON + rapid arm commands ---");
            camera.StartFaceTraking();
            System.out.println("Face tracking RE-STARTED");
            CRobotUtil.wait(1000);
            
            for (int i = 0; i < 5; i++) {
                short lsh = (short)(-300 - i * 80);
                short rsh = (short)(300 + i * 80);
                CRobotPose rapid = new CRobotPose();
                rapid.SetPose(
                    new Byte[]  {L_SH, R_SH, L_EL, R_EL, BODY},
                    new Short[] {lsh, rsh, (short)(-100 - i*30), (short)(100 + i*30), (short)(i * 60 - 120)}
                );
                ok = motion.play(rapid, 300);
                System.out.println("Rapid arm #" + i + " (L_SH=" + lsh + " R_SH=" + rsh + ") returned: " + ok);
                CRobotUtil.wait(350);
            }
            
            readAllPositions(motion, "After rapid arms (face tracking ON)");
            
            // Cleanup
            camera.StopFaceTraking();
            camera.closeCapture();
            
            // Return to neutral
            motion.play(init, 500);
            CRobotUtil.wait(600);
            
            System.out.println("\n=== TEST COMPLETE ===");
        } else {
            System.out.println("Failed to connect to robot!");
        }
    }
    
    static void readAllPositions(CSotaMotion motion, String label) {
        Short[] pos = motion.getReadpos();
        if (pos != null && pos.length >= 8) {
            System.out.println("  [" + label + "] BODY=" + pos[0] 
                + " L_SH=" + pos[1] + " L_EL=" + pos[2] 
                + " R_SH=" + pos[3] + " R_EL=" + pos[4]
                + " H_Y=" + pos[5] + " H_P=" + pos[6] + " H_R=" + pos[7]);
        } else {
            System.out.println("  [" + label + "] getReadpos returned null or short array");
        }
    }
}
