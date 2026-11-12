# 0.1.3 return-to-app regression checks

## Android host tests

With the required signing environment and SDK/JDK configured:

```sh
./gradlew testDebugUnitTest
```

Robolectric checks activity recreation three times without duplicate screens, a cold
saved-state restoration with a persisted voice and byte-exact embedding round-trip,
and visible/logged playback errors. PCM checks reject no audio, silence and non-finite
samples. The recreation check was also run against the old MainActivity: it failed with
6 fragments rather than 3 immediately after the first recreation. The fixed version passes.

The tests render Speak after recreation and an error dialog to `/downloads/` in this
build environment. These are host-rendered layouts, not phone screenshots.

## Real-weight native check

Build provenance for the bundled 0.1.3 native library:

- ZONOS3-Mini upstream `3a294675a06dd496242e8dbdf2232116a874f67a`
- ggml submodule `a05c9922613c6a2230dbb1d0de679519ecafb4f0`
- Apply `native/ZONOS3_SERVER-option.patch` before configuring.
- Android NDK 27.2.12479018, arm64-v8a, android-28, static ggml,
  GGML_OPENMP=OFF, GGML_NATIVE=OFF, GGML_CPU_ARM_ARCH=armv8.2-a+dotprod.

```sh
cmake -S native -B build-host -DZONOS3_GGML_DIR=/path/to/ZONOS3-Mini/servers/ggml \
  -DCMAKE_BUILD_TYPE=Release -DGGML_NATIVE=OFF -DGGML_OPENMP=OFF
cmake --build build-host --target native_reentry
mkdir -p /tmp/voice-reps-test-output
ZAC_THREADS=1 SPK_THREADS=1 build-host/native_reentry /path/to/gguf-models /tmp/voice-reps-test-output
```

Uses the released backbone-ptq, zac-keep2, spk-encoder and steer weights. Generates
non-silent speech, checks streaming vs blocking/WAV duration parity, creates a real
192-d speaker embedding, persists it, frees the engine, reloads a fresh engine, restores
the embedding and speaks twice. All PCM must be finite with peak > 0.00001.

Observed: default stream and WAV both 82,320 samples, peak 0.075528, RMS 0.010644;
restored voice and repeated Speak both 73,500 samples, peak 0.669183, RMS 0.179692.

## Limits

No physical Android device or accelerated emulator was available. Host tests do not
prove the phone's audio routing, Bluetooth, audio focus or low-memory behavior. The
reported symptom's exact phone-side cause is not confirmed without phone logs. The
activity duplication is reproduced, and all listed corrected paths are tested as stated.

Install 0.1.3 over the permanent-key 0.1.2 build. Do not uninstall: in-place updates keep
models and saved voices. The signing certificate must remain
`94fa514536c06d65cd0879c6d9741437eeb16ca4af7f0beffb3aa10351a8d218`.
