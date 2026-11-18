package com.ahmedmunna.callrecorder.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.ahmedmunna.callrecorder.adb.AdbModule
import com.ahmedmunna.callrecorder.data.db.RecordingEntity
import com.ahmedmunna.callrecorder.permissions.PermAction
import com.ahmedmunna.callrecorder.permissions.PermStatus
import com.ahmedmunna.callrecorder.permissions.PermissionCenter
import com.ahmedmunna.callrecorder.root.RootDetector
import com.ahmedmunna.callrecorder.ui.MainViewModel
import com.ahmedmunna.callrecorder.ui.common.Fmt
import com.ahmedmunna.callrecorder.ui.common.KeyValue
import com.ahmedmunna.callrecorder.ui.common.Pill
import com.ahmedmunna.callrecorder.ui.common.SectionHeader
import com.ahmedmunna.callrecorder.ui.common.SoftCard
import com.ahmedmunna.callrecorder.ui.common.rememberPermissionRequester
import com.ahmedmunna.callrecorder.ui.common.rememberResumeTick
import com.ahmedmunna.callrecorder.ui.theme.StatusColors
import com.ahmedmunna.callrecorder.work.WorkScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun persistTree(ctx: android.content.Context, uri: Uri) {
    runCatching {
        ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    }
}

@Composable
fun StorageSection(vm: MainViewModel) {
    val ctx = LocalContext.current
    val s by vm.settings.collectAsState()
    val recs by vm.recordings.collectAsState()
    var review by remember { mutableStateOf<List<RecordingEntity>?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) { persistTree(ctx, uri); vm.update { copy(storageTreeUri = uri.toString()) } }
    }
    val used = recs.sumOf { it.fileSize }
    SoftCard { Column(Modifier.padding(18.dp)) {
        Text("Where recordings are saved", style = MaterialTheme.typography.titleMedium)
        InfoText(
            if (s.storageTreeUri == null) "Default: ${vm.storage.rootDir.absolutePath.substringAfter("/Android/", vm.storage.rootDir.absolutePath).let { "Android/$it" }}"
            else "Custom folder: ${Uri.decode(s.storageTreeUri!!.substringAfterLast('/'))}"
        )
        InfoText("Calls are sorted into Incoming Calls, Outgoing Calls and Private folders. Contacts, Unknown, Favorites, Important and Failed are views in the Recordings tab. New recordings use the folder chosen here; existing ones stay where they are.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ picker.launch(null) }) { Text("Choose folder") }
            if (s.storageTreeUri != null) OutlinedButton({ vm.update { copy(storageTreeUri = null) } }) { Text("Use default") }
        }
    } }
    SectionHeader("Usage")
    SoftCard { Column(Modifier.padding(18.dp)) {
        val total = vm.storage.totalBytes().coerceAtLeast(1)
        LinearProgressIndicator(progress = { (used.toFloat() / total).coerceIn(0.005f, 1f) }, Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(50)))
        Spacer(Modifier.height(8.dp))
        KeyValue("Recordings", "${recs.size}")
        KeyValue("Used by recordings", Fmt.size(used))
        KeyValue("Free on device", Fmt.size(vm.storage.freeBytes()))
    } }
    SectionHeader("Cleanup")
    SoftCard { Column(Modifier.padding(18.dp)) {
        SettingSlider("Suggest cleaning up recordings older than", if (s.cleanupDays == 0) "Off" else "${s.cleanupDays} days", s.cleanupDays.toFloat(), 0f..365f, 11) { v -> vm.update { copy(cleanupDays = (v.toInt() / 30) * 30) } }
        InfoText("Favorites and Important recordings are never suggested. Nothing is ever deleted automatically: you review the list and confirm.")
        OutlinedButton({
            val c = vm.cleanupCandidates()
            if (c.isEmpty()) Toast.makeText(ctx, "Nothing matches your cleanup rules", Toast.LENGTH_SHORT).show() else review = c
        }) { Text("Review cleanup candidates") }
        OutlinedButton({ WorkScheduler.runMaintenanceNow(ctx); Toast.makeText(ctx, "Maintenance started", Toast.LENGTH_SHORT).show() }) { Text("Run maintenance now") }
    } }
    SectionHeader("Integrity")
    SoftCard { Column(Modifier.padding(18.dp)) {
        InfoText("Compares the database with the files: marks missing or empty files, fixes sizes and adds recordings found in the default folder. Never deletes anything.")
        Button({ vm.checkIntegrity { msg -> Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show() } }) { Text("Check file integrity") }
    } }
    review?.let { list ->
        AlertDialog(
            onDismissRequest = { review = null },
            title = { Text("Delete ${list.size} recording(s)?") },
            text = {
                Text("These match your cleanup rules (${Fmt.size(list.sumOf { it.fileSize })}), oldest first:\n\n" +
                    list.take(8).joinToString("\n") { "• ${Fmt.date(it.startTime)} · ${it.contactName ?: it.phoneNumber}" } +
                    (if (list.size > 8) "\n…and ${list.size - 8} more" else "") +
                    "\n\nDeleting is permanent.")
            },
            confirmButton = { TextButton({ val l = list; review = null; vm.deleteConfirmed(l) }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton({ review = null }) { Text("Keep them") } },
        )
    }
}

