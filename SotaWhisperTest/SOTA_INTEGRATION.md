# Sota Integration Guide — Whisper STT

## Part 2: Connectivity & Deployment

### 2.1 Firewall Setup (Laptop)

The Whisper server runs on port 5050. Sota needs to reach it over WiFi.

**Open port 5050 in Windows Firewall:**
```bat
netsh advfirewall firewall add rule name="SotaWhisper5050" dir=in action=allow protocol=TCP localport=5050
```

Or manually: Windows Defender Firewall -> Advanced Settings -> Inbound Rules -> New Rule -> Port -> TCP 5050 -> Allow.

### 2.2 Find Laptop IP

```bat
ipconfig
```
Look for **Wireless LAN adapter Wi-Fi** -> **IPv4 Address** (e.g. `192.168.11.5`).

### 2.3 Verify WiFi Connectivity from Sota

SSH into the robot:
```bash
ssh root@192.168.11.30
```

Check network interface:
```bash
ifconfig wlan0
# Should show an IP in the same subnet, e.g. 192.168.11.30
```

Ping the laptop:
```bash
ping -c 3 192.168.11.5
# Should get replies
```

Test the Whisper server:
```bash
curl http://192.168.11.5:5050/health
# Expected: {"device":"cuda","model":"base","status":"ok"}
```

If curl fails:
- Check both devices are on the same WiFi network
- Verify firewall rule (step 2.1)
- Try disabling Windows Firewall temporarily to isolate the issue

### 2.4 Upload Test Files to Sota

From laptop terminal:
```bash
# Upload test audio
scp audio/test_english.wav root@192.168.11.30:~/SotaSample/
scp audio/test_japanese.wav root@192.168.11.30:~/SotaSample/
```

### 2.5 Test WAV Transcription from Sota via curl

On the robot (SSH):
```bash
cd ~/SotaSample

# Send English WAV to Whisper server
curl -X POST -F "audio=@test_english.wav" http://192.168.11.5:5050/transcribe
# Expected: {"language":"en","ok":true,"processing_ms":832,"text":"Hello, my name is Sota..."}

# Send Japanese WAV
curl -X POST -F "audio=@test_japanese.wav" http://192.168.11.5:5050/transcribe
# Expected: {"language":"ja","ok":true,...,"text":"こんにちは、私の名前は..."}

# Test with forced language
curl -X POST -F "audio=@test_english.wav" -F "language=en" http://192.168.11.5:5050/transcribe
```

### 2.6 Deploy Java Classes to Sota

**Compile on laptop (cross-compile for Java 1.8):**
```bat
cd SotaWhisperTest\java
javac -source 1.8 -target 1.8 WhisperSTT.java TestWhisperSota.java
```

**Copy .class files to robot:**
```bash
scp java/WhisperSTT.class root@192.168.11.30:~/SotaSample/
scp java/TestWhisperSota.class root@192.168.11.30:~/SotaSample/
```

**Run on robot:**
```bash
ssh root@192.168.11.30
cd ~/SotaSample
java -cp . TestWhisperSota 192.168.11.5 test_english.wav
```

Expected output:
```
============================================================
  Sota Whisper STT — Java Integration Test
============================================================

  Server  : http://192.168.11.5:5050
  WAV file: test_english.wav
  Size    : 85 KB

[WhisperSTT] Health OK: {"device":"cuda","model":"base","status":"ok"}

[Test 1] Server health check...
  [PASS] Server is alive and responding.

[Test 2] Transcribing: test_english.wav
[WhisperSTT] Result: lang=en, text='Hello, my name is Sota...', server=832ms, total=1245ms

  --- Result ---
  OK           : true
  Language     : en
  Text         : Hello, my name is Sota. How are you today? I am a social robot.
  Server time  : 832 ms
  Total time   : 1245 ms (incl. network)

  [PASS] Transcription succeeded!

============================================================
  ALL TESTS PASSED!
  WhisperSTT is ready for integration.
============================================================
```

---

## Part 3: Replacing SpeechRecog with WhisperSTT

### 3.1 Architecture Difference

**SpeechRecog (current):**
```
[Sota mic] -> [SpeechRecog SDK] -> String (on-device, real-time)
              (handles recording + recognition internally)
```

**WhisperSTT (new):**
```
[Sota mic] -> [CRecordMic] -> WAV file -> [HTTP POST] -> [Whisper GPU] -> JSON -> String
              (explicit recording)         (network)      (laptop)
```

