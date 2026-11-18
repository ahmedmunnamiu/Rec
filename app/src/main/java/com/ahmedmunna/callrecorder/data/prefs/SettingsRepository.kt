package com.ahmedmunna.callrecorder.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.ahmedmunna.callrecorder.domain.AudioFormat
import com.ahmedmunna.callrecorder.domain.EngineId
import com.ahmedmunna.callrecorder.domain.QualityPreset
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "settings")

enum class RecordScope(val label: String) {
    ALL("All calls"),
    SELECTED_ONLY("Selected numbers only"),
    UNKNOWN_ONLY("Unknown numbers only")
}

enum class ThemeMode(val label: String) { SYSTEM("System"), LIGHT("Light"), DARK("Dark") }
enum class AccentTheme(val label: String, val argb: Long) {
    INDIGO("Indigo", 0xFF3B5BDB),
    TEAL("Teal", 0xFF0CA678),
    ROSE("Rose", 0xFFD6336C),
    AMBER("Amber", 0xFFF08C00)
}

data class AppSettings(
    val autoRecord: Boolean = false,
    val scope: RecordScope = RecordScope.ALL,
    val recordUnknown: Boolean = true,
    val selectedNumbers: Set<String> = emptySet(),
    val excludedNumbers: Set<String> = emptySet(),
    val manualEngine: EngineId? = null,
    val speakerphoneConsent: Boolean = false,
    val format: AudioFormat = AudioFormat.M4A,
    val quality: QualityPreset = QualityPreset.HIGH,
    val customSampleRate: Int = 44100,
    val customBitrate: Int = 128_000,
    val stereo: Boolean = false,
    val noiseSuppression: Boolean = true,
    val echoCancellation: Boolean = true,
    val autoGain: Boolean = true,
    val micGainDb: Int = 0,
    val compression: Boolean = false,
    val maxDurationMin: Int = 0,
    val minDurationSec: Int = 0,
    val silenceStopSec: Int = 0,
    val autoRetry: Boolean = true,
    val storageTreeUri: String? = null,
    val callLogIntegration: Boolean = false,
    val contactsIntegration: Boolean = false,
    val notifyOnFailure: Boolean = true,
    val lowStorageMb: Int = 500,
    val storageLimitMb: Int = 0,
    val cleanupDays: Int = 0,
    val autoFavoriteMinSec: Int = 0,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val accent: AccentTheme = AccentTheme.INDIGO,
    val pinHash: String? = null,
    val pinSalt: String? = null,
    val biometricLock: Boolean = false,
    val lockPrivateFolder: Boolean = true,
    val backupIntervalDays: Int = 0,
    val backupTreeUri: String? = null,
    val transcription: Boolean = false,
    val lastBackupAt: Long = 0,
) {
    /** "Compact files" caps compressed (non-WAV) recordings at 16 kHz / 64 kbps. */
    private val compact: Boolean get() = compression && format != AudioFormat.WAV
    val sampleRate: Int
        get() {
            val base = format.fixedSampleRate ?: if (quality == QualityPreset.CUSTOM) customSampleRate else quality.sampleRate
            return if (compact) minOf(base, 16000) else base
        }
    val bitrate: Int
        get() {
            val base = if (quality == QualityPreset.CUSTOM) customBitrate else quality.bitrate
            return if (compact) minOf(base, 64_000) else base
        }
    val appLockEnabled: Boolean get() = pinHash != null || biometricLock
}

@Singleton
class SettingsRepository @Inject constructor(@ApplicationContext private val context: Context) {

