package com.ahmedmunna.callrecorder.ui.screens

import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ahmedmunna.callrecorder.BuildConfig
import com.ahmedmunna.callrecorder.data.prefs.AccentTheme
import com.ahmedmunna.callrecorder.data.prefs.RecordScope
import com.ahmedmunna.callrecorder.data.prefs.ThemeMode
import com.ahmedmunna.callrecorder.domain.AudioFormat
import com.ahmedmunna.callrecorder.domain.CaptureMode
import com.ahmedmunna.callrecorder.domain.EngineCategory
import com.ahmedmunna.callrecorder.domain.EngineId
import com.ahmedmunna.callrecorder.domain.QualityPreset
import com.ahmedmunna.callrecorder.engine.BluetoothEngine
import com.ahmedmunna.callrecorder.engine.EncoderSupport
import com.ahmedmunna.callrecorder.security.Biometrics
import com.ahmedmunna.callrecorder.ui.MainViewModel
import com.ahmedmunna.callrecorder.ui.common.Fmt
import com.ahmedmunna.callrecorder.ui.common.SectionHeader
import com.ahmedmunna.callrecorder.ui.common.SoftCard
import com.ahmedmunna.callrecorder.ui.theme.StatusColors

private data class Section(val id: String, val title: String, val subtitle: String, val icon: ImageVector)

private val sections = listOf(
    Section("auto", "Automatic Recording", "What gets recorded, exclusions, minimum length", Icons.Filled.Bolt),
    Section("engine", "Recording Engine", "Automatic or manual engine, speakerphone, Bluetooth", Icons.Filled.GraphicEq),
    Section("quality", "Audio Quality", "Format, sample rate, bitrate, processing, quality test", Icons.Filled.Tune),
    Section("storage", "Storage", "Location, usage, limits, cleanup, integrity", Icons.Filled.Storage),
    Section("notifications", "Notifications", "Failure, storage and recording alerts", Icons.Filled.Notifications),
    Section("permissions", "Permissions", "Permission Center with explanations", Icons.Filled.VerifiedUser),
    Section("adb", "ADB Setup", "Commands and status for your package", Icons.Filled.Terminal),
    Section("root", "Root Status", "Optional root backend", Icons.Filled.Shield),
    Section("backup", "Backup", "Backup, schedule, restore, export, import", Icons.Filled.Backup),
    Section("security", "Privacy & Security", "PIN, biometrics, private folder, transcription, theme", Icons.Filled.Security),
    Section("about", "About", "Version, limitations, legal notice", Icons.Filled.Info),
)

