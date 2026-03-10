#!/usr/bin/env python3
"""
parse_logs.py — Parse HRI experiment session logs into a structured CSV.

Extracts from each log file:
  - Participant ID, Group, Session, Condition
  - Start/End time, Duration
  - Total turns, User turns, Robot turns
  - Peak VAD, Goodbye type
  - Average user utterance length (words)
  - Detected languages (unique set)
  - Estimated age (from first robot turn if available)

Output: behavioral_data.csv
"""

import os
import re
import csv
import sys
from datetime import datetime

LOG_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "logs")
OUTPUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "behavioral_data.csv")


def parse_log_file(filepath):
    """Parse a single session log file and return a dict of extracted data."""
    with open(filepath, "r", encoding="utf-8", errors="replace") as f:
        content = f.read()

    data = {}

    # Header fields
    m = re.search(r"Participant\s*:\s*(\S+)", content)
    data["participant_id"] = m.group(1) if m else ""

    m = re.search(r"Group\s*:\s*(\S+)", content)
    data["group"] = m.group(1) if m else ""

    m = re.search(r"Session\s*:\s*(\d+)", content)
    data["session"] = int(m.group(1)) if m else 0

    m = re.search(r"Condition\s*:\s*(.+)", content)
    data["condition"] = m.group(1).strip() if m else ""

    m = re.search(r"Reciprocity\s*:\s*(\S+)", content)
    data["reciprocity"] = m.group(1) if m else ""

    # Times
    m = re.search(r"Start Time\s*:\s*(.+)", content)
    data["start_time"] = m.group(1).strip() if m else ""

    m = re.search(r"End Time\s*:\s*(.+)", content)
    data["end_time"] = m.group(1).strip() if m else ""

    m = re.search(r"Duration\s*:\s*(\d+)s", content)
    data["duration_sec"] = int(m.group(1)) if m else 0

    # Turn counts
    m = re.search(r"Total Turns\s*:\s*(\d+)", content)
    data["total_turns"] = int(m.group(1)) if m else 0

    m = re.search(r"User Turns\s*:\s*(\d+)", content)
    data["user_turns"] = int(m.group(1)) if m else 0

    m = re.search(r"Robot Turns\s*:\s*(\d+)", content)
    data["robot_turns"] = int(m.group(1)) if m else 0

    m = re.search(r"Peak VAD\s*:\s*(\d+)", content)
    data["peak_vad"] = int(m.group(1)) if m else 0

    m = re.search(r"Goodbye\s*:\s*(.+)", content)
    data["goodbye"] = m.group(1).strip() if m else ""

    # Parse individual turns for richer analysis
    user_turns = re.findall(
        r"\[[\d:]+\]\s*Turn\s+\d+\s*-\s*USER.*?\n"
        r"(?:\s+Original\s*:\s*(.+)\n)?",
        content
    )
    user_texts = [t.strip() for t in user_turns if t and t.strip()]

    # Average user utterance length (in words)
    if user_texts:
        word_counts = [len(t.split()) for t in user_texts]
        data["avg_user_words"] = round(sum(word_counts) / len(word_counts), 1)
        data["total_user_words"] = sum(word_counts)
    else:
        data["avg_user_words"] = 0
        data["total_user_words"] = 0

    # Detected languages
    langs = re.findall(r"Turn\s+\d+\s*-\s*USER\s*\(lang=(\w+)\)", content)
    data["detected_languages"] = ",".join(sorted(set(langs))) if langs else ""

    # User-initiated goodbye? (True if user said goodbye, False if robot/timeout)
    goodbye_lower = data["goodbye"].lower()
    data["user_initiated_goodbye"] = "user" in goodbye_lower and "timeout" not in goodbye_lower

    # Source file
    data["log_file"] = os.path.basename(filepath)

    return data


def find_best_logs(log_dir):
    """
    For each (participant, session), pick the LAST log file (latest timestamp).
    This handles retries where multiple logs exist for the same session.
    """
    logs_by_key = {}
    for fname in sorted(os.listdir(log_dir)):
        if not fname.endswith(".txt"):
            continue
        filepath = os.path.join(log_dir, fname)
        data = parse_log_file(filepath)
        key = (data["participant_id"], data["session"])

        # Skip logs with 0 user turns (failed sessions)
        if data["user_turns"] == 0:
            continue

        # Keep latest by filename (timestamp in name)
        if key not in logs_by_key or fname > logs_by_key[key][1]:
            logs_by_key[key] = (data, fname)

    return [v[0] for v in sorted(logs_by_key.values(), key=lambda x: x[1])]


def main():
    if not os.path.isdir(LOG_DIR):
        print(f"ERROR: Log directory not found: {LOG_DIR}")
        sys.exit(1)

    results = find_best_logs(LOG_DIR)

    if not results:
        print("No valid log files found.")
        sys.exit(1)

    # Write CSV
    fields = [
        "participant_id", "group", "session", "condition", "reciprocity",
        "start_time", "end_time", "duration_sec",
        "total_turns", "user_turns", "robot_turns",
        "avg_user_words", "total_user_words",
        "peak_vad", "goodbye", "user_initiated_goodbye",
        "detected_languages", "log_file"
    ]

    with open(OUTPUT, "w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=fields)
        writer.writeheader()
        for row in results:
            writer.writerow(row)

    print(f"Parsed {len(results)} session logs -> {OUTPUT}")
    for r in results:
        print(f"  {r['participant_id']} G{r['group'][-1] if r['group'] else '?'} "
              f"S{r['session']} | {r['duration_sec']}s | "
              f"turns={r['total_turns']} (user={r['user_turns']}) | "
              f"{r['log_file']}")


if __name__ == "__main__":
    main()
