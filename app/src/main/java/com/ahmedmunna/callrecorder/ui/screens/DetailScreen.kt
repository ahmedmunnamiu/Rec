package com.ahmedmunna.callrecorder.ui.screens

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PriorityHigh
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.ahmedmunna.callrecorder.data.db.RecordingEntity
import com.ahmedmunna.callrecorder.domain.RecordingStatus
import com.ahmedmunna.callrecorder.ui.MainViewModel
import com.ahmedmunna.callrecorder.ui.common.Fmt
import com.ahmedmunna.callrecorder.ui.common.KeyValue
import com.ahmedmunna.callrecorder.ui.common.Pill
import com.ahmedmunna.callrecorder.ui.common.SectionHeader
import com.ahmedmunna.callrecorder.ui.common.SoftCard
import com.ahmedmunna.callrecorder.ui.common.WaveformView
import com.ahmedmunna.callrecorder.ui.theme.StatusColors
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(vm: MainViewModel, id: Long, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val r by remember(id) { vm.repo.observe(id) }.collectAsState(initial = null)
    val player by vm.player.state.collectAsState()
    val wave by vm.wave.collectAsState()
    val transcribing by vm.transcribing.collectAsState()
    val settings by vm.settings.collectAsState()

    var confirmDelete by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var showTrim by remember { mutableStateOf(false) }

    fun toast(msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()

    val rec = r
    LaunchedEffect(rec?.id) { if (rec != null && rec.filePath.isNotBlank()) vm.loadWaveform(rec) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/*")) { uri ->
        if (uri != null && rec != null) vm.exportAudio(rec, uri, ::toast)
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                windowInsets = WindowInsets(0, 0, 0, 0),
                title = { Text(rec?.contactName ?: rec?.phoneNumber ?: "Recording") },
                navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    if (rec != null && rec.status != RecordingStatus.FAILED) {
                        IconButton({ vm.toggleFavorite(rec) }) {
                            Icon(if (rec.favorite) Icons.Filled.Star else Icons.Outlined.StarBorder, "Favorite", tint = if (rec.favorite) StatusColors.warn else MaterialTheme.colorScheme.onSurface)
                        }
                    }
                },
            )
        },
    ) { pad ->
        if (rec == null) { Text("Recording not found.", Modifier.padding(pad).padding(24.dp)); return@Scaffold }

        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            if (rec.status == RecordingStatus.FAILED) {
                SoftCard { Column(Modifier.padding(18.dp)) {
                    Text("This recording failed", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
                    Text(rec.failureReason ?: "Unknown reason", style = MaterialTheme.typography.bodyMedium)
                } }
            } else {
                PlayerCard(vm, rec, player, wave?.takeIf { it.first == rec.id }?.second ?: FloatArray(0))
                Spacer(Modifier.height(12.dp))
                // Actions
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton({
                        runCatching {
                            val uri = if (rec.isUri) android.net.Uri.parse(rec.filePath)
                            else FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", File(rec.filePath))
                            val send = Intent(Intent.ACTION_SEND).setType(rec.format.mime).putExtra(Intent.EXTRA_STREAM, uri)
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            ctx.startActivity(Intent.createChooser(send, "Share recording"))
                        }.onFailure { toast("Could not share: ${it.message}") }
                    }, Modifier.weight(1f)) { Icon(Icons.Filled.Share, null, Modifier.size(18.dp)); Text(" Share") }
                    FilledTonalButton({ exportLauncher.launch(if (rec.isUri) "recording.${rec.format.extension}" else File(rec.filePath).name) }, Modifier.weight(1f)) {
                        Icon(Icons.Filled.Download, null, Modifier.size(18.dp)); Text(" Export")
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton({ showRename = true }, Modifier.weight(1f)) { Icon(Icons.Filled.Edit, null, Modifier.size(18.dp)); Text(" Rename") }
                    OutlinedButton({ showTrim = true }, Modifier.weight(1f), enabled = rec.durationMs >= 1000) { Icon(Icons.Filled.ContentCut, null, Modifier.size(18.dp)); Text(" Trim") }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (rec.status != RecordingStatus.FAILED) {
                    OutlinedButton({ vm.toggleImportant(rec) }, Modifier.weight(1f)) {
                        Icon(Icons.Filled.PriorityHigh, null, Modifier.size(18.dp)); Text(if (rec.important) " Not important" else " Important")
                    }
                    OutlinedButton({ vm.togglePrivate(rec, ::toast) }, Modifier.weight(1f)) {
                        Icon(if (rec.isPrivate) Icons.Filled.LockOpen else Icons.Filled.Lock, null, Modifier.size(18.dp))
                        Text(if (rec.isPrivate) " Unprivate" else " Make private")
                    }
                }
            }
            OutlinedButton({ confirmDelete = true }, Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Icon(Icons.Filled.Delete, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                Text(" Delete", color = MaterialTheme.colorScheme.error)
            }

            SectionHeader("Notes & tags")
            NotesEditor(rec) { notes, tags -> vm.saveNotes(rec, notes, tags); toast("Saved") }

            SectionHeader("Transcript")
            SoftCard { Column(Modifier.padding(18.dp)) {
                if (rec.transcript != null) Text(rec.transcript, style = MaterialTheme.typography.bodyLarge)
                else Text(
                    if (!settings.transcription) "Transcription is off. Enable it in Settings → Privacy & Security. It runs fully on this device." else "No transcript yet.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (settings.transcription && rec.status != RecordingStatus.FAILED) {
                    Spacer(Modifier.height(8.dp))
                    Button({ vm.transcribe(rec, ::toast) }, enabled = transcribing == null) {
                        Text(if (transcribing == rec.id) "Transcribing…" else if (rec.transcript == null) "Transcribe on device" else "Transcribe again")
                    }
                    if (!vm.transcriptionAvailable()) Text("No on-device recogniser detected; this may fail.", style = MaterialTheme.typography.bodyMedium, color = StatusColors.warn)
                }
            } }

            SectionHeader("Details")
            SoftCard { Column(Modifier.padding(18.dp)) {
                KeyValue("Number", if (rec.isUnknownNumber) "Unknown" else rec.phoneNumber)
                rec.contactName?.let { KeyValue("Contact", it) }
                KeyValue("Direction", rec.direction.name.lowercase().replaceFirstChar { it.uppercase() })
                KeyValue("Started", Fmt.dateTime(rec.startTime))
                rec.endTime?.let { KeyValue("Ended", Fmt.dateTime(it)) }
                KeyValue("Duration", Fmt.duration(rec.durationMs))
                KeyValue("File size", Fmt.size(rec.fileSize))
                KeyValue("Format", rec.format.label)
                KeyValue("Quality", rec.quality)
                KeyValue("Engine", rec.engine.label)
                KeyValue("Captured", rec.captureMode.label)
                KeyValue("Status", rec.status.name.lowercase().replaceFirstChar { it.uppercase() })
                KeyValue("Location", if (rec.filePath.isBlank()) "-" else if (rec.isUri) "Custom folder" else rec.filePath.substringAfter("Android/", rec.filePath))
                rec.failureReason?.let { Text(it, color = StatusColors.warn, style = MaterialTheme.typography.bodyMedium) }
            } }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (rec != null && confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Delete this recording?") },
        text = { Text("The audio file and its notes will be permanently deleted. This cannot be undone.") },
        confirmButton = { TextButton({ confirmDelete = false; vm.deleteConfirmed(listOf(rec)) { onBack() } }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton({ confirmDelete = false }) { Text("Cancel") } },
    )
    if (rec != null && showRename) {
        var name by remember { mutableStateOf(if (rec.isUri) "" else File(rec.filePath).nameWithoutExtension) }
        AlertDialog(
            onDismissRequest = { showRename = false },
            title = { Text("Rename recording") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("File name") }) },
            confirmButton = {
                TextButton({
                    vm.rename(rec, name) { ok -> toast(if (ok) "Renamed" else "Could not rename (name exists or invalid)") }
                    showRename = false
                }, enabled = name.isNotBlank()) { Text("Rename") }
            },
            dismissButton = { TextButton({ showRename = false }) { Text("Cancel") } },
        )
    }
    if (rec != null && showTrim) TrimDialog(rec, onDismiss = { showTrim = false }) { s, e ->
        showTrim = false; toast("Trimming…"); vm.trim(rec, s, e, ::toast)
    }
}

@Composable
private fun PlayerCard(vm: MainViewModel, r: RecordingEntity, p: com.ahmedmunna.callrecorder.player.PlayerUi, peaks: FloatArray) {
    val active = p.currentId == r.id
    val duration = if (active && p.durationMs > 0) p.durationMs else r.durationMs
    val position = if (active) p.positionMs else r.lastPlayPositionMs
    val progress = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    SoftCard {
        Column(Modifier.padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            WaveformView(peaks, progress, { f ->
                if (!active) vm.player.play(r, resume = false)
                vm.player.seekTo((f * duration).toLong())
            })
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(Fmt.duration(position), style = MaterialTheme.typography.labelMedium)
                Text(Fmt.duration(duration), style = MaterialTheme.typography.labelMedium)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconButton({ vm.player.setRepeat(!p.repeat) }) { Icon(if (p.repeat) Icons.Filled.RepeatOne else Icons.Filled.Repeat, "Repeat", tint = if (p.repeat) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
                IconButton({ vm.player.skip(-10_000) }, enabled = active) { Icon(Icons.Filled.Replay10, "Back 10 seconds") }
                FilledIconButton({ if (active) vm.player.toggle() else vm.player.play(r) }, Modifier.size(64.dp)) {
                    Icon(if (active && p.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (active && p.playing) "Pause" else "Play", Modifier.size(34.dp))
                }
                IconButton({ vm.player.skip(10_000) }, enabled = active) { Icon(Icons.Filled.Forward10, "Forward 10 seconds") }
                IconButton({ vm.player.stop() }, enabled = active) { Icon(Icons.Filled.Stop, "Stop") }
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)) {
                listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f).forEach { s ->
                    FilterChip(p.speed == s, { vm.player.setSpeed(s) }, { Text(if (s == 1f) "1x" else "${s}x") }, enabled = active)
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.AutoMirrored.Filled.VolumeUp, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Slider(p.volume, { vm.player.setVolume(it) }, Modifier.weight(1f).padding(start = 8.dp), enabled = active)
            }
            if (active && p.error != null) Text("Playback problem: ${p.error}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun NotesEditor(r: RecordingEntity, onSave: (String, String) -> Unit) {
    var notes by remember(r.id, r.notes) { mutableStateOf(r.notes) }
    var tags by remember(r.id, r.tags) { mutableStateOf(r.tags) }
    SoftCard { Column(Modifier.padding(18.dp)) {
        OutlinedTextField(notes, { notes = it }, Modifier.fillMaxWidth(), label = { Text("Notes") }, minLines = 2)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(tags, { tags = it }, Modifier.fillMaxWidth(), label = { Text("Tags (comma separated)") }, singleLine = true)
        Spacer(Modifier.height(8.dp))
        Button({ onSave(notes, tags) }, enabled = notes != r.notes || tags != r.tags) { Text("Save") }
    } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrimDialog(r: RecordingEntity, onDismiss: () -> Unit, onTrim: (Long, Long) -> Unit) {
    val total = r.durationMs.coerceAtLeast(1000).toFloat()
    var range by remember { mutableStateOf(0f..total) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Trim recording") },
        text = {
            Column {
                Text("Creates a new trimmed copy. The original stays untouched.", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                RangeSlider(range, { range = it }, valueRange = 0f..total)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("From ${Fmt.duration(range.start.toLong())}")
                    Text("To ${Fmt.duration(range.endInclusive.toLong())}")
                }
            }
        },
        confirmButton = {
            TextButton({ onTrim(range.start.toLong(), range.endInclusive.toLong()) }, enabled = range.endInclusive - range.start >= 500) { Text("Save copy") }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}
