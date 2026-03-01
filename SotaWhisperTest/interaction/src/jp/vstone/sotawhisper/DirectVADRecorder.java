package jp.vstone.sotawhisper;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.TargetDataLine;

/**
 * Direct audio recording with real-time RMS-based VAD.
 *
 * Bypasses CRecordMic entirely — opens the USB audio device via
 * javax.sound.sampled, reads raw PCM, computes RMS for voice activity
 * detection, and writes a WAV file.
 *
 * This guarantees VAD works on Edison (no reflection, no SDK methods).
 *
 * Audio format: 16000 Hz, 16-bit, mono, little-endian (Whisper-friendly).
 * Java 1.8 compatible (no lambda, no var).
 */
public class DirectVADRecorder {

    private static final String TAG = "DirectVAD";

    // Audio format: 16kHz 16-bit mono LE (optimal for Whisper)
    private static final float SAMPLE_RATE   = 16000.0f;
    private static final int   SAMPLE_SIZE   = 16;
    private static final int   CHANNELS      = 1;
    private static final boolean SIGNED      = true;
    private static final boolean BIG_ENDIAN  = false;

    // VAD parameters
    private static final int DEFAULT_SILENCE_THRESHOLD = 150;
    private static final int DEFAULT_SILENCE_HOLD_MS   = 2500;
    private static final int DEFAULT_MAX_RECORDING_MS  = 15000;
    private static final int POLL_INTERVAL_MS          = 100;

    // Minimum speech duration to consider valid (avoid false triggers)
    private static final int MIN_SPEECH_MS = 500;

    private int silenceThreshold  = DEFAULT_SILENCE_THRESHOLD;
    private int silenceHoldMs     = DEFAULT_SILENCE_HOLD_MS;

    private volatile boolean stopRequested = false;

    // Listener for external monitoring
    private VadUpdateListener vadUpdateListener = null;

    // ----------------------------------------------------------------
    // Listener interface
    // ----------------------------------------------------------------

    /** Callback for real-time VAD updates. */
    public interface VadUpdateListener {
        /**
         * Called every poll cycle during recording.
         * @param rmsLevel     Current RMS audio level (0-32767)
         * @param vadWorking   Always true for DirectVADRecorder
         * @param isRecording  True while recording
         * @param elapsedMs    Milliseconds since recording started
         * @param isSpeech     True if rmsLevel >= threshold
         */
        void onVadUpdate(int rmsLevel, boolean vadWorking, boolean isRecording,
                         long elapsedMs, boolean isSpeech);
    }

    // ----------------------------------------------------------------
    // Result
    // ----------------------------------------------------------------

    public static class RecordingResult {
        public final boolean success;
        public final String filePath;
        public final double durationSec;
        public final int peakRms;
        public final int vadEvents;
        public final String message;

        public RecordingResult(boolean success, String filePath, double durationSec,
                               int peakRms, int vadEvents, String message) {
            this.success     = success;
            this.filePath    = filePath;
            this.durationSec = durationSec;
            this.peakRms     = peakRms;
            this.vadEvents   = vadEvents;
            this.message     = message;
        }
    }

    // ----------------------------------------------------------------
    // Configuration
    // ----------------------------------------------------------------

    public void setSilenceThreshold(int threshold) {
        this.silenceThreshold = threshold;
    }

    public void setSilenceHoldMs(int ms) {
        this.silenceHoldMs = ms;
    }

    public void setVadUpdateListener(VadUpdateListener listener) {
        this.vadUpdateListener = listener;
    }

    public void requestStop() {
        this.stopRequested = true;
    }

    // ----------------------------------------------------------------
    // Record with VAD
    // ----------------------------------------------------------------

