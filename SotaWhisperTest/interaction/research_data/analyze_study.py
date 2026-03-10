#!/usr/bin/env python3
"""
analyze_study.py — Main analysis script for HRI Reciprocity Study.

Data sources:
  1. behavioral_data.csv  — auto-generated from parse_logs.py
  2. questionnaire_data.csv — manually created from Google Forms export
     (see TEMPLATE section below for required format)

Analysis performed:
  A. Descriptive statistics per Group × Session
  B. Mixed ANOVA (2 Groups × 2 Sessions) for each DV
  C. Between-groups: G1-S2 vs G2-S2 (independent t-test / Mann-Whitney U)
  D. Within-subjects: S1 vs S2 per group (paired t-test / Wilcoxon)
  E. Behavioral data analysis (duration, turns, user words)
  F. Visualizations (bar plots, interaction plots, box plots)

Output:
  - analysis_results.txt   (full statistical report)
  - figures/               (all plots as PNG)
  - summary_table.csv      (means and SDs for quick reference)

=============================================================================
QUESTIONNAIRE CSV FORMAT (questionnaire_data.csv):

participant_id,group,session,Q1,Q2,Q3,Q4,Q5,Q6,Q7,Q8,Q9,Q10,Q11,Q12,Q13,Q14,Q15,Q16,Q17,Q18,Q19,Q20,Q21,Q22,Q23,age,gender,nationality,robot_experience,ai_familiarity,seen_sota_before
P01,G1,1,5,6,4,5,5,6,5,4,5,6,3,4,3,4,5,6,2,5,5,5,6,5,6,22,Male,Indonesia,None,Somewhat,No
P01,G1,2,6,7,5,6,6,7,6,5,6,7,3,5,3,5,6,7,2,6,6,6,7,6,7,22,Male,Indonesia,None,Somewhat,No
...

Notes:
  - Q1-Q23: Likert 1-7
  - age: integer
  - gender: Male/Female/Non-binary
  - nationality: free text
  - robot_experience: None/Several times/Regular use/Expert
  - ai_familiarity: Not at all/Somewhat/Very familiar
  - seen_sota_before: Yes/No
  - Demographics (age onwards) can be empty for Session 2 rows
=============================================================================
"""

import os
import sys
import warnings
import numpy as np
import pandas as pd
from scipy import stats
from datetime import datetime

warnings.filterwarnings("ignore", category=FutureWarning)

# Try importing optional packages
try:
    import pingouin as pg
    HAS_PINGOUIN = True
except ImportError:
    HAS_PINGOUIN = False
    print("WARNING: pingouin not installed. Mixed ANOVA will be skipped.")
    print("  Install with: pip install pingouin")

try:
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    import seaborn as sns
    HAS_PLOT = True
    sns.set_theme(style="whitegrid", font_scale=1.1)
    plt.rcParams["figure.dpi"] = 150
except ImportError:
    HAS_PLOT = False
    print("WARNING: matplotlib/seaborn not installed. Plots will be skipped.")

# ================================================================
# Paths
# ================================================================
BASE_DIR = os.path.dirname(os.path.abspath(__file__))
BEHAVIORAL_CSV = os.path.join(BASE_DIR, "behavioral_data.csv")
QUESTIONNAIRE_CSV = os.path.join(BASE_DIR, "questionnaire_data.csv")
RESULTS_FILE = os.path.join(BASE_DIR, "analysis_results.txt")
SUMMARY_CSV = os.path.join(BASE_DIR, "summary_table.csv")
FIG_DIR = os.path.join(BASE_DIR, "figures")

# Section definitions
SECTIONS = {
    "A_Trust": ["Q1", "Q2", "Q3", "Q4", "Q5"],
    "B_Disclosure": ["Q6", "Q7", "Q8", "Q9", "Q10"],
    "C_Empathy": ["Q11", "Q12", "Q13", "Q14", "Q15"],
    "D_RobotChar": ["Q16", "Q17", "Q18", "Q19", "Q20"],
    "E_Continue": ["Q21", "Q22", "Q23"],
}