    private object K {
        val autoRecord = booleanPreferencesKey("autoRecord")
        val scope = stringPreferencesKey("scope")
        val recordUnknown = booleanPreferencesKey("recordUnknown")
        val selectedNumbers = stringSetPreferencesKey("selectedNumbers")
        val excludedNumbers = stringSetPreferencesKey("excludedNumbers")
        val manualEngine = stringPreferencesKey("manualEngine")
        val speakerConsent = booleanPreferencesKey("speakerConsent")
        val format = stringPreferencesKey("format")
        val quality = stringPreferencesKey("quality")
        val customSampleRate = intPreferencesKey("customSampleRate")
        val customBitrate = intPreferencesKey("customBitrate")
        val stereo = booleanPreferencesKey("stereo")
        val ns = booleanPreferencesKey("ns")
        val aec = booleanPreferencesKey("aec")
        val agc = booleanPreferencesKey("agc")
        val micGainDb = intPreferencesKey("micGainDb")
        val compression = booleanPreferencesKey("compression")
        val maxDurationMin = intPreferencesKey("maxDurationMin")
        val minDurationSec = intPreferencesKey("minDurationSec")
        val silenceStopSec = intPreferencesKey("silenceStopSec")
        val autoRetry = booleanPreferencesKey("autoRetry")
        val storageTreeUri = stringPreferencesKey("storageTreeUri")
        val callLog = booleanPreferencesKey("callLog")
        val contacts = booleanPreferencesKey("contacts")
        val notifyFailure = booleanPreferencesKey("notifyFailure")
        val lowStorageMb = intPreferencesKey("lowStorageMb")
        val storageLimitMb = intPreferencesKey("storageLimitMb")
        val cleanupDays = intPreferencesKey("cleanupDays")
        val autoFavMin = intPreferencesKey("autoFavMin")
        val themeMode = stringPreferencesKey("themeMode")
        val accent = stringPreferencesKey("accent")
        val pinHash = stringPreferencesKey("pinHash")
        val pinSalt = stringPreferencesKey("pinSalt")
        val biometric = booleanPreferencesKey("biometric")
        val lockPrivate = booleanPreferencesKey("lockPrivate")
        val backupDays = intPreferencesKey("backupDays")
        val backupTree = stringPreferencesKey("backupTree")
        val transcription = booleanPreferencesKey("transcription")
        val lastBackup = longPreferencesKey("lastBackup")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p -> p.toSettings() }

    suspend fun current(): AppSettings = settings.first()

    suspend fun update(block: AppSettings.() -> AppSettings) {
        context.dataStore.edit { prefs ->
            val next = prefs.toSettings().block()
            prefs[K.autoRecord] = next.autoRecord
            prefs[K.scope] = next.scope.name
            prefs[K.recordUnknown] = next.recordUnknown
            prefs[K.selectedNumbers] = next.selectedNumbers
            prefs[K.excludedNumbers] = next.excludedNumbers
            setOrRemove(prefs, K.manualEngine, next.manualEngine?.name)
            prefs[K.speakerConsent] = next.speakerphoneConsent
            prefs[K.format] = next.format.name
            prefs[K.quality] = next.quality.name
            prefs[K.customSampleRate] = next.customSampleRate
            prefs[K.customBitrate] = next.customBitrate
            prefs[K.stereo] = next.stereo
            prefs[K.ns] = next.noiseSuppression
            prefs[K.aec] = next.echoCancellation
            prefs[K.agc] = next.autoGain
            prefs[K.micGainDb] = next.micGainDb
            prefs[K.compression] = next.compression
            prefs[K.maxDurationMin] = next.maxDurationMin
            prefs[K.minDurationSec] = next.minDurationSec
            prefs[K.silenceStopSec] = next.silenceStopSec
            prefs[K.autoRetry] = next.autoRetry
            setOrRemove(prefs, K.storageTreeUri, next.storageTreeUri)
            prefs[K.callLog] = next.callLogIntegration
            prefs[K.contacts] = next.contactsIntegration
            prefs[K.notifyFailure] = next.notifyOnFailure
            prefs[K.lowStorageMb] = next.lowStorageMb
            prefs[K.storageLimitMb] = next.storageLimitMb
            prefs[K.cleanupDays] = next.cleanupDays
            prefs[K.autoFavMin] = next.autoFavoriteMinSec
            prefs[K.themeMode] = next.themeMode.name
            prefs[K.accent] = next.accent.name
            setOrRemove(prefs, K.pinHash, next.pinHash)
            setOrRemove(prefs, K.pinSalt, next.pinSalt)
            prefs[K.biometric] = next.biometricLock
            prefs[K.lockPrivate] = next.lockPrivateFolder
            prefs[K.backupDays] = next.backupIntervalDays
            setOrRemove(prefs, K.backupTree, next.backupTreeUri)
            prefs[K.transcription] = next.transcription
            prefs[K.lastBackup] = next.lastBackupAt
        }
    }

