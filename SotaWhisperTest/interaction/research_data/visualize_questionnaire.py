#!/usr/bin/env python3
"""
Visualize Questionnaire Data for HRI Reciprocity Study
=======================================================
Generates figures 6-9 for the presentation slides:
  Fig 6: Subscale means by Group × Session (grouped bar + interaction lines)
  Fig 7: Radar chart — Treatment vs Control across subscales
  Fig 8: Individual item heatmap (all 23 Likert items × participants)
  Fig 9: Session change (S1→S2) comparison by group (paired slopes)
"""

import pandas as pd
import numpy as np
import matplotlib.pyplot as plt
import matplotlib.patches as mpatches
from matplotlib.patches import FancyBboxPatch
import os
import warnings
warnings.filterwarnings('ignore')

# ── Paths ────────────────────────────────────────────────────────────────────
BASE_DIR = os.path.dirname(os.path.abspath(__file__))
DATA_PATH = os.path.join(BASE_DIR, "questionnaire_clean.csv")
FIG_DIR = os.path.join(BASE_DIR, "figures")

# ── Style constants ──────────────────────────────────────────────────────────
C_BG        = "#FAFBFC"
C_TITLE     = "#1A1A2E"
C_SUBTITLE  = "#4A4A6A"
C_G1        = "#2196F3"   # blue (Treatment / REMEMBER)
C_G1_LIGHT  = "#BBDEFB"
C_G2        = "#FF9800"   # orange (Control / NO-REMEMBER)
C_G2_LIGHT  = "#FFE0B2"
C_S1        = "#78909C"   # session 1 (gray-blue)
C_S2        = "#26A69A"   # session 2 (teal)
C_GRID      = "#E0E0E0"
C_ACCENT    = "#E91E63"

SUBSCALES = ['A_Trust', 'B_Disclosure', 'C_Empathy', 'D_RobotChar', 'E_Continue']
SUBSCALE_LABELS = ['Trust', 'Disclosure', 'Empathy', 'Robot\nCharacter', 'Continuity']
SUBSCALE_SHORT = ['Trust', 'Disclosure', 'Empathy', 'Robot Char', 'Continuity']

Q_CODES = [f"Q{i+1}" for i in range(23)]
Q_SHORT_LABELS = [
    "Q1: Safe talking",      "Q2: Trustworthy",        "Q3: Trust w/ info",
    "Q4: Capable",           "Q5: Understood",
    "Q6: Shared willingly",  "Q7: Honest comfort",     "Q8: Share more",
    "Q9: Trust feelings",    "Q10: Heard/understood",
    "Q11: Vulnerable",       "Q12: Protective",        "Q13: Need support",
    "Q14: Help feel better", "Q15: Emotionally conn.",
    "Q16: Confident",        "Q17: Distressed",        "Q18: Human-like",
    "Q19: Natural/auth.",    "Q20: Genuine emotions",
    "Q21: Interact again",   "Q22: Recommend",         "Q23: Valuable",
]

SUBSCALE_RANGES = {
    'Trust':     (0, 5),
    'Disclosure':(5, 10),
    'Empathy':   (10, 15),
    'Robot Char':(15, 20),
    'Continuity':(20, 23),
}


def load_data():
    """Load cleaned questionnaire data."""
    df = pd.read_csv(DATA_PATH)
    return df


