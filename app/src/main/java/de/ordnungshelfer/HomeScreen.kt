package de.ordnungshelfer

import android.net.Uri
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Kategorien der Home-Seite mit Symbol und Farbe. */
private val CATEGORY_STYLE: List<Triple<String, ImageVector, Color>> = listOf(
    Triple("Bilder", Icons.Filled.Image, Color(0xFF4CAF50)),
    Triple("Videos", Icons.Filled.Movie, Color(0xFFE53935)),
    Triple("Audio", Icons.Filled.MusicNote, Color(0xFF8E24AA)),
    Triple("PDFs", Icons.Filled.PictureAsPdf, Color(0xFFD84315)),
    Triple("Dokumente", Icons.Filled.Description, Color(0xFF1E88E5)),
    Triple("Tabellen", Icons.Filled.TableChart, Color(0xFF2E7D32)),
    Triple("Präsentationen", Icons.Filled.Slideshow, Color(0xFFF57C00)),
    Triple("Archive", Icons.Filled.FolderZip, Color(0xFF6D4C41)),
    Triple("Apps", Icons.Filled.Android, Color(0xFF43A047)),
    Triple("E-Books", Icons.Filled.MenuBook, Color(0xFF00897B)),
    Triple("Kontakte & Termine", Icons.Filled.Event, Color(0xFF5E35B1)),
    Triple(Categories.OTHER, Icons.Filled.InsertDriveFile, Color(0xFF757575)),
)

