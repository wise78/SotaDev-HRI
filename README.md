# SotaDev-HRI

Vstone Sota robot development workspace for **Human-Robot Interaction (HRI)** research.

Contains various systems developed for the Sota robot — from SDK samples, Whisper STT integration, to LLM-based conversational interaction with user memory and reciprocity.

---

## Project Structure

```
SotaDev-HRI/
├── SotaWhisperTest/
│   └── interaction/              # Main project — full HRI system
│       ├── src/                  # Java source (WhisperInteraction, GestureManager, etc.)
│       ├── research_data/        # Experiment logs, questionnaires, analysis scripts
│       │   ├── logs/             # Per-session conversation transcripts (P01–P20)
│       │   ├── questionnaire/    # Raw questionnaire CSVs (Likert + open-ended)
│       │   └── figures/          # Generated analysis figures
│       ├── whisper_server.py     # Whisper STT + DeepFace server (laptop)
│       ├── interaction_gui.py    # Real-time monitoring GUI (laptop)
│       └── *.sh                  # Robot launch scripts (novice/remember/no-remember)
├── SotaVisionTest/               # Social vision interaction system (earlier prototype)
├── llm_test/                     # Standalone Ollama LLM bridge (no SDK deps)
├── src/                          # Original Vstone SDK samples
├── lib/                          # SDK JARs (sotalib, OpenCV 3.1.0, JNA)
├── sound/                        # Robot audio assets
└── send.xml                      # Ant build & deploy targets
```

---

## Main Project: WhisperInteraction

**Path:** [`SotaWhisperTest/interaction/`](SotaWhisperTest/interaction/)

Full interaction system: **Face Detection → Whisper STT → LLM (Ollama) → TTS → Gesture + User Memory**

### System Architecture

```
ROBOT (Sota/Edison)                       LAPTOP (RTX 4070)
├─ CRoboCamera → face detect             ├─ whisper_server.py :5050
├─ DirectVADRecorder → WAV               │  ├─ Whisper (base) GPU → STT
├─ WhisperSTT ──HTTP POST──────────►     │  └─ DeepFace → ethnicity detect
├─ LlamaClient ─HTTP POST──────────►     ├─ Ollama :11434
│  └─ text_en → LLM → response           │  └─ llama3.2:3b
├─ TextToSpeechSota → speak              │
├─ GestureManager → motion + LED         ├─ interaction_gui.py (Monitor)
├─ UserMemory → remember/recall           │  └─ polls /status every 500ms
├─ StatusServer :5051 ◄──HTTP GET──      │
└─ WhisperInteraction (FSM)              │
```

### Key Features

- **FSM**: `IDLE → GREETING → LISTENING → THINKING → RESPONDING → CLOSING`
- **User Memory & Reciprocity**: Robot remembers name, origin, past conversation topics across sessions
- **DirectVADRecorder**: Voice Activity Detection via RMS energy (javax.sound.sampled), SDK-independent
- **Real-time monitoring GUI** (Python tkinter) via embedded HTTP StatusServer
- **Multi-language**: Whisper auto-detects language, LLM responds in detected language
- **Dynamic gestures**: State-based LED patterns + servo motion (nod, wave, thinking tilt, etc.)
- **Sound effects**: Ambient cues for state transitions (chime, thinking hum, etc.)
- **Video-call mode**: Remote interaction support

### Java Source Files

| File | Role |
|------|------|
| `WhisperInteraction.java` | Main FSM orchestrator |
| `WhisperSpeechManager.java` | Recording + VAD + Whisper STT + TTS |
| `WhisperSTT.java` | HTTP client to Whisper server |
| `LlamaClient.java` | HTTP client to Ollama (streaming NDJSON) |
| `GestureManager.java` | Background threads: motion + LED per state |
| `UserMemory.java` | Persistent user profiles (name, origin, social state, memory summaries) |
| `DirectVADRecorder.java` | RMS-based voice activity detection |
| `DeepFaceClient.java` | HTTP client to DeepFace endpoint on whisper_server |
| `SoundEffects.java` | Audio cue playback for state transitions |
| `StatusServer.java` | HTTP server (port 5051) for monitoring GUI |
| `FaceTrackArmTest.java` | Standalone test: face tracking with arm gestures |
| `ServoTest.java` | Standalone test: servo range testing |
| `LEDInterferenceTest.java` | Standalone test: LED behavior |

### Launch Scripts (on robot)

| Script | Purpose |
|--------|---------|
| `run_interaction.sh` | General launcher (sets LD_LIBRARY_PATH for OpenCV) |
| `start_novice.sh` | Session 1 — all participants, no memory |
| `start_remember.sh` | Session 2 G1 — memory ON (reciprocity condition) |
| `start_no_remember.sh` | Session 2 G2 — memory OFF (control condition) |
| `start_videocall.sh` | Video-call remote interaction mode |
| `reset_memory.sh` | Clear all user profiles |

**Detailed setup & usage:** [`SotaWhisperTest/interaction/README.md`](SotaWhisperTest/interaction/README.md)

---

## Research Data

**Path:** [`SotaWhisperTest/interaction/research_data/`](SotaWhisperTest/interaction/research_data/)

