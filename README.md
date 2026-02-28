# SotaDev-HRI

Vstone Sota robot development workspace for **Human-Robot Interaction (HRI)** research.

Berisi berbagai sistem yang dikembangkan untuk robot Sota — mulai dari SDK samples, integrasi Whisper STT, hingga sistem percakapan berbasis LLM.

---

## Project Structure

```
SotaDev-HRI/
├── src/                          # Original Vstone SDK samples
├── SotaWhisperTest/              # Whisper STT integration
│   └── interaction/              # Full interaction system (main project)
├── SotaVisionTest/               # Social vision interaction system
├── llm_test/                     # Standalone Ollama LLM bridge
├── lib/                          # SDK JARs (sotalib, OpenCV, JNA)
├── sound/                        # Robot audio assets
└── send.xml                      # Ant build & deploy targets
```

---

## Sub-projects

### WhisperInteraction ⭐ (Main)

**Path:** [`SotaWhisperTest/interaction/`](SotaWhisperTest/interaction/)

Sistem interaksi lengkap: **Face Detection → Whisper STT → LLM (Ollama) → TTS → Gesture**

Features:
- **DirectVADRecorder** — Voice Activity Detection berbasis RMS (javax.sound.sampled), tidak bergantung SDK
- **Real-time monitoring GUI** (Python tkinter) via embedded HTTP StatusServer
- Multi-language support (Whisper mendeteksi bahasa otomatis, LLM merespons)
- FSM: `IDLE → GREETING → LISTENING → THINKING → RESPONDING → CLOSING`

**Quick start:** → [`SotaWhisperTest/interaction/README.md`](SotaWhisperTest/interaction/README.md)

---

### SotaVisionTest

**Path:** [`SotaVisionTest/`](SotaVisionTest/)

Sistem interaksi sosial dengan face detection real-time + LLM conversation.

**Quick start:** → [`SotaVisionTest/README.md`](SotaVisionTest/README.md)

---

### LLM Test Bridge

**Path:** [`llm_test/`](llm_test/)

Standalone test untuk koneksi Sota ↔ Ollama tanpa SDK dependencies.
Berguna untuk testing latency dan konektivitas LLM sebelum deploy ke robot.

---

### SDK Samples

**Path:** [`src/jp/vstone/sotasample/`](src/jp/vstone/sotasample/)

Original Vstone SDK samples termasuk `DynamicVulnerabilityStudy.java` — program HRI study
dengan tiga kondisi robot (CONFIDENT, UNCERTAIN, DISTRESSED).

---

## Requirements

| Component | Version | Notes |
|-----------|---------|-------|
| Java | 8 (1.8) | Robot Edison compatibility |
| Eclipse IDE | Latest | Ant build via `send.xml` |
| Python | 3.8+ | Whisper server, monitoring GUIs |
| Ollama | Latest | LLM backend (llama3.2:3b) |
| Vstone Sota | — | Edison board robot |
| OpenCV | 3.1.0 | Pre-installed on robot at `/usr/local/share/OpenCV/java` |

---

## Build & Deploy

Gunakan Eclipse Ant dengan target di [`send.xml`](send.xml):

| Target | Description |
|--------|-------------|
| `whisperinteraction_to_edison` | Build + deploy WhisperInteraction ke robot |
| `compile_whisperinteraction` | Compile saja |
| `makeJar_whisperinteraction` | Build JAR |

---

## Robot Connection

```bash
# SSH ke robot
ssh root@<robot_ip>

# Jalankan WhisperInteraction
cd /home/root/SotaWhisperTest
./run_interaction.sh <laptop_ip>
```

---

## Setup Laptop (Whisper + Ollama)

```powershell
# 1. Ollama - set listen on all interfaces (WAJIB agar robot bisa akses)
$env:OLLAMA_HOST = "0.0.0.0"
ollama serve

# 2. Whisper server
cd SotaWhisperTest
pip install -r requirements.txt
python whisper_server.py

# 3. Monitoring GUI
python SotaWhisperTest/interaction/interaction_gui.py
```

---

## Data & Privacy

- Data eksperimen (logs, audio) **tidak** disimpan di repository
- Folder `logs/`, `voice/`, `recordings/` diabaikan oleh `.gitignore`
- Participant ID digunakan sebagai anonymization (bukan nama)
