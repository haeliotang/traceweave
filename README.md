# Traceform

> **Objective**:  
> **"To determine whether persistent latent structure can be discovered from continuous natural human trajectories without predefined behavioral semantics."**  
> **「检验在没有预定义行为语义的条件下，能否从连续自然人类轨迹中发现持久的潜在结构。」**

---

## 1. Scientific Motivation

Traceform is a longitudinal physical trajectory observation and structure discovery system.

Instead of pre-injecting behavioral categories (*home, work, sleep, commute, exercise, resting*), Traceform observes low-level multimodal physical sensor data in the wild (motion dynamics, barometric pressure, light levels, spatial geocells, connectivity fingerprints, battery charge state, acoustic features) to evaluate:

1. **Recurrence**: Which states recur naturally over days and weeks?
2. **Transition Stability**: Which transition paths form stable dynamic regimes?
3. **Periodicity & Motifs**: Which sub-sequences exhibit periodic structure?
4. **Out-of-Sample Persistence**: Which patterns persist across temporal partitions (e.g. Day 1–7 discovery vs. Day 8–14 validation)?
5. **Noise vs. Structure**: Which patterns differentiate from null / surrogate baseline models?

The fundamental research question is not *"What is this person doing right now?"*, but rather:  
**"Can a machine discover an individual's persistent life structure purely from unlabelled physical trajectory dynamics?"**

```text
Raw Trajectory  --->  Structure Discovery  --->  Temporal Stability  --->  Semantic Inference
                                                                            (Final Phase Only)
```

---

## 2. System Architecture

Traceform operates across two coordinated environments:

1. **Physical Observation & Supervision Stack (GrapheneOS / Pixel 6a)**:
   - **Physical Observation Stack v1**: Samples 6 physical modalities (`motion`, `place`, `environment`, `connectivity`, `device_relation`, `audio_context`) and appends immutable records to append-only `.jsonl` ledgers.
   - **Physical Runner Supervision v1**: Out-of-band monitoring anchored to Linux kernel boot epoch (`/proc/sys/kernel/random/boot_id`) and Android `JobScheduler` (Job ID `7702`), guaranteeing uninterrupted logging without restart storms.
2. **Analysis & Structure Discovery Stack (Antigravity Desktop)**:
   - Houses corpus freezing, statistical auditing, state representation, null hypothesis testing, and latent graph extraction.

---

## 3. Current Phase: 14-Day Corpus Freeze & Audit (v1)

The initial continuous 14-day natural trajectory corpus has been frozen and audited:

- **Corpus Identity**: `natural_trajectory_14d_v1`
- **Corpus Window**: `2026-09-02T22:48:12.694637+00:00` → `2026-09-16T22:48:12.694637+00:00` (14.0 days)
- **Observations Count (in-window)**: `5,961` (13,008 raw ledger total)
- **Transitions Count (in-window)**: `791` (1,342 raw ledger total)
- **Episodes Count (in-window)**: `1,433` (2,474 raw ledger total)
- **Supervision Ticks**: `464` in-window (100.0% healthy, 0 runner crashes, 0 reboots)
- **Data Integrity**: 0 timestamp duplicates, 0 non-monotonic steps, 0 invalid sensor values.
- **Audit Verdict**: **`usable_for_structure_discovery: true`**

Full audit report, metrics, and manifest:
- [`artifacts/trajectory_corpus_v1/manifest.json`](artifacts/trajectory_corpus_v1/manifest.json)
- [`artifacts/trajectory_corpus_v1/corpus_stats.json`](artifacts/trajectory_corpus_v1/corpus_stats.json)
- [`artifacts/trajectory_corpus_v1/CORPUS_AUDIT_v1.md`](artifacts/trajectory_corpus_v1/CORPUS_AUDIT_v1.md)

---

## 4. Reproducible Audit Execution

To reproduce the exact audit results, run:

```bash
python3 scripts/audit_trajectory_corpus.py
```

The script computes deterministic cryptographic hashes over the corpus and outputs identical statistical tables.

---

## 5. Research Roadmap

```text
Corpus Freeze + Audit v1  [Completed]
          │
          ▼
Representation Freeze v1   (Continuous feature normalization & state quantization)
          │
          ▼
Null / Surrogate Baselines (Permutation, phase-randomized, and Markov baselines)
          │
          ▼
Trajectory Structure Discovery v1 (Recurrence matrix, regime shifts, motif extraction)
          │
          ▼
7d → 7d Stability Test    (Day 1–7 Discovery vs. Day 8–14 Validation)
          │
          ▼
Candidate Semantic Inference (Optional post-hoc mapping to human semantics)
```

---

## 6. Research Disciplines

- **No Semantic Pre-injection**: Abstract state tokens ($S_1, S_2, \dots$) only.
- **Raw Evidence Immutability**: Raw ledgers in `data/raw/` are immutable. No retroactive filtering or threshold tuning.
- **Null Comparison**: No structural pattern is accepted without comparison against surrogate data.
- **Out-of-Sample Verification**: All discovered structures must reproduce across independent time partitions.
