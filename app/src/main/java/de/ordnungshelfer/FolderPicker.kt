package de.ordnungshelfer

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.io.File

/**
 * Liefert eine Funktion, die einen Ordner auswählen lässt – überall auf dem Speicher.
 *  - mit „Zugriff auf alle Dateien“: eigener Ordnerpicker (auch Download & Hauptverzeichnis)
 *  - ohne: die Android-Ordnerauswahl
 * Das Ergebnis ist ein gespeicherter Ordner-Wert (file://… oder content://…).
 */
@Composable
fun rememberFolderChooser(vm: MainViewModel, title: String, onChosen: (String) -> Unit): () -> Unit {
    val callback by rememberUpdatedState(onChosen)
    var showPicker by remember { mutableStateOf(false) }
    val saf = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            vm.grantFolder(uri)
            callback(uri.toString())
        }
    }
    if (showPicker) {
        FolderPickerDialog(
            title = title,
            onPick = { dir ->
                showPicker = false
                callback(Uri.fromFile(dir).toString())
            },
            onDismiss = { showPicker = false },
        )
    }
    return { if (vm.allFilesAccess) showPicker = true else saf.launch(null) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderPickerDialog(title: String, onPick: (File) -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val roots = remember { Docs.storageRoots(ctx) }
    // null = Auswahl des Speicherorts (nur wenn es mehrere gibt)
    var current by remember { mutableStateOf<File?>(if (roots.size == 1) roots.first().second else null) }
    var refresh by remember { mutableIntStateOf(0) }
    var newFolder by remember { mutableStateOf(false) }
    val rootDirs = roots.map { it.second.absolutePath }.toSet()

    val goUp: () -> Unit = {
        val c = current
        current = when {
            c == null -> { onDismiss(); null }
            c.absolutePath in rootDirs -> if (roots.size == 1) { onDismiss(); c } else null
            else -> c.parentFile
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BackHandler(onBack = goUp)
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                TopAppBar(
                    title = {
                        Column {
                            Text(title, style = MaterialTheme.typography.titleMedium)
                            Text(
                                current?.let { Docs.prettyPath(Uri.fromFile(it)) } ?: "Speicherort wählen",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    },
                    navigationIcon = { IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, "Abbrechen") } },
                    actions = {
                        if (current != null) {
                            IconButton(onClick = { newFolder = true }) {
                                Icon(Icons.Filled.CreateNewFolder, "Neuer Ordner")
                            }
                        }
                    },
                )

                val dir = current
                val folders = remember(dir, refresh) { dir?.let { Docs.subfolders(it) }.orEmpty() }
                LazyColumn(Modifier.weight(1f)) {
                    if (dir == null) {
                        items(roots) { (label, root) ->
                            FolderRow(label, Icons.Filled.PhoneAndroid) { current = root }
                        }
                    } else {
                        if (roots.size > 1 || dir.absolutePath !in rootDirs) {
                            item { FolderRow("Übergeordneter Ordner", Icons.Filled.ArrowUpward, onClick = goUp) }
                        }
                        items(folders, key = { it.absolutePath }) { f ->
                            FolderRow(f.name, Icons.Filled.Folder) { current = f }
                        }
                        if (folders.isEmpty()) {
                            item {
                                Text(
                                    "Keine Unterordner. Mit dem Ordner-Symbol oben legst du einen neuen an.",
                                    Modifier.padding(24.dp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                Button(
                    onClick = { dir?.let(onPick) },
                    enabled = dir != null,
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                ) {
                    Icon(Icons.Filled.Check, null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (dir == null) "Speicherort wählen" else "Diesen Ordner verwenden")
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

@Composable
private fun FolderRow(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(16.dp))
        Text(label, Modifier.weight(1f), fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Icon(Icons.Filled.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
