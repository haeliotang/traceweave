# On-device instruct models, 1–4B, llama.cpp — shortlist for Pixel 6a (Tensor G1, 8 GB)

Research date: 2026-10-31. Compiled by a research subagent; numbers are cited from vendor/shared-harness cards, not measured on this device. Baseline to beat: **Qwen2.5-1.5B-Instruct Q4_0, 1.07 GB**, 26/50 on the mixed suite
(EN 9/10, ZH 6/10, arithmetic 9/10, strict JSON 0/10, list format 2/10), median 2.7 s/case,
app refuses prompts > ~500 tokens.

**Read this first (it changes the model decision):** the 0/10 strict-JSON score is mostly a *decoding* problem,
not a model-capability problem. llama.cpp has grammar-constrained decoding (GBNF, `llama-grammar.cpp`) and
JSON-Schema → GBNF conversion; every model below inherits it for free. Forcing a JSON grammar typically takes a
Q4 small model from "invalid JSON" to "valid but occasionally wrong" in one change, with zero latency cost beyond
the tokens you no longer waste on preambles. Do that **before/alongside** swapping weights. The 500-token app cap
is also self-imposed — every candidate here has ≥32K context — and few-shot JSON examples are exactly the thing
that cap is cutting off.

## Ranked shortlist (improvement per byte at similar size)

| # | Model | Params | License | Q4 size | IFEval¹ | IFBench¹ | Multi-IF¹ | BFCL v4¹ | Chinese | Thinking default |
|---|-------|--------|---------|---------|---------|----------|-----------|----------|---------|------------------|
| 1 | **MiniCPM5-2B** | 2B | Apache-2.0 | **1.56 GB** (Q4_K_M) | 86.7 | 66.3 | 71.8 | 66.6 | bi-lingual zh/en (no public C-Eval found) — *uncertain* | hybrid, toggle |
| 2 | **Granite-4.2-3B** | 3B | Apache-2.0 | 2.24 GB (Q4_K_M) | **93.7** | **73.0** | **75.9** | 52.2 | listed, but weak non-EN knowledge (MMLU-ProX-lite 27.8) | **thinking ON** by default |
| 3 | **Qwen3.5-2B** | 2B (GDN hybrid) | Apache-2.0 | **1.22 GB** (Q4_0) | 77.5 | 59.0 | 57.1 | 43.6 | **best**: C-Eval 65.2 (non-think) / 73.2 (think) | non-thinking |
| 4 | **LFM2.5-1.2B-Instruct** | 1.2B | LFM Open License 1.0 | **0.70 GB** (Q4_0) | 86.2² | 47.3² | 61.0² | 49.1² | zh supported, unbenchmarked | non-thinking |
| 5 | **Qwen3-4B-Instruct-2507** | 4B | Apache-2.0 | 2.38 GB (Q4_0) | 83.4³ | n/p | n/p | 39.9³ | strong (C-Eval ~80) | non-thinking |