# ══════════════════════════════════════════════════════════════════════════════
# FIGURE 6: Subscale Means — Group × Session (Grouped Bars + Interaction Plot)
# ══════════════════════════════════════════════════════════════════════════════
def draw_questionnaire_subscales(df):
    """
    2-panel figure:
      Left:  Grouped bar chart (G1 vs G2) per subscale per session
      Right: Interaction plot (Session × Group) per subscale
    """
    fig, axes = plt.subplots(1, 2, figsize=(16, 7.5), gridspec_kw={'width_ratios': [1.2, 1]})
    fig.patch.set_facecolor(C_BG)

    # ── Compute group × session means ──────────────────────────────────
    grouped = df.groupby(['Group', 'Session'])[SUBSCALES].mean()

    # Ensure all combinations exist
    g1s1 = grouped.loc[('G1', 1)] if ('G1', 1) in grouped.index else pd.Series(0, index=SUBSCALES)
    g1s2 = grouped.loc[('G1', 2)] if ('G1', 2) in grouped.index else pd.Series(0, index=SUBSCALES)
    g2s1 = grouped.loc[('G2', 1)] if ('G2', 1) in grouped.index else pd.Series(0, index=SUBSCALES)
    g2s2 = grouped.loc[('G2', 2)] if ('G2', 2) in grouped.index else pd.Series(0, index=SUBSCALES)

    # Standard errors
    grouped_se = df.groupby(['Group', 'Session'])[SUBSCALES].sem()
    g1s1_se = grouped_se.loc[('G1', 1)] if ('G1', 1) in grouped_se.index else pd.Series(0, index=SUBSCALES)
    g1s2_se = grouped_se.loc[('G1', 2)] if ('G1', 2) in grouped_se.index else pd.Series(0, index=SUBSCALES)
    g2s1_se = grouped_se.loc[('G2', 1)] if ('G2', 1) in grouped_se.index else pd.Series(0, index=SUBSCALES)
    g2s2_se = grouped_se.loc[('G2', 2)] if ('G2', 2) in grouped_se.index else pd.Series(0, index=SUBSCALES)

    # ── LEFT PANEL: Grouped bar chart ──────────────────────────────────
    ax = axes[0]
    ax.set_facecolor(C_BG)
    x = np.arange(len(SUBSCALES))
    w = 0.18

    bars1 = ax.bar(x - 1.5*w, g1s1.values, w, yerr=g1s1_se.values,
                   color=C_G1_LIGHT, edgecolor=C_G1, linewidth=1.2,
                   label='G1 Remember – S1', capsize=3, error_kw={'linewidth': 1})
    bars2 = ax.bar(x - 0.5*w, g1s2.values, w, yerr=g1s2_se.values,
                   color=C_G1, edgecolor=C_G1, linewidth=1.2,
                   label='G1 Remember – S2', capsize=3, error_kw={'linewidth': 1})
    bars3 = ax.bar(x + 0.5*w, g2s1.values, w, yerr=g2s1_se.values,
                   color=C_G2_LIGHT, edgecolor=C_G2, linewidth=1.2,
                   label='G2 Control – S1', capsize=3, error_kw={'linewidth': 1})
    bars4 = ax.bar(x + 1.5*w, g2s2.values, w, yerr=g2s2_se.values,
                   color=C_G2, edgecolor=C_G2, linewidth=1.2,
                   label='G2 Control – S2', capsize=3, error_kw={'linewidth': 1})

    ax.set_xticks(x)
    ax.set_xticklabels(SUBSCALE_LABELS, fontsize=9.5, fontweight='bold')
    ax.set_ylabel('Mean Score (1–7 Likert)', fontsize=11, fontweight='bold')
    ax.set_ylim(1, 6)
    ax.set_title('Subscale Means by Group × Session', fontsize=13, fontweight='bold',
                 color=C_TITLE, pad=12)
    ax.legend(loc='upper right', fontsize=8, framealpha=0.9)
    ax.yaxis.grid(True, alpha=0.3, color=C_GRID)
    ax.set_axisbelow(True)
    ax.spines['top'].set_visible(False)
    ax.spines['right'].set_visible(False)

    # Add value labels on bars
    for bars in [bars1, bars2, bars3, bars4]:
        for bar in bars:
            h = bar.get_height()
            if h > 0:
                ax.text(bar.get_x() + bar.get_width()/2, h + 0.08, f'{h:.1f}',
                        ha='center', va='bottom', fontsize=6.5, color=C_SUBTITLE)

    # ── RIGHT PANEL: Interaction plot ──────────────────────────────────
    ax2 = axes[1]
    ax2.set_facecolor(C_BG)

    sessions = [1, 2]
    for i, (sub, label) in enumerate(zip(SUBSCALES, SUBSCALE_SHORT)):
        g1_vals = [g1s1[sub], g1s2[sub]]
        g2_vals = [g2s1[sub], g2s2[sub]]

        offset = (i - 2) * 0.03  # slight offset to prevent overlap
        ax2.plot([s + offset for s in sessions], g1_vals, 'o-',
                 color=C_G1, linewidth=2, markersize=7, alpha=0.85,
                 label=f'G1 {label}' if i == 0 else None)
        ax2.plot([s + offset for s in sessions], g2_vals, 's--',
                 color=C_G2, linewidth=2, markersize=7, alpha=0.85,
                 label=f'G2 {label}' if i == 0 else None)

        # Annotate
        ax2.annotate(label, (2.06 + offset, g1_vals[1]), fontsize=7,
                     color=C_G1, va='center', fontweight='bold')
        ax2.annotate(label, (2.06 + offset, g2_vals[1]), fontsize=7,
                     color=C_G2, va='center')

    ax2.set_xticks([1, 2])
    ax2.set_xticklabels(['Session 1\n(NOVICE)', 'Session 2\n(Remember/Control)'],
                        fontsize=10, fontweight='bold')
    ax2.set_ylabel('Mean Score', fontsize=11, fontweight='bold')
    ax2.set_ylim(1, 6)
    ax2.set_xlim(0.7, 2.8)
    ax2.set_title('Session × Group Interaction', fontsize=13, fontweight='bold',
                  color=C_TITLE, pad=12)
    ax2.yaxis.grid(True, alpha=0.3, color=C_GRID)
    ax2.set_axisbelow(True)
    ax2.spines['top'].set_visible(False)
    ax2.spines['right'].set_visible(False)

    # Custom legend
    g1_patch = mpatches.Patch(color=C_G1, label='G1 Remember (REMEMBER)')
    g2_patch = mpatches.Patch(color=C_G2, label='G2 Control (NO-REMEMBER)')
    ax2.legend(handles=[g1_patch, g2_patch], loc='upper left', fontsize=9, framealpha=0.9)

    fig.suptitle('Figure 6: Questionnaire Results — Subscale Comparison',
                 fontsize=15, fontweight='bold', color=C_TITLE, y=0.98)
    fig.tight_layout(rect=[0, 0, 1, 0.94])
    return fig


