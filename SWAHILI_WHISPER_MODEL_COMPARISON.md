# Survey2026 — Swahili Whisper Model Comparison

## Overview

Survey2026 currently includes four experimental Swahili Whisper Small models.

All four models are:

- converted for `whisper.cpp`
- quantized to `Q5_0`
- about 175 MB
- integrated into separate Survey2026 branches
- available as Branch APK Preview builds

> Current accuracy results are based on one 16.4-second synthetic Swahili sample. Real Kenyan/Tanzanian speech should be used before selecting the production model.

## Model Summary

| Model | Focus | Mac Time | Download |
|---|---|---:|---|
| `Lingua-Connect/whisper-small-sw-normal` | Current Swahili baseline | 442.68 ms | [Download Page](https://ishizuki-tech.github.io/Survey2026/branches/feature-swahili-whisper-model/) |
| `cdli/whisper-small-Swahili_finetuned_small_CV20` | Common Voice 20 | 444.90 ms | [Download Page](https://ishizuki-tech.github.io/Survey2026/branches/feature-swahili-whisper-cv20/) |
| `cdli/whisper-small_finetuned_kenyan_swahili_nonstandard_speech_v1.0` | Kenyan / non-standard Swahili | 390.25 ms | [Download Page](https://ishizuki-tech.github.io/Survey2026/branches/feature-swahili-whisper-kenyan-nonstandard/) |
| `dmusingu/WHISPER-SMALL-SWAHILI-ASR-CV-14` | Common Voice 14 | 456.67 ms | [Download Page](https://ishizuki-tech.github.io/Survey2026/branches/feature-swahili-whisper-cv14/) |

## 1. Lingua-Connect Baseline

- **Hugging Face:** `Lingua-Connect/whisper-small-sw-normal`
- **Branch:** `feature/swahili-whisper-model`
- **whisper.cpp model:** `ggml-small-sw-q5_0.bin`
- **Model SHA-256:** `3cb6382561f9dc56372dbed75433ec73be05ef03d142dce964575529ea08c18d`
- **Download:** https://ishizuki-tech.github.io/Survey2026/branches/feature-swahili-whisper-model/
- **Preliminary result:** Current baseline and the most natural result on the synthetic sample.

## 2. CDLI CV20

- **Hugging Face:** `cdli/whisper-small-Swahili_finetuned_small_CV20`
- **Branch:** `feature/swahili-whisper-cv20`
- **whisper.cpp model:** `ggml-small-sw-cv20-q5_0.bin`
- **Model SHA-256:** `0a6f5f81019ebd7be3c62bfe789866da84cf99de572dac82259cd5f823e86e9f`
- **Download:** https://ishizuki-tech.github.io/Survey2026/branches/feature-swahili-whisper-cv20/
- **APK:** `Survey2026-feature-swahili-whisper-cv20-f88b8ea.apk`
- **APK SHA-256:** `3e1c25d651be1a49af0e16e46c793abea3dfc1b9aeb64c7a17f5c5b7959a80c8`
- **Preliminary result:** Similar speed to the baseline, but slightly weaker transcription on the synthetic sample.

## 3. CDLI Kenyan Non-Standard

- **Hugging Face:** `cdli/whisper-small_finetuned_kenyan_swahili_nonstandard_speech_v1.0`
- **Branch:** `feature/swahili-whisper-kenyan-nonstandard`
- **whisper.cpp model:** `ggml-small-sw-kenyan-nonstandard-q5_0.bin`
- **Model SHA-256:** `f121e2a38d640d51b301cccbf377a9a0c27a7a8751c9e1ef7c0a34efd94dd6c7`
- **Download:** https://ishizuki-tech.github.io/Survey2026/branches/feature-swahili-whisper-kenyan-nonstandard/
- **Preliminary result:** Fastest of the four on Mac. Real Kenyan speech testing is especially important for this model.

## 4. dmusingu CV14

- **Hugging Face:** `dmusingu/WHISPER-SMALL-SWAHILI-ASR-CV-14`
- **Branch:** `feature/swahili-whisper-cv14`
- **whisper.cpp model:** `ggml-small-sw-cv14-q5_0.bin`
- **Model SHA-256:** `a8610565fe9ebed140d799b30771c984d85e08eb21f17365845fb4c357a510ce`
- **Download:** https://ishizuki-tech.github.io/Survey2026/branches/feature-swahili-whisper-cv14/
- **APK:** `Survey2026-feature-swahili-whisper-cv14-260576b.apk`
- **APK Size:** `254,107,784 bytes (242.33 MiB)`
- **APK SHA-256:** `4a66b03d5b37a40c8fe752a921f6e2532fcc1dfaa7b4d058d8cc5f848b0ff0c6`
- **Preliminary result:** Good mid-sentence recognition, but weaker sentence-start recognition in the synthetic test.

## Preliminary Performance

| Model | Total Time |
|---|---:|
| Kenyan Non-Standard | **390.25 ms** |
| Lingua-Connect | 442.68 ms |
| CV20 | 444.90 ms |
| CV14 | 456.67 ms |

All four models transcribed the 16.4-second test audio in under 0.5 seconds on the Apple M5 Pro test system.

## Next Evaluation

Use the same real-world audio across all four models and compare:

- WER / CER
- Pixel 9a transcription time
- model load time
- peak memory
- Kenyan/Tanzanian accent robustness
- noisy field-recording performance

## Current Status

| Model | whisper.cpp | Q5_0 | Mac Test | Android BranchBuild |
|---|---|---|---|---|
| Lingua-Connect | ✅ | ✅ | ✅ | ✅ |
| CDLI CV20 | ✅ | ✅ | ✅ | ✅ |
| Kenyan Non-Standard | ✅ | ✅ | ✅ | ✅ |
| dmusingu CV14 | ✅ | ✅ | ✅ | ✅ |

The four builds are ready for side-by-side Android testing.
