#!/usr/bin/env python3
"""
Preprocess Questionnaire Data for HRI Reciprocity Study
========================================================
Reads raw Google Forms CSVs, fixes known issues, maps to participant IDs,
and outputs a clean analysis-ready CSV.

Issues fixed:
  1. Nathania Cahya Romadhona moved from S1G1 → S1G2 (she is P02, G2 Control)
  2. P09 (Ha Yan) identified as missing from all questionnaires
  3. P03 (Rudy Ong) missing from S2 questionnaire
  4. P04 (MRR) missing S2 (dropped out after S1)
  5. Marzuli S1G1 Q16 missing value → imputed with row median
"""

import pandas as pd
import numpy as np
import os
import warnings
warnings.filterwarnings('ignore')

# ── Paths ────────────────────────────────────────────────────────────────────
RAW_DIR = r"C:\Users\interact-ai-001\Downloads\Tmp logs sota reci\Questionnaire"
OUT_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)))

S1G1_PATH = os.path.join(RAW_DIR, "Sota Reciprocity Questionnaire - S1G1.csv",
                         "Sota Reciprocity Questionnaire - S1G1.csv")
S1G2_PATH = os.path.join(RAW_DIR, "Sota Reciprocity Questionnaire - S1G2.csv",
                         "Sota Reciprocity Questionnaire - S1G2.csv")
S2G1_PATH = os.path.join(RAW_DIR, "Session 2 Questionnaire Group 1.csv",
                         "Session 2 Questionnaire Group 1.csv")
S2G2_PATH = os.path.join(RAW_DIR, "Session 2 Questionnaire.csv",
                         "Session 2 Questionnaire.csv")

# ── Question mapping ─────────────────────────────────────────────────────────
# 23 Likert items (7-point scale) across 5 subscales
Q_LABELS = [
    "I felt safe talking with Sota",                       # Q1  Trust
    "Sota seemed trustworthy",                             # Q2  Trust
    "I would trust Sota with personal information",        # Q3  Trust
    "Sota seemed capable and competent",                   # Q4  Trust
    "I felt Sota understood my concerns",                  # Q5  Trust
    "I shared personal information willingly",             # Q6  Disclosure
    "I felt comfortable being honest with Sota",           # Q7  Disclosure
    "I would share more sensitive topics in future",       # Q8  Disclosure
    "I trust Sota with my feelings",                       # Q9  Disclosure
    "Sota made me feel heard and understood",              # Q10 Disclosure
    "Sota seemed vulnerable or uncertain",                 # Q11 Empathy
    "I felt protective or caring toward Sota",             # Q12 Empathy
    "Sota appeared to need support or reassurance",        # Q13 Empathy
    "I wanted to help Sota feel better",                   # Q14 Empathy
    "I felt emotionally connected to Sota",                # Q15 Empathy
    "Sota seemed confident in this interaction",           # Q16 RobotChar
    "Sota seemed distressed or struggling",                # Q17 RobotChar
    "Sota seemed human-like in its responses",             # Q18 RobotChar
    "I found Sota's behavior natural and authentic",       # Q19 RobotChar
    "Sota's emotions seemed genuine",                      # Q20 RobotChar
    "I would like to interact with Sota again",            # Q21 Continue
    "I would recommend Sota to others",                    # Q22 Continue
    "This interaction was valuable/meaningful",             # Q23 Continue
]

Q_CODES = [f"Q{i+1}" for i in range(23)]

SUBSCALE_MAP = {
    'A_Trust':      ['Q1','Q2','Q3','Q4','Q5'],
    'B_Disclosure': ['Q6','Q7','Q8','Q9','Q10'],
    'C_Empathy':    ['Q11','Q12','Q13','Q14','Q15'],
    'D_RobotChar':  ['Q16','Q17','Q18','Q19','Q20'],
    'E_Continue':   ['Q21','Q22','Q23'],
}

# Open-ended Qs in S2 forms only
OPEN_LABELS = [
    "What did you notice most about Sota's behavior or appearance?",
    "How would you describe Sota's emotional or mental state?",
    "Did Sota's behavior make you more/less comfortable sharing personal information? why?",
    "Did you feel any desire to help or support Sota? please explain",
    "Is there anything else you would like to tell us about your experience?",
]
OPEN_CODES = [f"OQ{i+1}" for i in range(5)]

# ── Name → Participant ID mapping ────────────────────────────────────────────
NAME_TO_PID = {
    "Marzuli Suhada M":        "P01",
    "Nathania Cahya Romadhona":"P02",
    "Rudy Ong":                "P03",
    "MRR":                     "P04",
    "Sherwynn":                "P05",
    "Jessica":                 "P06",
    "Rupak Raj Ghimire":       "P08",
    "Nadim":                   "P07",
}

PID_TO_GROUP = {
    "P01": "G1", "P02": "G2", "P03": "G1", "P04": "G2",
    "P05": "G1", "P06": "G2", "P07": "G1", "P08": "G2",
    "P09": "G1",
}

