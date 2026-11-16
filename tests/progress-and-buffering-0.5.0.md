# 0.5.0 progress indicators and jitter buffer

Host tests only (JVM/Robolectric). Nothing here was measured on a physical phone.

- Engine and speaker-encoder load progress is APPROXIMATE: growth of this process's total bytes-read counter (/proc/self/io rchar) relative to the model file sizes, shown as a rounded "~N%" with elapsed time only (no exact MB or ETA), capped at 99%. The counter is process-wide, so other reads can inflate it. Falls back to an indeterminate bar with elapsed time if unreadable. Not verified on Android.
- Steps without a measurable total (speaker analysis, WAV render, first-audio wait) show an indeterminate bar and elapsed time.
- Playback: generator -> JitterBuffer queue -> writer thread -> AudioTrack. Starts after a prebuffer, pauses cleanly and refills (with a larger target) if the queue runs dry before generation finishes. Modes: Auto (adapts, remembers what the phone needed), Extra (6 s), Whole text first.
- A buffer cannot make generation faster. If a phone generates slower than real time, expect refill pauses (fewer with Whole text first).
- JitterBufferTest simulates producer stalls in virtual time; it is not a weak-phone measurement.
