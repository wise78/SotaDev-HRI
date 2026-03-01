# WhisperInteraction — Face + Whisper STT + LLM + TTS + Motion

Program interaksi Sota robot yang menggabungkan semua komponen:
face detection, Whisper speech-to-text, Ollama LLM, text-to-speech, dan dynamic gesture.

## Alur Program

```
[IDLE] --- face terdeteksi ---> [GREETING] --- Sota menyapa --->
[LISTENING] --- CRecordMic + VAD + Whisper ---> [THINKING] --- LLM generate --->
[RESPONDING] --- TTS + gesture ---> [LISTENING] (loop) atau [CLOSING] --- goodbye --->
[IDLE]
```

## Arsitektur

```
ROBOT (Sota/Edison)                     LAPTOP (RTX 4070)
├─ CRoboCamera → face detect           ├─ whisper_server.py :5050
├─ CRecordMic → WAV file               │  └─ Whisper (base) GPU
├─ WhisperSTT ──HTTP POST──────────►   │     → text + text_en + language
├─ LlamaClient ─HTTP POST──────────►   ├─ Ollama :11434
│  └─ text_en → LLM → response         │  └─ llama3.2:3b
├─ TextToSpeechSota → speak            │
├─ GestureManager → motion + LED       ├─ interaction_gui.py (Monitor)
├─ StatusServer :5051 ◄──HTTP GET──    │  └─ polls /status every 500ms
└─ WhisperInteraction (FSM)            │
```

---

## Langkah 1 — Siapkan Laptop (SETIAP KALI mau pakai)

Setiap kali mau menjalankan program ini, **dua server** berikut harus aktif di laptop **sebelum** menjalankan program di robot.

> **Catatan v3**: DeepFace untuk deteksi etnisitas sudah terintegrasi di dalam `whisper_server.py` (endpoint `/analyze_face`). **Tidak perlu server tambahan** — cukup jalankan `whisper_server.py` seperti biasa, DeepFace otomatis aktif.

### 1a. Cari IP laptop dulu

Buka **PowerShell** dan jalankan:

```powershell
ipconfig
```

Cari bagian **Wireless LAN adapter Wi-Fi** → lihat **IPv4 Address**.
Contoh: `192.168.11.32`

> IP ini yang akan dipakai sebagai argumen saat menjalankan di robot.

---

### 1b. Jalankan Whisper Server (+ DeepFace)

Server ini sekarang menangani **dua fungsi sekaligus**:
- `/transcribe` — Speech-to-Text (Whisper)
- `/analyze_face` — Deteksi etnisitas (DeepFace) ← **baru di v3**

**Buka PowerShell BARU** (terminal 1):

```powershell
cd C:\Users\interact-ai-001\eclipse-workspace\SotaSample\SotaWhisperTest\interaction
python whisper_server.py
```

**Output sukses:**
```
Using device: cuda (NVIDIA GeForce RTX 4070 Laptop GPU)
Loading Whisper model 'base' on cuda...
DeepFace available: True
Model ready. Starting Flask on port 5050
 * Running on all addresses (0.0.0.0)
 * Running on http://127.0.0.1:5050
```

> **Pertama kali run?** DeepFace akan otomatis download model (~400MB ke `~/.deepface/`). Tunggu sampai selesai (~5 menit). Run berikutnya akan skip download.

> **Terminal ini harus tetap terbuka.** Jangan ditutup selama program robot berjalan.

Verifikasi server jalan (dari PowerShell lain):
```powershell
curl.exe http://localhost:5050/health
# Expected: {"deepface":true,"device":"cuda","model":"base","status":"ok"}
```

> Jika `"deepface":false` → DeepFace gagal load (biasanya karena dependencies belum install). Sistem tetap berjalan, hanya deteksi etnisitas yang dinonaktifkan.

**Install dependencies jika belum** (sekali saja):
```powershell
pip install flask numpy openai-whisper pillow deepface
```

---

### 1c. Jalankan Ollama (PENTING: harus listen di 0.0.0.0)

Ollama secara default hanya listen di `127.0.0.1` (localhost). Robot **tidak bisa** mengakses
Ollama jika hanya listen di localhost. Harus diset agar listen di semua interface (`0.0.0.0`).