    /**
     * Record audio with real-time RMS-based VAD. Blocks until done.
     *
     * @param outputPath  Path to write WAV file
     * @param maxMs       Maximum recording duration in milliseconds
     * @return RecordingResult with success/failure info
     */
    public RecordingResult record(String outputPath, int maxMs) {
        if (maxMs <= 0) maxMs = DEFAULT_MAX_RECORDING_MS;
        stopRequested = false;

        AudioFormat format = new AudioFormat(
            SAMPLE_RATE, SAMPLE_SIZE, CHANNELS, SIGNED, BIG_ENDIAN);

        TargetDataLine line = null;
        try {
            line = findUSBAudioLine(format);
            if (line == null) {
                log("WARN: USB audio not found, trying default line");
                DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);
                line = (TargetDataLine) AudioSystem.getLine(info);
            }
            line.open(format);
            line.start();
            log("Recording started (format: " + format + ")");
        } catch (Exception e) {
            log("ERROR: Cannot open audio line: " + e.getMessage());
            notifyListener(-1, false, false, 0, false);
            return new RecordingResult(false, outputPath, 0, 0, 0,
                "Cannot open audio: " + e.getMessage());
        }

        // Buffer: poll every POLL_INTERVAL_MS
        int bytesPerMs = (int)(SAMPLE_RATE * CHANNELS * (SAMPLE_SIZE / 8) / 1000.0);
        int bufferSize = bytesPerMs * POLL_INTERVAL_MS;
        byte[] buffer = new byte[bufferSize];

        ByteArrayOutputStream audioData = new ByteArrayOutputStream();
        long startedAt = System.currentTimeMillis();
        long silenceSince = -1L;
        long speechStart = -1L;
        int peakRms = 0;
        int vadEvents = 0;
        boolean earlyStopped = false;

        try {
            while (!stopRequested) {
                long elapsed = System.currentTimeMillis() - startedAt;
                if (elapsed >= maxMs) {
                    log("[REC] Max duration reached (" + maxMs + "ms)");
                    break;
                }

                int bytesRead = line.read(buffer, 0, buffer.length);
                if (bytesRead <= 0) {
                    try { Thread.sleep(10); } catch (InterruptedException ie) { break; }
                    continue;
                }

                // Save raw PCM
                audioData.write(buffer, 0, bytesRead);

                // Compute RMS from 16-bit LE PCM
                int rms = computeRMS(buffer, bytesRead);
                if (rms > peakRms) peakRms = rms;

                boolean isSpeech = rms >= silenceThreshold;

                if (isSpeech) {
                    vadEvents++;
                    silenceSince = -1L;
                    if (speechStart < 0) speechStart = System.currentTimeMillis();
                } else {
                    if (silenceSince < 0) {
                        silenceSince = System.currentTimeMillis();
                    }
                    // Only early-stop if we've had some speech first
                    long speechDuration = (speechStart > 0)
                        ? (System.currentTimeMillis() - speechStart) : 0;
                    if (speechDuration >= MIN_SPEECH_MS
                            && (System.currentTimeMillis() - silenceSince) >= silenceHoldMs) {
                        log("[REC] Silence detected after speech, stopping early (rms="
                            + rms + ", threshold=" + silenceThreshold + ")");
                        earlyStopped = true;
                        notifyListener(rms, true, true, elapsed, false);
                        break;
                    }
                }

                // Notify listener
                notifyListener(rms, true, true, elapsed, isSpeech);
            }
        } finally {
            line.stop();
            line.close();
        }

        double duration = (System.currentTimeMillis() - startedAt) / 1000.0;

        // Write WAV file
        boolean writeOk = writeWAV(outputPath, audioData.toByteArray(), format);
        if (!writeOk) {
            log("ERROR: Failed to write WAV: " + outputPath);
            notifyListener(-1, true, false, 0, false);
            return new RecordingResult(false, outputPath, duration, peakRms, vadEvents,
                "WAV write failed");
        }

        File f = new File(outputPath);
        String msg = earlyStopped ? "VAD early-stop" : "Full duration";
        log("[REC] Done: " + String.format("%.1f", duration) + "s, "
            + f.length() / 1024 + "KB, peak=" + peakRms
            + ", vadEvents=" + vadEvents + ", " + msg);

        // Notify: recording stopped
        notifyListener(0, true, false, 0, false);

