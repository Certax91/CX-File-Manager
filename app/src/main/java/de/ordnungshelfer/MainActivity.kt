package de.ordnungshelfer

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.format.DateFormat
import android.text.format.Formatter
import androidx.fragment.app.FragmentActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
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
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import kotlinx.coroutines.launch

class MainActivity : FragmentActivity() {
    private val vm: MainViewModel by viewModels()

    companion object {
        /** Pfad einer Datei, die im Explorer angezeigt werden soll („Anzeigen“). */
        const val EXTRA_REVEAL = "anzeigen"
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_REVEAL)?.let { vm.reveal(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) intent?.getStringExtra(EXTRA_REVEAL)?.let { vm.reveal(it) }
        setContent {
            AppTheme(mode = vm.themeMode, dynamic = vm.dynamicColor) {
                if (vm.locked) LockScreen(onUnlock = ::authenticate) else App(vm)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // z. B. nach dem Erteilen von „Zugriff auf alle Dateien“ in den Einstellungen
        vm.refreshAccess()
    }

    override fun onStart() {
        super.onStart()
        vm.onForeground()
    }

    override fun onStop() {
        super.onStop()
        vm.onBackground()
    }

    /** Fingerabdruck, Gesicht oder Displaysperre (PIN/Muster) abfragen. */
    private fun authenticate() {
        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    vm.unlocked()
                }
            },
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("CX File Manager entsperren")
            .setSubtitle("Mit Fingerabdruck oder Displaysperre")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
            .build()
        prompt.authenticate(info)
    }
}

/** Sperrbildschirm der App. */
@Composable
fun LockScreen(onUnlock: () -> Unit) {
    LaunchedEffect(Unit) { onUnlock() }
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(Icons.Filled.Lock, null, Modifier.size(72.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(16.dp))
            Text("CX File Manager ist gesperrt", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(24.dp))
            Button(onClick = onUnlock) {
                Icon(Icons.Filled.Fingerprint, null)
                Spacer(Modifier.width(8.dp))
                Text("Entsperren")
            }
        }
    }
}

enum class NavTab(val label: String, val title: String, val icon: ImageVector, val outlined: ImageVector) {
    HOME("Home", "Übersicht", Icons.Filled.Home, Icons.Outlined.Home),
    SEARCH("Suche", "Dateien", Icons.Filled.Search, Icons.Outlined.Search),
    SCAN("Scan", "Dokument scannen", Icons.Filled.DocumentScanner, Icons.Outlined.DocumentScanner),
    TOOLS("Funktionen", "Funktionen", Icons.Filled.AutoAwesome, Icons.Outlined.AutoAwesome),
    SETTINGS("Einstellung", "Einstellungen", Icons.Filled.Settings, Icons.Outlined.Settings),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(vm: MainViewModel = viewModel()) {
    var tab by rememberSaveable { mutableStateOf(NavTab.HOME) }
    val snackbar = remember { SnackbarHostState() }
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    var showDownload by remember { mutableStateOf(false) }
    var downloadUrl by remember { mutableStateOf("") }
    if (showDownload) {
        DownloadDialog(vm, initialUrl = downloadUrl, stream = null, onClose = { showDownload = false })
    }

    // „Anzeigen“: zum Explorer wechseln, der Ordner und Datei öffnet
    LaunchedEffect(vm.revealPath) {
        if (vm.revealPath != null) {
            vm.closeViewer()
            tab = NavTab.SEARCH
        }
    }

    LaunchedEffect(vm.message) {
        vm.message?.let {
            snackbar.showSnackbar(it)
            vm.message = null
        }
    }

    val openDownload: () -> Unit = {
        // Link aus der Zwischenablage übernehmen, falls vorhanden
        downloadUrl = Downloader.findUrl(clipboard.getText()?.text).orEmpty()
        showDownload = true
    }

    Box(Modifier.fillMaxSize()) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(
                            painterResource(R.mipmap.ic_launcher_foreground), null,
                            Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)),
                            contentScale = ContentScale.Crop,
                        )
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(
                                "CX FILE MANAGER",
                                style = SmallCaps,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            AnimatedContent(targetState = tab, label = "titel") { t ->
                                Text(t.title, style = MaterialTheme.typography.titleLarge)
                            }
                        }
                    }
                },
                actions = {
                    FilledTonalIconButton(onClick = openDownload) { Icon(Icons.Filled.Download, "Herunterladen") }
                    Spacer(Modifier.width(8.dp))
                },
            )
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 0.dp) {
                NavTab.entries.forEach { t ->
                    val selected = tab == t
                    NavigationBarItem(
                        selected = selected,
                        onClick = { tab = t },
                        icon = { Icon(if (selected) t.icon else t.outlined, contentDescription = null) },
                        label = {
                            Text(
                                t.label, maxLines = 1, softWrap = false,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                        ),
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            Crossfade(targetState = tab, label = "seite") { t ->
                when (t) {
                    NavTab.HOME -> HomeScreen(
                        vm,
                        onGoToSettings = { tab = NavTab.SETTINGS },
                        onNavigate = { tab = it },
                        onDownload = openDownload,
                    )
                    NavTab.SEARCH -> SearchScreen(vm, onGoToSettings = { tab = NavTab.SETTINGS })
                    NavTab.SCAN -> ScanScreen(vm)
                    NavTab.TOOLS -> ToolsScreen(vm, onGoToSettings = { tab = NavTab.SETTINGS })
                    NavTab.SETTINGS -> SettingsScreen(vm)
                }
            }
        }
    }
    // Dateiansicht über der ganzen App (inkl. Navigationsleiste)
    vm.viewer?.let { FileViewer(vm, it) }
    NoticeBanner(vm, Modifier.align(Alignment.BottomCenter))
    SnackbarHost(
        snackbar,
        Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 88.dp),
    )
    vm.busy?.let { BusyOverlay(it) }
    }
}

