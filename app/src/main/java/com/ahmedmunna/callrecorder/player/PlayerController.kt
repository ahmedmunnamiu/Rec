package com.ahmedmunna.callrecorder.player

import android.content.ComponentName
import android.content.Context
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.ahmedmunna.callrecorder.data.db.RecordingEntity
import com.ahmedmunna.callrecorder.data.repo.RecordingRepository
import com.ahmedmunna.callrecorder.storage.StorageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class PlayerUi(
    val currentId: Long? = null,
    val playing: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val speed: Float = 1f,
    val repeat: Boolean = false,
    val volume: Float = 1f,
    val error: String? = null,
)

@OptIn(UnstableApi::class)
@Singleton
class PlayerController @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val storage: StorageManager,
    private val repo: RecordingRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val lock = Mutex()
    private var controller: MediaController? = null
    private val _state = MutableStateFlow(PlayerUi())
    val state: StateFlow<PlayerUi> = _state.asStateFlow()
    private var lastSavedAt = 0L

    private suspend fun ensure(): MediaController = lock.withLock {
        controller?.let { return it }
        val token = SessionToken(ctx, ComponentName(ctx, PlaybackService::class.java))
        val future = MediaController.Builder(ctx, token).buildAsync()
        val c = suspendCancellableCoroutine { cont ->
            future.addListener({
                try { cont.resume(future.get()) } catch (e: Exception) { cont.resumeWithException(e) }
            }, ContextCompat.getMainExecutor(ctx))
            cont.invokeOnCancellation { future.cancel(true) }
        }
        c.addListener(object : Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                _state.value = _state.value.copy(error = error.message ?: "Playback error", playing = false)
            }
        })
        controller = c
        scope.launch { poll() }
        c
    }

    private suspend fun poll() {
        while (true) {
            val c = controller ?: return
            val id = c.currentMediaItem?.mediaId?.toLongOrNull()
            _state.value = _state.value.copy(
                currentId = id, playing = c.isPlaying, positionMs = c.currentPosition.coerceAtLeast(0),
                durationMs = c.duration.takeIf { it > 0 } ?: _state.value.durationMs,
                speed = c.playbackParameters.speed, repeat = c.repeatMode == Player.REPEAT_MODE_ONE,
                volume = c.volume,
            )
            val now = System.currentTimeMillis()
            if (id != null && c.isPlaying && now - lastSavedAt > 5000) {
                lastSavedAt = now
                repo.setPlayPosition(id, c.currentPosition)
            }
            delay(250)
        }
    }

    /** Starts playback and resumes from the last saved position. */
    fun play(e: RecordingEntity, resume: Boolean = true) {
        scope.launch {
            val c = ensure()
            if (c.currentMediaItem?.mediaId == e.id.toString()) { c.play(); return@launch }
            saveCurrent()
            val item = MediaItem.Builder()
                .setMediaId(e.id.toString())
                .setUri(storage.uriFor(e))
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(e.contactName ?: e.phoneNumber)
                        .setArtist("${e.direction.name.lowercase().replaceFirstChar { it.uppercase() }} call")
                        .build()
                ).build()
            val start = if (resume && e.durationMs > 0 && e.lastPlayPositionMs in 1 until (e.durationMs - 1500)) e.lastPlayPositionMs else 0L
            _state.value = _state.value.copy(currentId = e.id, durationMs = e.durationMs, positionMs = start, error = null)
            c.setMediaItem(item, start)
            c.prepare()
            c.play()
        }
    }

    private suspend fun saveCurrent() {
        val c = controller ?: return
        c.currentMediaItem?.mediaId?.toLongOrNull()?.let { repo.setPlayPosition(it, c.currentPosition) }
    }

    fun toggle() = scope.launch { val c = ensure(); if (c.isPlaying) { c.pause(); saveCurrent() } else c.play() }
    fun stop() = scope.launch {
        val c = ensure(); saveCurrent(); c.stop(); c.clearMediaItems()
        _state.value = _state.value.copy(currentId = null, playing = false, positionMs = 0)
    }
    fun seekTo(ms: Long) = scope.launch { ensure().seekTo(ms) }
    fun skip(deltaMs: Long) = scope.launch {
        val c = ensure()
        val target = (c.currentPosition + deltaMs).coerceIn(0, if (c.duration > 0) c.duration else Long.MAX_VALUE)
        c.seekTo(target)
    }
    fun setSpeed(speed: Float) = scope.launch { ensure().setPlaybackSpeed(speed) }
    fun setRepeat(on: Boolean) = scope.launch { ensure().repeatMode = if (on) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF }
    fun setVolume(v: Float) = scope.launch { ensure().volume = v.coerceIn(0f, 1f) }
}