        return new RecordingResult(true, outputPath, duration, peakRms, vadEvents, msg);
    }

    /** Record with default max duration. */
    public RecordingResult record(String outputPath) {
        return record(outputPath, DEFAULT_MAX_RECORDING_MS);
    }

    // ----------------------------------------------------------------
    // Find USB Audio device
    // ----------------------------------------------------------------

    private TargetDataLine findUSBAudioLine(AudioFormat format) {
        Mixer.Info[] mixers = AudioSystem.getMixerInfo();
        for (int i = 0; i < mixers.length; i++) {
            String name = mixers[i].getName().toLowerCase();
            String desc = mixers[i].getDescription().toLowerCase();
            // Match "USB audio CODEC" as seen in CRecordMic logs
            if (name.contains("usb audio") || desc.contains("usb audio")
                    || name.contains("usb") && name.contains("audio")) {
                try {
                    Mixer mixer = AudioSystem.getMixer(mixers[i]);
                    DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);
                    if (mixer.isLineSupported(info)) {
                        log("Found USB audio device: " + mixers[i].getName());
                        return (TargetDataLine) mixer.getLine(info);
                    }
                } catch (Exception e) {
                    // try next
                }
            }
        }
        return null;
    }

    // ----------------------------------------------------------------
    // RMS computation from 16-bit LE PCM
    // ----------------------------------------------------------------

    private int computeRMS(byte[] buffer, int length) {
        long sumSquares = 0;
        int samples = 0;

        // 16-bit LE: byte[i] = low, byte[i+1] = high
        for (int i = 0; i + 1 < length; i += 2) {
            int low  = buffer[i] & 0xFF;
            int high = buffer[i + 1];  // signed
            short sample = (short)((high << 8) | low);
            sumSquares += (long) sample * sample;
            samples++;
        }

        if (samples == 0) return 0;
        return (int) Math.sqrt(sumSquares / samples);
    }

    // ----------------------------------------------------------------
    // WAV file writer (PCM 16-bit)
    // ----------------------------------------------------------------

    private boolean writeWAV(String path, byte[] pcmData, AudioFormat format) {
        try {
            FileOutputStream fos = new FileOutputStream(path);
            try {
                int dataLen = pcmData.length;
                int sampleRate = (int) format.getSampleRate();
                int bitsPerSample = format.getSampleSizeInBits();
                int numChannels = format.getChannels();
                int byteRate = sampleRate * numChannels * bitsPerSample / 8;
                int blockAlign = numChannels * bitsPerSample / 8;

                // RIFF header
                fos.write(new byte[]{'R','I','F','F'});
                writeIntLE(fos, 36 + dataLen);
                fos.write(new byte[]{'W','A','V','E'});

                // fmt chunk
                fos.write(new byte[]{'f','m','t',' '});
                writeIntLE(fos, 16);            // chunk size
                writeShortLE(fos, (short) 1);   // PCM format
                writeShortLE(fos, (short) numChannels);
                writeIntLE(fos, sampleRate);
                writeIntLE(fos, byteRate);
                writeShortLE(fos, (short) blockAlign);
                writeShortLE(fos, (short) bitsPerSample);

                // data chunk
                fos.write(new byte[]{'d','a','t','a'});
                writeIntLE(fos, dataLen);
                fos.write(pcmData);

                return true;
            } finally {
                fos.close();
            }
        } catch (IOException e) {
            log("ERROR: WAV write failed: " + e.getMessage());
            return false;
        }
    }

    private void writeIntLE(FileOutputStream fos, int val) throws IOException {
        fos.write(val & 0xFF);
        fos.write((val >> 8) & 0xFF);
        fos.write((val >> 16) & 0xFF);
        fos.write((val >> 24) & 0xFF);
    }

    private void writeShortLE(FileOutputStream fos, short val) throws IOException {
        fos.write(val & 0xFF);
        fos.write((val >> 8) & 0xFF);
    }

    // ----------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------

    private void notifyListener(int level, boolean vadWorking, boolean isRecording,
                                long elapsedMs, boolean isSpeech) {
        VadUpdateListener l = vadUpdateListener;
        if (l != null) {
            l.onVadUpdate(level, vadWorking, isRecording, elapsedMs, isSpeech);
        }
    }

    private void log(String msg) {
        System.out.println("[" + TAG + "] " + msg);
    }
}
