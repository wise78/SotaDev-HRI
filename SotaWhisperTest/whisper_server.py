"""
whisper_server.py — Flask HTTP server for Whisper STT.

Endpoints:
  GET  /health      -> {"status":"ok","model":"base","device":"cuda"}
  POST /transcribe  -> multipart form with 'audio' file field
                       optional form field 'language' (e.g. "ja", "en")
                       optional form field 'translate' ("true" to also get English translation)
                       returns {"ok":true,"text":"...","text_en":"...","language":"ja","processing_ms":1234}

Model is loaded once at startup (not per-request).
Listens on 0.0.0.0:5050 so Sota can reach it over WiFi.
"""

import whisper
import torch
import flask
import tempfile
import os
import time

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
        "device": device
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


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=PORT, debug=False)
