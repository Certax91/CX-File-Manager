package de.ordnungshelfer

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.format.DateFormat
import android.text.format.Formatter
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import java.util.Date

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { AppTheme { App(vm) } }
    }

    override fun onResume() {
        super.onResume()
        // z. B. nach dem Erteilen von „Zugriff auf alle Dateien“ in den Einstellungen
        vm.refreshAccess()
    }
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 && dark -> dynamicDarkColorScheme(ctx)
        Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(ctx)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

enum class Tab(val label: String, val icon: ImageVector) {
    FOLDERS("Ordner", Icons.Filled.Folder),
    RULES("Regeln", Icons.Filled.Tune),
    SORT("Sortieren", Icons.Filled.CleaningServices),
    DUPES("Duplikate", Icons.Filled.ContentCopy),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(vm: MainViewModel = viewModel()) {
    var tab by rememberSaveable { mutableStateOf(Tab.FOLDERS) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(vm.message) {
        vm.message?.let {
            snackbar.showSnackbar(it)
            vm.message = null
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Ordnungshelfer") }) },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = { Icon(t.icon, contentDescription = null) },
                        label = { Text(t.label) },
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (tab) {
                Tab.FOLDERS -> FoldersScreen(vm)
                Tab.RULES -> RulesScreen(vm)
                Tab.SORT -> SortScreen(vm, onGoToFolders = { tab = Tab.FOLDERS })
                Tab.DUPES -> DuplicatesScreen(vm, onGoToFolders = { tab = Tab.FOLDERS })
            }
            vm.busy?.let { BusyOverlay(it) }
        }
    }
}

@Composable
fun BusyOverlay(text: String) {
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.4f))
            .clickable(enabled = true, onClick = {}),
        contentAlignment = Alignment.Center,
    ) {
        Card {
            Row(Modifier.padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(28.dp))
                Spacer(Modifier.width(16.dp))
                Text(text)
            }
        }
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
}

@Composable
fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
fun EmptyState(icon: ImageVector, title: String, text: String, action: (@Composable () -> Unit)? = null) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Hint(text)
        if (action != null) {
            Spacer(Modifier.height(16.dp))
            action()
        }
    }
}

// ======================= Ordner =======================

@Composable
fun FoldersScreen(vm: MainViewModel) {
    val ctx = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.addRoot(uri)
    }
    // Android 8–10: normale Speicher-Berechtigung
    val legacyPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        vm.refreshAccess()
    }
    val requestAllFiles = {
        if (Build.VERSION.SDK_INT >= 30) {
            val specific = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + ctx.packageName))
            try {
                ctx.startActivity(specific)
            } catch (e: Exception) {
                ctx.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            legacyPermission.launch(
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            )
        }
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SectionTitle("Ordner zum Aufräumen")
            Spacer(Modifier.height(4.dp))
            Hint("Die App sortiert nur in den Ordnern, die du hier hinzufügst. Sortiert wird jeweils innerhalb des Ordners in Unterordner.")
        }
        items(vm.roots, key = { it }) { r ->
            val noAccess = r.startsWith("file:") && !vm.allFilesAccess
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (noAccess) Icons.Filled.Warning else Icons.Filled.Folder, null,
                        tint = if (noAccess) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(vm.rootNames[r] ?: "Ordner", fontWeight = FontWeight.Medium)
                        Text(
                            if (noAccess) "Zugriff fehlt – wird übersprungen" else Docs.prettyPath(Uri.parse(r)),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (noAccess) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(onClick = { vm.removeRoot(r) }) {
                        Icon(Icons.Filled.Close, contentDescription = "Ordner entfernen")
                    }
                }
            }
        }
        if (vm.allFilesAccess) {
            val available = Docs.commonFolders().filter { (_, dir) -> Uri.fromFile(dir).toString() !in vm.roots }
            if (available.isNotEmpty()) {
                item {
                    Text("Schnell hinzufügen", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(4.dp))
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        available.forEach { (label, dir) ->
                            AssistChip(
                                onClick = { vm.addFileRoot(dir) },
                                label = { Text(label) },
                                leadingIcon = { Icon(Icons.Filled.Add, null, Modifier.size(18.dp)) },
                            )
                        }
                    }
                }
            }
        } else {
            item {
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Download-Ordner aufräumen", fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Android lässt den Ordner „Download“ über die normale Ordnerauswahl nicht zu. " +
                                "Mit „Zugriff auf alle Dateien“ kann die App ihn trotzdem aufräumen. " +
                                "Die App liest nur die Ordner, die du hier hinzufügst.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = requestAllFiles) { Text("Zugriff erlauben") }
                    }
                }
            }
        }
        item {
            FilledTonalButton(onClick = { picker.launch(null) }) {
                Icon(Icons.Filled.Add, null)
                Spacer(Modifier.width(8.dp))
                Text(if (vm.allFilesAccess) "Anderen Ordner wählen" else "Ordner hinzufügen")
            }
        }
        item {
            Spacer(Modifier.height(8.dp))
            SectionTitle("Sortieren nach")
        }
        item {
            SwitchRow("Dateityp", "Bilder, PDFs, Videos, Audio, Dokumente …", vm.settings.byType) {
                vm.updateSettings(vm.settings.copy(byType = it))
            }
        }
        item {
            SwitchRow("Datum", "Unterordner nach Jahr/Monat, z. B. Bilder/2026/10. Bei Fotos und Videos zählt das Aufnahmedatum.", vm.settings.byDate) {
                vm.updateSettings(vm.settings.copy(byDate = it))
            }
        }
        item {
            SwitchRow(
                "Unterordner einbeziehen",
                "Auch Dateien in vorhandenen Unterordnern sortieren. Bereits sortierte Ordner werden übersprungen.",
                vm.settings.includeSubfolders,
            ) { vm.updateSettings(vm.settings.copy(includeSubfolders = it)) }
        }
        item {
            Hint("Eigene Regeln (Reiter „Regeln“) haben immer Vorrang vor dem Dateityp.")
        }
    }
}

