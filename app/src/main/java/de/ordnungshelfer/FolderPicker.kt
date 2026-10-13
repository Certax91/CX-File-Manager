package de.ordnungshelfer

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.io.File

/**
 * Liefert eine Funktion, die einen Ordner auswählen lässt – überall auf dem Speicher.
 *  - mit „Zugriff auf alle Dateien“: der eigene Ordnerpicker (einheitliche Darstellung, auch Download)
 *  - ohne: die Android-Ordnerauswahl
 * Das Ergebnis ist ein gespeicherter Ordner-Wert (file://… oder content://…).
 */
@Composable
fun rememberFolderChooser(vm: MainViewModel, title: String, onChosen: (String) -> Unit): () -> Unit {
    val callback by rememberUpdatedState(onChosen)
    val own = rememberLauncherForActivityResult(PickFolder()) { folder -> if (folder != null) callback(folder) }
    val saf = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            vm.grantFolder(uri)
            callback(uri.toString())
        }
    }
    return { if (vm.allFilesAccess) own.launch(title) else saf.launch(null) }
}

/** Startet den Ordnerpicker; Eingabe = Titel, Ergebnis = gewählter Ordner als file://-Wert. */
class PickFolder : ActivityResultContract<String, String?>() {
    override fun createIntent(context: Context, input: String) =
        Intent(context, FolderPickerActivity::class.java).putExtra(EXTRA_TITLE, input)

    override fun parseResult(resultCode: Int, intent: Intent?): String? =
        if (resultCode == Activity.RESULT_OK) intent?.getStringExtra(EXTRA_FOLDER) else null

    companion object {
        const val EXTRA_TITLE = "titel"
        const val EXTRA_FOLDER = "ordner"
    }
}

/** Eigener Bildschirm für die Ordnerwahl – so sitzt der Bestätigen-Knopf immer über der Navigationsleiste. */
class FolderPickerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val title = intent.getStringExtra(PickFolder.EXTRA_TITLE) ?: "Ordner wählen"
        setContent {
            AppTheme {
                FolderPickerScreen(
                    title = title,
                    onPick = { dir ->
                        setResult(RESULT_OK, Intent().putExtra(PickFolder.EXTRA_FOLDER, Uri.fromFile(dir).toString()))
                        finish()
                    },
                    onCancel = { finish() },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderPickerScreen(title: String, onPick: (File) -> Unit, onCancel: () -> Unit) {
    val ctx = LocalContext.current
    val roots = remember { Docs.storageRoots(ctx) }
    val quick = remember { Docs.commonFolders() }
    // null = Auswahl des Speicherorts (nur wenn es mehrere gibt)
    var current by remember { mutableStateOf<File?>(if (roots.size == 1) roots.first().second else null) }
    var refresh by remember { mutableIntStateOf(0) }
    var newFolder by remember { mutableStateOf(false) }
    val rootDirs = roots.map { it.second.absolutePath }.toSet()
    val dir = current
    val atTop = dir == null || (roots.size == 1 && dir.absolutePath in rootDirs)

    val goUp: () -> Unit = {
        val c = current
        current = when {
            c == null -> { onCancel(); null }
            c.absolutePath in rootDirs -> if (roots.size == 1) { onCancel(); c } else null
            else -> c.parentFile
        }
    }
    BackHandler(onBack = goUp)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(title, style = MaterialTheme.typography.titleMedium)
                        Text(
                            dir?.let { Docs.prettyPath(Uri.fromFile(it)) } ?: "Speicherort wählen",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = if (atTop) onCancel else goUp) {
                        Icon(if (atTop) Icons.Filled.Close else Icons.Filled.ArrowBack, if (atTop) "Abbrechen" else "Zurück")
                    }
                },
                actions = {
                    if (dir != null) {
                        IconButton(onClick = { newFolder = true }) { Icon(Icons.Filled.CreateNewFolder, "Neuer Ordner") }
                    }
                },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Button(
                    onClick = { dir?.let(onPick) },
                    enabled = dir != null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .padding(16.dp),
                ) {
                    Icon(Icons.Filled.Check, null)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (dir == null) "Speicherort wählen" else "„${dir.name.ifEmpty { "Speicher" }}“ verwenden",
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
    ) { padding ->
        val folders = remember(dir, refresh) { dir?.let { Docs.subfolders(it) }.orEmpty() }
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Schnellwahl wie auf dem Seite „Einstellung“
            if (quick.isNotEmpty()) {
                item {
                    Text("Schnell wählen", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(4.dp))
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        quick.forEach { (label, f) ->
                            FilterChip(
                                selected = dir?.absolutePath == f.absolutePath,
                                onClick = { current = f },
                                label = { Text(label) },
                                leadingIcon = { Icon(Icons.Filled.Folder, null, Modifier.size(18.dp)) },
                            )
                        }
                    }
                }
            }
            if (dir == null) {
                items(roots) { (label, root) ->
                    FolderCard(label, Docs.prettyPath(Uri.fromFile(root)), Icons.Filled.PhoneAndroid) { current = root }
                }
            } else {
                if (!atTop) {
                    item { FolderCard("Übergeordneter Ordner", null, Icons.Filled.ArrowUpward, onClick = goUp) }
                }
                items(folders, key = { it.absolutePath }) { f ->
                    FolderCard(f.name, null, Icons.Filled.Folder) { current = f }
                }
                if (folders.isEmpty()) {
                    item {
                        Text(
                            "Keine Unterordner. Mit dem Ordner-Symbol oben legst du einen neuen an.",
                            Modifier.padding(vertical = 24.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    if (newFolder) {
        var name by remember { mutableStateOf("") }
        var error by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { newFolder = false },
            title = { Text("Neuer Ordner") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; error = null },
                    label = { Text("Name") },
                    singleLine = true,
                    isError = error != null,
                    supportingText = error?.let { { Text(it) } },
                )
            },
            confirmButton = {
                TextButton(
                    enabled = name.isNotBlank(),
                    onClick = {
                        val clean = name.trim()
                        val parent = current ?: return@TextButton
                        when {
                            clean.contains('/') || clean.startsWith(".") -> error = "Ungültiger Name"
                            File(parent, clean).exists() -> error = "Gibt es schon"
                            !File(parent, clean).mkdir() -> error = "Konnte nicht angelegt werden"
                            else -> {
                                newFolder = false
                                refresh++
                                current = File(parent, clean)
                            }
                        }
                    },
                ) { Text("Anlegen") }
            },
            dismissButton = { TextButton(onClick = { newFolder = false }) { Text("Abbrechen") } },
        )
    }
}

/** Ordner als Karte – gleiche Darstellung wie auf dem Seite „Einstellung“. */
@Composable
private fun FolderCard(
    label: String,
    subtitle: String?,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(label, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Icon(Icons.Filled.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
