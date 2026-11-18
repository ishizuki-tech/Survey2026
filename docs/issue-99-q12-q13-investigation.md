# Issue #99 Swahili Q12 and Q13 investigation

## Original failures

The October real-model validation on Galaxy S25 reproduced two mismatches in two runs per fixture. Q12 EVAL accepted an answer about early rainfall cessation with score 92 and no follow-up, even though the answer supplied no maize growth stage. Q13 asked a redundant, awkward follow-up after the respondent supplied household, livestock, and sale-price allocation criteria. On the later Pixel 9a baseline at `f16a9ddfeaf616679d302efeaa2389e0c52aae80`, Q12 generated a rainfall-timing question and Q13 completed without a follow-up in all three historical fixture runs. That baseline includes the Q13 EVAL change in PR #192. [Issue #99](https://github.com/ishizuki-tech/Survey2026/issues/99) · [PR #192](https://github.com/ishizuki-tech/Survey2026/pull/192)

## Validated EVAL clarification

The proposed production diff adds one sentence to Q12 EVAL:

> Ikiwa jibu linataja wakati mvua zilipokatika bila kutaja hatua ya ukuaji wa mahindi, hatua hiyo bado haijajibiwa.

A matched 30-session test used the same APKs, Pixel 9a (Android 16/API 36), LiteRT-LM 0.18.0, Gemma 3n E4B IT int4 on GPU, and the same sampling settings across arms (4,096 max tokens, top-K 64, top-P 0.95, temperature 1.0). The original Q12 FOLLOWUP template and global instructions were identical. Each generated follow-up received the raw EVAL JSON from its own run.

| Fixture | Main EVAL | Clarified EVAL |
| --- | --- | --- |
| Early rainfall cessation | `GENERATE`, score 65, vague `hatua nyingine` | `GENERATE`, score 70, `hatua ya ukuaji wa mahindi` in 3/3 |
| Drought effects | `ACHIEVED`, score 95, despite a missing stage | `GENERATE`, score 75, `hatua ya ukuaji wa mahindi` in 3/3 |
| Plant loss | `GENERATE`, score 70, stage named with an unclear qualifier | `GENERATE`, score 45, stage named with a different unclear qualifier in 3/3 |
| Q12 and Q13 complete | Each `ACHIEVED` 3/3, no follow-ups | Same for both fixtures |

The clarification is adopted for its narrower benefit. It directs EVAL to identify the missing stage and prevents premature completion for the tested drought-effects answer. The EVAL-only arm reproduced Candidate B's EVAL results while keeping the original FOLLOWUP prompt. It passed zero of nine partial runs on the combined FOLLOWUP semantic and wording criteria. All nine generated questions passed strict format admission. The change therefore improves EVAL on the tested inputs while the end-to-end FOLLOWUP limitation remains open.

## Other experiments

| Arm | Observed Q12 FOLLOWUP result |
| --- | --- |
| A | The historical partial fixture got a generic “when else” question in 3/3. |
| B | The historical and drought-effects fixtures retained the greatest-damage growth-stage target in 6/9 matched partial runs overall. Their wording was awkward, and plant-loss questions left the stage implicit. B changed both EVAL and FOLLOWUP. |
| C | The historical fixture got an undefined-quantity question in 3/3. |
| D | Asked for the growth stage at early rain cessation in 3/3 historical runs. The drought-effects question used an unclear verb in 3/3. |
| E | Missed the precise target in 9/9 partial runs, with generic or plant-death timing. |
| F | A structured FOLLOWUP input missed the target in 9/9 partial runs despite identical EVAL to B. |
| EVAL-only | The original FOLLOWUP still produced generic timing, repeated supplied drought effects, or left the growth-stage request implicit. |

Q12 has no required-component catalog. FOLLOWUP receives its missing points in raw EVAL JSON, which can already contain the correct stage, while strict admission checks format and duplicates. The experiments show semantic drift after EVAL. They leave the cause of that drift unresolved.

The offline deterministic study reduced 42 accepted outputs to eight unique questions with one positive, six negatives, and one uncertain reference. On 18 synthetic challenge items, the target-aware rule falsely accepted 4/8 negatives and rejected 7/8 positives. The conservative rule abstained on 14/18. The offline Gemma judge returned malformed JSON in 81/93 runs and accepted an off-target plant-death question in 3/3. Its median standalone GPU inference was 4.66 seconds. Neither study supports adding a validator or judge to the interview.

## Evidence and limits

The paths below describe the local experiment archive. They are outside Git and may be unavailable on another machine. The saved prompts and outputs support review and replay of the recorded decisions. Reproducing live inference requires the recorded APKs, exact fixtures, matching model hash, and a suitable device.

| Evidence | Local archive |
| --- | --- |
| Current-baseline historical fixture rerun and A/B | `/private/tmp/Survey2026-issue-99-evidence/` and `/private/tmp/Survey2026-issue-99-prompt-experiment/` |
| C, D, and E | `/private/tmp/Survey2026-issue-99-candidate-c/`, `/private/tmp/Survey2026-issue-99-candidate-d/`, and `/private/tmp/Survey2026-issue-99-candidate-e/` |
| Matched main, B, and F | `/private/tmp/Survey2026-issue-99-followup-input/all-45-runs.json` |
| Isolated EVAL comparison | `/private/tmp/Survey2026-issue-99-eval-only/` contains `eval-only.patch`, `all-30-runs.json`, `semantic-assessment.json`, hashes, and `reproduce.md`. |
| Offline checks | `/private/tmp/Survey2026-issue-99-semantic-validator/report.md` and `/private/tmp/Survey2026-issue-99-slm-judge/results-report.md` |

The EVAL-only evidence covers three partial-answer contexts and two complete fixtures on one device, with empty answered follow-up histories. Repeated outputs measure repeatability on those inputs. Broader Swahili fluency, held-out answers, multi-turn history, and field performance remain unverified. [Remaining Q12 work](../TODO.md)
