package com.ahmedmunna.callrecorder.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.ahmedmunna.callrecorder.backup.BackupManager
import com.ahmedmunna.callrecorder.data.db.RecordingEntity
import com.ahmedmunna.callrecorder.data.prefs.AppSettings
import com.ahmedmunna.callrecorder.data.prefs.SettingsRepository
import com.ahmedmunna.callrecorder.data.repo.RecordingRepository
import com.ahmedmunna.callrecorder.service.RecordingService
import com.ahmedmunna.callrecorder.service.RecoveryManager
import com.ahmedmunna.callrecorder.storage.StorageManager
import com.ahmedmunna.callrecorder.util.Notifier
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * Automatic cleanup NEVER deletes anything. It only finds candidates; the user reviews and confirms
 * deletion in Settings → Storage.
 */
object CleanupPlanner {
    fun candidates(all: List<RecordingEntity>, s: AppSettings, now: Long = System.currentTimeMillis()): List<RecordingEntity> {
        val protectedIds = all.filter { it.favorite || it.important }.map { it.id }.toSet()
        val eligible = all.filter { it.id !in protectedIds && it.status != com.ahmedmunna.callrecorder.domain.RecordingStatus.RECORDING }
        val picked = LinkedHashMap<Long, RecordingEntity>()
        if (s.cleanupDays > 0) {
            val cutoff = now - s.cleanupDays * 86_400_000L
            eligible.filter { it.startTime < cutoff }.forEach { picked[it.id] = it }
        }
        if (s.storageLimitMb > 0) {
            val limit = s.storageLimitMb * 1024L * 1024L
            var total = all.sumOf { it.fileSize }
            for (e in eligible.sortedBy { it.startTime }) {
                if (total <= limit) break
                picked[e.id] = e; total -= e.fileSize
            }
        }
        return picked.values.sortedBy { it.startTime }
    }
}

@HiltWorker
class MaintenanceWorker @AssistedInject constructor(
    @Assisted ctx: Context,
    @Assisted params: WorkerParameters,
    private val repo: RecordingRepository,
    private val storage: StorageManager,
    private val settingsRepo: SettingsRepository,
) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        RecoveryManager(repo, storage).recover(RecordingService.activeRowId)
        repo.checkIntegrity()
        val s = settingsRepo.current()
        if (storage.isLowStorage(s.lowStorageMb)) {
            Notifier.alert(applicationContext, Notifier.ID_LOW_STORAGE, "Storage is running low", "Less than ${s.lowStorageMb} MB is free.")
        }
        val c = CleanupPlanner.candidates(repo.all(), s)
        if (c.isNotEmpty()) {
            Notifier.alert(
                applicationContext, Notifier.ID_CLEANUP, "Recordings ready for cleanup review",
                "${c.size} recording(s) (${c.sumOf { it.fileSize } / (1024 * 1024)} MB) match your cleanup rules. Nothing is deleted until you confirm in Settings → Storage."
            )
        }
        return Result.success()
    }
}

@HiltWorker
class BackupWorker @AssistedInject constructor(
    @Assisted ctx: Context,
    @Assisted params: WorkerParameters,
    private val backup: BackupManager,
    private val settingsRepo: SettingsRepository,
) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val s = settingsRepo.current()
        val tree = s.backupTreeUri ?: return Result.success()
        val r = backup.backupToTree(tree)
        if (r.count > 0) settingsRepo.update { copy(lastBackupAt = System.currentTimeMillis()) }
        Notifier.alert(applicationContext, Notifier.ID_BACKUP, if (r.count > 0) "Backup complete" else "Backup problem", r.message)
        return Result.success()
    }
}

object WorkScheduler {
    private const val MAINTENANCE = "maintenance"
    private const val BACKUP = "scheduled-backup"

    fun scheduleMaintenance(ctx: Context) {
        val wm = WorkManager.getInstance(ctx)
        wm.enqueueUniquePeriodicWork(MAINTENANCE, ExistingPeriodicWorkPolicy.KEEP, PeriodicWorkRequestBuilder<MaintenanceWorker>(12, TimeUnit.HOURS).build())
    }

    fun runMaintenanceNow(ctx: Context) {
        WorkManager.getInstance(ctx).enqueueUniqueWork("maintenance-now", ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<MaintenanceWorker>().build())
    }

    fun applyBackupSchedule(ctx: Context, intervalDays: Int) {
        val wm = WorkManager.getInstance(ctx)
        if (intervalDays <= 0) { wm.cancelUniqueWork(BACKUP); return }
        wm.enqueueUniquePeriodicWork(
            BACKUP, ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<BackupWorker>(intervalDays.toLong(), TimeUnit.DAYS).build()
        )
    }
}
