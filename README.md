# voice-reps

On-device voice replication for Android, built on [ZONOS3-Mini](https://huggingface.co/Zyphra/ZONOS3-Mini),
Zyphra's open-source (Apache 2.0 weights) text-to-speech / zero-shot voice-cloning model.
Everything runs locally on the phone: recording, voice-profile creation, and speech generation.
No account, no server, no audio leaves the device.

## What it does

- **Consent-first recording**: a disclaimer gate, then prompted English + Hebrew phrases
  (30-60 s of speech is enough for a good profile).
- **Upload instead**: any audio file (mp3/m4a/wav/...) with at least a few seconds of clean speech.
- **Named voices**: each recording becomes a 192-dimension speaker embedding (MECT-B2) you can
  rename, replay (reference), or delete.
- **Speak**: pick a voice, type text, hear it. 43 languages supported by the model, including
  Hebrew and English; the app detects Hebrew input automatically.
- **Streaming playback** (audio starts as soon as the first frames decode) and **MP3 export/share** (128 kbps, LAME 3.100 built into `libvoicerepsmp3.so` by `native/mp3/build_mp3.sh`).

## How it works

- The native engine is Zyphra's C++/ggml runtime (`servers/ggml` of
  [Zyphra/ZONOS3-Mini](https://github.com/Zyphra/ZONOS3-Mini), MIT-licensed code), cross-compiled
  for `arm64-v8a` (`armv8.2-a+dotprod`, NDK r27) as `libzonos3jni.so` (~0.9 MB stripped), with a thin
  JNI bridge (`com.zyphra.zonos3.Zonos3`). Only the core synthesis + speaker-encoder libraries are
  linked - the HTTP server and its Rust text-normalizer are not needed on device
  (`ZONOS3_SERVER=OFF`, a one-option patch on top of upstream).
- On first launch the app downloads four GGUF files (~1.2 GB total) from the public Hugging Face
  repo - no token required:
  - `backbone-ptq.gguf` (663 MB) - the backbone, Q4 experts / Q8 mixers (~100M active params/token)
    (upstream renamed it from `zonos3-ptq.gguf` on 2026-11-11; 0.1.1 follows the new name and
    migrates an already-downloaded local copy)
  - `zac-keep2.gguf` (452 MB) - the ZAC-1 waveform decoder (44.1 kHz)
  - `spk-encoder.gguf` (45 MB) - the MECT-B2 speaker encoder used to build voice profiles
  - `steer.gguf` (0.2 MB) - steering directions behind the expression controls
- **Expression controls** (Speak tab): an emotion preset (Neutral, Happy, Sad, Angry, Calm,
  Excited, Whisper) plus Pitch, Expression and Pace sliders. These map to the engine's steering
  directions (`emotions.*`, `delivery.pitch`, `expressive.expressive`, `delivery.tempo`, ...),
  resolved through `steer.gguf` at generation time; doses stay inside the studio bundle's safe
  ranges. The emotion/delivery directions are hidden-space, so they also work with the default
  voice; raw-space directions would move the speaker embedding and apply to saved voices only.
- Voice profiles are stored locally (`files/voices/`): the reference WAV + raw float32 embedding.

## 0.4.0: Auto emotions and playback controls

Update the existing installation, without uninstalling, to keep saved voices and voice models.

1. Open **Speak**, choose a voice and enter text.
2. For automatic delivery, check **Auto emotions**. This disables the manual emotion
   chips and Pitch / Expression / Pace sliders. Uncheck it to use the manual controls again.
3. The first Speak or Save & share MP3 with Auto enabled downloads a **268.4 MB** int8
   model from Hugging Face. Wi-Fi and free storage are recommended. The tokenizer is
   bundled in a compact 12.3 MB binary in the APK (smaller after APK compression),
   rather than requiring the original 34.4 MB JSON download. Manual mode needs no new model.
4. Detection runs on your phone and shows its label and chosen speaking preset. The
   same detected controls apply to live Speak and MP3 export. No typed text or audio is uploaded.
5. During Speak playback, use **Pause**, then **Resume**, or **Stop**. Stop silences and
   discards buffered playback immediately. Before audio begins it cancels the request
   at the next model/generation checkpoint. Speak stays disabled while that background
   step exits, so large model loads cannot overlap. Leaving the screen also stops playback.

The chosen classifier is [Horizon-Labs/multilingual-emotions-small](https://huggingface.co/Horizon-Labs/multilingual-emotions-small),
Apache-2.0, pinned to `ac26dadbdafc26276e01093790104679bf33aec5`, with a SHA-256-verified
ONNX download. It runs with ONNX Runtime Android 1.23.2 CPU. The original tokenizer's
vocabulary, byte fallback, added tokens and ranked BPE merges are preserved in
`emotion-tokenizer.bin`; `tools/compile_emotion_tokenizer.py` regenerates it from the
pinned upstream JSON. Model and runtime licenses are included in app assets.

Auto applies one delivery preset to the **whole input**. Inputs longer than the model's
256-token training window are analyzed in 254-content-token sections (plus BOS/EOS),
then scores are averaged by content-token count. No text is silently dropped. Auto has
an explicit 100,000-character limit; split larger text or switch to manual. This does
not change the TTS engine's own existing utterance limits. Joy-related labels map to
Happy, sadness-related labels to Sad, anger/annoyance to Angry, excitement to Excited.
A conservative 0.5 minimum and unsupported emotions use Neutral. Scores do not alter
steering strength. Calm and Whisper remain manual choices. Labels can still be wrong,
especially for sarcasm, negation and mixed emotions. Hebrew training data was translated,
so this is not a claim of benchmarked native-Hebrew accuracy.

To reduce peak memory, Auto releases the cached TTS engine before classification and
closes the ONNX session before reloading TTS. This trades some reload time for memory.
Physical-phone latency, audio routing and memory behavior still need device testing.
The existing GGUF models, saved voice files, MP3 encoder and keyboard inset ownership
are unchanged.

### Tests for this update

`./gradlew testDebugUnitTest` covers tokenizer equality with 217 pinned Hugging Face
fixtures (Hebrew, English, Unicode, special tokens and long text), lossless windowing,
conservative mapping, disabled manual controls, pause/resume/stop, and existing
voice/recreation/keyboard regressions. UI and AudioTrack checks are Robolectric host
simulations on APIs 28 and 34, not physical-phone tests. The MP3 native test skips
when its host library is not available; the existing arm64 MP3 binary is unchanged.

An optional real-weight smoke test runs the actual app classifier with Linux ONNX Runtime
1.23.2 native libraries and the pinned ONNX file, without a network request or private input:

```sh
VOICE_REPS_EMOTION_TEST_MODEL=/path/to/model_quantized.onnx \
VOICE_REPS_ORT_HOST_LIBS=/path/to/linux-x64-ort-libraries \
./gradlew testDebugUnitTest --tests com.voicereps.app.EmotionModelSmokeTest
```

Run the real-weight test separately from the full UI suite to keep host test memory low.
This passed on synthetic English/Hebrew joy and sadness, negated anger (Neutral fallback),
and a three-window long input. It does not prove arm64 Android model execution or listening quality.

## Build

Prereqs: JDK 17, Android SDK 36 (platform + build-tools), Gradle 8.11.1 (included wrapper). The prebuilt
`libzonos3jni.so` ships in `app/src/main/jniLibs/arm64-v8a/`, so a plain

```bash
gradle assembleDebug    # or: ./gradlew assembleDebug
```

produces `app/build/outputs/apk/debug/app-debug.apk`.

### Signing (required for debug and release builds)

Every build must use the same private signing key so Android accepts updates over an
installed copy. Both `assembleDebug` and `assembleRelease` use one persistent PKCS#12
keystore; Gradle deliberately fails rather than silently using a sandbox-specific debug key.
Keep the keystore and its passwords outside the repository and provide them to the build
process through these environment variables:

- `VOICE_REPS_KEYSTORE_FILE`: path to the private `.p12` keystore file
- `VOICE_REPS_STORE_PASSWORD`: keystore password
- `VOICE_REPS_KEY_ALIAS`: signing-key alias
- `VOICE_REPS_KEY_PASSWORD`: key password

Example (set the values from the approved secure store, not shell history or a checked-in
file):

```bash
export VOICE_REPS_KEYSTORE_FILE="$HOME/.config/voice-reps/signing.p12"
export VOICE_REPS_STORE_PASSWORD="..."
export VOICE_REPS_KEY_ALIAS="voice-reps"
export VOICE_REPS_KEY_PASSWORD="..."
./gradlew assembleDebug    # or assembleRelease
```

Do not put the keystore or passwords in Git, chat, build logs, or APK metadata. A new key
cannot update APKs signed by a different key; users must uninstall those older builds first,
which deletes the app's private model and voice-profile data.

### Rebuilding the native library from source (optional)

```bash
git clone --depth 1 https://github.com/Zyphra/ZONOS3-Mini.git
cd ZONOS3-Mini && git submodule update --init --depth 1 servers/ggml/ggml
# 1. Make the server optional in servers/ggml/CMakeLists.txt: add
#    option(ZONOS3_SERVER "..." ON) and guard the zonos3-cli + HTTP-server
#    (cargo/zyphra-textnorm) blocks with if(ZONOS3_SERVER) ... endif()
#    (the exact patch used for this build is in native/ZONOS3_SERVER-option.patch)
# 2. Copy native/jni/zonos3_jni.cpp next to a small wrapper CMakeLists (native/CMakeLists.txt here)
cmake -S native -B build-arm64 \
  -DCMAKE_TOOLCHAIN_FILE=$ANDROID_NDK/build/cmake/android.toolchain.cmake \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-28 \
  -DCMAKE_BUILD_TYPE=Release -DGGML_NATIVE=OFF -DGGML_CPU_ARM_ARCH="armv8.2-a+dotprod" \
  -DBUILD_SHARED_LIBS=OFF -DGGML_OPENMP=OFF \
  -DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON   # 16 KB page alignment (Play requirement)
cmake --build build-arm64 -j --target zonos3jni

BUILD_SHARED_LIBS=OFF is required: it statically links ggml into libzonos3jni.so so the APK
ships a single self-contained library that only needs Android system libs. (0.1.0 linked ggml
dynamically and the APK was missing libggml*.so, so every native call crashed.)
GGML_OPENMP=OFF avoids a runtime dependency on libomp.so, which Android does not provide.
llvm-strip build-arm64/libzonos3jni.so -o app/src/main/jniLibs/arm64-v8a/libzonos3jni.so
```

Device requirements: Android 9+ (API 28), arm64 with ARMv8.2 dot-product instructions
(virtually every mid-range+ phone since ~2019), ~1.5 GB free storage, ~1.5 GB free RAM while
generating.

## Changelog

- 0.3.0 (2026-11-15): "Save & share" now exports MP3 (on-device LAME 3.100 encoding) instead of WAV.
- 0.2.2 (2026-11-15): Fix oversized bottom navigation while the keyboard is open.
  The activity now applies and consumes system-bar and keyboard insets once, so
  Material bottom navigation cannot apply the keyboard height as padding again.
  Regression tests dispatch actual legacy and modern window insets, check navigation
  height through keyboard open/close cycles, and scroll/edit the text field.
- 0.2.1 (2026-11-15): fix Speak tab on Android 15+/API 36: the screen now scrolls and the text
  box keeps a usable height while the keyboard is open (it collapsed to zero with edge-to-edge).
- 0.2.0 (2026-11-15): Google Play readiness, no feature changes. targetSdk/compileSdk 36
  (AGP 8.10.1, Gradle 8.11.1) with edge-to-edge insets handling; libzonos3jni.so rebuilt
  with NDK r27c and 16 KB page alignment (`-Wl,-z,max-page-size=16384`); new "Report
  offensive or harmful audio" action on the Speak tab (opens an email to the developer
  with app/device/voice/text context and the error log); release `.aab` for Play upload.
- 0.1.3 (2026-11-12): reuse restored fragments after Android recreates the activity,
  instead of stacking duplicate Speak screens. Remember the chosen saved voice. A new
  playback session is opened only once audio is ready, requests media audio focus, checks
  media mute state, handles partial/failed writes and drain timeouts, and rejects empty,
  silent or invalid PCM. Leaving Speak stops its player and invalidates old UI callbacks;
  the next Speak starts a fresh audio session. Speak and WAV export cannot race over the
  shared native speaker state. Failed model initialization is not cached as a ready engine.
  Failures appear in a dialog and the existing on-device crash log.
  Native streaming now counts discarded prefill separately from delivered speech, bounds
  tail slicing, checks callback failure/short output and applies the same gain as WAV export.
  Rebuilt the bundled arm64 library. See `tests/README.md` for verification and limits.

- 0.1.2 (2026-11-12): playback no longer drops the tail of the speech - 0.1.1 stopped and
  released the AudioTrack 300 ms after the last chunk was written while up to the whole 1 s
  stream buffer was still queued, cutting the end of the utterance (the missing last word);
  the player now waits for the written frames to actually play out before stopping.
  Expression controls on the Speak tab: emotion presets (Happy, Sad, Angry, Calm, Excited,
  Whisper) and Pitch/Expression/Pace sliders, driven by the engine's steering directions
  (new 0.2 MB `steer.gguf` model file; existing installs fetch just that file).
- 0.1.1 (2026-11-12): crash fix - statically link ggml into libzonos3jni.so (0.1.0's APK was
  missing libggml.so/libggml-cpu.so/libggml-base.so, so the first native call -
  create voice or speak - killed the app); backbone download URL updated to the renamed
  upstream file; voice recordings are persisted before the embedding step and can be finished
  from the Voices tab after an interruption; native errors are surfaced as messages instead of
  crashes; step-by-step logcat logging (tags Zonos3JNI/Zonos3Engine) and an on-device crash log
  shown on next launch.
- 0.1.0 (2026-11-11): first build.

## Verified / not verified

- Host-tested end-to-end with the released weights (x86_64, CPU): English and Hebrew synthesis,
  blocking and streaming, produce clean non-silent 44.1 kHz audio. Backbone generation runs
  faster than real time on one server core; the waveform decoder is the slower stage, so first
  audio on a phone lands in a few seconds for short sentences.
- Not yet verified: on-device behavior (no emulator/device in the build environment) - first
  launch, recording flow, and Hebrew quality on a real phone should be checked by installing
  the APK.

## Privacy & consent

Record only people who explicitly agreed. Generated audio is a replica of a real person's
voice - treat it with the same care as a recording of them. All data stays on the device.

## Licenses

- App and JNI wrapper: MIT (this repo).
- ZONOS3-Mini runtime code: MIT (Zyphra). Model weights: Apache 2.0 (Zyphra).
- ggml: MIT (ggml-org).
