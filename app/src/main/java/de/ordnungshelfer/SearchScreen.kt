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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.items
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
    var menu by remember { mutableStateOf(false) }
    var selMenu by remember { mutableStateOf(false) }
    var newFolder by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<File?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    val grid = vm.gridView
    val searching = query.isNotBlank() || tagFilter.isNotEmpty()
    val roots = remember { Docs.storageRoots(ctx).map { it.second.absolutePath }.toSet() }
    val atRoot = current.absolutePath in roots
    val done: () -> Unit = { selection = emptySet(); refresh++ }
    val chooseMove = rememberFolderChooser(vm, "Verschieben nach") { dest -> vm.transferMany(selection, dest, copy = false, onDone = done) }
    val chooseCopy = rememberFolderChooser(vm, "Kopieren nach") { dest -> vm.transferMany(selection, dest, copy = true, onDone = done) }

    BackHandler(enabled = selection.isNotEmpty() || searching || !atRoot) {
        when {
            selection.isNotEmpty() -> selection = emptySet()
            searching -> { query = ""; tagFilter = emptySet() }
            else -> current.parentFile?.let { currentPath = it.absolutePath }
        }
    }

    // Inhalt des aktuellen Ordners
    val listing by produceState<List<ExEntry>?>(null, currentPath, refresh, sort, vm.fsVersion) {
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
    val contentMap = vm.content
    val index = vm.index
    val results = remember(index, query, tagFilter, tagsMap, contentMap, searching) {
        if (!searching || vm.indexRoot != currentPath) emptyList()
        else {
            val terms = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
            val wanted = tagFilter.map { it.lowercase() }
            index.orEmpty().filter { e ->
                val key = e.node.uri.toString()
                val fileTags = tagsMap[key].orEmpty().map { it.lowercase() }
                val text = contentMap[key]
                wanted.all { it in fileTags } &&
                    terms.all { t ->
                        e.node.name.lowercase().contains(t) || fileTags.any { it.contains(t) } ||
                            (text != null && text.contains(t, ignoreCase = true))
                    }
            }.sortedBy { it.node.name.lowercase() }
        }
    }

    val fileActions = rememberFileActions(vm)

    /** Dateiansicht öffnen – mit allen Dateien des Ordners zum Durchwischen. */
    fun openEntry(f: File) {
        val files = listing.orEmpty().filter { !it.isDir }
        val parentUri = Uri.fromFile(current)
        val path = Docs.prettyPath(parentUri)
        val entries = files.map { e ->
            val u = Uri.fromFile(e.file)
            val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(extensionOf(e.file.name)) ?: "application/octet-stream"
            DupEntry(Node(u, e.file.name, mime, e.size, e.modified), path, parentUri, parentUri)
        }
        val idx = files.indexOfFirst { it.file == f }.coerceAtLeast(0)
        vm.openViewer(entries, idx)
    }

    fun clickItem(f: File, isDir: Boolean) {
        val u = Uri.fromFile(f)
        when {
            selection.isNotEmpty() -> selection = selection.toggle(u)
            isDir -> currentPath = f.absolutePath
            else -> openEntry(f)
        }
    }

    Column(Modifier.fillMaxSize()) {
        // ---------- Suchfeld bzw. Auswahlleiste ----------
        if (selection.isEmpty()) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("In „${current.name.ifEmpty { "Speicher" }}“ suchen (Name, Tag, Text)") },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                trailingIcon = {
                    if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Close, "Leeren") }
                },
                singleLine = true,
                shape = MaterialTheme.shapes.extraLarge,
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unfocusedBorderColor = Color.Transparent,
                ),
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp),
            )
        } else {
            val selFiles = selection.filter { File(it.path!!).isFile }
            Row(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.secondaryContainer).padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { selection = emptySet() }) { Icon(Icons.Filled.Close, "Auswahl beenden") }
                Text("${selection.size} ausgewählt", Modifier.weight(1f), fontWeight = FontWeight.Medium)
                IconButton(onClick = { shareFiles(ctx, selFiles) { vm.message = it } }, enabled = selFiles.isNotEmpty()) {
                    Icon(Icons.Filled.Share, "Teilen")
                }
                IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, "Löschen") }
                IconButton(onClick = chooseMove) { Icon(Icons.Filled.DriveFileMove, "Verschieben") }
                Box {
                    IconButton(onClick = { selMenu = true }) { Icon(Icons.Filled.MoreVert, "Mehr") }
                    DropdownMenu(expanded = selMenu, onDismissRequest = { selMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("Alle auswählen") }, leadingIcon = { Icon(Icons.Filled.SelectAll, null) },
                            onClick = {
                                selMenu = false
                                selection = if (searching) results.map { it.node.uri }.toSet()
                                else listing.orEmpty().map { Uri.fromFile(it.file) }.toSet()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Kopieren nach …") }, leadingIcon = { Icon(Icons.Filled.ContentCopy, null) },
                            onClick = { selMenu = false; chooseCopy() },
                        )
                        DropdownMenuItem(
                            text = { Text("Umbenennen") }, leadingIcon = { Icon(Icons.Filled.DriveFileRenameOutline, null) },
                            enabled = selection.size == 1,
                            onClick = { selMenu = false; renameTarget = File(selection.first().path!!) },
                        )
                        DropdownMenuItem(
                            text = { Text("Tag vergeben") }, leadingIcon = { Icon(Icons.Filled.Sell, null) },
                            enabled = selFiles.isNotEmpty(),
                            onClick = { selMenu = false; bulkTag = true },
                        )
                    }
                }
            }
        }

        // ---------- Tag-Filter ----------
        val allTags = vm.allTags
        if (allTags.isNotEmpty() && selection.isEmpty()) {
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
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Menü") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("Neuer Ordner") }, leadingIcon = { Icon(Icons.Filled.CreateNewFolder, null) },
                        onClick = { menu = false; newFolder = true },
                    )
                    DropdownMenuItem(
                        text = { Text(if (grid) "Listenansicht" else "Rasteransicht") },
                        leadingIcon = { Icon(if (grid) Icons.Filled.ViewList else Icons.Filled.GridView, null) },
                        onClick = { menu = false; vm.toggleGridView() },
                    )
                    HorizontalDivider()
                    ExplorerSort.entries.forEach { o ->
                        DropdownMenuItem(
                            text = { Text("Sortieren nach ${o.label}") },
                            leadingIcon = { if (o == sort) Icon(Icons.Filled.Check, null) else Icon(Icons.Filled.Sort, null) },
                            onClick = { sort = o; menu = false },
                        )
                    }
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text(if (startDir.absolutePath == currentPath) "Ist Startordner" else "Als Startordner festlegen") },
                        leadingIcon = { Icon(Icons.Filled.PushPin, null) },
                        enabled = startDir.absolutePath != currentPath,
                        onClick = { vm.setSearchStart(Uri.fromFile(current).toString()); menu = false },
                    )
                    DropdownMenuItem(
                        text = { Text("Neu laden") },
                        leadingIcon = { Icon(Icons.Filled.Refresh, null) },
                        onClick = { refresh++; if (searching) vm.buildIndexFor(current); menu = false },
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
                            SwipeFileRow(
                                vm = vm,
                                actions = fileActions,
                                name = e.node.name,
                                subtitle = "${e.path} · ${Formatter.formatShortFileSize(ctx, e.node.size)}",
                                uri = e.node.uri,
                                selected = e.node.uri in selection,
                                swipeEnabled = selection.isEmpty(),
                                onClick = {
                                    if (selection.isNotEmpty()) selection = selection.toggle(e.node.uri)
                                    else vm.openViewer(results, results.indexOf(e))
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
                l.isEmpty() -> EmptyState(Icons.Filled.FolderOpen, "Ordner ist leer", "Über das Menü oben rechts legst du einen neuen Ordner an.")
                grid -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(104.dp),
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(8.dp),
                ) {
                    items(l, key = { it.file.absolutePath }) { e ->
                        val u = Uri.fromFile(e.file)
                        GridItem(
                            vm = vm,
                            entry = e,
                            selected = u in selection,
                            onClick = { clickItem(e.file, e.isDir) },
                            onLongClick = { selection = selection + u },
                        )
                    }
                }
                else -> LazyColumn(Modifier.weight(1f)) {
                    items(l, key = { it.file.absolutePath }) { e ->
                        val u = Uri.fromFile(e.file)
                        if (e.isDir) {
                            val sel = u in selection
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 8.dp, vertical = 2.dp)
                                    .clip(MaterialTheme.shapes.medium)
                                    .background(if (sel) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                                    .combinedClickable(onClick = { clickItem(e.file, true) }, onLongClick = { selection = selection + u })
                                    .padding(horizontal = 8.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (sel) Icon(Icons.Filled.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp))
                                else IconBadge(Icons.Filled.Folder, MaterialTheme.colorScheme.primary, size = 40.dp, iconSize = 22.dp)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(e.file.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                                    Text(
                                        if (e.childCount == 1) "1 Element" else "${e.childCount} Elemente",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                if (u.toString() in vm.favorites) Icon(Icons.Filled.Star, null, Modifier.size(18.dp), tint = Color(0xFFF5B400))
                                Icon(Icons.Filled.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        } else {
                            SwipeFileRow(
                                vm = vm,
                                actions = fileActions,
                                name = e.file.name,
                                subtitle = "${Formatter.formatShortFileSize(ctx, e.size)} · ${formatShortDate(ctx, e.modified)}",
                                uri = u,
                                selected = u in selection,
                                swipeEnabled = selection.isEmpty(),
                                onClick = { clickItem(e.file, false) },
                                onLongClick = { selection = selection + u },
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
        val files = selection.filter { File(it.path!!).isFile }
        TagInputDialog(
            title = "Tag für ${files.size} Dateien",
            suggestions = vm.allTags,
            onConfirm = { t ->
                vm.addTagTo(files, t)
                bulkTag = false
                selection = emptySet()
            },
            onDismiss = { bulkTag = false },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("In den Papierkorb?") },
            text = { Text("${selection.size} Elemente kommen in den Papierkorb und können dort ${Sorter.TRASH_DAYS} Tage lang wiederhergestellt werden.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; vm.trashUris(selection, done) }) {
                    Text("Löschen", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Abbrechen") } },
        )
    }

    if (newFolder) {
        NameDialog(title = "Neuer Ordner", initial = "", confirm = "Anlegen", onDismiss = { newFolder = false }) { name ->
            if (vm.createFolder(current, name)) { newFolder = false; refresh++ }
        }
    }

    renameTarget?.let { f ->
        NameDialog(title = "Umbenennen", initial = f.name, confirm = "Umbenennen", selectBase = !f.isDirectory, onDismiss = { renameTarget = null }) { name ->
            renameTarget = null
            vm.renamePath(Uri.fromFile(f), name, done)
        }
    }
}

/** Kachel in der Rasteransicht. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GridItem(vm: MainViewModel, entry: ExEntry, selected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    Column(
        Modifier
            .padding(4.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(88.dp), contentAlignment = Alignment.Center) {
            if (entry.isDir) Icon(Icons.Filled.Folder, null, Modifier.size(72.dp), tint = MaterialTheme.colorScheme.primary)
            else FileThumb(Uri.fromFile(entry.file), entry.file.name, 88.dp)
            if (selected) Icon(Icons.Filled.CheckCircle, null, Modifier.align(Alignment.TopEnd), tint = MaterialTheme.colorScheme.primary)
            else if (Uri.fromFile(entry.file).toString() in vm.favorites) {
                Icon(Icons.Filled.Star, null, Modifier.size(18.dp).align(Alignment.TopEnd), tint = Color(0xFFF5B400))
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            entry.file.name,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

/** Eingabedialog für Namen (Neuer Ordner, Umbenennen). */
@Composable
fun NameDialog(
    title: String,
    initial: String,
    confirm: String,
    selectBase: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val dot = initial.lastIndexOf('.')
    var value by remember {
        mutableStateOf(
            androidx.compose.ui.text.input.TextFieldValue(
                initial,
                androidx.compose.ui.text.TextRange(0, if (selectBase && dot > 0) dot else initial.length),
            )
        )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(value = value, onValueChange = { value = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        },
        confirmButton = { TextButton(onClick = { onConfirm(value.text) }, enabled = value.text.isNotBlank()) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
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
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selected) {
            Icon(Icons.Filled.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp))
        } else {
            FileThumb(uri, name, 40.dp)
        }
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