PID_TO_NAME = {v: k for k, v in NAME_TO_PID.items()}
PID_TO_NAME["P09"] = "Ha Yan"


def normalize_name(name: str) -> str:
    """Strip whitespace and normalize name for matching."""
    return name.strip()


def read_form_csv(path: str, has_open: bool = False) -> pd.DataFrame:
    """Read a Google Forms CSV and return standardised DataFrame."""
    df = pd.read_csv(path, dtype=str)
    # Normalize column names by stripping whitespace
    df.columns = df.columns.str.strip()
    # Normalize Name column
    df['Name'] = df['Name'].apply(normalize_name)
    return df


def extract_likert(df: pd.DataFrame) -> pd.DataFrame:
    """Extract the 23 Likert items and convert to numeric."""
    # Columns 3..25 (0-indexed: col 0=Timestamp, 1=Name, 2=Nationality, 3..25=Q1-Q23)
    likert_cols = df.columns[3:26].tolist()
    out = df[['Name', 'Nationality']].copy()
    for i, col in enumerate(likert_cols):
        out[Q_CODES[i]] = pd.to_numeric(df[col], errors='coerce')
    return out


def extract_open(df: pd.DataFrame) -> pd.DataFrame:
    """Extract 5 open-ended responses from S2 forms."""
    if len(df.columns) < 29:
        return pd.DataFrame()
    open_cols = df.columns[26:31].tolist()
    out = df[['Name']].copy()
    for i, col in enumerate(open_cols):
        out[OPEN_CODES[i]] = df[col].fillna('')
    return out