**Set environment variable (sekali saja, permanen):**
```powershell
[System.Environment]::SetEnvironmentVariable("OLLAMA_HOST", "0.0.0.0", "User")
```

> Setelah set environment variable, **restart Ollama** (tutup dan buka lagi, atau restart PC).

Cek apakah Ollama sudah berjalan:

```powershell
curl.exe http://localhost:11434/api/tags
```

- Jika responnya JSON (`{"models":[...]}`): **Ollama sudah aktif.**
- Jika error `connection refused`: Ollama belum jalan, jalankan dengan:

```powershell
$env:OLLAMA_HOST = "0.0.0.0"
ollama serve
```

**Output sukses:**
```
time=... level=INFO source=... msg="Listening on [::]:11434 (version ...)"
```

> Jika muncul error `bind: Only one usage of each socket address` → **normal**, artinya Ollama sudah berjalan di background. Tidak perlu melakukan apa-apa.

> Jika `ollama serve` berhasil dijalankan, **terminal ini harus tetap terbuka.**

**Verifikasi Ollama bisa diakses dari jaringan** (PENTING!):
```powershell
curl.exe http://192.168.11.32:11434/api/tags
# Ganti IP dengan IP laptop dari ipconfig
# Harus return JSON {"models":[...]}
# Jika error/timeout → OLLAMA_HOST belum diset atau firewall belum dibuka
```

Pastikan model `llama3.2:3b` sudah ada:
```powershell
ollama list
# Harus ada: llama3.2:3b
```

Jika model belum ada, download dulu:
```powershell
ollama pull llama3.2:3b
# Download ~2GB, tunggu sampai selesai
```

---

### 1d. Buka Firewall (sekali saja, sudah permanen)

Jika belum pernah dibuka, jalankan di **PowerShell sebagai Administrator**:

```powershell
# Port 5050 — Whisper server
netsh advfirewall firewall add rule name="SotaWhisper5050" dir=in action=allow protocol=TCP localport=5050

# Port 11434 — Ollama
netsh advfirewall firewall add rule name="SotaOllama11434" dir=in action=allow protocol=TCP localport=11434
```

**Output sukses:**
```
Ok.
Ok.
```

> Firewall hanya perlu dibuka **sekali**. Rule akan tersimpan permanen.

Verifikasi rule sudah ada:
```powershell
netsh advfirewall firewall show rule name="SotaWhisper5050"
netsh advfirewall firewall show rule name="SotaOllama11434"
```

---

## Langkah 2 — Deploy ke Robot (Eclipse)

### Compile saja (tanpa kirim ke robot)

1. Di Eclipse **Project Explorer**, klik kanan `send.xml`
2. Pilih **Run As → Ant Build...**
3. Di tab **Targets**, centang **`compile_whisperinteraction`** saja
4. Klik **Run**
5. Output: `WhisperInteraction compiled!`

### Compile + Kirim ke Robot

1. Di Eclipse **Project Explorer**, klik kanan `send.xml`
2. Pilih **Run As → Ant Build...**
3. Di tab **Targets**, centang **`whisperinteraction_to_edison`**
4. Klik **Run**
5. Input:
   - `Select Platform?` → **Edison**
   - `Please enter ip:` → IP robot (misal **192.168.11.30**)
6. Output sukses:
   ```
   WhisperInteraction compiled!
   Created runnablejar/whisperinteraction.jar
   WhisperInteraction deployed!
   ```

---

## Langkah 3 — Jalankan di Robot (SSH)

**Buka PowerShell BARU** (terminal 3):

```powershell
# SSH ke robot
ssh root@192.168.11.30
# password: edison00
```

Di dalam SSH robot:

```bash
cd /home/root/SotaWhisperTest

# Cek file sudah ada
ls -la whisperinteraction.jar run_interaction.sh

# Jalankan via shell script (sudah include LD_LIBRARY_PATH untuk OpenCV)
./run_interaction.sh 192.168.11.32

# Dengan opsi custom:
./run_interaction.sh 192.168.11.32 --ollama-port 11434 --model llama3.2:3b
```

