# Installing beta 0.1.0-beta.1

This is a sideloadable **debug-signed beta build** for device evaluation. It is not a Play Store or production-signed release.

## Install

1. Download app-debug.apk from the build artifact attached to the project release message.
2. Transfer it to the Android phone and open the APK.
3. Allow installation from the file manager/browser when Android prompts.
4. Launch **SIM Call Recorder**.
5. Grant Phone permission when requested. Grant notification permission so the foreground recording notification and Stop action can be shown.
6. Open **Diagnostics** and inspect the available capture paths.

The app currently targets Android 16/API 36 and declares Android 8/API 26 as its minimum installation level. API 26 is still under compatibility validation.

## Capture setup

**Preferred experimental path — Shizuku**

1. Install Shizuku from its official distribution channel separately.
2. Start Shizuku using Wireless Debugging (Android 11+) or the appropriate ADB/root method for the device.
3. In the app's Diagnostics screen, connect/authorize Shizuku and approve its UserService request.
4. Arm **best available automatic recording** before placing or accepting a test call.
5. The selector chooses the Shizuku VOICE_CALL path only when the helper and shell audio attribution checks report ready.

**Fallback — Speakerphone + MIC**

If the privileged Shizuku path is unavailable, the app may select Speakerphone + MIC when microphone permission is granted. This is acoustic capture: enable speakerphone during the call and keep the phone microphone unobstructed. Echo, gain control, Bluetooth routing, and OEM audio processing can affect the result.

## Verify the recording

1. Use a short test call with the consent of all participants and in compliance with local law.
2. End the call normally and wait for finalization.
3. Open **Recordings**, select the newest completed item, and play it.
4. Check that both the near-end and remote-end test phrases are audible. A non-empty WAV file or a green CI run does not prove two-way audio.
5. If the recording is silent, one-sided, or missing, capture the Diagnostics screen and note the phone model, Android version, SIM route, and whether Shizuku was active.

## Known limitations

- Cellular two-way audio is **not yet validated across physical devices**. VOICE_CALL depends on privileged permission and OEM audio implementation; it may be denied or silent.
- Speakerphone + MIC is not a digital call-audio source and cannot guarantee the remote party is captured.
- The APK is debug-signed, intended for controlled sideload testing, and not suitable for Play Store publication.
- Do not use this build to record anyone without the legally required consent.