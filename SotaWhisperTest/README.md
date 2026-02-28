# SotaWhisperTest — Whisper STT Server for Sota Robot

Runs OpenAI Whisper speech-to-text on the laptop (GPU) as an HTTP server.
Sota sends WAV audio files over WiFi and receives transcribed text + detected language.

## Architecture

```
[Sota Robot (Edison)]                  [Laptop (RTX 4070)]
  CRecordMic -> WAV file                 whisper_server.py
  WhisperSTT.java ---HTTP POST---->      Flask :5050
                   <---JSON--------      Whisper model (base)
  result.text + result.language
```

## Prerequisites

- **Python 3.12** — PyTorch CUDA wheels require Python 3.10-3.12 (NOT 3.13+)
  - Install: `winget install Python.Python.3.12`
- **ffmpeg** — required by Whisper for audio decoding
  - Install: `winget install Gyan.FFmpeg` (lalu restart terminal)
- **NVIDIA GPU + drivers** — CUDA 12.x (RTX 4070 works out of the box)

---

## Daftar Semua File

| # | File | Jalan di mana | Fungsi | Butuh server? |
|---|------|---------------|--------|---------------|
| 1 | `setup_venv.bat` | Laptop | Setup Python venv + install semua library (sekali saja) | Tidak |
| 2 | `generate_test_audio.py` | Laptop | Buat file WAV test (EN + JA) dari Windows SAPI | Tidak |
| 3 | `test_whisper_local.py` | Laptop | Test Whisper langsung di GPU (tanpa server) | Tidak |
| 4 | `start_server.bat` | Laptop | Jalankan Whisper HTTP server di port 5050 | - |
| 5 | `whisper_server.py` | Laptop | Flask server (dipanggil oleh start_server.bat) | - |
| 6 | `test_server.py` | Laptop | Smoke test HTTP endpoint /health + /transcribe | Ya |
| 7 | `whisper_gui.py` | Laptop | GUI untuk test, troubleshoot, dan benchmark | Ya |
| 8 | `start_gui.bat` | Laptop | Shortcut untuk buka GUI (double-click) | Ya |
| 9 | `benchmark.py` | Laptop | Ukur latency avg/min/max (command-line) | Ya |
| 10 | `java/WhisperSTT.java` | Laptop + Robot | Java 1.8 HTTP client untuk Whisper (source code) | Ya |
| 11 | `java/TestWhisperSota.java` | Laptop + Robot | Standalone Java test program | Ya |
| 12 | `java/compile.bat` | Laptop | Compile Java (pakai full JDK path, untuk PowerShell) | Tidak |
| 13 | `java/run_test.bat` | Laptop | Jalankan Java test (pakai full JDK path) | Ya |
| 14 | `SOTA_INTEGRATION.md` | - | Dokumentasi: connectivity + pengganti SpeechRecog | - |

---

## Cara Menjalankan — Urutan dari Awal

### Langkah 1: Setup Environment (sekali saja)

**Buka:** File Explorer atau PowerShell
**Lokasi:** `C:\Users\interact-ai-001\eclipse-workspace\SotaSample\SotaWhisperTest\`

```
Cara 1: Double-click setup_venv.bat di File Explorer
Cara 2: Di PowerShell:
  cd C:\Users\interact-ai-001\eclipse-workspace\SotaSample\SotaWhisperTest
  .\setup_venv.bat
```

**Apa yang terjadi:**
- Cek Python 3.12 terinstall
- Cek ffmpeg terinstall
- Cek NVIDIA GPU terdeteksi
- Buat folder `venv\`
- Download + install PyTorch CUDA 12.1 (~4.3 GB)
- Install openai-whisper, flask, pyttsx3

**Output sukses:**
```
[OK] Found Python 3.12.x
[OK] ffmpeg found.
[OK] NVIDIA GPU detected: NVIDIA GeForce RTX 4070 Laptop GPU
...
Setup complete!
```

---

### Langkah 2: Buat File Audio Test

**Buka:** PowerShell (terminal yang sama setelah setup, atau terminal baru)
**Lokasi:** `SotaWhisperTest\`

```powershell
cd C:\Users\interact-ai-001\eclipse-workspace\SotaSample\SotaWhisperTest
venv\Scripts\activate
python generate_test_audio.py
```

**Apa yang terjadi:**
- Pakai Windows SAPI (text-to-speech bawaan Windows) untuk generate audio
- Buat `audio\test_english.wav` (~284 KB) — "Hello, my name is Sota..."
- Buat `audio\test_japanese.wav` (~262 KB) — "こんにちは、私の名前はソータです..."

---

### Langkah 3: Test Whisper di GPU (tanpa server)

**Buka:** PowerShell (venv sudah aktif)
**Lokasi:** `SotaWhisperTest\`

```powershell
python test_whisper_local.py
```

**Apa yang terjadi:**
- Load model Whisper "base" ke GPU
- Test transcribe English WAV
- Test transcribe Japanese WAV
- Test auto-detect bahasa

**Output sukses:**
```
[TEST a] GPU detection and model loading...
  CUDA available  : True
  GPU             : NVIDIA GeForce RTX 4070 Laptop GPU
  VRAM            : 8188 MB
  [OK] Model on GPU

