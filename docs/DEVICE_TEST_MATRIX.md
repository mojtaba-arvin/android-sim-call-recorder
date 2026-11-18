# Device validation matrix

No physical-device recording results have been claimed. Use the in-app Device diagnostics screen to capture the device profile, visible SIM slots, per-subscription call-state events, and available audio endpoints. Transfer the observed results into this matrix after each handset test.

| API level | Android | Device / build | SIM setup | SIM 1 states | SIM 2 states | Input/output route | Local speech | Remote speech | Background behavior | Result |
|---:|---|---|---|---|---|---|---|---|---|---|---|
| 26 | 8.0 | Pending | Pending | Pending | Pending | Pending | Pending | Pending | Pending | Not tested |
| 27 | 8.1 | Pending | Pending | Pending | Pending | Pending | Pending | Pending | Pending | Not tested |
| 28 | 9 | Pending | Pending | Pending | Pending | Pending | Pending | Pending | Pending | Not tested |
| 29 | 10 | Pending | Pending | Pending | Pending | Pending | Pending | Pending | Pending | Not tested |
| 30 | 11 | Pending | Pending | Pending | Pending | Pending | Pending | Pending | Pending | Not tested |
| 31 | 12 | Pending | Pending | Pending | Pending | Pending | Pending | Pending | Pending | Not tested |
| 32 | 12L | Pending | Pending | Pending | Pending | Pending | Pending | Pending | Pending | Not tested |
| 33 | 13 | Pending | Pending | Pending | Pending | Pending | Pending | Pending | Pending | Not tested |
| 34 | 14 | Pending | Pending | Pending | Pending | Pending | Pending | Pending | Pending | Not tested |
| 35 | 15 | Pending | Pending | Pending | Pending | Pending | Pending | Pending | Pending | Not tested |
| 36 | 16 | Pending | Pending | Pending | Pending | Pending | Pending | Pending | Pending | Not tested |
| 37 | 17 | Pending | Pending | Pending | Pending | Pending | Pending | Pending | Pending | Not tested |

## Procedure per physical device

1. Install the debug APK. Grant call-state permission; grant RECORD_AUDIO only when the best-eligible selector must use microphone/acoustic capture. Confirm participant authorization/consent. Connect/authorize Shizuku when available. Confirm the live UserService has both shell audio attribution and the protected CAPTURE_AUDIO_OUTPUT permission; UID 2000 alone is not sufficient. Then arm best-eligible automatic recording from the visible app before a call.
2. Open Device diagnostics and record manufacturer, model, Android release, API level, build display, and security patch.
3. Confirm the visible SIM slot count and labels. Do not record phone numbers, ICCIDs, or subscriber identifiers.
4. Start call-state monitoring while the app is visible.
5. Place an incoming call to SIM 1. Verify RINGING, OFFHOOK, and IDLE; repeat with the opposite party ending the call.
6. Place an outgoing call using SIM 1. Verify call-state transitions. Repeat for SIM 2 when present.
7. Repeat incoming and outgoing scenarios on SIM 2. Record call waiting and dual-SIM standby behavior separately.
8. Refresh the audio inventory with earpiece, loudspeaker, wired headset, and Bluetooth routes connected one at a time.
9. Do not infer audio capture capability from the device inventory, AudioRecord initialization, or call-state success. For best-eligible automatic validation, connect/authorize Shizuku first, then arm from the visible Diagnostics UI before the call. The app fixes Shizuku VOICE_CALL only when the live helper UID, CAPTURE_AUDIO_OUTPUT permission, and shell audio attribution are all confirmed; otherwise it selects Speakerphone + MIC when RECORD_AUDIO is granted. For isolated strategy tests, select the explicit mode and start it manually during OFFHOOK. Record the exact selected strategy and actual source label.
10. On Android 17, verify that monitoring/recording is started from the visible app and remains a non-short foreground service when the app is backgrounded or the screen turns off. Record any AudioHardening or foreground-service policy diagnostics.
11. Speak a unique test phrase at the local end and a different phrase at the remote end. Verify each phrase independently in the finalized WAV. Repeat at least three incoming and three outgoing calls for each strategy/route combination before marking two-way support. Do not upload call audio to issue reports.

## Required audio acceptance criteria

A device/route is not supported unless all of these are observed:

- The source is available to the selected backend under the device's actual OS policy and verified privilege identity (ordinary app UID, Shizuku shell UID 2000, or root UID 0).
- Recording starts and stops without a permission or foreground-service policy violation.
- The caller's phrase and remote party's phrase are both intelligible in the saved file.
- The result is repeatable for at least three incoming and three outgoing calls.
- Headset and Bluetooth routes are explicitly tested or marked unsupported.
- File finalization, metadata consistency, interruption recovery, and deletion are verified.

## Test scenarios

- Permission denied, revoked in Settings, and granted after process recreation.
- App foreground/background, screen off, battery saver, OEM power saver, process recreation, and force-stop.
- Single-SIM, dual-SIM, eSIM where available, SIM disabled/enabled, and active-subscription changes.
- Incoming call, outgoing call, rejected call, unanswered call, remote hang-up, local hang-up, and call waiting.

A report must include the exact device model, OS build, carrier/network mode, SIM slot, source configuration, helper UID where applicable, audio route, test repetition count, phrase-intelligibility result, and reproducible notes. Do not include phone numbers, SIM identifiers, call recordings, or other personal call content in issue reports.
