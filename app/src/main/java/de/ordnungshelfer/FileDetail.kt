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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Date

private val TEXT_EXT = setOf("txt", "md", "csv", "json", "xml", "log", "ini", "yml", "yaml", "html", "htm", "kt", "java", "py", "js", "css", "vcf", "ics", "gpx")

/** Vollbild-Ansicht einer Datei: Vorschau, Infos, Umbenennen, Kopieren, Verschieben, Tags. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FileDetailDialog(vm: MainViewModel, entry: DupEntry, onChanged: (DupEntry) -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val node = entry.node
    var renaming by remember { mutableStateOf(false) }
    var editTags by remember { mutableStateOf(false) }
    val chooseMoveTarget = rememberFolderChooser(vm, "Verschieben nach") { dest -> vm.moveFile(entry, dest, onChanged) }
    val chooseCopyTarget = rememberFolderChooser(vm, "Kopieren nach") { dest -> vm.copyFile(entry, dest) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                TopAppBar(
                    title = { Text(node.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = { IconButton(onClick = onDismiss) { Icon(Icons.Filled.ArrowBack, "Zurück") } },
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

                // ---------- Vorschau ----------
                Box(
                    Modifier.weight(1f).fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow),
                    contentAlignment = Alignment.Center,
                ) {
                    key(node.uri) { Preview(node, onOpen = { openFile(ctx, node) { vm.message = it } }) }
                }

                // ---------- Infos ----------
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text(node.name, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
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
                Row(
                    Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    ActionButton(Icons.Filled.DriveFileRenameOutline, "Umbenennen") { renaming = true }
                    ActionButton(Icons.Filled.ContentCopy, "Kopieren", chooseCopyTarget)
                    ActionButton(Icons.Filled.DriveFileMove, "Verschieben", chooseMoveTarget)
                    ActionButton(Icons.Filled.OpenInNew, "Öffnen") { openFile(ctx, node) { vm.message = it } }
                }
            }
        }
    }

    if (renaming) {
        val dot = node.name.lastIndexOf('.')
        var value by remember {
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
                        vm.renameFile(entry, value.text, onChanged)
                    },
                ) { Text("Umbenennen") }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Abbrechen") } },
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

@Composable
private fun ActionButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null)
            Spacer(Modifier.height(4.dp))
            Text(label, style = MaterialTheme.typography.labelMedium)
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
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    Image(
        bitmap = bmp.asImageBitmap(),
        contentDescription = node.name,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                // Mit zwei Fingern zoomen und verschieben
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 6f)
                    if (scale == 1f) { offsetX = 0f; offsetY = 0f } else { offsetX += pan.x; offsetY += pan.y }
                }
            }
            .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offsetX, translationY = offsetY),
    )
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
    BoxWithConstraints(Modifier.fillMaxSize()) {
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
    SelectionContainer {
        Text(
            t,
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()).padding(12.dp),
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
        )
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
