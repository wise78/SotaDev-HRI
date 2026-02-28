"""
benchmark.py — Measure Whisper server throughput and latency.

Sends the same WAV file multiple times and reports:
  - Server processing time (GPU time reported by server)
  - Total round-trip time (incl. HTTP overhead + network)
  - Avg / Min / Max across N runs

Usage:
  python benchmark.py                          # 5 runs, localhost, test_english.wav
  python benchmark.py --runs 10               # 10 runs
  python benchmark.py --host 192.168.11.32    # test from another machine
  python benchmark.py --wav audio/test_japanese.wav
  python benchmark.py --runs 5 --wav audio/test_english.wav --wav audio/test_japanese.wav
"""

import os
import sys
import json
import time

try:
    from urllib.request import Request, urlopen
    from urllib.error import HTTPError, URLError
except ImportError:
    from urllib2 import Request, urlopen, HTTPError, URLError

# Defaults
DEFAULT_HOST  = "localhost"
DEFAULT_PORT  = 5050
DEFAULT_RUNS  = 5
DEFAULT_WAVS  = ["audio/test_english.wav", "audio/test_japanese.wav"]

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))


def build_multipart(boundary, filename, wav_bytes):
    body = b""
    body += ("--" + boundary + "\r\n").encode("ascii")
    body += ("Content-Disposition: form-data; name=\"audio\"; "
             "filename=\"" + filename + "\"\r\n").encode("ascii")
    body += b"Content-Type: audio/wav\r\n\r\n"
    body += wav_bytes
    body += ("\r\n--" + boundary + "--\r\n").encode("ascii")
    return body


def send_transcribe(server_url, wav_path):
    """Send one transcription request, return (total_ms, server_ms, text, lang) or None."""
    with open(wav_path, "rb") as f:
        wav_bytes = f.read()

    boundary = "----BenchBoundary"
    body = build_multipart(boundary, os.path.basename(wav_path), wav_bytes)

    req = Request(server_url + "/transcribe", data=body, method="POST")
    req.add_header("Content-Type", "multipart/form-data; boundary=" + boundary)
    req.add_header("Content-Length", str(len(body)))

    t0 = time.time()
    try:
        resp = urlopen(req, timeout=60)
        raw = resp.read().decode("utf-8")
        total_ms = int((time.time() - t0) * 1000)
        data = json.loads(raw)
        return total_ms, data.get("processing_ms", 0), data.get("text", ""), data.get("language", "")
    except Exception as e:
        total_ms = int((time.time() - t0) * 1000)
        print("    ERROR: {} ({} ms)".format(str(e), total_ms))
        return None


def benchmark_file(server_url, wav_path, runs):
    print()
    print("  File : {}".format(os.path.basename(wav_path)))
    size_kb = os.path.getsize(wav_path) / 1024.0
    print("  Size : {:.1f} KB".format(size_kb))
    print("  Runs : {}".format(runs))
    print()
    print("  {:>4}  {:>10}  {:>10}  {}".format("Run", "Total ms", "Server ms", "Text"))
    print("  " + "-" * 65)

    total_times  = []
    server_times = []

    for i in range(1, runs + 1):
        result = send_transcribe(server_url, wav_path)
        if result is None:
            print("  {:>4}  FAILED".format(i))
            continue
        total_ms, server_ms, text, lang = result
        total_times.append(total_ms)
        server_times.append(server_ms)
        preview = text[:40] + "..." if len(text) > 40 else text
        print("  {:>4}  {:>9}ms  {:>9}ms  [{}] {}".format(
            i, total_ms, server_ms, lang, preview))

    if not total_times:
        print("  All runs failed!")
        return

    print()
    print("  --- Results ---")
    print("  Total time  (incl. network):  avg={:>6}ms  min={:>6}ms  max={:>6}ms".format(
        int(sum(total_times) / len(total_times)), min(total_times), max(total_times)))
    print("  Server time (GPU only):       avg={:>6}ms  min={:>6}ms  max={:>6}ms".format(
        int(sum(server_times) / len(server_times)), min(server_times), max(server_times)))
    net_overhead = [t - s for t, s in zip(total_times, server_times)]
    print("  Network overhead:             avg={:>6}ms  min={:>6}ms  max={:>6}ms".format(
        int(sum(net_overhead) / len(net_overhead)), min(net_overhead), max(net_overhead)))


def check_health(server_url):
    try:
        resp = urlopen(server_url + "/health", timeout=5)
        data = json.loads(resp.read().decode("utf-8"))
        print("  Server : {}".format(server_url))
        print("  Model  : {}".format(data.get("model", "?")))
        print("  Device : {}".format(data.get("device", "?")))
        return True
    except Exception as e:
        print("  [FAIL] Cannot connect: {}".format(str(e)))
        return False


def main():
    # Simple arg parsing (no argparse needed, no external deps)
    args = sys.argv[1:]
    host  = DEFAULT_HOST
    port  = DEFAULT_PORT
    runs  = DEFAULT_RUNS
    wavs  = []

    i = 0
    while i < len(args):
        if args[i] == "--host" and i + 1 < len(args):
            host = args[i + 1]; i += 2
        elif args[i] == "--port" and i + 1 < len(args):
            port = int(args[i + 1]); i += 2
        elif args[i] == "--runs" and i + 1 < len(args):
            runs = int(args[i + 1]); i += 2
        elif args[i] == "--wav" and i + 1 < len(args):
            wavs.append(args[i + 1]); i += 2
        else:
            i += 1

    if not wavs:
        wavs = [os.path.join(SCRIPT_DIR, w) for w in DEFAULT_WAVS]
        wavs = [w for w in wavs if os.path.exists(w)]

    server_url = "http://{}:{}".format(host, port)

    print("=" * 60)
    print("  Whisper STT Benchmark")
    print("=" * 60)
    check_health(server_url)
    print()

    for wav_path in wavs:
        if not os.path.exists(wav_path):
            print("  [SKIP] Not found: {}".format(wav_path))
            continue
        benchmark_file(server_url, wav_path, runs)
        print()

    print("=" * 60)
    print("  Benchmark complete!")
    print("  Tip: First run is slower (CUDA kernel warmup).")
    print("       Change WHISPER_MODEL in whisper_server.py for")
    print("       accuracy vs speed tradeoff (base/small/medium).")
    print("=" * 60)


if __name__ == "__main__":
    main()
