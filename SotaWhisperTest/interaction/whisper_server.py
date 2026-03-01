"""
whisper_server.py — Flask HTTP server for Whisper STT + DeepFace ethnicity detection.

Endpoints:
  GET  /health         -> {"status":"ok","model":"base","device":"cuda","deepface":true/false}
  POST /transcribe     -> multipart form with 'audio' file field
                          optional form field 'language' (e.g. "ja", "en")
                          optional form field 'translate' ("true" to also get English translation)
                          returns {"ok":true,"text":"...","text_en":"...","language":"ja","processing_ms":1234}
  POST /analyze_face   -> multipart form with 'image' file field (JPEG)
                          returns {"ok":true,"dominant_race":"asian","confidence":87,"all_races":{...}}

Models are loaded once at startup (not per-request).
Listens on 0.0.0.0:5050 so Sota can reach it over WiFi.
"""

import whisper
import torch
import flask
import tempfile
import os
import time
import numpy as np
from PIL import Image

# --- DeepFace (optional) ---
try:
    from deepface import DeepFace
    DEEPFACE_AVAILABLE = True
    print("[Server] DeepFace loaded — ethnicity detection enabled")
except ImportError:
    DEEPFACE_AVAILABLE = False
    print("[Server] DeepFace not available — /analyze_face disabled")
    print("[Server] Install with: pip install deepface tf-keras")

# --- Configuration ---
WHISPER_MODEL = "base"   # Change to "small" for better accuracy
PORT = 5050

# --- Load model at startup ---
print("[Whisper Server] Loading model '{}'...".format(WHISPER_MODEL))
device = "cuda" if torch.cuda.is_available() else "cpu"
print("[Whisper Server] Device: {}".format(device))
if device == "cpu":
    print("[Whisper Server] WARNING: Running on CPU! Transcription will be slow.")
    print("[Whisper Server] Check CUDA installation if you have an NVIDIA GPU.")

model = whisper.load_model(WHISPER_MODEL, device=device)
print("[Whisper Server] Model ready!")
print("[Whisper Server] Starting on port {}...".format(PORT))

app = flask.Flask(__name__)


@app.route("/health", methods=["GET"])
def health():
    return flask.jsonify({
        "status": "ok",
        "model": WHISPER_MODEL,
        "device": device,
        "deepface": DEEPFACE_AVAILABLE
    })


@app.route("/transcribe", methods=["POST"])
def transcribe():
    # Check for audio file in multipart form
    if "audio" not in flask.request.files:
        return flask.jsonify({
            "ok": False,
            "text": "",
            "language": "",
            "error": "No 'audio' field in multipart form. "
                     "Send as: curl -F 'audio=@file.wav' /transcribe"
        }), 400

    audio_file = flask.request.files["audio"]

    # Check file is not empty
    audio_file.seek(0, 2)  # seek to end
    size = audio_file.tell()
    audio_file.seek(0)     # seek back to start

    if size == 0:
        return flask.jsonify({
            "ok": False,
            "text": "",
            "language": "",
            "error": "Audio file is empty (0 bytes)"
        }), 400

    # Optional language parameter
    language = flask.request.form.get("language", None)
    if language == "":
        language = None

    # Optional translate parameter: if "true", also return English translation
    do_translate = flask.request.form.get("translate", "").lower() in ("true", "1", "yes")

    # Save to temp file (Whisper needs a file path, not a stream)
    tmp = tempfile.NamedTemporaryFile(delete=False, suffix=".wav")
    tmp_path = tmp.name
    tmp.close()

    try:
        audio_file.save(tmp_path)
        print("[Whisper Server] Transcribing {} ({:.1f} KB, lang={}, translate={})...".format(
            audio_file.filename or "unknown",
            size / 1024.0,
            language or "auto",
            do_translate
        ))

        t0 = time.time()

        # Step 1: Transcribe (original language)
        if language:
            result = model.transcribe(tmp_path, language=language)
        else:
            result = model.transcribe(tmp_path)

        text = result.get("text", "").strip()
        lang = result.get("language", "")

        # Step 2: Translate to English (if requested and not already English)
        text_en = text  # default: same as original
        if do_translate and lang and lang != "en":
            result_en = model.transcribe(tmp_path, task="translate")
            text_en = result_en.get("text", "").strip()

        t1 = time.time()
        processing_ms = int((t1 - t0) * 1000)

        print("[Whisper Server] Result: lang={}, text='{}', text_en='{}', {}ms".format(
            lang, text[:60], text_en[:60], processing_ms
        ))

        return flask.jsonify({
            "ok": True,
            "text": text,
            "text_en": text_en,
            "language": lang,
            "processing_ms": processing_ms
        })

    except Exception as e:
        print("[Whisper Server] ERROR: {}".format(str(e)))
        return flask.jsonify({
            "ok": False,
            "text": "",
            "language": "",
            "error": str(e)
        }), 500

    finally:
        try:
            os.unlink(tmp_path)
        except Exception:
            pass


@app.route("/analyze_face", methods=["POST"])
def analyze_face():
    """Receive a JPEG image, run DeepFace race detection, return dominant race."""
    if not DEEPFACE_AVAILABLE:
        return flask.jsonify({"ok": False, "error": "DeepFace not installed"}), 503

    if "image" not in flask.request.files:
        return flask.jsonify({
            "ok": False,
            "error": "No 'image' field in multipart form. "
                     "Send as: curl -F 'image=@face.jpg' /analyze_face"
        }), 400

    image_file = flask.request.files["image"]

    tmp = tempfile.NamedTemporaryFile(delete=False, suffix=".jpg")
    tmp_path = tmp.name
    tmp.close()

    try:
        image_file.save(tmp_path)
        img = Image.open(tmp_path).convert("RGB")
        face_arr = np.array(img)

        print("[DeepFace] Analyzing face ({} x {})...".format(img.width, img.height))
        t0 = time.time()

        result = DeepFace.analyze(
            face_arr,
            actions=["race"],
            enforce_detection=False,
            silent=True
        )
        t1 = time.time()
        processing_ms = int((t1 - t0) * 1000)

        if isinstance(result, list):
            result = result[0]

        dominant = result.get("dominant_race", "")
        scores = result.get("race", {})
        confidence = int(scores.get(dominant, 0))

        print("[DeepFace] Result: {} ({}%), {}ms".format(dominant, confidence, processing_ms))

        return flask.jsonify({
            "ok": True,
            "dominant_race": dominant,
            "confidence": confidence,
            "all_races": scores,
            "processing_ms": processing_ms
        })

    except Exception as e:
        print("[DeepFace] ERROR: {}".format(str(e)))
        return flask.jsonify({"ok": False, "error": str(e)}), 500

    finally:
        try:
            os.unlink(tmp_path)
        except Exception:
            pass


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=PORT, debug=False)