# Key individual items to analyze separately
KEY_ITEMS = {
    "Q12_Protective": "Q12",
    "Q14_HelpDesire": "Q14",
    "Q15_Emotional": "Q15",
    "Q18_HumanLike": "Q18",
    "Q19_Natural": "Q19",
    "Q20_Genuine": "Q20",
}

# ================================================================
# Output helper
# ================================================================
report_lines = []


def out(text=""):
    """Print and store for report."""
    print(text)
    report_lines.append(text)


def save_report():
    with open(RESULTS_FILE, "w", encoding="utf-8") as f:
        f.write("\n".join(report_lines))
    out(f"\nFull report saved to: {RESULTS_FILE}")


# ================================================================
# Statistical helpers
# ================================================================

def test_normality(data, label=""):
    """Shapiro-Wilk test. Returns (W, p, is_normal)."""
    if len(data) < 3:
        return None, None, False
    w, p = stats.shapiro(data)
    return w, p, p > 0.05


def effect_size_d(g1, g2):
    """Cohen's d (pooled SD)."""
    n1, n2 = len(g1), len(g2)
    if n1 < 2 or n2 < 2:
        return float("nan")
    s1, s2 = np.std(g1, ddof=1), np.std(g2, ddof=1)
    pooled = np.sqrt(((n1 - 1) * s1**2 + (n2 - 1) * s2**2) / (n1 + n2 - 2))
    if pooled == 0:
        return 0
    return (np.mean(g1) - np.mean(g2)) / pooled


def interpret_d(d):
    """Interpret Cohen's d magnitude."""
    d = abs(d)
    if d < 0.2:
        return "negligible"
    elif d < 0.5:
        return "small"
    elif d < 0.8:
        return "medium"
    else:
        return "large"


def sig_stars(p):
    if p is None:
        return ""
    if p < 0.001:
        return "***"
    elif p < 0.01:
        return "**"
    elif p < 0.05:
        return "*"
    else:
        return "ns"


def between_group_test(g1_data, g2_data, label):
    """Independent samples comparison: t-test + Mann-Whitney U."""
    out(f"\n  --- {label} ---")
    out(f"  G1 (n={len(g1_data)}): M={np.mean(g1_data):.2f}, SD={np.std(g1_data, ddof=1):.2f}")
    out(f"  G2 (n={len(g2_data)}): M={np.mean(g2_data):.2f}, SD={np.std(g2_data, ddof=1):.2f}")

    if len(g1_data) < 2 or len(g2_data) < 2:
        out("  Insufficient data for statistical test.")
        return

    # Normality checks
    _, p1, norm1 = test_normality(g1_data)
    _, p2, norm2 = test_normality(g2_data)

    # t-test
    t_stat, t_p = stats.ttest_ind(g1_data, g2_data, equal_var=False)
    d = effect_size_d(g1_data, g2_data)
    out(f"  Welch's t-test: t={t_stat:.3f}, p={t_p:.4f} {sig_stars(t_p)}")
    out(f"  Cohen's d = {d:.3f} ({interpret_d(d)})")

    # Non-parametric alternative
    u_stat, u_p = stats.mannwhitneyu(g1_data, g2_data, alternative="two-sided")
    out(f"  Mann-Whitney U: U={u_stat:.1f}, p={u_p:.4f} {sig_stars(u_p)}")

    if not (norm1 and norm2):
        out(f"  Note: Non-normal distribution detected (Shapiro p: G1={p1:.3f}, G2={p2:.3f}). Use Mann-Whitney U.")


