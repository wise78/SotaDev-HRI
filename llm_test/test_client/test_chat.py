"""
test_chat.py
------------
Interactive terminal chat loop with llama3.2:3b via Ollama API.
Simulates the Sota robot's HRI compliance conversation pipeline.

Commands:
  quit  — Exit the chat
  reset — Clear conversation history
"""

import requests
import json
import time
import os
import sys

OLLAMA_URL = "http://localhost:11434/api/chat"
MODEL_NAME = "llama3.2:3b"
MAX_HISTORY_TURNS = 10  # Max user+assistant pairs kept in memory

# Load system prompt from file
PROMPT_FILE = os.path.join(
    os.path.dirname(os.path.abspath(__file__)),
    "..", "prompts", "sota_compliance_system_prompt.txt"
)


def load_system_prompt():
    """Read the system prompt from file. Fall back to inline default if missing."""
    if os.path.isfile(PROMPT_FILE):
        with open(PROMPT_FILE, "r", encoding="utf-8") as f:
            return f.read().strip()
    # Fallback inline prompt
    return (
        "You are Sota, a small humanoid robot in an HRI compliance study. "
        "Keep all responses under 2 sentences. Be natural and concise."
    )


def build_messages(history, system_prompt):
    """
    Construct the messages list for the Ollama /api/chat endpoint.
    Format: [system, user, assistant, user, assistant, ...]
    """
    messages = [{"role": "system", "content": system_prompt}]
    messages.extend(history)
    return messages


def trim_history(history):
    """
    Keep at most MAX_HISTORY_TURNS user+assistant pairs (2 messages each).
    Removes the oldest pairs from the front.
    """
    max_messages = MAX_HISTORY_TURNS * 2
    if len(history) > max_messages:
        history = history[-max_messages:]
    return history


def send_message(messages):
    """
    Send a streaming chat request to Ollama.
    Prints tokens to screen as they arrive.
    Returns (response_text, total_ms, ttft_ms) or (None, None, None) on failure.
    """
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
                print(token, end="", flush=True)
                full_response.append(token)
            if chunk.get("done"):
                break

        total_ms = (time.time() - start_time) * 1000
        ttft_ms = (first_token_time - start_time) * 1000 if first_token_time else total_ms
        return "".join(full_response).strip(), total_ms, ttft_ms

    except requests.exceptions.ConnectionError:
        print("\n[ERROR] Cannot reach Ollama. Is it running?")
        return None, None, None
    except requests.exceptions.Timeout:
        print("\n[ERROR] Request timed out.")
        return None, None, None
    except Exception as e:
        print("\n[ERROR] Unexpected error:", e)
        return None, None, None


def main():
    system_prompt = load_system_prompt()
    history = []

    print("=" * 55)
    print("  Sota HRI Chat — llama3.2:3b via Ollama")
    print("  Type 'quit' to exit | 'reset' to clear history")
    print("=" * 55)
    print()

    while True:
        # Get user input
        try:
            user_input = input("You: ").strip()
        except (KeyboardInterrupt, EOFError):
            print("\n[Exiting]")
            break

        if not user_input:
            continue

        if user_input.lower() == "quit":
            print("[Exiting chat]")
            break

        if user_input.lower() == "reset":
            history = []
            print("[History cleared]\n")
            continue

        # Append user turn to history
        history.append({"role": "user", "content": user_input})
        history = trim_history(history)

        # Build full message list and send
        messages = build_messages(history, system_prompt)
        print("Sota: ", end="", flush=True)

        response_text, total_ms, ttft_ms = send_message(messages)

        if response_text is None:
            # Remove the failed user turn from history to avoid corruption
            history.pop()
            continue

        print()
        print("      [TTFT {:.0f} ms | total {:.0f} ms | {} turns in history]".format(
            ttft_ms, total_ms, len(history) // 2
        ))
        print()

        # Append assistant turn to history
        history.append({"role": "assistant", "content": response_text})
        history = trim_history(history)


if __name__ == "__main__":
    main()
