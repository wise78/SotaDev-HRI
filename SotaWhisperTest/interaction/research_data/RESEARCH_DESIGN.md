# Research Design: Reciprocity in Human-Robot Interaction
## Effect of Robot Memory & Social Reciprocity on User Trust, Self-Disclosure, and Emotional Connection

**Platform:** Vstone Sota Social Robot  
**System:** WhisperInteraction (Whisper STT + Ollama LLM + Face Recognition + UserMemory)  
**Date:** March 2026  

---

## 1. Research Question

> **Does a social robot that remembers past interactions (reciprocal behavior) increase user trust, self-disclosure, and emotional connection compared to a robot that treats every encounter as new?**

### Hypotheses

| # | Hypothesis | Measurement |
|---|-----------|-------------|
| H1 | Participants in the reciprocity condition (G1-S2) will report **higher trust** than the control condition (G2-S2) | Section A mean score |
| H2 | Participants in the reciprocity condition will demonstrate **greater self-disclosure** | Section B mean score + conversation turn count |
| H3 | Participants in the reciprocity condition will report **stronger emotional connection** | Section C Q12, Q14, Q15 scores |
| H4 | Participants in the reciprocity condition will perceive the robot as **more natural and human-like** | Section D Q18-Q20 scores |
| H5 | Participants in the reciprocity condition will show **higher intention to continue** interacting | Section E mean score |

---

## 2. Definition of Reciprocity

**Reciprocity** (timbal balik) in this study is operationalized as:

> The robot's ability to **remember and reference** the user's personal information (name, origin, conversation topics) from previous interactions, creating a sense of mutual social investment.

### Reciprocity Conditions

| Code | Condition | Robot Behavior |
|------|-----------|---------------|
| **WR** (With Reciprocity) | Memory ENABLED | Sota remembers name, origin, past conversation topics. Greets by name ("Welcome back, Bijak!"). References shared history. Social state progresses (stranger → acquaintance → friendly → close). Conversation style becomes more casual over time. |
| **WOR** (Without Reciprocity) | Memory DISABLED | Sota treats every encounter as first meeting. Asks name again. No memory of past topics. Always uses formal/polite stranger greeting. No social state progression. |

### What Constitutes Reciprocity in Practice

| Reciprocity Signal | WR (With) | WOR (Without) |
|---|---|---|
| Name recognition | "Welcome back, Bijak!" | "What's your name?" |
| Origin awareness | "My friend from Indonesia!" | "Where are you from?" |
| Conversation memory | "Last time we talked about interesting things!" | (no reference) |
| Social progression | "Hey Bijak!" (casual, close friend) | "Hello! I'm Sota." (formal, stranger) |
| Interaction count | "We've met 3 times now!" | (no count) |
| Cultural awareness | Uses Indonesian cultural references naturally | Generic conversation |
| Name-based recognition | Asks "Have we met before?" if face fails | Always treats as new |

---

## 3. Experimental Design

### Design Type
**2-Session Mixed Design**
- **Between-subjects** factor: Group (G1 Treatment vs G2 Control) — compared at Session 2
- **Within-subjects** factor: Session (S1 vs S2) — measured within each group

### Group Assignment

```
                    SESSION 1 (Day 1)              SESSION 2 (Day 2+)
                  ┌─────────────────────┐        ┌──────────────────────────┐
G1 (Treatment)    │ NOVICE + WOR        │   →    │ REMEMBER + WR            │
n = 15-26         │ First meeting       │        │ Memory ON                │
                  │ No memory           │        │ Reciprocity ON           │
                  │ Robot = stranger     │        │ Robot remembers user     │
                  └─────────────────────┘        └──────────────────────────┘

                  ┌─────────────────────┐        ┌──────────────────────────┐
G2 (Control)      │ NOVICE + WOR        │   →    │ NO-REMEMBER + WOR        │
n = 15-26         │ First meeting       │        │ Memory OFF               │
                  │ No memory           │        │ No reciprocity           │
                  │ Robot = stranger     │        │ Robot = stranger again   │
                  └─────────────────────┘        └──────────────────────────┘
```

