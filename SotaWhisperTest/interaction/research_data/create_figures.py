"""
Generate presentation figures for HRI Reciprocity Study.
Figure 1: Experimental Design Diagram (2×2 Mixed Design)
"""

import matplotlib.pyplot as plt
import matplotlib.patches as mpatches
from matplotlib.patches import FancyBboxPatch, FancyArrowPatch
import numpy as np

# ── Color palette ──────────────────────────────────────────────────────
C_BG        = "#FAFBFC"
C_TITLE     = "#1A1A2E"
C_SUBTITLE  = "#4A4A6A"
C_G1_FILL   = "#E8F4FD"   # light blue
C_G1_BORDER = "#2196F3"   # blue
C_G2_FILL   = "#FFF3E0"   # light orange
C_G2_BORDER = "#FF9800"   # orange
C_S1_FILL   = "#F5F5F5"   # light gray
C_S1_BORDER = "#9E9E9E"   # gray
C_ARROW     = "#546E7A"
C_ACCENT    = "#E91E63"   # pink accent
C_BASELINE  = "#66BB6A"   # green for baseline
C_WR        = "#42A5F5"   # blue for with-reciprocity
C_WOR       = "#EF5350"   # red for without-reciprocity


def draw_experimental_design():
    """
    Creates a clean experimental design diagram showing:
    - 2 groups × 2 sessions
    - Participant allocation
    - Conditions per cell
    - Arrows showing flow
    """
    fig, ax = plt.subplots(1, 1, figsize=(14, 8.5))
    fig.patch.set_facecolor(C_BG)
    ax.set_facecolor(C_BG)
    ax.set_xlim(0, 14)
    ax.set_ylim(0, 9)
    ax.axis("off")

    # ── Title ──────────────────────────────────────────────────────────
    ax.text(7, 8.55, "Experimental Design: 2-Session Mixed Design",
            fontsize=18, fontweight="bold", ha="center", va="center",
            color=C_TITLE, fontfamily="sans-serif")
    ax.text(7, 8.15,
            "Between-subjects: Group (Treatment vs Control)  ·  Within-subjects: Session (S1 vs S2)",
            fontsize=10.5, ha="center", va="center", color=C_SUBTITLE,
            fontstyle="italic", fontfamily="sans-serif")

    # ── Column headers ─────────────────────────────────────────────────
    # Participant pool
    ax.text(1.8, 7.35, "Participants", fontsize=12, fontweight="bold",
            ha="center", va="center", color=C_TITLE)
    ax.text(1.8, 7.0, "(N = 9)", fontsize=10, ha="center", va="center",
            color=C_SUBTITLE)

    # Session 1
    ax.text(5.8, 7.35, "Session 1 (Day 1)", fontsize=12, fontweight="bold",
            ha="center", va="center", color=C_TITLE)
    ax.text(5.8, 7.0, "BASELINE", fontsize=10, ha="center", va="center",
            color=C_BASELINE, fontweight="bold")

    # Session 2
    ax.text(10.2, 7.35, "Session 2 (Day 2+)", fontsize=12, fontweight="bold",
            ha="center", va="center", color=C_TITLE)
    ax.text(10.2, 7.0, "MANIPULATION", fontsize=10, ha="center", va="center",
            color=C_ACCENT, fontweight="bold")

    # Measures
    ax.text(13.0, 7.35, "Measures", fontsize=12, fontweight="bold",
            ha="center", va="center", color=C_TITLE)

    # ── Dashed vertical separator lines ────────────────────────────────
    for x_pos in [3.5, 8.0, 12.0]:
        ax.plot([x_pos, x_pos], [1.0, 7.6], ls=":", lw=0.8, color="#CCCCCC",
                zorder=0)

    # ════════════════════════════════════════════════════════════════════
    # GROUP 1 — TREATMENT (top row)
    # ════════════════════════════════════════════════════════════════════
    g1_y = 5.1  # center y

    # Participant pool box
    pool_g1 = FancyBboxPatch((0.5, g1_y - 0.9), 2.6, 1.8,
                              boxstyle="round,pad=0.12",
                              facecolor=C_G1_FILL, edgecolor=C_G1_BORDER, lw=2)
    ax.add_patch(pool_g1)
    ax.text(1.8, g1_y + 0.45, "G1 — Treatment", fontsize=11, fontweight="bold",
            ha="center", va="center", color=C_G1_BORDER)
    ax.text(1.8, g1_y + 0.05, "n = 5", fontsize=10, ha="center", va="center",
            color=C_SUBTITLE)
    ax.text(1.8, g1_y - 0.35, "P01, P03, P05,\nP07, P09",
            fontsize=8.5, ha="center", va="center", color="#666666",
            linespacing=1.3)

    # Arrow pool → S1
    ax.annotate("", xy=(4.0, g1_y), xytext=(3.2, g1_y),
                arrowprops=dict(arrowstyle="-|>", color=C_ARROW, lw=1.8))

    # Session 1 box (G1)
    s1_g1 = FancyBboxPatch((4.0, g1_y - 0.9), 3.6, 1.8,
                            boxstyle="round,pad=0.12",
                            facecolor="#F1F8E9", edgecolor=C_BASELINE, lw=2)
    ax.add_patch(s1_g1)
    ax.text(5.8, g1_y + 0.45, "NOVICE  ·  WOR", fontsize=10.5, fontweight="bold",
            ha="center", va="center", color="#33691E")
    ax.text(5.8, g1_y + 0.05, "Memory OFF", fontsize=9.5,
            ha="center", va="center", color=C_SUBTITLE)
    ax.text(5.8, g1_y - 0.38, "Robot = Stranger\n\"Hello! I'm Sota. What's your name?\"",
            fontsize=8.2, ha="center", va="center", color="#666666",
            fontstyle="italic", linespacing=1.3)

    # Arrow S1 → S2
    ax.annotate("", xy=(8.4, g1_y), xytext=(7.7, g1_y),
                arrowprops=dict(arrowstyle="-|>", color=C_ARROW, lw=1.8))
    # "1-7 days" label on arrow
    ax.text(8.05, g1_y + 0.25, "1-7\ndays", fontsize=7, ha="center",
            va="center", color=C_ARROW, fontstyle="italic", linespacing=1.1)

    # Session 2 box (G1) — TREATMENT
    s2_g1 = FancyBboxPatch((8.4, g1_y - 0.9), 3.6, 1.8,
                            boxstyle="round,pad=0.12",
                            facecolor="#E3F2FD", edgecolor=C_WR, lw=2.5)
    ax.add_patch(s2_g1)
    ax.text(10.2, g1_y + 0.45, "REMEMBER  ·  WR", fontsize=10.5,
            fontweight="bold", ha="center", va="center", color="#1565C0")
    ax.text(10.2, g1_y + 0.05, "Memory ON  ·  Reciprocity ON",
            fontsize=9.5, ha="center", va="center", color=C_WR,
            fontweight="bold")
    ax.text(10.2, g1_y - 0.38,
            "Robot remembers name & topics\n\"Welcome back, [name]!\"",
            fontsize=8.2, ha="center", va="center", color="#666666",
            fontstyle="italic", linespacing=1.3)

    # Star/highlight on G1-S2
    ax.plot(8.55, g1_y + 0.7, marker="*", markersize=14, color=C_ACCENT,
            zorder=5)

    # Arrow S2 → Measures
    ax.annotate("", xy=(12.2, g1_y), xytext=(12.05, g1_y),
                arrowprops=dict(arrowstyle="-|>", color=C_ARROW, lw=1.5))

    # Measures box G1
    meas_g1 = FancyBboxPatch((12.15, g1_y - 0.7), 1.7, 1.4,
                              boxstyle="round,pad=0.08",
                              facecolor="#FCE4EC", edgecolor=C_ACCENT, lw=1.5)
    ax.add_patch(meas_g1)
    ax.text(13.0, g1_y + 0.25, "Questionnaire", fontsize=8.5,
            fontweight="bold", ha="center", va="center", color=C_ACCENT)
    ax.text(13.0, g1_y - 0.15, "Q1-Q23\n(7-point Likert)",
            fontsize=7.5, ha="center", va="center", color="#666666",
            linespacing=1.3)

    # ════════════════════════════════════════════════════════════════════
    # GROUP 2 — CONTROL (bottom row)
    # ════════════════════════════════════════════════════════════════════
    g2_y = 2.5  # center y

    # Participant pool box
    pool_g2 = FancyBboxPatch((0.5, g2_y - 0.9), 2.6, 1.8,
                               boxstyle="round,pad=0.12",
                               facecolor=C_G2_FILL, edgecolor=C_G2_BORDER, lw=2)
    ax.add_patch(pool_g2)
    ax.text(1.8, g2_y + 0.45, "G2 — Control", fontsize=11, fontweight="bold",
            ha="center", va="center", color=C_G2_BORDER)
    ax.text(1.8, g2_y + 0.05, "n = 4", fontsize=10, ha="center", va="center",
            color=C_SUBTITLE)
    ax.text(1.8, g2_y - 0.35, "P02, P04, P06, P08",
            fontsize=8.5, ha="center", va="center", color="#666666")

    # Arrow pool → S1
    ax.annotate("", xy=(4.0, g2_y), xytext=(3.2, g2_y),
                arrowprops=dict(arrowstyle="-|>", color=C_ARROW, lw=1.8))

    # Session 1 box (G2) — same as G1-S1
    s1_g2 = FancyBboxPatch((4.0, g2_y - 0.9), 3.6, 1.8,
                            boxstyle="round,pad=0.12",
                            facecolor="#F1F8E9", edgecolor=C_BASELINE, lw=2)
    ax.add_patch(s1_g2)
    ax.text(5.8, g2_y + 0.45, "NOVICE  ·  WOR", fontsize=10.5, fontweight="bold",
            ha="center", va="center", color="#33691E")
    ax.text(5.8, g2_y + 0.05, "Memory OFF", fontsize=9.5,
            ha="center", va="center", color=C_SUBTITLE)
    ax.text(5.8, g2_y - 0.38, "Robot = Stranger\n\"Hello! I'm Sota. What's your name?\"",
            fontsize=8.2, ha="center", va="center", color="#666666",
            fontstyle="italic", linespacing=1.3)

    # "IDENTICAL" label connecting G1-S1 and G2-S1
    mid_y = (g1_y + g2_y) / 2
    ax.annotate("", xy=(5.8, g1_y - 0.95), xytext=(5.8, g2_y + 0.95),
                arrowprops=dict(arrowstyle="<->", color=C_BASELINE, lw=1.5,
                                ls="--"))
    ax.text(5.8, mid_y, " IDENTICAL\n BASELINE",
            fontsize=8, ha="center", va="center", color=C_BASELINE,
            fontweight="bold",
            bbox=dict(boxstyle="round,pad=0.2", facecolor="white",
                      edgecolor=C_BASELINE, lw=1))

    # Arrow S1 → S2
    ax.annotate("", xy=(8.4, g2_y), xytext=(7.7, g2_y),
                arrowprops=dict(arrowstyle="-|>", color=C_ARROW, lw=1.8))
    ax.text(8.05, g2_y + 0.25, "1-7\ndays", fontsize=7, ha="center",
            va="center", color=C_ARROW, fontstyle="italic", linespacing=1.1)

    # Session 2 box (G2) — CONTROL
    s2_g2 = FancyBboxPatch((8.4, g2_y - 0.9), 3.6, 1.8,
                            boxstyle="round,pad=0.12",
                            facecolor="#FFF8E1", edgecolor=C_WOR, lw=2.5)
    ax.add_patch(s2_g2)
    ax.text(10.2, g2_y + 0.45, "NO-REMEMBER  ·  WOR", fontsize=10.5,
            fontweight="bold", ha="center", va="center", color="#C62828")
    ax.text(10.2, g2_y + 0.05, "Memory OFF  ·  No Reciprocity",
            fontsize=9.5, ha="center", va="center", color=C_WOR,
            fontweight="bold")
    ax.text(10.2, g2_y - 0.38,
            "Robot forgets everything\n\"Hello! I'm Sota. What's your name?\"",
            fontsize=8.2, ha="center", va="center", color="#666666",
            fontstyle="italic", linespacing=1.3)

    # Arrow S2 → Measures
    ax.annotate("", xy=(12.2, g2_y), xytext=(12.05, g2_y),
                arrowprops=dict(arrowstyle="-|>", color=C_ARROW, lw=1.5))

    # Measures box G2
    meas_g2 = FancyBboxPatch((12.15, g2_y - 0.7), 1.7, 1.4,
                              boxstyle="round,pad=0.08",
                              facecolor="#FCE4EC", edgecolor=C_ACCENT, lw=1.5)
    ax.add_patch(meas_g2)
    ax.text(13.0, g2_y + 0.25, "Questionnaire", fontsize=8.5,
            fontweight="bold", ha="center", va="center", color=C_ACCENT)
    ax.text(13.0, g2_y - 0.15, "Q1-Q23\n(7-point Likert)",
            fontsize=7.5, ha="center", va="center", color="#666666",
            linespacing=1.3)

    # "DIFFERENT" label connecting G1-S2 and G2-S2
    ax.annotate("", xy=(10.2, g1_y - 0.95), xytext=(10.2, g2_y + 0.95),
                arrowprops=dict(arrowstyle="<->", color=C_ACCENT, lw=1.5,
                                ls="--"))
    ax.text(10.2, mid_y, " KEY\n COMPARISON",
            fontsize=8, ha="center", va="center", color=C_ACCENT,
            fontweight="bold",
            bbox=dict(boxstyle="round,pad=0.2", facecolor="white",
                      edgecolor=C_ACCENT, lw=1))

    # ── Random Assignment arrow from a common pool ─────────────────────
    # Curly brace text
    ax.text(0.3, mid_y, "Random\nAssignment",
            fontsize=8.5, ha="center", va="center", color=C_ARROW,
            fontweight="bold", rotation=90, linespacing=1.4)

    # ── Bottom legend ──────────────────────────────────────────────────
    legend_y = 0.55

    # Factor labels
    ax.text(0.5, legend_y, "Factors:", fontsize=9, fontweight="bold",
            va="center", color=C_TITLE)

    # Between-subjects
    rect1 = FancyBboxPatch((2.0, legend_y - 0.2), 3.5, 0.4,
                            boxstyle="round,pad=0.06",
                            facecolor="#E8EAF6", edgecolor="#5C6BC0", lw=1.2)
    ax.add_patch(rect1)
    ax.text(3.75, legend_y, "Between: Group (G1 vs G2)", fontsize=8,
            ha="center", va="center", color="#283593", fontweight="bold")

    # Within-subjects
    rect2 = FancyBboxPatch((5.8, legend_y - 0.2), 3.5, 0.4,
                            boxstyle="round,pad=0.06",
                            facecolor="#E8F5E9", edgecolor="#66BB6A", lw=1.2)
    ax.add_patch(rect2)
    ax.text(7.55, legend_y, "Within: Session (S1 vs S2)", fontsize=8,
            ha="center", va="center", color="#2E7D32", fontweight="bold")

    # DVs
    rect3 = FancyBboxPatch((9.6, legend_y - 0.2), 4.2, 0.4,
                            boxstyle="round,pad=0.06",
                            facecolor="#FCE4EC", edgecolor=C_ACCENT, lw=1.2)
    ax.add_patch(rect3)
    ax.text(11.7, legend_y,
            "DVs: Trust · Disclosure · Empathy · Robot Char · Continue",
            fontsize=7.5, ha="center", va="center", color="#880E4F",
            fontweight="bold")

    # ── WR / WOR legend ────────────────────────────────────────────────
    ax.text(1.5, 0.05, "WR = With Reciprocity (Memory ON)",
            fontsize=7.5, va="center", color=C_WR, fontweight="bold")
    ax.text(6.5, 0.05, "WOR = Without Reciprocity (Memory OFF)",
            fontsize=7.5, va="center", color=C_WOR, fontweight="bold")

    plt.tight_layout(pad=0.5)
    return fig


