# Survey2026 — Swahili Whisper Model Comparison

## Overview

Survey2026 currently includes four experimental Swahili Whisper Small models for side-by-side Android evaluation.

All four models are:

- converted for `whisper.cpp`
- quantized to `Q5_0`
- approximately 175 MB
- integrated into separate Survey2026 branches
- built successfully as Branch APK Preview builds
- synchronized with the current `main` branch, including the HF encrypted-token implementation

> Current accuracy and Mac timing results are based on one 16.4-second synthetic Swahili sample. These results are preliminary. Real Kenyan and Tanzanian speech, using the same test corpus and Android device, should be used before selecting a production model.

## Model Summary

| Model | Focus | Branch Commit | Mac Time | Download |
|---|---|---:|---:|---|
| `Lingua-Connect/whisper-small-sw-normal` | Swahili baseline | `37f90cc` | 442.68 ms | [Download Page](https://ishizuki-tech.github.io/Survey2026/feature-swahili-whisper-model/) |
| `cdli/whisper-small-Swahili_finetuned_small_CV20` | Common Voice 20 | `6c74f51` | 444.90 ms | [Download Page](https://ishizuki-tech.github.io/Survey2026/feature-swahili-whisper-cv20/) |
| `cdli/whisper-small_finetuned_kenyan_swahili_nonstandard_speech_v1.0` | Kenyan / non-standard Swahili | `e165d5a` | 390.25 ms | [Download Page](https://ishizuki-tech.github.io/Survey2026/feature-swahili-whisper-kenyan-nonstandard/) |
| `dmusingu/WHISPER-SMALL-SWAHILI-ASR-CV-14` | Common Voice 14 | `e0b5b6b` | 456.67 ms | [Download Page](https://ishizuki-tech.github.io/Survey2026/feature-swahili-whisper-cv14/) |

## 1. Lingua-Connect Baseline

- **Hugging Face:** `Lingua-Connect/whisper-small-sw-normal`
- **Branch:** `feature/swahili-whisper-model`
- **Latest branch commit:** `37f90cc646692f7d89f7e5f7e5bf848f5538a0d9`
- **whisper.cpp model:** `ggml-small-sw-q5_0.bin`
- **Model SHA-256:** `3cb6382561f9dc56372dbed75433ec73be05ef03d142dce964575529ea08c18d`
- **Download:** https://ishizuki-tech.github.io/Survey2026/feature-swahili-whisper-model/
- **APK:** `Survey2026-feature-swahili-whisper-model-37f90cc.apk`
- **APK SHA-256:** `245bc9643b4e15f2717e4d69df29e36b22dafecada85d38fa45870314a60cdf4`
- **BranchBuild generated:** `2026-09-23T22:56:18Z`
- **Preliminary result:** Current baseline and the most natural result on the synthetic sample.

## 2. CDLI CV20

- **Hugging Face:** `cdli/whisper-small-Swahili_finetuned_small_CV20`
- **Branch:** `feature/swahili-whisper-cv20`
- **Latest branch commit:** `6c74f51865d8c6151f87858407900aa626a2bbfd`
- **whisper.cpp model:** `ggml-small-sw-cv20-q5_0.bin`
- **Model SHA-256:** `0a6f5f81019ebd7be3c62bfe789866da84cf99de572dac82259cd5f823e86e9f`
- **Download:** https://ishizuki-tech.github.io/Survey2026/feature-swahili-whisper-cv20/
- **APK:** `Survey2026-feature-swahili-whisper-cv20-6c74f51.apk`
- **APK SHA-256:** `adbc91ebc0cd8e5bad156ddcbd09dd31c8effe33aff7451ddc04052205e06204`
- **BranchBuild generated:** `2026-09-23T23:16:14Z`
- **Preliminary result:** Similar speed to the baseline, but slightly weaker transcription on the synthetic sample.

## 3. CDLI Kenyan Non-Standard

- **Hugging Face:** `cdli/whisper-small_finetuned_kenyan_swahili_nonstandard_speech_v1.0`
- **Branch:** `feature/swahili-whisper-kenyan-nonstandard`
- **Latest branch commit:** `e165d5a4b7c7950723c2badf6f5fe2c0b4098972`
- **whisper.cpp model:** `ggml-small-sw-kenyan-nonstandard-q5_0.bin`
- **Model SHA-256:** `f121e2a38d640d51b301cccbf377a9a0c27a7a8751c9e1ef7c0a34efd94dd6c7`
- **Download:** https://ishizuki-tech.github.io/Survey2026/feature-swahili-whisper-kenyan-nonstandard/
- **APK:** `Survey2026-feature-swahili-whisper-kenyan-nonstandard-e165d5a.apk`
- **APK SHA-256:** `5c1f393223ff52005460e0c0e8dd5aba78476436b93bd4bea775ddd937c9edb8`
- **BranchBuild generated:** `2026-09-23T23:27:34Z`
- **Preliminary result:** Fastest of the four on the Mac test. Real Kenyan speech testing is especially important for this model.

## 4. dmusingu CV14

- **Hugging Face:** `dmusingu/WHISPER-SMALL-SWAHILI-ASR-CV-14`
- **Branch:** `feature/swahili-whisper-cv14`
- **Latest branch commit:** `e0b5b6b36f58309b9ddda6b03e24a3d2555a9287`
- **whisper.cpp model:** `ggml-small-sw-cv14-q5_0.bin`
- **Model SHA-256:** `a8610565fe9ebed140d799b30771c984d85e08eb21f17365845fb4c357a510ce`
- **Download:** https://ishizuki-tech.github.io/Survey2026/feature-swahili-whisper-cv14/
- **APK:** `Survey2026-feature-swahili-whisper-cv14-e0b5b6b.apk`
- **APK SHA-256:** `74eb35a24e9d02df8a0ba1a558ef710fbed6fb3d03f6b28252d4d59830e52f05`
- **BranchBuild generated:** `2026-09-23T23:06:03Z`
- **Preliminary result:** Good mid-sentence recognition, but weaker sentence-start recognition in the synthetic test.

## Preliminary Mac Performance

| Model | Total Time |
|---|---:|
| Kenyan Non-Standard | **390.25 ms** |
| Lingua-Connect | 442.68 ms |
| CV20 | 444.90 ms |
| CV14 | 456.67 ms |

All four models transcribed the same 16.4-second synthetic test audio in under 0.5 seconds on the Apple M5 Pro test system.

These results are preliminary Mac measurements. They are not Pixel 9a performance measurements and should not be used alone to select the production model.

## Android BranchBuild Status

All four current model branches include the current `main` integration and are `0` commits behind `main`.

| Model | Branch | Latest Commit | BranchBuild | APK |
|---|---|---|---|---|
| Lingua-Connect | `feature/swahili-whisper-model` | `37f90cc` | ✅ Success | `Survey2026-feature-swahili-whisper-model-37f90cc.apk` |
| CDLI CV20 | `feature/swahili-whisper-cv20` | `6c74f51` | ✅ Success | `Survey2026-feature-swahili-whisper-cv20-6c74f51.apk` |
| Kenyan Non-Standard | `feature/swahili-whisper-kenyan-nonstandard` | `e165d5a` | ✅ Success | `Survey2026-feature-swahili-whisper-kenyan-nonstandard-e165d5a.apk` |
| dmusingu CV14 | `feature/swahili-whisper-cv14` | `e0b5b6b` | ✅ Success | `Survey2026-feature-swahili-whisper-cv14-e0b5b6b.apk` |

## Security / Build Baseline

The four current Android branch builds were rebuilt after integrating the latest `main` branch.

That baseline includes the HF encrypted-token implementation:

- plaintext `BuildConfig.HF_TOKEN` is no longer used
- HF token material is stored as encrypted BuildConfig material
- download authorization is restricted to `huggingface.co` and its legitimate subdomains
- redirect destinations are re-evaluated before sending authorization
- current Branch APK Preview builds completed successfully

This mechanism provides APK obfuscation and hardening. It does not provide true secret storage because the encrypted material and reconstruction logic remain inside the APK.

## Next Evaluation

Use the exact same real-world audio files across all four APKs and compare:

- WER
- CER
- Pixel 9a transcription time
- model load time
- peak memory
- Kenyan accent robustness
- Tanzanian accent robustness
- non-standard Kenyan speech
- noisy field-recording performance
- sentence-start recognition
- proper nouns and locally specific vocabulary

For a fair comparison, keep the following identical across all four models:

- test corpus
- Pixel 9a device
- microphone / recording source
- audio format and sample rate
- language setting
- `whisper.cpp` runtime version
- decoding parameters
- number of test repetitions

## Current Status

| Model | whisper.cpp | Q5_0 | Mac Test | Current Main Integrated | Android BranchBuild |
|---|---|---|---|---|---|
| Lingua-Connect | ✅ | ✅ | ✅ | ✅ | ✅ |
| CDLI CV20 | ✅ | ✅ | ✅ | ✅ | ✅ |
| Kenyan Non-Standard | ✅ | ✅ | ✅ | ✅ | ✅ |
| dmusingu CV14 | ✅ | ✅ | ✅ | ✅ | ✅ |

The four current APK builds are ready for side-by-side Android testing.
