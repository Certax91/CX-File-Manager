package de.ordnungshelfer

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.core.content.FileProvider
import java.io.File

/** Sortierung im Explorer */
enum class ExplorerSort(val label: String) { NAME("Name"), DATE("Datum"), SIZE("Größe") }

/** Eintrag im Explorer: Ordner oder Datei. */
private data class ExEntry(val file: File, val isDir: Boolean, val size: Long, val modified: Long, val childCount: Int)

/**
 * Suche als Ordner-Explorer: zeigt die Ordnerstruktur wie ein normaler Dateimanager.
 * Mit Suchbegriff oder Tag-Filter wird der aktuelle Ordner samt Unterordnern durchsucht – ohne Begrenzung.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SearchScreen(vm: MainViewModel, onGoToSettings: () -> Unit) {
    val ctx = LocalContext.current
    if (!vm.allFilesAccess) {
        EmptyState(
            Icons.Filled.Lock, "Zugriff auf alle Dateien nötig",
            "Damit die Suche alle Ordner des Handys zeigen kann, erlaube unter „Einstellung“ den Zugriff auf alle Dateien.",
        ) { FilledTonalButton(onClick = onGoToSettings) { Text("Zur Einstellung") } }
        return
    }

    val storageRoot = remember { Docs.storageRoots(ctx).first().second }
    val startDir = vm.searchFolders.firstOrNull()?.let { s -> Uri.parse(s).path?.let { File(it) } }
        ?.takeIf { it.isDirectory } ?: storageRoot
    var currentPath by rememberSaveable { mutableStateOf(startDir.absolutePath) }
    val current = File(currentPath)
    var query by rememberSaveable { mutableStateOf("") }
    var tagFilter by remember { mutableStateOf<Set<String>>(emptySet()) }
    var selection by remember { mutableStateOf<Set<Uri>>(emptySet()) }
    var editing by remember { mutableStateOf<DupEntry?>(null) }
    var bulkTag by remember { mutableStateOf(false) }
    var sort by rememberSaveable { mutableStateOf(ExplorerSort.NAME) }
    var sortMenu by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    val searching = query.isNotBlank() || tagFilter.isNotEmpty()
    val roots = remember { Docs.storageRoots(ctx).map { it.second.absolutePath }.toSet() }
    val atRoot = current.absolutePath in roots

    BackHandler(enabled = selection.isNotEmpty() || searching || !atRoot) {
        when {
            selection.isNotEmpty() -> selection = emptySet()
            searching -> { query = ""; tagFilter = emptySet() }
            else -> current.parentFile?.let { currentPath = it.absolutePath }
        }
    }

    // Inhalt des aktuellen Ordners
    val listing by produceState<List<ExEntry>?>(null, currentPath, refresh, sort) {
        value = withContext(Dispatchers.IO) {
            val items = (current.listFiles() ?: emptyArray())
                .filter { !it.name.startsWith(".") }
                .map { f ->
                    if (f.isDirectory) ExEntry(f, true, 0, f.lastModified(), f.list()?.count { !it.startsWith(".") } ?: 0)
                    else ExEntry(f, false, f.length(), f.lastModified(), 0)
                }
            val (dirs, files) = items.partition { it.isDir }
            val cmp: Comparator<ExEntry> = when (sort) {
                ExplorerSort.NAME -> compareBy { it.file.name.lowercase() }
                ExplorerSort.DATE -> compareByDescending { it.modified }
                ExplorerSort.SIZE -> compareByDescending<ExEntry> { it.size }.thenBy { it.file.name.lowercase() }
            }
            dirs.sortedBy { it.file.name.lowercase() } + files.sortedWith(cmp)
        }
    }

    // Suche im aktuellen Ordner inkl. Unterordnern
    LaunchedEffect(searching, currentPath) {
        if (searching && (vm.index == null || vm.indexRoot != currentPath)) vm.buildIndexFor(current)
    }
    val tagsMap = vm.tags
    val index = vm.index
    val results = remember(index, query, tagFilter, tagsMap, searching) {
        if (!searching || vm.indexRoot != currentPath) emptyList()
        else {
            val terms = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
            val wanted = tagFilter.map { it.lowercase() }
            index.orEmpty().filter { e ->
                val fileTags = tagsMap[e.node.uri.toString()].orEmpty().map { it.lowercase() }
                wanted.all { it in fileTags } &&
                    terms.all { t -> e.node.name.lowercase().contains(t) || fileTags.any { it.contains(t) } }
            }.sortedBy { it.node.name.lowercase() }
        }
    }

    fun openEntry(f: File) {
        val uri = Uri.fromFile(f)
        val node = Docs.nodeOf(ctx, uri) ?: return
        val parent = f.parentFile ?: f
        editing = DupEntry(node, Docs.prettyPath(Uri.fromFile(parent)), Uri.fromFile(parent), Uri.fromFile(parent))
    }

    Column(Modifier.fillMaxSize()) {
        // ---------- Suchfeld bzw. Auswahlleiste ----------
        if (selection.isEmpty()) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("In „${current.name.ifEmpty { "Speicher" }}“ suchen (Name oder Tag)") },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                trailingIcon = {
                    if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Close, "Leeren") }
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp),
            )
        } else {
            Row(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.secondaryContainer).padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { selection = emptySet() }) { Icon(Icons.Filled.Close, "Auswahl beenden") }
                Text("${selection.size} ausgewählt", Modifier.weight(1f), fontWeight = FontWeight.Medium)
                TextButton(onClick = {
                    selection = if (searching) results.map { it.node.uri }.toSet()
                    else listing.orEmpty().filter { !it.isDir }.map { Uri.fromFile(it.file) }.toSet()
                }) { Text("Alle") }
                Button(onClick = { bulkTag = true }) {
                    Icon(Icons.Filled.Sell, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Tag vergeben")
                }
            }
        }

        // ---------- Tag-Filter ----------
        val allTags = vm.allTags
        if (allTags.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                allTags.forEach { t ->
                    val on = t in tagFilter
                    FilterChip(selected = on, onClick = { tagFilter = if (on) tagFilter - t else tagFilter + t }, label = { Text("#$t") })
                }
            }
        }

        // ---------- Pfadleiste ----------
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { current.parentFile?.let { currentPath = it.absolutePath } }, enabled = !atRoot) {
                Icon(Icons.Filled.ArrowUpward, "Übergeordneter Ordner")
            }
            Row(
                Modifier.weight(1f).horizontalScroll(rememberScrollState(), reverseScrolling = true),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                breadcrumbs(current, roots).forEachIndexed { i, (label, f) ->
                    if (i > 0) Icon(Icons.Filled.ChevronRight, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { currentPath = f.absolutePath }, contentPadding = PaddingValues(horizontal = 6.dp)) {
                        Text(label, maxLines = 1, fontWeight = if (f == current) FontWeight.Bold else FontWeight.Normal)
                    }
                }
            }
            Box {
                IconButton(onClick = { sortMenu = true }) { Icon(Icons.Filled.Sort, "Sortieren") }
                DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                    ExplorerSort.entries.forEach { o ->
                        DropdownMenuItem(
                            text = { Text("Nach ${o.label}") },
                            leadingIcon = { if (o == sort) Icon(Icons.Filled.Check, null) },
                            onClick = { sort = o; sortMenu = false },
                        )
                    }
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text(if (startDir.absolutePath == currentPath) "Ist Startordner" else "Als Startordner festlegen") },
                        leadingIcon = { Icon(Icons.Filled.PushPin, null) },
                        enabled = startDir.absolutePath != currentPath,
                        onClick = { vm.setSearchStart(Uri.fromFile(current).toString()); sortMenu = false },
                    )
                    DropdownMenuItem(
                        text = { Text("Neu laden") },
                        leadingIcon = { Icon(Icons.Filled.Refresh, null) },
                        onClick = { refresh++; if (searching) vm.buildIndexFor(current); sortMenu = false },
                    )
                }
            }
        }
        HorizontalDivider()

        // ---------- Inhalt ----------
        if (searching) {
            when {
                vm.indexRoot != currentPath || index == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                results.isEmpty() -> EmptyState(Icons.Filled.SearchOff, "Nichts gefunden", "Gesucht wurde in „${current.name}“ und allen Unterordnern.")
                else -> {
                    Text(
                        "${results.size} Treffer in „${current.name.ifEmpty { "Speicher" }}“ und Unterordnern",
                        Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    LazyColumn(Modifier.weight(1f)) {
                        items(results, key = { it.node.uri.toString() }) { e ->
                            FileRow(
                                vm = vm,
                                name = e.node.name,
                                subtitle = "${e.path} · ${Formatter.formatShortFileSize(ctx, e.node.size)}",
                                uri = e.node.uri,
                                selected = e.node.uri in selection,
                                onClick = {
                                    if (selection.isNotEmpty()) selection = selection.toggle(e.node.uri) else editing = e
                                },
                                onLongClick = { selection = selection + e.node.uri },
                            )
                        }
                    }
                }
            }
        } else {
            val l = listing
            when {
                l == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                l.isEmpty() -> EmptyState(Icons.Filled.FolderOpen, "Ordner ist leer", "")
                else -> LazyColumn(Modifier.weight(1f)) {
                    items(l, key = { it.file.absolutePath }) { e ->
                        if (e.isDir) {
                            Row(
                                Modifier.fillMaxWidth().clickable { currentPath = e.file.absolutePath }.padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Filled.Folder, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(e.file.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                                    Text(
                                        if (e.childCount == 1) "1 Element" else "${e.childCount} Elemente",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Icon(Icons.Filled.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        } else {
                            val uri = Uri.fromFile(e.file)
                            FileRow(
                                vm = vm,
                                name = e.file.name,
                                subtitle = "${Formatter.formatShortFileSize(ctx, e.size)} · ${formatShortDate(ctx, e.modified)}",
                                uri = uri,
                                selected = uri in selection,
                                onClick = {
                                    if (selection.isNotEmpty()) selection = selection.toggle(uri) else openEntry(e.file)
                                },
                                onLongClick = { selection = selection + uri },
                            )
                        }
                    }
                }
            }
        }
    }

    editing?.let { e ->
        FileDetailDialog(
            vm = vm,
            entry = e,
            onChanged = { editing = it; refresh++ },
            onDismiss = { editing = null; refresh++ },
        )
    }

    if (bulkTag) {
        TagInputDialog(
            title = "Tag für ${selection.size} Dateien",
            suggestions = vm.allTags,
            onConfirm = { t ->
                vm.addTagTo(selection, t)
                bulkTag = false
                selection = emptySet()
            },
            onDismiss = { bulkTag = false },
        )
    }
}

private fun <T> Set<T>.toggle(x: T): Set<T> = if (x in this) this - x else this + x

/** Pfad als anklickbare Teile, beginnend beim Speicher (z. B. Interner Speicher › Download › PDFs). */
private fun breadcrumbs(dir: File, roots: Set<String>): List<Pair<String, File>> {
    val out = mutableListOf<Pair<String, File>>()
    var f: File? = dir
    while (f != null) {
        if (f.absolutePath in roots) {
            out += (if (f.absolutePath.startsWith("/storage/emulated")) "Interner Speicher" else "SD-Karte") to f
            break
        }
        out += f.name to f
        f = f.parentFile
    }
    return out.reversed()
}

