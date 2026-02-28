#!/usr/bin/env python3
"""
Sota Vision Test — GUI Client
==============================
Connects to VisionTestServer running on Sota robot.
Provides live camera feed with face detection bounding boxes,
PFAGR (Age/Gender) overlay, and photo capture.

Usage:
  python vision_test_gui.py
  python vision_test_gui.py --ip 192.168.11.1 --port 8889

Requirements:
  pip install Pillow
"""

import tkinter as tk
from tkinter import ttk, messagebox
from PIL import Image, ImageTk, ImageDraw, ImageFont
import socket
import struct
import json
import threading
import queue
import io
import os
import sys
import time
import numpy as np
from datetime import datetime

# DeepFace — optional. Install with: pip install deepface tf-keras
try:
    from deepface import DeepFace
    DEEPFACE_AVAILABLE = True
    print("[INFO] DeepFace loaded — ethnicity detection enabled")
except ImportError:
    DEEPFACE_AVAILABLE = False
    print("[WARN] DeepFace not installed — ethnicity detection disabled")
    print("       Run: pip install deepface tf-keras")


# ================================================================
# TCP Connection Manager
# ================================================================

class SotaConnection:
    """Manages TCP connection to VisionTestServer on Sota."""

    def __init__(self):
        self.sock = None
        self.connected = False
        self._lock = threading.Lock()

    def connect(self, host, port=8889, timeout=5):
        """Connect to VisionTestServer."""
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.sock.settimeout(timeout)
        self.sock.connect((host, port))
        self.sock.settimeout(15.0)
        self.sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        self.connected = True

    def disconnect(self):
        """Disconnect cleanly."""
        if self.sock:
            try:
                with self._lock:
                    self.sock.sendall(b"QUIT\n")
                    self._read_line()
            except Exception:
                pass
            try:
                self.sock.close()
            except Exception:
                pass
        self.sock = None
        self.connected = False

    def send_command(self, cmd):
        """Send a text command and return the response line."""
        with self._lock:
            self.sock.sendall((cmd + "\n").encode("utf-8"))
            return self._read_line()

    def send_get_frame(self):
        """Send GET_FRAME. Returns (metadata_dict, jpeg_bytes) or (None, None)."""
        with self._lock:
            self.sock.sendall(b"GET_FRAME\n")
            header = self._read_line()

            if header.startswith("FRAME"):
                meta_len = self._read_int()
                meta_bytes = self._read_exact(meta_len)
                metadata = json.loads(meta_bytes.decode("utf-8"))

                img_len = self._read_int()
                img_bytes = self._read_exact(img_len) if img_len > 0 else None

                return metadata, img_bytes

            # Error or unexpected response
            return {"error": header}, None

    def send_take_photo(self):
        """Send TAKE_PHOTO. Returns jpeg_bytes or None."""
        with self._lock:
            self.sock.sendall(b"TAKE_PHOTO\n")
            header = self._read_line()

            if header.startswith("PHOTO"):
                img_len = self._read_int()
                if img_len > 0:
                    return self._read_exact(img_len)
                return None

            # Error
            return None

    # --- Low-level I/O ---

    def _read_line(self):
        """Read a newline-terminated string."""
        buf = b""
        while True:
            ch = self.sock.recv(1)
            if not ch:
                raise ConnectionError("Connection closed by server")
            if ch == b"\n":
                return buf.decode("utf-8", errors="replace")
            buf += ch

    def _read_int(self):
        """Read a 4-byte big-endian integer."""
        data = self._read_exact(4)
        return struct.unpack(">i", data)[0]

    def _read_exact(self, n):
        """Read exactly n bytes."""
        buf = b""
        while len(buf) < n:
            chunk = self.sock.recv(min(n - len(buf), 65536))
            if not chunk:
                raise ConnectionError("Connection lost while reading data")
            buf += chunk
        return buf


# ================================================================
# Main GUI Application
# ================================================================

