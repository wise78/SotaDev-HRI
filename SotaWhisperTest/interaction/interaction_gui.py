"""
interaction_gui.py -- Sota HRI Research Control GUI

Research operation dashboard for WhisperInteraction HRI experiment.
Polls the embedded StatusServer (Java) on the robot via HTTP.

3 panels:
  - Session Setup: participant ID, group, session, condition auto-calc, notes
  - Live Monitor: robot state, VAD, recording, turn count, timer
  - Conversation Log: scrollable, color-coded USER (blue) / ROBOT (green)

Data logging:
  - research_data/session_log.csv (master CSV, UTF-8 BOM)
  - research_data/logs/P01_G1_S1_20260228_1430.txt (per-session detail)

Run:
    python interaction_gui.py
    python interaction_gui.py --robot-ip 192.168.11.30
"""

import os
import sys
import csv
import json
import time
import datetime
import threading
import codecs

try:
    import tkinter as tk
    from tkinter import ttk, scrolledtext, messagebox
except ImportError:
    print("[ERROR] tkinter not available.")
    sys.exit(1)

try:
    from urllib.request import Request, urlopen
    from urllib.error import HTTPError, URLError
except ImportError:
    from urllib2 import Request, urlopen, HTTPError, URLError


# ================================================================
# Constants
# ================================================================

STATE_COLORS = {
    "init":        ("#607D8B", "white"),
    "idle":        ("#ECEFF1", "#263238"),
    "recognizing": ("#FF8C00", "white"),
    "greeting":    ("#66BB6A", "white"),
    "registering": ("#DAA520", "white"),
    "listening":   ("#26C6DA", "white"),
    "thinking":    ("#FFCA28", "#263238"),
    "responding":  ("#43A047", "white"),
    "closing":     ("#90A4AE", "white"),
    "shutdown":    ("#F44336", "white"),
    "error":       ("#D32F2F", "white"),
}

STATE_LABELS = {
    "init":        "INITIALIZING",
    "idle":        "IDLE",
    "recognizing": "RECOGNIZING",
    "greeting":    "GREETING",
    "registering": "REGISTERING",
    "listening":   "LISTENING",
    "thinking":    "THINKING",
    "responding":  "RESPONDING",
    "closing":     "CLOSING",
    "shutdown":    "SHUTDOWN",
    "error":       "ERROR",
}

# Experiment condition mapping
# G1 (Remember): S1=NOVICE+WOR, S2=REMEMBER+WR
# G2 (Control):  S1=NOVICE+WOR, S2=NO-REMEMBER+WOR
CONDITION_MAP = {
    ("G1", "1"): ("NOVICE",      "WOR", "First meeting, no memory, no reciprocity"),
    ("G1", "2"): ("REMEMBER",    "WR",  "Memory active, with reciprocity"),
    ("G2", "1"): ("NOVICE",      "WOR", "First meeting, no memory, no reciprocity"),
    ("G2", "2"): ("NO-REMEMBER", "WOR", "No memory, no reciprocity (control)"),
}

RESEARCH_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "research_data")
LOGS_DIR = os.path.join(RESEARCH_DIR, "logs")
CSV_FILE = os.path.join(RESEARCH_DIR, "session_log.csv")

CSV_COLUMNS = [
    "participant_id", "group", "session_num", "condition_code", "condition_label",
    "with_reciprocity", "start_time", "end_time", "duration_sec", "total_turns",
    "user_turns", "robot_turns", "user_initiated_goodbye", "peak_vad_level",
    "conversation_file", "researcher_notes",
]


# ================================================================
# Data Logger
# ================================================================