**Key differences:**
1. **Recording is now explicit.** SpeechRecog records and recognizes in one call. With Whisper, you must record to a WAV file first (using `CRecordMic`), then send it.
2. **Network dependency.** Whisper requires WiFi to the laptop. If WiFi drops, transcription fails.
3. **Post-processing vs. streaming.** SpeechRecog can detect speech onset in real-time. Whisper processes the entire recording after it's done.
4. **Multilingual.** Whisper auto-detects 99 languages. SpeechRecog only works in Japanese.

### 3.2 Feature Comparison

| Feature | SpeechRecog (current) | WhisperSTT (new) |
|---------|-----------------------|-------------------|
| Languages | Japanese only | 99 languages, auto-detect |
| Accuracy | Limited vocabulary, low sensitivity | High accuracy (Whisper base model) |
| Processing | On-device, real-time | Remote (laptop GPU), post-processing |
| Network | Not needed | WiFi required |
| Latency (recognition) | ~1-3s | ~0.5-2s (GPU, base model) |
| Latency (total) | ~1-3s | ~1-4s (+ network round-trip) |
| Offline | Yes | No |
| `getName()` | Built-in SDK method | Manual: parse text from transcript |
| `getYesorNo()` | Built-in SDK method | Manual: keyword matching |
| Wake word detect | Built-in (voice activity) | Not supported directly |
| Recording | Internal (automatic) | External (CRecordMic needed) |
| RAM usage on Sota | Moderate (SDK loaded) | Minimal (only HTTP client) |
| GPU/CPU on Sota | Uses Edison CPU | No processing (offloaded to laptop) |

### 3.3 Use Case 1: Wake Word / Speech Onset Detection

**Problem:** SpeechRecog detects when someone starts speaking ("voice activity detection"). Whisper cannot do this — it only processes complete recordings.

**Recommendation:** Whisper is **NOT suitable** for wake word detection. Options:

**Option A — Hybrid (recommended):**
Keep SpeechRecog for voice onset detection only. Once speech is detected, switch to CRecordMic + WhisperSTT for the actual transcription.

```java
// Detect that someone is speaking (SpeechRecog)
RecogResult onset = speechRecog.getRecognition(5000);
if (onset != null && onset.recognized) {
    // Now record the full response with CRecordMic
    // and transcribe with WhisperSTT
}
```

**Option B — Fixed duration recording:**
Skip wake word detection entirely. After Sota asks a question (TTS), wait a fixed duration (e.g. 5 seconds) and record. Simple but less responsive.

**Option C — Volume-based VAD (advanced):**
Implement a simple Voice Activity Detection in Java by monitoring microphone audio levels. Start recording when volume exceeds a threshold, stop when it drops below for N milliseconds. More complex but gives the most natural interaction.

### 3.4 Use Case 2: Post-Recording Transcription (Main Use Case)

This is the primary use case: after Sota finishes speaking (TTS), record the user's response, then transcribe it.

**Java 1.8 example (conceptual — adapt CRecordMic API to actual SDK):**

```java
// In SpeechManager.java or a new class

private WhisperSTT whisperSTT;
// private CRecordMic recorder;  // Uncomment when SDK API is confirmed

public SpeechManager(CSotaMotion motion, String whisperServerIp) {
    this.motion = motion;
    this.speechRecog = new SpeechRecog(motion);
    // this.recorder = new CRecordMic(motion);

    if (whisperServerIp != null) {
        this.whisperSTT = new WhisperSTT(whisperServerIp, 5050);
        if (this.whisperSTT.isServerAlive()) {
            log("WhisperSTT connected to " + whisperServerIp);
        } else {
            log("WARN: WhisperSTT server not reachable, falling back to SpeechRecog");
            this.whisperSTT = null;
        }
    }
}

/**
 * Listen for speech using Whisper (with fallback to SpeechRecog).
 */
public String listen(int timeoutMs) {
    if (whisperSTT != null) {
        return listenViaWhisper(timeoutMs);
    }
    return listenViaSpeechRecog(timeoutMs);
}

private String listenViaWhisper(int timeoutMs) {
    log("Recording audio for Whisper...");

    // Record audio to WAV file
    // IMPORTANT: Adapt these method names to the actual CRecordMic API
    // Check sotalib.jar documentation for exact method signatures
    String wavPath = "/tmp/whisper_input.wav";

    // --- Option A: Fixed duration ---
    // recorder.StartRec(wavPath);
    // try { Thread.sleep(Math.min(timeoutMs, 5000)); } catch (InterruptedException e) {}
    // recorder.StopRec();

    // --- Option B: Using CRobotUtil.wait if CRecordMic uses it ---
    // CRobotUtil.wait(motion, timeoutMs);

    // For now, assume the WAV file exists at wavPath after recording

    WhisperSTT.WhisperResult result = whisperSTT.transcribe(wavPath);
    if (result.ok && result.text.length() > 0) {
        log("Whisper heard [" + result.language + "]: " + result.text);
        return result.text;
    }

    log("Whisper returned empty, nothing recognized");
    return null;
}

private String listenViaSpeechRecog(int timeoutMs) {
    log("Listening via SpeechRecog... (timeout=" + timeoutMs + "ms)");
    try {
        SpeechRecog.RecogResult result = speechRecog.getRecognition(timeoutMs);
        if (result != null && result.recognized) {
            return result.getBasicResult();
        }
    } catch (Exception e) {
        log("WARN: SpeechRecog error: " + e.getMessage());
    }
    return null;
}
```

