# Installing the beta APK

Download `SIM-Call-Recorder-<version>.apk` from the [latest GitHub Release](https://github.com/mojtaba-arvin/android-sim-call-recorder/releases/latest). Each release also contains the SDK AAR and SHA-256 checksums.

The APK attached to beta releases is built from the tagged commit and signed with the GitHub Actions debug keystore. It is intended for sideloaded testing, not Play Store distribution. CI debug keystores are ephemeral; if Android refuses to update an older installation because its signing certificate differs, back up recordings, uninstall the old app, then install the new APK.

The current minSdk 26 is an experimental compatibility floor. Shizuku `VOICE_CALL` remains device/OEM/carrier dependent and is not a universal two-way recording guarantee. Speakerphone + MIC is acoustic capture and requires speakerphone; remote-party audibility is not guaranteed.

## Install

1. Download the versioned APK from GitHub Releases.
2. Allow installation from the browser or file manager you used.
3. Open the APK and install it.
4. Grant permissions only after reviewing their purpose.

## Shizuku setup

1. Install and start Shizuku separately.
2. On Android 11+, use Wireless debugging; on Android 10 and below, start it with a computer and USB ADB unless rooted.
3. Open SIM Call Recorder and select **Connect / authorize Shizuku**.
4. Approve the Shizuku request.
5. Arm automatic recording only after confirming authorization and all required participant consent.

## Validate a real call

Make a short test call with the other participant informed. Speak distinct phrases from both sides, stop the recording, and play it back. Confirm both phrases are audible before treating that device/firmware/carrier combination as compatible. Emulator and Robolectric tests cannot validate cellular audio routing.

For integration through the public `SimCallRecorderSdk` facade, see [SDK integration](SDK_INTEGRATION.md).
