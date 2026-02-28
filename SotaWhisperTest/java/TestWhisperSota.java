/**
 * TestWhisperSota.java — Standalone test for WhisperSTT on Sota robot.
 *
 * No Vstone SDK dependencies — pure Java 1.8.
 * Tests: server health check + WAV transcription.
 *
 * Usage:
 *   javac -source 1.8 -target 1.8 WhisperSTT.java TestWhisperSota.java
 *   java -cp . TestWhisperSota <server_ip> <wav_file_path>
 *
 * Examples:
 *   java -cp . TestWhisperSota localhost ../audio/test_english.wav
 *   java -cp . TestWhisperSota 192.168.11.5 test_english.wav
 *   java -cp . TestWhisperSota 192.168.11.5 /tmp/recorded.wav
 */

import java.io.File;

public class TestWhisperSota {

    public static void main(String[] args) {
        System.out.println("============================================================");
        System.out.println("  Sota Whisper STT — Java Integration Test");
        System.out.println("============================================================");
        System.out.println();

        // Parse arguments
        if (args.length < 2) {
            System.out.println("Usage: java TestWhisperSota <server_ip> <wav_file_path>");
            System.out.println();
            System.out.println("Arguments:");
            System.out.println("  server_ip     IP address of the Whisper server (laptop)");
            System.out.println("  wav_file_path  Path to a WAV audio file to transcribe");
            System.out.println();
            System.out.println("Examples:");
            System.out.println("  java TestWhisperSota localhost ../audio/test_english.wav");
            System.out.println("  java TestWhisperSota 192.168.11.5 test_english.wav");
            System.exit(1);
        }

        String serverIp = args[0];
        String wavPath  = args[1];
        int port        = 5050;

        // Allow optional port as 3rd argument
        if (args.length >= 3) {
            try {
                port = Integer.parseInt(args[2]);
            } catch (NumberFormatException e) {
                System.out.println("[WARN] Invalid port '" + args[2] + "', using default 5050");
                port = 5050;
            }
        }

        System.out.println("  Server  : http://" + serverIp + ":" + port);
        System.out.println("  WAV file: " + wavPath);

        // Check WAV file exists
        File wavFile = new File(wavPath);
        if (!wavFile.exists()) {
            System.out.println("  [ERROR] WAV file not found: " + wavPath);
            System.out.println("          Make sure the file path is correct.");
            System.exit(1);
        }
        System.out.println("  Size    : " + (wavFile.length() / 1024) + " KB");
        System.out.println();

        // Create client
        WhisperSTT stt = new WhisperSTT(serverIp, port);

        // ---- Test 1: Health check ----
        System.out.println("------------------------------------------------------------");
        System.out.println("[Test 1] Server health check...");
        boolean alive = stt.isServerAlive();
        System.out.println();

        if (!alive) {
            System.out.println("  [FAIL] Cannot reach Whisper server!");
            System.out.println();
            System.out.println("  Troubleshooting:");
            System.out.println("    1. Is start_server.bat running on the laptop?");
            System.out.println("    2. Is the laptop IP correct? (ipconfig on laptop)");
            System.out.println("    3. Is port " + port + " open in Windows Firewall?");
            System.out.println("       netsh advfirewall firewall add rule name=\"SotaWhisper\" dir=in action=allow protocol=TCP localport=" + port);
            System.out.println("    4. Are both devices on the same WiFi network?");
            System.out.println();
            System.out.println("[ABORT] Fix server connectivity before proceeding.");
            System.exit(1);
        }

        System.out.println("  [PASS] Server is alive and responding.");
        System.out.println();

        // ---- Test 2: Transcription ----
        System.out.println("------------------------------------------------------------");
        System.out.println("[Test 2] Transcribing: " + wavPath);
        System.out.println("         (this may take a few seconds)...");
        System.out.println();

        long t0 = System.currentTimeMillis();
        WhisperSTT.WhisperResult result = stt.transcribe(wavPath);
        long totalMs = System.currentTimeMillis() - t0;

        System.out.println();
        System.out.println("  --- Result ---");
        System.out.println("  OK           : " + result.ok);
        System.out.println("  Language     : " + result.language);
        System.out.println("  Text         : " + result.text);
        System.out.println("  Server time  : " + result.processingMs + " ms");
        System.out.println("  Total time   : " + totalMs + " ms (incl. network)");
        System.out.println();

        if (result.ok && result.text.length() > 0) {
            System.out.println("  [PASS] Transcription succeeded!");
        } else {
            System.out.println("  [FAIL] Transcription returned empty or error.");
            if (!result.ok) {
                System.out.println("         Server returned ok=false. Check server logs.");
            }
        }

        // ---- Summary ----
        System.out.println();
        System.out.println("============================================================");
        if (alive && result.ok && result.text.length() > 0) {
            System.out.println("  ALL TESTS PASSED!");
            System.out.println("  WhisperSTT is ready for integration.");
        } else {
            System.out.println("  SOME TESTS FAILED. Check output above.");
        }
        System.out.println("============================================================");
    }
}