    private fun setOrRemove(p: androidx.datastore.preferences.core.MutablePreferences, key: Preferences.Key<String>, v: String?) {
        if (v == null) p.remove(key) else p[key] = v
    }

    private inline fun <reified E : Enum<E>> enumOf(name: String?, default: E): E =
        runCatching { if (name == null) default else enumValueOf<E>(name) }.getOrDefault(default)

    private fun Preferences.toSettings(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            autoRecord = this[K.autoRecord] ?: d.autoRecord,
            scope = enumOf(this[K.scope], d.scope),
            recordUnknown = this[K.recordUnknown] ?: d.recordUnknown,
            selectedNumbers = this[K.selectedNumbers] ?: d.selectedNumbers,
            excludedNumbers = this[K.excludedNumbers] ?: d.excludedNumbers,
            manualEngine = this[K.manualEngine]?.let { n -> runCatching { enumValueOf<EngineId>(n) }.getOrNull() },
            speakerphoneConsent = this[K.speakerConsent] ?: d.speakerphoneConsent,
            format = enumOf(this[K.format], d.format),
            quality = enumOf(this[K.quality], d.quality),
            customSampleRate = this[K.customSampleRate] ?: d.customSampleRate,
            customBitrate = this[K.customBitrate] ?: d.customBitrate,
            stereo = this[K.stereo] ?: d.stereo,
            noiseSuppression = this[K.ns] ?: d.noiseSuppression,
            echoCancellation = this[K.aec] ?: d.echoCancellation,
            autoGain = this[K.agc] ?: d.autoGain,
            micGainDb = this[K.micGainDb] ?: d.micGainDb,
            compression = this[K.compression] ?: d.compression,
            maxDurationMin = this[K.maxDurationMin] ?: d.maxDurationMin,
            minDurationSec = this[K.minDurationSec] ?: d.minDurationSec,
            silenceStopSec = this[K.silenceStopSec] ?: d.silenceStopSec,
            autoRetry = this[K.autoRetry] ?: d.autoRetry,
            storageTreeUri = this[K.storageTreeUri],
            callLogIntegration = this[K.callLog] ?: d.callLogIntegration,
            contactsIntegration = this[K.contacts] ?: d.contactsIntegration,
            notifyOnFailure = this[K.notifyFailure] ?: d.notifyOnFailure,
            lowStorageMb = this[K.lowStorageMb] ?: d.lowStorageMb,
            storageLimitMb = this[K.storageLimitMb] ?: d.storageLimitMb,
            cleanupDays = this[K.cleanupDays] ?: d.cleanupDays,
            autoFavoriteMinSec = this[K.autoFavMin] ?: d.autoFavoriteMinSec,
            themeMode = enumOf(this[K.themeMode], d.themeMode),
            accent = enumOf(this[K.accent], d.accent),
            pinHash = this[K.pinHash],
            pinSalt = this[K.pinSalt],
            biometricLock = this[K.biometric] ?: d.biometricLock,
            lockPrivateFolder = this[K.lockPrivate] ?: d.lockPrivateFolder,
            backupIntervalDays = this[K.backupDays] ?: d.backupIntervalDays,
            backupTreeUri = this[K.backupTree],
            transcription = this[K.transcription] ?: d.transcription,
            lastBackupAt = this[K.lastBackup] ?: d.lastBackupAt,
        )
    }
}
