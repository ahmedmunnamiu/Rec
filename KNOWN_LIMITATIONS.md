# Known limitations

These are properties of Android, not bugs to be fixed in this app.

1. **No call-stream capture for normal apps (Android 10+).** `VOICE_CALL`, `VOICE_UPLINK`, `VOICE_DOWNLINK`,
   `REMOTE_SUBMIX` need `CAPTURE_AUDIO_OUTPUT` (signature permission). ADB cannot grant it. Only some custom ROMs or
   system-signed builds allow it. Expect microphone-only recording.
2. **Microphone recording during calls is often silenced.** From Android 9 many devices give background apps silent
   audio while the telephony stack holds the microphone. Results vary by manufacturer; the Engine Test Center shows
   what your phone does and a "failed" recording is logged when no audio arrives.
3. **The other person is only heard on speakerphone**, and then at lower quality with echo and room noise.
4. **Android 12+ background restrictions** can prevent the microphone service from starting from the background.
   Battery and overlay exemptions help but are not guaranteed.
5. **Call numbers**: Android 9+ delivers the caller's number only with `READ_CALL_LOG`. Outgoing numbers are read from the
   call log after the call; without it they show as Unknown (and number-based rules cannot be applied during the call).
6. **Bluetooth**: with call audio routed to a headset, the phone microphone usually cannot carry the call.
7. **MP3** cannot be recorded (no platform encoder). AAC/M4A is the compact alternative.
8. **Noise suppression, echo cancellation, AGC and gain** apply only to the WAV path.
9. **Interrupted M4A/3GP files** can be unplayable (the MP4 index is written at the end). Recovered files are labelled
   "may be incomplete". WAV is the most crash-tolerant format.
10. **Transcription** needs Android 13+ and an installed offline speech model; long calls may fail.
11. **App lock** hides the app's screens but recordings are normal files readable by anything with access to
    `Android/media`. Use the Private folder and device encryption for more protection. Backup ZIPs are not encrypted.
12. **Custom storage folders** (Storage Access Framework): such recordings cannot be moved to the Private folder from the app.
13. **Root engine** is experimental and depends on a `tinycap` binary and device-specific mixer paths.
14. Multi-SIM, conference and VoIP (WhatsApp etc.) calls are not recorded: only cellular calls that trigger the
    system phone-state broadcast.

## Legal

Laws on recording calls differ by country and state; many require all parties' consent. You are responsible for
complying. The app never hides that it is recording.

## Build status

See CHANGELOG.md. The source was statically validated but **compiled only by your GitHub Actions run**; please report
any compile errors that run shows.