# ════════════════════════════════════════════════════════════════════════
# FIGURE 2: FSM / Interaction Flow
# ════════════════════════════════════════════════════════════════════════

def draw_fsm_interaction_flow():
    """
    Creates a professional FSM diagram for the WhisperInteraction system.
    States: IDLE → RECOGNIZING → GREETING → [REGISTERING] → LISTENING →
            THINKING → RESPONDING → (loop back or CLOSING → IDLE)
    """
    fig, ax = plt.subplots(1, 1, figsize=(16, 11))
    fig.patch.set_facecolor(C_BG)
    ax.set_facecolor(C_BG)
    ax.set_xlim(0, 16)
    ax.set_ylim(0, 11)
    ax.axis("off")

    # ── Title ──────────────────────────────────────────────────────────
    ax.text(8, 10.6, "Interaction Flow: WhisperInteraction FSM",
            fontsize=18, fontweight="bold", ha="center", va="center",
            color=C_TITLE, fontfamily="sans-serif")
    ax.text(8, 10.2,
            "Sota Robot State Machine  —  Face Detection → Conversation → Closing",
            fontsize=10.5, ha="center", va="center", color=C_SUBTITLE,
            fontstyle="italic")

    # ── Helper: draw a state node ──────────────────────────────────────
    def state_box(x, y, w, h, label, sublabel, fill, border, led_color=None,
                  sublabel2=None, is_main=True):
        """Draw a rounded state box with optional LED indicator."""
        lw = 2.5 if is_main else 1.8
        box = FancyBboxPatch((x - w/2, y - h/2), w, h,
                              boxstyle="round,pad=0.15",
                              facecolor=fill, edgecolor=border, lw=lw,
                              zorder=3)
        ax.add_patch(box)
        # State name
        ax.text(x, y + 0.18 if sublabel else y, label,
                fontsize=11.5 if is_main else 10, fontweight="bold",
                ha="center", va="center", color=border, zorder=4)
        # Sub-label
        if sublabel:
            ax.text(x, y - 0.15, sublabel,
                    fontsize=8, ha="center", va="center", color="#555555",
                    zorder=4)
        if sublabel2:
            ax.text(x, y - 0.38, sublabel2,
                    fontsize=7.5, ha="center", va="center", color="#777777",
                    fontstyle="italic", zorder=4)
        # LED circle
        if led_color:
            led = plt.Circle((x + w/2 - 0.15, y + h/2 - 0.15), 0.1,
                              facecolor=led_color, edgecolor="#333333",
                              lw=0.8, zorder=5)
            ax.add_patch(led)

    def arrow(x1, y1, x2, y2, label="", color=C_ARROW, style="-|>",
              lw=1.8, curved=False, rad=0.0, label_offset=(0, 0.15),
              fontsize=8, fontcolor=None, bold=False):
        """Draw an arrow with optional label."""
        if fontcolor is None:
            fontcolor = color
        if curved:
            ax.annotate("", xy=(x2, y2), xytext=(x1, y1),
                        arrowprops=dict(arrowstyle=style, color=color, lw=lw,
                                        connectionstyle=f"arc3,rad={rad}"),
                        zorder=2)
        else:
            ax.annotate("", xy=(x2, y2), xytext=(x1, y1),
                        arrowprops=dict(arrowstyle=style, color=color, lw=lw),
                        zorder=2)
        if label:
            mx = (x1 + x2) / 2 + label_offset[0]
            my = (y1 + y2) / 2 + label_offset[1]
            fw = "bold" if bold else "normal"
            ax.text(mx, my, label, fontsize=fontsize, ha="center",
                    va="center", color=fontcolor, fontweight=fw,
                    bbox=dict(boxstyle="round,pad=0.12", facecolor="white",
                              edgecolor="none", alpha=0.85),
                    zorder=6)

    # ════════════════════════════════════════════════════════════════════
    # STATE POSITIONS (arranged in a flow)
    # ════════════════════════════════════════════════════════════════════
    #
    #  Layout:
    #                        IDLE (top-left)
    #                          |
    #                     RECOGNIZING
    #                       /       \
    #                 (new)           (known)
    #                   |               |
    #               GREETING ──────────┘
    #               /       \
    #        REGISTERING     |
    #              \        /
    #            LISTENING  (center) ←──────────┐
    #                |                          |
    #            THINKING                       |
    #                |                          |
    #            RESPONDING ────────────────────┘
    #                |  (goodbye/timeout)
    #             CLOSING
    #                |
    #              IDLE

    # ── State coordinates ──────────────────────────────────────────────
    sx = {  # x positions
        "idle":         3.0,
        "recognizing":  3.0,
        "greeting":     3.0,
        "registering":  3.0,
        "listening":    8.0,
        "thinking":     8.0,
        "responding":   8.0,
        "closing":      13.0,
    }
    sy = {  # y positions
        "idle":         9.0,
        "recognizing":  7.2,
        "greeting":     5.3,
        "registering":  3.2,
        "listening":    7.2,
        "thinking":     5.3,
        "responding":   3.2,
        "closing":      7.2,
    }

    # ── Draw states ────────────────────────────────────────────────────
    # IDLE
    state_box(sx["idle"], sy["idle"], 2.8, 0.95,
              "IDLE", "Waiting for face",
              "#ECEFF1", "#607D8B", led_color="#FFFFFF",
              sublabel2="Camera polling @ 10fps")

    # RECOGNIZING
    state_box(sx["recognizing"], sy["recognizing"], 2.8, 0.95,
              "RECOGNIZING", "Face recognition",
              "#E0F7FA", "#00ACC1", led_color="#00FFFF",
              sublabel2="OpenCV + UserMemory lookup")

    # GREETING
    state_box(sx["greeting"], sy["greeting"], 2.8, 1.05,
              "GREETING", "Sota greets user",
              "#E8F5E9", "#43A047", led_color="#00FF00",
              sublabel2="Nod + wave gesture")

    # REGISTERING
    state_box(sx["registering"], sy["registering"], 2.8, 0.95,
              "REGISTERING", "Ask name & origin",
              "#FFF3E0", "#EF6C00", led_color="#FFA500",
              sublabel2="Whisper STT → save profile")

    # LISTENING
    state_box(sx["listening"], sy["listening"], 2.8, 0.95,
              "LISTENING", "Recording user speech",
              "#E0F7FA", "#0097A7", led_color="#00E5FF",
              sublabel2="CRecordMic + VAD")

    # THINKING
    state_box(sx["thinking"], sy["thinking"], 2.8, 0.95,
              "THINKING", "Processing speech + LLM",
              "#FFFDE7", "#F9A825", led_color="#FFFF00",
              sublabel2="Whisper STT → Ollama llama3.2")

    # RESPONDING
    state_box(sx["responding"], sy["responding"], 2.8, 0.95,
              "RESPONDING", "Sota speaks response",
              "#E8F5E9", "#2E7D32", led_color="#00FF00",
              sublabel2="TTS + arm/head gestures")

    # CLOSING
    state_box(sx["closing"], sy["closing"], 2.8, 0.95,
              "CLOSING", "Goodbye + save memory",
              "#FCE4EC", "#C62828", led_color="#FF6666",
              sublabel2="Wave goodbye → cooldown")

    # ════════════════════════════════════════════════════════════════════
    # ARROWS / TRANSITIONS
    # ════════════════════════════════════════════════════════════════════

    # IDLE → RECOGNIZING
    arrow(sx["idle"], sy["idle"] - 0.5,
          sx["recognizing"], sy["recognizing"] + 0.5,
          "Face detected\n(3+ frames)", "#00ACC1", lw=2.0,
          label_offset=(1.15, 0))

    # RECOGNIZING → GREETING
    arrow(sx["recognizing"], sy["recognizing"] - 0.5,
          sx["greeting"], sy["greeting"] + 0.55,
          "", "#43A047", lw=2.0)

    # Decision diamond for recognized/new — draw as annotation
    # Known user label (right side)
    ax.text(4.55, 6.5, "Known user\n→ personalized", fontsize=7.5,
            ha="left", va="center", color="#00695C",
            bbox=dict(boxstyle="round,pad=0.15", facecolor="#E0F2F1",
                      edgecolor="#00695C", lw=0.8), zorder=6)

    # New user label (left side)
    ax.text(0.4, 6.5, "New user\n→ stranger greeting", fontsize=7.5,
            ha="left", va="center", color="#BF360C",
            bbox=dict(boxstyle="round,pad=0.15", facecolor="#FBE9E7",
                      edgecolor="#BF360C", lw=0.8), zorder=6)

    # GREETING → REGISTERING (new user path)
    arrow(sx["greeting"], sy["greeting"] - 0.55,
          sx["registering"], sy["registering"] + 0.5,
          "New user", "#EF6C00", lw=1.5,
          label_offset=(-1.05, 0), fontcolor="#BF360C")

    # GREETING → LISTENING (known user, direct)
    arrow(sx["greeting"] + 1.4, sy["greeting"],
          sx["listening"] - 1.4, sy["listening"],
          "Returning user\n(skip registration)", "#0097A7", lw=2.0,
          label_offset=(0, 0.35))

    # REGISTERING → LISTENING
    arrow(sx["registering"] + 1.4, sy["registering"],
          sx["listening"] - 1.4, sy["listening"] - 1.8,
          "", "#0097A7", lw=1.5,
          curved=True, rad=-0.3)
    ax.text(5.5, 4.0, "Registration\ncomplete", fontsize=7.5,
            ha="center", va="center", color="#0097A7",
            bbox=dict(boxstyle="round,pad=0.12", facecolor="white",
                      edgecolor="none", alpha=0.85), zorder=6)

    # ── CONVERSATION LOOP ──────────────────────────────────────────────
    # LISTENING → THINKING
    arrow(sx["listening"], sy["listening"] - 0.5,
          sx["thinking"], sy["thinking"] + 0.5,
          "Audio captured\n→ send to Whisper", "#F9A825", lw=2.2,
          label_offset=(1.45, 0))

    # THINKING → RESPONDING
    arrow(sx["thinking"], sy["thinking"] - 0.5,
          sx["responding"], sy["responding"] + 0.5,
          "LLM response\ngenerated", "#2E7D32", lw=2.2,
          label_offset=(1.35, 0))

    # RESPONDING → LISTENING (loop back) — curved arrow on the RIGHT
    arrow(sx["responding"] + 1.35, sy["responding"] + 0.2,
          sx["listening"] + 1.35, sy["listening"] - 0.2,
          "Continue\nconversation", C_WR, lw=2.5,
          curved=True, rad=-0.4,
          label_offset=(0.85, 0), bold=True)

    # Conversation loop highlight box
    loop_box = FancyBboxPatch((6.2, 2.4), 5.5, 6.0,
                               boxstyle="round,pad=0.2",
                               facecolor="none", edgecolor=C_WR,
                               lw=2.0, ls="--", zorder=1)
    ax.add_patch(loop_box)
    ax.text(8.95, 8.7, "CONVERSATION LOOP", fontsize=10,
            fontweight="bold", ha="center", va="center", color=C_WR,
            bbox=dict(boxstyle="round,pad=0.2", facecolor="#E3F2FD",
                      edgecolor=C_WR, lw=1.2), zorder=6)

    # ── EXIT PATH ──────────────────────────────────────────────────────
    # RESPONDING → CLOSING (goodbye or timeout)
    arrow(sx["responding"] + 1.4, sy["responding"],
          sx["closing"] - 1.4, sy["closing"],
          "Goodbye detected\nor silence timeout", "#C62828", lw=2.0,
          label_offset=(0, -0.35), fontcolor="#C62828")

    # CLOSING → IDLE (reset, curved back around)
    # Draw as a long path: CLOSING goes up-right then curves back to IDLE
    ax.annotate("", xy=(sx["idle"] + 1.35, sy["idle"] + 0.15),
                xytext=(sx["closing"], sy["closing"] + 0.5),
                arrowprops=dict(arrowstyle="-|>", color="#607D8B", lw=2.0,
                                connectionstyle="arc3,rad=-0.35"),
                zorder=2)
    ax.text(9.5, 9.6, "Reset + Cooldown\n→ back to IDLE", fontsize=7.5,
            ha="center", va="center", color="#607D8B",
            bbox=dict(boxstyle="round,pad=0.12", facecolor="white",
                      edgecolor="#607D8B", lw=0.8), zorder=6)

    # LISTENING → CLOSING (silence timeout, no speech detected)
    arrow(sx["listening"] + 1.4, sy["listening"] + 0.15,
          sx["closing"] - 1.4, sy["closing"] + 0.15,
          "Silence × 3\n(no speech)",  "#C62828", lw=1.5,
          label_offset=(0, 0.35), fontcolor="#C62828")

    # ════════════════════════════════════════════════════════════════════
    # CONDITION BRANCHING BOX (WR vs WOR)
    # ════════════════════════════════════════════════════════════════════
    cond_y = 1.35
    # WR box
    wr_box = FancyBboxPatch((0.3, cond_y - 0.6), 5.0, 1.2,
                             boxstyle="round,pad=0.1",
                             facecolor="#E3F2FD", edgecolor=C_WR, lw=1.5)
    ax.add_patch(wr_box)
    ax.text(2.8, cond_y + 0.22, "WR — With Reciprocity (G1-S2)",
            fontsize=9, fontweight="bold", ha="center", va="center",
            color="#1565C0")
    ax.text(2.8, cond_y - 0.18,
            "Greeting: \"Welcome back, [name]!\"  ·  Memory ON\n"
            "LLM prompt includes past topics  ·  Social state progresses",
            fontsize=7.5, ha="center", va="center", color="#555555",
            linespacing=1.3)

    # WOR box
    wor_box = FancyBboxPatch((5.8, cond_y - 0.6), 5.0, 1.2,
                              boxstyle="round,pad=0.1",
                              facecolor="#FFF8E1", edgecolor=C_WOR, lw=1.5)
    ax.add_patch(wor_box)
    ax.text(8.3, cond_y + 0.22, "WOR — Without Reciprocity (G1-S1, G2)",
            fontsize=9, fontweight="bold", ha="center", va="center",
            color="#C62828")
    ax.text(8.3, cond_y - 0.18,
            "Greeting: \"Hello! I'm Sota. What's your name?\"\n"
            "Memory OFF  ·  LLM has no history  ·  Always stranger",
            fontsize=7.5, ha="center", va="center", color="#555555",
            linespacing=1.3)

    # Condition affects GREETING badge
    ax.text(5.4, cond_y + 0.22, "vs", fontsize=10, fontweight="bold",
            ha="center", va="center", color=C_ACCENT,
            bbox=dict(boxstyle="circle,pad=0.15", facecolor="#FCE4EC",
                      edgecolor=C_ACCENT, lw=1.2), zorder=6)

    # Label: "Condition affects GREETING & LLM context"
    ax.text(5.4, cond_y - 0.85,
            "Condition determines greeting style and LLM context (memory/no-memory)",
            fontsize=8, ha="center", va="center", color=C_SUBTITLE,
            fontstyle="italic")

    # ── Hardware/Software legend ───────────────────────────────────────
    hw_y = 0.15
    components = [
        ("Vstone Sota", "#78909C"),
        ("OpenCV Camera", "#00ACC1"),
        ("Whisper STT", "#F9A825"),
        ("Ollama LLM", "#7B1FA2"),
        ("CRoboCamera", "#607D8B"),
        ("UserMemory", "#2E7D32"),
    ]
    start_x = 0.5
    for i, (name, color) in enumerate(components):
        bx = start_x + i * 2.55
        rect = FancyBboxPatch((bx, hw_y - 0.12), 2.2, 0.26,
                               boxstyle="round,pad=0.04",
                               facecolor="white", edgecolor=color, lw=1.0)
        ax.add_patch(rect)
        ax.text(bx + 1.1, hw_y + 0.01, name, fontsize=7, fontweight="bold",
                ha="center", va="center", color=color)

    plt.tight_layout(pad=0.5)
    return fig


