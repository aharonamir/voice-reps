# 0.4.0 verification, 2026-11-15

- Version 0.4.0 / code 11, arm64-v8a, min API 28, target/compile API 36.
- `testDebugUnitTest`: 22 test cases, 19 passed, 3 conditional skips (two unchanged
  MP3 host-native tests; optional real-weight emotion smoke test). No test failures.
- Real-weight smoke test ran separately and passed with the app's actual EmotionModel
  and EmotionTokenizer Kotlin classes under Robolectric API 28, using Linux ONNX
  Runtime 1.23.2 JNI, not the Android arm64 library. Seven synthetic inputs:
  - English and Hebrew happy: joy / Happy
  - English and Hebrew sad: sadness / Sad
  - English and Hebrew negated anger: top label anger / conservative Neutral fallback
  - Repeated happy English, 2,700 characters: excitement / Excited, all 3 windows used
- Tokenizer matches 217 original Hugging Face tokenizer outputs, including Hebrew,
  English, Arabic, CJK, emoji, accents, added tokens, whitespace and a 10,000-character
  single word. Window test reconstructs all 5,001 original content tokens exactly.
- Host-simulated AudioTrack tests, APIs 28/34: Pause changes track to PAUSED, Resume
  to PLAYING, Stop releases a paused writer within 1 second, fresh track starts unpaused.
- UI tests, APIs 28/34: checkbox disables all preset chips and three sliders; automatic
  controls exclude saved manual slider values; manual mode restores controls. Resume
  and Stop remain reachable above the simulated keyboard inset. Rendered UI inspected
  at 393dp x 800dp with keyboard closed and open; no clipping or doubled IME padding.
- Existing saved-voice, recreation, PCM and keyboard regression tests passed.
- Existing `libzonos3jni.so` and `libvoicerepsmp3.so` bytes are unchanged. All four
  packaged native libraries have ELF LOAD alignment 0x4000. APK zipalign -P 16 passes.
- APK apksigner verification passes, certificate SHA-256:
  `94fa514536c06d65cd0879c6d9741437eeb16ca4af7f0beffb3aa10351a8d218`.
- AAB passes bundletool 1.18.3 validation and JAR signature verification with the
  same certificate. Self-signed-certificate and streaming-JAR ordering warnings
  are emitted by jarsigner; exit is successful.

## Not verified

No physical Android phone, arm64 Android ONNX execution, actual model download on a
phone, combined TTS/emotion memory benchmark, listening quality, Bluetooth or
long-utterance TTS continuity was tested here. Host smoke tests do not cover these.
The existing TTS engine may have its own long-utterance limits. Emotion classification
can still be wrong; especially negation, sarcasm, mixed emotions and translated Hebrew.