/** „Einstellung“ mit den Reitern „Allgemein“ und „Regeln“. */
@Composable
fun SettingsScreen(vm: MainViewModel) {
    var sub by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = sub, containerColor = MaterialTheme.colorScheme.background) {
            Tab(selected = sub == 0, onClick = { sub = 0 }, text = { Text("Allgemein") }, icon = { Icon(Icons.Filled.Settings, null) })
            Tab(selected = sub == 1, onClick = { sub = 1 }, text = { Text("Regeln") }, icon = { Icon(Icons.Filled.Tune, null) })
            Tab(selected = sub == 2, onClick = { sub = 2 }, text = { Text("Daten") }, icon = { Icon(Icons.Filled.Shield, null) })
        }
        Box(Modifier.weight(1f)) {
            when (sub) {
                0 -> FoldersScreen(vm)
                1 -> RulesScreen(vm)
                else -> DataScreen(vm)
            }
        }
    }
}

/** „Funktionen“ mit den Seiten „Sortieren“ und „Duplikate“. */
@Composable
fun ToolsScreen(vm: MainViewModel, onGoToSettings: () -> Unit) {
    var sub by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        ScrollableTabRow(selectedTabIndex = sub, edgePadding = 8.dp, containerColor = MaterialTheme.colorScheme.background) {
            Tab(selected = sub == 0, onClick = { sub = 0 }, text = { Text("Sortieren") }, icon = { Icon(Icons.Filled.CleaningServices, null) })
            Tab(selected = sub == 1, onClick = { sub = 1 }, text = { Text("Fotoserien") }, icon = { Icon(Icons.Filled.PhotoLibrary, null) })
            Tab(selected = sub == 2, onClick = { sub = 2 }, text = { Text("Kopieren") }, icon = { Icon(Icons.Filled.AddPhotoAlternate, null) })
            Tab(selected = sub == 3, onClick = { sub = 3 }, text = { Text("Duplikate") }, icon = { Icon(Icons.Filled.ContentCopy, null) })
            Tab(selected = sub == 4, onClick = { sub = 4 }, text = { Text("Speicher") }, icon = { Icon(Icons.Filled.PieChart, null) })
            Tab(selected = sub == 5, onClick = { sub = 5 }, text = { Text("Papierkorb (${vm.trash.size})") }, icon = { Icon(Icons.Filled.Delete, null) })
        }
        Box(Modifier.weight(1f)) {
            when (sub) {
                0 -> SortScreen(vm, onGoToFolders = onGoToSettings)
                1 -> SeriesScreen(vm, onGoToSettings = onGoToSettings)
                2 -> PhotoCopyScreen(vm, initial = emptyList(), onClose = null)
                3 -> DuplicatesScreen(vm, onGoToFolders = onGoToSettings)
                4 -> StorageScreen(vm, onGoToSettings = onGoToSettings)
                else -> TrashScreen(vm, onBack = null)
            }
        }
    }
}

