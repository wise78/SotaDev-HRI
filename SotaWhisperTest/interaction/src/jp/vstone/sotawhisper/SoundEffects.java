package jp.vstone.sotawhisper;

import jp.vstone.RobotLib.CPlayWave;

/**
 * Centralized sound effect manager for interaction state transitions.
 * Plays short WAV files non-blocking so the FSM is not delayed.
 *
 * Sound files are in ./sound/ relative to working directory.
 * Java 1.8, no external dependencies.
 */
public class SoundEffects {

    private static final String TAG = "SoundFX";
    private static final String SOUND_DIR = "./sound/";

    // --- Sound effect file mappings ---
    public static final String SFX_FACE_DETECTED = SOUND_DIR + "face_ok.wav";
    public static final String SFX_LISTENING      = SOUND_DIR + "cursor10.wav";
    public static final String SFX_THINKING       = SOUND_DIR + "ok.wav";
    public static final String SFX_CLOSING        = SOUND_DIR + "end_test.wav";
    public static final String SFX_ERROR          = SOUND_DIR + "error.wav";

    /**
     * Play a sound effect non-blocking (returns immediately).
     * @param filePath  Path to WAV file
     */
    public static void play(final String filePath) {
        try {
            CPlayWave.PlayWave(filePath, false);
        } catch (Exception e) {
            System.out.println("[" + TAG + "] WARN: Cannot play " + filePath
                + ": " + e.getMessage());
        }
    }

    /**
     * Play a sound effect blocking (waits until finished).
     * @param filePath  Path to WAV file
     */
    public static void playBlocking(final String filePath) {
        try {
            CPlayWave.PlayWave(filePath, true);
        } catch (Exception e) {
            System.out.println("[" + TAG + "] WARN: Cannot play " + filePath
                + ": " + e.getMessage());
        }
    }
}
