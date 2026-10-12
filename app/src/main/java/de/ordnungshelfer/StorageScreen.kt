package de.ordnungshelfer

import android.net.Uri
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private data class SizedItem(val file: File, val isDir: Boolean, val size: Long)

/** Speicheranalyse: Welche Ordner und Dateien belegen am meisten Platz? Mit Durchklicken und Aufräumen. */
@Composable
fun StorageScreen(vm: MainViewModel, onGoToSettings: () -> Unit) {
    val ctx = LocalContext.current
    if (!vm.allFilesAccess) {
        EmptyState(Icons.Filled.Lock, "Zugriff auf alle Dateien nötig", "Für die Speicheranalyse braucht die App diese Freigabe.") {
            FilledTonalButton(onClick = onGoToSettings) { Text("Zur Einstellung") }
        }
        return
    }
    val root = remember { Docs.storageRoots(ctx).first().second }
    var path by rememberSaveable { mutableStateOf(root.absolutePath) }
    var showLargest by rememberSaveable { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf<DupEntry?>(null) }
    val sizeCache = remember { mutableStateMapOf<String, Long>() }
    val dir = File(path)
    val atRoot = path == root.absolutePath

    BackHandler(enabled = showLargest || !atRoot) {
        if (showLargest) showLargest = false else dir.parentFile?.let { path = it.absolutePath }
    }

    val items by produceState<List<SizedItem>?>(null, path, refresh) {
        value = null
        value = withContext(Dispatchers.IO) {
            (dir.listFiles() ?: emptyArray()).filter { !it.name.startsWith(".") || it.name == Sorter.TRASH_DIR }.map { f ->
                if (f.isDirectory) {
                    val s = sizeCache[f.absolutePath] ?: f.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                        .also { sizeCache[f.absolutePath] = it }
                    SizedItem(f, true, s)
                } else SizedItem(f, false, f.length())
            }.sortedByDescending { it.size }
        }
    }

    fun open(f: File) {
        val u = Uri.fromFile(f)
        val node = Docs.nodeOf(ctx, u) ?: return
        val parent = Uri.fromFile(f.parentFile ?: f)
        editing = DupEntry(node, Docs.prettyParent(u), parent, parent)
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { if (showLargest) showLargest = false else dir.parentFile?.let { path = it.absolutePath } }, enabled = showLargest || !atRoot) {
                Icon(Icons.Filled.ArrowBack, "Zurück")
            }
            Column(Modifier.weight(1f)) {
                Text(if (showLargest) "Größte Dateien" else Docs.prettyPath(Uri.fromFile(dir)), fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val total = items?.sumOf { it.size }
                if (!showLargest && total != null) Hint("${Formatter.formatShortFileSize(ctx, total)} in diesem Ordner")
            }
            if (!showLargest) {
                FilledTonalButton(onClick = { showLargest = true; if (vm.library == null) vm.loadLibrary() }) { Text("Größte Dateien") }
            }
        }
        HorizontalDivider()

        if (showLargest) {
            val lib = vm.library
            if (lib == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else {
                val largest = remember(lib) { lib.sortedByDescending { it.node.size }.take(100) }
                LazyColumn(Modifier.fillMaxSize()) {
                    items(largest, key = { it.node.uri.toString() }) { e ->
                        FileRow(
                            vm = vm,
                            name = e.node.name,
                            subtitle = "${Formatter.formatShortFileSize(ctx, e.node.size)} · ${e.path}",
                            uri = e.node.uri,
                            selected = false,
                            onClick = { editing = e },
                            onLongClick = { editing = e },
                        )
                    }
                }
            }
        } else {
            val list = items
            if (list == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(8.dp))
                        Hint("Ordnergrößen werden berechnet …")
                    }
                }
            } else {
                val max = (list.maxOfOrNull { it.size } ?: 1L).coerceAtLeast(1L)
                LazyColumn(Modifier.fillMaxSize()) {
                    items(list, key = { it.file.absolutePath }) { it ->
                        Column(
                            Modifier.fillMaxWidth()
                                .clickable { if (it.isDir) path = it.file.absolutePath else open(it.file) }
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (it.isDir) Icon(Icons.Filled.Folder, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.primary)
                                else FileThumb(Uri.fromFile(it.file), it.file.name, 36.dp)
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    if (it.file.name == Sorter.TRASH_DIR) "Papierkorb" else it.file.name,
                                    Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                                Text(Formatter.formatShortFileSize(ctx, it.size), style = MaterialTheme.typography.labelLarge)
                            }
                            Spacer(Modifier.height(4.dp))
                            LinearProgressIndicator(
                                progress = { it.size.toFloat() / max },
                                modifier = Modifier.fillMaxWidth().padding(start = 48.dp).height(4.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    editing?.let { e ->
        FileDetailDialog(vm = vm, entry = e, onChanged = { editing = it }, onDismiss = {
            editing = null
            sizeCache.clear()
            refresh++
        })
    }
}