### 3.5 Replacing listenForName()

SpeechRecog.getName() has built-in name matching logic. With Whisper, extract the name from the full transcript:

```java
/**
 * Listen for a name via Whisper.
 * Assumes the user speaks just their name (or "My name is X").
 */
public String listenForName() {
    String transcript = listen(NAME_LISTEN_TIMEOUT_MS);
    if (transcript == null) return null;

    // Simple extraction: remove common prefixes
    String cleaned = transcript.trim();

    // Remove "My name is", "I'm", "I am", "watashi wa" etc.
    String[] prefixes = {
        "my name is ", "i'm ", "i am ", "it's ", "call me ",
        "watashi wa ", "boku wa ", "ore wa "
    };
    String lower = cleaned.toLowerCase();
    for (int i = 0; i < prefixes.length; i++) {
        if (lower.startsWith(prefixes[i])) {
            cleaned = cleaned.substring(prefixes[i].length()).trim();
            break;
        }
    }

    // Remove trailing period/comma
    if (cleaned.endsWith(".") || cleaned.endsWith(",")) {
        cleaned = cleaned.substring(0, cleaned.length() - 1).trim();
    }

    log("Name extracted: " + cleaned);
    return cleaned.isEmpty() ? null : cleaned;
}
```

### 3.6 Replacing listenForYesNo()

SpeechRecog.getYesorNo() matches specific Japanese patterns. With Whisper, do keyword matching:

```java
/**
 * Listen for yes/no via Whisper.
 * Supports multiple languages via keyword matching.
 * @return "yes", "no", or null
 */
public String listenForYesNo() {
    String transcript = listen(YESNO_TIMEOUT_MS);
    if (transcript == null) return null;

    String lower = transcript.toLowerCase().trim();

    // Yes patterns (EN, JA, ID)
    String[] yesPatterns = {
        "yes", "yeah", "yep", "sure", "ok", "okay",
        "hai", "un", "ee", "sou", "soudesu",
        "\u306f\u3044",           // はい
        "\u3046\u3093",           // うん
        "\u305d\u3046",           // そう
        "ya", "iya"               // Indonesian
    };

    // No patterns (EN, JA, ID)
    String[] noPatterns = {
        "no", "nope", "nah",
        "iie", "iya", "dame", "chigau",
        "\u3044\u3044\u3048",     // いいえ
        "\u3060\u3081",           // だめ
        "\u3061\u304c\u3046",     // ちがう
        "tidak", "bukan"          // Indonesian
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

    log("YesNo: unrecognized answer: " + transcript);
    return null;
}
```

### 3.7 Things Whisper CANNOT Replace Directly

| SpeechRecog Feature | Whisper Limitation | Workaround |
|---------------------|--------------------|------------|
| Real-time voice activity detection | Whisper is post-processing only | Use hybrid (SpeechRecog for onset, Whisper for content) or volume threshold |
| `getName()` with built-in name database | Whisper returns raw text | Parse text to extract name (see 3.5) |
| `getYesorNo()` with Japanese NLU | Whisper returns raw text | Keyword matching in multiple languages (see 3.6) |
| Offline operation | Requires WiFi to laptop | Keep SpeechRecog as fallback when WiFi unavailable |
| Zero recording management | SpeechRecog handles mic internally | Must use CRecordMic explicitly |
| Built-in noise filtering | Whisper has some robustness | Pre-process audio or increase model size for noisy environments |

### 3.8 Recommended Integration Strategy

1. **Phase 1 (current):** Get WhisperSTT working standalone (this project)
2. **Phase 2:** Verify CRecordMic API from sotalib.jar — record to WAV on robot
3. **Phase 3:** Add WhisperSTT to SpeechManager with dual-mode (Whisper + SpeechRecog fallback)
4. **Phase 4:** Optimize — implement VAD, adjust recording duration, try "small" model for better accuracy
