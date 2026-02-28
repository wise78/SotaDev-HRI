"""
check_ollama.py
---------------
Quick health check for the Sota LLM pipeline:
  - Verifies Ollama is running at localhost:11434
  - Checks that llama3.2:3b model is available
  - Prints basic model info and GPU status
"""

import requests
import json
import sys

OLLAMA_BASE_URL = "http://localhost:11434"
TARGET_MODEL = "llama3.2:3b"


def check_ollama_running():
    """Check if the Ollama server is reachable."""
    try:
        response = requests.get(OLLAMA_BASE_URL, timeout=5)
        print("[OK] Ollama server is running at", OLLAMA_BASE_URL)
        return True
    except requests.exceptions.ConnectionError:
        print("[FAIL] Cannot connect to Ollama at", OLLAMA_BASE_URL)
        print("       Make sure Ollama is running: start it with 'ollama serve'")
        return False
    except requests.exceptions.Timeout:
        print("[FAIL] Ollama server timed out.")
        return False


def get_available_models():
    """Retrieve list of locally available Ollama models."""
    try:
        response = requests.get(OLLAMA_BASE_URL + "/api/tags", timeout=10)
        response.raise_for_status()
        data = response.json()
        models = data.get("models", [])
        return models
    except Exception as e:
        print("[FAIL] Could not retrieve model list:", e)
        return []


def check_target_model(models):
    """Check if TARGET_MODEL is in the available models list."""
    model_names = [m.get("name", "") for m in models]
    for name in model_names:
        # Match exact name or name without tag
        if name == TARGET_MODEL or name.split(":")[0] == TARGET_MODEL.split(":")[0]:
            return True, name
    return False, None


def print_model_info(models, matched_name):
    """Print info for the matched model."""
    for m in models:
        if m.get("name", "") == matched_name:
            details = m.get("details", {})
            size_gb = m.get("size", 0) / (1024 ** 3)
            print("  Model name  :", m.get("name"))
            print("  Size        : {:.2f} GB".format(size_gb))
            print("  Family      :", details.get("family", "unknown"))
            print("  Parameters  :", details.get("parameter_size", "unknown"))
            print("  Quantization:", details.get("quantization_level", "unknown"))
            break


def check_gpu_via_ollama():
    """
    Send a minimal generation request and check if Ollama reports GPU usage.
    Ollama itself handles GPU scheduling — if CUDA is available, it uses it automatically.
    """
    print("\n[INFO] Sending a test prompt to check response...")
    payload = {
        "model": TARGET_MODEL,
        "prompt": "Say: test",
        "stream": False,
        "options": {"num_predict": 5}
    }
    try:
        response = requests.post(
            OLLAMA_BASE_URL + "/api/generate",
            json=payload,
            timeout=60
        )
        response.raise_for_status()
        data = response.json()
        print("[OK] Test prompt responded successfully.")

        # Ollama includes eval_duration in nanoseconds
        eval_duration_ns = data.get("eval_duration", 0)
        eval_count = data.get("eval_count", 1)
        if eval_duration_ns > 0 and eval_count > 0:
            tps = eval_count / (eval_duration_ns / 1e9)
            print("     Tokens/sec (rough): {:.1f}".format(tps))
            if tps > 20:
                print("     [LIKELY GPU] Speed suggests GPU acceleration is active.")
            else:
                print("     [POSSIBLE CPU] Speed is low — GPU may not be active.")
        return True
    except Exception as e:
        print("[FAIL] Test prompt failed:", e)
        return False


def main():
    print("=" * 55)
    print("  Sota LLM Pipeline — Ollama Health Check")
    print("=" * 55)

    # Step 1: Check server
    if not check_ollama_running():
        sys.exit(1)

    # Step 2: List models
    print("\n[INFO] Fetching available models...")
    models = get_available_models()
    if not models:
        print("[WARN] No models found. Pull the model with:")
        print("       ollama pull", TARGET_MODEL)
        sys.exit(1)

    print("  Available models:")
    for m in models:
        print("   -", m.get("name", "unknown"))

    # Step 3: Check target model
    found, matched_name = check_target_model(models)
    if found:
        print("\n[OK] Target model '{}' is available.".format(TARGET_MODEL))
        print_model_info(models, matched_name)
    else:
        print("\n[FAIL] Model '{}' not found locally.".format(TARGET_MODEL))
        print("       Run: ollama pull", TARGET_MODEL)
        sys.exit(1)

    # Step 4: Quick generation test + speed estimate
    check_gpu_via_ollama()

    print("\n" + "=" * 55)
    print("  Health check complete. Ready to run test_chat.py")
    print("=" * 55)


if __name__ == "__main__":
    main()
