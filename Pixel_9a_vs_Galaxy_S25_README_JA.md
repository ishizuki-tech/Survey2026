# Google Pixel 9a vs Samsung Galaxy S25
## Android・オンデバイスAI開発向け徹底比較

_Last updated: 2026-09-17_

---

# 日本語

## 1. 結論

Pixel 9a と Galaxy S25 は、どちらも最新世代の Android アプリ開発に十分使える端末ですが、**開発機としてはかなり性格が違います**。

最も決定的な違いは次の4点です。

| 項目 | Pixel 9a | Galaxy S25 | 開発への影響 |
|---|---|---|---|
| SoC | Google Tensor G4 | Snapdragon 8 Elite for Galaxy | CPU/GPU/NPU の実装が異なる |
| GPU | ARM Mali-G715 系 | Qualcomm Adreno 830 系 | OpenCL/Vulkan/GPU delegate の挙動差が出やすい |
| RAM | 8 GB | 12 GB | 大型LLM・Whisper・複数モデル同時利用で差が出る |
| Android実装 | Google Pixel software stack | Samsung One UI | MediaStore、権限、バックグラウンド動作などでOEM差が出る可能性 |

**開発方針としては、Pixel 9a を基準機に使うのは問題ありません。**<br>
ただし、Pixel 9a だけで検証して「Androidで動く」と判断するのは危険です。

特に以下は Galaxy S25 でも必ず実機検証すべきです。

- MediaStore / Storage
- OpenCL / Vulkan
- LiteRT GPU delegate
- whisper.cpp GPU backend
- メモリ使用量
- サーマルスロットリング
- バックグラウンド処理
- アンインストール / 再インストール後のデータ再発見
- 同一モデル・同一入力での推論結果

---

# 2. ハードウェア比較

## 基本仕様

| 項目 | Google Pixel 9a | Samsung Galaxy S25 |
|---|---|---|
| 発売世代 | 2025 | 2025 |
| SoC | Google Tensor G4 | Snapdragon 8 Elite for Galaxy |
| CPU | ARMベース 8コア | Qualcomm Oryon ベース 8コア |
| GPU | Mali-G715 系 | Adreno 830 系 |
| AI accelerator | Google Tensor の AI/ML hardware | Qualcomm Hexagon NPU |
| RAM | 8 GB | 12 GB |
| Storage | 128 / 256 GB | 128 / 256 / 512 GB（市場により異なる） |
| microSD | 非対応 | 非対応 |
| Display | 6.3" OLED, 60–120 Hz | 6.2" Dynamic AMOLED 2X, 最大120 Hz |
| Resolution | 1080 × 2424 | 2340 × 1080 |
| Battery | 5100 mAh typical | 4000 mAh typical |
| Weight | 約185.9 g | 約162 g |
| 防水防塵 | IP68 | IP68 |

---

# 3. SoC の違い

## Pixel 9a — Tensor G4

Pixel 9a は Google Tensor G4 を搭載しています。

Tensor は単純なピーク性能だけを狙った SoC ではなく、Google の Android / Pixel / AI 機能との統合を重視した設計です。

開発上の特徴:

- Google の Android 実装に近い環境でテストしやすい
- ARM Mali GPU
- Google 独自の Tensor / ML スタック
- Pixel 固有のドライバや power management
- 8 GB RAM が大型モデルでは制約になりやすい

## Galaxy S25 — Snapdragon 8 Elite for Galaxy

Galaxy S25 は Qualcomm と Samsung がカスタマイズした Snapdragon 8 Elite for Galaxy を搭載しています。

構成:

- Qualcomm Oryon CPU
- Qualcomm Adreno GPU
- Qualcomm Hexagon NPU
- Samsung 向けに調整されたクロック・電力・画像処理
- 12 GB RAM

オンデバイスAIでは、RAM容量と GPU/NPU の性能余裕が Pixel 9a より大きいケースがあります。

---

# 4. GPU の違い — 最重要ポイントの一つ

## Pixel 9a

GPU:

**ARM Mali-G715**

実機ベンチマークでも Pixel 9a は Mali-G715 として OpenCL / Vulkan デバイスが確認されています。

## Galaxy S25

GPU:

**Qualcomm Adreno 830**

