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
- **Streaming playback** (audio starts as soon as the first frames decode) and **WAV export/share**.

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

## Build

Prereqs: JDK 17, Android SDK 34 (platform + build-tools), Gradle 8.9. The prebuilt
`libzonos3jni.so` ships in `app/src/main/jniLibs/arm64-v8a/`, so a plain

```bash
gradle assembleDebug    # or: ./gradlew assembleDebug
```

produces `app/build/outputs/apk/debug/app-debug.apk`.

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
  -DBUILD_SHARED_LIBS=OFF -DGGML_OPENMP=OFF
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
