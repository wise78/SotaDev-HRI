# VisionTestServer — Sota Camera + DeepFace Ethnicity Detection

Tool untuk mengetes fitur computer vision Sota robot.
Terdiri dari Java TCP server (jalan di robot) dan Python GUI client (jalan di laptop).
Ethnicity/race detection dilakukan di sisi laptop menggunakan **DeepFace**.

## Fitur
- **Face Tracking** — Live camera feed dengan bounding box di wajah
- **PFAGR** — Estimasi umur (Age) dan jenis kelamin (Gender) via OMRON SDK
- **Ethnicity Detection** — Deteksi ras/etnis via DeepFace (laptop-side, otomatis)
- **Photo Capture** — Ambil foto resolusi tinggi

## Arsitektur

```
┌─────────────────────┐        TCP:8889        ┌──────────────────────────┐
│   Sota Robot        │ ◄──────────────────────►│   Laptop                 │
│   VisionTestServer  │   commands & frames     │   vision_test_gui.py     │
│   (Java)            │                         │   (Python 3.12 + tkinter)│
└─────────────────────┘                         │                          │
                                                │   DeepFace (TensorFlow)  │
                                                │   → Race analysis tiap   │
                                                │     5 detik dari face    │
                                                │     crop bounding box    │
                                                └──────────────────────────┘
```

## File Structure

```
vision_test_server/
├── compile.bat          # Compile VisionTestServer.java (Windows)
├── run_robot.sh         # Jalankan server di robot Sota
├── start_gui.bat        # Jalankan Python GUI client (Windows, Python 3.12)
├── vision_test_gui.py   # Python GUI client + DeepFace race analysis
├── requirements.txt     # Python dependencies
├── photos/              # Output foto yang di-capture
└── README.md            # File ini

Java source: ../src/jp/vstone/sotavisiontest/VisionTestServer.java
```

## Cara Pakai

### 1. Deploy JAR ke Robot (dari Eclipse)

Dari Eclipse: klik kanan `send.xml` → Run As → Ant Build → pilih target:
**`jp.vstone.sotavisiontest.VisionTestServer`**

Ini akan compile dan kirim `visiontestserver.jar` ke robot via SCP.

### 2. Jalankan Server di Robot (SSH)

```bash
ssh root@192.168.11.30     # password: edison00
cd ~/SotaSample
LD_LIBRARY_PATH=/usr/local/share/OpenCV/java java -jar visiontestserver.jar
```

Server akan listen di port 8889. Output:
```
[VisionTestServer] ========================================
[VisionTestServer]   Sota Vision Test Server
[VisionTestServer] ========================================
[VisionTestServer] Robot connected.
[VisionTestServer] Camera initialized.
[VisionTestServer] Waiting for client on port 8889...
```

### 3. Jalankan GUI di Laptop

