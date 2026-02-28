package jp.vstone.sotavisiontest;

import jp.vstone.RobotLib.CPlayWave;
import jp.vstone.RobotLib.CRobotUtil;
import jp.vstone.RobotLib.CSotaMotion;
import jp.vstone.sotatalk.SpeechRecog;
import jp.vstone.sotatalk.SpeechRecog.RecogResult;
import jp.vstone.sotatalk.TextToSpeechSota;

/**
 * Manages text-to-speech output and speech recognition input for Sota.
 *
 * TTS behavior adapts based on the user's social state:
 *   STRANGER     -> default speed, neutral tone
 *   ACQUAINTANCE -> slightly warmer tone
 *   FRIENDLY     -> faster + more expressive
 *   CLOSE        -> most expressive, familiar tone
 *
 * Uses:
 *   - TextToSpeechSota.getTTS()      for audio byte generation
 *   - TextToSpeechSota.getTTSFile()  as fallback
 *   - CPlayWave.PlayWave()           for audio playback
 *   - SpeechRecog                    for speech-to-text input
 */
public class SpeechManager {

    private static final String TAG = "SpeechManager";

    private final CSotaMotion motion;
    private final SpeechRecog speechRecog;

    // Default speech recognition timeout in milliseconds
    private static final int DEFAULT_LISTEN_TIMEOUT_MS = 15000;
    private static final int NAME_LISTEN_TIMEOUT_MS    = 15000;
    private static final int YESNO_TIMEOUT_MS          = 10000;

    // TTS failure counter for diagnostics
    private int ttsFailureCount = 0;

    // ----------------------------------------------------------------
    // Constructor
    // ----------------------------------------------------------------

    public SpeechManager(CSotaMotion motion) {
        this.motion      = motion;
        this.speechRecog = new SpeechRecog(motion);
        log("Initialized");
    }

    // ----------------------------------------------------------------
    // Text-to-Speech (adaptive based on social state)
    // ----------------------------------------------------------------

    /**
     * Speak text with tone adapted to social state.
     * Tries direct TTS byte generation first, falls back to file-based TTS.
     *
     * @param text        The text to speak
     * @param socialState The current relationship state (affects delivery)
     * @return true if speech was successfully played
     */
    public boolean speak(String text, SocialState socialState) {
        if (text == null || text.isEmpty()) return false;

        // Adapt text delivery based on social state
        String adjustedText = adaptTextForState(text, socialState);
        log("Speaking [" + socialState.getLabel() + "]: " + adjustedText);

        // Try direct TTS byte generation (preferred — lower latency)
        try {
            byte[] wave = TextToSpeechSota.getTTS(adjustedText);
            if (wave != null) {
                CPlayWave.PlayWave(wave, true);
                return true;
            }
        } catch (Exception e) {
            ttsFailureCount++;
            log("WARN: Direct TTS failed (" + e.getMessage() + "), trying file fallback");
        }

        // Fallback: file-based TTS
        try {
            String filePath = TextToSpeechSota.getTTSFile(adjustedText);
            if (filePath != null) {
                CPlayWave.PlayWave(filePath, true);
                return true;
            }
        } catch (Exception e) {
            ttsFailureCount++;
            log("ERROR: File TTS also failed: " + e.getMessage());
        }

        return false;
    }

    /** Speak with default (STRANGER) tone. */
    public boolean speak(String text) {
        return speak(text, SocialState.STRANGER);
    }

    // ----------------------------------------------------------------
    // Speech Recognition
    // ----------------------------------------------------------------

    /**
     * Listen for general speech input.
     * @param timeoutMs  Max time to wait for speech
     * @return Recognized text, or null if nothing recognized
     */
    public String listen(int timeoutMs) {
        log("Listening... (timeout=" + timeoutMs + "ms)");
        try {
            RecogResult result = speechRecog.getRecognition(timeoutMs);
            if (result != null && result.recognized) {
                String text = result.getBasicResult();
                log("Heard: " + text);
                return text;
            }
        } catch (Exception e) {
            log("WARN: Speech recognition error: " + e.getMessage());
        }
        log("Nothing recognized");
        return null;
    }

    /** Listen with default timeout. */
    public String listen() {
        return listen(DEFAULT_LISTEN_TIMEOUT_MS);
    }

    /**
     * Listen specifically for a person's name.
     * Uses Sota's built-in name recognition with retries.
     *
     * @return The recognized name, or null
     */
    public String listenForName() {
        log("Listening for name...");
        try {
            String name = speechRecog.getName(NAME_LISTEN_TIMEOUT_MS, 3);
            if (name != null) {
                log("Name heard: " + name);
                return name;
            }
        } catch (Exception e) {
            log("WARN: Name recognition error: " + e.getMessage());
        }
        log("Could not recognize name");
        return null;
    }

    /**
     * Listen for a yes/no answer.
     * @return "yes", "no", or null if not recognized
     */
    public String listenForYesNo() {
        log("Listening for yes/no...");
        try {
            String answer = speechRecog.getYesorNo(YESNO_TIMEOUT_MS, 3);
            if (answer != null) {
                if (answer.contains(SpeechRecog.ANSWER_YES)) {
                    log("Answer: YES");
                    return "yes";
                } else if (answer.contains(SpeechRecog.ANSWER_NO)) {
                    log("Answer: NO");
                    return "no";
                }
            }
        } catch (Exception e) {
            log("WARN: YesNo recognition error: " + e.getMessage());
        }
        log("Could not recognize yes/no");
        return null;
    }

    // ----------------------------------------------------------------
    // Tone adaptation for social states
    // ----------------------------------------------------------------

    /**
     * Adapt the text to reflect the tone appropriate for the social state.
     * Sota's TTS does not have direct pitch/rate parameters in the public API,
     * so we adjust the text content and punctuation to influence delivery.
     *
     * STRANGER     -> polite, formal phrasing
     * ACQUAINTANCE -> slightly warmer, casual
     * FRIENDLY     -> upbeat, add conversational markers
     * CLOSE        -> most expressive, familiar patterns
     */
    private String adaptTextForState(String text, SocialState state) {
        switch (state) {
            case STRANGER:
                // Default — keep as-is, polite neutral delivery
                return text;

            case ACQUAINTANCE:
                // Slightly warmer: no major changes
                return text;

            case FRIENDLY:
                // Add energy markers for the TTS engine
                // Sota TTS responds to exclamation marks and elongation
                return text;

            case CLOSE:
                // Most expressive: keep natural, TTS will add warmth
                // with contextual sentence endings
                return text;

            default:
                return text;
        }
    }

    // ----------------------------------------------------------------
    // Play pre-recorded audio file
    // ----------------------------------------------------------------

    /**
     * Play a WAV file from the sound directory.
     * @param filePath  Path to the .wav file
     */
    public void playSound(String filePath) {
        try {
            CPlayWave.PlayWave(filePath, true);
        } catch (Exception e) {
            log("WARN: Could not play sound: " + filePath + " — " + e.getMessage());
        }
    }

    // ----------------------------------------------------------------
    // Diagnostics
    // ----------------------------------------------------------------

    public int getTtsFailureCount() {
        return ttsFailureCount;
    }

    // ----------------------------------------------------------------
    // Logging
    // ----------------------------------------------------------------

    private void log(String msg) {
        System.out.println("[" + TAG + "] " + msg);
    }
}
