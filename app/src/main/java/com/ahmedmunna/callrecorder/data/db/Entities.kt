package com.ahmedmunna.callrecorder.data.db

import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.Index
import androidx.room.PrimaryKey
import com.ahmedmunna.callrecorder.domain.AudioFormat
import com.ahmedmunna.callrecorder.domain.CallDirection
import com.ahmedmunna.callrecorder.domain.CaptureMode
import com.ahmedmunna.callrecorder.domain.EngineId
import com.ahmedmunna.callrecorder.domain.EngineState
import com.ahmedmunna.callrecorder.domain.RecordingStatus

@Entity(tableName = "recordings", indices = [Index("startTime"), Index("filePath")])
data class RecordingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val phoneNumber: String,
    val contactName: String? = null,
    val direction: CallDirection,
    val startTime: Long,
    val endTime: Long? = null,
    val durationMs: Long = 0,
    val fileSize: Long = 0,
    /** Absolute file path, or a content:// URI when stored through the Storage Access Framework. */
    val filePath: String,
    val format: AudioFormat,
    val engine: EngineId,
    val quality: String,
    val captureMode: CaptureMode,
    val status: RecordingStatus,
    val favorite: Boolean = false,
    val important: Boolean = false,
    val isPrivate: Boolean = false,
    val notes: String = "",
    val tags: String = "",
    val transcript: String? = null,
    val failureReason: String? = null,
    val lastPlayPositionMs: Long = 0,
) {
    @get:Ignore
    val isUri: Boolean get() = filePath.startsWith("content://")
    @get:Ignore
    val isUnknownNumber: Boolean get() = phoneNumber.isBlank() || phoneNumber == "UNKNOWN"
}

@Entity(tableName = "engine_tests")
data class EngineTestEntity(
    @PrimaryKey val engine: EngineId,
    val state: EngineState,
    val captureMode: CaptureMode,
    val testedAt: Long,
    val error: String? = null,
    val peak: Float = 0f,
    val rms: Float = 0f,
    val clippingPct: Float = 0f,
    val silencePct: Float = 0f,
    val verdict: String = "",
    val testFilePath: String? = null,
)
