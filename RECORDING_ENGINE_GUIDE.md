# Recording engine guide

Every engine is a separate class in `engine/` (`Engines.kt`, `root/RootEngine.kt`). The Engine Test Center records real
audio, analyses it, and stores the verdict. The recommended engine is chosen only from those stored results.

## Engines

| | Engine | Source | Expected on a normal phone | Notes |
|---|---|---|---|---|
| A | VOICE_CALL | `VOICE_CALL` | Restricted | Both sides, needs `CAPTURE_AUDIO_OUTPUT`. Usually opens but returns silence or errors |
| B | VOICE_UPLINK | `VOICE_UPLINK` | Restricted | Your voice only |
| C | VOICE_DOWNLINK | `VOICE_DOWNLINK` | Restricted | Remote party only |
| D | MIC | `MIC` | Works (mic only) | Remote side only audible via speaker |
| E | VOICE_RECOGNITION | `VOICE_RECOGNITION` | Works (mic only) | Speech-tuned; often the cleanest |
| F | VOICE_COMMUNICATION | `VOICE_COMMUNICATION` | Works (mic only) | Echo cancellation / noise suppression by the platform |
| G | DEFAULT | `DEFAULT` | Works | Last-resort fallback |
| H | CAMCORDER | `CAMCORDER` | Usually | Mic oriented for video |
| I | UNPROCESSED | `UNPROCESSED` | If advertised | Skipped when the phone does not advertise support |
| J | REMOTE_SUBMIX | `REMOTE_SUBMIX` | Restricted | Detection only; not attempted without `CAPTURE_AUDIO_OUTPUT` |
| K | SPEAKERPHONE | `MIC` + loudspeaker | Works with consent | Routes the call to the speaker, restores it afterwards; only with your explicit switch |
| L | BLUETOOTH | BT SCO/LE input | Only with a BT mic | Warns when Bluetooth routing prevents reliable capture |
| M | ADB | - | Setup module | Grants ordinary permissions; cannot grant privileged ones; cannot record |
| N | ROOT | tinycap | Optional | Experimental, needs root **and** a `tinycap` binary; isolated in `root/` |

"Expected" is a prior, not a result. Your phone may differ, which is why the tests exist.

## How a verdict is decided (`EngineVerdict`)

1. The engine is opened and **real PCM audio** is captured (5 s per phase) and analysed: peak, RMS, clipping %,
   silence %. Opening the recorder successfully proves nothing.
2. **Guided call test** (needs an active call and your consent): phase 1 the other person talks and you stay silent,
   phase 2 you talk and they stay silent.
3. A call source is **two-way verified only if both phases contain audio**. Only the remote phase = downlink only;
   only the local phase = uplink only.
4. Microphone-type engines are **never** reported as two-way, even if the speaker made both people audible.
5. Silence on a call source outside a call is "not tested", during a call it is "restricted".
6. Findings are flagged for silence, low volume, clipping (> 0.5 %) and likely distortion (> 2 %).

## Selection algorithm (`EngineSelector`)

1. Any engine with **verified two-way** capture (VOICE_CALL preferred).
2. A call-audio source that delivered one side (downlink, then uplink) or unverified call audio.
3. SPEAKERPHONE if it tested OK **and** you enabled speakerphone routing.
4. The microphone engine with the best measured score (level up, clipping and silence down).
5. DEFAULT microphone.

You can override this with **Use this engine**. During a call, if the chosen engine fails to start or dies, the
service falls back through working microphone engines (when *automatic retry* is on). Each recording stores the
engine and the capture mode that was **tested**, so the UI never claims more than was proven.

## Formats

Recorded with `MediaRecorder` (M4A/AAC, 3GP, AMR-NB, AMR-WB) or `AudioRecord` (WAV, which also enables gain,
noise suppression, echo cancellation and AGC). MP3 is listed but disabled: Android includes no MP3 encoder.
Sample rates and bitrates are checked against `MediaCodecList` before use and snapped (and reported) if unsupported.
