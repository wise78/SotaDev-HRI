"""
whisper_gui.py — GUI for testing, troubleshooting, and benchmarking the Whisper STT server.

Uses tkinter (built-in Python, no extra install needed).
Run from SotaWhisperTest/ with venv activated:
    python whisper_gui.py

Features:
  - Server health check (green/red indicator)
  - WAV file selection (browse or type path)
  - Transcribe button with result display
  - Benchmark with configurable runs + chart
  - Live log output
  - Record from microphone (if available)
"""

import os
import sys
import json
import time
import threading

try:
    import tkinter as tk
    from tkinter import ttk, filedialog, scrolledtext
except ImportError:
    print("[ERROR] tkinter not available. Install Python with tkinter support.")
    sys.exit(1)

try:
    from urllib.request import Request, urlopen
    from urllib.error import HTTPError, URLError
except ImportError:
    from urllib2 import Request, urlopen, HTTPError, URLError

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
AUDIO_DIR = os.path.join(SCRIPT_DIR, "audio")


class WhisperGUI:
    def __init__(self, root):
        self.root = root
        self.root.title("Sota Whisper STT — Test & Benchmark")
        self.root.geometry("820x700")
        self.root.minsize(700, 600)

        # State
        self.server_alive = False
        self.benchmark_running = False

        self._build_ui()
        self._log("Whisper STT GUI started.")
        self._log("Audio directory: {}".format(AUDIO_DIR))

        # Auto health check on start
        self.root.after(500, self._check_health)

    # ================================================================
    # UI Construction
    # ================================================================

    def _build_ui(self):
        # --- Top frame: Server config ---
        top = ttk.LabelFrame(self.root, text="Server Configuration", padding=8)
        top.pack(fill="x", padx=8, pady=(8, 4))

        ttk.Label(top, text="Server IP:").grid(row=0, column=0, sticky="w")
        self.ip_var = tk.StringVar(value="localhost")
        ttk.Entry(top, textvariable=self.ip_var, width=20).grid(row=0, column=1, padx=4)

        ttk.Label(top, text="Port:").grid(row=0, column=2, sticky="w", padx=(12, 0))
        self.port_var = tk.StringVar(value="5050")
        ttk.Entry(top, textvariable=self.port_var, width=8).grid(row=0, column=3, padx=4)

        self.health_btn = ttk.Button(top, text="Check Health", command=self._check_health)
        self.health_btn.grid(row=0, column=4, padx=(12, 4))

        self.health_indicator = tk.Label(top, text="  ???  ", bg="gray", fg="white",
                                         font=("Consolas", 10, "bold"), relief="sunken")
        self.health_indicator.grid(row=0, column=5, padx=4)

        self.health_info = ttk.Label(top, text="Not checked yet")
        self.health_info.grid(row=0, column=6, padx=4, sticky="w")

        # --- Middle frame: Actions ---
        mid = ttk.LabelFrame(self.root, text="Actions", padding=8)
        mid.pack(fill="x", padx=8, pady=4)

        # WAV file selection
        ttk.Label(mid, text="WAV File:").grid(row=0, column=0, sticky="w")
        self.wav_var = tk.StringVar()
        wav_entry = ttk.Entry(mid, textvariable=self.wav_var, width=50)
        wav_entry.grid(row=0, column=1, padx=4, sticky="ew")
        ttk.Button(mid, text="Browse...", command=self._browse_wav).grid(row=0, column=2, padx=4)

        # Quick select buttons for existing test files
        quick_frame = ttk.Frame(mid)
        quick_frame.grid(row=1, column=0, columnspan=3, sticky="w", pady=(4, 0))
        ttk.Label(quick_frame, text="Quick select:").pack(side="left")

        en_wav = os.path.join(AUDIO_DIR, "test_english.wav")
        ja_wav = os.path.join(AUDIO_DIR, "test_japanese.wav")
        if os.path.exists(en_wav):
            ttk.Button(quick_frame, text="test_english.wav",
                       command=lambda: self.wav_var.set(en_wav)).pack(side="left", padx=4)
        if os.path.exists(ja_wav):
            ttk.Button(quick_frame, text="test_japanese.wav",
                       command=lambda: self.wav_var.set(ja_wav)).pack(side="left", padx=4)

        # Language selection
        ttk.Label(mid, text="Language:").grid(row=2, column=0, sticky="w", pady=(4, 0))
        lang_frame = ttk.Frame(mid)
        lang_frame.grid(row=2, column=1, sticky="w", pady=(4, 0))
        self.lang_var = tk.StringVar(value="auto")
        ttk.Radiobutton(lang_frame, text="Auto-detect", variable=self.lang_var, value="auto").pack(side="left")
        ttk.Radiobutton(lang_frame, text="English", variable=self.lang_var, value="en").pack(side="left", padx=8)
        ttk.Radiobutton(lang_frame, text="Japanese", variable=self.lang_var, value="ja").pack(side="left")

        mid.columnconfigure(1, weight=1)

        # Buttons row
        btn_frame = ttk.Frame(mid)
        btn_frame.grid(row=3, column=0, columnspan=3, pady=(8, 0))

        self.transcribe_btn = ttk.Button(btn_frame, text="Transcribe",
                                          command=self._transcribe)
        self.transcribe_btn.pack(side="left", padx=4)

        ttk.Separator(btn_frame, orient="vertical").pack(side="left", padx=8, fill="y")

        ttk.Label(btn_frame, text="Benchmark runs:").pack(side="left")
        self.runs_var = tk.StringVar(value="5")
        ttk.Spinbox(btn_frame, textvariable=self.runs_var, from_=1, to=50, width=4).pack(side="left", padx=4)
        self.bench_btn = ttk.Button(btn_frame, text="Run Benchmark",
                                     command=self._benchmark)
        self.bench_btn.pack(side="left", padx=4)

        # --- Result frame ---
        result_frame = ttk.LabelFrame(self.root, text="Last Result", padding=8)
        result_frame.pack(fill="x", padx=8, pady=4)

        result_grid = ttk.Frame(result_frame)
        result_grid.pack(fill="x")

        labels = ["Status:", "Language:", "Text:", "Server Time:", "Total Time:"]
        self.result_vars = {}
        for i, label in enumerate(labels):
            ttk.Label(result_grid, text=label, font=("Consolas", 10)).grid(row=i, column=0, sticky="w", padx=(0, 8))
            key = label.replace(":", "").replace(" ", "_").lower()
            var = tk.StringVar(value="-")
            lbl = ttk.Label(result_grid, textvariable=var, font=("Consolas", 10, "bold"),
                            wraplength=600, justify="left")
            lbl.grid(row=i, column=1, sticky="w")
            self.result_vars[key] = var

        # --- Benchmark results frame ---
        bench_frame = ttk.LabelFrame(self.root, text="Benchmark Results", padding=8)
        bench_frame.pack(fill="x", padx=8, pady=4)

        self.bench_text = scrolledtext.ScrolledText(bench_frame, height=6,
                                                      font=("Consolas", 9), state="disabled")
        self.bench_text.pack(fill="x")

        # --- Log frame ---
        log_frame = ttk.LabelFrame(self.root, text="Log", padding=4)
        log_frame.pack(fill="both", expand=True, padx=8, pady=(4, 8))

        self.log_text = scrolledtext.ScrolledText(log_frame, height=8,
                                                    font=("Consolas", 9), state="disabled")
        self.log_text.pack(fill="both", expand=True)

        # Clear log button
        ttk.Button(log_frame, text="Clear Log", command=self._clear_log).pack(anchor="e", pady=(4, 0))

    # ================================================================
    # Server Communication
    # ================================================================

    def _get_server_url(self):
        ip = self.ip_var.get().strip()
        port = self.port_var.get().strip()
        return "http://{}:{}".format(ip, port)

    def _check_health(self):
        """Check server health in background thread."""
        self._log("Checking server health...")
        self.health_indicator.config(text="  ...  ", bg="orange")
        self.health_info.config(text="Checking...")

        def do_check():
            url = self._get_server_url()
            try:
                resp = urlopen(url + "/health", timeout=5)
                body = resp.read().decode("utf-8")
                data = json.loads(body)
                model = data.get("model", "?")
                device = data.get("device", "?")
                self.root.after(0, lambda: self._health_result(True, model, device))
            except Exception as e:
                self.root.after(0, lambda: self._health_result(False, str(e), ""))

        threading.Thread(target=do_check, daemon=True).start()

    def _health_result(self, ok, model_or_err, device):
        if ok:
            self.server_alive = True
            self.health_indicator.config(text="  OK  ", bg="#2e7d32", fg="white")
            self.health_info.config(text="model={}, device={}".format(model_or_err, device))
            self._log("[OK] Server alive — model={}, device={}".format(model_or_err, device))
        else:
            self.server_alive = False
            self.health_indicator.config(text=" FAIL ", bg="#c62828", fg="white")
            self.health_info.config(text="Error: {}".format(model_or_err[:60]))
            self._log("[FAIL] Server not reachable: {}".format(model_or_err))

    def _build_multipart(self, boundary, filename, wav_bytes, language):
        """Build multipart/form-data body."""
        body = b""
        # Audio file part
        body += ("--" + boundary + "\r\n").encode("ascii")
        body += ("Content-Disposition: form-data; name=\"audio\"; "
                 "filename=\"" + filename + "\"\r\n").encode("ascii")
        body += b"Content-Type: audio/wav\r\n\r\n"
        body += wav_bytes
        # Language part (if specified)
        if language and language != "auto":
            body += ("\r\n--" + boundary + "\r\n").encode("ascii")
            body += ("Content-Disposition: form-data; name=\"language\"\r\n\r\n").encode("ascii")
            body += language.encode("ascii")
        body += ("\r\n--" + boundary + "--\r\n").encode("ascii")
        return body

    def _send_transcribe(self, wav_path, language):
        """Send transcription request. Returns (total_ms, server_ms, text, lang) or None."""
        with open(wav_path, "rb") as f:
            wav_bytes = f.read()

        boundary = "----GUIBoundary" + str(int(time.time() * 1000))
        body = self._build_multipart(boundary, os.path.basename(wav_path), wav_bytes, language)

        req = Request(self._get_server_url() + "/transcribe", data=body, method="POST")
        req.add_header("Content-Type", "multipart/form-data; boundary=" + boundary)
        req.add_header("Content-Length", str(len(body)))

        t0 = time.time()
        resp = urlopen(req, timeout=120)
        raw = resp.read().decode("utf-8")
        total_ms = int((time.time() - t0) * 1000)
        data = json.loads(raw)
        return total_ms, data.get("processing_ms", 0), data.get("text", ""), data.get("language", "")

    # ================================================================
    # Actions
    # ================================================================

    def _transcribe(self):
        """Transcribe selected WAV file."""
        wav_path = self.wav_var.get().strip()
        if not wav_path:
            self._log("[ERROR] No WAV file selected. Click Browse or use Quick Select.")
            return
        if not os.path.exists(wav_path):
            self._log("[ERROR] File not found: {}".format(wav_path))
            return

        language = self.lang_var.get()
        size_kb = os.path.getsize(wav_path) / 1024.0
        self._log("Transcribing: {} ({:.1f} KB, lang={})...".format(
            os.path.basename(wav_path), size_kb, language))

        self.transcribe_btn.config(state="disabled")
        self.result_vars["status"].set("Processing...")
        self.result_vars["language"].set("-")
        self.result_vars["text"].set("-")
        self.result_vars["server_time"].set("-")
        self.result_vars["total_time"].set("-")

        def do_transcribe():
            try:
                total_ms, server_ms, text, lang = self._send_transcribe(wav_path, language)
                self.root.after(0, lambda: self._transcribe_result(True, total_ms, server_ms, text, lang))
            except Exception as e:
                self.root.after(0, lambda: self._transcribe_result(False, 0, 0, str(e), ""))

        threading.Thread(target=do_transcribe, daemon=True).start()

    def _transcribe_result(self, ok, total_ms, server_ms, text, lang):
        self.transcribe_btn.config(state="normal")
        if ok:
            self.result_vars["status"].set("OK")
            self.result_vars["language"].set(lang)
            self.result_vars["text"].set(text)
            self.result_vars["server_time"].set("{} ms (GPU processing)".format(server_ms))
            self.result_vars["total_time"].set("{} ms (incl. network)".format(total_ms))
            self._log("[OK] [{}] \"{}\" (server={}ms, total={}ms)".format(lang, text[:80], server_ms, total_ms))
        else:
            self.result_vars["status"].set("FAILED")
            self.result_vars["text"].set(text)
            self._log("[FAIL] {}".format(text))

    def _benchmark(self):
        """Run benchmark in background thread."""
        wav_path = self.wav_var.get().strip()
        if not wav_path:
            self._log("[ERROR] No WAV file selected for benchmark.")
            return
        if not os.path.exists(wav_path):
            self._log("[ERROR] File not found: {}".format(wav_path))
            return

        try:
            runs = int(self.runs_var.get())
        except ValueError:
            runs = 5

        if self.benchmark_running:
            self._log("[WARN] Benchmark already running.")
            return

        language = self.lang_var.get()
        self.benchmark_running = True
        self.bench_btn.config(state="disabled")
        self._bench_clear()
        self._bench_log("Benchmark: {} x {} (lang={})".format(
            os.path.basename(wav_path), runs, language))
        self._bench_log("{:>4}  {:>10}  {:>10}  {}".format("Run", "Total ms", "Server ms", "Text"))
        self._bench_log("-" * 65)

        def do_benchmark():
            total_times = []
            server_times = []
            for i in range(1, runs + 1):
                try:
                    total_ms, server_ms, text, lang = self._send_transcribe(wav_path, language)
                    total_times.append(total_ms)
                    server_times.append(server_ms)
                    preview = text[:40] + "..." if len(text) > 40 else text
                    line = "{:>4}  {:>9}ms  {:>9}ms  [{}] {}".format(
                        i, total_ms, server_ms, lang, preview)
                    self.root.after(0, lambda l=line: self._bench_log(l))
                except Exception as e:
                    line = "{:>4}  FAILED: {}".format(i, str(e)[:50])
                    self.root.after(0, lambda l=line: self._bench_log(l))

            # Summary
            self.root.after(0, lambda: self._bench_summary(total_times, server_times))

        threading.Thread(target=do_benchmark, daemon=True).start()

    def _bench_summary(self, total_times, server_times):
        self.benchmark_running = False
        self.bench_btn.config(state="normal")

        if not total_times:
            self._bench_log("\nAll runs failed!")
            self._log("[FAIL] Benchmark: all runs failed.")
            return

        self._bench_log("")
        self._bench_log("--- Results ({} successful runs) ---".format(len(total_times)))
        avg_t = int(sum(total_times) / len(total_times))
        avg_s = int(sum(server_times) / len(server_times))
        net = [t - s for t, s in zip(total_times, server_times)]
        avg_n = int(sum(net) / len(net))

        self._bench_log("Total time  (incl. network):  avg={:>5}ms  min={:>5}ms  max={:>5}ms".format(
            avg_t, min(total_times), max(total_times)))
        self._bench_log("Server time (GPU only):       avg={:>5}ms  min={:>5}ms  max={:>5}ms".format(
            avg_s, min(server_times), max(server_times)))
        self._bench_log("Network overhead:             avg={:>5}ms  min={:>5}ms  max={:>5}ms".format(
            avg_n, min(net), max(net)))

        self._log("[OK] Benchmark done: avg_total={}ms, avg_server={}ms, avg_network={}ms".format(
            avg_t, avg_s, avg_n))

    # ================================================================
    # Logging
    # ================================================================

    def _log(self, msg):
        ts = time.strftime("%H:%M:%S")
        self.log_text.config(state="normal")
        self.log_text.insert("end", "[{}] {}\n".format(ts, msg))
        self.log_text.see("end")
        self.log_text.config(state="disabled")

    def _clear_log(self):
        self.log_text.config(state="normal")
        self.log_text.delete("1.0", "end")
        self.log_text.config(state="disabled")

    def _bench_clear(self):
        self.bench_text.config(state="normal")
        self.bench_text.delete("1.0", "end")
        self.bench_text.config(state="disabled")

    def _bench_log(self, msg):
        self.bench_text.config(state="normal")
        self.bench_text.insert("end", msg + "\n")
        self.bench_text.see("end")
        self.bench_text.config(state="disabled")

    # ================================================================
    # File selection
    # ================================================================

    def _browse_wav(self):
        path = filedialog.askopenfilename(
            title="Select WAV file",
            initialdir=AUDIO_DIR if os.path.exists(AUDIO_DIR) else SCRIPT_DIR,
            filetypes=[("WAV files", "*.wav"), ("All files", "*.*")]
        )
        if path:
            self.wav_var.set(path)
            self._log("Selected: {}".format(path))


def main():
    root = tk.Tk()
    WhisperGUI(root)
    root.mainloop()


if __name__ == "__main__":
    main()
