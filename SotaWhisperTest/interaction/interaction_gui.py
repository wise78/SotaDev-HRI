"""
interaction_gui.py -- Sota Interaction Monitor GUI

Real-time monitoring dashboard for WhisperInteraction running on Sota robot.
Polls the embedded StatusServer (Java) on the robot via HTTP.

Run from laptop:
    python interaction_gui.py
    python interaction_gui.py --robot-ip 192.168.11.30

Features:
  - Prerequisites check (Whisper server, Ollama, Robot StatusServer)
  - Robot FSM state display with color indicators
  - VAD diagnostics (working/not working, audio level meter)
  - Recording status with duration progress
  - Conversation display (user text, English translation, Sota response)
  - Live log panel
"""

import os
import sys
import json
import time
import threading

try:
    import tkinter as tk
    from tkinter import ttk, scrolledtext
except ImportError:
    print("[ERROR] tkinter not available.")
    sys.exit(1)

try:
    from urllib.request import Request, urlopen
    from urllib.error import HTTPError, URLError
except ImportError:
    from urllib2 import Request, urlopen, HTTPError, URLError


# State color map
STATE_COLORS = {
    "init":       ("#607D8B", "white"),   # gray
    "idle":       ("#ECEFF1", "black"),   # light gray
    "greeting":   ("#66BB6A", "white"),   # green
    "listening":  ("#26C6DA", "white"),   # cyan
    "thinking":   ("#FFCA28", "black"),   # yellow
    "responding": ("#43A047", "white"),   # dark green
    "closing":    ("#90A4AE", "white"),   # gray
    "shutdown":   ("#F44336", "white"),   # red
    "error":      ("#D32F2F", "white"),   # dark red
}

STATE_LABELS = {
    "init":       "INITIALIZING",
    "idle":       "IDLE -- Waiting for face",
    "greeting":   "GREETING",
    "listening":  "LISTENING -- Recording...",
    "thinking":   "THINKING -- LLM processing...",
    "responding": "RESPONDING -- Speaking...",
    "closing":    "CLOSING -- Goodbye",
    "shutdown":   "SHUTDOWN",
    "error":      "ERROR",
}