/** Hinweis-Banner mit Knöpfen (z. B. „Rückgängig“, „Öffnen“, „Anzeigen“), blendet sich nach 6 s aus. */
@Composable
fun NoticeBanner(vm: MainViewModel, modifier: Modifier = Modifier) {
    val n = vm.notice
    LaunchedEffect(n?.id) {
        if (n != null) {
            kotlinx.coroutines.delay(6000)
            if (vm.notice?.id == n.id) vm.notice = null
        }
    }
    androidx.compose.animation.AnimatedVisibility(
        visible = n != null,
        modifier = modifier.navigationBarsPadding().padding(start = 12.dp, end = 12.dp, bottom = 92.dp),
        enter = androidx.compose.animation.slideInVertically { it } + androidx.compose.animation.fadeIn(),
        exit = androidx.compose.animation.slideOutVertically { it } + androidx.compose.animation.fadeOut(),
    ) {
        val shown = n ?: return@AnimatedVisibility
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.inverseSurface,
            contentColor = MaterialTheme.colorScheme.inverseOnSurface,
            shadowElevation = 8.dp,
        ) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 6.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(shown.text, Modifier.weight(1f).padding(vertical = 10.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                shown.actions.forEach { (label, action) ->
                    TextButton(
                        onClick = { vm.notice = null; action() },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.inversePrimary),
                    ) { Text(label, fontWeight = FontWeight.Bold) }
                }
            }
        }
    }
}