def main():
    print("=" * 60)
    print("HRI Reciprocity Study — Questionnaire Preprocessing")
    print("=" * 60)

    # ── 1. Read raw CSVs ─────────────────────────────────────────────────
    print("\n[1/6] Reading raw CSV files...")
    s1g1_raw = read_form_csv(S1G1_PATH)
    s1g2_raw = read_form_csv(S1G2_PATH)
    s2g1_raw = read_form_csv(S2G1_PATH, has_open=True)
    s2g2_raw = read_form_csv(S2G2_PATH, has_open=True)

    print(f"  S1G1: {len(s1g1_raw)} responses — {', '.join(s1g1_raw['Name'].tolist())}")
    print(f"  S1G2: {len(s1g2_raw)} responses — {', '.join(s1g2_raw['Name'].tolist())}")
    print(f"  S2G1: {len(s2g1_raw)} responses — {', '.join(s2g1_raw['Name'].tolist())}")
    print(f"  S2G2: {len(s2g2_raw)} responses — {', '.join(s2g2_raw['Name'].tolist())}")

    # ── 2. Fix Nathania: move from S1G1 → S1G2 ──────────────────────────
    print("\n[2/6] Fixing group assignment...")
    nathania_mask = s1g1_raw['Name'] == 'Nathania Cahya Romadhona'
    if nathania_mask.sum() == 1:
        nathania_row = s1g1_raw[nathania_mask].copy()
        s1g1_raw = s1g1_raw[~nathania_mask].reset_index(drop=True)
        s1g2_raw = pd.concat([s1g2_raw, nathania_row], ignore_index=True)
        print("  ✓ Moved Nathania Cahya Romadhona: S1G1 → S1G2")
    else:
        print("  ⚠ Nathania not found in S1G1 (already fixed?)")

    print(f"  S1G1 now: {len(s1g1_raw)} responses — {', '.join(s1g1_raw['Name'].tolist())}")
    print(f"  S1G2 now: {len(s1g2_raw)} responses — {', '.join(s1g2_raw['Name'].tolist())}")

    # ── 3. Extract Likert data & assign participant IDs ──────────────────
    print("\n[3/6] Extracting Likert data & mapping participant IDs...")

    all_rows = []

    for source_label, df, session, group in [
        ("S1G1", s1g1_raw, 1, "G1"),
        ("S1G2", s1g2_raw, 1, "G2"),
        ("S2G1", s2g1_raw, 2, "G1"),
        ("S2G2", s2g2_raw, 2, "G2"),
    ]:
        likert = extract_likert(df)
        for _, row in likert.iterrows():
            name = row['Name']
            pid = NAME_TO_PID.get(name, "UNKNOWN")
            if pid == "UNKNOWN":
                print(f"  ⚠ Unknown name '{name}' in {source_label}")
                continue
            condition = "REMEMBER" if group == "G1" and session == 2 else \
                        "NO-REMEMBER" if group == "G2" and session == 2 else "NOVICE"
            rec = {
                'PID': pid,
                'Name': name,
                'Nationality': row['Nationality'],
                'Group': group,
                'Session': session,
                'Condition': condition,
            }
            for q in Q_CODES:
                rec[q] = row[q]
            all_rows.append(rec)

    df_all = pd.DataFrame(all_rows)
    df_all = df_all.sort_values(['PID', 'Session']).reset_index(drop=True)
    print(f"  Total Likert records: {len(df_all)}")
    print(f"  Participants with data: {sorted(df_all['PID'].unique().tolist())}")

    # ── 4. Identify missing participants ─────────────────────────────────
    print("\n[4/6] Identifying missing data...")
    all_pids = set(f"P{i:02d}" for i in range(1, 10))
    present_pids = set(df_all['PID'].unique())
    missing_pids = all_pids - present_pids

    if missing_pids:
        for mp in sorted(missing_pids):
            print(f"  ✗ {mp} ({PID_TO_NAME.get(mp, '?')}) — NO questionnaire data at all")

    # Check per-session completeness
    for pid in sorted(present_pids):
        sessions = df_all[df_all['PID'] == pid]['Session'].tolist()
        if 1 not in sessions:
            print(f"  ✗ {pid} — missing S1 questionnaire")
        if 2 not in sessions:
            if pid == "P04":
                print(f"  ✗ {pid} (MRR) — missing S2 questionnaire (dropped out, no S2 session)")
            else:
                print(f"  ✗ {pid} ({PID_TO_NAME.get(pid, '?')}) — missing S2 questionnaire")

    # Check for missing values within records
    print("\n  Checking for missing Likert values within records...")
    for _, row in df_all.iterrows():
        missing_qs = [q for q in Q_CODES if pd.isna(row[q])]
        if missing_qs:
            print(f"  ⚠ {row['PID']} S{row['Session']}: missing {', '.join(missing_qs)}")

    # ── 5. Impute missing values & compute subscale scores ───────────────
    print("\n[5/6] Imputing missing values & computing subscale scores...")

    # Impute missing Likert items with participant's row median
    for idx, row in df_all.iterrows():
        likert_vals = row[Q_CODES].values.astype(float)
        nan_mask = np.isnan(likert_vals)
        if nan_mask.any():
            med = np.nanmedian(likert_vals)
            for i, is_nan in enumerate(nan_mask):
                if is_nan:
                    df_all.at[idx, Q_CODES[i]] = med
                    print(f"  → {row['PID']} S{row['Session']} {Q_CODES[i]}: imputed with row median = {med}")

    # Compute subscale means
    for subscale, items in SUBSCALE_MAP.items():
        df_all[subscale] = df_all[items].mean(axis=1)

    # Overall score
    df_all['Overall'] = df_all[Q_CODES].mean(axis=1)

    print("  ✓ Subscale scores computed: " + ", ".join(SUBSCALE_MAP.keys()) + ", Overall")

    # ── 6. Save cleaned data ─────────────────────────────────────────────
    print("\n[6/6] Saving cleaned data...")

    # Main Likert + subscales CSV
    out_path = os.path.join(OUT_DIR, "questionnaire_clean.csv")
    df_all.to_csv(out_path, index=False, encoding='utf-8-sig')
    print(f"  ✓ Saved: {out_path}")
    print(f"    Columns: {', '.join(df_all.columns.tolist())}")

    # Open-ended responses CSV (S2 only)
    open_rows = []
    for source_label, df, session, group in [
        ("S2G1", s2g1_raw, 2, "G1"),
        ("S2G2", s2g2_raw, 2, "G2"),
    ]:
        oe = extract_open(df)
        if oe.empty:
            continue
        for _, row in oe.iterrows():
            name = row['Name']
            pid = NAME_TO_PID.get(name, "UNKNOWN")
            if pid == "UNKNOWN":
                continue
            rec = {'PID': pid, 'Name': name, 'Group': group, 'Session': 2}
            for oq in OPEN_CODES:
                rec[oq] = row[oq]
            open_rows.append(rec)

    if open_rows:
        df_open = pd.DataFrame(open_rows).sort_values('PID').reset_index(drop=True)
        open_path = os.path.join(OUT_DIR, "questionnaire_open_ended.csv")
        df_open.to_csv(open_path, index=False, encoding='utf-8-sig')
        print(f"  ✓ Saved: {open_path}")

    # ── Summary ──────────────────────────────────────────────────────────
    print("\n" + "=" * 60)
    print("PREPROCESSING SUMMARY")
    print("=" * 60)
    print(f"Total participants: 9 (P01–P09)")
    print(f"Participants with questionnaire data: {len(present_pids)}")
    print(f"Missing entirely: {sorted(missing_pids)} ({', '.join(PID_TO_NAME.get(p, '?') for p in sorted(missing_pids))})")
    print(f"\nRecords per session:")
    for sess in [1, 2]:
        n = len(df_all[df_all['Session'] == sess])
        pids = sorted(df_all[df_all['Session'] == sess]['PID'].tolist())
        print(f"  S{sess}: {n} records — {pids}")

    print(f"\nSubscale statistics (all records combined):")
    stats_cols = list(SUBSCALE_MAP.keys()) + ['Overall']
    desc = df_all[stats_cols].describe().round(2)
    print(desc.to_string())

    print("\n✓ Preprocessing complete!")
    return df_all


if __name__ == '__main__':
    df = main()