@Composable
fun PermissionsSection() {
    val ctx = LocalContext.current
    var bump by remember { mutableIntStateOf(0) }
    val tick = rememberResumeTick()
    val requester = rememberPermissionRequester { bump++ }
    val items = remember(tick, bump) { PermissionCenter.items(ctx) }
    InfoText("Permissions are only requested when you tap a button here or turn on a feature. Statuses refresh when you return from Android settings.")
    items.forEach { p ->
        SoftCard(Modifier.padding(vertical = 5.dp)) { Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(p.title + if (p.optional) " (optional)" else "", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Pill(
                    p.status.label,
                    when (p.status) { PermStatus.GRANTED -> StatusColors.ok; PermStatus.DENIED -> StatusColors.bad; PermStatus.RESTRICTED -> StatusColors.warn; PermStatus.NOT_SUPPORTED -> StatusColors.neutral },
                )
            }
            Text(p.why, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (p.status == PermStatus.DENIED || (p.status == PermStatus.RESTRICTED && p.id != "capture")) {
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    when (p.action) {
                        PermAction.REQUEST_RUNTIME -> {
                            Button({ requester(arrayOf(p.permission!!)) }) { Text("Grant") }
                            OutlinedButton({ ctx.startActivity(PermissionCenter.appSettingsIntent(ctx)) }) { Text("Open app settings") }
                        }
                        PermAction.BATTERY -> Button({ runCatching { ctx.startActivity(PermissionCenter.batteryIntent(ctx)) }.onFailure { ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }) { Text("Open battery settings") }
                        PermAction.OVERLAY -> Button({ ctx.startActivity(PermissionCenter.overlayIntent(ctx)) }) { Text("Open overlay settings") }
                        else -> OutlinedButton({ ctx.startActivity(PermissionCenter.appSettingsIntent(ctx)) }) { Text("Open app settings") }
                    }
                }
            }
        } }
    }
    Spacer(Modifier.height(8.dp))
    InfoText("Sideloaded apps on Android 13+ may show some permissions as greyed out. Open App info → ⋮ → \"Allow restricted settings\", then grant them.")
    OutlinedButton({ ctx.startActivity(PermissionCenter.appSettingsIntent(ctx)) }) { Text("Open Android app settings") }
}

