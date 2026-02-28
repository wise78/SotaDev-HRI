package jp.vstone.sotawhisper;

import java.io.File;
import jp.vstone.RobotLib.CPlayWave;
import jp.vstone.RobotLib.CSotaMotion;
import jp.vstone.sotatalk.TextToSpeechSota;

/**
 * Speech manager using DirectVADRecorder + WhisperSTT (replaces SpeechRecog).
 *
 * Recording: DirectVADRecorder with real-time RMS-based Voice Activity Detection.
 *            Bypasses CRecordMic (whose audio level methods don't work on Edison)
 *            and uses javax.sound.sampled directly for guaranteed VAD.
 * STT: WhisperSTT HTTP client -> laptop GPU.
 * TTS: TextToSpeechSota (Japanese + English only).
 *
 * Java 1.8, no lambda, no var.
 */
public class WhisperSpeechManager {

    private static final String TAG = "WhisperSpeech";

    private final CSotaMotion motion;
    private final WhisperSTT whisperSTT;
    private final DirectVADRecorder vadRecorder;

    private static final int MAX_RECORDING_MS = 15000;
    private static final String TEMP_WAV      = "/tmp/whisper_input.wav";

    // TTS failure counter
    private int ttsFailureCount = 0;

    // VAD listener for external monitoring (StatusServer)
    private VadListener vadListener = null;

    // ----------------------------------------------------------------
    // VAD Listener interface (compatible with StatusServer)
    // ----------------------------------------------------------------

    /** Callback interface for VAD diagnostics (used by StatusServer). */
    public interface VadListener {
        /**
         * Called every poll cycle during recording.
         * @param level        Audio RMS level (0-32767, always real with DirectVAD)
         * @param vadWorking   True if VAD is functional
         * @param isRecording  True if currently recording
         * @param elapsedMs    Milliseconds since recording started
         * @param isSpeech     True if level >= threshold
         */
        void onVadUpdate(int level, boolean vadWorking, boolean isRecording,
                         long elapsedMs, boolean isSpeech);
    }

    // ----------------------------------------------------------------
    // Constructor
    // ----------------------------------------------------------------

    public WhisperSpeechManager(CSotaMotion motion, String whisperServerIp, int whisperPort) {
        this.motion      = motion;
        this.whisperSTT  = new WhisperSTT(whisperServerIp, whisperPort);
        this.vadRecorder = new DirectVADRecorder();
        log("Initialized with Whisper at " + whisperServerIp + ":" + whisperPort);
        log("Using DirectVADRecorder (javax.sound.sampled, RMS-based VAD)");
    }

    /** Set VAD listener for external monitoring. */
    public void setVadListener(VadListener listener) {
        this.vadListener = listener;
        // Bridge: forward DirectVADRecorder updates to our VadListener
        if (listener != null) {
            vadRecorder.setVadUpdateListener(new DirectVADRecorder.VadUpdateListener() {
                public void onVadUpdate(int rmsLevel, boolean vadWorking, boolean isRecording,
                                        long elapsedMs, boolean isSpeech) {
                    VadListener l = vadListener;
                    if (l != null) {
                        l.onVadUpdate(rmsLevel, vadWorking, isRecording, elapsedMs, isSpeech);
                    }
                }
            });
        } else {
            vadRecorder.setVadUpdateListener(null);
        }
    }

    // ----------------------------------------------------------------
    // TTS — speak text (Japanese or English only)
    // ----------------------------------------------------------------