Snapdragon 8 Elite for Galaxy の GPU は Qualcomm Adreno 系です。

---

# 5. なぜ GPU が違うと同じ Android アプリでも挙動が変わるのか

Android の API は共通でも、その下にあるドライバはメーカーごとに異なります。

```text
Application
    ↓
LiteRT / whisper.cpp / native library
    ↓
OpenCL / Vulkan / GPU delegate
    ↓
GPU driver
    ↓
Mali GPU or Adreno GPU
```

つまり同じ Kotlin / C++ コードでも、

```text
Pixel
→ ARM Mali driver

Galaxy
→ Qualcomm Adreno driver
```

を通ります。

この違いによって次が変わる可能性があります。

- 対応 OpenCL feature
- Vulkan extension
- shader compilation
- FP16 の扱い
- buffer alignment
- memory allocation
- kernel scheduling
- fallback behavior
- GPU delegate 対応演算
- driver bug
- execution timing

---

# 6. OpenCL

今回のプロジェクトでは特に重要です。

Pixel 9a の Mali-G715 は OpenCL デバイスとして確認できます。

Galaxy S25 の Adreno も Qualcomm GPU stack を使用しますが、**同じ OpenCL API でもドライバ実装は別物**です。

そのため、例えば:

```text
Pixel 9a
OpenCL kernel → OK

Galaxy S25
OpenCL kernel → compile error
```

または逆に:

```text
Pixel 9a
CPU fallback

Galaxy S25
GPU execution
```

ということも起こり得ます。

whisper.cpp / ggml では過去に Qualcomm Adreno の OpenCL 実装で、特定の OpenCL symbol が存在しないことによる互換性問題も報告されています。

したがって、

> 「OpenCL 2.x 対応」

という表記だけでは互換性を保証できません。

---

# 7. Vulkan

Vulkan は Android で GPU を跨いだ比較的標準的な GPU API です。

現在の whisper.cpp / ggml には Vulkan backend があります。

開発上は、OpenCL だけではなく Vulkan backend も評価する価値があります。

テスト項目:

```text
Pixel 9a + Vulkan
Galaxy S25 + Vulkan

Pixel 9a + OpenCL
Galaxy S25 + OpenCL
```

この4パターンを比較すると、GPU固有問題を発見しやすくなります。

---

# 8. LiteRT / Gemma 3n への影響

LiteRT で Gemma 3n のようなローカルLLMを動かす場合、差が出る可能性がある箇所は次です。

## 8.1 GPU delegate compatibility

同じモデルでも、

```text
Pixel → GPU delegate accepts operation
Samsung → operation falls back to CPU
```

または逆が起こる可能性があります。

## 8.2 Memory

Pixel 9a:

```text
8 GB RAM
```

Galaxy S25:

```text
12 GB RAM
```

約5 GB級モデルを扱う場合、この4 GB差はかなり重要です。

RAMはモデルファイルサイズそのものだけではなく、

- weights
- KV cache
- tensors
- GPU buffers
- tokenizer
- application heap
- audio buffers
- Android OS
- Compose UI
- whisper.cpp

でも使用されます。

そのため Pixel 9a ではメモリプレッシャーが先に起きる可能性があります。

---

# 9. whisper.cpp への影響

whisper.cpp は Android をサポートしています。

現在の upstream では CPU-only に加えて Vulkan backend も利用可能です。

プロジェクト側で OpenCL / CLBlast 等を使っている場合は、GPU差の影響がより大きくなります。

比較すべき指標:

- Model load time
- Audio decode time
- Encoder time
- Decoder time
- Real-time factor
- Peak RAM
- GPU memory
- Crash rate
- thermal throttling
- transcript output

---

# 10. 推論速度

これは S25 と Pixel 9a で差が出やすい部分です。

同じモデルでも、

```text
Model
Prompt
Temperature
Top-K
Top-P
Max tokens
```

が同じであっても、

```text
CPU backend
GPU backend
NPU/backend delegate
driver
memory bandwidth
thermal condition
```

が違えば速度は変わります。

S25 は高性能 SoC と12 GB RAMを持つため、特に長時間のAI処理や大きなモデルでは余裕が出る可能性があります。

ただし、実際の速度は「理論性能」ではなく、LiteRT / ggml / OpenCL / Vulkan がそのハードウェアをどれだけ有効利用できるかで決まります。

