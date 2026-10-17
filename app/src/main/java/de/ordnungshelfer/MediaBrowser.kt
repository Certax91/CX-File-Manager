package de.ordnungshelfer

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private class BrowseEntry(val file: File, val isDir: Boolean, val count: Int)

/**
 * Ordneransicht ab „Interner Speicher“ zum gezielten Auswählen von Fotos und Videos.
 * Die Auswahl bleibt beim Wechseln der Ordner erhalten.
 */
@Composable
fun MediaBrowser(startPath: String?, preselected: Set<String>, onCancel: () -> Unit, onDone: (List<File>, String) -> Unit) {
    val ctx = LocalContext.current
    val roots = remember { Docs.storageRoots(ctx) }
    val rootPaths = remember { roots.map { it.second.absolutePath }.toSet() }
    var path by rememberSaveable {
        mutableStateOf(startPath?.takeIf { File(it).isDirectory } ?: roots.first().second.absolutePath)
    }
    val current = File(path)
    var selected by remember { mutableStateOf(preselected) }
    val atRoot = path in rootPaths

    BackHandler {
        if (atRoot) onCancel() else current.parentFile?.let { path = it.absolutePath }
    }

    val entries by produceState<List<BrowseEntry>?>(null, path) {
        value = null
        value = withContext(Dispatchers.IO) {
            val all = current.listFiles()?.filter { !it.name.startsWith(".") } ?: emptyList()
            val dirs = all.filter { it.isDirectory }.sortedBy { it.name.lowercase() }.map { d ->
                BrowseEntry(d, true, d.listFiles()?.count { f -> !f.name.startsWith(".") && (f.isDirectory || isMedia(f.name)) } ?: 0)
            }
            val media = all.filter { it.isFile && isMedia(it.name) }.sortedByDescending { it.lastModified() }.map { BrowseEntry(it, false, 0) }
            dirs + media
        }
    }
    val mediaHere = entries.orEmpty().filter { !it.isDir }

    Column(Modifier.fillMaxSize()) {
        // Kopfzeile
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { if (atRoot) onCancel() else current.parentFile?.let { path = it.absolutePath } }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück")
            }
            Column(Modifier.weight(1f)) {
                Text("Fotos auswählen", style = MaterialTheme.typography.titleLarge)
                Text(
                    Docs.prettyPath(Uri.fromFile(current)),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            if (mediaHere.isNotEmpty()) {
                val paths = mediaHere.map { it.file.absolutePath }
                val all = paths.all { it in selected }
                TextButton(onClick = { selected = if (all) selected - paths.toSet() else selected + paths }) {
                    Text(if (all) "Keine" else "Alle (${mediaHere.size})")
                }
            }
        }
        if (roots.size > 1) {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                roots.forEach { (label, dir) ->
                    FilterChip(
                        selected = path.startsWith(dir.absolutePath),
                        onClick = { path = dir.absolutePath },
                        label = { Text(label) },
                        leadingIcon = { Icon(if (label.startsWith("SD")) Icons.Filled.SdCard else Icons.Filled.PhoneAndroid, null, Modifier.size(18.dp)) },
                    )
                }
            }
        }

        val list = entries
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                list == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                list.isEmpty() -> Text(
                    "Keine Fotos oder Ordner hier",
                    Modifier.align(Alignment.Center), color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(104.dp),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(list, key = { it.file.absolutePath }, span = { if (it.isDir) GridItemSpan(maxLineSpan) else GridItemSpan(1) }) { e ->
                        if (e.isDir) {
                            val inside = selected.count { it.startsWith(e.file.absolutePath + "/") }
                            Surface(
                                onClick = { path = e.file.absolutePath },
                                shape = MaterialTheme.shapes.medium,
                                color = MaterialTheme.colorScheme.surfaceContainer,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Filled.Folder, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Spacer(Modifier.width(12.dp))
                                    Text(e.file.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    if (inside > 0) {
                                        Text("$inside ✓", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                        Spacer(Modifier.width(8.dp))
                                    }
                                    Text("${e.count}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Icon(Icons.Filled.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        } else {
                            val p = e.file.absolutePath
                            val on = p in selected
                            Box(
                                Modifier.aspectRatio(1f).clip(RoundedCornerShape(10.dp))
                                    .then(if (on) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp)) else Modifier)
                                    .clickable { selected = if (on) selected - p else selected + p },
                            ) {
                                FileThumb(Uri.fromFile(e.file), e.file.name, 104.dp)
                                if (Categories.of(e.file.name) == "Videos") {
                                    Icon(
                                        Icons.Filled.PlayCircle, null, Modifier.align(Alignment.Center).size(30.dp),
                                        tint = Color.White.copy(alpha = 0.9f),
                                    )
                                }
                                Box(
                                    Modifier.align(Alignment.TopEnd).padding(6.dp).size(22.dp).clip(CircleShape)
                                        .background(if (on) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.35f))
                                        .border(1.5.dp, Color.White, CircleShape),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (on) Icon(Icons.Filled.Check, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onPrimary)
                                }
                            }
                        }
                    }
                }
            }
        }

        Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (selected.isEmpty()) "Fotos antippen zum Auswählen" else "${selected.size} ausgewählt",
                    Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                )
                if (selected.isNotEmpty()) TextButton(onClick = { selected = emptySet() }) { Text("Leeren") }
                Button(onClick = { onDone(selected.map { File(it) }, path) }) { Text("Übernehmen") }
            }
        }
    }
}

private fun isMedia(name: String) = Categories.of(name).let { it == "Bilder" || it == "Videos" }