def within_group_test(s1_data, s2_data, label, group_name):
    """Paired comparison: S1 vs S2 within one group."""
    out(f"\n  --- {label} ({group_name}: S1 vs S2) ---")
    out(f"  S1 (n={len(s1_data)}): M={np.mean(s1_data):.2f}, SD={np.std(s1_data, ddof=1):.2f}")
    out(f"  S2 (n={len(s2_data)}): M={np.mean(s2_data):.2f}, SD={np.std(s2_data, ddof=1):.2f}")
    out(f"  Change: {np.mean(s2_data) - np.mean(s1_data):+.2f}")

    if len(s1_data) < 2:
        out("  Insufficient data for statistical test.")
        return

    # Paired t-test
    t_stat, t_p = stats.ttest_rel(s1_data, s2_data)
    d = effect_size_d(s2_data, s1_data)
    out(f"  Paired t-test: t={t_stat:.3f}, p={t_p:.4f} {sig_stars(t_p)}")
    out(f"  Cohen's d = {d:.3f} ({interpret_d(d)})")

    # Wilcoxon signed-rank
    try:
        w_stat, w_p = stats.wilcoxon(s1_data, s2_data)
        out(f"  Wilcoxon signed-rank: W={w_stat:.1f}, p={w_p:.4f} {sig_stars(w_p)}")
    except ValueError:
        out("  Wilcoxon: cannot compute (all differences zero or n too small)")


# ================================================================
# Visualization helpers
# ================================================================

def ensure_fig_dir():
    os.makedirs(FIG_DIR, exist_ok=True)


def plot_interaction(df_long, dv, title, filename):
    """Interaction plot: Group × Session line plot with error bars."""
    if not HAS_PLOT:
        return
    ensure_fig_dir()

    fig, ax = plt.subplots(figsize=(7, 5))

    # Compute means and SEs
    summary = df_long.groupby(["group", "session"])[dv].agg(["mean", "sem", "count"]).reset_index()

    colors = {"G1": "#2196F3", "G2": "#FF5722"}
    markers = {"G1": "o", "G2": "s"}

    for grp in ["G1", "G2"]:
        grp_data = summary[summary["group"] == grp].sort_values("session")
        if grp_data.empty:
            continue
        sessions = grp_data["session"].values
        means = grp_data["mean"].values
        sems = grp_data["sem"].values
        label = f"{grp} ({'Treatment' if grp == 'G1' else 'Control'})"
        ax.errorbar(sessions, means, yerr=sems, marker=markers[grp],
                     color=colors[grp], label=label, linewidth=2,
                     markersize=8, capsize=5)

    ax.set_xticks([1, 2])
    ax.set_xticklabels(["Session 1\n(Baseline)", "Session 2\n(Manipulation)"])
    ax.set_ylabel(f"Mean Score (1-7)")
    ax.set_title(title)
    ax.legend(loc="best")
    ax.set_ylim(1, 7.5)
    ax.grid(True, alpha=0.3)

    fig.tight_layout()
    fig.savefig(os.path.join(FIG_DIR, filename), dpi=150, bbox_inches="tight")
    plt.close(fig)
    out(f"  Plot saved: figures/{filename}")


def plot_grouped_bars(df_long, dv, title, filename):
    """Grouped bar chart: Group × Session."""
    if not HAS_PLOT:
        return
    ensure_fig_dir()

    fig, ax = plt.subplots(figsize=(7, 5))

    summary = df_long.groupby(["group", "session"])[dv].agg(["mean", "sem"]).reset_index()

    x = np.arange(2)  # S1, S2
    width = 0.3
    colors = {"G1": "#2196F3", "G2": "#FF5722"}

    for i, grp in enumerate(["G1", "G2"]):
        grp_data = summary[summary["group"] == grp].sort_values("session")
        if grp_data.empty:
            continue
        means = grp_data["mean"].values
        sems = grp_data["sem"].values
        offset = (i - 0.5) * width
        label = f"{grp} ({'Treatment' if grp == 'G1' else 'Control'})"
        ax.bar(x + offset, means, width, yerr=sems, color=colors[grp],
               label=label, capsize=5, alpha=0.85, edgecolor="white")

    ax.set_xticks(x)
    ax.set_xticklabels(["Session 1\n(Baseline)", "Session 2\n(Manipulation)"])
    ax.set_ylabel("Mean Score (1-7)")
    ax.set_title(title)
    ax.legend(loc="best")
    ax.set_ylim(0, 7.5)
    ax.grid(True, alpha=0.3, axis="y")

    fig.tight_layout()
    fig.savefig(os.path.join(FIG_DIR, filename), dpi=150, bbox_inches="tight")
    plt.close(fig)