### Why Session 1 is Identical for All
Session 1 serves as the **baseline**. All participants have the same first encounter so we can:
1. Verify that G1 and G2 start at equivalent levels
2. Measure **change** from S1 to S2 within each group
3. Attribute differences in S2 to the manipulation (reciprocity), not pre-existing differences

### Session Timing
- **S1 to S2 gap:** Minimum 1 day, maximum 7 days
- Reasoning: Long enough for a "returning visit" feel, short enough to maintain engagement

---

## 4. Participants

### Sample Size
- **Target:** 26 per group (52 total) for medium effect size (Cohen's d = 0.5) with power = 0.80
- **Pilot:** 10-15 per group (20-30 total) is acceptable

### Inclusion Criteria
- Age 18+
- Can communicate in English or Japanese
- No prior interaction with Sota in research context (Q33 check)

### Exclusion Criteria
- Technical failure during session (>2 minutes downtime)
- Participant unable to complete both sessions

### Random Assignment
- Use randomization table or coin flip to assign G1/G2
- Record assignment in GUI before Session 1 begins

---

## 5. Procedure

### Pre-Experiment Setup
1. Start Whisper server on laptop (`python whisper_server.py`)
2. Start Ollama on laptop (llama3.2:3b model loaded)
3. Open interaction GUI (`python interaction_gui.py`)
4. Verify robot connection (SSH to Edison board)

### Session 1 Protocol (All Participants)

| Step | Action | Duration |
|------|--------|----------|
| 1 | Researcher sets up GUI: Participant ID, Group, Session=1 | 1 min |
| 2 | Brief explanation to participant: "You will have a conversation with Sota" | 2 min |
| 3 | Researcher clicks START SESSION in GUI | — |
| 4 | On robot: `./start_novice.sh <laptop_ip> P01 G1` | — |
| 5 | Participant sits in front of Sota (50-80cm distance) | — |
| 6 | **Interaction proceeds naturally** (Sota detects face → greets → asks name → asks origin → free conversation → closing) | 3-10 min |
| 7 | Researcher clicks END SESSION when conversation ends | — |
| 8 | Participant fills out **Questionnaire Sections A-G** | 5-10 min |
| 9 | Researcher fills out **Section H** (observer notes) | 2 min |

### Session 2 Protocol

**For G1 (Treatment — REMEMBER + WR):**

| Step | Action |
|------|--------|
| 1 | Researcher sets GUI: same Participant ID, Group=G1, Session=2 |
| 2 | On robot: `./start_remember.sh <laptop_ip> P01` |
| 3 | Interaction: Sota detects face → tries name-based recognition → "Welcome back, [name]!" → references past conversation → casual friendly style |
| 4 | After session: Questionnaire **Sections A-F** (no demographics) + Section H |

**For G2 (Control — NO-REMEMBER + WOR):**

| Step | Action |
|------|--------|
| 1 | Researcher sets GUI: same Participant ID, Group=G2, Session=2 |
| 2 | On robot: `./start_no_remember.sh <laptop_ip> P03` |
| 3 | Interaction: Sota detects face → treats as stranger → "Hello! I'm Sota. What's your name?" → no memory |
| 4 | After session: Questionnaire **Sections A-F** (no demographics) + Section H |

### Important Researcher Notes
- Do NOT tell participants whether the robot will remember them or not
- Keep a neutral demeanor during both conditions
- If participant asks "Does Sota remember me?", respond: "Just interact naturally"
- Note any spontaneous comments about memory/recognition in Section H

---

## 6. Questionnaire — Complete Detail

### Rating Scale
```
1 = Strongly Disagree (Sangat Tidak Setuju)
2 = Disagree (Tidak Setuju)
3 = Somewhat Disagree (Agak Tidak Setuju)
4 = Neutral (Netral)
5 = Somewhat Agree (Agak Setuju)
6 = Agree (Setuju)
7 = Strongly Agree (Sangat Setuju)
```

### SECTION A: TRUST AND SAFETY (5 items)
**Construct measured:** User's trust in the robot and feeling of psychological safety.  
**Relevance to reciprocity:** A robot that remembers you should feel more trustworthy.

| Q# | Statement | What It Measures |
|----|-----------|------------------|
| Q1 | "I felt safe talking with Sota" | Psychological safety — comfortable in this interaction? |
| Q2 | "Sota seemed trustworthy" | Perceived trustworthiness of the robot |
| Q3 | "I would trust Sota with personal information" | Willingness to disclose — trust for private info |
| Q4 | "Sota seemed capable and competent" | Perceived competence — robot seems smart/able? |
| Q5 | "I felt Sota understood my concerns" | Perceived understanding — robot "gets" me? |

**Score calculation:** Mean of Q1-Q5 per participant  
**Expected result:** G1-S2 mean > G2-S2 mean

---

### SECTION B: DISCLOSURE AND OPENNESS (5 items)
**Construct measured:** Self-disclosure willingness and comfort.  
**Relevance to reciprocity:** This is the PRIMARY dependent variable. Reciprocity theory predicts that if the robot "gives" (memory/recognition), the user "gives back" (more personal sharing).

| Q# | Statement | What It Measures |
|----|-----------|------------------|
| Q6 | "I shared personal information willingly" | Actual self-disclosure behavior |
| Q7 | "I felt comfortable being honest with Sota" | Honesty comfort level |
| Q8 | "I would share more sensitive topics in future" | Future disclosure intention |
| Q9 | "I trust Sota with my feelings" | Emotional trust — deeper than informational trust |
| Q10 | "Sota made me feel heard and understood" | Felt heard — prerequisite for deeper disclosure |

**Score calculation:** Mean of Q6-Q10 per participant  
**Expected result:** Largest difference between G1-S2 and G2-S2

---

### SECTION C: EMPATHY AND VULNERABILITY (5 items)
**Construct measured:** Emotional connection and prosocial behavior toward the robot.  
**Relevance to reciprocity:** If robot "cares" (remembers), does user "care back"?

| Q# | Statement | What It Measures |
|----|-----------|------------------|
| Q11 | "Sota seemed vulnerable or uncertain" | Perceived vulnerability of robot |
| Q12 | "I felt protective or caring toward Sota" | **Prosocial reciprocity** — urge to protect |
| Q13 | "Sota appeared to need support or reassurance" | Perceived neediness |
| Q14 | "I wanted to help Sota feel better" | **Desire to help** — reciprocal caring |
| Q15 | "I felt emotionally connected to Sota" | **Emotional bond** — core reciprocity outcome |

**Score calculation:** Mean of Q11-Q15; also analyze Q12, Q14, Q15 separately  
**Expected result:** Q12, Q14, Q15 higher in G1-S2

---

### SECTION D: PERCEIVED ROBOT CHARACTERISTICS (5 items)
**Construct measured:** How human-like and natural the robot seems.  
**Relevance to reciprocity:** A robot that remembers may seem "more alive."

| Q# | Statement | What It Measures |
|----|-----------|------------------|
| Q16 | "Sota seemed confident in this interaction" | Perceived confidence |
| Q17 | "Sota seemed distressed or struggling" | Perceived distress (note: reverse-coded relative to Q16) |
| Q18 | "Sota seemed human-like in its responses" | Anthropomorphism — degree of human-likeness |
| Q19 | "I found Sota's behavior natural and authentic" | Perceived naturalness |
| Q20 | "Sota's emotions seemed genuine" | Perceived genuineness of robot's affect |

**Score calculation:** Mean of Q16, Q18, Q19, Q20 (Q17 analyzed separately as it measures distress)  
**Expected result:** G1-S2 higher on Q18-Q20

---

### SECTION E: INTENTION TO CONTINUE (3 items)
**Construct measured:** Willingness to interact again.  
**Relevance to reciprocity:** Would users return to a robot that remembers them?

| Q# | Statement | What It Measures |
|----|-----------|------------------|
| Q21 | "I would like to interact with Sota again" | Re-interaction intention |
| Q22 | "I would recommend Sota to others" | Recommendation willingness (NPS-like) |
| Q23 | "This interaction was valuable/meaningful" | Perceived value of the experience |

**Score calculation:** Mean of Q21-Q23 per participant  
**Expected result:** G1-S2 significantly higher than G2-S2

---

### SECTION F: OPEN-ENDED QUESTIONS (5 items)
**Data type:** Qualitative (free text)  
**Collected:** After BOTH Session 1 and Session 2

| Q# | Question | What to Look For in Answers |
|----|----------|----------------------------|
| Q24 | "What did you notice most about Sota's behavior or appearance?" | G1-S2: Do they mention "Sota remembered me"? This is a **manipulation check** — confirms reciprocity was perceived. |
| Q25 | "How would you describe Sota's emotional or mental state?" | G1-S2: "happy", "friendly", "warm" vs G2-S2: "neutral", "robotic"? |
| Q26 | "Did Sota's behavior make you more/less comfortable sharing personal information? Why?" | **KEY QUESTION for reciprocity.** Direct evidence of whether memory affects disclosure. |
| Q27 | "Did you feel any desire to help or support Sota? Please explain." | Measures **prosocial reciprocity** — did the robot's "kindness" (remembering) trigger helping behavior? |
| Q28 | "Is there anything else you would like to tell us about your experience?" | Catch-all for unexpected findings. Often produces the best quotes for papers. |

**Analysis method:** Thematic coding
1. Read all responses
2. Identify recurring themes (e.g., "memory", "recognized", "felt special", "like talking to a friend")
3. Count frequency per group
4. Report as supporting evidence for quantitative findings

---

### SECTION G: DEMOGRAPHICS (5 items)
**Collected:** Once, after Session 1 only

| Q# | Question | Purpose |
|----|----------|---------|
| Q29 | Age (years) | Control variable — younger people may be more comfortable with tech |
| Q32 | Familiarity with AI/robotics (Not at all / Somewhat / Very familiar) | **Confounding variable** — tech-savvy people may react differently |
| Q33 | "Have you seen Sota before this study?" (Yes / No) | **Manipulation check** — if Yes, the NOVICE condition is compromised |

**Usage in analysis:**
- Verify G1 and G2 groups are balanced on demographics (chi-square / t-test)
- Use Q31/Q32 as covariates in ANCOVA if groups differ
- Exclude participants who answer Yes to Q33 (if needed)

---

### SECTION H: OBSERVER NOTES (Researcher fills during session)
**Collected:** During BOTH sessions

| Field | What to Record | Examples |
|-------|---------------|----------|
| Observer notes | General observations | "Participant seemed surprised when Sota said 'Welcome back!'" |
| Behavioral observations | Body language, expressions, hesitations | "Smiled broadly in S2. Leaned forward. More animated gestures." |
| Technical issues | Any problems | "Whisper failed to recognize name twice. Robot said wrong name initially." |

---

## 7. Data Collection Summary

### Automatic Data (System-collected)

| Data | Source | File Location |
|------|--------|---------------|
| Session metadata | GUI DataLogger | `research_data/session_log.csv` |
| Conversation transcript | Per-session | `research_data/logs/P01_G1_S1_NOVICE_20260301_1020.txt` |
| Duration (seconds) | GUI timer | CSV: `duration_sec` |
| Total turns | Auto-count | CSV: `total_turns` |
| User turns | Auto-count | CSV: `user_turns` |
| Robot turns | Auto-count | CSV: `robot_turns` |
| Who ended conversation | Auto-detect | CSV: `user_initiated_goodbye` |
| Peak VAD level | Audio energy | CSV: `peak_vad_level` |
| User profile (name, origin, social state, memory summary) | UserMemory | `data/user_profiles.json` |

### Manual Data (Researcher-collected)

| Data | When | How |
|------|------|-----|
| Questionnaire Q1-Q23 (Likert scores) | After each session | Paper or digital form |
| Open-ended Q24-Q28 | After each session | Written responses |
| Demographics Q29-Q33 | After Session 1 only | Written responses |
| Observer notes (Section H) | During each session | Researcher writes in GUI Notes field or paper |

---

## 8. Variables Summary

| Type | Variable | Operationalization |
|------|----------|-------------------|
| **Independent Variable (IV)** | Reciprocity condition | WR (G1-S2: Sota remembers) vs WOR (G2-S2: Sota forgets) |
| **Dependent Variables (DV)** | | |
| | Trust | Section A mean (Q1-Q5) |
| | Self-Disclosure | Section B mean (Q6-Q10) |
| | Emotional Connection | Section C: Q12, Q14, Q15 |
| | Perceived Naturalness | Section D: Q18, Q19, Q20 |
| | Re-interaction Intention | Section E mean (Q21-Q23) |
| **Behavioral DVs** | | |
| | Conversation duration | CSV: `duration_sec` |
| | Turn count | CSV: `total_turns`, `user_turns` |
| | Who ended conversation | CSV: `user_initiated_goodbye` |
| **Control Variables** | | |
| | Age | Q29 |
| | Gender | Q30 |
| | Robot experience | Q31 |
| | AI familiarity | Q32 |
| **Manipulation Check** | | |
| | Perceived memory | Q24-Q25 (do G1-S2 mention "Sota remembered me"?) |
| | Prior Sota exposure | Q33 |

---

## 9. Analysis Plan

### Primary Analysis: G1-S2 vs G2-S2 (Between-Subjects)
- **Test:** Independent samples t-test (or Mann-Whitney U if non-normal)
- **Variables:** Mean scores of Section A, B, C, D, E
- **Significance level:** α = 0.05
- **Effect size:** Cohen's d

### Secondary Analysis: S1 vs S2 Within Each Group
- **Test:** Paired samples t-test
- **Purpose:** Measure change over time
- **Expected:** G1 shows significant increase S1→S2; G2 shows no significant change

### Behavioral Data Analysis
- **Test:** Independent t-test on duration, turn count
- **Expected:** G1-S2 longer interactions, more turns

### Covariates
- If G1 and G2 differ on demographics (Q29-Q32), use ANCOVA with demographics as covariates

### Qualitative Analysis
- Thematic analysis of Q24-Q28
- Inter-rater reliability: 2 coders independently code responses
- Report themes with frequency counts and representative quotes

---

## 10. Ethical Considerations

- Informed consent before Session 1
- Participants may withdraw at any time
- Data anonymized (participant IDs only, no real names in published data)
- Face data stored locally on robot, not transmitted externally
- Debrief after Session 2: explain the reciprocity manipulation

---

## 11. Technical Setup Checklist

### Before Each Session
- [ ] Whisper server running (`python whisper_server.py`)
- [ ] Ollama running with model loaded (`ollama serve`)
- [ ] GUI open (`python interaction_gui.py`)
- [ ] Robot powered on and SSH accessible
- [ ] Camera angle checked (face visible at 50-80cm)
- [ ] Sound volume appropriate for room

### For G1-S2 (REMEMBER) Sessions
- [ ] Verify `data/user_profiles.json` contains participant's profile from S1
- [ ] Do NOT use `--no-memory` flag
- [ ] Do NOT run `reset_memory.sh` before session

### For G2-S2 (NO-REMEMBER) Sessions
- [ ] `--no-memory` flag will be set automatically by `start_no_remember.sh`
- [ ] Robot will ignore stored profiles even if they exist

---

## 12. File Reference

| File | Purpose |
|------|---------|
| `start_novice.sh` | Launch Session 1 (all participants) |
| `start_remember.sh` | Launch G1 Session 2 (memory ON) |
| `start_no_remember.sh` | Launch G2 Session 2 (memory OFF) |
| `interaction_gui.py` | Research GUI: session setup, live monitor, data logging |
| `research_data/session_log.csv` | All session metadata |
| `research_data/logs/` | Per-session conversation transcripts |
| `data/user_profiles.json` | User memory profiles (name, origin, social state, summaries) |
| `questionnaire.txt` | Paper questionnaire template |
| `reset_memory.sh` | Clear all user profiles (use with caution!) |

---

*Document generated: March 4, 2026*  
*System: Sota WhisperInteraction v3*