@Composable
fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title)
            Hint(subtitle)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

// ======================= Regeln =======================

@Composable
fun RulesScreen(vm: MainViewModel) {
    var field by rememberSaveable { mutableStateOf(RuleField.NAME_CONTAINS) }
    var pattern by rememberSaveable { mutableStateOf("") }
    var target by rememberSaveable { mutableStateOf("") }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SectionTitle("Neue Regel")
            Spacer(Modifier.height(4.dp))
            Hint("Beispiel: Name enthält „Rechnung“ → Ordner „Finanzen/Rechnungen“. Mit „/“ legst du Unterordner an.")
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RuleField.entries.forEach { f ->
                    FilterChip(selected = field == f, onClick = { field = f }, label = { Text(f.label) })
                }
            }
        }
        item {
            OutlinedTextField(
                value = pattern, onValueChange = { pattern = it },
                label = { Text(if (field == RuleField.EXTENSION) "Endung, z. B. pdf" else "Text im Dateinamen") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            OutlinedTextField(
                value = target, onValueChange = { target = it },
                label = { Text("Zielordner") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            Button(
                onClick = {
                    vm.addRule(field, pattern, target)
                    pattern = ""; target = ""
                },
                enabled = pattern.isNotBlank() && target.trim('/', ' ').isNotEmpty(),
            ) {
                Icon(Icons.Filled.Add, null)
                Spacer(Modifier.width(8.dp))
                Text("Regel hinzufügen")
            }
        }
        item {
            Spacer(Modifier.height(8.dp))
            SectionTitle("Deine Regeln")
            Spacer(Modifier.height(4.dp))
            Hint(if (vm.rules.isEmpty()) "Noch keine Regeln – sortiert wird dann nur nach Typ und/oder Datum." else "Die erste passende Regel gewinnt. Mit den Pfeilen änderst du die Reihenfolge.")
        }
        itemsIndexed(vm.rules, key = { _, r -> r.id }) { i, rule ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${rule.field.label} „${rule.pattern}“")
                        Text(
                            "→ ${rule.targetFolder}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    IconButton(onClick = { vm.moveRule(rule, up = true) }, enabled = i > 0) {
                        Icon(Icons.Filled.KeyboardArrowUp, "Nach oben")
                    }
                    IconButton(onClick = { vm.moveRule(rule, up = false) }, enabled = i < vm.rules.lastIndex) {
                        Icon(Icons.Filled.KeyboardArrowDown, "Nach unten")
                    }
                    IconButton(onClick = { vm.deleteRule(rule) }) {
                        Icon(Icons.Filled.Delete, "Regel löschen")
                    }
                }
            }
        }
    }
}

// ======================= Sortieren =======================