class VisionTestGUI:
    """Sota Vision Test — tkinter GUI client."""

    CANVAS_W = 640
    CANVAS_H = 480

    # Colors
    COLOR_BG = "#1a1a2e"
    COLOR_FACE_BOX = "#00ff88"
    COLOR_PFAGR_TEXT = "#ffdd44"
    COLOR_INFO_TEXT = "#ffffff"

    def __init__(self, default_ip="192.168.11.1", default_port=8889):
        self.root = tk.Tk()
        self.root.title("Sota Vision Test")
        self.root.resizable(False, False)
        self.root.configure(bg="#f0f0f0")

        self.conn = SotaConnection()
        self.tracking = False
        self.pfagr = False
        self.streaming = False
        self.frame_thread = None
        self._photo_pause_until = 0
        self._current_tk_image = None  # prevent GC

        self.default_ip = default_ip
        self.default_port = default_port

        # Photo save directory
        self.photo_dir = os.path.join(
            os.path.dirname(os.path.abspath(__file__)), "photos")
        os.makedirs(self.photo_dir, exist_ok=True)

        # FPS counter
        self._frame_times = []

        # DeepFace race analysis (async, background thread)
        self._deepface_available = DEEPFACE_AVAILABLE
        self._race_queue   = queue.Queue(maxsize=1)   # max 1 pending job
        self._race_last    = 0.0                       # timestamp of last trigger
        self._race_result  = None                      # latest result string e.g. "Asian (87%)"
        self._race_running = False                     # True while DeepFace is processing
        self._race_interval = 5.0                      # seconds between analyses
        if self._deepface_available:
            threading.Thread(target=self._race_worker, daemon=True).start()

        self._setup_gui()
        self._update_button_states()

    # ================================================================
    # GUI Setup
    # ================================================================

    def _setup_gui(self):
        pad = {"padx": 10, "pady": 5}

        # --- Connection bar ---
        conn_frame = ttk.LabelFrame(self.root, text="Connection", padding=8)
        conn_frame.pack(padx=10, pady=(10, 5), fill="x")

        ttk.Label(conn_frame, text="Sota IP:").pack(side="left")
        self.ip_var = tk.StringVar(value=self.default_ip)
        ttk.Entry(conn_frame, textvariable=self.ip_var, width=16).pack(
            side="left", padx=(4, 8))

        ttk.Label(conn_frame, text="Port:").pack(side="left")
        self.port_var = tk.StringVar(value=str(self.default_port))
        ttk.Entry(conn_frame, textvariable=self.port_var, width=6).pack(
            side="left", padx=(4, 12))

        self.conn_btn = ttk.Button(
            conn_frame, text="Connect", command=self._toggle_connection)
        self.conn_btn.pack(side="left")

        self.conn_status = ttk.Label(
            conn_frame, text="  Disconnected", foreground="red")
        self.conn_status.pack(side="right", padx=5)

        # --- Camera feed ---
        feed_frame = ttk.LabelFrame(self.root, text="Camera Feed", padding=4)
        feed_frame.pack(**pad)

        self.canvas = tk.Canvas(
            feed_frame, width=self.CANVAS_W, height=self.CANVAS_H,
            bg=self.COLOR_BG, highlightthickness=0)
        self.canvas.pack()
        self._draw_placeholder("Not Connected")

        # --- Control buttons ---
        ctrl_frame = ttk.Frame(self.root, padding=4)
        ctrl_frame.pack(padx=10, pady=5, fill="x")

        self.track_btn = ttk.Button(
            ctrl_frame, text="Start Face Tracking",
            command=self._toggle_tracking, width=24)
        self.track_btn.pack(side="left", padx=4, expand=True)

        self.pfagr_btn = ttk.Button(
            ctrl_frame, text="Enable PFAGR",
            command=self._toggle_pfagr, width=24)
        self.pfagr_btn.pack(side="left", padx=4, expand=True)

        self.photo_btn = ttk.Button(
            ctrl_frame, text="Take Photo",
            command=self._take_photo, width=24)
        self.photo_btn.pack(side="left", padx=4, expand=True)

        # --- Detection info panel ---
        info_frame = ttk.LabelFrame(self.root, text="Detection Info", padding=6)
        info_frame.pack(padx=10, pady=5, fill="x")

        # Row 1
        row1 = ttk.Frame(info_frame)
        row1.pack(fill="x")
        self.lbl_faces = ttk.Label(row1, text="Faces: —", width=14)
        self.lbl_faces.pack(side="left", padx=6)
        self.lbl_smile = ttk.Label(row1, text="Smile: —", width=14)
        self.lbl_smile.pack(side="left", padx=6)
        self.lbl_fps = ttk.Label(row1, text="FPS: —", width=14)
        self.lbl_fps.pack(side="left", padx=6)
        self.lbl_client_fps = ttk.Label(row1, text="Client FPS: —", width=16)
        self.lbl_client_fps.pack(side="left", padx=6)

        # Row 2
        row2 = ttk.Frame(info_frame)
        row2.pack(fill="x", pady=(4, 0))
        self.lbl_age = ttk.Label(row2, text="Age: —", width=14)
        self.lbl_age.pack(side="left", padx=6)
        self.lbl_gender = ttk.Label(row2, text="Gender: —", width=14)
        self.lbl_gender.pack(side="left", padx=6)
        self.lbl_race = ttk.Label(row2, text="Race: —", width=18)
        self.lbl_race.pack(side="left", padx=6)
        self.lbl_pose = ttk.Label(row2, text="Pose: —", width=22)
        self.lbl_pose.pack(side="left", padx=6)

        # --- Status bar ---
        self.status_var = tk.StringVar(value="Ready — Click Connect to start")
        ttk.Label(self.root, textvariable=self.status_var,
                  relief="sunken", padding=4).pack(
            padx=10, pady=(0, 10), fill="x")

    # ================================================================
    # Helpers
    # ================================================================

    def _draw_placeholder(self, text):
        """Draw centered placeholder text on canvas."""
        self.canvas.delete("all")
        self.canvas.create_text(
            self.CANVAS_W // 2, self.CANVAS_H // 2,
            text=text, fill="#555555", font=("Segoe UI", 18))

    def _update_button_states(self):
        """Update button text and enabled/disabled states."""
        connected = self.conn.connected

        for btn in [self.track_btn, self.pfagr_btn, self.photo_btn]:
            btn.config(state="normal" if connected else "disabled")

        self.conn_btn.config(text="Disconnect" if connected else "Connect")
        self.conn_status.config(
            text="  Connected" if connected else "  Disconnected",
            foreground="#008800" if connected else "red")

        if self.tracking:
            self.track_btn.config(text="[ON] Stop Face Tracking")
        else:
            self.track_btn.config(text="[OFF] Start Face Tracking")

        if self.pfagr:
            self.pfagr_btn.config(text="[ON] Disable PFAGR")
        else:
            self.pfagr_btn.config(text="[OFF] Enable PFAGR")

    def _get_font(self, size=14, bold=False):
        """Get a PIL ImageFont, with fallback."""
        weight = "bold" if bold else "normal"
        try:
            return ImageFont.truetype("arial.ttf", size)
        except Exception:
            try:
                return ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", size)
            except Exception:
                return ImageFont.load_default()

    # ================================================================
    # Connection
    # ================================================================

    def _toggle_connection(self):
        if self.conn.connected:
            self._disconnect()
        else:
            self._connect()

    def _connect(self):
        host = self.ip_var.get().strip()
        port = int(self.port_var.get().strip())
        self.status_var.set(f"Connecting to {host}:{port}...")
        self.root.update_idletasks()

        try:
            self.conn.connect(host, port)
            self.status_var.set(f"Connected to {host}:{port}")
            self._update_button_states()
            self._draw_placeholder("Connected!\nClick 'Start Face Tracking' to begin")
        except Exception as e:
            self.status_var.set(f"Connection failed: {e}")
            messagebox.showerror("Connection Error",
                f"Cannot connect to {host}:{port}\n\n{e}\n\n"
                "Make sure VisionTestServer is running on Sota.")

    def _disconnect(self):
        self.streaming = False
        self.tracking = False
        self.pfagr = False

        if self.frame_thread and self.frame_thread.is_alive():
            self.frame_thread.join(timeout=3)

        self.conn.disconnect()
        self._update_button_states()
        self._draw_placeholder("Disconnected")
        self._clear_info_labels()
        self.status_var.set("Disconnected")

    # ================================================================
    # Face Tracking toggle
    # ================================================================

    def _toggle_tracking(self):
        if not self.conn.connected:
            return
        # Disable button during operation
        self.track_btn.config(state="disabled")
        threading.Thread(target=self._do_toggle_tracking, daemon=True).start()

    def _do_toggle_tracking(self):
        try:
            if self.tracking:
                # Stop streaming first
                self.streaming = False
                if self.frame_thread and self.frame_thread.is_alive():
                    self.frame_thread.join(timeout=3)

                self.conn.send_command("STOP_TRACKING")
                self.tracking = False
                self.root.after(0, lambda: self._draw_placeholder(
                    "Face Tracking Stopped"))
                self.root.after(0, lambda: self.status_var.set(
                    "Face tracking stopped"))
                self.root.after(0, self._clear_info_labels)
            else:
                resp = self.conn.send_command("START_TRACKING")
                self.tracking = True
                self.streaming = True
                self._frame_times = []

                self.frame_thread = threading.Thread(
                    target=self._frame_loop, daemon=True)
                self.frame_thread.start()
                self.root.after(0, lambda: self.status_var.set(
                    "Face tracking started — streaming live"))
        except Exception as e:
            self.root.after(0, lambda: self.status_var.set(f"Error: {e}"))
        finally:
            self.root.after(0, self._update_button_states)

    # ================================================================
    # PFAGR toggle
    # ================================================================

    def _toggle_pfagr(self):
        if not self.conn.connected:
            return
        self.pfagr_btn.config(state="disabled")
        threading.Thread(target=self._do_toggle_pfagr, daemon=True).start()

    def _do_toggle_pfagr(self):
        try:
            if self.pfagr:
                self.conn.send_command("DISABLE_PFAGR")
                self.pfagr = False
                self.root.after(0, lambda: self.status_var.set(
                    "PFAGR disabled"))
            else:
                self.conn.send_command("ENABLE_PFAGR")
                self.pfagr = True
                self.root.after(0, lambda: self.status_var.set(
                    "PFAGR enabled — Age/Gender estimation active"))
        except Exception as e:
            self.root.after(0, lambda: self.status_var.set(f"Error: {e}"))
        finally:
            self.root.after(0, self._update_button_states)

    # ================================================================
    # Photo capture
    # ================================================================

    def _take_photo(self):
        if not self.conn.connected:
            return
        self.photo_btn.config(state="disabled")
        self.status_var.set("Capturing photo...")
        threading.Thread(target=self._do_take_photo, daemon=True).start()

    def _do_take_photo(self):
        try:
            # Pause live feed during photo capture
            self._photo_pause_until = time.time() + 4

            img_bytes = self.conn.send_take_photo()
            if img_bytes:
                # Save to photos directory
                ts = datetime.now().strftime("%Y%m%d_%H%M%S")
                filename = f"sota_photo_{ts}.jpg"
                filepath = os.path.join(self.photo_dir, filename)
                with open(filepath, "wb") as f:
                    f.write(img_bytes)

                # Display on canvas
                img = Image.open(io.BytesIO(img_bytes))
                self.root.after(0, self._display_photo, img, filepath)
            else:
                self.root.after(0, lambda: self.status_var.set(
                    "Photo capture failed — no data received"))
        except Exception as e:
            self.root.after(0, lambda: self.status_var.set(
                f"Photo error: {e}"))
        finally:
            self.root.after(0, lambda: self.photo_btn.config(state="normal"))

    def _display_photo(self, img, filepath):
        """Display captured photo on canvas with save info overlay."""
        display = img.resize((self.CANVAS_W, self.CANVAS_H), Image.LANCZOS).convert("RGBA")
        overlay = Image.new("RGBA", display.size, (0, 0, 0, 0))
        draw = ImageDraw.Draw(overlay)

        # Draw save info overlay at bottom
        font = self._get_font(12)
        text = f"Saved: {os.path.basename(filepath)}  ({img.width}x{img.height})"
        # Dark background bar
        draw.rectangle(
            [0, self.CANVAS_H - 30, self.CANVAS_W, self.CANVAS_H],
            fill=(0, 0, 0, 180))
        draw.text((10, self.CANVAS_H - 24), text,
                  fill="#00ff88", font=font)

        # "PHOTO" label at top
        font_big = self._get_font(16, bold=True)
        draw.text((10, 8), "PHOTO CAPTURED", fill="#ffdd44", font=font_big)

        display = Image.alpha_composite(display, overlay).convert("RGB")
        self._current_tk_image = ImageTk.PhotoImage(display)
        self.canvas.delete("all")
        self.canvas.create_image(0, 0, anchor="nw",
                                 image=self._current_tk_image)
        self.status_var.set(f"Photo saved: {filepath}")

    # ================================================================
    # DeepFace race analysis (background worker thread)
    # ================================================================

    def _race_worker(self):
        """
        Background thread: receives face-crop numpy arrays from _race_queue,
        runs DeepFace.analyze, posts result back to main thread.
        Stays alive for the entire app session.
        """
        while True:
            try:
                face_arr = self._race_queue.get(timeout=1.0)
                if face_arr is None:
                    break  # poison pill — exit

                self._race_running = True
                try:
                    result = DeepFace.analyze(
                        face_arr,
                        actions=["race"],
                        enforce_detection=False,
                        silent=True,
                        detector_backend="skip"  # image is already a crop
                    )
                    if isinstance(result, list):
                        result = result[0]

                    dominant = result.get("dominant_race", "")
                    scores   = result.get("race", {})
                    pct      = int(scores.get(dominant, 0))
                    label    = f"{dominant.title()} ({pct}%)"

                    self._race_result = label
                    self.root.after(0, self._update_race_label, label)

                except Exception as e:
                    self.root.after(0, self._update_race_label, "Race: Error")
                finally:
                    self._race_running = False

            except queue.Empty:
                continue
            except Exception:
                self._race_running = False

    def _update_race_label(self, label):
        """Update the Race info label on the main thread."""
        self.lbl_race.config(text=f"Race: {label}")

    # ================================================================
    # Frame streaming loop (background thread)
    # ================================================================

    def _frame_loop(self):
        """Continuously request frames from Sota and update display."""
        while self.streaming and self.conn.connected:
            try:
                # Pause during photo capture
                if time.time() < self._photo_pause_until:
                    time.sleep(0.2)
                    continue

                metadata, img_bytes = self.conn.send_get_frame()
                if metadata is not None:
                    # Track client-side FPS
                    now = time.time()
                    self._frame_times.append(now)
                    # Keep only last 30 timestamps
                    self._frame_times = [
                        t for t in self._frame_times if now - t < 2.0]

                    self.root.after(0, self._render_frame,
                                   metadata, img_bytes)

                # Rate limit: ~20 FPS max
                time.sleep(0.03)

            except ConnectionError:
                if self.streaming:
                    self.root.after(0, lambda: self.status_var.set(
                        "Connection lost"))
                    self.root.after(0, self._disconnect)
                break
            except Exception as e:
                if self.streaming:
                    self.root.after(0, lambda err=str(e):
                        self.status_var.set(f"Stream error: {err}"))
                time.sleep(0.5)

    # ================================================================
    # Frame rendering (runs on main thread via root.after)
    # ================================================================

    def _render_frame(self, metadata, img_bytes):
        """Render a frame on the canvas with overlays."""
        # Update info labels
        self._update_info_labels(metadata)

        if not img_bytes:
            if metadata.get("faceDetected"):
                self._draw_placeholder("Face detected\n(no image data)")
            else:
                self._draw_placeholder(
                    "Tracking active\nWaiting for face...")
            return

        try:
            # Decode image
            img = Image.open(io.BytesIO(img_bytes))
            orig_w, orig_h = img.size

            # ── DeepFace race analysis ─────────────────────────────────
            faces = metadata.get("faces", [])
            if (self._deepface_available
                    and faces
                    and not self._race_running
                    and not self._race_queue.full()
                    and time.time() - self._race_last > self._race_interval):
                try:
                    f = faces[0]
                    x, y, w, h = f["x"], f["y"], f["w"], f["h"]
                    # Expand bounding box by 20% for better context
                    pad = int(min(w, h) * 0.2)
                    x1 = max(0, x - pad)
                    y1 = max(0, y - pad)
                    x2 = min(orig_w, x + w + pad)
                    y2 = min(orig_h, y + h + pad)
                    face_crop = img.crop((x1, y1, x2, y2))
                    face_arr  = np.array(face_crop.convert("RGB"))
                    self._race_queue.put_nowait(face_arr)
                    self._race_last = time.time()
                except Exception:
                    pass
            # ──────────────────────────────────────────────────────────

            # Resize to canvas and convert to RGBA for overlay drawing
            display = img.resize(
                (self.CANVAS_W, self.CANVAS_H), Image.LANCZOS).convert("RGBA")
            overlay = Image.new("RGBA", display.size, (0, 0, 0, 0))
            draw = ImageDraw.Draw(overlay)

            # Scale factors for bounding boxes
            sx = self.CANVAS_W / orig_w
            sy = self.CANVAS_H / orig_h

            # Draw face bounding boxes
            font = self._get_font(13)
            font_small = self._get_font(11)

            for i, face in enumerate(faces):
                x1 = int(face["x"] * sx)
                y1 = int(face["y"] * sy)
                x2 = int((face["x"] + face["w"]) * sx)
                y2 = int((face["y"] + face["h"]) * sy)

                # Green bounding box
                draw.rectangle([x1, y1, x2, y2],
                               outline=self.COLOR_FACE_BOX, width=2)

                # Face label above box
                label_parts = []

                # PFAGR info
                if metadata.get("ageDetected"):
                    age = metadata.get("age", "?")
                    gender = metadata.get("gender", "?")
                    label_parts.append(f"Age:{age} {gender}")

                # Race — prefer DeepFace result over server (PFAGR always -1)
                race_display = self._race_result or metadata.get("race")
                if race_display:
                    # Remove "Race: " prefix if present (from _update_race_label)
                    clean = race_display.replace("Race: ", "")
                    label_parts.append(f"Race:{clean}")

                # Smile
                smile = metadata.get("smile", 0)
                if smile > 0:
                    label_parts.append(f"Smile:{smile}")

                if label_parts:
                    label = "  |  ".join(label_parts)
                    # Background for text
                    tw = len(label) * 7 + 8
                    draw.rectangle(
                        [x1, y1 - 20, x1 + tw, y1],
                        fill=(0, 0, 0, 200))
                    draw.text(
                        (x1 + 4, y1 - 18), label,
                        fill=self.COLOR_PFAGR_TEXT, font=font_small)

            # OSD: tracking status
            osd_parts = []
            if self.tracking:
                osd_parts.append("TRACKING")
            if self.pfagr:
                osd_parts.append("PFAGR")
            if self._deepface_available:
                osd_parts.append("DeepFace" + (" ..." if self._race_running else ""))
            if osd_parts:
                osd_text = " | ".join(osd_parts)
                draw.rectangle([0, 0, len(osd_text) * 8 + 16, 22],
                               fill=(0, 0, 0, 160))
                draw.text((8, 4), osd_text,
                          fill="#00ff88", font=font_small)

            # Client FPS
            if len(self._frame_times) >= 2:
                duration = self._frame_times[-1] - self._frame_times[0]
                if duration > 0:
                    cfps = (len(self._frame_times) - 1) / duration
                    self.lbl_client_fps.config(
                        text=f"Client FPS: {cfps:.1f}")

            # Composite overlay onto display and convert to RGB for Tk
            display = Image.alpha_composite(display, overlay).convert("RGB")

            # Update canvas
            self._current_tk_image = ImageTk.PhotoImage(display)
            self.canvas.delete("all")
            self.canvas.create_image(
                0, 0, anchor="nw", image=self._current_tk_image)

        except Exception as e:
            self._draw_placeholder(f"Frame decode error:\n{e}")

    # ================================================================
    # Info label updates
    # ================================================================

    def _update_info_labels(self, metadata):
        """Update the detection info panel from metadata."""
        if metadata.get("faceDetected"):
            self.lbl_faces.config(
                text=f"Faces: {metadata.get('faceNum', 0)}")
            self.lbl_smile.config(
                text=f"Smile: {metadata.get('smile', '—')}")

            fps = metadata.get("fps", "—")
            self.lbl_fps.config(text=f"Cam FPS: {fps}")

            if metadata.get("ageDetected"):
                self.lbl_age.config(
                    text=f"Age: {metadata.get('age', '—')}")
                self.lbl_gender.config(
                    text=f"Gender: {metadata.get('gender', '—')}")
                # Prefer DeepFace result; fall back to server PFAGR (usually empty)
                server_race  = metadata.get('race')
                server_score = metadata.get('raceScore', '')
                if self._race_result:
                    # DeepFace result already has confidence embedded e.g. "Asian (87%)"
                    self.lbl_race.config(text=f"Race: {self._race_result}")
                elif self._race_running:
                    self.lbl_race.config(text="Race: analyzing...")
                elif server_race and server_score:
                    self.lbl_race.config(text=f"Race: {server_race} ({server_score})")
                elif server_race:
                    self.lbl_race.config(text=f"Race: {server_race}")
                elif self._deepface_available:
                    self.lbl_race.config(text="Race: waiting...")
            else:
                self.lbl_age.config(text="Age: \u2014")
                self.lbl_gender.config(text="Gender: \u2014")
                self.lbl_race.config(text="Race: \u2014")

            pitch = metadata.get("pitch", 0)
            yaw = metadata.get("yaw", 0)
            roll = metadata.get("roll", 0)
            self.lbl_pose.config(
                text=f"Pose: P={pitch} Y={yaw} R={roll}")
        else:
            self.lbl_faces.config(text="Faces: 0")
            self.lbl_smile.config(text="Smile: —")
            self.lbl_age.config(text="Age: —")
            self.lbl_gender.config(text="Gender: —")
            self.lbl_race.config(text="Race: —")
            self.lbl_pose.config(text="Pose: —")

    def _clear_info_labels(self):
        """Reset all info labels."""
        self.lbl_faces.config(text="Faces: —")
        self.lbl_smile.config(text="Smile: —")
        self.lbl_fps.config(text="FPS: —")
        self.lbl_client_fps.config(text="Client FPS: —")
        self.lbl_age.config(text="Age: —")
        self.lbl_gender.config(text="Gender: —")
        self.lbl_race.config(text="Race: —")
        self.lbl_pose.config(text="Pose: —")
        # Clear DeepFace cached result so next person gets fresh analysis
        self._race_result = None
        self._race_last   = 0.0

    # ================================================================
    # Application lifecycle
    # ================================================================

    def run(self):
        """Start the GUI main loop."""
        self.root.protocol("WM_DELETE_WINDOW", self._on_close)
        self.root.mainloop()

    def _on_close(self):
        """Clean shutdown."""
        self.streaming = False

        if self.conn.connected:
            if self.tracking:
                try:
                    self.conn.send_command("STOP_TRACKING")
                except Exception:
                    pass
            self.conn.disconnect()

        self.root.destroy()


# ================================================================
# Entry point
# ================================================================

def main():
    default_ip = "192.168.11.1"
    default_port = 8889

    # Parse command line args
    args = sys.argv[1:]
    i = 0
    while i < len(args):
        if args[i] == "--ip" and i + 1 < len(args):
            default_ip = args[i + 1]
            i += 2
        elif args[i] == "--port" and i + 1 < len(args):
            default_port = int(args[i + 1])
            i += 2
        else:
            i += 1

    app = VisionTestGUI(default_ip=default_ip, default_port=default_port)
    app.run()


if __name__ == "__main__":
    main()