/** Eine Liste, die von der Home-Seite aus geöffnet wird (Kategorie, Tag, Favoriten, Zuletzt). */
private data class HomeList(val title: String, val entries: List<DupEntry>)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(
    vm: MainViewModel,
    onGoToSettings: () -> Unit,
    onNavigate: (NavTab) -> Unit = {},
    onDownload: () -> Unit = {},
) {
    val ctx = LocalContext.current
    var open by remember { mutableStateOf<HomeList?>(null) }
    var editing by remember { mutableStateOf<DupEntry?>(null) }

    LaunchedEffect(vm.allFilesAccess) {
        if (vm.allFilesAccess && vm.library == null) vm.loadLibrary()
    }

    // Favoriten und getaggte Dateien auflösen (es können auch Dateien außerhalb der Bibliothek sein)
    val favEntries by produceState(emptyList<DupEntry>(), vm.favorites, vm.library) {
        value = withContext(Dispatchers.IO) { vm.favorites.mapNotNull { entryOf(ctx, it) }.sortedBy { it.node.name.lowercase() } }
    }

    val list = open
    if (list != null) {
        BackHandler { open = null }
        HomeListScreen(vm, list, onBack = { open = null }, onOpen = { editing = it })
    } else {
        val library = vm.library
        val counts = remember(library) { library.orEmpty().groupingBy { Categories.of(it.node.name) }.eachCount() }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // ---------- Speicher (Hero-Karte) ----------
            item {
                val (used, total) = remember(library) { Library.storage() }
                StorageHero(
                    used = used,
                    total = total,
                    files = library?.size,
                    loading = vm.libraryLoading,
                    onRefresh = { if (vm.allFilesAccess) vm.loadLibrary() },
                )
            }

            // ---------- Schnellzugriff ----------
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    QuickAction(Icons.Filled.DocumentScanner, "Scannen", Brand.Orange) { onNavigate(NavTab.SCAN) }
                    QuickAction(Icons.Filled.FolderOpen, "Dateien", Brand.Blue) { onNavigate(NavTab.SEARCH) }
                    QuickAction(Icons.Filled.Download, "Download", Brand.Green) { onDownload() }
                    QuickAction(Icons.Filled.AutoAwesome, "Aufräumen", Brand.Violet) { onNavigate(NavTab.TOOLS) }
                }
            }

            if (!vm.allFilesAccess) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                        Column(Modifier.padding(16.dp)) {
                            Text("Zugriff auf alle Dateien nötig", fontWeight = FontWeight.Medium)
                            Text("Für Kategorien und zuletzt geänderte Dateien braucht die App diese Freigabe.")
                            Spacer(Modifier.height(8.dp))
                            Button(onClick = onGoToSettings) { Text("Zur Einstellung") }
                        }
                    }
                }
            }

            // ---------- Favoriten ----------
            item {
                SectionHeader("Favoriten", if (favEntries.size > 4) "Alle (${favEntries.size})" else null) {
                    open = HomeList("Favoriten", favEntries)
                }
                if (favEntries.isEmpty()) {
                    Hint("Noch keine Favoriten. Öffne eine Datei und tippe oben auf den Stern.")
                } else {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        favEntries.take(12).forEach { e -> MiniFileCard(e) { vm.openViewer(favEntries, favEntries.indexOf(e)) } }
                    }
                }
            }

            // ---------- Kategorien ----------
            item {
                SectionHeader("Kategorien", null) {}
                if (library == null) {
                    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                        if (vm.libraryLoading) CircularProgressIndicator() else Hint("Noch nicht geladen.")
                    }
                } else {
                    val cats = CATEGORY_STYLE.filter { (counts[it.first] ?: 0) > 0 }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        cats.chunked(3).forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                row.forEach { (name, icon, color) ->
                                    CategoryTile(name, icon, color, counts[name] ?: 0, Modifier.weight(1f)) {
                                        open = HomeList(name, library.filter { Categories.of(it.node.name) == name })
                                    }
                                }
                                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                }
            }

            // ---------- Tags ----------
            item {
                SectionHeader("Tags", null) {}
                val tagCounts = vm.tags.values.flatten().groupingBy { it.lowercase() }.eachCount()
                if (vm.allTags.isEmpty()) {
                    Hint("Noch keine Tags vergeben.")
                } else {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        vm.allTags.forEach { t ->
                            AssistChip(
                                onClick = {
                                    val keys = vm.tags.filterValues { v -> v.any { it.equals(t, true) } }.keys
                                    open = HomeList("#$t", keys.mapNotNull { entryOf(ctx, it) })
                                },
                                label = { Text("#$t (${tagCounts[t.lowercase()] ?: 0})") },
                                leadingIcon = { Icon(Icons.Filled.Sell, null, Modifier.size(16.dp)) },
                            )
                        }
                    }
                }
            }

            // ---------- Zuletzt geändert ----------
            if (!library.isNullOrEmpty()) {
                item {
                    SectionHeader("Zuletzt geändert", "Mehr") {
                        open = HomeList("Zuletzt geändert", library.take(200))
                    }
                }
                items(library.take(8), key = { "recent_" + it.node.uri }) { e ->
                    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 1.dp) {
                        FileRow(
                            vm = vm,
                            name = e.node.name,
                            subtitle = "${e.path} · ${formatShortDate(ctx, e.node.modified)}",
                            uri = e.node.uri,
                            selected = false,
                            onClick = { vm.openViewer(library.take(8), library.take(8).indexOf(e)) },
                            onLongClick = { editing = e },
                        )
                    }
                }
            }
        }
    }

    editing?.let { e ->
        FileDetailDialog(vm = vm, entry = e, onChanged = { editing = it }, onDismiss = { editing = null })
    }
}

