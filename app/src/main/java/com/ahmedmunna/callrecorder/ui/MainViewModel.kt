package com.ahmedmunna.callrecorder.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ahmedmunna.callrecorder.backup.BackupManager
import com.ahmedmunna.callrecorder.data.db.EngineTestEntity
import com.ahmedmunna.callrecorder.data.db.RecordingEntity
import com.ahmedmunna.callrecorder.data.prefs.AppSettings
import com.ahmedmunna.callrecorder.data.prefs.SettingsRepository
import com.ahmedmunna.callrecorder.data.repo.RecordingRepository
import com.ahmedmunna.callrecorder.domain.CallDirection
import com.ahmedmunna.callrecorder.domain.EngineId
import com.ahmedmunna.callrecorder.domain.RecordingStatus
import com.ahmedmunna.callrecorder.engine.EngineRegistry
import com.ahmedmunna.callrecorder.engine.EngineSelector
import com.ahmedmunna.callrecorder.engine.EngineTester
import com.ahmedmunna.callrecorder.engine.Recommendation
import com.ahmedmunna.callrecorder.engine.TestProgress
import com.ahmedmunna.callrecorder.player.AudioTrimmer
import com.ahmedmunna.callrecorder.player.PlayerController
import com.ahmedmunna.callrecorder.player.Waveform
import com.ahmedmunna.callrecorder.security.PinHasher
import com.ahmedmunna.callrecorder.storage.StorageManager
import com.ahmedmunna.callrecorder.transcribe.Transcriber
import com.ahmedmunna.callrecorder.work.CleanupPlanner
import com.ahmedmunna.callrecorder.work.WorkScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

enum class SortOrder(val label: String) {
    NEWEST("Newest first"), OLDEST("Oldest first"), LONGEST("Longest first"), LARGEST("Largest first")
}

enum class DirectionFilter(val label: String) { ALL("All"), INCOMING("Incoming"), OUTGOING("Outgoing") }
enum class DurationFilter(val label: String, val minMs: Long, val maxMs: Long) {
    ANY("Any length", 0, Long.MAX_VALUE),
    SHORT("Under 1 min", 0, 60_000),
    MEDIUM("1 – 5 min", 60_000, 300_000),
    LONG("Over 5 min", 300_000, Long.MAX_VALUE)
}

/** Library folders requested in the spec. */
enum class Folder(val label: String) {
    ALL("All"), INCOMING("Incoming Calls"), OUTGOING("Outgoing Calls"), CONTACTS("Contacts"),
    UNKNOWN("Unknown Numbers"), FAVORITES("Favorites"), IMPORTANT("Important"), FAILED("Failed"), PRIVATE("Private")
}

data class LibraryQuery(
    val text: String = "",
    val direction: DirectionFilter = DirectionFilter.ALL,
    val duration: DurationFilter = DurationFilter.ANY,
    val sort: SortOrder = SortOrder.NEWEST,
    val folder: Folder = Folder.ALL,
    val tag: String? = null,
)

data class HomeStats(val total: Int, val totalDurationMs: Long, val usedBytes: Long, val last: RecordingEntity?, val recent: List<RecordingEntity>)

data class TestRun(val engine: EngineId, val progress: TestProgress?)