# ══════════════════════════════════════════════════════════════════════════════
# FIGURE 7: Radar Chart — Treatment vs Control (S2 only)
# ══════════════════════════════════════════════════════════════════════════════
def draw_questionnaire_radar(df):
    """
    Radar chart comparing G1 (Treatment/REMEMBER) vs G2 (Control) on S2.
    Also overlays S1 baseline.
    """
    fig, axes = plt.subplots(1, 2, figsize=(14, 7),
                             subplot_kw=dict(polar=True))
    fig.patch.set_facecolor(C_BG)

    categories = SUBSCALE_SHORT
    N = len(categories)
    angles = [n / float(N) * 2 * np.pi for n in range(N)]
    angles += angles[:1]  # close the polygon

    for ax_idx, (title, session_filter) in enumerate([
        ("All Sessions Combined", None),
        ("Session 2 Only (Remember vs Control)", 2),
    ]):
        ax = axes[ax_idx]
        ax.set_facecolor(C_BG)

        if session_filter:
            data = df[df['Session'] == session_filter]
        else:
            data = df

        g1_means = data[data['Group'] == 'G1'][SUBSCALES].mean().values.tolist()
        g2_means = data[data['Group'] == 'G2'][SUBSCALES].mean().values.tolist()
        g1_means += g1_means[:1]
        g2_means += g2_means[:1]

        ax.plot(angles, g1_means, 'o-', linewidth=2.5, color=C_G1,
                label='G1 Remember', markersize=6)
        ax.fill(angles, g1_means, alpha=0.15, color=C_G1)
        ax.plot(angles, g2_means, 's--', linewidth=2.5, color=C_G2,
                label='G2 Control', markersize=6)
        ax.fill(angles, g2_means, alpha=0.15, color=C_G2)

        ax.set_xticks(angles[:-1])
        ax.set_xticklabels(categories, fontsize=9.5, fontweight='bold')
        ax.set_ylim(1, 6)
        ax.set_yticks([2, 3, 4, 5])
        ax.set_yticklabels(['2', '3', '4', '5'], fontsize=8, color=C_SUBTITLE)
        ax.set_title(title, fontsize=11, fontweight='bold', color=C_TITLE, pad=20)
        ax.legend(loc='lower right', fontsize=9, framealpha=0.9,
                  bbox_to_anchor=(1.15, -0.1))
        ax.grid(True, alpha=0.3)

        # Annotate values
        for i in range(N):
            ax.annotate(f'{g1_means[i]:.1f}', (angles[i], g1_means[i]),
                        fontsize=7, color=C_G1, fontweight='bold',
                        textcoords="offset points", xytext=(5, 5))
            ax.annotate(f'{g2_means[i]:.1f}', (angles[i], g2_means[i]),
                        fontsize=7, color=C_G2, fontweight='bold',
                        textcoords="offset points", xytext=(-15, -10))

    fig.suptitle('Figure 7: Questionnaire Radar — Remember vs Control',
                 fontsize=15, fontweight='bold', color=C_TITLE, y=1.0)
    fig.tight_layout(rect=[0, 0, 1, 0.95])
    return fig