# ════════════════════════════════════════════════════════════════════════
# FIGURE 3: Reciprocity Comparison (WR vs WOR)
# ════════════════════════════════════════════════════════════════════════

def draw_reciprocity_comparison():
    """
    Side-by-side comparison of WR (With Reciprocity) vs WOR (Without Reciprocity)
    showing actual program behavior from WhisperInteraction.java:
    - WR:  Full memory, personalized greeting, social state, LLM has history
    - WOR: Has context (profile loaded) but pretends to forget name,
           greeting references origin but asks name again, LLM says "first meeting"
    """
    fig, ax = plt.subplots(1, 1, figsize=(16, 12))
    fig.patch.set_facecolor(C_BG)
    ax.set_facecolor(C_BG)
    ax.set_xlim(0, 16)
    ax.set_ylim(0, 12)
    ax.axis("off")

    # ── Title ──────────────────────────────────────────────────────────
    ax.text(8, 11.55, "Reciprocity Conditions: WR vs WOR",
            fontsize=19, fontweight="bold", ha="center", va="center",
            color=C_TITLE, fontfamily="sans-serif")
    ax.text(8, 11.15,
            "How the robot behaves differently between Treatment (G1-S2) and Control (G2-S2)",
            fontsize=10.5, ha="center", va="center", color=C_SUBTITLE,
            fontstyle="italic")

    # ── Divider line ───────────────────────────────────────────────────
    ax.plot([8, 8], [0.3, 10.7], ls="-", lw=1.5, color="#CCCCCC", zorder=0)

    # ── Column headers ─────────────────────────────────────────────────
    # WR header
    wr_hdr = FancyBboxPatch((0.4, 10.15), 7.0, 0.7,
                             boxstyle="round,pad=0.12",
                             facecolor="#E3F2FD", edgecolor=C_WR, lw=2.5)
    ax.add_patch(wr_hdr)
    ax.text(3.9, 10.62, "WR — With Reciprocity", fontsize=14,
            fontweight="bold", ha="center", va="center", color="#1565C0")
    ax.text(3.9, 10.32, "G1-S2  ·  REMEMBER  ·  Memory ON", fontsize=10,
            ha="center", va="center", color="#42A5F5")

    # WOR header
    wor_hdr = FancyBboxPatch((8.6, 10.15), 7.0, 0.7,
                              boxstyle="round,pad=0.12",
                              facecolor="#FFF8E1", edgecolor="#FF6F00", lw=2.5)
    ax.add_patch(wor_hdr)
    ax.text(12.1, 10.62, "WOR — Without Reciprocity", fontsize=14,
            fontweight="bold", ha="center", va="center", color="#E65100")
    ax.text(12.1, 10.32, "G2-S2  ·  NO-REMEMBER  ·  pretendForget", fontsize=10,
            ha="center", va="center", color="#FF6F00")

    # ════════════════════════════════════════════════════════════════════
    # ROW DEFINITIONS — each row compares one aspect
    # ════════════════════════════════════════════════════════════════════

    rows = [
        {
            "label": "1. Profile Loading",
            "icon": "👤",
            "wr_title": "Full profile loaded",
            "wr_detail": "getProfileByName(targetName)\nisNewUser = false\nRecognized as returning user",
            "wr_color": "#1565C0",
            "wor_title": "Profile loaded BUT pretends to forget",
            "wor_detail": "getProfileByName(targetName)\npretendForget = true  ·  isNewUser = true\nHas context internally, acts like new",
            "wor_color": "#E65100",
        },
        {
            "label": "2. Greeting",
            "icon": "👋",
            "wr_title": "Personalized welcome back",
            "wr_detail": "\"Welcome back, Bijak! My friend from\n Indonesia! We had such a great chat\n last time!\"",
            "wr_color": "#1565C0",
            "wor_title": "Vague familiarity, asks name again",
            "wor_detail": "\"Hey! You look familiar... you're the\n one from Indonesia, right? Sorry,\n what's your name again?\"",
            "wor_color": "#E65100",
        },
        {
            "label": "3. Registration",
            "icon": "📝",
            "wr_title": "Skipped entirely",
            "wr_detail": "No registration needed — user already\nknown. Goes directly to LISTENING.\nFace recognition not required.",
            "wr_color": "#1565C0",
            "wor_title": "Simplified — name only",
            "wor_detail": "Asks name (no face reg, no origin Q).\nAfter name: \"Bijak! Nice to meet you!\n So what's life like in Indonesia?\"",
            "wor_color": "#E65100",
        },
        {
            "label": "4. LLM System Prompt",
            "icon": "🧠",
            "wr_title": "Full memory context in prompt",
            "wr_detail": "Includes: name, origin, interaction count,\nprevious conversation summary,\nsocial state (stranger→close friend)",
            "wr_color": "#1565C0",
            "wor_title": "Name + origin only, no history",
            "wor_detail": "Has name & origin BUT prompt says:\n\"This is your first time meeting\n this person.\" No memory/summary.",
            "wor_color": "#E65100",
        },
        {
            "label": "5. Social State",
            "icon": "💬",
            "wr_title": "Progressive relationship",
            "wr_detail": "Stranger → Acquaintance → Friendly →\nClose Friend. Tone becomes casual,\nuses humor and personal questions.",
            "wr_color": "#1565C0",
            "wor_title": "Always stranger",
            "wor_detail": "No social state progression.\nAlways polite/formal first-meeting\ntone. No relationship building.",
            "wor_color": "#E65100",
        },
        {
            "label": "6. Conversation Style",
            "icon": "🗣️",
            "wr_title": "Deep & personal",
            "wr_detail": "References past topics. Asks follow-up\nQ's about things user shared before.\n\"How's your mom doing in Lampung?\"",
            "wr_color": "#1565C0",
            "wor_title": "Surface-level & generic",
            "wor_detail": "No reference to past. Generic getting-\nto-know-you Q's every time.\n\"What do you like to do for fun?\"",
            "wor_color": "#E65100",
        },
    ]

    row_height = 1.5
    start_y = 9.5
    wr_x = 0.55       # left box x
    wor_x = 8.75      # right box x
    box_w = 6.8
    label_x = 8.0     # center label

    for i, row in enumerate(rows):
        cy = start_y - i * row_height  # center y of this row
        top = cy + 0.55
        bot = cy - 0.55
        h = 1.1

        # ── Row label (center) ─────────────────────────────────────────
        ax.text(label_x, cy + 0.02, row["label"], fontsize=9.5,
                fontweight="bold", ha="center", va="center", color=C_TITLE,
                bbox=dict(boxstyle="round,pad=0.18", facecolor="#F5F5F5",
                          edgecolor="#BDBDBD", lw=1.0),
                zorder=5)

        # ── Horizontal guide line ──────────────────────────────────────
        if i > 0:
            ax.plot([0.3, 15.7], [cy + 0.75, cy + 0.75],
                    ls=":", lw=0.6, color="#E0E0E0", zorder=0)

        # ── WR box (left) ──────────────────────────────────────────────
        wr_box = FancyBboxPatch((wr_x, bot), box_w, h,
                                 boxstyle="round,pad=0.08",
                                 facecolor="#F0F7FF", edgecolor="#90CAF9",
                                 lw=1.2, zorder=2)
        ax.add_patch(wr_box)
        ax.text(wr_x + 0.2, cy + 0.33, row["wr_title"],
                fontsize=9, fontweight="bold", ha="left", va="center",
                color=row["wr_color"], zorder=3)
        ax.text(wr_x + 0.2, cy - 0.15, row["wr_detail"],
                fontsize=7.5, ha="left", va="center", color="#444444",
                linespacing=1.25, fontfamily="monospace", zorder=3)

        # Check mark
        ax.text(wr_x + box_w - 0.35, cy + 0.33, "✓", fontsize=12,
                fontweight="bold", ha="center", va="center", color="#2E7D32",
                zorder=4)

        # ── WOR box (right) ────────────────────────────────────────────
        wor_box = FancyBboxPatch((wor_x, bot), box_w, h,
                                  boxstyle="round,pad=0.08",
                                  facecolor="#FFFBF0", edgecolor="#FFE082",
                                  lw=1.2, zorder=2)
        ax.add_patch(wor_box)
        ax.text(wor_x + 0.2, cy + 0.33, row["wor_title"],
                fontsize=9, fontweight="bold", ha="left", va="center",
                color=row["wor_color"], zorder=3)
        ax.text(wor_x + 0.2, cy - 0.15, row["wor_detail"],
                fontsize=7.5, ha="left", va="center", color="#444444",
                linespacing=1.25, fontfamily="monospace", zorder=3)

        # X mark
        ax.text(wor_x + box_w - 0.35, cy + 0.33, "✗", fontsize=12,
                fontweight="bold", ha="center", va="center", color="#C62828",
                zorder=4)

    # ════════════════════════════════════════════════════════════════════
    # BOTTOM — Key insight box
    # ════════════════════════════════════════════════════════════════════
    insight_y = 0.65
    insight_box = FancyBboxPatch((0.4, insight_y - 0.45), 15.2, 0.85,
                                  boxstyle="round,pad=0.12",
                                  facecolor="#FCE4EC", edgecolor=C_ACCENT,
                                  lw=2.0, zorder=3)
    ax.add_patch(insight_box)
    ax.text(8, insight_y + 0.12,
            "Key Design Choice: WOR still loads the user profile (knows origin) but acts like it forgot.",
            fontsize=10, fontweight="bold", ha="center", va="center",
            color="#880E4F", zorder=4)
    ax.text(8, insight_y - 0.18,
            "This ensures the control condition has the same topic context (e.g. asks about Indonesia) "
            "but without the reciprocal memory signal — isolating the effect of recognition.",
            fontsize=8.5, ha="center", va="center", color="#555555",
            fontstyle="italic", zorder=4)

    # ════════════════════════════════════════════════════════════════════
    # BOTTOM LINE — implementation note
    # ════════════════════════════════════════════════════════════════════
    ax.text(8, 0.08,
            "Implementation: WhisperInteraction.java  ·  pretendForget flag  ·  "
            "buildPretendForgetGreeting()  ·  buildSystemPrompt() conditional context",
            fontsize=7.5, ha="center", va="center", color="#9E9E9E",
            fontstyle="italic")

    plt.tight_layout(pad=0.5)
    return fig


