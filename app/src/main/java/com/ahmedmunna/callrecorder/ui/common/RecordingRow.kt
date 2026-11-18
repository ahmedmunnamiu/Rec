package com.ahmedmunna.callrecorder.ui.common

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CallMade
import androidx.compose.material.icons.filled.CallReceived
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.ahmedmunna.callrecorder.data.db.RecordingEntity
import com.ahmedmunna.callrecorder.domain.CallDirection
import com.ahmedmunna.callrecorder.domain.RecordingStatus
import com.ahmedmunna.callrecorder.ui.theme.StatusColors
import com.ahmedmunna.callrecorder.util.ContactResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Increments every time the app returns to the foreground, so permission state can be re-read. */
@Composable
fun rememberResumeTick(): Int {
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
    return tick
}

/** Returns a function that asks for the given runtime permissions and calls [onResult] afterwards. */
@Composable
fun rememberPermissionRequester(onResult: () -> Unit = {}): (Array<String>) -> Unit {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { onResult() }
    return { perms -> launcher.launch(perms) }
}

@Composable
fun ContactAvatar(number: String, name: String?, modifier: Modifier = Modifier, size: Int = 44) {
    val ctx = LocalContext.current
    val photo by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, number) {
        value = withContext(Dispatchers.IO) {
            if (!ContactResolver.has(ctx) || number == "UNKNOWN") return@withContext null
            runCatching {
                val uri = ContactResolver.lookup(ctx, number).photoUri ?: return@runCatching null
                ctx.contentResolver.openInputStream(Uri.parse(uri))?.use { BitmapFactory.decodeStream(it)?.asImageBitmap() }
            }.getOrNull()
        }
    }
    Box(
        modifier.size(size.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        val p = photo
        if (p != null) Image(p, null, Modifier.size(size.dp), contentScale = ContentScale.Crop)
        else {
            val initial = (name ?: number).firstOrNull { it.isLetterOrDigit() || it == '+' }?.uppercase() ?: "?"
            Text(initial, color = MaterialTheme.colorScheme.onPrimaryContainer, style = MaterialTheme.typography.titleMedium)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RecordingRow(
    r: RecordingEntity,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onFavorite: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bg = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
    Row(
        modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).background(bg)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            ContactAvatar(r.phoneNumber, r.contactName)
            if (selected) Box(
                Modifier.size(20.dp).align(Alignment.BottomEnd).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.Check, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onPrimary) }
        }
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(
                r.contactName ?: if (r.isUnknownNumber) "Unknown number" else r.phoneNumber,
                style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(
                    if (r.direction == CallDirection.OUTGOING) Icons.Filled.CallMade else Icons.Filled.CallReceived, null,
                    Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "${Fmt.dateTime(r.startTime)} · ${Fmt.duration(r.durationMs)}" + if (r.fileSize > 0) " · ${Fmt.size(r.fileSize)}" else "",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            when (r.status) {
                RecordingStatus.FAILED -> Pill(r.failureReason ?: "Failed", StatusColors.bad)
                RecordingStatus.INCOMPLETE -> Pill("Recovered · may be incomplete", StatusColors.warn)
                RecordingStatus.RECORDING -> Pill("Recording…", StatusColors.ok)
                else -> if (r.isPrivate) Pill("Private", StatusColors.neutral)
            }
        }
        if (r.status != RecordingStatus.FAILED) {
            IconButton(onClick = onFavorite) {
                Icon(
                    if (r.favorite) Icons.Filled.Star else Icons.Outlined.StarBorder, "Favorite",
                    tint = if (r.favorite) StatusColors.warn else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
