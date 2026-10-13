package de.ordnungshelfer

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.IntentCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Wird über „Teilen“ bzw. „Öffnen mit“ aus Browser, E-Mail-App & Co. aufgerufen
 * und zeigt nur das Speichern-Fenster über der anderen App an.
 */
class ReceiveActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val i = intent
        var stream: Uri? = null
        var text: String? = null
        when (i?.action) {
            Intent.ACTION_SEND -> {
                stream = IntentCompat.getParcelableExtra(i, Intent.EXTRA_STREAM, Uri::class.java)
                text = i.getStringExtra(Intent.EXTRA_TEXT)
            }
            Intent.ACTION_VIEW -> {
                val d = i.data
                if (d?.scheme == "http" || d?.scheme == "https") text = d.toString() else stream = d
            }
        }
        val url = if (stream == null) Downloader.findUrl(text) else null
        // Aus der Benachrichtigung „Neu im Download“
        val watched = i?.getStringExtra(WatchService.EXTRA_PATH)?.let { java.io.File(it) }
        if (watched != null) {
            androidx.core.app.NotificationManagerCompat.from(this).cancel(WatchService.notificationId(watched.absolutePath))
        }

        setContent {
            AppTheme {
                val ctx = LocalContext.current
                LaunchedEffect(vm.message) {
                    vm.message?.let {
                        Toast.makeText(ctx, it, Toast.LENGTH_LONG).show()
                        vm.message = null
                    }
                }
                var saved by remember { mutableStateOf<Uri?>(null) }
                val sv = saved
                if (sv != null) {
                    // Nach dem Speichern: Banner mit „Öffnen“ und „Anzeigen“
                    SavedBanner(
                        uri = sv,
                        onOpen = {
                            Docs.nodeOf(this@ReceiveActivity, sv)?.let { n -> openFile(this@ReceiveActivity, n) { } }
                            finish()
                        },
                        onShow = {
                            startActivity(
                                Intent(this@ReceiveActivity, MainActivity::class.java)
                                    .putExtra(MainActivity.EXTRA_REVEAL, sv.path)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                            )
                            finish()
                        },
                        onDone = { finish() },
                    )
                } else if (watched != null) {
                    NewFileDialog(vm, watched, onClose = { finish() }, onSaved = { saved = it })
                } else if (stream == null && url == null) {
                    AlertDialog(
                        onDismissRequest = { finish() },
                        title = { Text("Nichts zum Speichern") },
                        text = { Text("Geteilt wurde weder eine Datei noch ein Link.") },
                        confirmButton = { TextButton(onClick = { finish() }) { Text("OK") } },
                    )
                } else {
                    DownloadDialog(vm, initialUrl = url, stream = stream, onClose = { finish() }, onSaved = { saved = it })
                }
            }
        }
    }
}

/**
 * Speichern-Fenster mit eigenem Dateinamen.
 *  - [stream]: geteilte Datei → Kopie unter neuem Namen (Ziel: Download oder beliebiger Ordner)
 *  - sonst Link → Download über den Android-Download-Manager nach Download/…
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DownloadDialog(vm: MainViewModel, initialUrl: String?, stream: Uri?, onClose: () -> Unit, onSaved: ((Uri) -> Unit)? = null) {
    val ctx = LocalContext.current
    val isFile = stream != null
    var url by remember { mutableStateOf(initialUrl.orEmpty()) }
    var original by remember { mutableStateOf(stream?.let { Docs.displayName(ctx, it) ?: it.lastPathSegment } ?: "") }
    var mime by remember { mutableStateOf<String?>(null) }
    var probing by remember { mutableStateOf(false) }
    var nameTouched by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf(selectBaseName(original)) }
    var subfolder by remember { mutableStateOf("") }
    var dest by remember { mutableStateOf<String?>(null) }
    var tagText by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    val chooseDest = rememberFolderChooser(vm, "Speichern in") { dest = it }

    // Bei Links: echten Dateinamen beim Server erfragen
    if (!isFile) {
        LaunchedEffect(url) {
            val u = Downloader.findUrl(url) ?: return@LaunchedEffect
            delay(400)
            probing = true
            val p = withContext(Dispatchers.IO) { Downloader.probe(u) }
            probing = false
            original = p.name
            mime = p.mime
            if (!nameTouched) name = selectBaseName(p.name)
        }
    }

    val validUrl = Downloader.findUrl(url)
    val canSave = !saving && name.text.isNotBlank() && (isFile || validUrl != null)

    AlertDialog(
        onDismissRequest = onClose,
        icon = { Icon(if (isFile) Icons.Filled.SaveAs else Icons.Filled.Download, null) },
        title = { Text(if (isFile) "Datei speichern" else "Herunterladen") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (!isFile) {
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        label = { Text("Link") },
                        singleLine = true,
                        isError = url.isNotBlank() && validUrl == null,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Text(
                        "Original: $original",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; nameTouched = true },
                    label = { Text("Dateiname") },
                    singleLine = true,
                    trailingIcon = { if (probing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) },
                    supportingText = {
                        val ext = extensionOf(original)
                        if (ext.isNotEmpty()) Text("Ohne Endung wird automatisch .$ext angehängt")
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (isFile) {
                    Text("Speichern in", style = MaterialTheme.typography.labelLarge)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Folder, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            dest?.let { vm.folderLabel(it) } ?: "Download",
                            Modifier.weight(1f),
                            maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = chooseDest) { Text("Anderer Ordner") }
                        if (dest != null) TextButton(onClick = { dest = null }) { Text("Download") }
                    }
                } else {
                    OutlinedTextField(
                        value = subfolder,
                        onValueChange = { subfolder = it },
                        label = { Text("Unterordner in Download (optional)") },
                        placeholder = { Text("z. B. Rechnungen") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                OutlinedTextField(
                    value = tagText,
                    onValueChange = { tagText = it },
                    label = { Text("Tags (optional, mit Komma trennen)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                TagAutocomplete(
                    typed = currentTagToken(tagText),
                    allTags = vm.allTags,
                    exclude = parseTags(tagText.substringBeforeLast(',', "")),
                    onPick = { tagText = completeTagList(tagText, it) },
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = canSave,
                onClick = {
                    if (isFile) {
                        saving = true
                        vm.saveIncoming(stream!!, name.text, original, dest, parseTags(tagText)) { saved ->
                            saving = false
                            if (saved != null) { if (onSaved != null) onSaved(saved) else onClose() }
                        }
                    } else {
                        val ok = vm.startDownload(validUrl!!, name.text, original, subfolder, mime, parseTags(tagText))
                        if (ok) onClose()
                    }
                },
            ) { Text(if (isFile) "Speichern" else "Herunterladen") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Abbrechen") } },
    )
}

/** Dateiname mit vorausgewähltem Namensteil (ohne Endung), damit man direkt lostippen kann. */
private fun selectBaseName(name: String): TextFieldValue {
    val dot = name.lastIndexOf('.')
    return TextFieldValue(name, TextRange(0, if (dot > 0) dot else name.length))
}

