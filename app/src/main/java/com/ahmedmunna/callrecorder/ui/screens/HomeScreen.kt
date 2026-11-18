package com.ahmedmunna.callrecorder.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.background
import com.ahmedmunna.callrecorder.domain.CaptureMode
import com.ahmedmunna.callrecorder.domain.RecordingStatus
import com.ahmedmunna.callrecorder.engine.BluetoothEngine
import com.ahmedmunna.callrecorder.permissions.PermStatus
import com.ahmedmunna.callrecorder.permissions.PermissionCenter
import com.ahmedmunna.callrecorder.ui.MainViewModel
import com.ahmedmunna.callrecorder.ui.common.Fmt
import com.ahmedmunna.callrecorder.ui.common.Pill
import com.ahmedmunna.callrecorder.ui.common.RecordingRow
import com.ahmedmunna.callrecorder.ui.common.SectionHeader
import com.ahmedmunna.callrecorder.ui.common.SoftCard
import com.ahmedmunna.callrecorder.ui.common.StatTile
import com.ahmedmunna.callrecorder.ui.common.rememberPermissionRequester
import com.ahmedmunna.callrecorder.ui.common.rememberResumeTick
import com.ahmedmunna.callrecorder.ui.theme.StatusColors

@Composable
fun HomeScreen(vm: MainViewModel, onOpenRecording: (Long) -> Unit, onOpenSettings: (String) -> Unit, onOpenEngineTest: () -> Unit) {
    val ctx = LocalContext.current
    val settings by vm.settings.collectAsState()
    val stats by vm.stats.collectAsState()
    val recs by vm.recordings.collectAsState()
    val rec by vm.recommendation.collectAsState()
    val tick = rememberResumeTick()
    val missing = remember(tick) { PermissionCenter.missingCore(ctx) }
    val health = remember(tick) { PermissionCenter.health(ctx) }
    val btWarning = remember(tick) { BluetoothEngine.routingWarning(ctx) }
    val recordingNow = recs.any { it.status == RecordingStatus.RECORDING }
    val requestPerms = rememberPermissionRequester()

    val engine = settings.manualEngine ?: rec.engine
    val mode = if (settings.manualEngine != null) vm.engineTests.collectAsState().value.firstOrNull { it.engine == engine }?.captureMode ?: CaptureMode.NONE else rec.expectedMode
    val free = remember(tick, recs.size) { vm.storage.freeBytes() }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
        item {
            Text("Advanced Auto Call Recorder", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(16.dp))
            StatusCard(recordingNow, settings.autoRecord) { on ->
                if (on && missing.isNotEmpty()) requestPerms(PermissionCenter.runtimePermissions)
                vm.update { copy(autoRecord = on) }
            }
        }
        if (missing.isNotEmpty()) item {
            Spacer(Modifier.height(12.dp))
            WarningCard("Permissions missing: ${missing.joinToString()}. Auto recording cannot work until they are granted.", "Open Permissions") { onOpenSettings("permissions") }
        }
        if (btWarning != null) item { Spacer(Modifier.height(12.dp)); WarningCard(btWarning, null) {} }
        item {
            Spacer(Modifier.height(12.dp))
            SoftCard {
                Column(Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.GraphicEq, null, tint = MaterialTheme.colorScheme.primary)
                        Text("  Recording engine", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        Pill(if (settings.manualEngine != null) "Manual" else "Automatic", StatusColors.neutral)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(engine.label, style = MaterialTheme.typography.titleLarge)
                    Text(
                        if (settings.manualEngine != null) "Expected capture: ${mode.label}" else "${mode.label}. ${rec.reason}",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (mode != CaptureMode.TWO_WAY_VERIFIED) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Two-way recording is not verified on this phone. Most Android phones only record the microphone side.",
                            style = MaterialTheme.typography.bodyMedium, color = StatusColors.warn,
                        )
                    }
                    TextButton(onClick = onOpenEngineTest) { Text("Open Engine Test Center") }
                }
            }
        }
        item {
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatTile("Recordings", stats.total.toString(), Modifier.weight(1f))
                StatTile("Total time", Fmt.duration(stats.totalDurationMs), Modifier.weight(1f))
            }
            Spacer(Modifier.height(12.dp))
            SoftCard {
                Column(Modifier.padding(18.dp)) {
                    Text("Storage", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    val total = vm.storage.totalBytes().coerceAtLeast(1)
                    LinearProgressIndicator(progress = { (stats.usedBytes.toFloat() / total).coerceIn(0.01f, 1f) }, Modifier.fillMaxWidth().height(8.dp).clip(CircleShape))
                    Spacer(Modifier.height(8.dp))
                    Text("${Fmt.size(stats.usedBytes)} used by recordings · ${Fmt.size(free)} free on device", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Permission health: ${(health * 100).toInt()}%", style = MaterialTheme.typography.bodyMedium, color = if (health >= 1f) StatusColors.ok else StatusColors.warn)
                }
            }
        }
        item {
            SectionHeader("Quick settings")
            SoftCard {
                Column(Modifier.padding(horizontal = 18.dp, vertical = 6.dp)) {
                    QuickSwitch("Record unknown numbers", settings.recordUnknown) { v -> vm.update { copy(recordUnknown = v) } }
                    QuickSwitch("Speakerphone-assisted recording", settings.speakerphoneConsent) { v -> vm.update { copy(speakerphoneConsent = v) } }
                    QuickSwitch("Recording failure alerts", settings.notifyOnFailure) { v -> vm.update { copy(notifyOnFailure = v) } }
                }
            }
        }
        item { SectionHeader("Last recording") }
        item {
            val last = stats.last
            if (last == null) Text("No recordings yet. Turn on auto recording and make a call.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            else RecordingRow(last, false, { onOpenRecording(last.id) }, {}, { vm.toggleFavorite(last) })
        }
        if (stats.recent.size > 1) {
            item { SectionHeader("Recent") }
            stats.recent.drop(1).forEach { r ->
                item(key = r.id) {
                    RecordingRow(r, false, { onOpenRecording(r.id) }, {}, { vm.toggleFavorite(r) })
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun StatusCard(recording: Boolean, auto: Boolean, onToggle: (Boolean) -> Unit) {
    val container by animateColorAsState(
        when {
            recording -> StatusColors.bad.copy(alpha = 0.16f)
            auto -> MaterialTheme.colorScheme.primaryContainer
            else -> MaterialTheme.colorScheme.surfaceVariant
        }, label = "status"
    )
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        1f, 0.3f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "alpha"
    )
    Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge, colors = CardDefaults.cardColors(containerColor = container)) {
        Row(Modifier.padding(22.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.FiberManualRecord, null,
                Modifier.size(28.dp).alpha(if (recording) pulse else 1f),
                tint = if (recording) StatusColors.bad else if (auto) StatusColors.ok else StatusColors.neutral,
            )
            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                Text(if (recording) "Recording a call" else if (auto) "Ready to record" else "Auto recording is off", style = MaterialTheme.typography.titleLarge)
                Text(
                    if (recording) "A notification is shown while recording." else if (auto) "Calls are recorded automatically." else "Turn on to record calls automatically.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Switch(checked = auto, onCheckedChange = onToggle)
        }
    }
}

@Composable
fun WarningCard(text: String, action: String?, onAction: () -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, colors = CardDefaults.cardColors(containerColor = StatusColors.warn.copy(alpha = 0.14f))) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.Filled.Warning, null, tint = StatusColors.warn)
            Column(Modifier.padding(start = 12.dp)) {
                Text(text, style = MaterialTheme.typography.bodyMedium)
                if (action != null) TextButton(onClick = onAction) { Text(action) }
            }
        }
    }
}

@Composable
fun QuickSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked, onChange)
    }
}
