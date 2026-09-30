# Null / Surrogate Baseline Hypothesis Testing Report (v1)

> **Protocol**: `pixel-sensorium.null-baselines-stats.v1`  
> **Evaluated Window**: Day 1–7 Discovery (3147 frames)  
> **Number of Monte Carlo Surrogates per Model**: `100`  
> **Verdict**: **QUALIFIED NULL REJECTION** — Trajectory strongly deviates from memoryless random noise, but 1-step Markov dynamics account for empirical transition entropy ($p > 0.05$).

---

## 1. Hypothesis Testing Matrix

| Metric | Empirical Trajectory | Model 0 (Permutation) | Model 1 (1st-Order Markov) | Model 2 (Circadian) | Model 3 (Block Bootstrap) | Model 4 (2nd-Order Markov) |
| :--- | :---: | :---: | :---: | :---: | :---: | :---: |
| **Transition Entropy Rate (bits)** | **`0.419`** | `2.492 (z=-398.73)` | `0.405 (z=0.24)` | `2.144 (z=-150.25)` | `0.588 (z=-21.35)` | `0.396 (z=0.33)` |
| **Self-transition Ratio** | **`91.6%`** | `29.0% (z=94.53)` | `91.5% (z=0.05)` | `44.2% (z=81.53)` | `89.4% (z=20.0)` | `91.6% (z=-0.04)` |
| **Recurring Trigram Motifs (≥3)** | **`57`** | `180.3 (z=-17.94)` | `63.3 (z=-0.63)` | `151.8 (z=-15.22)` | `68.2 (z=-4.52)` | `64.1 (z=-0.52)` |
| **Null Rejection ($p < 0.01$)** | — | **REJECTED ($p < 0.001$)** | **NOT REJECTED ($p > 0.05$)** | **REJECTED ($p < 0.001$)** | **REJECTED ($p < 0.001$)** | **NOT REJECTED ($p > 0.05$)** |

---

## 2. Statistical Findings

1. **Model 0 (I.I.D. Permutation Null, $z = -398.73$)**:
   - Empirical entropy rate (`0.419`) is drastically lower than the shuffled baseline (`2.492`). Rejection confirms trajectory is **NOT memoryless I.I.D. noise**.
2. **Model 1 (1st-Order Markov Null, $z = 0.24$)**:
   - Markov null NOT rejected ($p > 0.05$). Preserving empirical 1-step transitions sufficiently explains short-range transition entropy and motif rates.
3. **Model 2 (Circadian Phase Null, $z = -150.25$)**:
   - Diurnal phase preservation alone does not account for continuous trajectory coherence.
4. **Model 3 (Block Bootstrap Null, $z = -21.35$)**:
   - Evaluates whether macro-scale sequence ordering persists beyond local 30-step (~15 min) blocks.
5. **Model 4 (2nd-Order Markov Null, $z = 0.33$)**:
   - Evaluates whether 2-step history conditioning accounts for multi-step pattern formation.

**Status**: **HONEST QUALIFIED FINDING: TRAJECTORY SHOWS STRONG TEMPORAL ORDER BUT 1ST-ORDER MARKOVIAN TRANSITIONS EXPLAIN SHORT-RANGE DYNAMICS**