---

# 11. 推論精度

ここは重要な区別があります。

**GPUが違うからモデルそのものの学習精度が変わるわけではありません。**

しかし、実行バックエンドによる数値差は起こり得ます。

例:

```text
FP32
FP16
INT8
INT4
```

また、

- floating-point rounding
- kernel implementation
- operation fusion
- quantization
- parallel execution
- fallback

によって、非常に小さい数値差が発生する可能性があります。

LLMでは小さい差でも autoregressive decoding の途中で token 分岐が起きると、その後の文章全体が変わる場合があります。

Whisperでも境界的な音声の場合、

```text
Pixel → "fifteen"
Samsung → "fifty"
```

のような違いが理論上起こり得ます。

したがって、**速度だけでなく output consistency も比較するべきです。**

---

# 12. 推論精度テスト

## Gemma 3n

同じ50〜100件の入力を使用します。

記録:

```text
device
backend
model hash
prompt hash
score
missing_points
followup_needed
raw output
latency
```

比較:

- JSON parse success rate
- score一致率
- missing_points一致率
- followup_needed一致率
- exact output一致率
- semantic一致率

## whisper.cpp

同じ音声50〜100本を使用します。

記録:

```text
device
backend
model hash
audio hash
transcript
latency
real-time factor
```

比較:

- WER
- CER
- exact transcript match
- latency
- crash / timeout

---

# 13. MediaStore の違い

これは GPU / SoC とは別問題です。

Android の `MediaStore.MediaColumns.OWNER_PACKAGE_NAME` は、

> その media を登録した package name

を表します。

Android公式仕様では、

**ownership を確実に判定できない場合 NULL になり得ます。**

さらに Android 14 以降では package visibility の影響も受けます。

したがって、

```text
OWNER_PACKAGE_NAME == my.package.name
```

だけを使ってファイル所有権やモデル再発見を判断するのは安全ではありません。

---

# 14. 今回観測したストレージ差

今回のプロジェクトでは、Pixel 9a と Samsung で MediaStore の再発見挙動に差が観測されました。

重要なのは、

> これは Tensor vs Snapdragon の差ではない

という点です。

主に以下の層です。

```text
Android framework
MediaProvider
OEM customization
package visibility
storage metadata
```

つまり、

```text
CPU/GPU difference
```

ではなく、

```text
OS / OEM implementation difference
```

として扱うべき問題です。

---

# 15. モデルファイルの識別方法

`OWNER_PACKAGE_NAME` のみに依存しない方が安全です。

候補:

```text
DISPLAY_NAME
RELATIVE_PATH
SIZE
MIME_TYPE
model version
SHA-256 hash
custom metadata
manifest
```

推奨:

```text
model id
+
model version
+
file size
+
SHA-256
```

で再発見・検証する方式です。

---

# 16. Android UI / OEM の違い

Pixel:

```text
Google Pixel software stack
```

Samsung:

```text
Samsung One UI
```

Samsungは Android framework の上に多数のOEM機能を追加しています。

違いが出る可能性がある領域:

- Battery optimization
- background restrictions
- permission UI
- notifications
- file picker
- MediaStore provider behavior
- camera provider
- audio routing
- Bluetooth
- USB
- process killing
- foreground service handling
- biometric implementation
- display scaling
- keyboard / IME interaction

---

# 17. バックグラウンド処理

AIアプリでは特に注意します。

長時間推論や音声認識中に、

```text
screen off
app background
battery saver
thermal state
```

になると端末ごとに動作が変わる可能性があります。

Samsung は独自の battery management を持つため、

```text
Pixelでは動く
Samsungではbackgroundで停止
```

というケースは別途検証すべきです。

---

# 18. Thermal behavior

長時間推論では CPU/GPU のピーク性能より thermal management が重要です。

テスト:

```text
1 inference
10 consecutive inferences
30 min loop
60 min loop
```

測定:

- latency
- CPU clock
- GPU clock
- temperature
- battery drain
- throttling
- crash

短いベンチマークだけでは実運用性能は判断できません。

---

# 19. RAM差の実際の意味

Pixel 9a:

```text
8 GB
```

S25:

```text
12 GB
```

これはローカルAI開発では非常に大きな差です。

例えば:

```text
Gemma 3n
+
Whisper
+
VAD
+
Compose
+
Audio buffers
+
GPU buffers
```

を同時に動かすと Pixel 9a の方が先に memory pressure を受ける可能性があります。

したがって Pixel 9a はある意味で、

> より厳しい lower-memory test device

として有用です。

Pixel 9a で安定すれば、メモリ面では S25 には余裕が出る可能性があります。

ただし GPU backend の互換性は別問題なので、S25実機テストは必要です。

---

# 20. カメラ

Pixel 9a:

- 48 MP wide
- 13 MP ultrawide

Galaxy S25:

- 50 MP wide
- 12 MP ultrawide
- 10 MP telephoto
- 3x optical zoom

カメラ入力をAIに使用する場合、

- image resolution
- Camera2 / CameraX behavior
- hardware level
- image format
- ISP processing

も別途比較対象になります。

---

# 21. ディスプレイ

Pixel 9a:

```text
6.3"
1080 x 2424
60–120 Hz
```

Galaxy S25:

```text
6.2"
2340 x 1080
up to 120 Hz
```

通常アプリでは大差ありませんが、

- density
- font scaling
- edge-to-edge
- insets
- Compose layout
- keyboard
- display cutout

は両方でUI確認した方が安全です。

---

# 22. バッテリー

Pixel 9a:

```text
5100 mAh typical
```

Galaxy S25:

```text
4000 mAh typical
```

ただし mAh だけでAI処理時の持続時間は比較できません。

電力効率は、

- SoC
- GPU utilization
- CPU utilization
- display
- modem
- thermal policy

に左右されます。

オンデバイスAIでは「1回の推論速度」だけではなく、

```text
energy per inference
```

も測る価値があります。

---

# 23. セキュリティ実装

Pixel 9a:

- Tensor security core
- Titan M2
- Trusty TEE

Galaxy S25:

- Samsung Knox
- Qualcomm hardware security
- Samsung security framework

一般アプリでは大きな違いは出にくいですが、

- Keystore
- biometric
- hardware-backed keys
- enterprise deployment

では差が出る可能性があります。

---

# 24. Pixel 9a をベースに開発してよいか

**Yes.**

Pixel 9a は基準開発機として非常に使いやすいです。

理由:

- Google端末
- OS更新が早い
- Android標準挙動の確認に向く
- 8 GBという比較的厳しいRAM条件
- Mali GPU検証ができる

ただし、

```text
Pixel 9a passed
```

は

```text
All Android phones passed
```

を意味しません。

---

# 25. Galaxy S25 が必要な理由

S25を追加すると、

```text
Tensor  → Snapdragon
Mali    → Adreno
8 GB    → 12 GB
Pixel   → Samsung One UI
```

と、一台追加するだけでかなり異なるAndroid環境をカバーできます。

特に今回のアプリでは価値が高いです。

理由:

- LiteRT
- Gemma 3n
- whisper.cpp
- OpenCL
- Vulkan
- JNI/native code
- large model storage
- MediaStore
- background audio
- long-running inference

すべてが device-specific difference の影響を受け得るからです。

---

# 26. 推奨テストマトリクス

## Gemma / LiteRT

| Device | CPU | GPU | Result |
|---|---:|---:|---|
| Pixel 9a | ✅ | ✅ | 必須 |
| Galaxy S25 | ✅ | ✅ | 必須 |

確認:

```text
model load
first token latency
tokens/sec
total latency
peak RAM
GPU fallback
JSON validity
output consistency
thermal behavior
```

## whisper.cpp

| Device | CPU | OpenCL | Vulkan |
|---|---:|---:|---:|
| Pixel 9a | ✅ | ✅ | ✅ |
| Galaxy S25 | ✅ | ✅ | ✅ |

確認:

```text
WER
CER
real-time factor
encoder latency
decoder latency
peak RAM
crash
thermal throttling
```

---

# 27. 最低限保存するログ

各推論で次を保存することを推奨します。

```text
manufacturer
model
Android version
SDK level
build fingerprint

SoC
GPU renderer
OpenCL version
Vulkan version

backend
delegate
model name
model SHA-256

input SHA-256
prompt
temperature
top-k
top-p
max tokens

output
latency
memory
temperature
error
fallback reason
```