class DataLogger:
    """Handles CSV master log and per-session TXT conversation logs."""

    def __init__(self):
        os.makedirs(LOGS_DIR, exist_ok=True)
        self._ensure_csv()
        self.txt_path = None
        self.txt_file = None
        self.turn_count = 0
        self.user_turns = 0
        self.robot_turns = 0
        self.peak_vad = 0
        self.user_goodbye = False

    def _ensure_csv(self):
        """Create CSV with UTF-8 BOM header if it doesn't exist."""
        if os.path.exists(CSV_FILE):
            return
        with open(CSV_FILE, "wb") as f:
            f.write(codecs.BOM_UTF8)
        with open(CSV_FILE, "a", newline="", encoding="utf-8") as f:
            writer = csv.writer(f)
            writer.writerow(CSV_COLUMNS)

    def start_session(self, pid, group, session_num, condition_code, condition_label,
                      with_reciprocity):
        """Open a new TXT log file for this session."""
        now = datetime.datetime.now()
        ts_str = now.strftime("%Y%m%d_%H%M")
        filename = "{}_{}_{}_{}_{}.txt".format(
            pid, group, "S" + str(session_num), condition_code, ts_str)
        self.txt_path = os.path.join(LOGS_DIR, filename)

        self.txt_file = open(self.txt_path, "w", encoding="utf-8")
        self.txt_file.write("=" * 60 + "\n")
        self.txt_file.write("HRI Experiment Session Log\n")
        self.txt_file.write("=" * 60 + "\n")
        self.txt_file.write("Participant : {}\n".format(pid))
        self.txt_file.write("Group       : {}\n".format(group))
        self.txt_file.write("Session     : {}\n".format(session_num))
        self.txt_file.write("Condition   : {} ({})\n".format(condition_code, condition_label))
        self.txt_file.write("Reciprocity : {}\n".format(with_reciprocity))
        self.txt_file.write("Start Time  : {}\n".format(now.strftime("%Y-%m-%d %H:%M:%S")))
        self.txt_file.write("=" * 60 + "\n\n")
        self.txt_file.flush()

        self.turn_count = 0
        self.user_turns = 0
        self.robot_turns = 0
        self.peak_vad = 0
        self.user_goodbye = False

    def log_turn(self, role, text, lang="", text_en=""):
        """Log a conversation turn."""
        if self.txt_file is None:
            return
        self.turn_count += 1
        ts = datetime.datetime.now().strftime("%H:%M:%S")

        if role == "USER":
            self.user_turns += 1
            self.txt_file.write("[{}] Turn {} - USER".format(ts, self.turn_count))
            if lang:
                self.txt_file.write(" (lang={})".format(lang))
            self.txt_file.write("\n")
            self.txt_file.write("  Original : {}\n".format(text))
            if text_en and text_en != text:
                self.txt_file.write("  English  : {}\n".format(text_en))
        elif role == "ROBOT":
            self.robot_turns += 1
            self.txt_file.write("[{}] Turn {} - ROBOT\n".format(ts, self.turn_count))
            self.txt_file.write("  Response : {}\n".format(text))

        self.txt_file.write("\n")
        self.txt_file.flush()

    def update_vad(self, level):
        if level > self.peak_vad:
            self.peak_vad = level

    def mark_goodbye(self, user_initiated):
        self.user_goodbye = user_initiated

    def end_session(self, pid, group, session_num, condition_code, condition_label,
                    with_reciprocity, start_time, notes=""):
        """Close TXT log and append row to master CSV."""
        now = datetime.datetime.now()
        duration = (now - start_time).total_seconds()

        # Close TXT
        if self.txt_file is not None:
            self.txt_file.write("\n" + "=" * 60 + "\n")
            self.txt_file.write("End Time    : {}\n".format(now.strftime("%Y-%m-%d %H:%M:%S")))
            self.txt_file.write("Duration    : {:.0f}s\n".format(duration))
            self.txt_file.write("Total Turns : {}\n".format(self.turn_count))
            self.txt_file.write("User Turns  : {}\n".format(self.user_turns))
            self.txt_file.write("Robot Turns : {}\n".format(self.robot_turns))
            self.txt_file.write("Peak VAD    : {}\n".format(self.peak_vad))
            self.txt_file.write("Goodbye     : {}\n".format(
                "User initiated" if self.user_goodbye else "Robot/timeout"))
            if notes:
                self.txt_file.write("Notes       : {}\n".format(notes))
            self.txt_file.write("=" * 60 + "\n")
            self.txt_file.close()
            self.txt_file = None

        # Append CSV
        conv_file = os.path.basename(self.txt_path) if self.txt_path else ""
        row = [
            pid,
            group,
            session_num,
            condition_code,
            condition_label,
            with_reciprocity,
            start_time.strftime("%Y-%m-%d %H:%M:%S"),
            now.strftime("%Y-%m-%d %H:%M:%S"),
            "{:.0f}".format(duration),
            self.turn_count,
            self.user_turns,
            self.robot_turns,
            "yes" if self.user_goodbye else "no",
            self.peak_vad,
            conv_file,
            notes.replace("\n", " "),
        ]

        with open(CSV_FILE, "a", newline="", encoding="utf-8") as f:
            writer = csv.writer(f)
            writer.writerow(row)

        return conv_file


