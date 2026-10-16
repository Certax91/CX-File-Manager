package de.ordnungshelfer

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Fotoserien (z. B. Urlaub): Zeitraum, Tags und Namensmuster festlegen.
 * Vorhandene Fotos werden über eine Vorschau bearbeitet, neue Fotos automatisch.
 */
@Composable
fun SeriesScreen(vm: MainViewModel, onGoToSettings: () -> Unit) {
    var editing by remember { mutableStateOf<PhotoSeries?>(null) }
    var previewId by remember { mutableStateOf<Long?>(null) }

    if (!vm.allFilesAccess) {
        ModernEmptyState(
            Icons.Filled.PhotoLibrary,
            "Zugriff auf alle Dateien nötig",
            "Damit Fotos umbenannt und getaggt werden können, braucht die App den Zugriff auf alle Dateien.",
        ) { Button(onClick = onGoToSettings) { Text("Zu den Einstellungen") } }
        return
    }

    editing?.let { s ->
        BackHandler { editing = null }
        SeriesEditor(
            vm = vm,
            initial = s,
            isNew = vm.series.none { it.id == s.id },
            onCancel = { editing = null },
            onSave = { saved, preview ->
                vm.saveSeries(saved)
                editing = null
                if (preview) previewId = saved.id
            },
        )
        return
    }

    previewId?.let { id ->
        BackHandler { previewId = null }
        SeriesPreview(vm, id, onClose = { previewId = null })
        return
    }

    var deleting by remember { mutableStateOf<PhotoSeries?>(null) }
    deleting?.let { s ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Fotoserie löschen?") },
            text = { Text("„${s.name}“ wird entfernt. Die Fotos behalten ihre Namen und Tags.") },
            confirmButton = { TextButton(onClick = { vm.deleteSeries(s.id); deleting = null }) { Text("Löschen") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Abbrechen") } },
        )
    }

    val newSeries = {
        val today = LocalDate.now().toEpochDay()
        editing = PhotoSeries(id = System.currentTimeMillis(), name = "", start = today, end = today + 6, tags = emptyList())
    }

    Box(Modifier.fillMaxSize()) {
        if (vm.series.isEmpty()) {
            ModernEmptyState(
                Icons.Filled.PhotoLibrary,
                "Noch keine Fotoserie",
                "Lege z. B. deinen Urlaub mit Zeitraum, Tags und Namensmuster an. Vorhandene Fotos aus dem Zeitraum " +
                    "bearbeitest du über eine Vorschau, neue Fotos bekommen Namen und Tags automatisch beim Fotografieren.",
            ) { Button(onClick = newSeries) { Icon(Icons.Filled.Add, null); Spacer(Modifier.width(8.dp)); Text("Neue Fotoserie") } }
        } else {
            val sorted = vm.series.sortedWith(compareBy<PhotoSeries> { it.isOver() }.thenByDescending { it.start })
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(sorted, key = { it.id }) { s ->
                    SeriesCard(
                        s = s,
                        onPreview = { previewId = s.id },
                        onEdit = { editing = s },
                        onToggleAuto = { vm.saveSeries(s.copy(autoNew = !s.autoNew)) },
                        onDelete = { deleting = s },
                    )
                }
            }
        }
        if (vm.series.isNotEmpty()) {
            ExtendedFloatingActionButton(
                onClick = newSeries,
                icon = { Icon(Icons.Filled.Add, null) },
                text = { Text("Neue Fotoserie") },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SeriesCard(
    s: PhotoSeries,
    onPreview: () -> Unit,
    onEdit: () -> Unit,
    onToggleAuto: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val (status, statusIcon) = when {
        s.isActive() -> "Läuft" to Icons.Filled.FiberManualRecord
        s.isOver() -> "Beendet" to Icons.Filled.CheckCircle
        else -> "Geplant" to Icons.Filled.Schedule
    }
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(Icons.Filled.PhotoLibrary, MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(s.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(s.rangeLabel, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Mehr") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Bearbeiten") }, leadingIcon = { Icon(Icons.Filled.Edit, null) }, onClick = { menu = false; onEdit() })
                        DropdownMenuItem(text = { Text("Löschen") }, leadingIcon = { Icon(Icons.Filled.Delete, null) }, onClick = { menu = false; onDelete() })
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        statusIcon, null, Modifier.size(14.dp),
                        tint = if (s.isActive()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(status, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("·", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    "${s.assigned.size} ${if (s.assigned.size == 1) "Foto" else "Fotos"} bearbeitet",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (s.tags.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    s.tags.forEach { t ->
                        AssistChip(onClick = {}, label = { Text(t) }, leadingIcon = { Icon(Icons.Filled.Sell, null, Modifier.size(16.dp)) })
                    }
                }
            }
            if (s.moveToFolder) {
                Text(
                    "Ordner: " + SeriesEngine.targetLabel(s),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            if (s.rename) {
                Text(
                    "Name: " + SeriesEngine.example(s),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            if (!s.isOver()) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable(onClick = onToggleAuto)) {
                    Text("Neue Fotos automatisch", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = s.autoNew, onCheckedChange = { onToggleAuto() })
                }
            }
            FilledTonalButton(onClick = onPreview, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.AutoFixHigh, null)
                Spacer(Modifier.width(8.dp))
                Text("Vorhandene Fotos bearbeiten")
            }
        }
    }
}

// ---------- Editor ----------

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun SeriesEditor(
    vm: MainViewModel,
    initial: PhotoSeries,
    isNew: Boolean,
    onCancel: () -> Unit,
    onSave: (PhotoSeries, Boolean) -> Unit,
) {
    var name by remember { mutableStateOf(initial.name) }
    var start by remember { mutableLongStateOf(initial.start) }
    var end by remember { mutableLongStateOf(initial.end) }
    var tagList by remember { mutableStateOf(initial.tags) }
    var tagInput by remember { mutableStateOf("") }
    var rename by remember { mutableStateOf(initial.rename) }
    var pattern by remember { mutableStateOf(initial.pattern) }
    var autoNew by remember { mutableStateOf(initial.autoNew) }
    var videos by remember { mutableStateOf(initial.videos) }
    var cameraOnly by remember { mutableStateOf(initial.cameraOnly) }
    var moveToFolder by remember { mutableStateOf(initial.moveToFolder) }
    var baseFolder by remember { mutableStateOf(initial.baseFolder) }
    var pickDates by remember { mutableStateOf(false) }
    val chooseBase = rememberFolderChooser(vm, "Hauptordner für die Fotoserie") { stored ->
        Uri.parse(stored).path?.let { baseFolder = it }
    }

    fun current() = initial.copy(
        name = name.trim(), start = start, end = end, tags = normalizeTags(tagList + parseTags(tagInput)),
        rename = rename, pattern = pattern.ifBlank { PhotoSeries.DEFAULT_PATTERN },
        autoNew = autoNew, videos = videos, cameraOnly = cameraOnly,
        moveToFolder = moveToFolder, baseFolder = baseFolder,
    )

    fun addTags(text: String) {
        val t = parseTags(text)
        if (t.isNotEmpty()) tagList = normalizeTags(tagList + t)
        tagInput = ""
    }

    if (pickDates) {
        val day = 86_400_000L
        val state = rememberDateRangePickerState(
            initialSelectedStartDateMillis = start * day,
            initialSelectedEndDateMillis = end * day,
        )
        DatePickerDialog(
            onDismissRequest = { pickDates = false },
            confirmButton = {
                TextButton(
                    enabled = state.selectedStartDateMillis != null,
                    onClick = {
                        val s = state.selectedStartDateMillis!! / day
                        start = s
                        end = (state.selectedEndDateMillis ?: state.selectedStartDateMillis!!) / day
                        pickDates = false
                    },
                ) { Text("Übernehmen") }
            },
            dismissButton = { TextButton(onClick = { pickDates = false }) { Text("Abbrechen") } },
        ) {
            DateRangePicker(
                state = state,
                title = { Text("Zeitraum der Serie", Modifier.padding(start = 24.dp, top = 16.dp)) },
                modifier = Modifier.weight(1f),
            )
        }
    }

    val valid = name.isNotBlank() && end >= start
    val today = LocalDate.now().toEpochDay()
    val hasPast = start <= today

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onCancel) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück") }
            Text(if (isNew) "Neue Fotoserie" else "Fotoserie bearbeiten", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SectionCard("Serie") {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("Name, z. B. Mallorca 2026") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedCard(onClick = { pickDates = true }, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.DateRange, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Zeitraum", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                initial.copy(start = start, end = end).rangeLabel + "  ·  ${end - start + 1} Tage",
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                        Icon(Icons.Filled.Edit, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            SectionCard("Tags") {
                if (tagList.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        tagList.forEach { t ->
                            InputChip(
                                selected = false,
                                onClick = { tagList = tagList - t },
                                label = { Text(t) },
                                trailingIcon = { Icon(Icons.Filled.Close, "Entfernen", Modifier.size(16.dp)) },
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = tagInput,
                    onValueChange = { v -> if (v.endsWith(",")) addTags(v) else tagInput = v },
                    label = { Text("Tag hinzufügen") },
                    placeholder = { Text("z. B. Urlaub, Spanien") },
                    singleLine = true,
                    trailingIcon = {
                        if (tagInput.isNotBlank()) IconButton(onClick = { addTags(tagInput) }) { Icon(Icons.Filled.Add, "Hinzufügen") }
                    },
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

            SectionCard("Dateinamen") {
                SwitchRow("Fotos umbenennen", "Sonst bekommen sie nur die Tags", rename) { rename = it }
                if (rename) {
                    OutlinedTextField(
                        value = pattern, onValueChange = { pattern = it },
                        label = { Text("Namensmuster") },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        PhotoSeries.PLACEHOLDERS.forEach { (p, _) ->
                            SuggestionChip(onClick = { pattern += p }, label = { Text(p) })
                        }
                    }
                    Text(
                        PhotoSeries.PLACEHOLDERS.joinToString("\n") { (p, d) -> "$p  $d" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text("Beispiel", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(SeriesEngine.example(current()), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }

            SectionCard("Ablage") {
                SwitchRow(
                    "In eigenen Ordner verschieben",
                    "Unterordner mit dem Namen der Serie",
                    moveToFolder,
                ) { moveToFolder = it }
                if (moveToFolder) {
                    Column(Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable { baseFolder = null }.padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = baseFolder == null, onClick = { baseFolder = null })
                            Column(Modifier.weight(1f)) {
                                Text("Im Kamera-Ordner (DCIM)", style = MaterialTheme.typography.bodyLarge)
                                Text("Erscheint in der Galerie als eigenes Album", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Row(
                            Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable { chooseBase() }.padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = baseFolder != null, onClick = { chooseBase() })
                            Column(Modifier.weight(1f)) {
                                Text("In einem anderen Hauptordner", style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    baseFolder?.let { Docs.prettyPath(Uri.fromFile(File(it))) } ?: "Ordner auswählen …",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (baseFolder != null) IconButton(onClick = chooseBase) { Icon(Icons.Filled.FolderOpen, "Ändern") }
                        }
                    }
                    Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Folder, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text("Zielordner", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(SeriesEngine.targetLabel(current()), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }

            SectionCard("Welche Fotos") {
                SwitchRow(
                    "Neue Fotos automatisch",
                    "Fotos, die im Zeitraum neu aufs Handy kommen, sofort benennen und taggen",
                    autoNew,
                ) { autoNew = it }
                SwitchRow("Videos einbeziehen", null, videos) { videos = it }
                SwitchRow(
                    "Nur Kamera-Fotos",
                    "Aus dem Kamera-Ordner (DCIM). Aus: auch Screenshots, WhatsApp-Bilder usw.",
                    cameraOnly,
                ) { cameraOnly = it }
            }
            Spacer(Modifier.height(4.dp))
        }
        Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
            Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                if (hasPast) {
                    OutlinedButton(enabled = valid, onClick = { onSave(current(), false) }) { Text("Nur speichern") }
                    Button(enabled = valid, onClick = { onSave(current(), true) }) { Text("Speichern & Fotos zeigen") }
                } else {
                    Button(enabled = valid, onClick = { onSave(current(), false) }) { Text("Speichern") }
                }
            }
        }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable { onChange(!checked) }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

// ---------- Vorschau ----------

@Composable
private fun SeriesPreview(vm: MainViewModel, id: Long, onClose: () -> Unit) {
    val s = vm.series.firstOrNull { it.id == id }
    var items by remember { mutableStateOf<List<SeriesItem>?>(null) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    LaunchedEffect(id) {
        vm.previewSeries(id) { list ->
            items = list
            selected = list.map { it.photo.path }.toSet()
        }
    }
    if (s == null) { onClose(); return }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück") }
            Column(Modifier.weight(1f)) {
                Text(s.name, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    s.rangeLabel + if (s.moveToFolder) "  →  " + SeriesEngine.targetLabel(s) else "",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            val list = items
            if (!list.isNullOrEmpty()) {
                val all = selected.size == list.size
                TextButton(onClick = { selected = if (all) emptySet() else list.map { it.photo.path }.toSet() }) {
                    Text(if (all) "Keine" else "Alle")
                }
            }
        }
        val list = items
        when {
            list == null -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            list.isEmpty() -> Box(Modifier.weight(1f)) {
                ModernEmptyState(
                    Icons.Filled.CheckCircle,
                    if (s.assigned.isEmpty()) "Keine Fotos im Zeitraum" else "Alles erledigt",
                    if (s.assigned.isEmpty()) "Im Zeitraum ${s.rangeLabel} wurden keine passenden Fotos gefunden" +
                        (if (s.cameraOnly) " (nur Kamera-Ordner)." else ".")
                    else "Alle ${s.assigned.size} Fotos der Serie sind bereits benannt und getaggt.",
                )
            }
            else -> {
                // Namen passend zur Auswahl neu nummerieren (abgewählte Fotos hinterlassen keine Lücken)
                val shown = remember(list, selected) {
                    val chosen = SeriesEngine.number(s, list.filter { it.photo.path in selected }.map { it.photo }).associateBy { it.photo.path }
                    list.map { chosen[it.photo.path] ?: it }
                }
                val dayFmt = DateTimeFormatter.ofPattern("EEEE, d. MMMM", Locale.GERMAN)
                val byDay = shown.groupBy { it.day }
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 12.dp)) {
                    byDay.forEach { (day, dayItems) ->
                        item(key = "h$day") {
                            val date = s.startDate.plusDays((day - 1).toLong())
                            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)) {
                                Text("Tag $day", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(8.dp))
                                Text(date.format(dayFmt), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.weight(1f))
                                Text("${dayItems.size}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        items(dayItems, key = { it.photo.path }) { it ->
                            val on = it.photo.path in selected
                            Row(
                                Modifier.fillMaxWidth()
                                    .clickable { selected = if (on) selected - it.photo.path else selected + it.photo.path }
                                    .padding(horizontal = 16.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(Modifier.size(52.dp).clip(RoundedCornerShape(10.dp))) {
                                    FileThumb(Uri.fromFile(File(it.photo.path)), it.photo.name, 52.dp)
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(if (on) it.newName else it.photo.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    if (on && it.newName != it.photo.name) {
                                        Text(
                                            it.photo.name,
                                            style = MaterialTheme.typography.bodySmall.copy(textDecoration = TextDecoration.LineThrough),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                                Checkbox(checked = on, onCheckedChange = { c -> selected = if (c) selected + it.photo.path else selected - it.photo.path })
                            }
                        }
                    }
                }
                Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (s.tags.isNotEmpty()) {
                            Text(
                                "Tags: " + s.tags.joinToString(", "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Button(
                            enabled = selected.isNotEmpty() && vm.busy == null,
                            onClick = {
                                // Nummern bleiben in Aufnahme-Reihenfolge, auch wenn Fotos abgewählt wurden
                                val chosen = shown.filter { it.photo.path in selected }
                                vm.applySeries(id, chosen) { onClose() }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Filled.AutoFixHigh, null)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "${selected.size} Fotos " + listOfNotNull(
                                    if (s.rename) "benennen" else null,
                                    "taggen",
                                    if (s.moveToFolder) "verschieben" else null,
                                ).let { w -> if (w.size == 1) w[0] else w.dropLast(1).joinToString(", ") + " & " + w.last() }
                            )
                        }
                    }
                }
            }
        }
    }
}