@HiltViewModel
class MainViewModel @Inject constructor(
    private val app: Application,
    val settingsRepo: SettingsRepository,
    val repo: RecordingRepository,
    val storage: StorageManager,
    val registry: EngineRegistry,
    val tester: EngineTester,
    val player: PlayerController,
    val backup: BackupManager,
    private val trimmer: AudioTrimmer,
    private val transcriber: Transcriber,
) : AndroidViewModel(app) {

    val settings: StateFlow<AppSettings> = settingsRepo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
    val recordings: StateFlow<List<RecordingEntity>> =
        repo.observeAll().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val engineTests: StateFlow<List<EngineTestEntity>> =
        repo.observeEngineTests().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val recommendation: StateFlow<Recommendation> = combine(engineTests, settings) { t, s ->
        EngineSelector.recommend(t, s.speakerphoneConsent)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, Recommendation(EngineId.DEFAULT, com.ahmedmunna.callrecorder.domain.CaptureMode.MIC_ONLY, "No tests yet"))

    val stats: StateFlow<HomeStats> = recordings.combine(settings) { list, _ ->
        val ok = list.filter { it.status != RecordingStatus.FAILED }
        HomeStats(ok.size, ok.sumOf { it.durationMs }, list.sumOf { it.fileSize }, ok.firstOrNull(), ok.take(4))
    }.stateIn(viewModelScope, SharingStarted.Eagerly, HomeStats(0, 0, 0, null, emptyList()))

    // ----------------------------------------------------------- library

    private val _query = MutableStateFlow(LibraryQuery())
    val query: StateFlow<LibraryQuery> = _query.asStateFlow()
    fun setQuery(block: LibraryQuery.() -> LibraryQuery) { _query.value = _query.value.block() }

    /** Private recordings stay hidden until the app lock has been passed this session. */
    private val _privateUnlocked = MutableStateFlow(false)
    val privateUnlocked: StateFlow<Boolean> = _privateUnlocked.asStateFlow()
    fun setPrivateUnlocked(v: Boolean) { _privateUnlocked.value = v }

    val library: StateFlow<List<RecordingEntity>> =
        combine(recordings, _query, _privateUnlocked, settings) { all, q, unlocked, s ->
            applyQuery(all, q, unlocked || !s.lockPrivateFolder || !s.appLockEnabled)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val allTags: StateFlow<List<String>> = recordings.combine(settings) { list, _ ->
        list.flatMap { splitTags(it.tags) }.distinct().sorted()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun canShowPrivate(unlocked: Boolean, s: AppSettings) = unlocked || !s.lockPrivateFolder || !s.appLockEnabled

    fun applyQuery(all: List<RecordingEntity>, q: LibraryQuery, showPrivate: Boolean): List<RecordingEntity> {
        val text = q.text.trim().lowercase()
        val digits = text.filter { it.isDigit() }
        var list = all.filter { showPrivate || !it.isPrivate }
        list = when (q.folder) {
            Folder.ALL -> list.filter { !it.isPrivate && it.status != RecordingStatus.FAILED }
            Folder.INCOMING -> list.filter { !it.isPrivate && it.direction == CallDirection.INCOMING && it.status != RecordingStatus.FAILED }
            Folder.OUTGOING -> list.filter { !it.isPrivate && it.direction == CallDirection.OUTGOING && it.status != RecordingStatus.FAILED }
            Folder.CONTACTS -> list.filter { !it.isPrivate && it.contactName != null && it.status != RecordingStatus.FAILED }
            Folder.UNKNOWN -> list.filter { !it.isPrivate && it.contactName == null && it.status != RecordingStatus.FAILED }
            Folder.FAVORITES -> list.filter { it.favorite && it.status != RecordingStatus.FAILED }
            Folder.IMPORTANT -> list.filter { it.important && it.status != RecordingStatus.FAILED }
            Folder.FAILED -> list.filter { it.status == RecordingStatus.FAILED || it.status == RecordingStatus.INCOMPLETE }
            Folder.PRIVATE -> list.filter { it.isPrivate }
        }
        if (q.direction != DirectionFilter.ALL) list = list.filter { it.direction.name == q.direction.name }
        list = list.filter { it.durationMs in q.duration.minMs..q.duration.maxMs || q.duration == DurationFilter.ANY }
        q.tag?.let { t -> list = list.filter { t in splitTags(it.tags) } }
        if (text.isNotEmpty()) {
            list = list.filter { r ->
                r.phoneNumber.contains(text) ||
                    (digits.length >= 3 && r.phoneNumber.filter { it.isDigit() }.contains(digits)) ||
                    (r.contactName?.lowercase()?.contains(text) == true) ||
                    Fmt2.date(r.startTime).lowercase().contains(text) ||
                    Fmt2.iso(r.startTime).contains(text) ||
                    r.notes.lowercase().contains(text) ||
                    r.tags.lowercase().contains(text) ||
                    (r.transcript?.lowercase()?.contains(text) == true)
            }
        }
        return when (q.sort) {
            SortOrder.NEWEST -> list.sortedByDescending { it.startTime }
            SortOrder.OLDEST -> list.sortedBy { it.startTime }
            SortOrder.LONGEST -> list.sortedByDescending { it.durationMs }
            SortOrder.LARGEST -> list.sortedByDescending { it.fileSize }
        }
    }

    private object Fmt2 {
        fun date(ms: Long) = java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.getDefault()).format(java.util.Date(ms))
        fun iso(ms: Long) = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date(ms))
    }

    /** UI callbacks (Toasts, dialogs) must run on the main thread. */
    private suspend fun onMain(block: () -> Unit) = withContext(Dispatchers.Main) { block() }

    fun splitTags(s: String): List<String> = s.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    // ----------------------------------------------------------- settings

    fun update(block: AppSettings.() -> AppSettings) { viewModelScope.launch { settingsRepo.update(block) } }

    fun setPin(pin: String) {
        viewModelScope.launch(Dispatchers.Default) {
            val salt = PinHasher.newSalt()
            val hash = PinHasher.hash(pin, salt)
            settingsRepo.update { copy(pinSalt = salt, pinHash = hash) }
        }
    }

    suspend fun verifyPin(pin: String): Boolean = withContext(Dispatchers.Default) {
        val s = settingsRepo.current()
        val h = s.pinHash; val salt = s.pinSalt
        h != null && salt != null && PinHasher.verify(pin, salt, h)
    }

    fun clearPin() = update { copy(pinHash = null, pinSalt = null) }

    fun applyBackupSchedule() {
        WorkScheduler.applyBackupSchedule(app, settings.value.backupIntervalDays)
    }

    // ----------------------------------------------------------- recordings

    fun toggleFavorite(r: RecordingEntity) = viewModelScope.launch { repo.setFavorite(r.id, !r.favorite) }
    fun toggleImportant(r: RecordingEntity) = viewModelScope.launch { repo.update(r.copy(important = !r.important)) }
    fun togglePrivate(r: RecordingEntity, onResult: (String) -> Unit) = viewModelScope.launch(Dispatchers.IO) {
        if (r.isUri) { onMain { onResult("Recordings stored in a custom folder cannot be moved to the private folder.") }; return@launch }
        val newPrivate = !r.isPrivate
        val dir = storage.folderFor(r.direction, newPrivate)
        val src = java.io.File(r.filePath)
        val target = java.io.File(dir, src.name)
        if (src.renameTo(target)) {
            repo.update(r.copy(isPrivate = newPrivate, filePath = target.absolutePath))
            onMain { onResult(if (newPrivate) "Moved to the private folder" else "Moved out of the private folder") }
        } else onMain { onResult("Could not move the file") }
    }

    fun saveNotes(r: RecordingEntity, notes: String, tags: String) = viewModelScope.launch {
        repo.update((repo.get(r.id) ?: r).copy(notes = notes, tags = splitTags(tags).joinToString(", ")))
    }

    fun rename(r: RecordingEntity, name: String, onResult: (Boolean) -> Unit) = viewModelScope.launch(Dispatchers.IO) {
        val ok = repo.rename(r, name)
        onMain { onResult(ok) }
    }

    /** Call only after the user confirmed in a dialog. */
    fun deleteConfirmed(items: List<RecordingEntity>, onDone: () -> Unit = {}) = viewModelScope.launch(Dispatchers.IO) {
        if (items.any { it.id == player.state.value.currentId }) player.stop()
        repo.deleteConfirmed(items)
        withContext(Dispatchers.Main) { onDone() }
    }

    fun checkIntegrity(onDone: (String) -> Unit) = viewModelScope.launch(Dispatchers.IO) {
        val r = repo.checkIntegrity()
        onMain { onDone("Checked ${r.checked}: ${r.missingFiles} missing, ${r.emptyFiles} empty, ${r.sizesFixed} sizes corrected, ${r.orphansImported} found on disk and added.") }
    }

    fun cleanupCandidates(): List<RecordingEntity> = CleanupPlanner.candidates(recordings.value, settings.value)

    // ----------------------------------------------------------- backup

    fun exportZip(target: Uri, items: List<RecordingEntity>, onDone: (String) -> Unit) = viewModelScope.launch {
        onDone(runCatching { backup.exportZip(target, items).message }.getOrElse { "Export failed: ${it.message}" })
    }

    fun exportAudio(r: RecordingEntity, target: Uri, onDone: (String) -> Unit) = viewModelScope.launch {
        onDone(if (runCatching { backup.exportAudio(r, target) }.getOrDefault(false)) "Audio exported" else "Export failed")
    }

    fun importZip(source: Uri, onDone: (String) -> Unit) = viewModelScope.launch {
        onDone(runCatching { backup.importZip(source).message }.getOrElse { "Import failed: ${it.message}" })
    }

    fun backupNow(onDone: (String) -> Unit) = viewModelScope.launch {
        val tree = settings.value.backupTreeUri
        if (tree == null) { onDone("Choose a backup folder first"); return@launch }
        val r = runCatching { backup.backupToTree(tree) }.getOrElse { com.ahmedmunna.callrecorder.backup.BackupResult(0, 0, "Backup failed: ${it.message}") }
        if (r.count > 0) settingsRepo.update { copy(lastBackupAt = System.currentTimeMillis()) }
        onDone(r.message)
    }

    // ----------------------------------------------------------- trimming and waveform

    fun trim(r: RecordingEntity, startMs: Long, endMs: Long, onDone: (String) -> Unit) = viewModelScope.launch {
        val res = trimmer.trim(r, startMs, endMs)
        onDone(res.fold({ "Saved a trimmed copy. The original is unchanged." }, { "Trim failed: ${it.message}" }))
    }

    private var waveJob: Job? = null
    private val _wave = MutableStateFlow<Pair<Long, FloatArray>?>(null)
    val wave: StateFlow<Pair<Long, FloatArray>?> = _wave.asStateFlow()
    fun loadWaveform(r: RecordingEntity) {
        if (_wave.value?.first == r.id) return
        waveJob?.cancel()
        _wave.value = r.id to FloatArray(0)
        waveJob = viewModelScope.launch {
            val peaks = runCatching { Waveform.compute(app, storage.uriFor(r)) }.getOrDefault(FloatArray(0))
            _wave.value = r.id to peaks
        }
    }

    // ----------------------------------------------------------- transcription (optional, on device)

    private val _transcribing = MutableStateFlow<Long?>(null)
    val transcribing: StateFlow<Long?> = _transcribing.asStateFlow()

    fun transcriptionAvailable() = transcriber.isAvailable()

    fun transcribe(r: RecordingEntity, onDone: (String) -> Unit) {
        if (!settings.value.transcription) { onDone("Turn on transcription in Settings → Privacy & Security first"); return }
        viewModelScope.launch {
            _transcribing.value = r.id
            val result = transcriber.transcribe(r)
            _transcribing.value = null
            result.fold(
                { text -> repo.update((repo.get(r.id) ?: r).copy(transcript = text)); onDone("Transcript saved") },
                { onDone("Transcription failed: ${it.message}") },
            )
        }
    }

    // ----------------------------------------------------------- engine tests

    private val _run = MutableStateFlow<TestRun?>(null)
    val testRun: StateFlow<TestRun?> = _run.asStateFlow()
    private var testJob: Job? = null

    fun runTest(engineId: EngineId, guided: Boolean) {
        if (_run.value != null) return
        testJob = viewModelScope.launch {
            _run.value = TestRun(engineId, null)
            try {
                tester.test(registry[engineId], guided) { p -> _run.value = TestRun(engineId, p) }
            } finally {
                _run.value = null
            }
        }
    }

    fun runAllMicTests() {
        if (_run.value != null) return
        testJob = viewModelScope.launch {
            try {
                tester.testAllMicrophoneEngines { id -> _run.value = TestRun(id, null) }
            } finally { _run.value = null }
        }
    }

    fun cancelTest() { testJob?.cancel() }

    private val _quality = MutableStateFlow<EngineTester.QualityResult?>(null)
    val qualityResult: StateFlow<EngineTester.QualityResult?> = _quality.asStateFlow()

    /** Test-records 5 s using the active engine and the user's current quality settings. */
    fun runQualityTest() {
        if (_run.value != null) return
        val id = settings.value.manualEngine ?: recommendation.value.engine
        testJob = viewModelScope.launch {
            _run.value = TestRun(id, null)
            _quality.value = null
            try {
                _quality.value = tester.qualityTest(id) { p -> _run.value = TestRun(id, p) }
            } finally { _run.value = null }
        }
    }

    fun playQualityFile() {
        val q = _quality.value ?: return
        val f = q.file ?: return
        val fmt = com.ahmedmunna.callrecorder.domain.AudioFormat.entries.firstOrNull { it.extension == f.extension } ?: com.ahmedmunna.callrecorder.domain.AudioFormat.M4A
        player.play(
            RecordingEntity(
                id = -1000, phoneNumber = "Quality test", direction = CallDirection.UNKNOWN, startTime = System.currentTimeMillis(),
                filePath = f.absolutePath, format = fmt, engine = EngineId.DEFAULT, quality = "test",
                captureMode = com.ahmedmunna.callrecorder.domain.CaptureMode.NONE, status = RecordingStatus.COMPLETED,
            ),
            resume = false,
        )
    }

    fun playTestFile(e: EngineTestEntity) {
        val path = e.testFilePath ?: return
        val temp = RecordingEntity(
            id = -e.engine.ordinal.toLong() - 1, phoneNumber = "Engine test", direction = CallDirection.UNKNOWN,
            startTime = e.testedAt, filePath = path, format = com.ahmedmunna.callrecorder.domain.AudioFormat.WAV,
            engine = e.engine, quality = "test", captureMode = e.captureMode, status = RecordingStatus.COMPLETED,
        )
        player.play(temp, resume = false)
    }
}
