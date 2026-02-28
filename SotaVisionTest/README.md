================================================================================
         SOTAVISIONTEST — SOCIAL INTERACTION SYSTEM FOR VSTONE SOTA
================================================================================

OVERVIEW
--------
Sistem interaksi sosial untuk robot Vstone Sota. Sota mendeteksi wajah,
mengenali user yang sudah pernah ditemui, membangun percakapan menggunakan
LLaMA (via Ollama di laptop), dan mengingat riwayat interaksi setiap user.

Hubungan sosial Sota dengan user berkembang seiring waktu:
  STRANGER     -> pertama kali bertemu
  ACQUAINTANCE -> setelah 1 interaksi
  FRIENDLY     -> setelah 5 interaksi
  CLOSE        -> setelah 15 interaksi

FLOW PROGRAM:
  IDLE -> Face detected -> Dikenal? -> Ya: sapa nama -> Percakapan LLaMA
                                    -> Tidak: tanya nama, estimasi umur, simpan
       -> Update interaction count -> Update social state -> IDLE


================================================================================
ARSITEKTUR (9 CLASS)
================================================================================

  SocialState.java          Enum: STRANGER, ACQUAINTANCE, FRIENDLY, CLOSE
  UserProfile.java          Data user + serialisasi JSON (tanpa Gson)
  MemoryManager.java        Simpan/muat profil dari file JSON
  SocialStateMachine.java   Aturan transisi state berdasarkan jumlah interaksi
  LlamaClient.java          HTTP client ke Ollama (streaming NDJSON)
  SpeechManager.java        TTS + speech recognition (tone adaptif per state)
  FaceManager.java          Face detection/recognition (FaceDetectUpdateListener)
  ConversationManager.java  Prompt dinamis, multi-turn, memory summary
  MainController.java       Orkestrator utama (FSM + main loop)

Struktur folder:
  SotaVisionTest/
  ├── src/jp/vstone/sotavisiontest/   <- 9 file Java
  ├── bin/                             <- hasil compile (auto-created)
  ├── data/                            <- user_profiles.json (auto-created)
  ├── compile.bat                      <- compile di Windows
  ├── run.bat                          <- compile + run di Windows
  ├── run_on_robot.sh                  <- run di robot (Linux)
  └── README.md


================================================================================
PREREQUISITES
================================================================================

1. HARDWARE
   - Vstone Sota robot (terhubung ke jaringan yang sama dengan laptop)
   - Laptop dengan NVIDIA GPU (untuk Ollama)

2. SOFTWARE DI LAPTOP
   - Ollama terinstall: https://ollama.com
   - Model sudah di-pull:
       ollama pull llama3.2:3b
   - Ollama HARUS listen di semua interface (bukan cuma localhost):
       set OLLAMA_HOST=0.0.0.0
       ollama serve
   - Port 11434 harus dibuka di Windows Firewall

3. SOFTWARE DI ROBOT
   - Java 1.8 (sudah terinstall di Sota)
   - Sota SDK libraries di /home/vstone/lib/
   - OpenCV native library


================================================================================
CARA RUNNING DI TERMINAL ROBOT (STEP BY STEP)
================================================================================

--- STEP 1: Pastikan Ollama jalan di laptop ---

Di laptop (Windows), buka Command Prompt/PowerShell:

  set OLLAMA_HOST=0.0.0.0
  ollama serve

Buka terminal lain, test:

  ollama run llama3.2:3b "hello"

Catat IP laptop (contoh: 192.168.11.5):

  ipconfig

--- STEP 2: Build & Transfer ke robot via Eclipse Ant ---

Transfer dilakukan melalui Eclipse menggunakan file send.xml (Ant Build).

CARA:
  1. Buka Eclipse
  2. Di Project Explorer, klik kanan file send.xml
  3. Pilih: Run As -> Ant Build...  (yang ada titik tiganya "...")
  4. Di tab "Targets", pilih SALAH SATU target:

     a) "jp.vstone.sotavisiontest.MainController"
        -> Compile SotaVisionTest, buat JAR, kirim JAR ke robot
        -> File yang dikirim: runnablejar/sotavisiontest.jar
        -> Cocok untuk deploy cepat (hanya kirim 1 file JAR)

     b) "send" atau "send_all"
        -> Kirim SELURUH folder SotaSample ke robot (termasuk SotaVisionTest)
        -> Cocok kalau pertama kali setup atau ada perubahan banyak file

  5. Klik "Run"
  6. Di console Eclipse, jawab pertanyaan:
     - Select Platform? -> Edison
     - Please enter ip:  -> masukkan IP robot (contoh: 192.168.11.30)

  Password Edison: edison00 (sudah di-set di send.xml)