@Composable
fun SortScreen(vm: MainViewModel, onGoToFolders: () -> Unit) {
    var confirmMove by remember { mutableStateOf(false) }
    var confirmUndo by remember { mutableStateOf(false) }
    val plan = vm.plan

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(onClick = vm::buildPlan, enabled = vm.roots.isNotEmpty()) {
                Icon(Icons.Filled.Search, null)
                Spacer(Modifier.width(8.dp))
                Text(if (plan == null) "Vorschau erstellen" else "Neu prüfen")
            }
            if (vm.undoCount > 0) {
                OutlinedButton(onClick = { confirmUndo = true }) {
                    Icon(Icons.Filled.Undo, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Rückgängig")
                }
            }
        }

        when {
            vm.roots.isEmpty() -> EmptyState(
                Icons.Filled.FolderOpen, "Noch kein Ordner gewählt",
                "Wähle zuerst einen Ordner aus, den die App aufräumen darf.",
            ) { FilledTonalButton(onClick = onGoToFolders) { Text("Ordner wählen") } }

            plan == null -> EmptyState(
                Icons.Filled.Visibility, "Erst schauen, dann sortieren",
                "Die Vorschau zeigt dir, welche Datei wohin käme. Verschoben wird erst, wenn du bestätigst.",
            )

            plan.isEmpty() -> EmptyState(
                Icons.Filled.CheckCircle, "Alles ordentlich",
                "Nach deinen Einstellungen gibt es nichts zu verschieben.",
            )

            else -> {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("${vm.selected.size} von ${plan.size} ausgewählt", Modifier.weight(1f))
                    TextButton(onClick = { vm.selectAll(vm.selected.size != plan.size) }) {
                        Text(if (vm.selected.size == plan.size) "Keine" else "Alle")
                    }
                }
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 8.dp)) {
                    itemsIndexed(plan) { i, m -> PlanRow(m, i in vm.selected) { vm.toggle(i) } }
                }
                Button(
                    onClick = { confirmMove = true },
                    enabled = vm.selected.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                ) { Text("${vm.selected.size} Dateien jetzt verschieben") }
            }
        }
    }

    if (confirmMove) {
        AlertDialog(
            onDismissRequest = { confirmMove = false },
            title = { Text("Dateien verschieben?") },
            text = { Text("${vm.selected.size} Dateien werden in ihre Zielordner verschoben. Es wird nichts überschrieben, und du kannst den Vorgang danach rückgängig machen.") },
            confirmButton = { TextButton(onClick = { confirmMove = false; vm.executePlan() }) { Text("Verschieben") } },
            dismissButton = { TextButton(onClick = { confirmMove = false }) { Text("Abbrechen") } },
        )
    }
    if (confirmUndo) {
        AlertDialog(
            onDismissRequest = { confirmUndo = false },
            title = { Text("Rückgängig machen?") },
            text = { Text("Die ${vm.undoCount} zuletzt sortierten Dateien kommen an ihren alten Platz zurück.") },
            confirmButton = { TextButton(onClick = { confirmUndo = false; vm.undoLast() }) { Text("Rückgängig") } },
            dismissButton = { TextButton(onClick = { confirmUndo = false }) { Text("Abbrechen") } },
        )
    }
}