[TEST b] English transcription...
  Text     : Hello, my name is Sota. How are you today?...
  Time     : 1.85s
  [OK]

[TEST c] Japanese transcription...
  Text     : こんにちは、私の名前はソータです...
  [OK]

  ALL TESTS PASSED!
```

---

### Langkah 4: Jalankan Server

**Buka:** PowerShell **BARU** (terminal terpisah, biarkan tetap terbuka)
**Lokasi:** `SotaWhisperTest\`

```
Cara 1: Double-click start_server.bat di File Explorer
Cara 2: Di PowerShell:
  cd C:\Users\interact-ai-001\eclipse-workspace\SotaSample\SotaWhisperTest
  .\start_server.bat
```

**Apa yang terjadi:**
- Aktivasi venv otomatis
- Load model Whisper ke GPU
- Server Flask mulai di `http://0.0.0.0:5050`
- **Terminal ini harus tetap terbuka selama testing!**

**Output:**
```
Using device: cuda (NVIDIA GeForce RTX 4070 Laptop GPU)
Loading Whisper model 'base' on cuda...
Model ready. Starting Flask on port 5050
 * Running on all addresses (0.0.0.0)
 * Running on http://127.0.0.1:5050
```

---

### Langkah 5: Test Server (pilih salah satu)

#### Opsi A: Pakai GUI (Recommended)

**Buka:** PowerShell **BARU** (terminal ke-3) atau double-click
**Lokasi:** `SotaWhisperTest\`

```
Cara 1: Double-click start_gui.bat di File Explorer
Cara 2: Di PowerShell:
  cd C:\Users\interact-ai-001\eclipse-workspace\SotaSample\SotaWhisperTest
  .\start_gui.bat
```

**Fitur GUI:**
- **Check Health** — cek apakah server nyala (indicator hijau/merah)
- **Quick Select** — pilih test_english.wav atau test_japanese.wav langsung
- **Browse** — pilih file WAV sendiri
- **Transcribe** — kirim audio ke server, lihat hasilnya (text, language, timing)
- **Run Benchmark** — jalankan N kali, lihat avg/min/max latency
- **Log** — semua aktivitas tercatat di panel bawah

#### Opsi B: Command-line test

**Buka:** PowerShell BARU
**Lokasi:** `SotaWhisperTest\`

```powershell
cd C:\Users\interact-ai-001\eclipse-workspace\SotaSample\SotaWhisperTest
venv\Scripts\activate
python test_server.py
```

#### Opsi C: Pakai curl

```powershell
curl.exe http://localhost:5050/health
curl.exe -X POST -F "audio=@audio/test_english.wav" http://localhost:5050/transcribe
```

> **PENTING:** Di PowerShell, harus pakai `curl.exe` (bukan `curl`) karena `curl` di PowerShell adalah alias untuk `Invoke-WebRequest`.

---

### Langkah 6: Benchmark (opsional)

#### Opsi A: Lewat GUI

Di GUI, set jumlah runs (default 5), pilih WAV file, klik **Run Benchmark**.

#### Opsi B: Command-line

**Buka:** PowerShell (venv aktif)
**Lokasi:** `SotaWhisperTest\`

```powershell
python benchmark.py                                    # default: 5 runs, semua WAV
python benchmark.py --runs 10                          # 10 runs
python benchmark.py --wav audio\test_english.wav       # file spesifik
python benchmark.py --host 192.168.11.5 --runs 5      # test ke IP lain
```

---

### Langkah 7: Test Java Client (dari laptop)

**Buka:** PowerShell
**Lokasi:** `SotaWhisperTest\java\`

```powershell
cd C:\Users\interact-ai-001\eclipse-workspace\SotaSample\SotaWhisperTest\java

# Compile
.\compile.bat