def plot_boxplots_s2(df_s2, dv, title, filename):
    """Boxplot comparing G1 vs G2 at Session 2."""
    if not HAS_PLOT:
        return
    ensure_fig_dir()

    fig, ax = plt.subplots(figsize=(6, 5))
    colors = ["#2196F3", "#FF5722"]

    groups = []
    data = []
    for grp in ["G1", "G2"]:
        vals = df_s2[df_s2["group"] == grp][dv].dropna().values
        for v in vals:
            groups.append(f"{grp}\n({'Treatment' if grp == 'G1' else 'Control'})")
            data.append(v)

    plot_df = pd.DataFrame({"Group": groups, dv: data})
    sns.boxplot(data=plot_df, x="Group", y=dv, palette=colors, ax=ax, width=0.5)
    sns.stripplot(data=plot_df, x="Group", y=dv, color="black", size=5, alpha=0.5, ax=ax)

    ax.set_ylabel("Mean Score (1-7)")
    ax.set_title(title)
    ax.set_ylim(1, 7.5)

    fig.tight_layout()
    fig.savefig(os.path.join(FIG_DIR, filename), dpi=150, bbox_inches="tight")
    plt.close(fig)


def plot_behavioral_comparison(beh_df, dv, ylabel, title, filename):
    """Bar chart for behavioral measures (duration, turns, etc.)."""
    if not HAS_PLOT:
        return
    ensure_fig_dir()

    fig, ax = plt.subplots(figsize=(7, 5))

    summary = beh_df.groupby(["group", "session"])[dv].agg(["mean", "sem"]).reset_index()

    x = np.arange(2)
    width = 0.3
    colors = {"G1": "#2196F3", "G2": "#FF5722"}

    for i, grp in enumerate(["G1", "G2"]):
        grp_data = summary[summary["group"] == grp].sort_values("session")
        if grp_data.empty:
            continue
        means = grp_data["mean"].values
        sems = grp_data["sem"].values
        if len(means) < 2:
            # Only one session available
            offset = (i - 0.5) * width
            ax.bar(x[:len(means)] + offset, means, width, yerr=sems,
                   color=colors[grp], label=grp, capsize=5, alpha=0.85)
            continue
        offset = (i - 0.5) * width
        ax.bar(x + offset, means, width, yerr=sems, color=colors[grp],
               label=grp, capsize=5, alpha=0.85, edgecolor="white")

    ax.set_xticks(x)
    ax.set_xticklabels(["Session 1", "Session 2"])
    ax.set_ylabel(ylabel)
    ax.set_title(title)
    ax.legend()
    ax.grid(True, alpha=0.3, axis="y")

    fig.tight_layout()
    fig.savefig(os.path.join(FIG_DIR, filename), dpi=150, bbox_inches="tight")
    plt.close(fig)


def plot_radar_s2(section_means_g1, section_means_g2, filename):
    """Radar/spider chart comparing G1 vs G2 at Session 2 across all sections."""
    if not HAS_PLOT:
        return
    ensure_fig_dir()

    labels = list(section_means_g1.keys())
    n = len(labels)
    angles = np.linspace(0, 2 * np.pi, n, endpoint=False).tolist()
    angles += angles[:1]  # close the polygon

    g1_vals = list(section_means_g1.values()) + [list(section_means_g1.values())[0]]
    g2_vals = list(section_means_g2.values()) + [list(section_means_g2.values())[0]]

    fig, ax = plt.subplots(figsize=(7, 7), subplot_kw=dict(polar=True))
    ax.plot(angles, g1_vals, "o-", color="#2196F3", linewidth=2, label="G1 (Treatment)")
    ax.fill(angles, g1_vals, color="#2196F3", alpha=0.15)
    ax.plot(angles, g2_vals, "s-", color="#FF5722", linewidth=2, label="G2 (Control)")
    ax.fill(angles, g2_vals, color="#FF5722", alpha=0.15)

    ax.set_xticks(angles[:-1])
    ax.set_xticklabels([l.replace("_", "\n") for l in labels])
    ax.set_ylim(1, 7)
    ax.set_title("Section Scores at Session 2\nG1 (Treatment) vs G2 (Control)", y=1.08)
    ax.legend(loc="upper right", bbox_to_anchor=(1.3, 1.1))

    fig.tight_layout()
    fig.savefig(os.path.join(FIG_DIR, filename), dpi=150, bbox_inches="tight")
    plt.close(fig)


