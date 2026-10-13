package de.ordnungshelfer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color as AndroidColor
import android.graphics.pdf.PdfRenderer
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.text.format.DateFormat
import android.text.format.Formatter
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Date

private val TEXT_EXT = setOf("txt", "md", "csv", "json", "xml", "log", "ini", "yml", "yaml", "html", "htm", "kt", "java", "py", "js", "css", "vcf", "ics", "gpx")

/**
 * Kompatibilität: öffnet die Dateiansicht für eine einzelne Datei.
 * Die eigentliche Ansicht ([FileViewer]) liegt über der ganzen App – so kennt Android die Navigationsleiste sicher.
 */
@Composable
fun FileDetailDialog(vm: MainViewModel, entry: DupEntry, onChanged: (DupEntry) -> Unit, onDismiss: () -> Unit) {
    LaunchedEffect(entry.node.uri) {
        vm.openViewer(listOf(entry), 0)
        onDismiss()
    }
}

/** Vollbild-Dateiansicht mit Wischen zur vorherigen/nächsten Datei, Zoom, Infos und Aktionen. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FileViewer(vm: MainViewModel, state: MainViewModel.ViewerState) {
    val ctx = LocalContext.current
    val items = remember(state) { mutableStateListOf<DupEntry>().apply { addAll(state.entries) } }
    val pager = rememberPagerState(initialPage = state.index.coerceIn(0, (items.size - 1).coerceAtLeast(0))) { items.size }
    var renaming by remember { mutableStateOf(false) }
    var editTags by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val close = { vm.closeViewer() }
    BackHandler(onBack = close)
    if (items.isEmpty()) {
        LaunchedEffect(Unit) { close() }
        return
    }
    val page = pager.currentPage.coerceIn(0, items.size - 1)
    val entry = items[page]
    val node = entry.node
    val replace: (DupEntry) -> Unit = { updated -> if (page < items.size) items[page] = updated }
    val chooseMoveTarget = rememberFolderChooser(vm, "Verschieben nach") { dest -> vm.moveFile(entry, dest, replace) }
    val chooseCopyTarget = rememberFolderChooser(vm, "Kopieren nach") { dest -> vm.copyFile(entry, dest) }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) {
            TopAppBar(
                windowInsets = WindowInsets(0),
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                title = {
                    Column {
                        Text(node.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                        if (items.size > 1) {
                            Text("${page + 1} von ${items.size}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                navigationIcon = { IconButton(onClick = close) { Icon(Icons.Filled.ArrowBack, "Zurück") } },
                actions = {
                    val fav = vm.isFavorite(node.uri)
                    IconButton(onClick = { vm.toggleFavorite(node.uri) }) {
                        Icon(
                            if (fav) Icons.Filled.Star else Icons.Filled.StarBorder,
                            if (fav) "Aus Favoriten entfernen" else "Zu Favoriten",
                            tint = if (fav) Color(0xFFF5B400) else LocalContentColor.current,
                        )
                    }
                    IconButton(onClick = { openFile(ctx, node) { vm.message = it } }) {
                        Icon(Icons.Filled.OpenInNew, "Mit App öffnen")
                    }
                },
            )

            // ---------- Vorschau: nach links/rechts wischen = vorherige/nächste Datei ----------
            HorizontalPager(
                state = pager,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                key = { items.getOrNull(it)?.node?.uri?.toString() ?: it.toString() },
                pageSpacing = 12.dp,
            ) { p ->
                val n = items.getOrNull(p)?.node ?: return@HorizontalPager
                Box(
                    Modifier.fillMaxSize().clip(MaterialTheme.shapes.large).background(MaterialTheme.colorScheme.surfaceContainerLow),
                    contentAlignment = Alignment.Center,
                ) {
                    Preview(n, onOpen = { openFile(ctx, n) { vm.message = it } })
                }
            }

            // ---------- Infos ----------
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                Text(
                    "${entry.path}\n${Formatter.formatShortFileSize(ctx, node.size)} · geändert ${formatDateTime(ctx, node.modified)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val tags = vm.tagsOf(node.uri)
                Spacer(Modifier.height(6.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    tags.forEach { t -> AssistChip(onClick = { editTags = true }, label = { Text("#$t") }) }
                    AssistChip(
                        onClick = { editTags = true },
                        label = { Text(if (tags.isEmpty()) "Tags hinzufügen" else "Bearbeiten") },
                        leadingIcon = { Icon(Icons.Filled.Sell, null, Modifier.size(16.dp)) },
                    )
                }
            }

            // ---------- Aktionen ----------
            Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 2.dp) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    ActionButton(Icons.Filled.DriveFileRenameOutline, "Umbenennen") { renaming = true }
                    ActionButton(Icons.Filled.ContentCopy, "Kopieren", chooseCopyTarget)
                    ActionButton(Icons.Filled.DriveFileMove, "Verschieben", chooseMoveTarget)
                    ActionButton(Icons.Filled.Share, "Teilen") { shareFiles(ctx, listOf(node.uri)) { vm.message = it } }
                    ActionButton(Icons.Filled.Delete, "Löschen") { confirmDelete = true }
                }
            }
        }
    }

    if (renaming) {
        val dot = node.name.lastIndexOf('.')
        var value by remember(node.uri) {
            // Nur den Namen ohne Endung vorauswählen
            mutableStateOf(TextFieldValue(node.name, TextRange(0, if (dot > 0) dot else node.name.length)))
        }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Umbenennen") },
            text = {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text("Neuer Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = value.text.isNotBlank() && value.text != node.name,
                    onClick = {
                        renaming = false
                        vm.renameFile(entry, value.text, replace)
                    },
                ) { Text("Umbenennen") }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Abbrechen") } },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("In den Papierkorb?") },
            text = { Text("„${node.name}“ kommt in den Papierkorb und kann dort ${Sorter.TRASH_DAYS} Tage lang wiederhergestellt werden.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    val removed = entry
                    vm.trashEntry(removed) { items.remove(removed) }
                }) {
                    Text("Löschen", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Abbrechen") } },
        )
    }

    if (editTags) {
        TagEditDialog(
            vm = vm,
            entry = entry,
            onOpen = { openFile(ctx, node) { vm.message = it } },
            onDismiss = { editTags = false },
        )
    }
}

/**
 * Zoom mit zwei Fingern oder Doppeltipp. Ist herangezoomt, verschiebt ein Finger den Ausschnitt
 * (statt zu blättern oder zu scrollen). Ohne Zoom verhält sich der Inhalt ganz normal.
 */
