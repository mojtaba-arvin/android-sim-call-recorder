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

Inject `SimCallRecorderSdk` from the host Hilt graph:

```kotlin
@AndroidEntryPoint
class RecorderHostActivity : ComponentActivity() {
    @Inject lateinit var recorderSdk: SimCallRecorderSdk
}
```

The facade exposes read-only StateFlow streams for recording status, automatic-monitoring state, audio-input diagnostics, Shizuku connection status, recent call-state events, recordings, and settings. It also provides suspend operations for querying SIM slots, observing/filtering recordings, deleting a recording, and updating settings.

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
