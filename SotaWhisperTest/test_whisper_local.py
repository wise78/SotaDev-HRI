"""
test_whisper_local.py — Verify Whisper runs on GPU and transcribes correctly.

Tests:
  (a) GPU detection + model loading
  (b) English WAV transcription
  (c) Japanese WAV transcription
  (d) Auto language detection (no --language flag)

Model is configurable via WHISPER_MODEL constant below.
"""

import sys
import os
import time

# --- Configuration ---
WHISPER_MODEL = "base"   # Change to "small" for better accuracy (slower)
AUDIO_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "audio")


def test_gpu():
    """Test (a): CUDA available + model loads on GPU."""
    print("[TEST a] GPU detection and model loading...")

    import torch
    print("  PyTorch version : {}".format(torch.__version__))
    print("  CUDA available  : {}".format(torch.cuda.is_available()))

    if not torch.cuda.is_available():
        print("  [FAIL] CUDA not available!")
        print("  Check: nvidia-smi works? PyTorch installed with CUDA?")
        print("  Reinstall: pip install torch torchaudio --index-url https://download.pytorch.org/whl/cu121")
        return None

    gpu_name = torch.cuda.get_device_name(0)
    vram_mb = torch.cuda.get_device_properties(0).total_memory / (1024 * 1024)
    print("  GPU             : {}".format(gpu_name))
    print("  VRAM            : {:.0f} MB".format(vram_mb))

    import whisper
    print("  Loading Whisper model '{}'...".format(WHISPER_MODEL))
    t0 = time.time()
    model = whisper.load_model(WHISPER_MODEL, device="cuda")
    t1 = time.time()
    print("  Model loaded in  : {:.1f}s".format(t1 - t0))
    print("  [OK] Model on GPU")

    return model


def test_english(model):
    """Test (b): English WAV transcription with language='en'."""
    print("\n[TEST b] English transcription...")

    wav_path = os.path.join(AUDIO_DIR, "test_english.wav")
    if not os.path.exists(wav_path):
        print("  [SKIP] test_english.wav not found. Run generate_test_audio.py first.")
        return False

    t0 = time.time()
    result = model.transcribe(wav_path, language="en")
    elapsed = time.time() - t0

    text = result.get("text", "").strip()
    lang = result.get("language", "?")
    print("  Language : {}".format(lang))
    print("  Text     : {}".format(text))
    print("  Time     : {:.2f}s".format(elapsed))

    ok = len(text) > 0
    print("  [{}]".format("OK" if ok else "FAIL"))
    return ok


def test_japanese(model):
    """Test (c): Japanese WAV transcription with language='ja'."""
    print("\n[TEST c] Japanese transcription...")

    wav_path = os.path.join(AUDIO_DIR, "test_japanese.wav")
    if not os.path.exists(wav_path):
        print("  [SKIP] test_japanese.wav not found. Run generate_test_audio.py first.")
        return False

    t0 = time.time()
    result = model.transcribe(wav_path, language="ja")
    elapsed = time.time() - t0

    text = result.get("text", "").strip()
    lang = result.get("language", "?")
    print("  Language : {}".format(lang))
    print("  Text     : {}".format(text))
    print("  Time     : {:.2f}s".format(elapsed))

    ok = len(text) > 0
    print("  [{}]".format("OK" if ok else "FAIL"))
    return ok


def test_auto_detect(model):
    """Test (d): Auto language detection (no language= parameter)."""
    print("\n[TEST d] Auto language detection...")

    wav_path = os.path.join(AUDIO_DIR, "test_english.wav")
    if not os.path.exists(wav_path):
        print("  [SKIP] test_english.wav not found.")
        return False

    t0 = time.time()
    result = model.transcribe(wav_path)  # No language= -> auto-detect
    elapsed = time.time() - t0

    detected = result.get("language", "unknown")
    text = result.get("text", "").strip()
    print("  Detected : {}".format(detected))
    print("  Text     : {}".format(text))
    print("  Time     : {:.2f}s".format(elapsed))

    ok = len(text) > 0 and detected != "unknown"
    print("  [{}] Detected language: {}".format("OK" if ok else "WARN", detected))
    return ok


def main():
    print("=" * 60)
    print("  Whisper STT Local Tests")
    print("  Model: {}".format(WHISPER_MODEL))
    print("  Audio: {}".format(AUDIO_DIR))
    print("=" * 60)

    # Test (a): GPU + model
    model = test_gpu()
    if model is None:
        print("\n[ABORT] Cannot proceed without GPU. Fix CUDA setup first.")
        sys.exit(1)

    # Test (b): English
    b = test_english(model)

    # Test (c): Japanese
    c = test_japanese(model)

    # Test (d): Auto-detect
    d = test_auto_detect(model)

    # Summary
    print("\n" + "=" * 60)
    results = {"GPU": True, "English": b, "Japanese": c, "AutoDetect": d}
    all_ok = all(results.values())
    for name, ok in results.items():
        print("  {} : {}".format(name.ljust(12), "PASS" if ok else "FAIL/SKIP"))
    print()
    if all_ok:
        print("  ALL TESTS PASSED!")
    else:
        print("  Some tests failed or were skipped.")
    print("  Next: start_server.bat -> python test_server.py")
    print("=" * 60)


if __name__ == "__main__":
    main()