@Composable
fun ZoomBox(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    fun clamp(o: Offset, s: Float): Offset {
        val maxX = size.width * (s - 1f) / 2f
        val maxY = size.height * (s - 1f) / 2f
        return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
    }
    Box(
        modifier
            .clipToBounds()
            .onSizeChanged { size = it }
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = { p ->
                    if (scale > 1.01f) {
                        scale = 1f; offset = Offset.Zero
                    } else {
                        scale = 2.5f
                        val center = Offset(size.width / 2f, size.height / 2f)
                        offset = clamp((center - p) * (scale - 1f), scale)
                    }
                })
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    do {
                        val ev = awaitPointerEvent(PointerEventPass.Initial)
                        val fingers = ev.changes.count { it.pressed }
                        if (fingers >= 2 || scale > 1.01f) {
                            val newScale = (scale * ev.calculateZoom()).coerceIn(1f, 5f)
                            scale = newScale
                            offset = if (newScale <= 1.01f) Offset.Zero else clamp(offset + ev.calculatePan(), newScale)
                            ev.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (ev.changes.any { it.pressed })
                }
            }
            .graphicsLayer {
                scaleX = scale; scaleY = scale
                translationX = offset.x; translationY = offset.y
            },
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun ActionButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null)
            Spacer(Modifier.height(4.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
    }
}

// ======================= Vorschau =======================

@Composable
private fun Preview(node: Node, onOpen: () -> Unit) {
    val ext = extensionOf(node.name)
    when {
        Categories.of(node.name) == "Bilder" && ext != "svg" -> ImagePreview(node, onOpen)
        ext == "pdf" -> PdfPreview(node, onOpen)
        ext in TEXT_EXT -> TextPreview(node, onOpen)
        Categories.of(node.name) == "Videos" -> VideoPreview(node, onOpen)
        else -> NoPreview(node, onOpen)
    }
}