@Composable
fun PlanRow(m: PlannedMove, checked: Boolean, onToggle: () -> Unit) {
    val from = (listOf(m.rootName) + m.currentPath).joinToString("/")
    val to = (listOf(m.rootName) + m.target).joinToString("/")
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
        Column(Modifier.weight(1f)) {
            Text(m.file.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
            Text(
                "$from  →  $to",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            Text(m.reason, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}

// ======================= Duplikate =======================

@Composable
fun DuplicatesScreen(vm: MainViewModel, onGoToFolders: () -> Unit) {
    val ctx = LocalContext.current
    var confirm by remember { mutableStateOf(false) }
    var showTrash by rememberSaveable { mutableStateOf(false) }
    val groups = vm.dupGroups

    if (showTrash) {
        TrashScreen(vm, onBack = { showTrash = false })
        return
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(onClick = vm::findDuplicates, enabled = vm.roots.isNotEmpty()) {
                Icon(Icons.Filled.Search, null)
                Spacer(Modifier.width(8.dp))
                Text(if (groups == null) "Duplikate suchen" else "Neu suchen")
            }
            if (vm.trash.isNotEmpty()) {
                OutlinedButton(onClick = { showTrash = true }) {
                    Icon(Icons.Filled.Delete, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Papierkorb (${vm.trash.size})")
                }
            }
        }

        when {
            vm.roots.isEmpty() -> EmptyState(
                Icons.Filled.FolderOpen, "Noch kein Ordner gewählt",
                "Wähle zuerst einen Ordner aus, der durchsucht werden soll.",
            ) { FilledTonalButton(onClick = onGoToFolders) { Text("Ordner wählen") } }

            groups == null -> EmptyState(
                Icons.Filled.ContentCopy, "Doppelte Dateien finden",
                "Durchsucht deine Ordner inklusive aller Unterordner und vergleicht den Inhalt – nicht nur den Namen.",
            )

            groups.isEmpty() -> EmptyState(Icons.Filled.CheckCircle, "Keine Duplikate", "Jede Datei gibt es nur einmal.")

            else -> {
                val savable = groups.flatten().filter { it.node.uri in vm.dupSelected }.sumOf { it.node.size }
                Text(
                    "${groups.size} Gruppen gefunden. Vorausgewählt sind alle Kopien außer der jeweils ältesten Datei.",
                    Modifier.padding(horizontal = 16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LazyColumn(
                    Modifier.weight(1f),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(groups) { g ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(vertical = 8.dp)) {
                                Text(
                                    "${g.size}× · ${Formatter.formatShortFileSize(ctx, g.first().node.size)}",
                                    Modifier.padding(horizontal = 16.dp),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                g.forEach { e ->
                                    val checked = e.node.uri in vm.dupSelected
                                    Row(
                                        Modifier.fillMaxWidth().clickable { vm.toggleDup(e.node.uri) }.padding(horizontal = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Checkbox(checked = checked, onCheckedChange = { vm.toggleDup(e.node.uri) })
                                        Column(Modifier.weight(1f)) {
                                            Text(e.node.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text(
                                                "${e.path} · ${formatDate(ctx, e.node.modified)}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                        if (checked) Icon(Icons.Filled.Delete, null, tint = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        }
                    }
                }
                Button(
                    onClick = { confirm = true },
                    enabled = vm.dupSelected.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                ) {
                    Text("${vm.dupSelected.size} in den Papierkorb (${Formatter.formatShortFileSize(ctx, savable)})")
                }
            }
        }
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("In den Papierkorb?") },
            text = {
                Text(
                    "${vm.dupSelected.size} Dateien kommen in den Papierkorb der App. " +
                        "Dort kannst du sie ${Sorter.TRASH_DAYS} Tage lang wiederherstellen, danach werden sie endgültig gelöscht."
                )
            },
            confirmButton = {
                TextButton(onClick = { confirm = false; vm.trashSelectedDuplicates() }) { Text("Verschieben") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Abbrechen") } },
        )
    }
}

// ======================= Papierkorb =======================

@Composable
fun TrashScreen(vm: MainViewModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var confirmEmpty by remember { mutableStateOf(false) }
    val entries = vm.trash.sortedByDescending { it.deletedAt }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, "Zurück") }
            Column(Modifier.weight(1f)) {
                Text("Papierkorb", style = MaterialTheme.typography.titleMedium)
                Hint("${entries.size} Dateien · ${Formatter.formatShortFileSize(ctx, entries.sumOf { it.size })}")
            }
        }
        if (entries.isEmpty()) {
            EmptyState(Icons.Filled.CheckCircle, "Papierkorb ist leer", "Hier landen Duplikate, die du aussortierst.")
            return@Column
        }
        Text(
            "Dateien werden nach ${Sorter.TRASH_DAYS} Tagen automatisch endgültig gelöscht.",
            Modifier.padding(horizontal = 16.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(vertical = 8.dp)) {
            items(entries, key = { it.uri }) { t ->
                val days = Sorter.daysLeft(t)
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(t.originalName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${Formatter.formatShortFileSize(ctx, t.size)} · gelöscht am ${formatDate(ctx, t.deletedAt)} · " +
                                if (days == 1) "noch 1 Tag" else "noch $days Tage",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    TextButton(onClick = { vm.restoreFromTrash(t) }) {
                        Icon(Icons.Filled.RestoreFromTrash, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Zurück")
                    }
                }
            }
        }
        Button(
            onClick = { confirmEmpty = true },
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        ) { Text("Papierkorb jetzt leeren") }
    }

    if (confirmEmpty) {
        AlertDialog(
            onDismissRequest = { confirmEmpty = false },
            title = { Text("Endgültig löschen?") },
            text = { Text("${vm.trash.size} Dateien werden endgültig gelöscht. Das lässt sich nicht rückgängig machen.") },
            confirmButton = {
                TextButton(onClick = { confirmEmpty = false; vm.emptyTrash() }) {
                    Text("Löschen", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmEmpty = false }) { Text("Abbrechen") } },
        )
    }
}

private fun formatDate(ctx: android.content.Context, millis: Long): String =
    if (millis <= 0) "unbekannt" else DateFormat.getDateFormat(ctx).format(Date(millis))