> **Kenapa pakai `run_interaction.sh` bukan `java -jar` langsung?**
> Program ini menggunakan kamera (OpenCV). Library native OpenCV (`libopencv_java310.so`)
> ada di `/usr/local/share/OpenCV/java/` dan tidak otomatis ditemukan Java.
> Shell script mengatur `LD_LIBRARY_PATH` sebelum menjalankan Java:
> ```bash
> LD_LIBRARY_PATH=/usr/local/share/OpenCV/java java -jar whisperinteraction.jar ...
> ```
> Tanpa ini, Java akan error: `UnsatisfiedLinkError: no opencv_java310 in java.library.path`

**Output awal yang diharapkan:**
```
========================================================
  Sota Whisper Interaction
  Face -> Greet -> Listen -> LLM -> Respond
========================================================

  Laptop IP    : 192.168.11.32
  Whisper      : http://192.168.11.32:5050
  Ollama       : http://192.168.11.32:11434
  Model        : llama3.2:3b
  Status       : http://0.0.0.0:5051/status

[StatusServer] Started on port 5051
[WhisperInteraction] Robot connected. Firmware: ...
[WhisperInteraction] Camera initialized
[WhisperInteraction] Whisper server OK
[WhisperInteraction] Ollama OK
[WhisperInteraction] GestureManager started
[WhisperInteraction] State: IDLE -- waiting for face...
```

Hentikan program dengan **Ctrl+C**.

---

## Langkah 4 — Monitoring GUI (Laptop, opsional)

GUI untuk monitoring real-time status robot dari laptop. Tidak memerlukan venv.

### Jalankan GUI

**Double-click** `SotaWhisperTest\interaction\start_interaction_gui.bat`

Atau via PowerShell:
```powershell
cd C:\Users\interact-ai-001\eclipse-workspace\SotaSample\SotaWhisperTest\interaction
python interaction_gui.py
```

### Cara Pakai GUI

1. Masukkan **Robot IP** (misal `192.168.11.30`) dan klik **Connect**
2. Jika robot sudah menjalankan `./run_interaction.sh`, status akan langsung muncul
3. GUI menampilkan:
   - **Prerequisites**: Whisper OK/FAIL, Ollama OK/FAIL, Robot OK/FAIL
   - **State**: State FSM saat ini (IDLE/GREETING/LISTENING/dll) dengan warna
   - **VAD**: Apakah Voice Activity Detection bekerja atau tidak
   - **Audio Level**: Level audio real-time (jika VAD bekerja)
   - **Recording**: Status recording aktif + durasi
   - **Conversation**: Teks user (original + English), respon Sota

### Tentang VAD (Voice Activity Detection)

VAD menggunakan reflective probe (`getRMS`, `getRms`, `getAudioLevel`, `getLevel`, `getVolume`)
pada `CRecordMic` SDK. Jika **semua method tidak tersedia** di SDK Edison:

- GUI akan menampilkan: `VAD Status: NOT WORKING (reflectInt=-1, VAD disabled)`
- Recording **tetap berjalan** tapi selalu sampai max 15 detik (tidak bisa early-stop)
- Ini berarti audio dikirim ke Whisper walaupun tidak ada yang bicara

**Jika VAD bekerja:**
- GUI menampilkan: `VAD Status: WORKING (early-stop enabled)`
- Audio level bar bergerak (hijau = ada suara, abu = diam)
- Recording berhenti otomatis setelah 2.5 detik diam

> **VAD tidak bekerja bukan berarti program gagal** — hanya kurang efisien karena
> mengirim audio kosong ke Whisper. Whisper akan mengembalikan teks kosong dan program
> akan retry (max 2 retries sebelum kembali ke IDLE).

---

## State Machine

| State | Apa yang terjadi | LED | Motion |
|-------|-----------------|-----|--------|
| IDLE | Camera polling, menunggu wajah | White breathing | Neutral, subtle movement |
| GREETING | Sota menyapa: "Hello! I'm Sota." | Green pulse | Nod + wave |
| LISTENING | CRecordMic recording + VAD | Cyan breathing | Lean forward (listening) |
| THINKING | Whisper transcribe + LLM processing | Yellow pulse | Head tilt (thinking) |
| RESPONDING | TTS speak LLM response | Green pulse | Random nods + arm gestures |
| CLOSING | Goodbye + return to neutral | Fade to white | Wave goodbye |