# ════════════════════════════════════════════════════════════════════════
# FIGURE 4: Behavioral Results from Conversation Logs
# ════════════════════════════════════════════════════════════════════════

def parse_all_logs(logs_dir):
    """Parse all log files and return structured data."""
    import re, glob

    data = []
    for fpath in sorted(glob.glob(os.path.join(logs_dir, "*.txt"))):
        fname = os.path.basename(fpath)
        content = open(fpath, "r", encoding="utf-8").read()

        # Extract from filename: P01_G1_S1_NOVICE_...
        m = re.match(r"(P\d+)_(G\d)_S(\d)", fname)
        if not m:
            continue
        pid, group, session = m.group(1), m.group(2), int(m.group(3))

        dur_m = re.search(r"Duration\s*:\s*(\d+)", content)
        ut_m = re.search(r"User Turns\s*:\s*(\d+)", content)
        tt_m = re.search(r"Total Turns\s*:\s*(\d+)", content)

        duration = int(dur_m.group(1)) if dur_m else 0
        user_turns = int(ut_m.group(1)) if ut_m else 0
        total_turns = int(tt_m.group(1)) if tt_m else 0

        # Count avg words per user turn
        user_lines = re.findall(r"Original\s*:\s*(.+)", content)
        word_counts = [len(line.strip().split()) for line in user_lines if line.strip()]
        avg_words = np.mean(word_counts) if word_counts else 0

        data.append({
            "pid": pid, "group": group, "session": session,
            "duration": duration, "user_turns": user_turns,
            "total_turns": total_turns, "avg_words": round(avg_words, 1),
        })
    return data