def plot_all_sections_interaction(df_long, sections_dict, filename):
    """Multi-panel interaction plot — one panel per section."""
    if not HAS_PLOT:
        return
    ensure_fig_dir()

    n_sections = len(sections_dict)
    fig, axes = plt.subplots(1, n_sections, figsize=(4 * n_sections, 5), sharey=True)
    if n_sections == 1:
        axes = [axes]

    colors = {"G1": "#2196F3", "G2": "#FF5722"}

    for idx, (sec_name, _) in enumerate(sections_dict.items()):
        ax = axes[idx]
        col = f"mean_{sec_name}"
        if col not in df_long.columns:
            continue
        summary = df_long.groupby(["group", "session"])[col].agg(["mean", "sem"]).reset_index()

        for grp in ["G1", "G2"]:
            grp_data = summary[summary["group"] == grp].sort_values("session")
            if grp_data.empty:
                continue
            sessions = grp_data["session"].values
            means = grp_data["mean"].values
            sems = grp_data["sem"].values
            marker = "o" if grp == "G1" else "s"
            ax.errorbar(sessions, means, yerr=sems, marker=marker,
                         color=colors[grp], linewidth=2, markersize=7, capsize=4,
                         label=grp if idx == 0 else "")

        ax.set_xticks([1, 2])
        ax.set_xticklabels(["S1", "S2"])
        ax.set_title(sec_name.replace("_", " "))
        ax.set_ylim(1, 7.5)
        ax.grid(True, alpha=0.3)

    axes[0].set_ylabel("Mean Score (1-7)")
    fig.legend(["G1 (Treatment)", "G2 (Control)"], loc="upper center",
               ncol=2, bbox_to_anchor=(0.5, 1.02))
    fig.suptitle("Questionnaire Sections: Group × Session Interaction", y=1.06, fontsize=14)
    fig.tight_layout()
    fig.savefig(os.path.join(FIG_DIR, filename), dpi=150, bbox_inches="tight")
    plt.close(fig)


# ================================================================
# Main analysis
# ================================================================

