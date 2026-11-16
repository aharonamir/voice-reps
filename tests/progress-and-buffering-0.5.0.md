# 0.5.0 progress indicators and jitter buffer

Host tests only (JVM/Robolectric). Nothing here was measured on a physical phone.

- Engine and speaker-encoder load progress = bytes this process has read (`/proc/self/io` rchar) divided by the model file sizes. The native loaders read the files with fread; verified on Linux that fread advances rchar by the bytes read. Capped at 99% until the native call returns. If the file is unreadable on a device, the bar is indeterminate with elapsed time only. Not verified on Android.
- Steps without a measurable total (speaker analysis, WAV render, first-audio wait) show an indeterminate bar and elapsed time.
- Playback: generator -> JitterBuffer queue -> writer thread -> AudioTrack. Starts after a prebuffer, pauses cleanly and refills (with a larger target) if the queue runs dry before generation finishes. Modes: Auto (adapts, remembers what the phone needed), Extra (6 s), Whole text first.
- A buffer cannot make generation faster. If a phone generates slower than real time, expect refill pauses (fewer with Whole text first).
- JitterBufferTest simulates producer stalls in virtual time; it is not a weak-phone measurement.