# ══════════════════════════════════════════════════════════════════════════════
# FIGURE 8: Heatmap — All 23 Items × Participants
# ══════════════════════════════════════════════════════════════════════════════
def draw_questionnaire_heatmap(df):
    """
    Heatmap showing each participant's responses for all 23 items,
    split by Session. Grouped by subscale with clear separators.
    """
    fig, axes = plt.subplots(1, 2, figsize=(18, 9),
                             gridspec_kw={'width_ratios': [1, 0.85]})
    fig.patch.set_facecolor(C_BG)

    for ax_idx, session in enumerate([1, 2]):
        ax = axes[ax_idx]
        ax.set_facecolor(C_BG)

        sdata = df[df['Session'] == session].sort_values('PID')
        if sdata.empty:
            ax.text(0.5, 0.5, 'No data', transform=ax.transAxes,
                    ha='center', va='center', fontsize=14, color=C_SUBTITLE)
            ax.set_title(f'Session {session}', fontsize=13, fontweight='bold')
            continue

        matrix = sdata[Q_CODES].values.astype(float)
        pids = sdata['PID'].values
        groups = sdata['Group'].values
        pid_labels = [f"{p} ({g})" for p, g in zip(pids, groups)]

        im = ax.imshow(matrix, aspect='auto', cmap='RdYlGn', vmin=1, vmax=7,
                       interpolation='nearest')

        # Axis labels
        ax.set_xticks(range(23))
        ax.set_xticklabels(Q_SHORT_LABELS, fontsize=6.5, rotation=65, ha='right')
        ax.set_yticks(range(len(pids)))
        ax.set_yticklabels(pid_labels, fontsize=9, fontweight='bold')

        # Cell text
        for i in range(matrix.shape[0]):
            for j in range(matrix.shape[1]):
                val = matrix[i, j]
                if not np.isnan(val):
                    color = 'white' if val <= 2 or val >= 6 else 'black'
                    ax.text(j, i, f'{int(val)}', ha='center', va='center',
                            fontsize=7, color=color, fontweight='bold')

        # Subscale separators
        for boundary in [4.5, 9.5, 14.5, 19.5]:
            ax.axvline(boundary, color='white', linewidth=2.5)

        # Subscale labels at top
        subscale_positions = [(2, 'Trust'), (7, 'Disclosure'), (12, 'Empathy'),
                              (17, 'Robot Char'), (21.5, 'Continuity')]
        for pos, label in subscale_positions:
            ax.text(pos, -0.8, label, ha='center', va='bottom',
                    fontsize=8, fontweight='bold', color=C_ACCENT)

        # No panel title (user will add manually)

    fig.suptitle('Figure 8: Individual Responses Heatmap (23 Likert Items)',
                 fontsize=15, fontweight='bold', color=C_TITLE, y=0.98)
    fig.tight_layout(rect=[0, 0, 1, 0.93])
    return fig


