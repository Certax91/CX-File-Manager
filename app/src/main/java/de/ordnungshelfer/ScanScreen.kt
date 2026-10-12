package de.ordnungshelfer

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Environment
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Ergebnis eines Scans: das fertige PDF und die erste Seite als Vorschau. */
private data class ScanResult(val pdf: Uri, val pages: Int, val firstPage: Uri?)

/** Dokumente mit der Kamera als PDF scannen, benennen und im Wunschordner ablegen. */
@Composable
fun ScanScreen(vm: MainViewModel) {
    val ctx = LocalContext.current
    val activity = ctx as Activity
    var result by remember { mutableStateOf<ScanResult?>(null) }
    var name by remember { mutableStateOf(TextFieldValue("")) }
    var tagText by remember { mutableStateOf("") }
    var lastSaved by remember { mutableStateOf<Uri?>(null) }
    var editing by remember { mutableStateOf<DupEntry?>(null) }
    val chooseFolder = rememberFolderChooser(vm, "Scan speichern in") { vm.chooseScanFolder(it) }

    val scanLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) {
            val scan = GmsDocumentScanningResult.fromActivityResultIntent(r.data)
            val pdf = scan?.pdf
            if (pdf != null) {
                result = ScanResult(pdf.uri, pdf.pageCount, scan.pages?.firstOrNull()?.imageUri)
                val def = "Scan_" + SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.GERMANY).format(Date())
                name = TextFieldValue(def, TextRange(0, def.length))
                tagText = ""
            }
        }
    }

    fun startScan() {
        val options = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_PDF, GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()
        GmsDocumentScanning.getClient(options).getStartScanIntent(activity)
            .addOnSuccessListener { sender -> scanLauncher.launch(IntentSenderRequest.Builder(sender).build()) }
            .addOnFailureListener { e -> vm.message = "Scanner nicht verfügbar: ${e.message}" }
    }

    val defaultFolder = remember {
        Uri.fromFile(File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "Scans")).toString()
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val r = result
        if (r == null) {
            item {
                Column(
                    Modifier.fillMaxWidth().padding(top = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(Icons.Filled.DocumentScanner, null, Modifier.size(96.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(16.dp))
                    Text("Dokument scannen", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(8.dp))
                    Hint("Fotografiere ein oder mehrere Seiten. Ränder werden automatisch erkannt und begradigt, " +
                        "daraus entsteht ein PDF, das du direkt benennst und ablegst.")
                    Spacer(Modifier.height(24.dp))
                    Button(onClick = { startScan() }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                        Icon(Icons.Filled.CameraAlt, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Scan starten")
                    }
                }
            }
            lastSaved?.let { saved ->
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Zuletzt gespeichert", style = MaterialTheme.typography.labelLarge)
                                Text(Docs.displayName(ctx, saved) ?: "", fontWeight = FontWeight.Medium)
                                Hint(Docs.prettyParent(saved))
                            }
                            TextButton(onClick = {
                                val node = Docs.nodeOf(ctx, saved)
                                val parent = Docs.parentOf(saved) ?: saved
                                if (node != null) editing = DupEntry(node, Docs.prettyParent(saved), parent, parent)
                            }) { Text("Ansehen") }
                        }
                    }
                }
            }
        } else {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Scan speichern", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Hint(if (r.pages == 1) "1 Seite" else "${r.pages} Seiten")
                }
            }
            item { ScanPreview(r.firstPage) }
            item {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Dateiname") },
                    suffix = { Text(".pdf") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                Text("Speicherort", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(2.dp))
                Hint("Ohne Auswahl landet der Scan in „Documents/Scans“.")
            }
            item {
                FolderSelector(
                    vm = vm,
                    selected = vm.scanFolder,
                    onSelect = { vm.chooseScanFolder(it) },
                    onClear = { vm.chooseScanFolder(null) },
                    onChoose = chooseFolder,
                )
            }
            item {
                OutlinedTextField(
                    value = tagText,
                    onValueChange = { tagText = it },
                    label = { Text("Tags (optional, mit Komma trennen)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                TagAutocomplete(
                    typed = currentTagToken(tagText),
                    allTags = vm.allTags,
                    exclude = parseTags(tagText.substringBeforeLast(',', "")),
                    onPick = { tagText = completeTagList(tagText, it) },
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            vm.saveScan(r.pdf, name.text, vm.scanFolder ?: defaultFolder, parseTags(tagText)) { saved ->
                                if (saved != null) {
                                    lastSaved = saved
                                    result = null
                                }
                            }
                        },
                        enabled = name.text.isNotBlank(),
                    ) {
                        Icon(Icons.Filled.Save, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Speichern")
                    }
                    OutlinedButton(onClick = { startScan() }) { Text("Neu scannen") }
                    TextButton(onClick = { result = null }) { Text("Verwerfen") }
                }
            }
        }
    }

    editing?.let { e ->
        FileDetailDialog(vm = vm, entry = e, onChanged = { editing = it }, onDismiss = { editing = null })
    }
}

@Composable
private fun ScanPreview(page: Uri?) {
    val ctx = LocalContext.current
    val bmp by produceState<Bitmap?>(null, page) {
        value = page?.let { p ->
            withContext(Dispatchers.IO) {
                runCatching {
                    val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
                    ctx.contentResolver.openInputStream(p)?.use { BitmapFactory.decodeStream(it, null, opts) }
                }.getOrNull()
            }
        }
    }
    Box(
        Modifier.fillMaxWidth().height(260.dp).background(MaterialTheme.colorScheme.surfaceContainerLow),
        contentAlignment = Alignment.Center,
    ) {
        val b = bmp
        if (b != null) Image(b.asImageBitmap(), "Erste Seite", Modifier.fillMaxSize().padding(8.dp), contentScale = ContentScale.Fit)
        else Icon(Icons.Filled.PictureAsPdf, null, Modifier.size(64.dp), tint = Color(0xFFD84315))
    }
}
