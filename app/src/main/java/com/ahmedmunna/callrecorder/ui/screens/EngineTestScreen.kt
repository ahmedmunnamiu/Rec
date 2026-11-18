package com.ahmedmunna.callrecorder.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Call
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.ahmedmunna.callrecorder.data.db.EngineTestEntity
import com.ahmedmunna.callrecorder.domain.EngineId
import com.ahmedmunna.callrecorder.domain.EngineState
import com.ahmedmunna.callrecorder.engine.PERM_CAPTURE_AUDIO_OUTPUT
import com.ahmedmunna.callrecorder.engine.RecordingEngine
import com.ahmedmunna.callrecorder.ui.MainViewModel
import com.ahmedmunna.callrecorder.ui.common.Fmt
import com.ahmedmunna.callrecorder.ui.common.Pill
import com.ahmedmunna.callrecorder.ui.common.SoftCard
import com.ahmedmunna.callrecorder.ui.common.StatePill
import com.ahmedmunna.callrecorder.ui.common.rememberPermissionRequester
import com.ahmedmunna.callrecorder.ui.common.rememberResumeTick
import com.ahmedmunna.callrecorder.ui.theme.StatusColors

@Composable
fun EngineTestScreen(vm: MainViewModel, onOpenSettings: (String) -> Unit) {
    val ctx = LocalContext.current
    val tests by vm.engineTests.collectAsState()
    val settings by vm.settings.collectAsState()
    val rec by vm.recommendation.collectAsState()
    val run by vm.testRun.collectAsState()
    val tick = rememberResumeTick()
    val callActive = remember(tick, run) { vm.tester.isCallActive() }
    var consentFor by remember { mutableStateOf<EngineId?>(null) }
    var pendingTest by remember { mutableStateOf<Pair<EngineId, Boolean>?>(null) }
    val requester = rememberPermissionRequester {
        pendingTest?.let { (id, guided) ->
            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) vm.runTest(id, guided)
            else Toast.makeText(ctx, "Microphone permission is required to test.", Toast.LENGTH_LONG).show()
        }
        pendingTest = null
    }

    fun start(id: EngineId, guided: Boolean) {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingTest = id to guided
            requester(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_PHONE_STATE))
        } else vm.runTest(id, guided)
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("Engine Test Center", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Every result below comes from recording real audio and analysing it. Opening a recorder successfully is never counted as proof of capture.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            SoftCard { Column(Modifier.padding(18.dp)) {
                Text("Recommended engine", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text(rec.engine.label, style = MaterialTheme.typography.titleLarge)
                Text("${rec.expectedMode.label} — ${rec.reason}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Choose engine manually", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            if (settings.manualEngine != null) "Using ${settings.manualEngine!!.label}" else "Automatic selection from test results",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (settings.manualEngine != null) TextButton({ vm.update { copy(manualEngine = null) } }) { Text("Back to automatic") }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton({ vm.runAllMicTests() }, enabled = run == null) { Text("Test all microphone engines") }
                }
                Text(
                    if (callActive) "A call is active. Guided call tests are available." else "No active call. Start or answer a call, then come back for guided call tests.",
                    style = MaterialTheme.typography.bodyMedium, color = if (callActive) StatusColors.ok else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            } }
        }
        run?.let { r ->
            item {
                SoftCard { Column(Modifier.padding(18.dp)) {
                    Text("Testing ${r.engine.label}", style = MaterialTheme.typography.titleMedium)
                    Text(r.progress?.instruction ?: "Preparing…", style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(progress = { (r.progress?.level ?: 0f).coerceIn(0f, 1f) }, Modifier.fillMaxWidth().height(10.dp))
                    r.progress?.let { Text("${it.secondsLeft}s left", style = MaterialTheme.typography.labelMedium) }
                    TextButton({ vm.cancelTest() }) { Text("Cancel") }
                } }
            }
        }
        items(vm.registry.all, key = { it.id.name }) { engine ->
            EngineCard(
                engine, tests.firstOrNull { it.engine == engine.id }, selected = settings.manualEngine == engine.id,
                recommended = rec.engine == engine.id, busy = run != null, callActive = callActive,
                onTest = { start(engine.id, false) }, onGuided = { consentFor = engine.id },
                onPlay = { t -> vm.playTestFile(t) },
                onUse = { vm.update { copy(manualEngine = engine.id) } },
                onOpenSetup = { onOpenSettings(if (engine.id == EngineId.ADB) "adb" else if (engine.id == EngineId.ROOT) "root" else "engine") },
            )
        }
        item { Spacer(Modifier.height(16.dp)) }
    }

    consentFor?.let { id ->
        AlertDialog(
            onDismissRequest = { consentFor = null },
            title = { Text("Guided call test") },
            text = {
                Text(
                    "This records about 10 seconds of the ongoing call with ${id.label}: first 5 s while ONLY the other person talks, then 5 s while ONLY you talk. " +
                        "The recording stays on this device. Make sure the other person agrees to a short test recording."
                )
            },
            confirmButton = { TextButton({ consentFor = null; start(id, true) }) { Text("I agree, start") } },
            dismissButton = { TextButton({ consentFor = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun EngineCard(
    engine: RecordingEngine, t: EngineTestEntity?, selected: Boolean, recommended: Boolean, busy: Boolean, callActive: Boolean,
    onTest: () -> Unit, onGuided: () -> Unit, onPlay: (EngineTestEntity) -> Unit, onUse: () -> Unit, onOpenSetup: () -> Unit,
) {
    val ctx = LocalContext.current
    val id = engine.id
    val isSetup = id == EngineId.ADB
    SoftCard { Column(Modifier.padding(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(id.label, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            if (recommended) Pill("Recommended", StatusColors.ok, Modifier.padding(end = 6.dp))
            if (selected) Pill("Selected", MaterialTheme.colorScheme.primary, Modifier.padding(end = 6.dp))
            StatePill(t?.state ?: EngineState.NOT_TESTED)
        }
        Text(id.summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        if (engine.requiredPermissions.isNotEmpty() && !isSetup) {
            engine.requiredPermissions.forEach { p ->
                val granted = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED
                val name = p.substringAfterLast('.')
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(name, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                    Text(
                        if (granted) "granted" else if (p == PERM_CAPTURE_AUDIO_OUTPUT) "system only" else "not granted",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (granted) StatusColors.ok else if (p == PERM_CAPTURE_AUDIO_OUTPUT) StatusColors.warn else StatusColors.bad,
                    )
                }
            }
        }
        if (t != null) {
            Spacer(Modifier.height(8.dp))
            Text("Last test: ${Fmt.dateTime(t.testedAt)}", style = MaterialTheme.typography.labelMedium)
            Text("Result: ${t.captureMode.label}", style = MaterialTheme.typography.bodyLarge)
            Text(t.verdict, style = MaterialTheme.typography.bodyMedium)
            if (t.testFilePath != null) Text(
                "Peak ${(t.peak * 100).toInt()}% · RMS ${"%.1f".format(t.rms * 100)}% · clipping ${"%.1f".format(t.clippingPct)}% · silence ${t.silencePct.toInt()}%",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            t.error?.takeIf { it != t.verdict }?.let { Text("Error: $it", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (isSetup) {
                Button(onTest, enabled = !busy) { Text("Check status") }
                OutlinedButton(onOpenSetup) { Text("Open ADB setup") }
            } else {
                Button(onTest, enabled = !busy) { Icon(Icons.Filled.Mic, null, Modifier.padding(end = 4.dp)); Text("Test") }
                if (id.category != com.ahmedmunna.callrecorder.domain.EngineCategory.SETUP || id == EngineId.ROOT) {
                    OutlinedButton(onGuided, enabled = !busy && callActive && id != EngineId.ROOT) { Icon(Icons.Filled.Call, null, Modifier.padding(end = 4.dp)); Text("Call test") }
                }
                if (t?.testFilePath != null) OutlinedButton({ onPlay(t) }) { Icon(Icons.Filled.PlayArrow, null); Text("Play") }
            }
        }
        if (!isSetup && id.isRecordable) {
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Use this engine", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Switch(selected, { if (it) onUse() })
            }
            if (id == EngineId.ROOT) TextButton(onOpenSetup) { Text("Root status") }
        }
    } }
}