/** Neue Datei im Download-Ordner: Name ändern, optional verschieben und Tags vergeben. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NewFileDialog(vm: MainViewModel, file: java.io.File, onClose: () -> Unit, onSaved: ((Uri) -> Unit)? = null) {
    if (!file.exists()) {
        AlertDialog(
            onDismissRequest = onClose,
            title = { Text("Nicht mehr vorhanden") },
            text = { Text("„${file.name}“ wurde inzwischen verschoben oder gelöscht.") },
            confirmButton = { TextButton(onClick = onClose) { Text("OK") } },
        )
        return
    }
    var name by remember { mutableStateOf(if (file.isDirectory) TextFieldValue(file.name, TextRange(0, file.name.length)) else selectBaseName(file.name)) }
    var dest by remember { mutableStateOf<String?>(null) }
    var tagText by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    val chooseDest = rememberFolderChooser(vm, "Verschieben nach") { dest = it }

    AlertDialog(
        onDismissRequest = onClose,
        icon = { Icon(if (file.isDirectory) Icons.Filled.CreateNewFolder else Icons.Filled.DriveFileRenameOutline, null) },
        title = { Text(if (file.isDirectory) "Neuer Ordner im Download" else "Neu im Download") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Speicherort", style = MaterialTheme.typography.labelLarge)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Folder, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text(dest?.let { vm.folderLabel(it) } ?: "Download (bleibt hier)", Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = chooseDest) { Text("Verschieben nach …") }
                    if (dest != null) TextButton(onClick = { dest = null }) { Text("Hier lassen") }
                }
                if (!file.isDirectory) {
                    OutlinedTextField(
                        value = tagText,
                        onValueChange = { tagText = it },
                        label = { Text("Tags (optional, mit Komma trennen)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TagAutocomplete(
                        typed = currentTagToken(tagText),
                        allTags = vm.allTags,
                        exclude = parseTags(tagText.substringBeforeLast(',', "")),
                        onPick = { tagText = completeTagList(tagText, it) },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !saving && name.text.isNotBlank(),
                onClick = {
                    saving = true
                    vm.processNewFile(file, name.text, dest, parseTags(tagText)) { saved ->
                        saving = false
                        if (saved != null) { if (onSaved != null) onSaved(saved) else onClose() }
                    }
                },
            ) { Text("Speichern") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Abbrechen") } },
    )
}

/** Kleines Banner nach dem Speichern über „Teilen“: Öffnen oder im Ordner anzeigen. Schließt sich nach 8 s. */
@Composable
fun SavedBanner(uri: Uri, onOpen: () -> Unit, onShow: () -> Unit, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val name = remember(uri) { Docs.displayName(ctx, uri) ?: uri.lastPathSegment ?: "" }
    LaunchedEffect(uri) {
        delay(8000)
        onDone()
    }
    Box(
        Modifier.fillMaxSize().clickable(onClick = onDone).navigationBarsPadding().padding(16.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Surface(shape = MaterialTheme.shapes.large, tonalElevation = 6.dp, shadowElevation = 8.dp) {
            Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Text("Gespeichert: $name", Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                TextButton(onClick = onOpen) { Text("Öffnen") }
                if (Docs.isFile(uri)) TextButton(onClick = onShow) { Text("Anzeigen") }
            }
        }
    }
}
