package de.ordnungshelfer

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
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
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Einstellung › Daten: Tags verwalten, Sicherung/Wiederherstellung, App-Sperre. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DataScreen(vm: MainViewModel) {
    val ctx = LocalContext.current
    var renaming by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<String?>(null) }
    var confirmImport by remember { mutableStateOf<android.net.Uri?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) vm.exportBackup(uri)
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) confirmImport = uri
    }
    val tagCounts = vm.tags.values.flatten().groupingBy { it.lowercase() }.eachCount()

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ---------- Darstellung ----------
        item {
            SectionTitle("Darstellung")
            Spacer(Modifier.height(8.dp))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf("System", "Hell", "Dunkel").forEachIndexed { i, label ->
                    SegmentedButton(
                        selected = vm.themeMode == i,
                        onClick = { vm.updateThemeMode(i) },
                        shape = SegmentedButtonDefaults.itemShape(index = i, count = 3),
                    ) { Text(label) }
                }
            }
            Spacer(Modifier.height(8.dp))
            SwitchRow(
                "Farben vom Hintergrundbild",
                "Statt der CX-Farben (Navy & Bernstein) die Farben deines Hintergrundbilds verwenden (ab Android 12).",
                vm.dynamicColor,
            ) { vm.updateDynamicColor(it) }
        }

        // ---------- App-Sperre ----------
        item {
            SectionTitle("App-Sperre")
            Spacer(Modifier.height(4.dp))
            SwitchRow(
                "Mit Fingerabdruck sichern",
                "Beim Öffnen – und nach mehr als 2 Minuten im Hintergrund – fragt die App nach Fingerabdruck, Gesicht oder Displaysperre.",
                vm.lockEnabled,
            ) { on ->
                if (on) {
                    val can = BiometricManager.from(ctx).canAuthenticate(
                        BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
                    )
                    if (can == BiometricManager.BIOMETRIC_SUCCESS) vm.setLock(true)
                    else vm.message = "Bitte zuerst eine Displaysperre oder einen Fingerabdruck im Handy einrichten"
                } else {
                    vm.setLock(false)
                }
            }
        }

        // ---------- Sicherung ----------
        item {
            Spacer(Modifier.height(8.dp))
            SectionTitle("Sicherung")
            Spacer(Modifier.height(4.dp))
            Hint("Sichert Regeln, Tags, Favoriten, Ordner, erkannte Texte und alle Einstellungen in einer Datei – z. B. für ein neues Handy. Die Dateien selbst werden nicht gesichert.")
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val name = "CX-File-Manager_Sicherung_" + SimpleDateFormat("yyyy-MM-dd", Locale.GERMANY).format(Date()) + ".json"
                    exportLauncher.launch(name)
                }) {
                    Icon(Icons.Filled.Backup, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Sichern")
                }
                OutlinedButton(onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }) {
                    Icon(Icons.Filled.Restore, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Wiederherstellen")
                }
            }
        }

        // ---------- Tags ----------
        item {
            Spacer(Modifier.height(8.dp))
            SectionTitle("Tags verwalten")
            Spacer(Modifier.height(4.dp))
            Hint(if (vm.allTags.isEmpty()) "Noch keine Tags vergeben." else "Umbenennen gilt für alle Dateien und Regeln. Gleichnamige Tags werden zusammengeführt.")
        }
        items(vm.allTags, key = { it }) { t ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Sell, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.tertiary)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("#$t")
                        Hint("${tagCounts[t.lowercase()] ?: 0} Dateien")
                    }
                    IconButton(onClick = { renaming = t }) { Icon(Icons.Filled.Edit, "Umbenennen") }
                    IconButton(onClick = { deleting = t }) { Icon(Icons.Filled.Delete, "Löschen") }
                }
            }
        }
    }

    renaming?.let { t ->
        NameDialog(title = "Tag umbenennen", initial = t, confirm = "Umbenennen", onDismiss = { renaming = null }) { new ->
            renaming = null
            vm.renameTag(t, new)
        }
    }
    deleting?.let { t ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Tag „$t“ löschen?") },
            text = { Text("Der Tag wird von allen Dateien und Regeln entfernt. Die Dateien selbst bleiben unverändert.") },
            confirmButton = { TextButton(onClick = { deleting = null; vm.deleteTag(t) }) { Text("Löschen", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Abbrechen") } },
        )
    }
    confirmImport?.let { uri ->
        AlertDialog(
            onDismissRequest = { confirmImport = null },
            title = { Text("Sicherung wiederherstellen?") },
            text = { Text("Alle aktuellen Regeln, Tags, Favoriten und Einstellungen werden durch die Sicherung ersetzt.") },
            confirmButton = { TextButton(onClick = { confirmImport = null; vm.importBackup(uri) }) { Text("Wiederherstellen") } },
            dismissButton = { TextButton(onClick = { confirmImport = null }) { Text("Abbrechen") } },
        )
    }
}
