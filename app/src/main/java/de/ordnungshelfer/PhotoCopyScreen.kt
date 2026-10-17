package de.ordnungshelfer

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Fotos kopieren, nach Muster benennen und taggen.
 * [initial]: bereits gewählte Dateien (z. B. aus dem Explorer). [onClose]: null = eigene Seite unter „Funktionen“.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PhotoCopyScreen(vm: MainViewModel, initial: List<Uri>, onClose: (() -> Unit)?) {
    val ctx = LocalContext.current
    if (onClose != null) BackHandler(onBack = onClose)

    var uris by remember { mutableStateOf(initial) }
    val sources by produceState<List<CopySource>?>(null, uris) {
        value = withContext(Dispatchers.IO) { uris.distinct().mapNotNull { PhotoCopy.read(ctx, it) } }
    }
    var name by remember { mutableStateOf("") }
    var pattern by remember { mutableStateOf(PhotoCopy.lastPattern(ctx)) }
    var tagList by remember { mutableStateOf<List<String>>(emptyList()) }
    var tagInput by remember { mutableStateOf("") }
    var base by remember { mutableStateOf(PhotoCopy.lastBase(ctx)) }
    var subfolder by remember { mutableStateOf(PhotoCopy.lastSubfolder(ctx)) }
    var showList by remember { mutableStateOf(true) }

    // Ohne Zahl nimmt Android die höchste erlaubte Anzahl (eine zu hohe Zahl lässt die App abstürzen)
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { picked ->
        if (picked.isNotEmpty()) uris = (uris + picked).distinct()
    }
    // Ausweichweg, falls die Fotoauswahl auf dem Gerät nicht startet: Android-Dateiauswahl
    val pickDocs = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { picked ->
        if (picked.isNotEmpty()) uris = (uris + picked).distinct()
    }
    val openPicker: () -> Unit = {
        try {
            pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
        } catch (e: Exception) {
            try {
                pickDocs.launch(arrayOf("image/*", "video/*"))
            } catch (e2: Exception) {
                vm.message = "Fotoauswahl konnte nicht geöffnet werden"
            }
        }
    }
    val chooseBase = rememberFolderChooser(vm, "Zielordner für die Kopien") { st -> Uri.parse(st).path?.let { base = it } }

    fun addTags(text: String) {
        val t = parseTags(text)
        if (t.isNotEmpty()) tagList = normalizeTags(tagList + t)
        tagInput = ""
    }

    val folderName = name.trim().replace(Regex("""[\\/:*?"<>|]"""), "_").trim('.', ' ')
    val dir: File? = base?.let { b -> if (subfolder && folderName.isNotEmpty()) File(b, folderName) else File(b) }
    val plans by produceState<List<CopyPlan>>(emptyList(), sources, name, pattern, dir) {
        val src = sources
        value = if (src.isNullOrEmpty() || dir == null) emptyList()
        else withContext(Dispatchers.IO) { PhotoCopy.plan(src, name.trim().ifEmpty { "Foto" }, pattern, dir) }
    }
    val ready = !sources.isNullOrEmpty() && dir != null && name.isNotBlank() && plans.isNotEmpty() && vm.busy == null

    Column(Modifier.fillMaxSize()) {
        if (onClose != null) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück") }
                Text("Kopieren & benennen", style = MaterialTheme.typography.titleLarge)
            }
        }
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                SectionCard("Fotos") {
                    val count = sources?.size
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconBadge(Icons.Filled.PhotoLibrary, MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                when {
                                    uris.isEmpty() -> "Noch keine Fotos gewählt"
                                    count == null -> "Lese Fotos …"
                                    else -> "$count ${if (count == 1) "Foto" else "Fotos"} ausgewählt"
                                },
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                "Die Originale bleiben unverändert.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (uris.isNotEmpty()) IconButton(onClick = { uris = emptyList() }) { Icon(Icons.Filled.Clear, "Auswahl leeren") }
                    }
                    FilledTonalButton(
                        onClick = openPicker,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Filled.AddPhotoAlternate, null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (uris.isEmpty()) "Fotos auswählen" else "Weitere Fotos hinzufügen")
                    }
                    Text(
                        "Tipp: Im Explorer (Suche) mehrere Dateien lange antippen und im Menü „Kopieren & benennen“ wählen.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item {
                SectionCard("Name") {
                    OutlinedTextField(
                        value = name, onValueChange = { name = it },
                        label = { Text("Bezeichnung, z. B. Geburtstag Oma") },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = pattern, onValueChange = { pattern = it },
                        label = { Text("Namensmuster") },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        PhotoSeries.PLACEHOLDERS.forEach { (p, _) -> SuggestionChip(onClick = { pattern += p }, label = { Text(p) }) }
                        if (pattern != PhotoSeries.DEFAULT_PATTERN) {
                            SuggestionChip(onClick = { pattern = PhotoSeries.DEFAULT_PATTERN }, label = { Text("Standard") })
                        }
                    }
                    Text(
                        PhotoSeries.PLACEHOLDERS.joinToString("\n") { (p, d) -> "$p  $d" } +
                            "\n{reisetag} zählt ab dem Tag des ältesten Fotos.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item {
                SectionCard("Tags") {
                    if (tagList.isNotEmpty()) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            tagList.forEach { t ->
                                InputChip(
                                    selected = false, onClick = { tagList = tagList - t }, label = { Text(t) },
                                    trailingIcon = { Icon(Icons.Filled.Close, "Entfernen", Modifier.size(16.dp)) },
                                )
                            }
                        }
                    }
                    OutlinedTextField(
                        value = tagInput,
                        onValueChange = { v -> if (v.endsWith(",")) addTags(v) else tagInput = v },
                        label = { Text("Tag hinzufügen") },
                        singleLine = true,
                        trailingIcon = { if (tagInput.isNotBlank()) IconButton(onClick = { addTags(tagInput) }) { Icon(Icons.Filled.Add, "Hinzufügen") } },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TagAutocomplete(
                        typed = tagInput,
                        allTags = (vm.allTags + listOfNotNull(name.trim().takeIf { it.isNotEmpty() })).distinct(),
                        exclude = tagList,
                        onPick = { addTags(it) },
                        max = 6,
                    )
                }
            }
            item {
                SectionCard("Zielordner") {
                    OutlinedCard(onClick = chooseBase, modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Folder, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(12.dp))
                            Text(
                                base?.let { Docs.prettyPath(Uri.fromFile(File(it))) } ?: "Ordner auswählen …",
                                Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge,
                                maxLines = 2, overflow = TextOverflow.Ellipsis,
                            )
                            Icon(Icons.Filled.FolderOpen, null, tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable { subfolder = !subfolder }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Unterordner mit der Bezeichnung", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                dir?.let { Docs.prettyPath(Uri.fromFile(it)).removePrefix("Interner Speicher/") } ?: "",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Switch(checked = subfolder, onCheckedChange = { subfolder = it })
                    }
                }
            }
            if (plans.isNotEmpty()) {
                item {
                    Row(Modifier.fillMaxWidth().clickable { showList = !showList }, verticalAlignment = Alignment.CenterVertically) {
                        Text("Vorschau der Namen", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                        Icon(if (showList) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null)
                    }
                }
                if (showList) {
                    items(plans, key = { it.src.uri.toString() }) { p ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(48.dp).clip(RoundedCornerShape(10.dp))) { FileThumb(p.src.uri, p.src.name, 48.dp) }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(p.newName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    p.src.name,
                                    style = MaterialTheme.typography.bodySmall.copy(textDecoration = TextDecoration.LineThrough),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                            }
                            IconButton(onClick = { uris = uris - p.src.uri }) { Icon(Icons.Filled.Close, "Entfernen", Modifier.size(20.dp)) }
                        }
                    }
                }
            }
        }
        Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                if (!ready && !sources.isNullOrEmpty()) {
                    Text(
                        when {
                            name.isBlank() -> "Bitte eine Bezeichnung eingeben"
                            dir == null -> "Bitte einen Zielordner wählen"
                            else -> ""
                        },
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                }
                Button(
                    enabled = ready,
                    onClick = {
                        val target = dir ?: return@Button
                        PhotoCopy.saveLast(ctx, base, pattern, subfolder)
                        vm.copyPhotos(plans, target, normalizeTags(tagList + parseTags(tagInput))) { ok ->
                            if (ok) {
                                if (onClose != null) onClose() else uris = emptyList()
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.ContentCopy, null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (plans.isEmpty()) "Kopieren" else "${plans.size} ${if (plans.size == 1) "Foto" else "Fotos"} kopieren")
                }
            }
        }
    }
}