@Composable
fun BusyOverlay(text: String) {
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.45f))
            .clickable(enabled = true, onClick = {}),
        contentAlignment = Alignment.Center,
    ) {
        Surface(shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp, shadowElevation = 12.dp) {
            Column(Modifier.padding(horizontal = 32.dp, vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(Modifier.size(44.dp), strokeWidth = 4.dp, strokeCap = androidx.compose.ui.graphics.StrokeCap.Round)
                Spacer(Modifier.height(16.dp))
                Text(text, style = MaterialTheme.typography.bodyLarge)
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
fun EmptyState(icon: ImageVector, title: String, text: String, action: (@Composable () -> Unit)? = null) =
    ModernEmptyState(icon, title, text, action)

// ======================= Ordner =======================

@Composable
fun FoldersScreen(vm: MainViewModel) {
    val ctx = LocalContext.current
    // Gleicher Ordnerpicker wie beim Ablageort (ohne „Zugriff auf alle Dateien“: Android-Ordnerauswahl)
    val pickRoot = rememberFolderChooser(vm, "Ordner zum Aufräumen") { vm.addRoot(Uri.parse(it)) }
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

    // Ab Android 13: Erlaubnis für Benachrichtigungen, bevor die Überwachung startet
    val notifyPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.setWatch(true) else vm.message = "Ohne Benachrichtigungen kann die App keine neuen Dateien melden"
    }
    val chooseTargetBase = rememberFolderChooser(vm, "Ablageort wählen") {
        vm.updateSettings(vm.settings.copy(targetBase = it))
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
            FilledTonalButton(onClick = pickRoot) {
                Icon(Icons.Filled.Add, null)
                Spacer(Modifier.width(8.dp))
                Text(if (vm.allFilesAccess) "Anderen Ordner wählen" else "Ordner hinzufügen")
            }
        }
        item {
            Spacer(Modifier.height(8.dp))
            SectionTitle("Download-Ordner überwachen")
            Spacer(Modifier.height(4.dp))
            SwitchRow(
                "Neue Dateien melden",
                if (vm.allFilesAccess) "Landet etwas Neues im Download-Ordner, kannst du es direkt aus der Benachrichtigung umbenennen. " +
                    "Solange die Überwachung läuft, steht eine kleine Benachrichtigung in der Leiste."
                else "Dafür braucht die App „Zugriff auf alle Dateien“ (siehe oben).",
                vm.watching,
            ) { on ->
                if (on && Build.VERSION.SDK_INT >= 33 && !WatchService.canNotify(ctx)) {
                    notifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    vm.setWatch(on)
                }
            }
            if (vm.watching) {
                SwitchRow(
                    "Automatisch nach Regeln einsortieren",
                    "Passt eine Regel zu einer neuen Datei, wird sie sofort verschoben. Sonst bietet die Benachrichtigung das passende Ziel als Knopf an.",
                    vm.autoSort,
                ) { vm.setAutoSortNew(it) }
            }
        }
        item {
            Spacer(Modifier.height(8.dp))
            SectionTitle("Ablageort")
            Spacer(Modifier.height(4.dp))
            Hint("Wohin sortierte Dateien kommen. Ohne Ablageort landen sie in Unterordnern des jeweils aufgeräumten Ordners.")
        }
        item {
            FolderSelector(
                vm = vm,
                selected = vm.settings.targetBase,
                onSelect = { vm.updateSettings(vm.settings.copy(targetBase = it)) },
                onClear = { vm.updateSettings(vm.settings.copy(targetBase = null)) },
                onChoose = chooseTargetBase,
            )
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

/**
 * Ordnerauswahl im gleichen Stil wie „Ordner zum Aufräumen“:
 * gewählter Ordner als Karte, Schnellwahl-Chips und „Ordner wählen“.
 */
@Composable
fun FolderSelector(
    vm: MainViewModel,
    selected: String?,
    onSelect: (String) -> Unit,
    onClear: () -> Unit,
    onChoose: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (selected != null) {
            val path = vm.folderLabel(selected)
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Folder, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(path.substringAfterLast('/'), fontWeight = FontWeight.Medium)
                        Text(
                            path,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(onClick = onClear) {
                        Icon(Icons.Filled.Close, contentDescription = "Ablageort entfernen")
                    }
                }
            }
        }
        if (vm.allFilesAccess) {
            val available = Docs.commonFolders().filter { (_, dir) -> Uri.fromFile(dir).toString() != selected }
            if (available.isNotEmpty()) {
                Column {
                    Text("Schnell wählen", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(4.dp))
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        available.forEach { (label, dir) ->
                            AssistChip(
                                onClick = { onSelect(Uri.fromFile(dir).toString()) },
                                label = { Text(label) },
                                leadingIcon = { Icon(Icons.Filled.Add, null, Modifier.size(18.dp)) },
                            )
                        }
                    }
                }
            }
        }
        FilledTonalButton(onClick = onChoose) {
            Icon(Icons.Filled.Add, null)
            Spacer(Modifier.width(8.dp))
            Text(if (selected == null) "Ordner wählen" else "Anderen Ordner wählen")
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RulesScreen(vm: MainViewModel) {
    var field by rememberSaveable { mutableStateOf(RuleField.NAME_CONTAINS) }
    var pattern by rememberSaveable { mutableStateOf("") }
    var target by rememberSaveable { mutableStateOf("") }
    var base by rememberSaveable { mutableStateOf<String?>(null) }
    var tagText by rememberSaveable { mutableStateOf("") }
    /** Regel, die gerade bearbeitet wird (null = neue Regel) */
    var editingId by rememberSaveable { mutableStateOf<Long?>(null) }
    val chooseBase = rememberFolderChooser(vm, "Ablageort für diese Regel") { base = it }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    fun resetForm() {
        field = RuleField.NAME_CONTAINS; pattern = ""; target = ""; base = null; tagText = ""; editingId = null
    }

    fun startEdit(rule: Rule) {
        editingId = rule.id
        field = rule.field
        pattern = rule.pattern
        target = rule.targetFolder
        base = rule.targetBase
        tagText = rule.tags.joinToString(", ")
        scope.launch { listState.animateScrollToItem(0) }
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SectionTitle(if (editingId == null) "Neue Regel" else "Regel bearbeiten")
            Spacer(Modifier.height(4.dp))
            if (editingId != null) Hint("Ändere die Felder und tippe auf „Änderungen speichern“.") else Hint("Beispiel: Name enthält „Rechnung“ → Ordner „Finanzen/Rechnungen“. Mit „/“ legst du Unterordner an. Über „Ablageort“ kann das Ziel auch ein ganz anderer Ordner sein.")
        }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RuleField.entries.forEach { f ->
                    FilterChip(selected = field == f, onClick = { field = f }, label = { Text(f.label) })
                }
            }
        }
        item {
            OutlinedTextField(
                value = pattern, onValueChange = { pattern = it },
                label = { Text(field.hint) },
                keyboardOptions = if (field.numeric) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            Text("Ablageort", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(2.dp))
            Hint("Optional. Ohne eigenen Ablageort gilt der allgemeine Ablageort bzw. der aufgeräumte Ordner.")
        }
        item {
            FolderSelector(
                vm = vm,
                selected = base,
                onSelect = { base = it },
                onClear = { base = null },
                onChoose = chooseBase,
            )
        }
        item {
            OutlinedTextField(
                value = target, onValueChange = { target = it },
                label = { Text(if (base == null) "Zielordner" else "Unterordner (optional)") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            OutlinedTextField(
                value = tagText, onValueChange = { tagText = it },
                label = { Text("Tags (optional, mit Komma trennen)") },
                placeholder = { Text("z. B. Finanzen, Steuer") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
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
            val valid = pattern.isNotBlank() && (base != null || target.trim('/', ' ').isNotEmpty())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = {
                        val id = editingId
                        if (id == null) vm.addRule(field, pattern, target, base, parseTags(tagText))
                        else vm.updateRule(id, field, pattern, target, base, parseTags(tagText))
                        resetForm()
                    },
                    enabled = valid,
                ) {
                    Icon(if (editingId == null) Icons.Filled.Add else Icons.Filled.Check, null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (editingId == null) "Regel hinzufügen" else "Änderungen speichern")
                }
                if (editingId != null) {
                    OutlinedButton(onClick = { resetForm() }) { Text("Abbrechen") }
                }
            }
        }
        item {
            Spacer(Modifier.height(8.dp))
            SectionTitle("Deine Regeln")
            Spacer(Modifier.height(4.dp))
            Hint(if (vm.rules.isEmpty()) "Noch keine Regeln – sortiert wird dann nur nach Typ und/oder Datum." else "Antippen zum Bearbeiten. Die erste passende Regel gewinnt, mit den Pfeilen änderst du die Reihenfolge.")
        }
        itemsIndexed(vm.rules, key = { _, r -> r.id }) { i, rule ->
            val isEditing = rule.id == editingId
            Card(
                Modifier.fillMaxWidth().clickable { startEdit(rule) },
                colors = if (isEditing) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                else CardDefaults.cardColors(),
            ) {
                Row(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(rule.description)
                        val dest = listOfNotNull(rule.targetBase?.let { vm.folderLabel(it) }, rule.targetFolder.ifEmpty { null })
                        Text(
                            "→ ${dest.joinToString("/")}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        if (rule.tags.isNotEmpty()) {
                            Text(
                                rule.tags.joinToString("  ") { "#$it" },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.tertiary,
                            )
                        }
                    }
                    IconButton(onClick = { vm.moveRule(rule, up = true) }, enabled = i > 0) {
                        Icon(Icons.Filled.KeyboardArrowUp, "Nach oben")
                    }
                    IconButton(onClick = { vm.moveRule(rule, up = false) }, enabled = i < vm.rules.lastIndex) {
                        Icon(Icons.Filled.KeyboardArrowDown, "Nach unten")
                    }
                    IconButton(onClick = {
                        if (isEditing) resetForm()
                        vm.deleteRule(rule)
                    }) {
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
    val to = (listOf(m.baseName) + m.target).joinToString("/")
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
            Text(
                m.reason + if (m.tags.isEmpty()) "" else "  ·  " + m.tags.joinToString(" ") { "#$it" },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
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
fun TrashScreen(vm: MainViewModel, onBack: (() -> Unit)?) {
    val ctx = LocalContext.current
    var confirmEmpty by remember { mutableStateOf(false) }
    val entries = vm.trash.sortedByDescending { it.deletedAt }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, "Zurück") }
            Column(Modifier.weight(1f).padding(start = if (onBack == null) 8.dp else 0.dp)) {
                Text("Papierkorb", style = MaterialTheme.typography.titleMedium)
                Hint("${entries.size} Dateien · ${Formatter.formatShortFileSize(ctx, entries.sumOf { it.size })}")
            }
        }
        if (entries.isEmpty()) {
            EmptyState(Icons.Filled.CheckCircle, "Papierkorb ist leer", "Hier landen gelöschte Dateien und Ordner.")
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