@Composable
fun AdbSection(vm: MainViewModel) {
    val ctx = LocalContext.current
    val tick = rememberResumeTick()
    val pkg = ctx.packageName
    val rows = remember(tick) { AdbModule.inspect(ctx) }
    val adbOn = remember(tick) { (vm.registry[com.ahmedmunna.callrecorder.domain.EngineId.ADB] as com.ahmedmunna.callrecorder.engine.AdbEngine).isAdbEnabled(ctx) }
    val wifiOn = remember(tick) { runCatching { Settings.Global.getInt(ctx.contentResolver, "adb_wifi_enabled", 0) == 1 }.getOrDefault(false) }
    SoftCard { Column(Modifier.padding(18.dp)) {
        Text("Detected on this phone", style = MaterialTheme.typography.titleMedium)
        KeyValue("Package name", pkg)
        KeyValue("USB debugging", if (adbOn) "on" else "off")
        KeyValue("Wireless debugging", if (wifiOn) "on" else "off")
        InfoText(AdbModule.LIMITATION)
        OutlinedButton({
            runCatching { ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                .onFailure { Toast.makeText(ctx, "Developer options are not enabled yet. Tap Build number 7 times in About phone.", Toast.LENGTH_LONG).show() }
        }) { Text("Open Developer options") }
    } }
    SectionHeader("Permission & AppOps status")
    SoftCard { Column(Modifier.padding(18.dp)) {
        rows.forEach { r -> KeyValue(r.label, r.detail) }
        val problems = rows.filter { !it.granted && !it.label.contains("privileged") }
        if (problems.isEmpty()) InfoText("Everything ADB can configure is already in place.")
        else InfoText("To fix: ${problems.joinToString { it.label }}. Run the grant commands below.")
    } }
    SectionHeader("USB debugging")
    SoftCard { Column(Modifier.padding(18.dp)) { AdbModule.usbSteps.forEachIndexed { i, t -> InfoText("${i + 1}. $t") } } }
    SectionHeader("Wireless debugging")
    SoftCard { Column(Modifier.padding(18.dp)) { AdbModule.wirelessSteps.forEachIndexed { i, t -> InfoText("${i + 1}. $t") } } }
    SectionHeader("Commands for $pkg")
    CommandBlock("Inspect", AdbModule.inspectCommands(pkg))
    CommandBlock("Grant what ADB can grant", AdbModule.grantCommands(pkg))
    CommandBlock("Reset", AdbModule.resetCommands(pkg))
    InfoText("The same commands are in the adb/ folder of the project as ready-to-run scripts (setup_adb.sh, setup_adb.bat, check_permissions.sh, reset_adb.sh).")
}

@Composable
private fun CommandBlock(title: String, commands: List<String>) {
    val ctx = LocalContext.current
    SoftCard(Modifier.padding(vertical = 5.dp)) { Column(Modifier.padding(16.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            commands.joinToString("\n"), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(12.dp),
        )
        TextButton({ copyToClipboard(ctx, title, commands.joinToString("\n")) }) { Text("Copy commands") }
    } }
}

@Composable
fun RootSection() {
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<RootDetector.Status?>(null) }
    var busy by remember { mutableStateOf(false) }
    SoftCard { Column(Modifier.padding(18.dp)) {
        InfoText("Root is completely optional. The app installs and works normally without it. Detection only runs when you press the button, and all root code lives in a separate package.")
        Button({
            scope.launch { busy = true; status = withContext(Dispatchers.IO) { RootDetector.detect() }; busy = false }
        }, enabled = !busy) { Text(if (busy) "Checking…" else "Detect root") }
        status?.let { st ->
            Spacer(Modifier.height(8.dp))
            KeyValue("su binary", if (st.suBinaryFound) "found" else "not found")
            KeyValue("Root access", if (st.accessGranted) "granted" else "not available")
            InfoText(st.detail)
        }
        InfoText("The optional root backend is experimental. It uses the tinycap utility, which only some ROMs include, to read the call stream directly. If tinycap is missing, the Engine Test Center reports the root engine as unsupported. Select it manually under Recording Engine only after a passing test.")
    } }
}

@Composable
fun BackupSection(vm: MainViewModel) {
    val ctx = LocalContext.current
    val s by vm.settings.collectAsState()
    val recs by vm.recordings.collectAsState()
    fun toast(m: String) = Toast.makeText(ctx, m, Toast.LENGTH_LONG).show()
    val treePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) { persistTree(ctx, uri); vm.update { copy(backupTreeUri = uri.toString()) } }
    }
    val exportAll = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) vm.exportZip(uri, recs, ::toast)
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importZip(uri, ::toast)
    }
    SoftCard { Column(Modifier.padding(18.dp)) {
        Text("Backup folder", style = MaterialTheme.typography.titleMedium)
        InfoText(if (s.backupTreeUri == null) "No folder chosen." else "Folder: ${Uri.decode(s.backupTreeUri!!.substringAfterLast('/'))}")
        InfoText(if (s.lastBackupAt == 0L) "No backup yet." else "Last backup: ${Fmt.dateTime(s.lastBackupAt)}")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ treePicker.launch(null) }) { Text("Choose folder") }
            Button({ vm.backupNow(::toast) }, enabled = s.backupTreeUri != null) { Text("Back up now") }
        }
        ChoiceRow("Schedule", "Uses Android's background scheduler, so exact timing varies.", listOf(0, 1, 7, 30), s.backupIntervalDays, { if (it == 0) "Off" else if (it == 1) "Daily" else if (it == 7) "Weekly" else "Monthly" }, enabled = { it == 0 || s.backupTreeUri != null }) { d ->
            vm.update { copy(backupIntervalDays = d) }
            WorkScheduler.applyBackupSchedule(ctx, d)
        }
    } }
    SectionHeader("Export & import")
    SoftCard { Column(Modifier.padding(18.dp)) {
        InfoText("Export packs all audio files and their metadata (notes, tags, favorites, transcripts) into one ZIP. Import restores them and skips recordings that already exist.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ exportAll.launch("AdvancedCallRecorder-backup-${Fmt.isoDate(System.currentTimeMillis())}.zip") }, enabled = recs.isNotEmpty()) { Text("Export all") }
            OutlinedButton({ importer.launch(arrayOf("application/zip", "application/octet-stream")) }) { Text("Import / restore") }
        }
        InfoText("Backup files are NOT encrypted and include private recordings. Keep them somewhere you trust.")
    } }
}