---

## Daftar File

### Java (di robot)

| # | File | Fungsi |
|---|------|--------|
| 1 | `WhisperInteraction.java` | Main FSM program — orchestrator |
| 2 | `WhisperSpeechManager.java` | CRecordMic + VAD + Whisper STT + TTS |
| 3 | `WhisperSTT.java` | HTTP client ke Whisper server (text + text_en + language) |
| 4 | `LlamaClient.java` | HTTP client ke Ollama LLM (streaming NDJSON) |
| 5 | `GestureManager.java` | Background threads: motion + LED berdasarkan state |
| 6 | `StatusServer.java` | Tiny HTTP server port 5051 untuk monitoring GUI |

### Python + Script (di laptop)

| # | File | Fungsi |
|---|------|--------|
| 7 | `interaction_gui.py` | Monitoring GUI — polls StatusServer di robot |
| 8 | `start_interaction_gui.bat` | Double-click launcher untuk GUI |
| 9 | `run_interaction.sh` | Shell script launcher di robot (LD_LIBRARY_PATH + status-port) |

---

## Bahasa

- **Input**: Semua bahasa (Whisper auto-detect + translate ke English)
- **LLM input**: Selalu English (`text_en`) — reliable untuk semua bahasa
- **LLM output**: Japanese jika user bicara Jepang, English untuk bahasa lain
- **TTS**: Hanya Japanese dan English (limitasi `TextToSpeechSota`)

---

## Checklist Sebelum Jalan

```
[ ] ipconfig -> catat IP laptop (WiFi)
[ ] start_server.bat -> Whisper server berjalan (terminal terbuka)
[ ] ollama serve -> Ollama berjalan (atau sudah background)
[ ] ollama list -> llama3.2:3b ada dalam daftar
[ ] Firewall port 5050 dan 11434 sudah dibuka
[ ] Laptop dan robot di WiFi yang sama
[ ] Eclipse: whisperinteraction_to_edison -> BUILD SUCCESSFUL
[ ] SSH ke robot -> ./run_interaction.sh <IP_LAPTOP>
[ ] (Opsional) Buka GUI -> start_interaction_gui.bat -> Connect ke robot
```

---

## Troubleshooting

| Problem | Solusi |
|---------|--------|
| `-bash: ./run_interaction.sh: /bin/sh^M: bad interpreter` | Windows line endings. Di robot: `sed -i 's/\r$//' run_interaction.sh && chmod +x run_interaction.sh` |
| `Cannot connect to robot` | Pastikan dijalankan di Sota (SSH), bukan di laptop |
| `UnsatisfiedLinkError: no opencv_java310` | Pakai `./run_interaction.sh`, bukan `java -jar` langsung |
| `Whisper server not reachable` | Jalankan `start_server.bat`, cek firewall port 5050 |
| `Whisper server OK` tidak muncul | Cek IP laptop benar, cek WiFi sama subnet |
| LLM response error / timeout | **Paling sering**: `OLLAMA_HOST` belum diset `0.0.0.0`. Set: `[System.Environment]::SetEnvironmentVariable("OLLAMA_HOST", "0.0.0.0", "User")` lalu restart Ollama. Verifikasi: `curl.exe http://<LAPTOP_IP>:11434/api/tags`. Juga cek firewall port 11434. |
| `No route to host` dari robot | Cek firewall, cek `ipconfig` di laptop untuk IP yang benar |
| No speech detected / silence | Bicara lebih keras, cek mic robot |
| TTS tidak keluar suara | Cek speaker robot, pastikan `ServoOn` berhasil |
| Face tidak terdeteksi | Hadapkan wajah ke kamera, pastikan pencahayaan cukup |
| Transcription lambat | Normal untuk pertama kali (CUDA warmup). Berikutnya lebih cepat |
| GUI: Connection failed | Pastikan `./run_interaction.sh` sudah jalan di robot (StatusServer port 5051) |
| GUI: VAD NOT WORKING | Normal jika SDK Edison tidak punya audio level method. Recording tetap jalan, hanya tidak bisa early-stop |