# ================================================================
# Main GUI
# ================================================================

class ResearchGUI:
    def __init__(self, root, robot_ip="192.168.11.30", status_port=5051):
        self.root = root
        self.root.title("Sota HRI Research Control")
        self.root.geometry("900x850")
        self.root.minsize(800, 750)

        self.robot_ip = robot_ip
        self.status_port = status_port

        self.polling = False
        self.poll_interval = 500
        self.connected = False
        self.prev_state = ""

        # Session state
        self.session_active = False
        self.session_start_time = None
        self.logger = DataLogger()

        # Track last seen conversation to detect new turns
        self._last_user_text = ""
        self._last_sota_text = ""

        self._build_ui()
        self._log("Research Control GUI started.")
        self._log("1) Connect to robot  2) Set session params  3) Click START")

    # ================================================================
    # UI
    # ================================================================

    def _build_ui(self):
        # Top bar: connection
        conn_frame = ttk.LabelFrame(self.root, text="Robot Connection", padding=6)
        conn_frame.pack(fill="x", padx=8, pady=(8, 4))

        ttk.Label(conn_frame, text="Robot IP:").grid(row=0, column=0, sticky="w")
        self.robot_ip_var = tk.StringVar(value=self.robot_ip)
        ttk.Entry(conn_frame, textvariable=self.robot_ip_var, width=16).grid(
            row=0, column=1, padx=4)

        ttk.Label(conn_frame, text="Port:").grid(row=0, column=2, padx=(8, 0))
        self.status_port_var = tk.StringVar(value=str(self.status_port))
        ttk.Entry(conn_frame, textvariable=self.status_port_var, width=6).grid(
            row=0, column=3, padx=4)

        self.connect_btn = ttk.Button(conn_frame, text="Connect",
                                       command=self._toggle_connect)
        self.connect_btn.grid(row=0, column=4, padx=(12, 4))

        self.conn_indicator = tk.Label(conn_frame, text=" DISCONNECTED ",
                                        bg="#c62828", fg="white",
                                        font=("Consolas", 9, "bold"), relief="sunken")
        self.conn_indicator.grid(row=0, column=5, padx=4)

        # --- Main content: 3 panels ---
        main_pw = ttk.PanedWindow(self.root, orient="vertical")
        main_pw.pack(fill="both", expand=True, padx=8, pady=4)

        # Panel 1: Session Setup
        self._build_session_panel(main_pw)

        # Panel 2: Live Monitor
        self._build_monitor_panel(main_pw)

        # Panel 3: Conversation Log
        self._build_conversation_panel(main_pw)

    # ---- Panel 1: Session Setup ----
    def _build_session_panel(self, parent):
        frame = ttk.LabelFrame(parent, text="Session Setup", padding=8)
        parent.add(frame, weight=0)

        # Row 0: Participant ID + Group + Session
        r0 = ttk.Frame(frame)
        r0.pack(fill="x", pady=(0, 4))

        ttk.Label(r0, text="Participant ID:", font=("Consolas", 10)).pack(side="left")
        self.pid_var = tk.StringVar(value="P01")
        ttk.Entry(r0, textvariable=self.pid_var, width=8,
                  font=("Consolas", 10)).pack(side="left", padx=(4, 16))

        ttk.Label(r0, text="Group:", font=("Consolas", 10)).pack(side="left")
        self.group_var = tk.StringVar(value="G1")
        group_cb = ttk.Combobox(r0, textvariable=self.group_var, values=["G1", "G2"],
                                 width=4, state="readonly", font=("Consolas", 10))
        group_cb.pack(side="left", padx=(4, 16))
        group_cb.bind("<<ComboboxSelected>>", lambda e: self._update_condition())

        ttk.Label(r0, text="Session:", font=("Consolas", 10)).pack(side="left")
        self.session_var = tk.StringVar(value="1")
        session_cb = ttk.Combobox(r0, textvariable=self.session_var, values=["1", "2"],
                                   width=4, state="readonly", font=("Consolas", 10))
        session_cb.pack(side="left", padx=(4, 16))
        session_cb.bind("<<ComboboxSelected>>", lambda e: self._update_condition())

        # Row 1: Auto-calculated condition display
        r1 = ttk.Frame(frame)
        r1.pack(fill="x", pady=4)

        ttk.Label(r1, text="Condition:", font=("Consolas", 10)).pack(side="left")
        self.condition_var = tk.StringVar(value="")
        self.condition_label = tk.Label(r1, textvariable=self.condition_var,
                                         font=("Consolas", 11, "bold"), fg="#1565C0")
        self.condition_label.pack(side="left", padx=(4, 16))

        ttk.Label(r1, text="Reciprocity:", font=("Consolas", 10)).pack(side="left")
        self.reciprocity_var = tk.StringVar(value="")
        self.reciprocity_label = tk.Label(r1, textvariable=self.reciprocity_var,
                                           font=("Consolas", 11, "bold"), fg="#2E7D32")
        self.reciprocity_label.pack(side="left", padx=4)

        # Row 2: Description
        r2 = ttk.Frame(frame)
        r2.pack(fill="x", pady=(0, 4))
        self.desc_var = tk.StringVar(value="")
        ttk.Label(r2, textvariable=self.desc_var, font=("Consolas", 9),
                  foreground="#616161").pack(side="left")

        # Row 3: Start/End buttons + timer + notes
        r3 = ttk.Frame(frame)
        r3.pack(fill="x", pady=4)

        self.start_btn = ttk.Button(r3, text="START SESSION",
                                     command=self._start_session)
        self.start_btn.pack(side="left", padx=(0, 8))

        self.end_btn = ttk.Button(r3, text="END SESSION",
                                   command=self._end_session, state="disabled")
        self.end_btn.pack(side="left", padx=(0, 16))

        self.timer_var = tk.StringVar(value="00:00")
        self.timer_label = tk.Label(r3, textvariable=self.timer_var,
                                     font=("Consolas", 16, "bold"), fg="#37474F")
        self.timer_label.pack(side="left", padx=8)

        self.session_status_var = tk.StringVar(value="No active session")
        tk.Label(r3, textvariable=self.session_status_var,
                 font=("Consolas", 10, "bold"), fg="#9E9E9E").pack(side="right")

        # Row 4: Notes
        r4 = ttk.Frame(frame)
        r4.pack(fill="x", pady=(4, 0))
        ttk.Label(r4, text="Notes:", font=("Consolas", 10)).pack(side="left")
        self.notes_var = tk.StringVar(value="")
        ttk.Entry(r4, textvariable=self.notes_var, font=("Consolas", 10)).pack(
            side="left", fill="x", expand=True, padx=4)

        self._update_condition()

    # ---- Panel 2: Live Monitor ----
    def _build_monitor_panel(self, parent):
        frame = ttk.LabelFrame(parent, text="Live Monitor", padding=8)
        parent.add(frame, weight=1)

        # State display
        self.state_label = tk.Label(frame, text="  DISCONNECTED  ",
                                     font=("Consolas", 14, "bold"),
                                     bg="#607D8B", fg="white", padx=12, pady=6,
                                     relief="raised")
        self.state_label.pack(fill="x", pady=(0, 6))

        # Info row: turn, silence retries, VAD, recording
        info = ttk.Frame(frame)
        info.pack(fill="x", pady=2)

        ttk.Label(info, text="Turn:", font=("Consolas", 10)).pack(side="left")
        self.turn_var = tk.StringVar(value="-/-")
        ttk.Label(info, textvariable=self.turn_var,
                  font=("Consolas", 10, "bold")).pack(side="left", padx=(2, 12))

        ttk.Label(info, text="Silence:", font=("Consolas", 10)).pack(side="left")
        self.silence_var = tk.StringVar(value="-")
        ttk.Label(info, textvariable=self.silence_var,
                  font=("Consolas", 10, "bold")).pack(side="left", padx=(2, 12))

        ttk.Label(info, text="VAD:", font=("Consolas", 10)).pack(side="left")
        self.vad_var = tk.StringVar(value="-")
        self.vad_label = tk.Label(info, textvariable=self.vad_var,
                                   font=("Consolas", 10, "bold"), fg="#607D8B")
        self.vad_label.pack(side="left", padx=(2, 12))

        ttk.Label(info, text="Rec:", font=("Consolas", 10)).pack(side="left")
        self.rec_var = tk.StringVar(value="--")
        self.rec_label = tk.Label(info, textvariable=self.rec_var,
                                   font=("Consolas", 10, "bold"), fg="#607D8B")
        self.rec_label.pack(side="left", padx=2)

        # Audio level bar
        level_row = ttk.Frame(frame)
        level_row.pack(fill="x", pady=(4, 2))

        ttk.Label(level_row, text="Audio:", font=("Consolas", 9)).pack(side="left")
        self.level_canvas = tk.Canvas(level_row, height=16, bg="#263238",
                                       relief="sunken", bd=1)
        self.level_canvas.pack(side="left", fill="x", expand=True, padx=4)
        self.level_text_var = tk.StringVar(value="--")
        ttk.Label(level_row, textvariable=self.level_text_var,
                  font=("Consolas", 9)).pack(side="left")

        # Current conversation snapshot
        conv = ttk.Frame(frame)
        conv.pack(fill="x", pady=(6, 0))

        self.conv_vars = {}
        for key, label in [("user", "User:"), ("english", "Eng:"), ("robot", "Sota:")]:
            row = ttk.Frame(conv)
            row.pack(fill="x", pady=1)
            ttk.Label(row, text=label, font=("Consolas", 9), width=6,
                      anchor="e").pack(side="left")
            var = tk.StringVar(value="-")
            lbl = ttk.Label(row, textvariable=var, font=("Consolas", 9, "bold"),
                            wraplength=700, justify="left")
            lbl.pack(side="left", padx=4)
            self.conv_vars[key] = var

        # Language switch row
        lang_row = ttk.Frame(frame)
        lang_row.pack(fill="x", pady=(6, 2))

        ttk.Label(lang_row, text="Response Language:",
                  font=("Consolas", 10)).pack(side="left")
        self.lang_var = tk.StringVar(value="en")
        self.lang_label = tk.Label(lang_row, text=" ENGLISH ",
                                    font=("Consolas", 10, "bold"),
                                    bg="#1565C0", fg="white", padx=6, pady=2)
        self.lang_label.pack(side="left", padx=(4, 8))

        self.lang_en_btn = ttk.Button(lang_row, text="English",
                                       command=lambda: self._set_language("en"))
        self.lang_en_btn.pack(side="left", padx=2)
        self.lang_ja_btn = ttk.Button(lang_row, text="Japanese",
                                       command=lambda: self._set_language("ja"))
        self.lang_ja_btn.pack(side="left", padx=2)

        # User profile
        profile_row = ttk.Frame(frame)
        profile_row.pack(fill="x", pady=(4, 0))
        ttk.Label(profile_row, text="Profile:", font=("Consolas", 9)).pack(side="left")
        self.profile_var = tk.StringVar(value="-")
        ttk.Label(profile_row, textvariable=self.profile_var,
                  font=("Consolas", 9, "bold")).pack(side="left", padx=4)

    # ---- Panel 3: Conversation Log ----
    def _build_conversation_panel(self, parent):
        frame = ttk.LabelFrame(parent, text="Conversation Log", padding=4)
        parent.add(frame, weight=2)

        self.conv_log = scrolledtext.ScrolledText(frame, height=12,
                                                    font=("Consolas", 10),
                                                    state="disabled", wrap="word")
        self.conv_log.pack(fill="both", expand=True)

        # Color tags
        self.conv_log.tag_configure("user", foreground="#1565C0",
                                     font=("Consolas", 10, "bold"))
        self.conv_log.tag_configure("robot", foreground="#2E7D32",
                                     font=("Consolas", 10, "bold"))
        self.conv_log.tag_configure("system", foreground="#9E9E9E",
                                     font=("Consolas", 9, "italic"))
        self.conv_log.tag_configure("timestamp", foreground="#78909C",
                                     font=("Consolas", 9))

        # Bottom bar: clear + export info
        bar = ttk.Frame(frame)
        bar.pack(fill="x", pady=(4, 0))
        ttk.Button(bar, text="Clear", command=self._clear_conv_log).pack(side="left")
        self.log_info_var = tk.StringVar(value="")
        ttk.Label(bar, textvariable=self.log_info_var,
                  font=("Consolas", 9), foreground="#9E9E9E").pack(side="right")

    # ================================================================
    # Condition Auto-calculation
    # ================================================================

    def _update_condition(self):
        group = self.group_var.get()
        session = self.session_var.get()
        key = (group, session)
        if key in CONDITION_MAP:
            code, recip, desc = CONDITION_MAP[key]
            self.condition_var.set(code)
            self.reciprocity_var.set(recip)
            self.desc_var.set(desc)

            if code == "REMEMBER":
                self.condition_label.config(fg="#2E7D32")
            elif code == "NO-REMEMBER":
                self.condition_label.config(fg="#C62828")
            else:
                self.condition_label.config(fg="#1565C0")

            if recip == "WR":
                self.reciprocity_label.config(fg="#2E7D32")
            else:
                self.reciprocity_label.config(fg="#9E9E9E")
        else:
            self.condition_var.set("?")
            self.reciprocity_var.set("?")
            self.desc_var.set("")

    # ================================================================
    # Session Control
    # ================================================================

    def _start_session(self):
        if not self.connected:
            messagebox.showwarning("Not Connected",
                                    "Connect to robot first before starting a session.")
            return

        pid = self.pid_var.get().strip()
        if not pid:
            messagebox.showwarning("Missing ID", "Enter a Participant ID (e.g., P01).")
            return

        group = self.group_var.get()
        session = self.session_var.get()
        key = (group, session)
        if key not in CONDITION_MAP:
            messagebox.showerror("Invalid", "Unknown group/session combination.")
            return

        code, recip, desc = CONDITION_MAP[key]

        # Confirm
        msg = ("Start session?\n\n"
               "Participant: {}\n"
               "Group: {} | Session: {}\n"
               "Condition: {} ({})\n"
               "Reciprocity: {}").format(pid, group, session, code, desc, recip)
        if not messagebox.askyesno("Confirm Start", msg):
            return

        self.session_active = True
        self.session_start_time = datetime.datetime.now()

        # Lock inputs
        self.start_btn.config(state="disabled")
        self.end_btn.config(state="normal")
        self.session_status_var.set("SESSION ACTIVE")

        # Start logger
        self.logger.start_session(pid, group, session, code,
                                   "{} ({})".format(code, desc), recip)

        self._append_conv_log("system",
                               "--- Session started: {} {} S{} {} ---".format(
                                   pid, group, session, code))

        self._log("SESSION STARTED: {} {} S{} {}".format(pid, group, session, code))
        if self.logger.txt_path:
            self.log_info_var.set("Log: " + os.path.basename(self.logger.txt_path))

        # Start timer
        self._update_timer()

    def _end_session(self):
        if not self.session_active:
            return

        if not messagebox.askyesno("Confirm End", "End this session?"):
            return

        pid = self.pid_var.get().strip()
        group = self.group_var.get()
        session = self.session_var.get()
        code, recip, desc = CONDITION_MAP.get((group, session), ("?", "?", "?"))
        notes = self.notes_var.get().strip()

        conv_file = self.logger.end_session(
            pid, group, session, code,
            "{} ({})".format(code, desc), recip,
            self.session_start_time, notes)

        self._append_conv_log("system", "--- Session ended ---")
        self._log("SESSION ENDED: {} (saved to {})".format(pid, conv_file))

        self.session_active = False
        self.session_start_time = None
        self.start_btn.config(state="normal")
        self.end_btn.config(state="disabled")
        self.session_status_var.set("Session complete")
        self.timer_var.set("00:00")

    def _update_timer(self):
        if not self.session_active or self.session_start_time is None:
            return
        elapsed = (datetime.datetime.now() - self.session_start_time).total_seconds()
        mins = int(elapsed) // 60
        secs = int(elapsed) % 60
        self.timer_var.set("{:02d}:{:02d}".format(mins, secs))
        self.root.after(1000, self._update_timer)

    # ================================================================
    # Connection
    # ================================================================

    def _set_language(self, lang):
        """Send language change to robot via POST /set_language."""
        if not self.connected:
            messagebox.showwarning("Not Connected",
                                    "Connect to robot first.")
            return

        def do_set():
            url = "http://{}:{}/set_language".format(self.robot_ip, self.status_port)
            body = '{{"language":"{}"}}'.format(lang).encode("utf-8")
            try:
                req = Request(url, data=body)
                req.add_header("Content-Type", "application/json")
                req.get_method = lambda: "POST"
                resp = urlopen(req, timeout=3)
                resp.read()
                self.root.after(0, lambda: self._on_language_set(lang))
            except Exception as e:
                self.root.after(0, lambda: self._log(
                    "Language change failed: {}".format(str(e))))

        threading.Thread(target=do_set, daemon=True).start()

    def _on_language_set(self, lang):
        self.lang_var.set(lang)
        if lang == "ja":
            self.lang_label.config(text=" JAPANESE ", bg="#C62828")
        else:
            self.lang_label.config(text=" ENGLISH ", bg="#1565C0")
        self._log("Language set to: {}".format(lang.upper()))
        if self.session_active:
            self._append_conv_log("system",
                                   "[Language changed to {}]".format(lang.upper()))

    def _toggle_connect(self):
        if self.polling:
            self._disconnect()
        else:
            self._connect()

    def _connect(self):
        self.robot_ip = self.robot_ip_var.get().strip()
        self.status_port = int(self.status_port_var.get().strip())
        self._log("Connecting to {}:{}...".format(self.robot_ip, self.status_port))
        self.connect_btn.config(text="Connecting...", state="disabled")

        def try_connect():
            url = "http://{}:{}/health".format(self.robot_ip, self.status_port)
            try:
                resp = urlopen(url, timeout=3)
                resp.read()
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
            self._log("Connected to robot!")
            self._poll()
        else:
            self.connected = False
            self.conn_indicator.config(text=" FAILED ", bg="#c62828")
            self.connect_btn.config(text="Connect")
            self._log("Connection failed: {}".format(err))

    def _disconnect(self):
        self.polling = False
        self.connected = False
        self.connect_btn.config(text="Connect")
        self.conn_indicator.config(text=" DISCONNECTED ", bg="#c62828")
        self.state_label.config(text="  DISCONNECTED  ", bg="#607D8B", fg="white")
        self._log("Disconnected.")

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
            self.root.after(2000, self._poll)

    def _update_ui(self, data):
        if not self.polling:
            return

        self.conn_indicator.config(text=" CONNECTED ", bg="#2e7d32")

        # State
        state = data.get("state", "?")
        colors = STATE_COLORS.get(state, ("#607D8B", "white"))
        label = STATE_LABELS.get(state, state.upper())
        self.state_label.config(text="  " + label + "  ", bg=colors[0], fg=colors[1])

        if state != self.prev_state and self.prev_state:
            self._log("State: {} -> {}".format(self.prev_state.upper(), state.upper()))
            if self.session_active:
                self._append_conv_log("system", "[State: {}]".format(label))
        self.prev_state = state

        # Turn / Silence
        turn = data.get("turn", 0)
        max_turns = data.get("maxTurns", 0)
        if max_turns > 0:
            self.turn_var.set("{}/{}".format(turn, max_turns))
        else:
            self.turn_var.set(str(turn))
        self.silence_var.set(str(data.get("silenceRetries", 0)))

        # VAD
        vad_working = data.get("vadWorking", False)
        vad_level = data.get("vadLevel", -1)
        is_recording = data.get("isRecording", False)
        is_speech = data.get("isSpeech", False)
        rec_dur = data.get("recordingDurationMs", 0)

        if vad_working:
            self.vad_var.set("OK")
            self.vad_label.config(fg="#2e7d32")
        else:
            self.vad_var.set("N/A")
            self.vad_label.config(fg="#9E9E9E")

        if is_recording:
            self.rec_var.set("{:.1f}s".format(rec_dur / 1000.0))
            self.rec_label.config(fg="#c62828")
        else:
            self.rec_var.set("--")
            self.rec_label.config(fg="#607D8B")

        # Level bar
        self._draw_level_bar(vad_level, is_speech, vad_working)
        self.level_text_var.set(str(vad_level) if vad_level >= 0 else "--")

        if vad_level > 0 and self.session_active:
            self.logger.update_vad(vad_level)

        # Conversation snapshot
        user_text = data.get("lastUserText", "") or ""
        user_en = data.get("lastUserTextEn", "") or ""
        sota_text = data.get("lastSotaText", "") or ""
        lang = data.get("lastDetectedLang", "") or ""

        self.conv_vars["user"].set(user_text if user_text else "-")
        self.conv_vars["english"].set(user_en if user_en else "-")
        self.conv_vars["robot"].set(sota_text if sota_text else "-")

        # Detect new conversation turns for logging
        if self.session_active:
            if user_text and user_text != self._last_user_text:
                self._last_user_text = user_text
                self._append_conv_log("user", user_text)
                if user_en and user_en != user_text:
                    self._append_conv_log("system", "  (EN: {})".format(user_en))
                self.logger.log_turn("USER", user_text, lang, user_en)

            if sota_text and sota_text != self._last_sota_text:
                self._last_sota_text = sota_text
                self._append_conv_log("robot", sota_text)
                self.logger.log_turn("ROBOT", sota_text)

            # Detect goodbye
            if state == "closing":
                lower = user_text.lower() if user_text else ""
                user_bye = any(w in lower for w in
                               ["bye", "goodbye", "see you", "sayonara",
                                "mata ne", "sampai jumpa"])
                self.logger.mark_goodbye(user_bye)

        # Language indicator from robot
        base_lang = data.get("baseLanguage", "en") or "en"
        if base_lang != self.lang_var.get():
            self.lang_var.set(base_lang)
            if base_lang == "ja":
                self.lang_label.config(text=" JAPANESE ", bg="#C62828")
            else:
                self.lang_label.config(text=" ENGLISH ", bg="#1565C0")

        # User profile
        name = data.get("userName", "") or ""
        origin = data.get("userOrigin", "") or ""
        social = data.get("userSocialLevel", "") or ""
        interactions = data.get("userInteractions", 0)
        if name:
            self.profile_var.set("{} | {} | {} | {} interactions".format(
                name, origin if origin else "?", social.upper() if social else "?",
                interactions))
        else:
            self.profile_var.set("-")

        # Next poll
        self.root.after(self.poll_interval, self._poll)

    # ================================================================
    # Level bar
    # ================================================================

    def _draw_level_bar(self, level, is_speech, vad_working):
        c = self.level_canvas
        c.delete("all")
        w = c.winfo_width()
        h = c.winfo_height()
        if w <= 1:
            w = 400
        if h <= 1:
            h = 16

        if level < 0:
            c.create_text(w // 2, h // 2, text="--", fill="#546E7A",
                          font=("Consolas", 8))
            return

        max_level = 2000
        thr_x = int(300.0 / max_level * w)
        c.create_line(thr_x, 0, thr_x, h, fill="#FF9800", width=1)

        bar_x = min(int(float(level) / max_level * w), w)
        color = "#43A047" if is_speech else ("#FDD835" if level > 300 else "#546E7A")
        c.create_rectangle(0, 1, bar_x, h - 1, fill=color, outline="")

    # ================================================================
    # Conversation Log
    # ================================================================

    def _append_conv_log(self, role, text):
        """Append a line to the conversation log with color coding."""
        ts = datetime.datetime.now().strftime("%H:%M:%S")
        self.conv_log.config(state="normal")

        if role == "user":
            self.conv_log.insert("end", "[{}] ".format(ts), "timestamp")
            self.conv_log.insert("end", "USER: {}\n".format(text), "user")
        elif role == "robot":
            self.conv_log.insert("end", "[{}] ".format(ts), "timestamp")
            self.conv_log.insert("end", "SOTA: {}\n".format(text), "robot")
        elif role == "system":
            self.conv_log.insert("end", "[{}] {}\n".format(ts, text), "system")

        self.conv_log.see("end")
        self.conv_log.config(state="disabled")

    def _clear_conv_log(self):
        self.conv_log.config(state="normal")
        self.conv_log.delete("1.0", "end")
        self.conv_log.config(state="disabled")

    # ================================================================
    # Log (status bar style)
    # ================================================================

    def _log(self, msg):
        ts = datetime.datetime.now().strftime("%H:%M:%S")
        print("[{}] {}".format(ts, msg))

    # ================================================================
    # Cleanup
    # ================================================================

    def on_closing(self):
        if self.session_active:
            if messagebox.askyesno("Session Active",
                                    "A session is still active. End it before closing?"):
                self._end_session()
        self.polling = False
        self.root.destroy()


# ================================================================
# Main
# ================================================================

def main():
    robot_ip = "192.168.11.30"
    status_port = 5051

    args = sys.argv[1:]
    i = 0
    while i < len(args):
        if args[i] == "--robot-ip" and i + 1 < len(args):
            robot_ip = args[i + 1]
            i += 2
        elif args[i] == "--status-port" and i + 1 < len(args):
            status_port = int(args[i + 1])
            i += 2
        else:
            robot_ip = args[i]
            i += 1

    root = tk.Tk()
    gui = ResearchGUI(root, robot_ip=robot_ip, status_port=status_port)
    root.protocol("WM_DELETE_CLOSE", gui.on_closing)
    root.mainloop()


if __name__ == "__main__":
    main()
