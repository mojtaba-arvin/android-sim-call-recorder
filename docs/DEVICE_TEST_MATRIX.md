# Device validation matrix

No physical-device recording results have been claimed. Use the in-app Device diagnostics screen to capture the device profile, visible SIM slots, per-subscription call-state events, and available audio endpoints. Transfer the observed results into this matrix after each handset test.

| API level | Android | Device / build | SIM setup | SIM 1 states | SIM 2 states | Input/output route | Local speech | Remote speech | Background behavior | Result |
|---:|---|---|---|---|---|---|---|---|---|---|
| 28 | 9 | Pending | Pending | Pending | Pending | Pending | Pending | Pending | Pending | Not tested |
| 30 | 11 | Pending | Pending | Pending | Pending | Pending | Pending | Pending | Pending | Not tested |
| 31 | 12 | Pending | Pending | Pending | Pending | Pending | Pending | Pending | Pending | Not tested |
| 33 | 13 | Pending | Pending | Pending | Pending | Pending | Pending | Pending | Pending | Not tested |
| 34 | 14 | Pending | Pending | Pending | Pending | Pending | Pending | Pending | Pending | Not tested |
| 35 | 15 | Pending | Pending | Pending | Pending | Pending | Pending | Pending | Pending | Not tested |
| 36 | 16 | Pending | Pending | Pending | Pending | Pending | Pending | Pending | Pending | Not tested |
| 37 | 17 | Pending | Pending | Pending | Pending | Pending | Pending | Pending | Pending | Not tested |

## Procedure per physical device

1. Install the debug APK and grant only the in-app call-state permission.
2. Open Device diagnostics and record manufacturer, model, Android release, API level, build display, and security patch.
3. Confirm the visible SIM slot count and labels. Do not record phone numbers, ICCIDs, or subscriber identifiers.
4. Start call-state monitoring while the app is visible.
5. Place an incoming call to SIM 1. Verify RINGING, OFFHOOK, and IDLE; repeat with the opposite party ending the call.
6. Place an outgoing call using SIM 1. Verify call-state transitions. Repeat for SIM 2 when present.
7. Repeat incoming and outgoing scenarios on SIM 2. Record call waiting and dual-SIM standby behavior separately.
8. Refresh the audio inventory with earpiece, loudspeaker, wired headset, and Bluetooth routes connected one at a time.
9. Do not infer audio capture capability from the device inventory or from call-state success. No recorder is included in this diagnostic build.
10. A later, separately implemented and consent-gated audio probe must test independently spoken phrases from both ends. Both phrases must be audible in repeated samples before that device/build/route is marked as two-way supported.

## Required audio acceptance criteria

A device/route is not supported unless all of these are observed:

- The source is available to an ordinary third-party app under the device's actual OS policy.
- Recording starts and stops without a permission or foreground-service policy violation.
- The caller's phrase and remote party's phrase are both intelligible in the saved file.
- The result is repeatable for incoming and outgoing calls.
- Headset and Bluetooth routes are explicitly tested or marked unsupported.
- File finalization, metadata consistency, interruption recovery, and deletion are verified.

## Test scenarios

- Permission denied, revoked in Settings, and granted after process recreation.
- App foreground/background, screen off, battery saver, OEM power saver, process recreation, and force-stop.
- Single-SIM, dual-SIM, eSIM where available, SIM disabled/enabled, and active-subscription changes.
- Incoming call, outgoing call, rejected call, unanswered call, remote hang-up, local hang-up, and call waiting.

A report must include the exact device model, OS build, source configuration, audio route, test phrases/result, and reproducible notes. Do not include phone numbers, SIM identifiers, call recordings, or other personal call content in issue reports.