¹ IFEval / IFBench / Multi-IF / BFCL-v4 columns are all from **one shared harness** in the
[MiniCPM5-2B model card](https://huggingface.co/openbmb/MiniCPM5-2B) so they are mutually comparable —
but the publisher is one of the contestants, and these numbers will not match vendor-reported ones.
Do not compare across tables.
² From [LiquidAI's own card](https://huggingface.co/LiquidAI/LFM2.5-1.2B-Instruct) (different harness; IFEval/Multi-IF averaged strict+loose).
³ From [Qwen's Qwen3.5-2B/4B cards](https://huggingface.co/Qwen/Qwen3.5-4B) (Qwen3-4B-Instruct-2507 comparison column), i.e. Qwen's own harness.
n/p = not published.

### Why this order

1. **MiniCPM5-2B** — the only candidate that is simultaneously ~1.5 GB, Apache-2.0, **strong on all four
   instruction/agent metrics** (86.7 / 66.3 / 71.8 / **66.6 BFCL** — best tool-calling of any sub-2B here),
   131K context, and bilingual zh/en by design. Q4_K_M is 1.56 GB vs your current 1.07 GB, i.e. ~+0.5 GB RAM for
   a big IF jump. Emits XML-style tool calls (SGLang parser documented; in llama.cpp you'd parse yourself or just
   use GBNF JSON). *Risk:* post-trained with heavy "deep-thinking" SFT, so expect verbosity — verify terse JSON under
   grammar. [Card](https://huggingface.co/openbmb/MiniCPM5-2B) · [GGUF](https://huggingface.co/openbmb/MiniCPM5-2B-GGUF)
2. **Granite-4.2-3B** — highest instruction-following numbers in the comparable table by a wide margin
   (IFEval 93.7, IFBench 73.0, Multi-IF 75.9) and IBM explicitly RL-trains on **structured output** and tool use
   (own card: BFCL-v4 52.41, τ³-bench 45.78, MMLU-Pro 67.84). Apache-2.0, 12 tested languages incl. Chinese, 128K ctx.
   *Cost:* 2.24 GB and 3B params → roughly **2× your current latency** (~5–6 s/case estimated) and more RAM pressure
   on 8 GB. Thinking (`<think>`) is **on by default**; you must pass `enable_thinking=False` (non-thinking ends the
   prompt with `<think></think>`) or the grammar will fight the reasoning trace. Best pick if you can trade latency.
   [Card](https://huggingface.co/ibm-granite/granite-4.2-3b) · [GGUF](https://huggingface.co/ibm-granite/granite-4.2-3b-GGUF)
3. **Qwen3.5-2B** — the safest "same family, newer, still 1.2 GB" upgrade and the **best Chinese** of the set
   (C-Eval 65.2 non-thinking vs 61.0 for Qwen3-1.7B; MMLU-Pro 55.3 vs 40.2). Non-thinking by default (good).
   But **do not expect an IF win**: Qwen's own table puts non-thinking IFEval at **61.2**, *below* Qwen3-1.7B's 68.2;
   the 78.6 figure is thinking mode only, and Qwen warns the 2B "is more prone to entering thinking loops".
   Architecture is a Gated-DeltaNet hybrid — llama.cpp has `LLM_ARCH_QWEN35` and GDN kernels exist (Hexagon/OpenCL PRs),
   but **on-device CPU throughput on a Tensor G1 is unverified**; budget a day to measure before committing.
   [Card](https://huggingface.co/Qwen/Qwen3.5-2B) · [GGUF](https://huggingface.co/unsloth/Qwen3.5-2B-GGUF)
4. **LFM2.5-1.2B-Instruct** — the latency/size play: **0.70 GB**, smaller than what you ship today, IFEval 86.2,
   designed for CPU/edge (LiquidAI reports 70 tok/s decode on a Snapdragon 8 Gen 4 **CPU** with llama.cpp Q4_0, and
   NPU ports via Nexa/Qualcomm/AMD). Weakness is knowledge (MMLU-Pro 44.4) and weaker IFBench/Multi-IF than the top two.
   License is the catch: **LFM Open License 1.0** (commercial use permitted with conditions such as a revenue
   threshold and notice obligations — read it; not OSI-approved). [Card](https://huggingface.co/LiquidAI/LFM2.5-1.2B-Instruct) · [GGUF](https://huggingface.co/LiquidAI/LFM2.5-1.2B-Instruct-GGUF)
5. **Qwen3-4B-Instruct-2507** — the boring, proven non-thinking workhorse: IFEval 83.4, MMLU-Pro 69.6, Apache-2.0,
   plain Qwen3 arch that llama.cpp has supported for over a year, no thinking-mode traps. 2.38 GB / ~2.5× latency.
   Use as the quality reference point when you A/B against MiniCPM5-2B. [GGUF](https://huggingface.co/unsloth/Qwen3-4B-Instruct-2507-GGUF)

**Practical suggestion:** test **MiniCPM5-2B** and **Granite-4.2-3B** first (one "same size, big IF jump", one
"max IF, accept latency"), keep **LFM2.5-1.2B** as the fast fallback tier, and run all three with a JSON-Schema GBNF
plus a raised context cap (≥2K) and 2–3 few-shot examples.

## Not suitable (and why)

| Model | Why not |
|-------|---------|
| **Gemma 4 E2B / E4B** | Apache-2.0 now (license changed — see [Gemma 4 license](https://ai.google.dev/gemma/docs/gemma_4_license)), great multilingual (140+ langs, MMMLU 67.4), native function calling, audio+vision, 128K ctx — but **memory kills it on a 6a**: Q4 is **3.0–3.35 GB**, and the smallest official mobile QAT (`UD-Q2_K_XL`) is still **2.19 GB** because of the Per-Layer-Embedding tables (2.3B effective / 5.1B total). Third-party IFEval is **31.4 (E2B) / 44.4 (E4B)** in the shared harness — the worst of the set, consistent with thinking-by-default breaking strict formats. Revisit on a 12 GB device. [Card](https://huggingface.co/google/gemma-4-E2B-it) · [QAT GGUF](https://huggingface.co/google/gemma-4-E2B-it-qat-q4_0-gguf) |
| **Gemma 3 1B / Gemma 3n E2B** | Superseded by Gemma 4 in both quality and license (Gemma 3/3n use the older Gemma Terms with use restrictions; Gemma 4 is Apache-2.0). Gemma 3 1B is weak (IFEval 63.3, MMLU-Pro 14.0 per LiquidAI's table); Gemma 3n is 3–5 GB. No reason to start here in 2026. |
| **Llama 3.2 3B Instruct** | Llama 3.2 Community License: commercial OK but <700M MAU cap, "Built with Llama" attribution and naming rules — awkward for redistribution. And it's simply behind: 2.0 GB Q4 with IFEval ~52 in LiquidAI's table (vs 86–94 for the picks above). |
| **Phi-4-mini-instruct (3.8B)** | MIT (cleanest license of the lot) and 2.49 GB Q4_K_M, but 3.8B is slow on Tensor G1 and it is English-centric — your Chinese cases (currently 6/10) will likely regress. Not competitive on IF per byte in 2026. |
| **SmolLM3-3B** | Apache-2.0, 1.92 GB Q4_K_M, decent for its era, but English-centric and outclassed by MiniCPM5-2B at a smaller footprint. Fine as a neutral fallback, not an upgrade. |
| **FunctionGemma 270M** | Not a general model — a function-calling specialist. On Google's MobileActions it hits ~90% (as measured in [arXiv:2609.25373](https://arxiv.org/abs/2609.25373)), but the same paper shows the base model at **29.3%** on a broader 15-category mobile-action set. Useful as a *router* in front of a bigger model, not as the answer engine. License: Gemma terms (not Apache). |
| **Anything >4B, or any 3B+ at Q5/Q6** | 8 GB total with Android overhead leaves ~2.5–4 GB for the app. >2.5 GB of weights plus KV cache invites LMK kills mid-generation. |

## Runtime / platform notes (2026)

- **llama.cpp support is real for all shortlist entries.** Current master registers `LLM_ARCH_QWEN35`,
  `LLM_ARCH_GEMMA4`/`GEMMA4_ASSISTANT`, `LLM_ARCH_GRANITE`, `LLM_ARCH_LFM2`/`LFM3`, `LLM_ARCH_MINICPM`,
  `LLM_ARCH_SMOLLM3`, `LLM_ARCH_PHI3`, `LLM_ARCH_LLAMA4` ([llama-arch.cpp](https://github.com/ggml-org/llama.cpp/blob/master/src/llama-arch.cpp)).
  MiniCPM5-2B ships GGUF tagged `llama` (standard Llama arch), so it is the lowest-risk of the new entrants.
  Pin a known-good llama.cpp build per model and re-verify after upgrades.
- **GBNF:** grammar-constrained sampling is model-agnostic in llama.cpp (`llama-grammar.cpp`) and the server exposes
  JSON-Schema output. Caveat that matters here: **grammar + thinking-mode models conflict**. For Granite 4.2 /
  Gemma 4 / Qwen3.5-4B you must disable thinking via the chat template (`enable_thinking=false`) or pre-fill the
  assistant turn past `<｜end▁of▁thinking｜>`, otherwise the model tries to reason inside the JSON string. Prefer non-thinking
  models for this app.
- **Android AICore / Gemini Nano:** Google shipped **Gemma 4 (E2B/E4B) in the AICore Developer Preview (Apr 2026)**
  via ML Kit's Prompt API (`GenerativeModel`, model selection, and — rolling out — tool calling, structured output,
  system prompts, thinking mode). Gemini Nano 4 replaces it on-device later in 2026.
  ([Android Developers blog](https://developer.android.com/blog/posts/announcing-gemma-4-in-the-ai-core-developer-preview),
  [Gemini Nano](https://developer.android.com/ai/gemini-nano), [ML Kit Prompt API](https://developers.google.com/ml-kit/genai/prompt/android))
  **But:** it runs in the AICore system service, requires an AICore-enabled device plus Play-services-managed model
  download (developer preview additionally requires Google-group opt-in and beta AICore). Publicly documented
  support is Pixel 8/9/10-class and Galaxy S24/S25+, **not Pixel 6a** — *I could not find any source listing a
  Tensor G1 device as AICore-enabled, so treat the 6a as unsupported* (flagged: unverified negative). Also note it
  is never bundled in your APK, so "fully offline" holds only after the system model is downloaded.
- **LiteRT-LM** is the other Google path (`.litertlm` builds of Gemma 4 E2B/E4B, Qwen3.5-2B exist under
  `litert-community`), better NPU/GPU utilisation than llama.cpp on Pixel, but a different runtime and API surface —
  a migration, not a swap. Worth a spike only if you hit a latency wall.

## Licensing / redistribution / offline checklist

- **Apache-2.0** (MiniCPM5-2B, Granite-4.2-3B, Qwen3.5-2B, Qwen3-4B-2507, Gemma 4): bundle in APK, redistribute,
  commercial use — keep the LICENSE/NOTICE file and state changes. Cleanest for shipping.
- **LFM Open License 1.0** (LFM2.5): commercial use allowed under conditions (revenue threshold, notice); read
  before shipping, and don't assume OSI-style freedom.
- **Llama 3.2 Community License**: <700M MAU cap + "Built with Llama" attribution/naming — avoid.
- **Gemma 3 / 3n Terms**: use restrictions + pass-through obligations; Gemma 4's move to Apache-2.0 removes this.
- **Fully offline in a sideloaded APK**: yes for every GGUF candidate (weights in APK assets or first-run download;
  llama.cpp needs no network). AICore/Gemini Nano is the exception — model lives in the system service and is
  downloaded by Play services, and availability is device-gated.

## Numbers I did *not* verify (treat as estimates)

- Any per-token latency on a Pixel 6a. The orderings above assume throughput scales roughly inversely with active
  parameters and that your 2.7 s median is dominated by decode. Expect ~1.3× faster for LFM2.5-1.2B, ~0.75× for the
  2B models, ~0.5× for the 3B, ~0.4× for the 4B — **measure, don't trust**.
- Qwen3.5's Gated-DeltaNet speed on Tensor G1 CPU (no public Pixel benchmark found).
- MiniCPM5-2B's Chinese quality (publisher is bilingual, but no C-Eval/CMMLU figure was published).
- Exact Gemma 4 thinking-toggle behaviour in llama.cpp's GGUF chat template.

## Sources

- [Qwen3.5-2B](https://huggingface.co/Qwen/Qwen3.5-2B) · [Qwen3.5-4B](https://huggingface.co/Qwen/Qwen3.5-4B) · [Qwen3.5-2B GGUF](https://huggingface.co/unsloth/Qwen3.5-2B-GGUF)
- [MiniCPM5-2B](https://huggingface.co/openbmb/MiniCPM5-2B) · [MiniCPM5-2B GGUF](https://huggingface.co/openbmb/MiniCPM5-2B-GGUF)
- [Granite-4.2-3B](https://huggingface.co/ibm-granite/granite-4.2-3b) · [GGUF](https://huggingface.co/ibm-granite/granite-4.2-3b-GGUF)
- [LFM2.5-1.2B-Instruct](https://huggingface.co/LiquidAI/LFM2.5-1.2B-Instruct) · [GGUF](https://huggingface.co/LiquidAI/LFM2.5-1.2B-Instruct-GGUF)
- [Qwen3-4B-Instruct-2507 GGUF](https://huggingface.co/unsloth/Qwen3-4B-Instruct-2507-GGUF) · [Qwen3-1.7B](https://huggingface.co/Qwen/Qwen3-1.7B)
- [Gemma 4 E2B-it](https://huggingface.co/google/gemma-4-E2B-it) · [Gemma 4 Apache-2.0 license](https://ai.google.dev/gemma/docs/gemma_4_license) · [Gemma 4 E2B QAT q4_0 GGUF](https://huggingface.co/google/gemma-4-E2B-it-qat-q4_0-gguf)
- [Gemma releases / model cards](https://ai.google.dev/gemma/docs/releases)
- [Gemma 4 in AICore Developer Preview](https://developer.android.com/blog/posts/announcing-gemma-4-in-the-ai-core-developer-preview) · [Gemini Nano](https://developer.android.com/ai/gemini-nano) · [ML Kit Prompt API](https://developers.google.com/ml-kit/genai/prompt/android) · [AICore Developer Preview program](https://developers.google.com/ml-kit/genai/aicore-dev-preview)
- [llama.cpp supported architectures](https://github.com/ggml-org/llama.cpp/blob/master/src/llama-arch.cpp) · [llama-grammar.cpp (GBNF)](https://github.com/ggml-org/llama.cpp/blob/master/src/llama-grammar.cpp) · [GDN Hexagon PR #29199](https://github.com/ggml-org/llama.cpp/pull/29199)
- [FunctionGemma on-device function calling, arXiv:2609.25373](https://arxiv.org/abs/2609.25373)
- [Artificial Analysis: Qwen3.5 small models](https://artificialanalysis.ai/articles/qwen3-5-small-models)
