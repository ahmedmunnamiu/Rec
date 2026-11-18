package com.ahmedmunna.callrecorder.ui.screens

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.ahmedmunna.callrecorder.data.db.RecordingEntity
import com.ahmedmunna.callrecorder.security.Biometrics
import com.ahmedmunna.callrecorder.ui.DirectionFilter
import com.ahmedmunna.callrecorder.ui.DurationFilter
import com.ahmedmunna.callrecorder.ui.Folder
import com.ahmedmunna.callrecorder.ui.LibraryQuery
import com.ahmedmunna.callrecorder.ui.MainViewModel
import com.ahmedmunna.callrecorder.ui.SortOrder
import com.ahmedmunna.callrecorder.ui.common.Fmt
import com.ahmedmunna.callrecorder.ui.common.RecordingRow
import com.ahmedmunna.callrecorder.ui.common.SoftCard

@Composable
fun LibraryScreen(vm: MainViewModel, favoritesOnly: Boolean, onOpen: (Long) -> Unit) {
    val ctx = LocalContext.current
    val all by vm.recordings.collectAsState()
    val settings by vm.settings.collectAsState()
    val unlocked by vm.privateUnlocked.collectAsState()
    val tags by vm.allTags.collectAsState()

    var q by remember { mutableStateOf(LibraryQuery(folder = if (favoritesOnly) Folder.FAVORITES else Folder.ALL)) }
    var selected by remember { mutableStateOf(setOf<Long>()) }
    var confirmDelete by remember { mutableStateOf(false) }
    var showSort by remember { mutableStateOf(false) }
    var pinPrompt by remember { mutableStateOf(false) }

    val items = remember(all, q, unlocked, settings.lockPrivateFolder, settings.pinHash, settings.biometricLock) {
        vm.applyQuery(all, q, vm.canShowPrivate(unlocked, settings))
    }
    val selectedItems = items.filter { it.id in selected }
    val selecting = selected.isNotEmpty()
    BackHandler(selecting) { selected = emptySet() }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) vm.exportZip(uri, selectedItems) { msg -> Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show(); selected = emptySet() }
    }

    fun openPrivate() {
        if (vm.canShowPrivate(unlocked, settings)) { q = q.copy(folder = Folder.PRIVATE); return }
        val act = ctx as? FragmentActivity
        if (settings.biometricLock && act != null && Biometrics.available(ctx)) {
            Biometrics.prompt(act, "Unlock private recordings", { vm.setPrivateUnlocked(true); q = q.copy(folder = Folder.PRIVATE) },
                { Toast.makeText(ctx, it, Toast.LENGTH_SHORT).show() })
        } else pinPrompt = true
    }

    Column(Modifier.fillMaxSize()) {
        // Header: title / selection bar
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (selecting) {
                IconButton({ selected = emptySet() }) { Icon(Icons.Filled.Close, "Clear selection") }
                Text("${selected.size} selected", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                IconButton({ selected = items.map { it.id }.toSet() }) { Icon(Icons.Filled.SelectAll, "Select all") }
                IconButton({ exportLauncher.launch("AdvancedCallRecorder-export-${Fmt.isoDate(System.currentTimeMillis())}.zip") }) { Icon(Icons.Filled.FileDownload, "Export selected") }
                IconButton({ confirmDelete = true }) { Icon(Icons.Filled.Delete, "Delete selected") }
            } else {
                Text(if (favoritesOnly) "Favorites" else "Recordings", Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium)
                Column {
                    IconButton({ showSort = true }) { Icon(Icons.Filled.Sort, "Sort") }
                    DropdownMenu(showSort, { showSort = false }) {
                        SortOrder.entries.forEach { s ->
                            DropdownMenuItem(
                                text = { Text(s.label + if (q.sort == s) "  ✓" else "") },
                                onClick = { q = q.copy(sort = s); showSort = false },
                            )
                        }
                    }
                }
            }
        }

        OutlinedTextField(
            q.text, { q = q.copy(text = it) },
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            placeholder = { Text("Search number, name, date (2026-11-16), notes, transcript") },
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            trailingIcon = { if (q.text.isNotEmpty()) IconButton({ q = q.copy(text = "") }) { Icon(Icons.Filled.Close, "Clear search") } },
            singleLine = true, shape = MaterialTheme.shapes.extraLarge,
        )

        if (!favoritesOnly) {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val folders = Folder.entries.filter { it != Folder.FAVORITES }
                items(folders) { f ->
                    FilterChip(q.folder == f, { if (f == Folder.PRIVATE) openPrivate() else q = q.copy(folder = f) }, { Text(f.label) })
                }
            }
        }
        LazyRow(Modifier.padding(top = 4.dp), contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(DirectionFilter.entries) { d -> FilterChip(q.direction == d, { q = q.copy(direction = d) }, { Text(d.label) }) }
            items(DurationFilter.entries.filter { it != DurationFilter.ANY }) { d ->
                FilterChip(q.duration == d, { q = q.copy(duration = if (q.duration == d) DurationFilter.ANY else d) }, { Text(d.label) })
            }
            items(tags) { t -> FilterChip(q.tag == t, { q = q.copy(tag = if (q.tag == t) null else t) }, { Text("#$t") }) }
        }

        if (items.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    if (all.isEmpty()) "No recordings yet." else "Nothing matches these filters.",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    if (favoritesOnly) "Tap the star on a recording to keep it here." else "Recorded calls will appear here.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(items, key = { it.id }) { r ->
                    RecordingRow(
                        r, r.id in selected,
                        onClick = { if (selecting) selected = if (r.id in selected) selected - r.id else selected + r.id else onOpen(r.id) },
                        onLongClick = { selected = if (r.id in selected) selected - r.id else selected + r.id },
                        onFavorite = { vm.toggleFavorite(r) },
                    )
                }
                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }

    if (confirmDelete) {
        val totalSize = selectedItems.sumOf { it.fileSize }
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${selectedItems.size} recording(s)?") },
            text = { Text("This permanently deletes the audio files (${Fmt.size(totalSize)}) and their notes. It cannot be undone. Export them first if you want a copy.") },
            confirmButton = {
                TextButton({
                    confirmDelete = false
                    vm.deleteConfirmed(selectedItems) { selected = emptySet() }
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton({ confirmDelete = false }) { Text("Cancel") } },
        )
    }
    if (pinPrompt) PinEntryDialog(
        title = "Unlock private recordings", vm = vm,
        onSuccess = { pinPrompt = false; vm.setPrivateUnlocked(true); q = q.copy(folder = Folder.PRIVATE) },
        onDismiss = { pinPrompt = false },
    )
}
