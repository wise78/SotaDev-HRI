package jp.vstone.sotatest;

import java.awt.Color;
import jp.vstone.RobotLib.*;

/**
 * Test whether LED-only play() calls interfere with servo interpolation.
 * 
 * Test A: Move arm without LED interruption → should work
 * Test B: Move arm WITH LED thread calling play() every 100ms → might fail
 */
public class LEDInterferenceTest {
    
    static volatile boolean ledRunning = false;
    static CSotaMotion motion;
    
    public static void main(String[] args) throws Exception {
        System.out.println("[LEDTest] Starting...");

        CRobotMem mem = new CRobotMem();
        if (!mem.Connect()) {
            System.out.println("[LEDTest] FATAL: Cannot connect!");
            System.exit(1);
        }
        motion = new CSotaMotion(mem);
        motion.InitRobot_Sota();
        motion.ServoOn();
        CRobotUtil.wait(500);

        // Go to natural pose first
        System.out.println("\n[LEDTest] Setting natural pose...");
        CRobotPose natural = new CRobotPose();
        natural.SetPose(
            new Byte[]  {1, 2, 3, 4, 5, 6, 7, 8},
            new Short[] {0, -900, 0, 900, 0, 0, 0, 0}
        );
        motion.play(natural, 1000);
        CRobotUtil.wait(1500);
        printPositions("After natural pose");

        // ============================================================
        // TEST A: Move L_SHOULDER WITHOUT LED thread
        // ============================================================
        System.out.println("\n========================================");
        System.out.println("[LEDTest] TEST A: Arm move WITHOUT LED thread");
        System.out.println("========================================");
        
        CRobotPose armPose = new CRobotPose();
        armPose.SetPose(
            new Byte[]  {2, 4},         // L_SHOULDER, R_SHOULDER
            new Short[] {-300, 300}     // Raise both arms
        );
        motion.play(armPose, 800);
        CRobotUtil.wait(1000);
        printPositions("After arm raise (no LED)");
        
        // Return to natural
        motion.play(natural, 800);
        CRobotUtil.wait(1200);
        printPositions("Back to natural");

        // ============================================================
        // TEST B: Move L_SHOULDER WITH LED thread firing every 100ms
        // ============================================================
        System.out.println("\n========================================");
        System.out.println("[LEDTest] TEST B: Arm move WITH LED thread (100ms interval)");
        System.out.println("========================================");
        
        // Start LED thread
        ledRunning = true;
        Thread ledThread = new Thread(new Runnable() {
            public void run() {
                int count = 0;
                while (ledRunning) {
                    try {
                        CRobotPose ledPose = new CRobotPose();
                        ledPose.setLED_Sota(Color.GREEN, Color.GREEN, 255, Color.GREEN);
                        motion.play(ledPose, 100);
                        count++;
                        CRobotUtil.wait(120);
                    } catch (Exception e) {
                        System.out.println("[LED] Error: " + e.getMessage());
                    }
                }
                System.out.println("[LED] Thread stopped after " + count + " LED plays");
            }
        }, "LEDThread");
        ledThread.setDaemon(true);
        ledThread.start();
        
        // Give LED thread time to start
        CRobotUtil.wait(300);
        
        // Now try to move arms
        CRobotPose armPose2 = new CRobotPose();
        armPose2.SetPose(
            new Byte[]  {2, 4},         // L_SHOULDER, R_SHOULDER  
            new Short[] {-300, 300}     // Raise both arms
        );
        System.out.println("[LEDTest] Sending arm command...");
        motion.play(armPose2, 800);
        CRobotUtil.wait(1000);
        printPositions("After arm raise WITH LED thread");
        
        // Stop LED thread
        ledRunning = false;
        CRobotUtil.wait(300);

        // ============================================================
        // TEST C: Move arms using COMBINED pose (servo + LED together)
        // ============================================================
        System.out.println("\n========================================");
        System.out.println("[LEDTest] TEST C: Arm move with COMBINED servo+LED pose");
        System.out.println("========================================");
        
        // Return to natural first
        motion.play(natural, 800);
        CRobotUtil.wait(1200);
        
        CRobotPose combined = new CRobotPose();
        combined.SetPose(
            new Byte[]  {2, 4},
            new Short[] {-300, 300}
        );
        combined.setLED_Sota(Color.CYAN, Color.CYAN, 255, Color.CYAN);
        motion.play(combined, 800);
        CRobotUtil.wait(1000);
        printPositions("After COMBINED arm+LED pose");

        // ============================================================
        // TEST D: Move arms WITH LED thread, but LED uses LockLEDHandle
        // ============================================================
        System.out.println("\n========================================");
        System.out.println("[LEDTest] TEST D: Arm move, LED managed separately");
        System.out.println("========================================");
        
        // Return to natural
        motion.play(natural, 800);
        CRobotUtil.wait(1200);

        // Start LED thread that just sleeps (no play calls)
        ledRunning = true;
        Thread ledThread2 = new Thread(new Runnable() {
            public void run() {
                int count = 0;
                while (ledRunning) {
                    try {
                        // No LED play — just wait
                        count++;
                        CRobotUtil.wait(120);
                    } catch (Exception e) {
                        System.out.println("[LED2] Error: " + e.getMessage());
                    }
                }
                System.out.println("[LED2] Thread stopped after " + count + " cycles");
            }
        }, "LEDThread2");
        ledThread2.setDaemon(true);
        ledThread2.start();
        CRobotUtil.wait(300);
        
        CRobotPose armPose3 = new CRobotPose();
        armPose3.SetPose(
            new Byte[]  {2, 4},
            new Short[] {-300, 300}
        );
        System.out.println("[LEDTest] Sending arm command with LED2 thread...");
        motion.play(armPose3, 800);
        CRobotUtil.wait(1000);
        printPositions("After arm raise with LED2 thread");
        
        ledRunning = false;
        CRobotUtil.wait(300);

        // Return to natural and finish
        motion.play(natural, 1000);
        CRobotUtil.wait(1500);

        System.out.println("\n[LEDTest] === ALL TESTS COMPLETE ===");
        motion.ServoOff();
    }
    
    static void printPositions(String label) {
        String[] names = {"BODY_Y", "L_SHOULDER", "L_ELBOW", "R_SHOULDER", "R_ELBOW",
                          "HEAD_Y", "HEAD_P", "HEAD_R"};
        Short[] pos = motion.getReadpos();
        System.out.println("  --- " + label + " ---");
        if (pos == null) { System.out.println("  null!"); return; }
        StringBuilder sb = new StringBuilder("  ");
        for (int i = 0; i < pos.length && i < names.length; i++) {
            sb.append(names[i]).append("=").append(pos[i]).append("  ");
        }
        System.out.println(sb.toString());
    }
}