@Composable
private fun ImagePreview(node: Node, onOpen: () -> Unit) {
    val ctx = LocalContext.current
    val bitmap by produceState<Bitmap?>(null, node.uri) {
        value = withContext(Dispatchers.IO) { loadBitmap(ctx, node.uri, 2048) }
    }
    val bmp = bitmap
    if (bmp == null) {
        CircularProgressIndicator()
        return
    }
    ZoomBox(Modifier.fillMaxSize()) {
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = node.name,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** Lädt ein Bild verkleinert, damit auch große Fotos den Speicher nicht sprengen. */
private fun loadBitmap(ctx: Context, uri: Uri, maxSize: Int): Bitmap? = try {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (bounds.outWidth / sample > maxSize || bounds.outHeight / sample > maxSize) sample *= 2
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    val raw = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    raw?.let { rotateByExif(ctx, uri, it) }
} catch (e: Throwable) {
    null
}

/** Handyfotos sind oft nur per EXIF gedreht – hier richtig herum drehen. */
private fun rotateByExif(ctx: Context, uri: Uri, bmp: Bitmap): Bitmap {
    val degrees = try {
        ctx.contentResolver.openInputStream(uri)?.use {
            androidx.exifinterface.media.ExifInterface(it).rotationDegrees
        } ?: 0
    } catch (e: Exception) {
        0
    }
    if (degrees == 0) return bmp
    val m = android.graphics.Matrix().apply { postRotate(degrees.toFloat()) }
    return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
}

@Composable
private fun PdfPreview(node: Node, onOpen: () -> Unit) {
    val ctx = LocalContext.current
    // PdfRenderer darf nur eine Seite gleichzeitig öffnen
    val mutex = remember { Mutex() }
    val renderer by produceState<PdfRenderer?>(null, node.uri) {
        val r = withContext(Dispatchers.IO) {
            runCatching { ctx.contentResolver.openFileDescriptor(node.uri, "r")?.let { PdfRenderer(it) } }.getOrNull()
        }
        value = r
        awaitDispose { runCatching { r?.close() } }
    }
    val r = renderer
    if (r == null) {
        NoPreview(node, onOpen, "PDF wird geladen oder lässt sich nicht anzeigen.")
        return
    }
    ZoomBox(Modifier.fillMaxSize()) { BoxWithConstraints(Modifier.fillMaxSize()) {
        val widthPx = constraints.maxWidth.coerceAtMost(1600)
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(r.pageCount) { i ->
                val page by produceState<Bitmap?>(null, i) {
                    value = withContext(Dispatchers.IO) {
                        mutex.withLock {
                            runCatching {
                                r.openPage(i).use { p ->
                                    val h = (widthPx.toFloat() * p.height / p.width).toInt().coerceAtLeast(1)
                                    Bitmap.createBitmap(widthPx, h, Bitmap.Config.ARGB_8888).also { b ->
                                        b.eraseColor(AndroidColor.WHITE)
                                        p.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                    }
                                }
                            }.getOrNull()
                        }
                    }
                }
                val b = page
                if (b == null) {
                    Box(Modifier.fillMaxWidth().aspectRatio(0.707f).background(Color.White), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else {
                    Image(b.asImageBitmap(), "Seite ${i + 1}", Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth)
                }
                Text(
                    "Seite ${i + 1} von ${r.pageCount}",
                    Modifier.fillMaxWidth().padding(top = 2.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    }
}

@Composable
private fun TextPreview(node: Node, onOpen: () -> Unit) {
    val ctx = LocalContext.current
    val text by produceState<String?>(null, node.uri) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                ctx.contentResolver.openInputStream(node.uri)?.use { input ->
                    val bytes = ByteArray(200_000)
                    val n = input.read(bytes).coerceAtLeast(0)
                    String(bytes, 0, n, Charsets.UTF_8) + if (n == bytes.size) "\n\n… (gekürzt)" else ""
                }
            }.getOrNull()
        }
    }
    val t = text ?: run { CircularProgressIndicator(); return }
    ZoomBox(Modifier.fillMaxSize()) { SelectionContainer {
        Text(
            t,
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()).padding(12.dp),
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
        )
    }
    }
}

@Composable
private fun VideoPreview(node: Node, onOpen: () -> Unit) {
    val ctx = LocalContext.current
    val frame by produceState<Bitmap?>(null, node.uri) {
        value = withContext(Dispatchers.IO) {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(ctx, node.uri)
                r.getFrameAtTime(1_000_000)
            } catch (e: Exception) {
                null
            } finally {
                runCatching { r.release() }
            }
        }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        frame?.let { Image(it.asImageBitmap(), node.name, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
        FilledIconButton(onClick = onOpen, modifier = Modifier.size(72.dp)) {
            Icon(Icons.Filled.PlayArrow, "Abspielen", Modifier.size(40.dp))
        }
    }
}

@Composable
private fun NoPreview(node: Node, onOpen: () -> Unit, text: String = "Für diesen Dateityp gibt es keine Vorschau.") {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
        Icon(iconFor(node.name), null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(12.dp))
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        FilledTonalButton(onClick = onOpen) { Text("Mit App öffnen") }
    }
}

private fun formatDateTime(ctx: Context, millis: Long): String =
    if (millis <= 0) "unbekannt"
    else DateFormat.getDateFormat(ctx).format(Date(millis)) + " " + DateFormat.getTimeFormat(ctx).format(Date(millis))
