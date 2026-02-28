"""
test_server.py — Smoke test for the Whisper STT server.
Uses only urllib (stdlib) — no 'requests' library needed.
Can run without activating the venv.

Tests:
  1. GET /health — verify server is up
  2. POST /transcribe — send test_english.wav, verify transcription
"""

import os
import sys
import json

# Python 2/3 compat (though we target Python 3.10+)
try:
    from urllib.request import Request, urlopen
    from urllib.error import HTTPError, URLError
except ImportError:
    from urllib2 import Request, urlopen, HTTPError, URLError

SERVER_URL = "http://localhost:5050"
AUDIO_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "audio")


def check_health():
    """GET /health"""
    print("[1] GET /health ...")
    try:
        resp = urlopen(SERVER_URL + "/health", timeout=5)
        body = resp.read().decode("utf-8")
        data = json.loads(body)
        print("    Status : {}".format(resp.getcode()))
        print("    Model  : {}".format(data.get("model", "?")))
        print("    Device : {}".format(data.get("device", "?")))
        print("    [OK]")
        return True
    except HTTPError as e:
        print("    [FAIL] HTTP {}".format(e.code))
        return False
    except URLError as e:
        print("    [FAIL] Cannot connect: {}".format(e.reason))
        print("    Is the server running? Start with: start_server.bat")
        return False
    except Exception as e:
        print("    [FAIL] {}".format(str(e)))
        return False


def transcribe_file(wav_path):
    """POST /transcribe with multipart/form-data using urllib."""
    filename = os.path.basename(wav_path)
    print("\n[2] POST /transcribe -> {}".format(filename))

    # Read WAV bytes
    with open(wav_path, "rb") as f:
        wav_bytes = f.read()
    print("    File size: {:.1f} KB".format(len(wav_bytes) / 1024.0))

    # Build multipart/form-data body manually
    boundary = "----PythonTestBoundary12345"
    body = b""
    body += ("--" + boundary + "\r\n").encode("ascii")
    body += ("Content-Disposition: form-data; name=\"audio\"; "
             "filename=\"" + filename + "\"\r\n").encode("ascii")
    body += b"Content-Type: audio/wav\r\n"
    body += b"\r\n"
    body += wav_bytes
    body += ("\r\n--" + boundary + "--\r\n").encode("ascii")

    req = Request(
        SERVER_URL + "/transcribe",
        data=body,
        method="POST"
    )
    req.add_header("Content-Type", "multipart/form-data; boundary=" + boundary)
    req.add_header("Content-Length", str(len(body)))

    try:
        resp = urlopen(req, timeout=60)
        result_str = resp.read().decode("utf-8")
        data = json.loads(result_str)

        print("    OK       : {}".format(data.get("ok", False)))
        print("    Language : {}".format(data.get("language", "?")))
        print("    Text     : {}".format(data.get("text", "")))
        print("    Time     : {} ms".format(data.get("processing_ms", "?")))

        if data.get("ok") and data.get("text", "").strip():
            print("    [OK] Transcription successful!")
            return True
        else:
            print("    [FAIL] Empty or error response")
            return False

    except HTTPError as e:
        body = e.read().decode("utf-8") if e.fp else ""
        print("    [FAIL] HTTP {}: {}".format(e.code, body))
        return False
    except URLError as e:
        print("    [FAIL] Connection error: {}".format(e.reason))
        return False
    except Exception as e:
        print("    [FAIL] {}".format(str(e)))
        return False


def main():
    print("=" * 55)
    print("  Whisper Server Smoke Test")
    print("  Target: {}".format(SERVER_URL))
    print("=" * 55)
    print()

    # Test 1: Health check
    if not check_health():
        print("\n[ABORT] Server not responding. Run start_server.bat first.")
        sys.exit(1)

    # Test 2: Transcribe English
    wav = os.path.join(AUDIO_DIR, "test_english.wav")
    if not os.path.exists(wav):
        print("\n[WARN] test_english.wav not found in audio/")
        print("       Run: python generate_test_audio.py")
        sys.exit(1)

    ok = transcribe_file(wav)

    # Test 3: Transcribe Japanese (if available)
    wav_ja = os.path.join(AUDIO_DIR, "test_japanese.wav")
    if os.path.exists(wav_ja):
        print()
        ok_ja = transcribe_file(wav_ja)
    else:
        ok_ja = True  # skip

    print()
    print("=" * 55)
    if ok and ok_ja:
        print("  ALL TESTS PASSED!")
        print("  Server is ready for Sota integration.")
    else:
        print("  Some tests failed. Check server logs.")
    print("=" * 55)


if __name__ == "__main__":
    main()