Data from the reciprocity study: *"Effect of Robot Memory & Social Reciprocity on User Trust, Self-Disclosure, and Emotional Connection"*

### Experiment Design

- **2-Session Mixed Design** (between + within subjects)
- **G1 (Treatment)**: S1 = NOVICE (no memory) → S2 = REMEMBER (memory ON, reciprocity)
- **G2 (Control)**: S1 = NOVICE (no memory) → S2 = NO-REMEMBER (memory OFF)
- **Participants**: 20 participants (P01–P20), ~10 per group

### Data Contents

```
research_data/
├── logs/                         # 43 session transcripts
│   ├── P01_G1_S1_NOVICE_20260305_1125.txt
│   ├── P01_G1_S2_REMEMBER_20260306_1305.txt
│   └── ...                       # Format: P{ID}_G{group}_S{session}_{condition}_{date}_{time}.txt
├── questionnaire/                # Raw questionnaire data (Google Forms export)
│   ├── Sota Reciprocity Questionnaire - S1G1.csv
│   ├── Sota Reciprocity Questionnaire - S1G2.csv
│   ├── Session 2 Questionnaire.csv
│   └── Session 2 Questionnaire Group 1.csv
├── figures/                      # Analysis output (10 figures)
├── questionnaire_data.csv        # Merged questionnaire data
├── questionnaire_clean.csv       # Cleaned/preprocessed data
├── session_log.csv               # Session metadata (duration, turns, etc.)
├── RESEARCH_DESIGN.md            # Full study design, hypotheses, questionnaire details
├── analyze_study.py              # Statistical analysis script
├── parse_logs.py                 # Log parser → structured data
├── preprocess_questionnaire.py   # Questionnaire data preprocessing
├── create_figures.py             # Figure generation
└── visualize_questionnaire.py    # Questionnaire visualization
```

### Log File Naming Convention

```
P{ID}_G{group}_S{session}_{CONDITION}_{YYYYMMDD}_{HHMM}.txt

Examples:
  P01_G1_S1_NOVICE_20260305_1125.txt      — Participant 01, Group 1, Session 1
  P01_G1_S2_REMEMBER_20260306_1305.txt     — Same participant, Session 2 (memory ON)
  P02_G2_S2_NO-REMEMBER_20260306_1256.txt  — Group 2 control (memory OFF)
```

**Full research design:** [`RESEARCH_DESIGN.md`](SotaWhisperTest/interaction/research_data/RESEARCH_DESIGN.md)

---

## Other Sub-projects

### SotaVisionTest

**Path:** [`SotaVisionTest/`](SotaVisionTest/)

Earlier prototype — social interaction system with face detection + LLM conversation.
9 classes: SocialState, UserProfile, MemoryManager, SocialStateMachine, LlamaClient, SpeechManager, FaceManager, ConversationManager, MainController.

### LLM Test Bridge

**Path:** [`llm_test/`](llm_test/)

Standalone Sota ↔ Ollama connectivity test without SDK dependencies.
Useful for testing LLM latency and connectivity before deploying to robot.

### SDK Samples

**Path:** [`src/jp/vstone/sotasample/`](src/jp/vstone/sotasample/)

Original Vstone SDK samples including `DynamicVulnerabilityStudy.java` — HRI study program with three robot conditions (CONFIDENT, UNCERTAIN, DISTRESSED).

---

## Requirements

| Component | Version | Notes |
|-----------|---------|-------|
| Java | 8 (1.8) | Robot Edison compatibility |
| Eclipse IDE | Latest | Ant build via `send.xml` |
| Python | 3.8+ | Whisper server, monitoring GUI, analysis scripts |
| Ollama | Latest | LLM backend (llama3.2:3b) |
| Vstone Sota | — | Edison board robot |
| OpenCV | 3.1.0 | Pre-installed on robot at `/usr/local/share/OpenCV/java` |

---

## Build & Deploy

Eclipse Ant targets in [`send.xml`](send.xml):

| Target | Description |
|--------|-------------|
| `whisperinteraction_to_edison` | Build + deploy WhisperInteraction to robot |
| `compile_whisperinteraction` | Compile only |
| `makeJar_whisperinteraction` | Build JAR |

---

## Quick Start

```bash
# 1. Laptop — start Whisper + DeepFace server
python SotaWhisperTest/interaction/whisper_server.py

# 2. Laptop — start Ollama (must listen on 0.0.0.0)
OLLAMA_HOST=0.0.0.0 ollama serve

# 3. Laptop — monitoring GUI (optional)
python SotaWhisperTest/interaction/interaction_gui.py

# 4. Robot — SSH and run
ssh root@<robot_ip>
cd /home/root/SotaWhisperTest
./run_interaction.sh <laptop_ip>
```

---

## Data & Privacy

- Research data (anonymized session logs, questionnaires) is included in the repository under `research_data/`
- Runtime output directories (`voice/`, `recordings/`, `photos/`) are excluded via `.gitignore`
- Participant IDs used for anonymization (no real names in logs or published data)
- Face data stored locally on robot only, not transmitted externally
