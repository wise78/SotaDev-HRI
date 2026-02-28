"""
generate_test_audio.py — Create test WAV files using Windows SAPI (pyttsx3).
No internet, no ffmpeg, no external tools needed.

Creates:
  audio/test_english.wav  — English sentence
  audio/test_japanese.wav — Japanese sentence

Note: Japanese audio requires a Japanese SAPI voice to be installed on Windows.
      If not installed, the default English voice will be used (Whisper can still
      transcribe it, but language detection may not detect Japanese).
      To install: Settings -> Time & Language -> Language -> Add Japanese -> Speech
"""

import pyttsx3
import os
import sys
import io
import subprocess

# Fix console encoding for Japanese characters on Windows
if sys.stdout.encoding != 'utf-8':
    sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8', errors='replace')
    sys.stderr = io.TextIOWrapper(sys.stderr.buffer, encoding='utf-8', errors='replace')

AUDIO_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "audio")


def ensure_audio_dir():
    if not os.path.exists(AUDIO_DIR):
        os.makedirs(AUDIO_DIR)
    print("[OK] Audio directory: " + AUDIO_DIR)


def find_voice(voices, lang_hint):
    """Find a voice matching the language hint."""
    for v in voices:
        name_lower = v.name.lower()
        if lang_hint == "ja":
            if "japanese" in name_lower or "haruka" in name_lower or "ayumi" in name_lower:
                return v.id
        elif lang_hint == "en":
            if "english" in name_lower or "david" in name_lower or "zira" in name_lower:
                return v.id
    return None


def generate_single_wav(text, filename, lang_hint):
    """Generate one WAV file in a subprocess to avoid pyttsx3 hanging."""
    output_path = os.path.join(AUDIO_DIR, filename)

    # Run generation in a subprocess to avoid pyttsx3 engine reuse hang
    script = '''
import pyttsx3, sys
engine = pyttsx3.init()
voices = engine.getProperty('voices')
selected = None
for v in voices:
    name = v.name.lower()
    if "{lang}" == "ja" and ("japanese" in name or "haruka" in name or "ayumi" in name):
        selected = v.id
        break
    elif "{lang}" == "en" and ("english" in name or "david" in name or "zira" in name):
        selected = v.id
        break
if selected:
    engine.setProperty('voice', selected)
    print("VOICE_FOUND")
else:
    print("VOICE_DEFAULT")
engine.setProperty('rate', 150)
engine.save_to_file("""{text_escaped}""", r"{output}")
engine.runAndWait()
engine.stop()
print("DONE")
'''.format(
        lang=lang_hint,
        text_escaped=text.replace('"', '\\"'),
        output=output_path.replace('\\', '\\\\')
    )

    print("[*] Generating: {} (lang={})".format(filename, lang_hint))
    print("    Text: {}".format(text))

    try:
        result = subprocess.run(
            [sys.executable, "-c", script],
            capture_output=True, text=True, timeout=30,
            encoding='utf-8', errors='replace'
        )
        stdout = result.stdout.strip()

        if "VOICE_FOUND" in stdout:
            print("    Voice: found matching {} voice".format(lang_hint))
        elif "VOICE_DEFAULT" in stdout:
            print("    [WARN] No {} voice found, using default".format(lang_hint))

        if "DONE" in stdout and os.path.exists(output_path):
            size_kb = os.path.getsize(output_path) / 1024.0
            print("    [OK] Created: {} ({:.1f} KB)".format(output_path, size_kb))
            return output_path
        else:
            print("    [FAIL] Generation failed")
            if result.stderr:
                print("    stderr: {}".format(result.stderr[:200]))
            return None

    except subprocess.TimeoutExpired:
        print("    [FAIL] Timed out after 30s")
        return None
    except Exception as e:
        print("    [FAIL] {}".format(str(e)))
        return None


def main():
    print("=" * 55)
    print("  Generate Test Audio (pyttsx3 / Windows SAPI)")
    print("=" * 55)
    print()

    ensure_audio_dir()

    # Show available voices
    engine = pyttsx3.init()
    voices = engine.getProperty('voices')
    print("  Available SAPI voices:")
    for i, v in enumerate(voices):
        print("    [{}] {}".format(i, v.name))
    print()
    del engine

    # Generate English test audio
    en_text = "Hello, my name is Sota. How are you today? I am a social robot."
    en_path = generate_single_wav(en_text, "test_english.wav", "en")

    print()

    # Generate Japanese test audio
    ja_text = "konnichiwa, watashi no namae wa souta desu. kyou no choushi wa dou desu ka."
    ja_text_display = "こんにちは、私の名前はソータです。今日の調子はどうですか。"

    # Try with actual Japanese text first (needs Haruka voice)
    ja_path = generate_single_wav(ja_text_display, "test_japanese.wav", "ja")

    # If Japanese failed, try with romaji (will still work for testing Whisper)
    if ja_path is None:
        print("    Retrying with romaji text...")
        ja_path = generate_single_wav(ja_text, "test_japanese.wav", "ja")

    print()
    print("=" * 55)
    if en_path and ja_path:
        print("  [OK] Both audio files created successfully!")
    elif en_path:
        print("  [PARTIAL] English OK, Japanese may need manual recording.")
    else:
        print("  [FAIL] Audio generation failed.")
    print()
    print("  Next: python test_whisper_local.py")
    print("=" * 55)


if __name__ == "__main__":
    main()