# ══════════════════════════════════════════════════════════════════════════════
# FIGURE 9: Paired Slope Chart — S1 → S2 Changes
# ══════════════════════════════════════════════════════════════════════════════
def draw_questionnaire_session_change(df):
    """
    Paired slope chart showing individual participant changes from S1 → S2
    for each subscale. Only includes participants with both sessions.
    """
    # Get participants with both sessions
    pid_counts = df.groupby('PID')['Session'].nunique()
    paired_pids = pid_counts[pid_counts == 2].index.tolist()

    if not paired_pids:
        fig, ax = plt.subplots(figsize=(10, 6))
        ax.text(0.5, 0.5, 'No participants with paired data',
                transform=ax.transAxes, ha='center', fontsize=14)
        return fig

    fig, axes = plt.subplots(1, 5, figsize=(18, 7), sharey=True)
    fig.patch.set_facecolor(C_BG)

    for ax_idx, (subscale, label) in enumerate(zip(SUBSCALES, SUBSCALE_SHORT)):
        ax = axes[ax_idx]
        ax.set_facecolor(C_BG)

        for pid in sorted(paired_pids):
            group = df[df['PID'] == pid]['Group'].iloc[0]
            color = C_G1 if group == 'G1' else C_G2
            marker = 'o' if group == 'G1' else 's'
            alpha = 0.8

            s1_val = df[(df['PID'] == pid) & (df['Session'] == 1)][subscale].values
            s2_val = df[(df['PID'] == pid) & (df['Session'] == 2)][subscale].values

            if len(s1_val) == 0 or len(s2_val) == 0:
                continue

            s1_val = s1_val[0]
            s2_val = s2_val[0]
            change = s2_val - s1_val

            # Draw slope line
            lw = 2.5 if abs(change) > 0.5 else 1.5
            ax.plot([1, 2], [s1_val, s2_val], '-', color=color,
                    linewidth=lw, alpha=alpha, zorder=2)
            ax.plot(1, s1_val, marker, color=color, markersize=8,
                    alpha=alpha, zorder=3, markeredgecolor='white', markeredgewidth=1)
            ax.plot(2, s2_val, marker, color=color, markersize=8,
                    alpha=alpha, zorder=3, markeredgecolor='white', markeredgewidth=1)

            # Label with PID
            ax.annotate(pid, (2.05, s2_val), fontsize=7, color=color,
                        va='center', fontweight='bold')

        # Group means
        for grp, color, ls in [('G1', C_G1, '-'), ('G2', C_G2, '--')]:
            grp_paired = df[(df['PID'].isin(paired_pids)) & (df['Group'] == grp)]
            s1m = grp_paired[grp_paired['Session'] == 1][subscale].mean()
            s2m = grp_paired[grp_paired['Session'] == 2][subscale].mean()
            if not np.isnan(s1m) and not np.isnan(s2m):
                ax.plot([1, 2], [s1m, s2m], ls, color=color, linewidth=3.5,
                        alpha=0.3, zorder=1)

        ax.set_xlim(0.5, 2.8)
        ax.set_ylim(0.5, 6.5)
        ax.set_xticks([1, 2])
        ax.set_xticklabels(['S1', 'S2'], fontsize=11, fontweight='bold')
        ax.set_title(label, fontsize=12, fontweight='bold', color=C_TITLE, pad=8)
        ax.yaxis.grid(True, alpha=0.3, color=C_GRID)
        ax.set_axisbelow(True)
        ax.spines['top'].set_visible(False)
        ax.spines['right'].set_visible(False)

        if ax_idx == 0:
            ax.set_ylabel('Subscale Mean Score', fontsize=11, fontweight='bold')

    # Custom legend
    g1_patch = plt.Line2D([0], [0], marker='o', color=C_G1, linewidth=2,
                          label='G1 Remember', markersize=8, markeredgecolor='white')
    g2_patch = plt.Line2D([0], [0], marker='s', color=C_G2, linewidth=2,
                          label='G2 Control', linestyle='--', markersize=8,
                          markeredgecolor='white')
    fig.legend(handles=[g1_patch, g2_patch], loc='lower center', ncol=2,
               fontsize=11, framealpha=0.9, bbox_to_anchor=(0.5, -0.02))

    fig.suptitle('Figure 9: Individual Session Changes (S1 → S2) by Subscale',
                 fontsize=15, fontweight='bold', color=C_TITLE, y=1.0)
    fig.tight_layout(rect=[0, 0.04, 1, 0.95])
    return fig