**Lokasi:** `SotaVisionTest\vision_test_server\`

**Cara 1 — Pakai bat file (recommended):**
```
cd C:\Users\interact-ai-001\eclipse-workspace\SotaSample\SotaVisionTest\vision_test_server
start_gui.bat --ip 192.168.11.30 --port 8889
```

**Cara 2 — Manual di PowerShell:**
```powershell
cd C:\Users\interact-ai-001\eclipse-workspace\SotaSample\SotaVisionTest\vision_test_server
$env:PYTHONIOENCODING="utf-8"
$env:TF_ENABLE_ONEDNN_OPTS="0"
$env:TF_CPP_MIN_LOG_LEVEL="2"
py -3.12 vision_test_gui.py --ip 192.168.11.30 --port 8889
```

> **Kenapa Python 3.12?** TensorFlow/DeepFace belum support Python 3.13+.
> Gunakan `py -3.12` untuk memilih versi yang benar.

### 4. Gunakan GUI

1. Masukkan IP robot (`192.168.11.30`) dan klik **Connect**
2. Klik **Start Face Tracking** untuk live camera feed
3. Klik **Enable PFAGR** untuk estimasi umur/gender dari robot
4. **Race/ethnicity otomatis terdeteksi** setiap 5 detik via DeepFace
5. Klik **Take Photo** untuk capture foto resolusi tinggi

### Info di GUI

| Label      | Sumber                | Keterangan                      |
|------------|----------------------|---------------------------------|
| Faces      | Robot (OMRON SDK)    | Jumlah wajah terdeteksi         |
| Smile      | Robot (OMRON SDK)    | Skor senyum (0-100)             |
| Age        | Robot (OMRON SDK)    | Estimasi umur                   |
| Gender     | Robot (OMRON SDK)    | Male/Female                     |
| **Race**   | **Laptop (DeepFace)**| **Otomatis tiap 5 detik**       |
| Pose       | Robot (OMRON SDK)    | Pitch/Yaw/Roll kepala           |

### DeepFace Race Categories

| Kategori         | Contoh                          |
|------------------|---------------------------------|
| Asian            | East Asian, Southeast Asian     |
| Indian           | South Asian                     |
| Black            | African descent                 |
| White            | European descent                |
| Middle Eastern   | Middle Eastern descent          |
| Latino Hispanic  | Latin American descent          |

## Troubleshooting

### TensorFlow warning saat startup
```
oneDNN custom operations are on...
This TensorFlow binary is optimized to use available CPU instructions...
```
**Ini bukan error.** Hanya info bahwa TensorFlow bisa dioptimasi lebih lanjut.
Untuk menyembunyikan, set environment variables sebelum jalankan:
```powershell
$env:TF_ENABLE_ONEDNN_OPTS="0"
$env:TF_CPP_MIN_LOG_LEVEL="2"
```

### `UnicodeEncodeError: 'charmap' codec can't encode character`
DeepFace logger pakai emoji yang tidak support di Windows cp1252.
Pastikan set: `$env:PYTHONIOENCODING="utf-8"` atau pakai `start_gui.bat`.

### Race selalu `waiting...`
- Pastikan face tracking aktif (Start Face Tracking)
- DeepFace butuh bounding box wajah dari server. Face harus terdeteksi dulu.
- Pertama kali butuh ~5-10 detik untuk load model TensorFlow.

### `Python 3.12 not found`
Install Python 3.12 dari: https://www.python.org/downloads/release/python-3120/
TensorFlow belum support Python 3.13/3.14.

## Protocol (TCP)

| Command          | Response                                           |
|------------------|----------------------------------------------------|
| START_TRACKING   | OK:TRACKING_STARTED                                |
| STOP_TRACKING    | OK:TRACKING_STOPPED                                |
| ENABLE_PFAGR     | OK:PFAGR_ENABLED                                   |
| DISABLE_PFAGR    | OK:PFAGR_DISABLED                                  |
| GET_FRAME        | FRAME + metadata JSON + JPEG bytes                 |
| TAKE_PHOTO       | PHOTO + JPEG bytes                                 |
| STATUS           | STATUS:{json}                                      |
| QUIT             | OK:BYE                                             |

## Dependencies

### Robot (Java)
- sotalib.jar, opencv-310.jar, jna-4.1.0.jar (sudah ada di `lib/`)

### Laptop (Python 3.12)
Install semua:
```
py -3.12 -m pip install -r requirements.txt
```

Atau manual:
```
py -3.12 -m pip install Pillow numpy deepface tf-keras
```

| Package    | Versi       | Ukuran   | Fungsi                      |
|------------|-------------|----------|-----------------------------|
| Pillow     | ≥9.0        | ~3 MB    | Image processing            |
| numpy      | ≥1.21       | ~20 MB   | Array operations            |
| deepface   | ≥0.0.80     | ~1 MB    | Face analysis framework     |
| tf-keras   | ≥2.16       | ~5 MB    | Keras wrapper               |
| tensorflow | (auto)      | ~600 MB  | ML backend (auto-installed) |

Model weights (auto-download on first run):
- `race_model_single_batch.h5` — 512 MB → `~/.deepface/weights/`