class InteractionGUI:
    def __init__(self, root, robot_ip="192.168.11.30", status_port=5051,
                 whisper_port=5050, ollama_port=11434):
        self.root = root
        self.root.title("Sota Interaction Monitor")
        self.root.geometry("780x820")
        self.root.minsize(700, 700)

        self.robot_ip = robot_ip
        self.status_port = status_port
        self.whisper_port = whisper_port
        self.ollama_port = ollama_port

        self.polling = False
        self.poll_interval = 500  # ms
        self.prev_state = ""
        self.connected = False

        self._build_ui()
        self._log("Interaction Monitor started.")
        self._log("Configure Robot IP and click Connect.")

    # ================================================================
    # UI Construction
    # ================================================================

    def _build_ui(self):
        # --- Connection frame ---
        conn_frame = ttk.LabelFrame(self.root, text="Connection", padding=6)
        conn_frame.pack(fill="x", padx=8, pady=(8, 4))

        ttk.Label(conn_frame, text="Robot IP:").grid(row=0, column=0, sticky="w")
        self.robot_ip_var = tk.StringVar(value=self.robot_ip)
        ttk.Entry(conn_frame, textvariable=self.robot_ip_var, width=16).grid(row=0, column=1, padx=4)

        ttk.Label(conn_frame, text="Status Port:").grid(row=0, column=2, padx=(8, 0))
        self.status_port_var = tk.StringVar(value=str(self.status_port))
        ttk.Entry(conn_frame, textvariable=self.status_port_var, width=6).grid(row=0, column=3, padx=4)

        ttk.Label(conn_frame, text="Whisper Port:").grid(row=0, column=4, padx=(8, 0))
        self.whisper_port_var = tk.StringVar(value=str(self.whisper_port))
        ttk.Entry(conn_frame, textvariable=self.whisper_port_var, width=6).grid(row=0, column=5, padx=4)

        self.connect_btn = ttk.Button(conn_frame, text="Connect", command=self._toggle_connect)
        self.connect_btn.grid(row=0, column=6, padx=(12, 4))

        self.conn_indicator = tk.Label(conn_frame, text=" DISCONNECTED ", bg="#c62828", fg="white",
                                        font=("Consolas", 9, "bold"), relief="sunken")
        self.conn_indicator.grid(row=0, column=7, padx=4)

        # --- Prerequisites frame ---
        prereq_frame = ttk.LabelFrame(self.root, text="Prerequisites", padding=6)
        prereq_frame.pack(fill="x", padx=8, pady=4)

        self.prereq_indicators = {}
        prereq_items = [
            ("whisper", "Whisper Server (laptop)"),
            ("ollama", "Ollama LLM (laptop)"),
            ("robot", "Robot StatusServer"),
        ]
        for i, (key, label) in enumerate(prereq_items):
            ttk.Label(prereq_frame, text=label).grid(row=0, column=i*2, sticky="w", padx=(0 if i==0 else 12, 4))
            ind = tk.Label(prereq_frame, text="  ?  ", bg="gray", fg="white",
                           font=("Consolas", 9, "bold"), relief="sunken", width=6)
            ind.grid(row=0, column=i*2+1, padx=2)
            self.prereq_indicators[key] = ind

        ttk.Button(prereq_frame, text="Recheck", command=self._check_prerequisites).grid(
            row=0, column=6, padx=(12, 0))

        # --- State frame ---
        state_frame = ttk.LabelFrame(self.root, text="Robot State", padding=8)
        state_frame.pack(fill="x", padx=8, pady=4)

        self.state_label = tk.Label(state_frame, text="  DISCONNECTED  ",
                                     font=("Consolas", 16, "bold"),
                                     bg="#607D8B", fg="white", padx=16, pady=8, relief="raised")
        self.state_label.pack(fill="x")

        state_info = ttk.Frame(state_frame)
        state_info.pack(fill="x", pady=(6, 0))

        ttk.Label(state_info, text="Turn:", font=("Consolas", 10)).pack(side="left")
        self.turn_var = tk.StringVar(value="- / -")
        ttk.Label(state_info, textvariable=self.turn_var, font=("Consolas", 10, "bold")).pack(side="left", padx=(4, 16))

        ttk.Label(state_info, text="Uptime:", font=("Consolas", 10)).pack(side="left")
        self.uptime_var = tk.StringVar(value="-")
        ttk.Label(state_info, textvariable=self.uptime_var, font=("Consolas", 10, "bold")).pack(side="left", padx=(4, 16))

        ttk.Label(state_info, text="Silence retries:", font=("Consolas", 10)).pack(side="left")
        self.silence_var = tk.StringVar(value="-")
        ttk.Label(state_info, textvariable=self.silence_var, font=("Consolas", 10, "bold")).pack(side="left", padx=4)

        # --- VAD frame ---
        vad_frame = ttk.LabelFrame(self.root, text="Voice Activity Detection (VAD)", padding=8)
        vad_frame.pack(fill="x", padx=8, pady=4)

        vad_row1 = ttk.Frame(vad_frame)
        vad_row1.pack(fill="x")

        ttk.Label(vad_row1, text="VAD Status:", font=("Consolas", 10)).pack(side="left")
        self.vad_status_var = tk.StringVar(value="Unknown")
        self.vad_status_label = tk.Label(vad_row1, textvariable=self.vad_status_var,
                                          font=("Consolas", 10, "bold"), fg="#607D8B")
        self.vad_status_label.pack(side="left", padx=8)

        ttk.Label(vad_row1, text="Recording:", font=("Consolas", 10)).pack(side="left", padx=(16, 0))
        self.rec_status_var = tk.StringVar(value="--")
        self.rec_status_label = tk.Label(vad_row1, textvariable=self.rec_status_var,
                                          font=("Consolas", 10, "bold"))
        self.rec_status_label.pack(side="left", padx=8)

        # Audio level bar
        vad_row2 = ttk.Frame(vad_frame)
        vad_row2.pack(fill="x", pady=(6, 0))

        ttk.Label(vad_row2, text="Audio Level:", font=("Consolas", 10)).pack(side="left")

        self.level_canvas = tk.Canvas(vad_row2, height=22, bg="#263238", relief="sunken", bd=1)
        self.level_canvas.pack(side="left", fill="x", expand=True, padx=8)

        self.level_text_var = tk.StringVar(value="-- / thr:300")
        ttk.Label(vad_row2, textvariable=self.level_text_var, font=("Consolas", 9)).pack(side="left")

        # --- Conversation frame ---
        conv_frame = ttk.LabelFrame(self.root, text="Conversation", padding=8)
        conv_frame.pack(fill="x", padx=8, pady=4)

        conv_grid = ttk.Frame(conv_frame)
        conv_grid.pack(fill="x")

        self.conv_vars = {}
        conv_labels = [
            ("lang", "Language:"),
            ("user_text", "User said:"),
            ("user_en", "English:"),
            ("sota_text", "Sota says:"),
        ]
        for i, (key, label) in enumerate(conv_labels):
            ttk.Label(conv_grid, text=label, font=("Consolas", 10)).grid(row=i, column=0, sticky="nw", padx=(0, 8), pady=1)
            var = tk.StringVar(value="-")
            lbl = ttk.Label(conv_grid, textvariable=var, font=("Consolas", 10, "bold"),
                            wraplength=580, justify="left")
            lbl.grid(row=i, column=1, sticky="w", pady=1)
            self.conv_vars[key] = var

        conv_grid.columnconfigure(1, weight=1)

        # --- Log frame ---
        log_frame = ttk.LabelFrame(self.root, text="Log", padding=4)
        log_frame.pack(fill="both", expand=True, padx=8, pady=(4, 8))

        self.log_text = scrolledtext.ScrolledText(log_frame, height=10,
                                                    font=("Consolas", 9), state="disabled")
        self.log_text.pack(fill="both", expand=True)

        ttk.Button(log_frame, text="Clear Log", command=self._clear_log).pack(anchor="e", pady=(4, 0))

    # ================================================================
    # Connection
    # ================================================================

    def _toggle_connect(self):
        if self.polling:
            self._disconnect()
        else:
            self._connect()

    def _connect(self):
        self.robot_ip = self.robot_ip_var.get().strip()
        self.status_port = int(self.status_port_var.get().strip())
        self.whisper_port = int(self.whisper_port_var.get().strip())

        self._log("Connecting to robot at {}:{}...".format(self.robot_ip, self.status_port))
        self.connect_btn.config(text="Connecting...", state="disabled")

        def try_connect():
            url = "http://{}:{}/health".format(self.robot_ip, self.status_port)
            try:
                resp = urlopen(url, timeout=3)
                body = resp.read().decode("utf-8")
                self.root.after(0, lambda: self._on_connected(True))
            except Exception as e:
                self.root.after(0, lambda: self._on_connected(False, str(e)))

        threading.Thread(target=try_connect, daemon=True).start()

    def _on_connected(self, ok, err=""):
        self.connect_btn.config(state="normal")
        if ok:
            self.connected = True
            self.polling = True
            self.connect_btn.config(text="Disconnect")
            self.conn_indicator.config(text=" CONNECTED ", bg="#2e7d32")
            self._log("Connected to robot StatusServer!")
            self._check_prerequisites()
            self._start_prereq_timer()
            self._poll()
        else:
            self.connected = False
            self.conn_indicator.config(text=" FAILED ", bg="#c62828")
            self.connect_btn.config(text="Connect")
            self._log("Connection failed: {}".format(err))
            self._log("Make sure WhisperInteraction is running on the robot.")

    def _disconnect(self):
        self.polling = False
        self.connected = False
        self.connect_btn.config(text="Connect")
        self.conn_indicator.config(text=" DISCONNECTED ", bg="#c62828")
        self.state_label.config(text="  DISCONNECTED  ", bg="#607D8B", fg="white")
        self._log("Disconnected.")

    # ================================================================
    # Prerequisites Check
    # ================================================================

    def _check_prerequisites(self):
        self._log("Checking prerequisites...")

        def check_service(name, url):
            try:
                resp = urlopen(url, timeout=3)
                resp.read()
                return True
            except Exception:
                return False

        def do_checks():
            robot_ip = self.robot_ip_var.get().strip()
            whisper_port = self.whisper_port_var.get().strip()

            results = {}
            results["whisper"] = check_service("Whisper",
                "http://localhost:{}/health".format(whisper_port))
            results["ollama"] = check_service("Ollama",
                "http://localhost:11434/api/tags")
            results["robot"] = check_service("Robot",
                "http://{}:{}/health".format(robot_ip, self.status_port_var.get().strip()))

            self.root.after(0, lambda: self._update_prerequisites(results))

        threading.Thread(target=do_checks, daemon=True).start()

    def _update_prerequisites(self, results):
        for key, ok in results.items():
            ind = self.prereq_indicators[key]
            if ok:
                ind.config(text="  OK  ", bg="#2e7d32", fg="white")
            else:
                ind.config(text=" FAIL ", bg="#c62828", fg="white")

        msgs = []
        for key, ok in results.items():
            msgs.append("{}: {}".format(key, "OK" if ok else "FAIL"))
        self._log("Prerequisites: {}".format(", ".join(msgs)))

    def _start_prereq_timer(self):
        """Recheck prerequisites every 15 seconds while connected."""
        if not self.polling:
            return
        self._check_prerequisites()
        self.root.after(15000, self._start_prereq_timer)

    # ================================================================
    # Polling
    # ================================================================

    def _poll(self):
        if not self.polling:
            return

        def do_poll():
            url = "http://{}:{}/status".format(self.robot_ip, self.status_port)
            try:
                resp = urlopen(url, timeout=2)
                body = resp.read().decode("utf-8")
                data = json.loads(body)
                self.root.after(0, lambda: self._update_ui(data))
            except Exception as e:
                self.root.after(0, lambda: self._on_poll_error(str(e)))

        threading.Thread(target=do_poll, daemon=True).start()

    def _on_poll_error(self, err):
        if self.polling:
            self.conn_indicator.config(text=" LOST ", bg="#FF9800")
            # Schedule next poll (slower on error)
            self.root.after(2000, self._poll)

    def _update_ui(self, data):
        if not self.polling:
            return

        # Restore connection indicator
        self.conn_indicator.config(text=" CONNECTED ", bg="#2e7d32")

        # --- State ---
        state = data.get("state", "?")
        colors = STATE_COLORS.get(state, ("#607D8B", "white"))
        label = STATE_LABELS.get(state, state.upper())
        self.state_label.config(text="  " + label + "  ", bg=colors[0], fg=colors[1])

        # Log state transitions
        if state != self.prev_state and self.prev_state:
            self._log("State: {} -> {}".format(self.prev_state.upper(), state.upper()))
        self.prev_state = state

        # --- Turn / Uptime / Silence ---
        turn = data.get("turn", 0)
        max_turns = data.get("maxTurns", 8)
        self.turn_var.set("{} / {}".format(turn, max_turns))

        uptime_ms = data.get("uptime", 0)
        uptime_s = int(uptime_ms / 1000)
        if uptime_s >= 3600:
            self.uptime_var.set("{}h {}m {}s".format(uptime_s // 3600, (uptime_s % 3600) // 60, uptime_s % 60))
        elif uptime_s >= 60:
            self.uptime_var.set("{}m {}s".format(uptime_s // 60, uptime_s % 60))
        else:
            self.uptime_var.set("{}s".format(uptime_s))

        silence = data.get("silenceRetries", 0)
        self.silence_var.set(str(silence))

        # --- VAD ---
        vad_working = data.get("vadWorking", False)
        vad_level = data.get("vadLevel", -1)
        is_recording = data.get("isRecording", False)
        is_speech = data.get("isSpeech", False)
        rec_duration = data.get("recordingDurationMs", 0)

        if vad_working:
            self.vad_status_var.set("WORKING (DirectVAD, RMS-based, early-stop enabled)")
            self.vad_status_label.config(fg="#2e7d32")
        elif is_recording:
            self.vad_status_var.set("RECORDING (waiting for VAD data...)")
            self.vad_status_label.config(fg="#FF9800")
        elif not is_recording and state == "idle":
            self.vad_status_var.set("Standby")
            self.vad_status_label.config(fg="#607D8B")
        else:
            self.vad_status_var.set("Ready")
            self.vad_status_label.config(fg="#607D8B")

        if is_recording:
            dur_s = rec_duration / 1000.0
            self.rec_status_var.set("RECORDING ({:.1f}s / 15.0s)".format(dur_s))
            self.rec_status_label.config(fg="#c62828")
        else:
            self.rec_status_var.set("--")
            self.rec_status_label.config(fg="#607D8B")

        # Audio level bar
        self._draw_level_bar(vad_level, is_speech, vad_working)
        if vad_level >= 0:
            self.level_text_var.set("{} / thr:300".format(vad_level))
        else:
            self.level_text_var.set("-- / thr:300")

        # --- Conversation ---
        lang = data.get("lastDetectedLang", "-")
        self.conv_vars["lang"].set(lang)
        self.conv_vars["user_text"].set(data.get("lastUserText", "-") or "-")
        self.conv_vars["user_en"].set(data.get("lastUserTextEn", "-") or "-")
        self.conv_vars["sota_text"].set(data.get("lastSotaText", "-") or "-")

        # Robot is alive (we just got a response)
        self.prereq_indicators["robot"].config(text="  OK  ", bg="#2e7d32")
        # Note: Whisper & Ollama indicators are only updated by _check_prerequisites()
        # (laptop-side direct check), NOT from robot data which is only checked once at startup.

        # Schedule next poll
        self.root.after(self.poll_interval, self._poll)

    # ================================================================
    # Level bar drawing
    # ================================================================

    def _draw_level_bar(self, level, is_speech, vad_working):
        c = self.level_canvas
        c.delete("all")
        w = c.winfo_width()
        h = c.winfo_height()
        if w <= 1:
            w = 400  # initial size before layout
        if h <= 1:
            h = 22

        if level < 0:
            # No data — gray bar with standby message
            c.create_text(w // 2, h // 2, text="Standby — waiting for recording",
                          fill="#78909C", font=("Consolas", 9))
            return

        # Draw threshold line
        max_level = 2000  # reasonable max for display
        thr_x = int(300.0 / max_level * w)
        c.create_line(thr_x, 0, thr_x, h, fill="#FF9800", width=2)

        # Draw level bar
        bar_x = min(int(float(level) / max_level * w), w)
        color = "#43A047" if is_speech else "#546E7A"
        if is_speech:
            color = "#43A047"  # green — speech
        elif level > 300:
            color = "#FDD835"  # yellow — some activity
        else:
            color = "#546E7A"  # gray — quiet

        c.create_rectangle(0, 2, bar_x, h - 2, fill=color, outline="")

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


# ================================================================
# Main
# ================================================================

def main():
    robot_ip = "192.168.11.30"
    status_port = 5051
    whisper_port = 5050

    # Parse simple args
    args = sys.argv[1:]
    i = 0
    while i < len(args):
        if args[i] == "--robot-ip" and i + 1 < len(args):
            robot_ip = args[i + 1]
            i += 2
        elif args[i] == "--status-port" and i + 1 < len(args):
            status_port = int(args[i + 1])
            i += 2
        elif args[i] == "--whisper-port" and i + 1 < len(args):
            whisper_port = int(args[i + 1])
            i += 2
        else:
            # Treat bare arg as robot IP
            robot_ip = args[i]
            i += 1

    root = tk.Tk()
    InteractionGUI(root, robot_ip=robot_ip, status_port=status_port,
                    whisper_port=whisper_port)
    root.mainloop()


if __name__ == "__main__":
    main()