def main():
    timestamp = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    out("=" * 70)
    out("  HRI RECIPROCITY STUDY — STATISTICAL ANALYSIS REPORT")
    out(f"  Generated: {timestamp}")
    out("=" * 70)

    # ----------------------------------------------------------
    # 1. Load questionnaire data
    # ----------------------------------------------------------
    if not os.path.exists(QUESTIONNAIRE_CSV):
        out(f"\nWARNING: {QUESTIONNAIRE_CSV} not found.")
        out("Please create questionnaire_data.csv from your Google Forms exports.")
        out("See the docstring at the top of this script for the required format.\n")
        q_df = None
    else:
        q_df = pd.read_csv(QUESTIONNAIRE_CSV)
        out(f"\nLoaded questionnaire data: {len(q_df)} rows")
        out(f"  Participants: {sorted(q_df['participant_id'].unique())}")
        out(f"  Groups: {sorted(q_df['group'].unique())}")
        out(f"  Sessions: {sorted(q_df['session'].unique())}")

        # Compute section means
        for sec_name, items in SECTIONS.items():
            existing = [q for q in items if q in q_df.columns]
            if existing:
                q_df[f"mean_{sec_name}"] = q_df[existing].mean(axis=1)

    # ----------------------------------------------------------
    # 2. Load behavioral data
    # ----------------------------------------------------------
    if not os.path.exists(BEHAVIORAL_CSV):
        out(f"\nWARNING: {BEHAVIORAL_CSV} not found.")
        out("Run parse_logs.py first to generate it.\n")
        b_df = None
    else:
        b_df = pd.read_csv(BEHAVIORAL_CSV)
        out(f"\nLoaded behavioral data: {len(b_df)} rows")

    # ----------------------------------------------------------
    # 3. Demographics summary
    # ----------------------------------------------------------
    if q_df is not None and "age" in q_df.columns:
        out("\n" + "=" * 70)
        out("  DEMOGRAPHICS SUMMARY")
        out("=" * 70)

        demo = q_df[q_df["session"] == 1].copy()  # demographics from S1 only
        demo["age"] = pd.to_numeric(demo["age"], errors="coerce")

        for grp in ["G1", "G2"]:
            g = demo[demo["group"] == grp]
            out(f"\n  {grp} (n={len(g)}):")
            if "age" in g.columns and g["age"].notna().any():
                out(f"    Age: M={g['age'].mean():.1f}, SD={g['age'].std():.1f}, "
                    f"range={g['age'].min():.0f}-{g['age'].max():.0f}")
            if "gender" in g.columns:
                out(f"    Gender: {g['gender'].value_counts().to_dict()}")
            if "nationality" in g.columns:
                out(f"    Nationality: {g['nationality'].value_counts().to_dict()}")
            if "robot_experience" in g.columns:
                out(f"    Robot exp: {g['robot_experience'].value_counts().to_dict()}")

        # Check group balance
        g1_ages = demo[demo["group"] == "G1"]["age"].dropna()
        g2_ages = demo[demo["group"] == "G2"]["age"].dropna()
        if len(g1_ages) >= 2 and len(g2_ages) >= 2:
            t, p = stats.ttest_ind(g1_ages, g2_ages)
            out(f"\n  Age balance check: t={t:.2f}, p={p:.3f} {'(balanced)' if p > 0.05 else '(IMBALANCED!)'}")

    # ----------------------------------------------------------
    # 4. Questionnaire analysis
    # ----------------------------------------------------------
    if q_df is not None:
        out("\n" + "=" * 70)
        out("  QUESTIONNAIRE ANALYSIS")
        out("=" * 70)

        # ---- Descriptive statistics ----
        out("\n--- Descriptive Statistics (Section Means) ---")

        summary_rows = []
        for sec_name in SECTIONS:
            col = f"mean_{sec_name}"
            if col not in q_df.columns:
                continue
            for grp in ["G1", "G2"]:
                for sess in [1, 2]:
                    vals = q_df[(q_df["group"] == grp) & (q_df["session"] == sess)][col].dropna()
                    if len(vals) > 0:
                        row = {
                            "Section": sec_name,
                            "Group": grp,
                            "Session": sess,
                            "N": len(vals),
                            "Mean": round(vals.mean(), 2),
                            "SD": round(vals.std(), 2),
                            "Median": round(vals.median(), 2),
                            "Min": round(vals.min(), 2),
                            "Max": round(vals.max(), 2),
                        }
                        summary_rows.append(row)
                        out(f"  {sec_name} {grp}-S{sess} (n={len(vals)}): "
                            f"M={vals.mean():.2f}, SD={vals.std():.2f}")

        if summary_rows:
            summary_df = pd.DataFrame(summary_rows)
            summary_df.to_csv(SUMMARY_CSV, index=False)
            out(f"\n  Summary table saved: {SUMMARY_CSV}")

        # ---- Mixed ANOVA (Group × Session) ----
        out("\n" + "-" * 50)
        out("--- Mixed ANOVA (Group × Session) ---")
        out("  Between: Group (G1 vs G2)")
        out("  Within : Session (S1 vs S2)")

        # Need participants with BOTH sessions
        both = q_df.groupby("participant_id")["session"].nunique()
        both_pids = both[both == 2].index.tolist()
        q_both = q_df[q_df["participant_id"].isin(both_pids)].copy()
        out(f"\n  Participants with both sessions: {len(both_pids)}")

        if HAS_PINGOUIN and len(both_pids) >= 4:
            for sec_name in SECTIONS:
                col = f"mean_{sec_name}"
                if col not in q_both.columns:
                    continue
                out(f"\n  === {sec_name} ===")
                try:
                    aov = pg.mixed_anova(
                        data=q_both, dv=col, within="session",
                        between="group", subject="participant_id"
                    )
                    for _, row in aov.iterrows():
                        src = row["Source"]
                        f_val = row["F"]
                        p_val = row["p-unc"]
                        eta = row.get("np2", row.get("eta-squared", "N/A"))
                        out(f"    {src}: F={f_val:.3f}, p={p_val:.4f} {sig_stars(p_val)}, "
                            f"partial eta²={eta:.3f}")
                except Exception as e:
                    out(f"    Error: {e}")
        elif len(both_pids) < 4:
            out("  Skipped: Need at least 4 participants with both sessions.")

        # ---- PRIMARY: G1-S2 vs G2-S2 (between-groups) ----
        out("\n" + "-" * 50)
        out("--- PRIMARY ANALYSIS: G1-S2 vs G2-S2 (Between-Groups) ---")
        out("  This is the main comparison that tests all hypotheses.")

        for sec_name in SECTIONS:
            col = f"mean_{sec_name}"
            if col not in q_df.columns:
                continue
            g1_s2 = q_df[(q_df["group"] == "G1") & (q_df["session"] == 2)][col].dropna().values
            g2_s2 = q_df[(q_df["group"] == "G2") & (q_df["session"] == 2)][col].dropna().values
            if len(g1_s2) > 0 and len(g2_s2) > 0:
                between_group_test(g1_s2, g2_s2, f"{sec_name} (G1-S2 vs G2-S2)")

        # Key individual items at S2
        out("\n" + "-" * 50)
        out("--- KEY ITEMS: G1-S2 vs G2-S2 ---")
        for label, qcol in KEY_ITEMS.items():
            if qcol not in q_df.columns:
                continue
            g1_s2 = q_df[(q_df["group"] == "G1") & (q_df["session"] == 2)][qcol].dropna().values
            g2_s2 = q_df[(q_df["group"] == "G2") & (q_df["session"] == 2)][qcol].dropna().values
            if len(g1_s2) > 0 and len(g2_s2) > 0:
                between_group_test(g1_s2.astype(float), g2_s2.astype(float),
                                    f"{label} (G1-S2 vs G2-S2)")

        # ---- SECONDARY: Within-group S1 vs S2 ----
        out("\n" + "-" * 50)
        out("--- SECONDARY ANALYSIS: S1 vs S2 Within Each Group ---")
        out("  Expected: G1 shows increase; G2 stays flat.")

        for sec_name in SECTIONS:
            col = f"mean_{sec_name}"
            if col not in q_both.columns:
                continue
            for grp in ["G1", "G2"]:
                pids = q_both[q_both["group"] == grp]["participant_id"].unique()
                s1_vals, s2_vals = [], []
                for pid in pids:
                    s1 = q_both[(q_both["participant_id"] == pid) & (q_both["session"] == 1)][col].values
                    s2 = q_both[(q_both["participant_id"] == pid) & (q_both["session"] == 2)][col].values
                    if len(s1) > 0 and len(s2) > 0:
                        s1_vals.append(s1[0])
                        s2_vals.append(s2[0])
                if len(s1_vals) >= 2:
                    within_group_test(
                        np.array(s1_vals), np.array(s2_vals),
                        sec_name, grp
                    )

        # ---- Visualizations ----
        if HAS_PLOT:
            out("\n" + "-" * 50)
            out("--- GENERATING VISUALIZATIONS ---")

            # Interaction plots per section
            for sec_name in SECTIONS:
                col = f"mean_{sec_name}"
                if col not in q_df.columns:
                    continue
                plot_interaction(q_df, col,
                                 f"{sec_name.replace('_', ': ')} — Group × Session",
                                 f"interaction_{sec_name}.png")
                plot_grouped_bars(q_df, col,
                                  f"{sec_name.replace('_', ': ')} — Group × Session",
                                  f"bars_{sec_name}.png")

            # Box plots at S2
            q_s2 = q_df[q_df["session"] == 2]
            for sec_name in SECTIONS:
                col = f"mean_{sec_name}"
                if col not in q_s2.columns:
                    continue
                plot_boxplots_s2(q_s2, col,
                                 f"{sec_name.replace('_', ': ')} at Session 2",
                                 f"box_s2_{sec_name}.png")

            # Multi-panel interaction plot
            plot_all_sections_interaction(q_df, SECTIONS,
                                          "all_sections_interaction.png")

            # Radar chart at S2
            g1_s2_means = {}
            g2_s2_means = {}
            for sec_name in SECTIONS:
                col = f"mean_{sec_name}"
                if col not in q_s2.columns:
                    continue
                g1_v = q_s2[q_s2["group"] == "G1"][col].dropna()
                g2_v = q_s2[q_s2["group"] == "G2"][col].dropna()
                if len(g1_v) > 0:
                    g1_s2_means[sec_name] = g1_v.mean()
                if len(g2_v) > 0:
                    g2_s2_means[sec_name] = g2_v.mean()

            if g1_s2_means and g2_s2_means:
                plot_radar_s2(g1_s2_means, g2_s2_means, "radar_s2.png")
                out("  Plot saved: figures/radar_s2.png")

    # ----------------------------------------------------------
    # 5. Behavioral data analysis
    # ----------------------------------------------------------
    if b_df is not None:
        out("\n" + "=" * 70)
        out("  BEHAVIORAL DATA ANALYSIS")
        out("=" * 70)

        beh_vars = {
            "duration_sec": "Duration (seconds)",
            "total_turns": "Total Turns",
            "user_turns": "User Turns",
            "avg_user_words": "Avg Words per User Turn",
            "total_user_words": "Total User Words",
        }

        # Descriptive
        out("\n--- Descriptive Statistics ---")
        for var, label in beh_vars.items():
            if var not in b_df.columns:
                continue
            for grp in ["G1", "G2"]:
                for sess in [1, 2]:
                    vals = b_df[(b_df["group"] == grp) & (b_df["session"] == sess)][var].dropna()
                    if len(vals) > 0:
                        out(f"  {label} {grp}-S{sess} (n={len(vals)}): "
                            f"M={vals.mean():.1f}, SD={vals.std():.1f}")

        # Between-groups at S2
        out("\n--- Behavioral: G1-S2 vs G2-S2 ---")
        for var, label in beh_vars.items():
            if var not in b_df.columns:
                continue
            g1_s2 = b_df[(b_df["group"] == "G1") & (b_df["session"] == 2)][var].dropna().values
            g2_s2 = b_df[(b_df["group"] == "G2") & (b_df["session"] == 2)][var].dropna().values
            if len(g1_s2) > 0 and len(g2_s2) > 0:
                between_group_test(g1_s2, g2_s2, f"{label} (G1-S2 vs G2-S2)")

        # Plots
        if HAS_PLOT:
            for var, label in beh_vars.items():
                if var not in b_df.columns:
                    continue
                plot_behavioral_comparison(b_df, var, label,
                                           f"Behavioral: {label}",
                                           f"behavioral_{var}.png")

        # Goodbye analysis
        if "user_initiated_goodbye" in b_df.columns:
            out("\n--- Goodbye Analysis ---")
            for grp in ["G1", "G2"]:
                for sess in [1, 2]:
                    subset = b_df[(b_df["group"] == grp) & (b_df["session"] == sess)]
                    n = len(subset)
                    user_bye = subset["user_initiated_goodbye"].sum()
                    out(f"  {grp}-S{sess}: {user_bye}/{n} user-initiated goodbyes "
                        f"({user_bye/n*100:.0f}%)" if n > 0 else f"  {grp}-S{sess}: no data")

    # ----------------------------------------------------------
    # 6. Save report
    # ----------------------------------------------------------
    save_report()

    out(f"\n{'=' * 70}")
    out("  ANALYSIS COMPLETE")
    out(f"{'=' * 70}")
    if HAS_PLOT:
        out(f"  Figures saved in: {FIG_DIR}/")
    out(f"  Report saved to:  {RESULTS_FILE}")
    if os.path.exists(SUMMARY_CSV):
        out(f"  Summary table:    {SUMMARY_CSV}")
    out("")


if __name__ == "__main__":
    main()