# Run test (server harus nyala)
.\run_test.bat localhost ..\audio\test_english.wav
.\run_test.bat localhost ..\audio\test_japanese.wav
```

Atau compile dari Eclipse (lihat Langkah 8).

---

### Langkah 8: Deploy ke Robot Sota via Eclipse (send.xml)

**Buka:** Eclipse IDE
**Lokasi:** Project `SotaSample`

#### 8a. Compile saja (tanpa kirim ke robot)

1. Di Eclipse **Project Explorer**, klik kanan `send.xml`
2. Pilih **Run As → Ant Build...**
3. Di tab **Targets**, centang **`compile_whispertest`** saja
4. Klik **Run**
5. Console Eclipse akan menampilkan: `SotaWhisperTest compiled!`

#### 8b. Compile + Kirim ke Robot

1. Di Eclipse **Project Explorer**, klik kanan `send.xml`
2. Pilih **Run As → Ant Build...**
3. Di tab **Targets**, centang **`whispertest_to_edison`**
4. Klik **Run**
5. Eclipse akan bertanya:
   - `Select Platform? [Rpi/Edison]` → ketik **Edison**, Enter
   - `Please enter ip:` → ketik IP robot (misal **192.168.11.30**), Enter
6. Build akan:
   - Compile `WhisperSTT.java` + `TestWhisperSota.java` (Java 1.8)
   - Buat `runnablejar/whispertest.jar`
   - SCP kirim `whispertest.jar` → `/home/root/SotaWhisperTest/` di robot
   - SCP kirim `audio/*.wav` → `/home/root/SotaWhisperTest/` di robot

**Output sukses di Eclipse Console:**
```
SotaWhisperTest compiled!
Created runnablejar/whispertest.jar
Audio files sent to /home/root/SotaWhisperTest/
WhisperSTT deployed! On robot: cd /home/root/SotaWhisperTest && java -jar whispertest.jar LAPTOP_IP test_english.wav
```

---

### Langkah 9: Test dari Robot Sota (SSH)

**Prasyarat:**
- Server Whisper harus nyala di laptop (Langkah 4)
- Firewall port 5050 harus terbuka: `netsh advfirewall firewall add rule name="SotaWhisper5050" dir=in action=allow protocol=TCP localport=5050`
- Laptop dan robot harus di WiFi yang sama
- Sudah deploy via Eclipse (Langkah 8b)

**Buka:** PowerShell di laptop (untuk SSH ke robot)

```powershell
# SSH ke robot
ssh root@192.168.11.30
# password: edison00
```

Di robot:

```bash
# Pindah ke folder yang sudah di-deploy
cd /home/root/SotaWhisperTest

# Cek file sudah ada
ls -la
# Harus ada: whispertest.jar, test_english.wav, test_japanese.wav

# Test health dulu pakai curl
curl http://192.168.11.32:5050/health
# Expected: {"device":"cuda","model":"base","status":"ok"}

# Jalankan Java test
java -jar whispertest.jar 192.168.11.32 test_english.wav
java -jar whispertest.jar 192.168.11.32 test_japanese.wav
```

> Ganti `192.168.11.5` dengan IP WiFi laptop kamu (cek dengan `ipconfig` di laptop).

---

## Troubleshooting

| Problem | Solusi |
|---------|--------|
| `py -3.12 not found` | Install: `winget install Python.Python.3.12` |
| `ffmpeg not found` | Install: `winget install Gyan.FFmpeg`, restart terminal |
| `CUDA not available` | Cek `nvidia-smi` di terminal. Kalau error, update GPU driver |
| Server tidak bisa diakses dari robot | Cek firewall port 5050, cek WiFi sama subnet |
| `curl` di PowerShell error | Pakai `curl.exe` (bukan `curl`) |
| `java not recognized` di PowerShell | Pakai `.\compile.bat` dan `.\run_test.bat` |
| GUI tidak muncul | Pastikan venv aktif: `venv\Scripts\activate` lalu `python whisper_gui.py` |
| Transcription lambat | Pertama kali lambat (CUDA warmup). Run ke-2+ lebih cepat |

---

## API Reference

### GET /health
```json
{"status": "ok", "model": "base", "device": "cuda"}
```

### POST /transcribe
- **Content-Type:** `multipart/form-data`
- **Field:** `audio` — WAV file (binary, bukan Base64)
- **Optional field:** `language` — paksa bahasa (e.g. `"ja"`, `"en"`). Kosongkan untuk auto-detect.

**Response:**
```json
{"ok": true, "text": "Hello, how are you?", "language": "en", "processing_ms": 832}
```

---

## Whisper Model

Default model: `base` (74M params, ~0.5-1s per 5s audio di RTX 4070).
Ubah `WHISPER_MODEL` di file Python untuk ganti model:

| Model | Params | VRAM | Speed (5s audio) | Accuracy |
|-------|--------|------|-------------------|----------|
| tiny  | 39M    | ~1GB | ~0.3s             | Basic    |
| base  | 74M    | ~1GB | ~0.5s             | Good     |
| small | 244M   | ~2GB | ~1.5s             | Better   |
| medium| 769M   | ~5GB | ~4s               | Best     |

---

## Referensi Lanjutan

- [SOTA_INTEGRATION.md](SOTA_INTEGRATION.md) — panduan lengkap: connectivity, firewall, curl test dari robot, dan cara mengganti SpeechRecog dengan WhisperSTT