CATATAN PENTING:
  - Target "jp.vstone.sotavisiontest.MainController" sudah ditambahkan di send.xml
  - Target ini compile -> buat JAR -> SCP ke robot secara otomatis
  - JAR dibuat di: runnablejar/sotavisiontest.jar
  - JAR dikirim ke: /home/root/SotaSample/ (Edison)

--- STEP 3: SSH ke robot ---

  ssh root@<ROBOT_IP>

Contoh:

  ssh root@192.168.11.30

--- STEP 4: Run di robot ---

Kalau pakai JAR (dari target "jp.vstone.sotavisiontest.MainController"):

  cd ~/SotaSample

  LD_LIBRARY_PATH=/usr/local/share/OpenCV/java:$LD_LIBRARY_PATH \
  java -jar sotavisiontest.jar http://<LAPTOP_IP>:11434

Contoh:

  LD_LIBRARY_PATH=/usr/local/share/OpenCV/java:$LD_LIBRARY_PATH \
  java -jar sotavisiontest.jar http://192.168.11.5:11434


Kalau pakai "send_all" (seluruh project di-transfer, compile di robot):

  cd ~/SotaSample/SotaVisionTest
  mkdir -p bin

  javac -source 8 -target 8 \
    -cp ".:../lib/*:/home/vstone/lib/*:/usr/local/share/OpenCV/java/opencv-310.jar" \
    -d bin \
    src/jp/vstone/sotavisiontest/*.java

  LD_LIBRARY_PATH=/usr/local/share/OpenCV/java:$LD_LIBRARY_PATH \
  java -cp "bin:../lib/*:/home/vstone/lib/*:/home/vstone/vstonemagic/*:/usr/local/share/OpenCV/java/opencv-310.jar" \
  -Dfile.encoding=UTF-8 \
  jp.vstone.sotavisiontest.MainController \
  http://<LAPTOP_IP>:11434

--- STEP 4 (ALTERNATIF): Pakai script ---

  cd ~/SotaSample/SotaVisionTest
  chmod +x run_on_robot.sh
  ./run_on_robot.sh http://192.168.11.32:11434 (en/jp)


================================================================================
COPY-PASTE COMMAND DI TERMINAL ROBOT (QUICK REFERENCE)
================================================================================

Ganti <LAPTOP_IP> dengan IP laptop kamu (cek pakai: ipconfig)

--- Cara 1: Pakai JAR (setelah Ant build dari Eclipse) ---

cd ~/SotaSample && LD_LIBRARY_PATH=/usr/local/share/OpenCV/java:$LD_LIBRARY_PATH java -jar sotavisiontest.jar http://<LAPTOP_IP>:11434

--- Cara 2: Compile + Run di robot (setelah send_all dari Eclipse) ---

cd ~/SotaSample/SotaVisionTest && mkdir -p bin && javac -source 8 -target 8 -cp ".:../lib/*:/home/vstone/lib/*:/usr/local/share/OpenCV/java/opencv-310.jar" -d bin src/jp/vstone/sotavisiontest/*.java && LD_LIBRARY_PATH=/usr/local/share/OpenCV/java:$LD_LIBRARY_PATH java -cp "bin:../lib/*:/home/vstone/lib/*:/home/vstone/vstonemagic/*:/usr/local/share/OpenCV/java/opencv-310.jar" -Dfile.encoding=UTF-8 jp.vstone.sotavisiontest.MainController http://<LAPTOP_IP>:11434

--- Cara 3: Pakai script ---

cd ~/SotaSample/SotaVisionTest && chmod +x run_on_robot.sh && ./run_on_robot.sh http://<LAPTOP_IP>:11434


================================================================================
ANT BUILD TARGETS (di Eclipse)
================================================================================

File: send.xml (klik kanan -> Run As -> Ant Build...)

Target yang tersedia untuk SotaVisionTest:

  jp.vstone.sotavisiontest.MainController
      Compile + buat JAR + kirim ke robot
      Ini cara TERCEPAT untuk deploy

  compile_visiontest
      Compile saja (tanpa buat JAR, tanpa kirim)

  makeJar_visiontest
      Compile + buat JAR (tanpa kirim)

  send / send_all
      Kirim seluruh project SotaSample ke robot


================================================================================
EXPECTED OUTPUT
================================================================================

Kalau berhasil, output di terminal robot seperti ini:

  ========================================================
    Sota Social Interaction System — SotaVisionTest
  ========================================================

  [MainController] Initializing subsystems...
  [MainController] Robot connected. Firmware: xxx
  [FaceManager] Initialized
  [FaceManager] Camera initialized with face search + age/sex detection enabled
  [SpeechManager] Initialized
  [MemoryManager] Loaded 0 profiles from ./data/user_profiles.json
  [LlamaClient] ready -> http://192.168.11.5:11434 / llama3.2:3b
  [MainController] All subsystems initialized.
  [MainController] Entering main loop. Press Ctrl+C to stop.
  [MainController] State: IDLE — waiting for face...

Lalu ketika ada wajah terdeteksi:

  [FaceManager] DetectFinish: face detected, faceNum=1
  [MainController] Face detected! Transitioning to FACE_DETECTED
  [MainController] --- RECOGNIZING ---
  ...


================================================================================
DATA PERSISTENCE
================================================================================

Profil user disimpan di:

  SotaVisionTest/data/user_profiles.json

Format:
  [
    {
      "userId": "user_1740000000000_0",
      "name": "Taro",
      "estimatedAge": 25,
      "gender": "male",
      "interactionCount": 3,
      "lastInteractionTime": 1740000000000,
      "socialState": "acquaintance",
      "shortMemorySummary": "Taro likes programming and robotics."
    }
  ]

Untuk mengambil data dari robot ke laptop (via terminal):

  scp root@<ROBOT_IP>:~/SotaSample/SotaVisionTest/data/user_profiles.json C:\Users\interact-ai-001\eclipse-workspace\SotaSample\SotaVisionTest\data\

Atau kalau pakai JAR (data tersimpan di ~/SotaSample/data/):

  scp root@<ROBOT_IP>:~/SotaSample/data/user_profiles.json C:\Users\interact-ai-001\eclipse-workspace\SotaSample\SotaVisionTest\data\


================================================================================
TROUBLESHOOTING
================================================================================

PROBLEM: "FATAL: Cannot connect to robot"
  -> Pastikan Sota menyala dan kabel/wifi terhubung
  -> Cek apakah VSMD service jalan: ps aux | grep vsmd

PROBLEM: "ERROR: Failed to initialize camera"
  -> Cek camera: ls /dev/video*
  -> Pastikan tidak ada program lain yang pakai camera

PROBLEM: "[ERROR] ConnectException: Connection refused" (LLM)
  -> Ollama belum jalan di laptop. Jalankan: ollama serve
  -> Cek IP laptop benar
  -> Cek firewall: port 11434 harus terbuka
  -> Test dari robot: curl http://<LAPTOP_IP>:11434/api/tags

PROBLEM: "UnsatisfiedLinkError" (OpenCV)
  -> Set LD_LIBRARY_PATH:
     export LD_LIBRARY_PATH=/usr/local/share/OpenCV/java:$LD_LIBRARY_PATH

PROBLEM: Compile error "cannot find symbol"
  -> Pastikan semua JAR ada di ../lib/ dan /home/vstone/lib/
  -> Cek: ls ../lib/*.jar dan ls /home/vstone/lib/*.jar

PROBLEM: Speech recognition tidak berfungsi
  -> Pastikan SRClientHelper.jar ada di classpath
  -> Cek microphone: arecord -l

PROBLEM: Face detection lambat / tidak akurat
  -> Pastikan cahaya cukup (Sota butuh pencahayaan yang baik)
  -> User harus menghadap camera secara frontal untuk registrasi


================================================================================
KONFIGURASI
================================================================================

Edit di MainController.java:

  DEFAULT_OLLAMA_URL    URL Ollama (default: http://localhost:11434)
  MODEL_NAME            Model LLaMA (default: llama3.2:3b)
  MAX_PREDICT           Max token per respons (default: 80)
  MAX_CONVERSATION_TURNS  Max turn percakapan (default: 5)
  COOLDOWN_MS           Cooldown sebelum IDLE lagi (default: 3000ms)

Edit di FaceManager.java:

  POLL_INTERVAL_MS      Interval polling camera (default: 300ms)
  DETECT_THRESHOLD      Deteksi berturut-turut sebelum trigger (default: 3)

Edit di SocialState.java (transisi):

  ACQUAINTANCE          Min 1 interaksi
  FRIENDLY              Min 5 interaksi
  CLOSE                 Min 15 interaksi


================================================================================
TEKNOLOGI
================================================================================

  Robot       : Vstone Sota (ARM Linux / Edison)
  Bahasa      : Java 1.8
  Face detect : Sota SDK (jp.vstone.camera.*)
  TTS         : TextToSpeechSota (cloud-based)
  STT         : SpeechRecog (jp.vstone.sotatalk.*)
  LLM         : LLaMA 3.2 3B via Ollama (laptop, HTTP REST API)
  Persistence : Manual JSON (tanpa Gson / library eksternal)

================================================================================