def draw_behavioral_results(logs_dir):
    """
    Create a 2×2 panel figure showing behavioral data from conversation logs:
    1. Duration: G1 vs G2, S1 vs S2 (grouped bar)
    2. User Turns: G1 vs G2, S1 vs S2 (grouped bar)
    3. Interaction plot (lines crossing = interaction effect)
    4. Individual change (spaghetti plot S1→S2)
    """
    data = parse_all_logs(logs_dir)

    # Organize by group and session
    g1_s1 = [d for d in data if d["group"] == "G1" and d["session"] == 1]
    g1_s2 = [d for d in data if d["group"] == "G1" and d["session"] == 2]
    g2_s1 = [d for d in data if d["group"] == "G2" and d["session"] == 1]
    g2_s2 = [d for d in data if d["group"] == "G2" and d["session"] == 2]

    # Means
    def mean_of(lst, key):
        vals = [d[key] for d in lst]
        return np.mean(vals) if vals else 0
    def std_of(lst, key):
        vals = [d[key] for d in lst]
        return np.std(vals, ddof=1) if len(vals) > 1 else 0

    fig, axes = plt.subplots(2, 2, figsize=(14, 10))
    fig.patch.set_facecolor(C_BG)
    fig.suptitle("Behavioral Results from Conversation Logs",
                 fontsize=17, fontweight="bold", color=C_TITLE, y=0.97)
    fig.text(0.5, 0.935,
             f"G1 Treatment (n=5, memory ON)  vs  G2 Control (n=4, pretend-forget)  |  "
             f"Participants with both sessions: G1=5, G2=3",
             ha="center", fontsize=9.5, color=C_SUBTITLE, fontstyle="italic")

    c_g1 = "#2196F3"
    c_g2 = "#FF9800"
    c_s1 = "#BBDEFB"
    c_s2 = "#1565C0"
    c_g2_s1 = "#FFE0B2"
    c_g2_s2 = "#E65100"

    # ── Panel A: Duration (Grouped Bar) ────────────────────────────────
    ax = axes[0, 0]
    ax.set_facecolor(C_BG)

    x = np.array([0, 1])
    width = 0.35
    dur_means = [mean_of(g1_s1, "duration"), mean_of(g1_s2, "duration")]
    dur_stds = [std_of(g1_s1, "duration"), std_of(g1_s2, "duration")]
    dur_means2 = [mean_of(g2_s1, "duration"), mean_of(g2_s2, "duration")]
    dur_stds2 = [std_of(g2_s1, "duration"), std_of(g2_s2, "duration")]

    bars1 = ax.bar(x - width/2, dur_means, width, yerr=dur_stds,
                   label="G1 Treatment", color=[c_s1, c_s2],
                   edgecolor=c_g1, lw=1.5, capsize=4, error_kw={"lw": 1.2})
    bars2 = ax.bar(x + width/2, dur_means2, width, yerr=dur_stds2,
                   label="G2 Control", color=[c_g2_s1, c_g2_s2],
                   edgecolor=c_g2, lw=1.5, capsize=4, error_kw={"lw": 1.2})

    ax.set_xticks(x)
    ax.set_xticklabels(["Session 1", "Session 2"], fontsize=10)
    ax.set_ylabel("Duration (seconds)", fontsize=10, fontweight="bold")
    ax.set_title("A. Conversation Duration", fontsize=12, fontweight="bold",
                 color=C_TITLE, pad=8)
    ax.legend(fontsize=9, loc="upper left")

    # Add value labels on bars
    for bar_group in [bars1, bars2]:
        for bar in bar_group:
            h = bar.get_height()
            if h > 0:
                ax.text(bar.get_x() + bar.get_width()/2, h + 20,
                        f"{h:.0f}s", ha="center", va="bottom", fontsize=8,
                        fontweight="bold", color="#333333")

    ax.spines["top"].set_visible(False)
    ax.spines["right"].set_visible(False)

    # ── Panel B: User Turns (Grouped Bar) ──────────────────────────────
    ax = axes[0, 1]
    ax.set_facecolor(C_BG)

    ut_means = [mean_of(g1_s1, "user_turns"), mean_of(g1_s2, "user_turns")]
    ut_stds = [std_of(g1_s1, "user_turns"), std_of(g1_s2, "user_turns")]
    ut_means2 = [mean_of(g2_s1, "user_turns"), mean_of(g2_s2, "user_turns")]
    ut_stds2 = [std_of(g2_s1, "user_turns"), std_of(g2_s2, "user_turns")]

    bars1 = ax.bar(x - width/2, ut_means, width, yerr=ut_stds,
                   label="G1 Treatment", color=[c_s1, c_s2],
                   edgecolor=c_g1, lw=1.5, capsize=4, error_kw={"lw": 1.2})
    bars2 = ax.bar(x + width/2, ut_means2, width, yerr=ut_stds2,
                   label="G2 Control", color=[c_g2_s1, c_g2_s2],
                   edgecolor=c_g2, lw=1.5, capsize=4, error_kw={"lw": 1.2})

    ax.set_xticks(x)
    ax.set_xticklabels(["Session 1", "Session 2"], fontsize=10)
    ax.set_ylabel("User Turns", fontsize=10, fontweight="bold")
    ax.set_title("B. User Turn Count", fontsize=12, fontweight="bold",
                 color=C_TITLE, pad=8)
    ax.legend(fontsize=9, loc="upper left")

    for bar_group in [bars1, bars2]:
        for bar in bar_group:
            h = bar.get_height()
            if h > 0:
                ax.text(bar.get_x() + bar.get_width()/2, h + 0.5,
                        f"{h:.1f}", ha="center", va="bottom", fontsize=8,
                        fontweight="bold", color="#333333")

    ax.spines["top"].set_visible(False)
    ax.spines["right"].set_visible(False)

    # ── Panel C: Interaction Plot (lines) ──────────────────────────────
    ax = axes[1, 0]
    ax.set_facecolor(C_BG)

    sessions = [1, 2]
    g1_dur_line = [mean_of(g1_s1, "duration"), mean_of(g1_s2, "duration")]
    g2_dur_line = [mean_of(g2_s1, "duration"), mean_of(g2_s2, "duration")]

    ax.plot(sessions, g1_dur_line, "o-", color=c_g1, lw=2.5, markersize=10,
            label="G1 Treatment", zorder=3)
    ax.plot(sessions, g2_dur_line, "s--", color=c_g2, lw=2.5, markersize=10,
            label="G2 Control", zorder=3)

    # Fill between to highlight divergence
    ax.fill_between(sessions, g1_dur_line, g2_dur_line,
                    alpha=0.12, color=c_g1, zorder=1)

    ax.set_xticks(sessions)
    ax.set_xticklabels(["Session 1\n(Baseline)", "Session 2\n(Manipulation)"],
                       fontsize=10)
    ax.set_ylabel("Duration (seconds)", fontsize=10, fontweight="bold")
    ax.set_title("C. Interaction Effect: Duration",
                 fontsize=12, fontweight="bold", color=C_TITLE, pad=8)
    ax.legend(fontsize=9)

    # Annotate the gap
    gap = g1_dur_line[1] - g2_dur_line[1]
    mid = (g1_dur_line[1] + g2_dur_line[1]) / 2
    ax.annotate(f"Δ = {gap:.0f}s", xy=(2.05, mid),
                fontsize=10, fontweight="bold", color=C_ACCENT)

    for s, v in zip(sessions, g1_dur_line):
        ax.text(s, v + 25, f"{v:.0f}s", ha="center", fontsize=8.5,
                color=c_g1, fontweight="bold")
    for s, v in zip(sessions, g2_dur_line):
        ax.text(s, v - 40, f"{v:.0f}s", ha="center", fontsize=8.5,
                color=c_g2, fontweight="bold")

    ax.spines["top"].set_visible(False)
    ax.spines["right"].set_visible(False)

    # ── Panel D: Individual Change (Spaghetti Plot) ────────────────────
    ax = axes[1, 1]
    ax.set_facecolor(C_BG)

    # Match S1→S2 per participant
    pids_with_both = set()
    s1_map = {}
    s2_map = {}
    for d in data:
        if d["session"] == 1:
            s1_map[d["pid"]] = d
        elif d["session"] == 2:
            s2_map[d["pid"]] = d

    for pid in s1_map:
        if pid in s2_map:
            pids_with_both.add(pid)

    for pid in sorted(pids_with_both):
        d1 = s1_map[pid]
        d2 = s2_map[pid]
        color = c_g1 if d1["group"] == "G1" else c_g2
        ls = "-" if d1["group"] == "G1" else "--"
        ax.plot([1, 2], [d1["duration"], d2["duration"]],
                f"o{ls}", color=color, lw=1.8, markersize=7, alpha=0.8)
        ax.text(2.05, d2["duration"], pid, fontsize=7.5, va="center",
                color=color, fontweight="bold")

    # Legend
    from matplotlib.lines import Line2D
    legend_elements = [
        Line2D([0], [0], color=c_g1, lw=2, marker="o", label="G1 Treatment"),
        Line2D([0], [0], color=c_g2, lw=2, marker="o", ls="--", label="G2 Control"),
    ]
    ax.legend(handles=legend_elements, fontsize=9, loc="upper left")

    ax.set_xticks([1, 2])
    ax.set_xticklabels(["Session 1", "Session 2"], fontsize=10)
    ax.set_ylabel("Duration (seconds)", fontsize=10, fontweight="bold")
    ax.set_title("D. Individual Participant Change (S1→S2)",
                 fontsize=12, fontweight="bold", color=C_TITLE, pad=8)
    ax.spines["top"].set_visible(False)
    ax.spines["right"].set_visible(False)

    plt.tight_layout(rect=[0, 0, 1, 0.92])
    return fig