@Composable
fun SettingsMenu(onOpen: (String) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))
        sections.forEach { s ->
            SoftCard(Modifier.padding(bottom = 10.dp)) {
                Row(Modifier.fillMaxWidth().clickable { onOpen(s.id) }.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(s.icon, null, tint = MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                        Text(s.title, style = MaterialTheme.typography.titleMedium)
                        Text(s.subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSection(vm: MainViewModel, id: String, onBack: () -> Unit, onOpenEngineTest: () -> Unit) {
    val title = sections.firstOrNull { it.id == id }?.title ?: "Settings"
    Scaffold(contentWindowInsets = WindowInsets(0, 0, 0, 0), topBar = {
        TopAppBar(windowInsets = WindowInsets(0, 0, 0, 0), title = { Text(title) }, navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } })
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            when (id) {
                "auto" -> AutoSection(vm)
                "engine" -> EngineSection(vm, onOpenEngineTest)
                "quality" -> QualitySection(vm)
                "storage" -> StorageSection(vm)
                "notifications" -> NotificationsSection(vm)
                "permissions" -> PermissionsSection()
                "adb" -> AdbSection(vm)
                "root" -> RootSection()
                "backup" -> BackupSection(vm)
                "security" -> SecuritySection(vm)
                "about" -> AboutSection()
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun AutoSection(vm: MainViewModel) {
    val s by vm.settings.collectAsState()
    SoftCard { Column(Modifier.padding(18.dp)) {
        SettingSwitch("Automatic recording", "Record calls without opening the app.", s.autoRecord) { v -> vm.update { copy(autoRecord = v) } }
        ChoiceRow("Which calls", null, RecordScope.entries, s.scope, { it.label }) { v -> vm.update { copy(scope = v) } }
        SettingSwitch("Record unknown numbers", "Calls from numbers Android does not reveal.", s.recordUnknown) { v -> vm.update { copy(recordUnknown = v) } }
        SettingSwitch("Link with call log", "Reads your call log to learn the number of outgoing calls. Needs the Call log permission.", s.callLogIntegration) { v -> vm.update { copy(callLogIntegration = v) } }
        SettingSwitch("Show contact names and photos", "Looks numbers up in your contacts on this device only. Needs the Contacts permission.", s.contactsIntegration) { v -> vm.update { copy(contactsIntegration = v) } }
    } }
    SectionHeader("Number rules")
    SoftCard { Column(Modifier.padding(18.dp)) {
        NumberListEditor("Selected numbers", "Used when \"Selected numbers only\" is chosen above.", s.selectedNumbers) { v -> vm.update { copy(selectedNumbers = v) } }
        NumberListEditor("Excluded numbers", "These calls are never kept, in any mode.", s.excludedNumbers) { v -> vm.update { copy(excludedNumbers = v) } }
    } }
    SectionHeader("Lengths and favorites")
    SoftCard { Column(Modifier.padding(18.dp)) {
        SettingSlider("Minimum call length to keep", if (s.minDurationSec == 0) "Keep all" else "${s.minDurationSec} s", s.minDurationSec.toFloat(), 0f..60f, 11) { v -> vm.update { copy(minDurationSec = v.toInt()) } }
        InfoText("Shorter recordings are deleted automatically when the call ends.")
        SettingSlider("Auto-favorite calls longer than", if (s.autoFavoriteMinSec == 0) "Off" else "${s.autoFavoriteMinSec / 60} min", (s.autoFavoriteMinSec / 60).toFloat(), 0f..30f, 29) { v -> vm.update { copy(autoFavoriteMinSec = v.toInt() * 60) } }
    } }
}

@Composable
private fun EngineSection(vm: MainViewModel, onOpenEngineTest: () -> Unit) {
    val ctx = LocalContext.current
    val s by vm.settings.collectAsState()
    val rec by vm.recommendation.collectAsState()
    SoftCard { Column(Modifier.padding(18.dp)) {
        Text("Recommended from your tests", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        Text(rec.engine.label, style = MaterialTheme.typography.titleLarge)
        InfoText("${rec.expectedMode.label}. ${rec.reason}")
        if (rec.expectedMode != CaptureMode.TWO_WAY_VERIFIED) InfoText("Recordings will be labelled with what was actually captured, never as two-way unless a call test proved it.")
        Button(onOpenEngineTest) { Text("Open Engine Test Center") }
    } }
    SectionHeader("Selection")
    SoftCard { Column(Modifier.padding(18.dp)) {
        ChoiceRow(
            "Engine", "Automatic uses your test results. Manual forces one engine.",
            listOf<EngineId?>(null) + vm.registry.all.map { it.id }.filter { it.isRecordable },
            s.manualEngine, { it?.label ?: "Automatic" },
        ) { v -> vm.update { copy(manualEngine = v) } }
        SettingSwitch("Automatic fallback if the engine fails", "Retries with other microphone sources so a call is not lost.", s.autoRetry) { v -> vm.update { copy(autoRetry = v) } }
    } }
    SectionHeader("Speakerphone-assisted recording")
    SoftCard { Column(Modifier.padding(18.dp)) {
        SettingSwitch("Allow speakerphone routing", "Lets the app switch the call to the loudspeaker while recording, and restore it afterwards. The loudspeaker lets the microphone hear the other person.", s.speakerphoneConsent) { v -> vm.update { copy(speakerphoneConsent = v) } }
        InfoText("Limits: quality depends on the room, the other person's voice is quieter and echoey, and everyone nearby is recorded too. Nothing is routed unless this switch is on.")
    } }
    BluetoothEngine.routingWarning(ctx)?.let { w -> Spacer(Modifier.height(12.dp)); WarningCard(w, null) {} }
}

@Composable
private fun QualitySection(vm: MainViewModel) {
    val s by vm.settings.collectAsState()
    val run by vm.testRun.collectAsState()
    val result by vm.qualityResult.collectAsState()
    val formats = remember { EncoderSupport.availableFormats() }
    val rates = remember(s.format) { EncoderSupport.supportedSampleRates(s.format) }
    val bitrates = remember(s.format, s.sampleRate) { EncoderSupport.supportedBitrates(s.format, s.sampleRate) }
    val stereo = remember(s.format) { EncoderSupport.supportsStereo(s.format) }
    val ns = remember { EncoderSupport.noiseSuppressionAvailable() }
    val aec = remember { EncoderSupport.echoCancellerAvailable() }
    val agc = remember { EncoderSupport.agcAvailable() }
    val isWav = s.format == AudioFormat.WAV
    val fixed = s.format.fixedSampleRate != null

    SoftCard { Column(Modifier.padding(18.dp)) {
        ChoiceRow("Format", "Only formats this phone can encode are selectable.", AudioFormat.entries, s.format, { it.label }, enabled = { it in formats }) { f ->
            vm.update { copy(format = f) }
        }
        InfoText("MP3 is listed but disabled: Android includes no MP3 encoder, so it cannot be recorded. Use M4A/AAC for small files or WAV for lossless.")
        ChoiceRow("Quality", null, QualityPreset.entries, s.quality, { it.label }) { q -> vm.update { copy(quality = q) } }
        if (fixed) InfoText("${s.format.label} always records at ${s.format.fixedSampleRate} Hz with a fixed bitrate.")
        if (s.quality == QualityPreset.CUSTOM && !fixed) {
            ChoiceRow("Sample rate", "Rates your encoder supports", EncoderSupport.SAMPLE_RATES, s.customSampleRate, { "$it Hz" }, enabled = { it in rates }) { r -> vm.update { copy(customSampleRate = r) } }
            if (!isWav) ChoiceRow("Bitrate", "Bitrates your encoder supports at this sample rate", EncoderSupport.BITRATES, s.customBitrate, { "${it / 1000} kbps" }, enabled = { it in bitrates }) { b -> vm.update { copy(customBitrate = b) } }
        } else if (!fixed) {
            InfoText("Preset uses ${s.quality.sampleRate} Hz" + (if (isWav) "" else " and ${s.quality.bitrate / 1000} kbps") + ". Choose Custom to set values yourself.")
        }
        SettingSwitch("Stereo", if (stereo) "Most phones have one microphone, so stereo mostly doubles file size." else "Not supported for this format on this phone.", s.stereo && stereo, stereo) { v -> vm.update { copy(stereo = v) } }
        SettingSwitch("Compact files", "Caps compressed recordings at 16 kHz / 64 kbps. Smaller files, speech stays clear. Not applied to WAV.", s.compression, !isWav) { v -> vm.update { copy(compression = v) } }
    } }
    SectionHeader("Audio processing")
    SoftCard { Column(Modifier.padding(18.dp)) {
        InfoText("These effects can only be attached on the WAV recording path. AAC, 3GP and AMR recordings are encoded directly by Android and use the audio source's own processing.")
        SettingSwitch("Noise suppression", if (ns) "Applied to WAV recordings." else "Not available on this phone.", s.noiseSuppression && ns, ns && isWav) { v -> vm.update { copy(noiseSuppression = v) } }
        SettingSwitch("Echo cancellation", if (aec) "Applied to WAV recordings. Helps with speakerphone recording." else "Not available on this phone.", s.echoCancellation && aec, aec && isWav) { v -> vm.update { copy(echoCancellation = v) } }
        SettingSwitch("Automatic gain control", if (agc) "Applied to WAV recordings. Evens out loud and quiet parts." else "Not available on this phone.", s.autoGain && agc, agc && isWav) { v -> vm.update { copy(autoGain = v) } }
        if (isWav) SettingSlider("Microphone gain", "${if (s.micGainDb > 0) "+" else ""}${s.micGainDb} dB", s.micGainDb.toFloat(), -12f..24f, 35) { v -> vm.update { copy(micGainDb = v.toInt()) } }
        InfoText("Gain is applied only when recording as WAV. Other formats are encoded directly by Android and cannot be amplified.")
    } }
    SectionHeader("Limits")
    SoftCard { Column(Modifier.padding(18.dp)) {
        SettingSlider("Maximum recording length", if (s.maxDurationMin == 0) "No limit" else "${s.maxDurationMin} min", s.maxDurationMin.toFloat(), 0f..180f, 35) { v -> vm.update { copy(maxDurationMin = (v.toInt() / 5) * 5) } }
        SettingSlider("Stop after silence", if (s.silenceStopSec == 0) "Off" else "${s.silenceStopSec} s", s.silenceStopSec.toFloat(), 0f..120f, 11) { v -> vm.update { copy(silenceStopSec = (v.toInt() / 10) * 10) } }
        InfoText("Silence detection ends the recording after that long without sound. A one-sided microphone recording is silent when the other person speaks, so use a generous value.")
    } }
    SectionHeader("Recording quality test")
    SoftCard { Column(Modifier.padding(18.dp)) {
        InfoText("Records 5 seconds through the real recording path with the settings above.")
        Button({ vm.runQualityTest() }, enabled = run == null) { Text(if (run != null) "Recording…" else "Run quality test") }
        run?.progress?.let { p ->
            LinearProgressIndicator(progress = { p.level.coerceIn(0f, 1f) }, Modifier.fillMaxWidth().padding(top = 8.dp).height(8.dp))
            Text("${p.instruction} (${p.secondsLeft}s)", style = MaterialTheme.typography.bodyMedium)
        }
        result?.let { q ->
            Spacer(Modifier.height(8.dp))
            Text(q.verdict, style = MaterialTheme.typography.bodyLarge, color = if (q.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            Text("Format: ${q.resolvedFormat}", style = MaterialTheme.typography.bodyMedium)
            if (q.file != null) Text("File size: ${Fmt.size(q.bytes)} (about ${Fmt.size(q.bytes * 12)} per minute) · peak ${(q.peak * 100).toInt()}% · active ${(q.loudShare * 100).toInt()}% of the time", style = MaterialTheme.typography.bodyMedium)
            q.adjustments.forEach { Text("Adjusted: $it", style = MaterialTheme.typography.bodyMedium, color = StatusColors.warn) }
            q.error?.let { Text("Error: $it", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
            if (q.file != null) OutlinedButton({ vm.playQualityFile() }) { Text("Play test recording") }
        }
    } }
}

@Composable
private fun NotificationsSection(vm: MainViewModel) {
    val ctx = LocalContext.current
    val s by vm.settings.collectAsState()
    SoftCard { Column(Modifier.padding(18.dp)) {
        InfoText("While a call is being recorded Android requires a visible notification. It cannot be turned off, and it makes sure you always know when recording is active.")
        SettingSwitch("Recording failure alerts", "Tell me when a call could not be recorded.", s.notifyOnFailure) { v -> vm.update { copy(notifyOnFailure = v) } }
        SettingSlider("Low-storage warning below", if (s.lowStorageMb == 0) "Off" else "${s.lowStorageMb} MB free", s.lowStorageMb.toFloat(), 0f..2000f, 19) { v -> vm.update { copy(lowStorageMb = (v.toInt() / 100) * 100) } }
        SettingSlider("Recording storage limit warning", if (s.storageLimitMb == 0) "Off" else "${s.storageLimitMb} MB", s.storageLimitMb.toFloat(), 0f..10_000f, 19) { v -> vm.update { copy(storageLimitMb = (v.toInt() / 500) * 500) } }
        OutlinedButton({
            ctx.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }) { Text("Open Android notification settings") }
    } }
}

@Composable
private fun SecuritySection(vm: MainViewModel) {
    val ctx = LocalContext.current
    val s by vm.settings.collectAsState()
    var setPin by remember { mutableStateOf(false) }
    var clearPin by remember { mutableStateOf(false) }
    val bioOk = remember { Biometrics.available(ctx) }
    SoftCard { Column(Modifier.padding(18.dp)) {
        Text("App lock", style = MaterialTheme.typography.titleMedium)
        InfoText(if (s.pinHash != null) "A PIN is set. It is stored only as a salted hash." else "No PIN set.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ setPin = true }) { Text(if (s.pinHash != null) "Change PIN" else "Set PIN") }
            if (s.pinHash != null) OutlinedButton({ clearPin = true }) { Text("Remove PIN") }
        }
        SettingSwitch("Biometric / device credential lock", if (bioOk) "Unlock with fingerprint, face or your screen lock." else "Set up a fingerprint or screen lock in Android settings first.", s.biometricLock && bioOk, bioOk) { v -> vm.update { copy(biometricLock = v) } }
        SettingSwitch("Protect private folder with the lock", "Private recordings stay hidden until you unlock this session.", s.lockPrivateFolder) { v -> vm.update { copy(lockPrivateFolder = v) } }
        InfoText("The lock hides the app's screens. Recording files remain normal files in the app's media folder; use the Private folder or device encryption for stronger protection.")
    } }
    SectionHeader("Transcription")
    SoftCard { Column(Modifier.padding(18.dp)) {
        SettingSwitch("Speech-to-text transcription", "Off by default. Runs only with Android's on-device recogniser; audio never leaves the phone. Needs Android 13+ and an installed offline speech model. Quality varies and long calls may fail.", s.transcription) { v -> vm.update { copy(transcription = v) } }
        InfoText(if (vm.transcriptionAvailable()) "An on-device recogniser is available on this phone." else "No on-device recogniser detected on this phone, so transcription will likely fail.")
    } }
    SectionHeader("Appearance")
    SoftCard { Column(Modifier.padding(18.dp)) {
        ChoiceRow("Theme", null, ThemeMode.entries, s.themeMode, { it.label }) { v -> vm.update { copy(themeMode = v) } }
        ChoiceRow("Accent color", null, AccentTheme.entries, s.accent, { it.label }) { v -> vm.update { copy(accent = v) } }
    } }
    SectionHeader("Privacy")
    SoftCard { Column(Modifier.padding(18.dp)) {
        InfoText("Recordings, contacts and call information never leave this device: the app has no internet permission and no analytics. Recording calls is regulated by law in many places. You are responsible for informing the other person and obtaining consent where required.")
    } }
    if (setPin) SetPinDialog({ vm.setPin(it); setPin = false; Toast.makeText(ctx, "PIN saved", Toast.LENGTH_SHORT).show() }, { setPin = false })
    if (clearPin) PinEntryDialog("Enter current PIN to remove it", vm, { vm.clearPin(); clearPin = false }, { clearPin = false })
}

@Composable
private fun AboutSection() {
    val ctx = LocalContext.current
    SoftCard { Column(Modifier.padding(18.dp)) {
        Text("Advanced Auto Call Recorder", style = MaterialTheme.typography.titleLarge)
        Text("Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", style = MaterialTheme.typography.bodyMedium)
        Text("Package ${ctx.packageName}", style = MaterialTheme.typography.bodyMedium)
        Text("Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT}) · ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}", style = MaterialTheme.typography.bodyMedium)
    } }
    SectionHeader("What to expect")
    SoftCard { Column(Modifier.padding(18.dp)) {
        InfoText("Since Android 10 normal apps cannot capture the phone call's audio stream. On most phones this app records through the microphone, which captures your voice clearly and the other person only when the speakerphone is used.")
        InfoText("VOICE_CALL, VOICE_UPLINK and VOICE_DOWNLINK need a system-only permission. ADB and this app cannot grant it. Some vendors or custom ROMs allow it anyway; the Engine Test Center measures that on your phone and never assumes it.")
        InfoText("Android 12+ restricts starting a microphone service from the background. If auto recording misses calls, grant the optional exemptions in the Permission Center.")
        InfoText("Recording calls may be illegal without consent. Use this app responsibly and lawfully. No recording is hidden: a notification is always shown while recording.")
    } }
}
