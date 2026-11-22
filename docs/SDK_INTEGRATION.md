# SDK integration

The reusable recording engine is the `:callrecorder-sdk` Android Library module. The `:app` module is the sample consumer, Compose UI, playback host, and diagnostics application.

## Requirements

- JDK 21 and Android SDK Platform 37
- minSdk 26 (experimental compatibility floor; validate on target OEM devices)
- Hilt in the host app and an `@HiltAndroidApp` Application

## Gradle

```kotlin
dependencies {
    implementation(project(":callrecorder-sdk"))
}
```

The SDK contributes its Hilt bindings and merged Android manifest declarations for permissions, Room-backed persistence, Shizuku provider, telephony listeners, accessibility/notification listeners, and recording foreground service. The host app owns the Hilt application component and runtime permission UX.

## Public facade

Inject `SimCallRecorderSdk` from the host Hilt graph. For integration code that does not use field injection, the SDK publishes `SimCallRecorderSdkEntryPoint` from its library module; this is the supported Hilt entry point and avoids recreating an app-local entry point:

```kotlin
@AndroidEntryPoint
class RecorderHostActivity : ComponentActivity() {
    @Inject lateinit var recorderSdk: SimCallRecorderSdk
}
```

The facade exposes read-only StateFlow streams for recording status, automatic-monitoring state, audio-input diagnostics, Shizuku connection status, recent call-state events, recordings, and settings. It also provides suspend operations for querying SIM slots, observing/filtering recordings, deleting a recording, and updating settings.

```kotlin
val sdk = EntryPointAccessors.fromApplication(
    applicationContext,
    SimCallRecorderSdkEntryPoint::class.java,
).simCallRecorderSdk()
```

The sample app's instrumentation test exercises this public entry point against the real application Hilt graph.

```kotlin
lifecycleScope.launch {
    recorderSdk.recordedItems.collect { records ->
        // Render the current recording list.
    }
}
```

Use `createArmAutomaticRecordingIntent` only after the host UI has obtained the required Android permissions and explicit participant-consent confirmation. Start the returned intent with `ContextCompat.startForegroundService`. Use `createStopRecordingIntent` for the visible Stop action.

Capture backends, Room entities/DAOs, repositories, services, platform adapters, and Hilt modules use Kotlin `internal` visibility. The public integration surface is the `SimCallRecorderSdk` facade and the public domain models/recording-mode enum. The SDK does not promise a specific cellular audio route: Shizuku `VOICE_CALL` depends on shell/root permission and OEM audio-HAL behavior, while Speakerphone + MIC is acoustic capture.

## Validation

A green Robolectric/emulator run does not prove two-way cellular audio. Validate both participant voices in a real call on each target OEM, Android build, and carrier combination.

## Recording data protection and backup

The SDK writes WAV files under the host app's private `files/recordings/` directory and stores recording metadata in `databases/recordings.db`. The sample app disables Android backup and excludes app files, databases, and shared preferences from cloud backup and device transfer.

A host application integrating this SDK must make an explicit backup decision. For call recordings, the recommended default is to disable backup (`android:allowBackup="false"`) unless the product has a separately reviewed, consented export/backup design. If the host app enables backup for other data, its full-backup and Android 12+ data-extraction rules must explicitly exclude at least:

- `domain="file" path="recordings/"`
- `domain="database" path="recordings.db"` (and any associated Room/WAL/SHM files, preferably by excluding the database domain or the complete recordings database directory)
- `domain="sharedpref" path="secure_recording_state.xml"`

Apply exclusions to both cloud backup and device-to-device transfer. Verify the merged host-app manifest and backup rules; a library cannot unilaterally set the host application's `allowBackup` policy. Do not treat app-private storage as automatically excluded from backup.

The SDK stores no phone number or SIM subscription identifier in new recording rows. Host applications must not export WAV files, call metadata, consent state, or diagnostic logs to analytics/crash reporting without a separate privacy review and explicit user authorization.


For public API compatibility and release-tag semantics, see [SDK API and versioning policy](SDK_API_VERSIONING.md).