    /**
     * Speak text using Sota TTS. Blocks until speech finishes.
     * Only supports Japanese and English.
     */
    public boolean speak(String text) {
        if (text == null || text.isEmpty()) return false;
        log("Speaking: " + text);

        // Try direct byte generation (lower latency)
        try {
            byte[] wave = TextToSpeechSota.getTTS(text);
            if (wave != null) {
                CPlayWave.PlayWave(wave, true);
                return true;
            }
        } catch (Exception e) {
            ttsFailureCount++;
            log("WARN: Direct TTS failed: " + e.getMessage());
        }

        // Fallback: file-based TTS
        try {
            String filePath = TextToSpeechSota.getTTSFile(text);
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

    // ----------------------------------------------------------------
    // Listen — DirectVADRecorder + Whisper
    // ----------------------------------------------------------------

    /**
     * Record audio with real-time RMS VAD, send to Whisper, return full result.
     * Includes original text, English translation, and detected language.
     *
     * @param maxDurationMs  Maximum recording duration
     * @return WhisperResult (check .ok for success)
     */
    public WhisperSTT.WhisperResult listen(int maxDurationMs) {
        log("Listening... (max " + maxDurationMs + "ms)");

        DirectVADRecorder.RecordingResult rec = vadRecorder.record(TEMP_WAV, maxDurationMs);

        // Notify listener: recording stopped
        if (vadListener != null) {
            vadListener.onVadUpdate(0, true, false, 0, false);
        }

        if (!rec.success) {
            log("Recording failed: " + rec.message);
            return new WhisperSTT.WhisperResult("", "", "", false, 0);
        }

        // Check file has content
        File wavFile = new File(rec.filePath);
        if (!wavFile.exists() || wavFile.length() < 1000) {
            log("Recording too short or empty (" + wavFile.length() + " bytes)");
            return new WhisperSTT.WhisperResult("", "", "", false, 0);
        }

        log("Transcribing " + wavFile.length() / 1024 + " KB (peak RMS=" + rec.peakRms
            + ", vadEvents=" + rec.vadEvents + ")...");
        WhisperSTT.WhisperResult result = whisperSTT.transcribe(rec.filePath, true);

        if (result.ok && result.text.length() > 0) {
            log("Heard [" + result.language + "]: " + result.text);
            if (!result.textEn.equals(result.text)) {
                log("English: " + result.textEn);
            }
        } else {
            log("Nothing recognized");
        }

        return result;
    }

    /** Listen with default max duration. */
    public WhisperSTT.WhisperResult listen() {
        return listen(MAX_RECORDING_MS);
    }

    /**
     * Listen and check for yes/no in English translation.
     * @return "yes", "no", or null
     */
    public String listenForYesNo(int timeoutMs) {
        WhisperSTT.WhisperResult result = listen(timeoutMs);
        if (!result.ok || result.textEn.isEmpty()) return null;

        String lower = result.textEn.toLowerCase().trim();

        String[] yesPatterns = {
            "yes", "yeah", "yep", "sure", "ok", "okay", "right", "correct",
            "of course", "absolutely"
        };
        String[] noPatterns = {
            "no", "nope", "nah", "not", "wrong", "never"
        };

        for (int i = 0; i < yesPatterns.length; i++) {
            if (lower.contains(yesPatterns[i])) {
                log("YesNo: YES (matched '" + yesPatterns[i] + "')");
                return "yes";
            }
        }
        for (int i = 0; i < noPatterns.length; i++) {
            if (lower.contains(noPatterns[i])) {
                log("YesNo: NO (matched '" + noPatterns[i] + "')");
                return "no";
            }
        }

        log("YesNo: unrecognized: " + result.textEn);
        return null;
    }

    /**
     * Listen and extract a name from the English translation.
     * @return extracted name or null
     */
    public String listenForName(int timeoutMs) {
        WhisperSTT.WhisperResult result = listen(timeoutMs);
        if (!result.ok || result.textEn.isEmpty()) return null;

        String cleaned = result.textEn.trim();

        // Remove common prefixes
        String[] prefixes = {
            "my name is ", "i'm ", "i am ", "it's ", "call me ",
            "this is ", "the name is "
        };
        String lower = cleaned.toLowerCase();
        for (int i = 0; i < prefixes.length; i++) {
            if (lower.startsWith(prefixes[i])) {
                cleaned = cleaned.substring(prefixes[i].length()).trim();
                break;
            }
        }

        // Remove trailing punctuation
        if (cleaned.endsWith(".") || cleaned.endsWith(",") || cleaned.endsWith("!")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1).trim();
        }

        log("Name extracted: " + cleaned);
        return cleaned.isEmpty() ? null : cleaned;
    }

    /** Check if Whisper server is reachable. */
    public boolean isWhisperAlive() {
        return whisperSTT.isServerAlive();
    }

    public int getTtsFailureCount() {
        return ttsFailureCount;
    }

    // ----------------------------------------------------------------
    // Play pre-recorded sound
    // ----------------------------------------------------------------

    public void playSound(String filePath) {
        try {
            CPlayWave.PlayWave(filePath, true);
        } catch (Exception e) {
            log("WARN: Cannot play: " + filePath);
        }
    }

    // ----------------------------------------------------------------
    // Logging
    // ----------------------------------------------------------------

    private void log(String msg) {
        System.out.println("[" + TAG + "] " + msg);
    }
}