def draw_behavioral_summary_table(logs_dir):
    """
    Create a clean summary table figure showing all behavioral metrics
    with group comparison and change ratios.
    """
    data = parse_all_logs(logs_dir)

    fig, ax = plt.subplots(1, 1, figsize=(14, 7))
    fig.patch.set_facecolor(C_BG)
    ax.set_facecolor(C_BG)
    ax.set_xlim(0, 14)
    ax.set_ylim(0, 7)
    ax.axis("off")

    ax.text(7, 6.7, "Behavioral Data Summary",
            fontsize=17, fontweight="bold", ha="center", va="center",
            color=C_TITLE)
    ax.text(7, 6.35, "Extracted from conversation logs  |  "
            "G1 Treatment (n=5) vs G2 Control (n=4, S2 n=3)",
            fontsize=10, ha="center", va="center", color=C_SUBTITLE,
            fontstyle="italic")

    # Build table data
    def grp(group, session, key):
        vals = [d[key] for d in data if d["group"] == group and d["session"] == session]
        return vals

    g1s1_dur = grp("G1", 1, "duration")
    g1s2_dur = grp("G1", 2, "duration")
    g2s1_dur = grp("G2", 1, "duration")
    g2s2_dur = grp("G2", 2, "duration")

    g1s1_ut = grp("G1", 1, "user_turns")
    g1s2_ut = grp("G1", 2, "user_turns")
    g2s1_ut = grp("G2", 1, "user_turns")
    g2s2_ut = grp("G2", 2, "user_turns")

    g1s1_tt = grp("G1", 1, "total_turns")
    g1s2_tt = grp("G1", 2, "total_turns")
    g2s1_tt = grp("G2", 1, "total_turns")
    g2s2_tt = grp("G2", 2, "total_turns")

    g1s1_aw = grp("G1", 1, "avg_words")
    g1s2_aw = grp("G1", 2, "avg_words")
    g2s1_aw = grp("G2", 1, "avg_words")
    g2s2_aw = grp("G2", 2, "avg_words")

    def fmt_mean_sd(vals):
        if not vals:
            return "—"
        m = np.mean(vals)
        s = np.std(vals, ddof=1) if len(vals) > 1 else 0
        return f"{m:.1f} ± {s:.1f}"

    def fmt_ratio(s1, s2):
        m1 = np.mean(s1) if s1 else 0
        m2 = np.mean(s2) if s2 else 0
        if m1 == 0:
            return "—"
        return f"{m2/m1:.1f}×"

    # Table structure
    headers = ["Metric", "G1-S1", "G1-S2", "G1 Change", "G2-S1", "G2-S2", "G2 Change", "S2 Diff"]
    rows_data = [
        ["Duration (s)",
         fmt_mean_sd(g1s1_dur), fmt_mean_sd(g1s2_dur), fmt_ratio(g1s1_dur, g1s2_dur),
         fmt_mean_sd(g2s1_dur), fmt_mean_sd(g2s2_dur), fmt_ratio(g2s1_dur, g2s2_dur),
         f"G1 {np.mean(g1s2_dur) - np.mean(g2s2_dur):+.0f}s" if g1s2_dur and g2s2_dur else "—"],
        ["User Turns",
         fmt_mean_sd(g1s1_ut), fmt_mean_sd(g1s2_ut), fmt_ratio(g1s1_ut, g1s2_ut),
         fmt_mean_sd(g2s1_ut), fmt_mean_sd(g2s2_ut), fmt_ratio(g2s1_ut, g2s2_ut),
         f"G1 {np.mean(g1s2_ut) - np.mean(g2s2_ut):+.1f}" if g1s2_ut and g2s2_ut else "—"],
        ["Total Turns",
         fmt_mean_sd(g1s1_tt), fmt_mean_sd(g1s2_tt), fmt_ratio(g1s1_tt, g1s2_tt),
         fmt_mean_sd(g2s1_tt), fmt_mean_sd(g2s2_tt), fmt_ratio(g2s1_tt, g2s2_tt),
         f"G1 {np.mean(g1s2_tt) - np.mean(g2s2_tt):+.1f}" if g1s2_tt and g2s2_tt else "—"],
        ["Avg Words/Turn",
         fmt_mean_sd(g1s1_aw), fmt_mean_sd(g1s2_aw), fmt_ratio(g1s1_aw, g1s2_aw),
         fmt_mean_sd(g2s1_aw), fmt_mean_sd(g2s2_aw), fmt_ratio(g2s1_aw, g2s2_aw),
         f"G1 {np.mean(g1s2_aw) - np.mean(g2s2_aw):+.1f}w" if g1s2_aw and g2s2_aw else "—"],
    ]

    # Draw table
    col_x = [0.3, 2.5, 4.5, 6.5, 7.8, 9.5, 11.3, 12.7]
    col_w = [2.0, 1.8, 1.8, 1.2, 1.5, 1.6, 1.2, 1.3]
    header_y = 5.7
    row_h = 0.9

    # Header background
    hdr_bg = FancyBboxPatch((0.15, header_y - 0.3), 13.7, 0.6,
                             boxstyle="round,pad=0.05",
                             facecolor="#37474F", edgecolor="none")
    ax.add_patch(hdr_bg)

    for i, (hx, hdr) in enumerate(zip(col_x, headers)):
        ax.text(hx + 0.8, header_y, hdr, fontsize=9, fontweight="bold",
                ha="center", va="center", color="white")

    # G1/G2 column group headers
    ax.text(4.5, 6.05, "── G1 Treatment ──", fontsize=9, fontweight="bold",
            ha="center", va="center", color="#1565C0")
    ax.text(10.2, 6.05, "── G2 Control ──", fontsize=9, fontweight="bold",
            ha="center", va="center", color="#E65100")

    for r, row in enumerate(rows_data):
        ry = header_y - (r + 1) * row_h
        # Alternating row background
        if r % 2 == 0:
            bg = FancyBboxPatch((0.15, ry - 0.3), 13.7, 0.6,
                                 boxstyle="round,pad=0.05",
                                 facecolor="#F5F5F5", edgecolor="none")
            ax.add_patch(bg)

        for c, val in enumerate(row):
            # Color the "Change" columns
            color = "#333333"
            fw = "normal"
            if c == 3:  # G1 change
                color = "#1565C0"
                fw = "bold"
            elif c == 6:  # G2 change
                color = "#E65100"
                fw = "bold"
            elif c == 7:  # S2 diff
                color = C_ACCENT
                fw = "bold"
            elif c == 0:  # Metric name
                fw = "bold"

            ax.text(col_x[c] + 0.8, ry, val, fontsize=9,
                    ha="center", va="center", color=color, fontweight=fw)

    # Bottom interpretation
    interp_y = 1.5
    interp_box = FancyBboxPatch((0.3, interp_y - 0.55), 13.4, 1.05,
                                 boxstyle="round,pad=0.1",
                                 facecolor="#E3F2FD", edgecolor="#42A5F5",
                                 lw=1.5)
    ax.add_patch(interp_box)
    ax.text(7, interp_y + 0.18,
            "Interpretation: Treatment group (G1) shows dramatically increased engagement in S2",
            fontsize=10, fontweight="bold", ha="center", va="center", color="#1565C0")
    ax.text(7, interp_y - 0.18,
            "G1 participants talked longer, contributed more turns, and used more words per turn when the robot remembered them.\n"
            "G2 participants (pretend-forget) showed modest increases, suggesting familiarity alone has limited effect.",
            fontsize=8.5, ha="center", va="center", color="#444444",
            linespacing=1.3)

    # P04 note
    ax.text(7, 0.6,
            "Note: P04 (G2) has only Session 1 — participant unavailable for Session 2. "
            "G2-S2 averages based on P02, P06, P08 (n=3).",
            fontsize=8, ha="center", va="center", color="#9E9E9E",
            fontstyle="italic")

    plt.tight_layout(pad=0.5)
    return fig


