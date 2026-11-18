package com.mojtaba.simcallrecorder.ui

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.mojtaba.simcallrecorder.domain.model.CallRecord
import com.mojtaba.simcallrecorder.domain.model.CallRecordFilter
import com.mojtaba.simcallrecorder.domain.model.RecorderSettings
import com.mojtaba.simcallrecorder.recording.RecordingMode
import kotlinx.coroutines.delay
import java.io.File
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale

enum class RecorderDestination { RECORDINGS, DIAGNOSTICS, SETTINGS, PLAYER }

@Composable
fun RecorderRootScreen(
    records: List<CallRecord>,
    settings: RecorderSettings,
    selectedRecord: CallRecord?,
    diagnosticsContent: @Composable () -> Unit,
    onFilterChanged: (CallRecordFilter) -> Unit,
    onOpenRecord: (CallRecord) -> Unit,
    onDeleteRecord: (CallRecord) -> Unit,
    onStorageLimitChanged: (Int) -> Unit,
    onRecordingModeChanged: (RecordingMode) -> Unit,
) {
    var destination by remember { mutableStateOf(RecorderDestination.RECORDINGS) }
    Scaffold(
        bottomBar = {
            if (destination != RecorderDestination.PLAYER) {
                NavigationBar {
                    NavigationBarItem(
                        selected = destination == RecorderDestination.RECORDINGS,
                        onClick = { destination = RecorderDestination.RECORDINGS },
                        icon = {},
                        label = { Text("Recordings") },
                    )
                    NavigationBarItem(
                        selected = destination == RecorderDestination.DIAGNOSTICS,
                        onClick = { destination = RecorderDestination.DIAGNOSTICS },
                        icon = {},
                        label = { Text("Diagnostics") },
                    )
                    NavigationBarItem(
                        selected = destination == RecorderDestination.SETTINGS,
                        onClick = { destination = RecorderDestination.SETTINGS },
                        icon = {},
                        label = { Text("Settings") },
                    )
                }
            }
        },
    ) { insets ->
        Surface(Modifier.fillMaxSize().padding(insets)) {
            when (destination) {
                RecorderDestination.RECORDINGS -> RecordingsListScreen(
                    records = records,
                    onFilterChanged = onFilterChanged,
                    onOpenRecord = {
                        onOpenRecord(it)
                        destination = RecorderDestination.PLAYER
                    },
                )
                RecorderDestination.DIAGNOSTICS -> diagnosticsContent()
                RecorderDestination.SETTINGS -> RecorderSettingsScreen(
                    settings = settings,
                    onStorageLimitChanged = onStorageLimitChanged,
                    onRecordingModeChanged = onRecordingModeChanged,
                )
                RecorderDestination.PLAYER -> {
                    val record = selectedRecord
                    if (record == null) {
                        Column(Modifier.padding(24.dp)) {
                            Text("No recording selected.")
                            Button(onClick = { destination = RecorderDestination.RECORDINGS }) {
                                Text("Back to recordings")
                            }
                        }
                    } else {
                        RecordingPlayerScreen(
                            record = record,
                            onBack = { destination = RecorderDestination.RECORDINGS },
                            onDelete = {
                                onDeleteRecord(record)
                                destination = RecorderDestination.RECORDINGS
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun RecordingsListScreen(
    records: List<CallRecord>,
    onFilterChanged: (CallRecordFilter) -> Unit,
    onOpenRecord: (CallRecord) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var selectedSim by remember { mutableStateOf<Int?>(null) }
    var dateText by remember { mutableStateOf("") }
    var oldestFirst by remember { mutableStateOf(false) }

    fun updateFilter(newQuery: String = query, newSim: Int? = selectedSim, newDate: String = dateText) {
        val parsedDate = runCatching { LocalDate.parse(newDate, DateTimeFormatter.ISO_LOCAL_DATE) }.getOrNull()
        val from = parsedDate?.atStartOfDay(ZoneId.systemDefault())?.toInstant()?.toEpochMilli()
        val to = parsedDate?.plusDays(1)?.atStartOfDay(ZoneId.systemDefault())?.toInstant()?.toEpochMilli()
        onFilterChanged(
            CallRecordFilter(
                simSlotIndex = newSim,
                fromEpochMillis = from,
                toEpochMillisExclusive = to,
                query = newQuery,
            ),
        )
    }

    val sortedRecords = if (oldestFirst) {
        records.sortedBy { it.startedAtEpochMillis }
    } else {
        records.sortedByDescending { it.startedAtEpochMillis }
    }

    Column(
        Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Recordings", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        OutlinedTextField(
            value = query,
            onValueChange = {
                query = it
                updateFilter(newQuery = it)
            },
            modifier = Modifier.fillMaxWidth().testTag("recordings-search"),
            label = { Text("Search file, phone, or mode") },
            singleLine = true,
        )
        OutlinedTextField(
            value = dateText,
            onValueChange = {
                dateText = it
                updateFilter(newDate = it)
            },
            modifier = Modifier.fillMaxWidth().testTag("recordings-date-filter"),
            label = { Text("Date (YYYY-MM-DD)") },
            singleLine = true,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(
                selected = selectedSim == null,
                onClick = {
                    selectedSim = null
                    updateFilter(newSim = null)
                },
                label = { Text("All SIMs") },
                modifier = Modifier.testTag("sim-filter-all"),
            )
            FilterChip(
                selected = selectedSim == 0,
                onClick = {
                    selectedSim = 0
                    updateFilter(newSim = 0)
                },
                label = { Text("SIM 1") },
                modifier = Modifier.testTag("sim-filter-0"),
            )
            FilterChip(
                selected = selectedSim == 1,
                onClick = {
                    selectedSim = 1
                    updateFilter(newSim = 1)
                },
                label = { Text("SIM 2") },
                modifier = Modifier.testTag("sim-filter-1"),
            )
        }
        TextButton(
            onClick = { oldestFirst = !oldestFirst },
            modifier = Modifier.testTag("recordings-sort"),
        ) { Text(if (oldestFirst) "Oldest first" else "Newest first") }
        if (sortedRecords.isEmpty()) {
            Column(Modifier.fillMaxWidth().padding(vertical = 32.dp)) {
                Text("No recordings yet", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Completed recordings appear here. Interrupted and failed sessions remain visible with their status.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize().testTag("recordings-list"),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(sortedRecords, key = { it.id }) { record ->
                    RecordingListItem(record, onClick = { onOpenRecord(record) })
                }
            }
        }
    }
}

@Composable
private fun RecordingListItem(record: CallRecord, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().testTag("record-list-item-${record.id}"),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (record.simSlotIndex >= 0) "SIM ${record.simSlotIndex + 1}" else "SIM unknown",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(record.status, style = MaterialTheme.typography.labelLarge)
            }
            Text(
                record.phoneNumber ?: File(record.filePath).name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(formatRecordDate(record.startedAtEpochMillis), style = MaterialTheme.typography.bodySmall)
            Text(
                "${record.recordingMode} · ${formatDuration(record.durationMillis ?: 0L)}",
                style = MaterialTheme.typography.bodySmall,
            )
            if (record.isSilent == true) {
                Text("No usable audio signal detected", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
fun RecordingPlayerScreen(record: CallRecord, onBack: () -> Unit, onDelete: () -> Unit) {
    val file = remember(record.filePath) { File(record.filePath) }
    if (record.status != "COMPLETED" || !file.isFile || !file.canRead()) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Recording unavailable", style = MaterialTheme.typography.headlineSmall)
            Text("This session is ${record.status.lowercase(Locale.ROOT)} or its private WAV file is missing.")
            Button(onClick = onBack) { Text("Back") }
            OutlinedButton(onClick = onDelete) { Text("Remove record") }
        }
        return
    }

    val context = LocalContext.current
    val player = remember(file.absolutePath) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
            prepare()
        }
    }
    var playing by remember(player) { mutableStateOf(false) }
    var position by remember(player) { mutableFloatStateOf(0f) }
    var duration by remember(player) { mutableFloatStateOf(1f) }
    var error by remember(player) { mutableStateOf<String?>(null) }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) duration = player.duration.coerceAtLeast(1L).toFloat()
            }
            override fun onPlayerError(playbackException: androidx.media3.common.PlaybackException) {
                error = playbackException.message ?: "Playback failed."
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }
    LaunchedEffect(player) {
        while (true) {
            position = player.currentPosition.toFloat().coerceIn(0f, duration)
            if (player.duration > 0L) duration = player.duration.toFloat()
            delay(250L)
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TextButton(onClick = onBack) { Text("← Back to recordings") }
        Text("Recording details", style = MaterialTheme.typography.headlineMedium)
        Text(file.name, style = MaterialTheme.typography.titleMedium)
        Text("SIM ${record.simSlotIndex + 1} · ${formatRecordDate(record.startedAtEpochMillis)}")
        Text("${record.recordingMode} · ${record.channelCount} channel(s)")
        Text("Duration: ${formatDuration(record.durationMillis ?: 0L)}")
        if (record.isSilent == true) Text("Digital silence detected", color = MaterialTheme.colorScheme.error)
        HorizontalDivider()
        Text("Playback", style = MaterialTheme.typography.titleLarge)
        Button(onClick = { if (player.isPlaying) player.pause() else player.play() }) {
            Text(if (playing) "Pause" else "Play")
        }
        Slider(
            value = position.coerceIn(0f, duration),
            onValueChange = { position = it },
            onValueChangeFinished = { player.seekTo(position.toLong()) },
            valueRange = 0f..duration.coerceAtLeast(1f),
            modifier = Modifier.testTag("player-progress"),
        )
        Text("${formatDuration(position.toLong())} / ${formatDuration(duration.toLong())}")
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        OutlinedButton(onClick = onDelete) { Text("Delete recording") }
    }
}

@Composable
fun RecorderSettingsScreen(
    settings: RecorderSettings,
    onStorageLimitChanged: (Int) -> Unit,
    onRecordingModeChanged: (RecordingMode) -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("Recording backend", style = MaterialTheme.typography.titleMedium)
        FilterChip(
            selected = settings.recordingMode == RecordingMode.STANDARD,
            onClick = { onRecordingModeChanged(RecordingMode.STANDARD) },
            label = { Text("Standard (call capture unavailable)") },
            modifier = Modifier.testTag("backend-standard"),
        )
        FilterChip(
            selected = settings.recordingMode == RecordingMode.SHIZUKU_VOICE_CALL,
            onClick = { onRecordingModeChanged(RecordingMode.SHIZUKU_VOICE_CALL) },
            label = { Text("Advanced · Shizuku") },
            modifier = Modifier.testTag("backend-shizuku"),
        )
        Text(
            "The standard app microphone is not a reliable cellular-call capture path; it deliberately refuses rather than saving misleading one-sided audio.",
            style = MaterialTheme.typography.bodySmall,
        )
        HorizontalDivider()
        Text("Storage limit: ${settings.storageLimitMb} MB", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(128, 256, 512, 1024, 2048).forEach { limit ->
                FilterChip(
                    selected = settings.storageLimitMb == limit,
                    onClick = { onStorageLimitChanged(limit) },
                    label = { Text(if (limit >= 1024) "${limit / 1024} GB" else "${limit} MB") },
                )
            }
        }
        HorizontalDivider()
        Text("Recording trigger", style = MaterialTheme.typography.titleMedium)
        Text("Manual only", fontWeight = FontWeight.SemiBold)
        Text(
            "Automatic recording on RINGING/OFFHOOK is disabled. Start remains an explicit user action while the selected SIM reports OFFHOOK.",
            style = MaterialTheme.typography.bodySmall,
        )
        Text("Privacy", style = MaterialTheme.typography.titleMedium)
        Text(
            "Recordings remain in app-private storage. Phone numbers are not collected because supported call-state callbacks do not consistently expose them. Obtain all legally required participant consent.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

private fun formatRecordDate(epochMillis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(epochMillis))

private fun formatDuration(durationMillis: Long): String {
    val seconds = durationMillis.coerceAtLeast(0L) / 1_000L
    val hours = seconds / 3_600L
    val minutes = (seconds % 3_600L) / 60L
    val remainder = seconds % 60L
    return if (hours > 0L) {
        String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, remainder)
    } else {
        String.format(Locale.ROOT, "%02d:%02d", minutes, remainder)
    }
}