これがあれば、

```text
model problem
device problem
driver problem
backend problem
app problem
```

を切り分けやすくなります。

---

# 28. 開発上の最終整理

## Pixel 9a が得意

- Google Android 基準機
- Mali GPU検証
- 8 GB RAMで低メモリ耐性テスト
- Android新API検証
- Pixel固有 behavior の確認

## Galaxy S25 が得意

- Snapdragon検証
- Adreno検証
- Qualcomm OpenCL/Vulkan stack
- 12 GB RAM
- 高負荷AI推論
- Samsung One UI compatibility
- OEM差検証

## 両方必要な理由

```text
Pixel 9a
   ↓
Google + Tensor + Mali + 8 GB

Galaxy S25
   ↓
Samsung + Snapdragon + Adreno + 12 GB
```

この2台は似たAndroid端末ではなく、**内部構成がかなり違う2種類のAndroidプラットフォーム**です。

今回のような

```text
LiteRT
Gemma 3n
whisper.cpp
OpenCL
Vulkan
MediaStore
large local model
```

を扱うアプリでは、この違いは特に重要です。

---

---

# 29. 追加の開発リスク完全チェックリスト

以下は、Pixel 9a を基準機として Galaxy S25 でも動作させる場合に、追加で問題になり得るポイントです。

## A. GPU / AI / Native

1. Adreno と Mali の OpenCL 実装差
2. Vulkan shader / extension 差
3. LiteRT GPU delegate の一部 operator fallback
4. FP16 / INT4 / quantization 実装差
5. GPU buffer alignment 差
6. GPU memory allocation failure
7. driver-level crash / hang
8. 初回 shader compilation の遅延
9. CPU fallback が無言で発生するケース
10. Tensor / Snapdragon 間の CPU 最適化差
11. NEON / SIMD / dot-product 最適化差
12. thread count の最適値の違い
13. GPU context coexistence
14. Whisper と Gemma の同時 GPU 使用競合
15. native heap / GPU memory の解放タイミング差
16. JNI reference leak
17. native crash: SIGSEGV / SIGABRT / SIGBUS
18. 16 KB page-size compatibility
19. 依存 `.so` の 16 KB alignment 非対応
20. R8 / ProGuard による JNI / reflection 問題

## B. Memory / Performance / Thermal

21. Pixel 9a の 8 GB RAM による memory pressure
22. Gemma + Whisper + VAD + UI の同時メモリ使用
23. KV cache 増大
24. mmap 後の追加 tensor allocation failure
25. Low Memory Killer による process kill
26. foreground → background 移行時の process kill
27. 30〜60分推論による thermal throttling
28. charging 中と非充電時の性能差
29. Battery Saver 時の性能低下
30. CPU/GPU clock 制御差
31. disk I/O / mmap page-in 差
32. 初回ロードと2回目以降の page cache 差
33. SHA-256 の毎回全量計算による起動遅延
34. file descriptor leak
35. long-running session での latency creep

## C. Storage / MediaStore / Model Files

36. `OWNER_PACKAGE_NAME` が `NULL` になるケース
37. MediaStore の OEM 実装差
38. uninstall / reinstall 後のモデル再発見
39. Clear storage と uninstall の違い
40. Clear cache と Clear storage の違い
41. app update 後の model migration
42. model version mismatch
43. model runtime version mismatch
44. partial download
45. corrupt model
46. storage full / ENOSPC
47. temp file が残るケース
48. atomic rename 失敗
49. Downloads / MediaStore / SAF の URI 差
50. `content://` を実ファイルpathに変換する実装
51. persistable URI permission の失効
52. removable / cloud storage provider 差
53. 同名モデルファイル衝突
54. stale metadata
55. hash は一致するが metadata が古いケース

## D. Android Lifecycle / Background

56. Activity recreation
57. rotation
58. dark mode / light mode change
59. language change
60. font scale change
61. display size change
62. multi-window
63. process death
64. `Don't keep activities`
65. singleton に native pointer を保持する設計
66. model session 再生成漏れ
67. foreground service type の不一致
68. microphone foreground service 制約
69. WorkManager / JobScheduler quota
70. screen off 中の推論停止
71. Samsung Sleeping Apps
72. Samsung Deep Sleeping Apps
73. Samsung 独自 battery optimization
74. background notification 抑制
75. app standby bucket 差

