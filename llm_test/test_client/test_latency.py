"""
test_latency.py
---------------
Automated latency benchmark for the Sota LLM pipeline.
Sends 10 predefined HRI compliance-scenario messages and measures:
  - Per-message latency (ms)
  - Min / Avg / Max latency
  - Estimated tokens per second (from Ollama response metadata)

Results are saved to: results/latency_log.txt
"""

import requests
import json
import time
import os
import datetime

OLLAMA_URL = "http://localhost:11434/api/chat"
MODEL_NAME = "llama3.2:3b"

RESULTS_DIR = os.path.join(
    os.path.dirname(os.path.abspath(__file__)),
    "..", "results"
)
LOG_FILE = os.path.join(RESULTS_DIR, "latency_log.txt")

# System prompt for benchmark (inline, no external file dependency)
SYSTEM_PROMPT = (
    "You are Sota, a small humanoid robot in an HRI compliance study. "
    "Keep all responses under 2 sentences. Be natural and concise."
)

# 10 realistic HRI compliance-scenario test messages
TEST_MESSAGES = [
    "Please hand me that object on the table.",
    "Can you step aside? I need to pass through.",
    "Follow my instructions carefully.",
    "I need you to do something for me right now.",
    "Stop what you are doing and look at me.",
    "Could you pick up that item and bring it here?",
    "Move to the left side of the room.",
    "I am going to give you a task. Are you ready?",
    "Please wait here until I come back.",
    "Can you help me with this task?",
]


def send_chat(user_message):
    """
    Send a single-turn streaming chat to Ollama and return a result dict.
    Measures both TTFT (time to first token) and total latency.
    Returns None on failure.
    """
    messages = [
        {"role": "system", "content": SYSTEM_PROMPT},
        {"role": "user", "content": user_message}
    ]
    payload = {
        "model": MODEL_NAME,
        "messages": messages,
        "stream": True,
        "options": {
            "num_predict": 60    # max tokens (~1-2 short sentences for HRI)
        }
    }

    start_time = time.time()
    first_token_time = None
    full_response = []
    eval_count = 0
    eval_duration_ns = 0

    try:
        response = requests.post(OLLAMA_URL, json=payload, timeout=120, stream=True)
        response.raise_for_status()

        for line in response.iter_lines():
            if not line:
                continue
            chunk = json.loads(line)
            token = chunk.get("message", {}).get("content", "")
            if token:
                if first_token_time is None:
                    first_token_time = time.time()
                full_response.append(token)
            if chunk.get("done"):
                eval_count = chunk.get("eval_count", 0)
                eval_duration_ns = chunk.get("eval_duration", 0)
                break

        total_ms = (time.time() - start_time) * 1000
        ttft_ms = (first_token_time - start_time) * 1000 if first_token_time else total_ms

        content = "".join(full_response).strip()
        tps = 0.0
        if eval_duration_ns > 0 and eval_count > 0:
            tps = eval_count / (eval_duration_ns / 1e9)

        return {
            "latency_ms": total_ms,
            "ttft_ms": ttft_ms,
            "response": content,
            "eval_tokens": eval_count,
            "tps": tps
        }

    except requests.exceptions.ConnectionError:
        print("  [ERROR] Cannot reach Ollama. Is it running?")
        return None
    except requests.exceptions.Timeout:
        print("  [ERROR] Request timed out.")
        return None
    except Exception as e:
        print("  [ERROR]", e)
        return None


def save_log(log_lines):
    """Append results to the latency log file."""
    os.makedirs(RESULTS_DIR, exist_ok=True)
    with open(LOG_FILE, "a", encoding="utf-8") as f:
        for line in log_lines:
            f.write(line + "\n")
    print("\n[Saved] Results written to:", LOG_FILE)


def main():
    print("=" * 60)
    print("  Sota LLM Latency Benchmark — llama3.2:3b")
    print("=" * 60)

    timestamp = datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    print("  Start time:", timestamp)
    print("  Messages  :", len(TEST_MESSAGES))
    print()

    # Warm-up request: loads model into VRAM before real benchmark starts.
    # The first request is always slow (cold start), this prevents it from
    # skewing the results.
    print("  [Warm-up] Loading model into GPU memory...")
    send_chat("Hello.")
    print("  [Warm-up] Done. Starting benchmark.\n")

    results = []
    log_lines = []
    log_lines.append("=" * 60)
    log_lines.append("Benchmark Run: " + timestamp)
    log_lines.append("Model: " + MODEL_NAME)
    log_lines.append("=" * 60)

    for i, message in enumerate(TEST_MESSAGES):
        print("  [{}/{}] Sending: {}".format(i + 1, len(TEST_MESSAGES), message))
        result = send_chat(message)

        if result is None:
            print("  [SKIP] Message failed, skipping.")
            log_lines.append("[{}] FAILED: {}".format(i + 1, message))
            continue

        latency = result["latency_ms"]
        ttft = result["ttft_ms"]
        tps = result["tps"]
        response_preview = result["response"][:80].replace("\n", " ")

        print("         TTFT    : {:.0f} ms  (perceived)".format(ttft))
        print("         Total   : {:.0f} ms".format(latency))
        print("         TPS     : {:.1f} tok/s".format(tps))
        print("         Response: {}...".format(response_preview) if len(result["response"]) > 80
              else "         Response: {}".format(response_preview))
        print()

        results.append(result)
        log_lines.append(
            "[{}] TTFT {:.0f}ms | Total {:.0f}ms | {:.1f} tok/s | Q: {} | A: {}".format(
                i + 1, ttft, latency, tps, message, result["response"]
            )
        )

    # Compute summary stats
    if results:
        latencies = [r["latency_ms"] for r in results]
        ttfts = [r["ttft_ms"] for r in results]
        tps_values = [r["tps"] for r in results if r["tps"] > 0]

        min_lat = min(latencies)
        max_lat = max(latencies)
        avg_lat = sum(latencies) / len(latencies)
        min_ttft = min(ttfts)
        max_ttft = max(ttfts)
        avg_ttft = sum(ttfts) / len(ttfts)
        avg_tps = sum(tps_values) / len(tps_values) if tps_values else 0.0

        summary = [
            "",
            "--- Summary ---",
            "  Completed    : {}/{}".format(len(results), len(TEST_MESSAGES)),
            "  TTFT  Min/Avg/Max: {:.0f} / {:.0f} / {:.0f} ms  (perceived)".format(
                min_ttft, avg_ttft, max_ttft),
            "  Total Min/Avg/Max: {:.0f} / {:.0f} / {:.0f} ms".format(
                min_lat, avg_lat, max_lat),
            "  Avg tok/sec  : {:.1f}".format(avg_tps),
            ""
        ]

        print("=" * 60)
        print("  RESULTS SUMMARY")
        print("=" * 60)
        for line in summary:
            print(" ", line)

        log_lines.extend(summary)
    else:
        print("[FAIL] No successful responses recorded.")
        log_lines.append("No successful responses.")

    save_log(log_lines)


if __name__ == "__main__":
    main()