/** Datei-Zeile (Explorer, Suchergebnisse, Home-Listen). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileRow(
    vm: MainViewModel,
    name: String,
    subtitle: String,
    uri: Uri,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val fileTags = vm.tags[uri.toString()].orEmpty()
    val fav = uri.toString() in vm.favorites
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (selected) Icons.Filled.CheckCircle else iconFor(name),
            null,
            tint = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.size(28.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (fileTags.isNotEmpty()) {
                Text(
                    fileTags.joinToString("  ") { "#$it" },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.tertiary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (fav) Icon(Icons.Filled.Star, "Favorit", Modifier.size(18.dp), tint = Color(0xFFF5B400))
    }
}

fun formatShortDate(ctx: Context, millis: Long): String =
    if (millis <= 0) "" else android.text.format.DateFormat.getDateFormat(ctx).format(java.util.Date(millis))

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TagEditDialog(vm: MainViewModel, entry: DupEntry, onOpen: () -> Unit, onDismiss: () -> Unit) {
    val current = vm.tagsOf(entry.node.uri)
    var input by remember { mutableStateOf("") }

    fun add(text: String) {
        val new = parseTags(text)
        if (new.isNotEmpty()) vm.setTags(entry.node.uri, current + new)
        input = ""
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(entry.node.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(entry.path, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (current.isEmpty()) {
                    Text("Noch keine Tags.", style = MaterialTheme.typography.bodyMedium)
                } else {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        current.forEach { t ->
                            InputChip(
                                selected = true,
                                onClick = { vm.setTags(entry.node.uri, current - t) },
                                label = { Text("#$t") },
                                trailingIcon = { Icon(Icons.Filled.Close, "Entfernen", Modifier.size(16.dp)) },
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text("Neuer Tag") },
                    singleLine = true,
                    trailingIcon = {
                        IconButton(onClick = { add(input) }, enabled = input.isNotBlank()) { Icon(Icons.Filled.Add, "Hinzufügen") }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                TagAutocomplete(typed = input, allTags = vm.allTags, exclude = current, onPick = { add(it) })
            }
        },
        confirmButton = {
            TextButton(onClick = { if (input.isNotBlank()) add(input); onDismiss() }) { Text("Fertig") }
        },
        dismissButton = {
            TextButton(onClick = onOpen) {
                Icon(Icons.Filled.OpenInNew, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Öffnen")
            }
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TagInputDialog(title: String, suggestions: List<String>, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var input by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text("Tag") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                TagAutocomplete(typed = input, allTags = suggestions, exclude = emptyList(), onPick = { onConfirm(it) })
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(input) }, enabled = input.isNotBlank()) { Text("Vergeben") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

internal fun iconFor(name: String) = when (Categories.of(name)) {
    "Bilder" -> Icons.Filled.Image
    "Videos" -> Icons.Filled.Movie
    "Audio" -> Icons.Filled.MusicNote
    "PDFs" -> Icons.Filled.PictureAsPdf
    "Tabellen" -> Icons.Filled.TableChart
    "Archive" -> Icons.Filled.FolderZip
    "Apps" -> Icons.Filled.Android
    else -> Icons.Filled.Description
}

/** Datei mit einer passenden App öffnen. */
fun openFile(ctx: Context, node: Node, onError: (String) -> Unit) {
    val uri = if (Docs.isFile(node.uri)) {
        FileProvider.getUriForFile(ctx, ctx.packageName + ".files", File(node.uri.path!!))
    } else {
        node.uri
    }
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, node.mime)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        ctx.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        onError("Keine App zum Öffnen von „${node.name}“ gefunden")
    } catch (e: Exception) {
        onError("Datei konnte nicht geöffnet werden")
    }
}
