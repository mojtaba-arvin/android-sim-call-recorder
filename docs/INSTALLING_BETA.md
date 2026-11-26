# Beta installation status

**No public beta APK is currently available.** The project has not published a GitHub Release. Physical-device call-audio acceptance, independent review, and production APK signing are still incomplete.

For internal development only, build a debug APK locally:

```bash
gradle --no-daemon :app:assembleDebug
```

The output is `app/build/outputs/apk/debug/app-debug.apk`. Install it with:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

This debug build is for development and controlled testing only. It is not a signed public beta, does not establish device compatibility, and must not be redistributed as a production release. CI APK artifacts are likewise test outputs, not published releases.

The current minSdk 26 is an experimental compatibility floor. Shizuku `VOICE_CALL` remains device/OEM/carrier dependent and is not a universal two-way recording guarantee. Speakerphone + MIC is acoustic capture and requires speakerphone; remote-party audibility is not guaranteed.

## Shizuku setup for internal testing

1. Install and start Shizuku separately.
2. On Android 11+, use Wireless debugging; on Android 10 and below, start it with a computer and USB ADB unless rooted.
3. Open SIM Call Recorder and select **Connect / authorize Shizuku**.
4. Approve the Shizuku request.
5. Arm automatic recording only after confirming authorization and all required participant consent.

## Validate a real call

Make a short test call with the other participant informed. Speak distinct phrases from both sides, stop the recording, and play it back. Confirm both phrases are audible before treating that exact device/firmware/carrier/route/backend combination as compatible. Emulator and Robolectric tests cannot validate cellular audio routing.

Follow the [physical-device acceptance protocol](PHYSICAL_DEVICE_ACCEPTANCE.md) for the required incoming/outgoing call count and independent Near-end/Far-end scoring.

For integration through the public `SimCallRecorderSdk` facade, see [SDK integration](SDK_INTEGRATION.md).