@Composable
private fun HomeListScreen(vm: MainViewModel, list: HomeList, onBack: () -> Unit, onOpen: (DupEntry) -> Unit) {
    val ctx = LocalContext.current
    val fileActions = rememberFileActions(vm)
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, "Zurück") }
            Column {
                Text(list.title, style = MaterialTheme.typography.titleMedium)
                Hint("${list.entries.size} Dateien")
            }
        }
        if (list.entries.isEmpty()) {
            EmptyState(Icons.Filled.FolderOpen, "Keine Dateien", "")
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(list.entries, key = { it.node.uri.toString() }) { e ->
                    SwipeFileRow(
                        vm = vm,
                        actions = fileActions,
                        name = e.node.name,
                        subtitle = "${e.path} · ${Formatter.formatShortFileSize(ctx, e.node.size)} · ${formatShortDate(ctx, e.node.modified)}",
                        uri = e.node.uri,
                        selected = false,
                        swipeEnabled = Docs.isFile(e.node.uri),
                        onClick = { vm.openViewer(list.entries, list.entries.indexOf(e)) },
                        onLongClick = { onOpen(e) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, action: String?, onAction: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        if (action != null) TextButton(onClick = onAction) { Text(action) }
    }
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun CategoryTile(name: String, icon: ImageVector, color: Color, count: Int, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 1.dp,
    ) {
        Column(Modifier.fillMaxWidth().padding(vertical = 16.dp, horizontal = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            IconBadge(icon, color, size = 48.dp, iconSize = 26.dp)
            Spacer(Modifier.height(8.dp))
            Text(name, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            Text("$count", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Runde Schnellzugriffs-Schaltfläche mit Beschriftung. */
@Composable
private fun QuickAction(icon: ImageVector, label: String, color: Color, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(76.dp)) {
        Surface(
            onClick = onClick,
            shape = MaterialTheme.shapes.large,
            color = color.copy(alpha = 0.14f),
            modifier = Modifier.size(60.dp),
        ) {
            Box(contentAlignment = Alignment.Center) { Icon(icon, label, tint = color, modifier = Modifier.size(28.dp)) }
        }
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}

/** Kopf der Home-Seite: Farbverlauf in Markenfarben mit Speicherring. */
@Composable
private fun StorageHero(used: Long, total: Long, files: Int?, loading: Boolean, onRefresh: () -> Unit) {
    val ctx = LocalContext.current
    val fraction = if (total > 0) used.toFloat() / total else 0f
    Box(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.extraLarge)
            .background(Brush.linearGradient(listOf(Brand.Navy, Brand.NavyLight)))
            .padding(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(104.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    val stroke = 12.dp.toPx()
                    val inset = stroke / 2
                    val arcSize = Size(size.width - stroke, size.height - stroke)
                    drawArc(Color.White.copy(alpha = 0.15f), 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
                    drawArc(
                        Brush.sweepGradient(listOf(Brand.Amber, Brand.Orange, Brand.Amber)),
                        -90f, 360f * fraction, false, Offset(inset, inset), arcSize,
                        style = Stroke(stroke, cap = StrokeCap.Round),
                    )
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${(fraction * 100).toInt()} %", color = Color.White, style = MaterialTheme.typography.titleLarge)
                    Text("belegt", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall)
                }
            }
            Spacer(Modifier.width(20.dp))
            Column(Modifier.weight(1f)) {
                Text("Interner Speicher", color = Color.White, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    "${Formatter.formatShortFileSize(ctx, total - used)} frei",
                    color = Brand.Amber, style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    "${Formatter.formatShortFileSize(ctx, used)} von ${Formatter.formatShortFileSize(ctx, total)}",
                    color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.bodySmall,
                )
                if (files != null) {
                    Text("$files Dateien", color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        IconButton(onClick = onRefresh, modifier = Modifier.align(Alignment.TopEnd).offset(x = 8.dp, y = (-8).dp)) {
            if (loading) CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
            else Icon(Icons.Filled.Refresh, "Aktualisieren", tint = Color.White.copy(alpha = 0.8f))
        }
    }
}

@Composable
private fun MiniFileCard(e: DupEntry, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = Modifier.width(120.dp), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 1.dp) {
        Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box {
                FileThumb(e.node.uri, e.node.name, 72.dp)
                Icon(Icons.Filled.Star, null, Modifier.size(18.dp).align(Alignment.TopEnd), tint = Color(0xFFF5B400))
            }
            Spacer(Modifier.height(6.dp))
            Text(e.node.name, style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        }
    }
}

/** Datei-Eintrag aus einer gespeicherten URI (für Favoriten und Tags). */
private fun entryOf(ctx: android.content.Context, key: String): DupEntry? {
    val uri = Uri.parse(key)
    val node = Docs.nodeOf(ctx, uri) ?: return null
    val parent = Docs.parentOf(uri) ?: uri
    return DupEntry(node, Docs.prettyParent(uri), parent, parent)
}