## E. Audio / Whisper

76. microphone hardware 差
77. AEC
78. AGC
79. noise suppression
80. sample rate
81. channel count
82. audio source
83. hardware resampling
84. microphone gain
85. Bluetooth SCO
86. BLE Audio
87. Bluetooth codec
88. USB audio
89. external microphone reconnect
90. screen lock 中の録音継続
91. 同一音声ファイルと実マイク入力の結果差
92. VAD threshold の端末依存
93. audio underrun / overrun
94. audio route change during inference

## F. Camera / Media

95. CameraX / Camera2 差
96. camera hardware level
97. YUV format
98. image rotation
99. ISP processing
100. capture latency
101. H.264 decoder差
102. H.265 decoder差
103. AV1 support差
104. AAC / Opus decoder差
105. hardware encoder / decoder 同時使用制限

## G. UI / Input / Locale

106. Samsung Keyboard vs Gboard
107. Japanese IME composing text
108. multiline Enter behavior
109. autofill
110. predictive text
111. edge-to-edge
112. navigation bar
113. gesture inset
114. display cutout
115. density
116. font clipping
117. IME が input field を覆うケース
118. 日本語 / 英語 / スワヒリ語 locale
119. per-app language
120. RTL language
121. Compose layout の OEM / font 差

## H. Permissions / Security / Notifications

122. RECORD_AUDIO permission
123. POST_NOTIFICATIONS permission
124. READ_MEDIA_* permission
125. Bluetooth permission
126. Camera permission
127. foreground service permission
128. Nearby devices permission
129. permission denial → retry
130. “Don't ask again”
131. Settings から後変更
132. Android Keystore capability 差
133. hardware-backed key 差
134. biometric implementation 差
135. Samsung Knox 差
136. Pixel Titan security stack 差
137. notification channel behavior
138. lock-screen notification 差

## I. Network / Model Delivery

139. Wi-Fi → cellular handover
140. offline
141. airplane mode
142. DNS failure
143. timeout
144. retry storm
145. resume download
146. HTTP Range request
147. partial file reuse
148. checksum mismatch
149. redirect
150. CDN edge差
151. captive portal
152. metered network
153. background download restrictions
154. Play Store update とモデル更新の競合

## J. Build / Release / Distribution

155. debug build では動くが release build で失敗
156. App Bundle / split APK 差
157. ABI split
158. arm64-v8a packaging
159. native library strip
160. Play Integrity
161. signing key 差
162. Play Store install vs adb install
163. targetSdk 差
164. Android 15 vs Android 16 差
165. vendor patch level 差
166. OEM firmware update 後の driver regression
167. app downgrade
168. rollback
169. staged rollout
170. crash-reporting で native tombstone を取得できないケース

---

# 30. このプロジェクトで優先度が高い順

優先順位は次の通りです。

1. Adreno vs Mali の GPU / OpenCL / Vulkan 互換性
2. LiteRT GPU delegate の fallback
3. Gemma + Whisper 同時実行時の 8 GB RAM
4. MediaStore / uninstall / reinstall
5. 約5 GBモデルの partial download / SHA-256 / atomic install
6. 16 KB native page-size compatibility
7. Whisper + Gemma の同時 GPU 利用
8. 30〜60分連続推論での thermal throttling
9. Samsung Sleeping / Deep Sleeping
10. screen off / background microphone / foreground service
11. native / JNI memory leak
12. actual inference output consistency
13. Android 16 / targetSdk 36 behavior
14. release / AAB build
15. 実マイクの Pixel / Samsung 差

---

# 31. 推奨リリースゲート

最低限、次の4領域は Pixel 9a と Galaxy S25 の両方で通すべきです。

```text
GPU / Native
Storage / MediaStore
Background / Lifecycle
Long-running AI inference
```

推奨ゲート:

```text
Pixel 9a
  CPU inference PASS
  GPU inference PASS
  MediaStore reinstall PASS
  30 min sustained PASS
  screen-off voice PASS

Galaxy S25
  CPU inference PASS
  GPU inference PASS
  MediaStore reinstall PASS
  30 min sustained PASS
  screen-off voice PASS
```

これを満たしてから「Android互換性OK」と判断するのが安全です。
