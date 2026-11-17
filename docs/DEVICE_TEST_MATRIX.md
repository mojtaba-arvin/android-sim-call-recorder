# Device validation matrix

No hardware recording results have been claimed yet. Fill this matrix with observed results before marking a device supported.

| API level | Android | Device / build fingerprint | SIM setup | Call state | Local audio | Remote audio | Background / screen-off | Result |
|---:|---|---|---|---|---|---|---|---|
| 28 | 9 | Pending | Pending | Pending | Pending | Pending | Pending | Not tested |
| 30 | 11 | Pending | Pending | Pending | Pending | Pending | Pending | Not tested |
| 31 | 12 | Pending | Pending | Pending | Pending | Pending | Pending | Not tested |
| 33 | 13 | Pending | Pending | Pending | Pending | Pending | Pending | Not tested |
| 34 | 14 | Pending | Pending | Pending | Pending | Pending | Pending | Not tested |
| 35 | 15 | Pending | Pending | Pending | Pending | Pending | Pending | Not tested |
| 36 | 16 | Pending | Pending | Pending | Pending | Pending | Pending | Not tested |
| 37 | 17 | Pending | Pending | Pending | Pending | Pending | Pending | Not tested |

## Per-device scenarios

- Incoming call: ringing, answer, sustained conversation, remote hang-up.
- Incoming call: local hang-up.
- Outgoing call: dialing, remote answer, sustained conversation, either-side hang-up.
- Dual SIM: calls on each subscription, SIM switch, inactive SIM, call waiting where supported.
- Audio routes: earpiece, speakerphone, wired headset, Bluetooth.
- Runtime permission denied, revoked in Settings, and granted after process recreation.
- App foreground/background, screen off, battery saver, OEM power saver, process recreation, and force-stop.
- Repeat each supported recording scenario with a known phrase spoken independently by each side. Verify both phrases are audible in the saved file.

A test result must include the exact device model, OS build, source configuration, and a reproducible test note. "Recorder started" is not a pass criterion.