if __name__ == "__main__":
    import os

    out_dir = os.path.join(os.path.dirname(__file__), "figures")
    os.makedirs(out_dir, exist_ok=True)
    logs_dir = os.path.join(os.path.dirname(__file__), "logs")

    # ── Figure 1: Experimental Design ──────────────────────────────────
    print("Creating Figure 1: Experimental Design...")
    fig1 = draw_experimental_design()
    path1 = os.path.join(out_dir, "fig1_experimental_design.png")
    fig1.savefig(path1, dpi=200, bbox_inches="tight", facecolor=fig1.get_facecolor())
    plt.close(fig1)
    print(f"  Saved → {path1}")

    # ── Figure 2: FSM / Interaction Flow ───────────────────────────────
    print("Creating Figure 2: FSM / Interaction Flow...")
    fig2 = draw_fsm_interaction_flow()
    path2 = os.path.join(out_dir, "fig2_fsm_interaction_flow.png")
    fig2.savefig(path2, dpi=200, bbox_inches="tight", facecolor=fig2.get_facecolor())
    plt.close(fig2)
    print(f"  Saved → {path2}")

    # ── Figure 3: Reciprocity Comparison ───────────────────────────────
    print("Creating Figure 3: Reciprocity Comparison...")
    fig3 = draw_reciprocity_comparison()
    path3 = os.path.join(out_dir, "fig3_reciprocity_comparison.png")
    fig3.savefig(path3, dpi=200, bbox_inches="tight", facecolor=fig3.get_facecolor())
    plt.close(fig3)
    print(f"  Saved → {path3}")

    # ── Figure 4: Behavioral Results ───────────────────────────────────
    print("Creating Figure 4: Behavioral Results...")
    fig4 = draw_behavioral_results(logs_dir)
    path4 = os.path.join(out_dir, "fig4_behavioral_results.png")
    fig4.savefig(path4, dpi=200, bbox_inches="tight", facecolor=fig4.get_facecolor())
    plt.close(fig4)
    print(f"  Saved → {path4}")

    # ── Figure 5: Behavioral Summary Table ─────────────────────────────
    print("Creating Figure 5: Behavioral Summary Table...")
    fig5 = draw_behavioral_summary_table(logs_dir)
    path5 = os.path.join(out_dir, "fig5_behavioral_summary.png")
    fig5.savefig(path5, dpi=200, bbox_inches="tight", facecolor=fig5.get_facecolor())
    plt.close(fig5)
    print(f"  Saved → {path5}")

    print("\nDone! All figures saved to figures/ folder.")