# ══════════════════════════════════════════════════════════════════════════════
# FIGURE 10: Summary Statistics Table
# ══════════════════════════════════════════════════════════════════════════════
def draw_questionnaire_summary_table(df):
    """
    Clean summary table as a figure, showing means (SD) for each
    Group × Session × Subscale combination, plus overall.
    """
    fig, ax = plt.subplots(figsize=(14, 8))
    fig.patch.set_facecolor(C_BG)
    ax.set_facecolor(C_BG)
    ax.axis('off')

    # ── Build table data ─────────────────────────────────────────────
    rows = []
    row_labels = []
    row_colors = []

    conditions = [
        ('G1', 1, 'G1 Remember – S1 (NOVICE)', C_G1_LIGHT),
        ('G1', 2, 'G1 Remember – S2 (REMEMBER)', '#90CAF9'),
        ('G2', 1, 'G2 Control – S1 (NOVICE)', C_G2_LIGHT),
        ('G2', 2, 'G2 Control – S2 (NO-REMEMBER)', '#FFCC80'),
    ]

    for grp, sess, label, color in conditions:
        sub = df[(df['Group'] == grp) & (df['Session'] == sess)]
        n = len(sub)
        row = [f'n={n}']
        for s in SUBSCALES:
            m = sub[s].mean()
            sd = sub[s].std()
            row.append(f'{m:.2f} ({sd:.2f})')
        overall_m = sub['Overall'].mean()
        overall_sd = sub['Overall'].std()
        row.append(f'{overall_m:.2f} ({overall_sd:.2f})')
        rows.append(row)
        row_labels.append(label)
        row_colors.append(color)

    # Add delta rows (S2 - S1)
    for grp, label, color in [('G1', 'G1 Δ (S2 − S1)', C_G1), ('G2', 'G2 Δ (S2 − S1)', C_G2)]:
        s1 = df[(df['Group'] == grp) & (df['Session'] == 1)]
        s2 = df[(df['Group'] == grp) & (df['Session'] == 2)]
        row = ['']
        for s in SUBSCALES:
            delta = s2[s].mean() - s1[s].mean()
            arrow = '↑' if delta > 0 else '↓' if delta < 0 else '→'
            row.append(f'{arrow} {delta:+.2f}')
        delta_o = s2['Overall'].mean() - s1['Overall'].mean()
        arrow = '↑' if delta_o > 0 else '↓' if delta_o < 0 else '→'
        row.append(f'{arrow} {delta_o:+.2f}')
        rows.append(row)
        row_labels.append(label)
        row_colors.append('#E8EAF6' if grp == 'G1' else '#FFF8E1')

    col_labels = ['N'] + SUBSCALE_SHORT + ['Overall']

    table = ax.table(
        cellText=rows,
        rowLabels=row_labels,
        colLabels=col_labels,
        cellLoc='center',
        rowLoc='right',
        loc='center',
    )

    table.auto_set_font_size(False)
    table.set_fontsize(9.5)
    table.scale(1, 1.8)

    # Style cells
    for (i, j), cell in table.get_celld().items():
        cell.set_edgecolor('#BDBDBD')
        if i == 0:  # Header row
            cell.set_facecolor('#37474F')
            cell.set_text_props(color='white', fontweight='bold', fontsize=10)
        elif j == -1:  # Row labels
            cell.set_facecolor(row_colors[i-1])
            cell.set_text_props(fontweight='bold', fontsize=9)
        else:
            cell.set_facecolor(row_colors[i-1])
            # Highlight delta rows
            if i >= 5:
                cell.set_text_props(fontweight='bold', fontsize=9.5)

    ax.set_title('Figure 10: Questionnaire Summary Statistics — Mean (SD)',
                 fontsize=15, fontweight='bold', color=C_TITLE, pad=20, y=1.02)

    # Add notes
    ax.text(0.5, -0.05,
            'Note: P09 (Ha Yan) missing from all questionnaires. '
            'P03 missing S2 questionnaire. P04 dropped out after S1.\n'
            'P01 S1 Q16 imputed with row median. Likert scale: 1 (Strongly Disagree) – 7 (Strongly Agree).',
            transform=ax.transAxes, fontsize=8.5, ha='center', va='top',
            color=C_SUBTITLE, fontstyle='italic')

    fig.tight_layout()
    return fig


# ══════════════════════════════════════════════════════════════════════════════
# MAIN
# ══════════════════════════════════════════════════════════════════════════════
def main():
    print("=" * 60)
    print("HRI Reciprocity Study — Questionnaire Visualizations")
    print("=" * 60)

    os.makedirs(FIG_DIR, exist_ok=True)
    df = load_data()
    print(f"Loaded {len(df)} records from {DATA_PATH}")
    print(f"Participants: {sorted(df['PID'].unique().tolist())}")

    figures = [
        ("fig6_questionnaire_subscales", draw_questionnaire_subscales),
        ("fig7_questionnaire_radar", draw_questionnaire_radar),
        ("fig8_questionnaire_heatmap", draw_questionnaire_heatmap),
        ("fig9_questionnaire_session_change", draw_questionnaire_session_change),
        ("fig10_questionnaire_summary", draw_questionnaire_summary_table),
    ]

    for name, func in figures:
        print(f"\nCreating {name}...")
        fig = func(df)
        path = os.path.join(FIG_DIR, f"{name}.png")
        fig.savefig(path, dpi=200, bbox_inches='tight', facecolor=fig.get_facecolor())
        plt.close(fig)
        print(f"  ✓ Saved → {path}")

    print("\n" + "=" * 60)
    print("All questionnaire figures saved to figures/ folder!")
    print("=" * 60)


if __name__ == '__main__':
    main()
