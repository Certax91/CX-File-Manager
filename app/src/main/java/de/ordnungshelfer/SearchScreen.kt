package de.ordnungshelfer

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import java.io.File

private const val MAX_RESULTS = 300

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(vm: MainViewModel, onGoToFolders: () -> Unit) {
    val ctx = LocalContext.current
    var query by rememberSaveable { mutableStateOf("") }
    var tagFilter by remember { mutableStateOf<Set<String>>(emptySet()) }
    var selection by remember { mutableStateOf<Set<Uri>>(emptySet()) }
    var editing by remember { mutableStateOf<DupEntry?>(null) }
    var bulkTag by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (vm.index == null && vm.roots.isNotEmpty()) vm.buildIndex()
    }
    BackHandler(enabled = selection.isNotEmpty()) { selection = emptySet() }

    val index = vm.index
    val tagsMap = vm.tags
    val results = remember(index, query, tagFilter, tagsMap) {
        val terms = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val wanted = tagFilter.map { it.lowercase() }
        index.orEmpty().filter { e ->
            val fileTags = tagsMap[e.node.uri.toString()].orEmpty().map { it.lowercase() }
            wanted.all { it in fileTags } &&
                terms.all { t -> e.node.name.lowercase().contains(t) || fileTags.any { it.contains(t) } }
        }
    }

    Column(Modifier.fillMaxSize()) {
        if (selection.isEmpty()) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Name oder Tag suchen") },
                    leadingIcon = { Icon(Icons.Filled.Search, null) },
                    trailingIcon = {
                        if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Close, "Leeren") }
                    },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = vm::buildIndex, enabled = vm.roots.isNotEmpty()) {
                    Icon(Icons.Filled.Refresh, "Neu einlesen")
                }
            }
        } else {
            Row(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.secondaryContainer).padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { selection = emptySet() }) { Icon(Icons.Filled.Close, "Auswahl beenden") }
                Text("${selection.size} ausgewählt", Modifier.weight(1f), fontWeight = FontWeight.Medium)
                TextButton(onClick = { selection = results.take(MAX_RESULTS).map { it.node.uri }.toSet() }) { Text("Alle") }
                Button(onClick = { bulkTag = true }) {
                    Icon(Icons.Filled.Sell, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Tag vergeben")
                }
            }
        }

        val allTags = vm.allTags
        if (allTags.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                allTags.forEach { t ->
                    val on = t in tagFilter
                    FilterChip(
                        selected = on,
                        onClick = { tagFilter = if (on) tagFilter - t else tagFilter + t },
                        label = { Text("#$t") },
                    )
                }
            }
        }

        when {
            vm.roots.isEmpty() && index.isNullOrEmpty() -> EmptyState(
                Icons.Filled.FolderOpen, "Noch kein Ordner gewählt",
                "Füge zuerst einen Ordner hinzu – dann kannst du hier Dateien suchen und mit Tags versehen.",
            ) { FilledTonalButton(onClick = onGoToFolders) { Text("Ordner wählen") } }

            index == null -> EmptyState(
                Icons.Filled.Search, "Dateien suchen",
                "Liest deine Ordner, Ablageorte und alle getaggten Dateien ein.",
            ) { FilledTonalButton(onClick = vm::buildIndex) { Text("Jetzt einlesen") } }

            results.isEmpty() -> EmptyState(Icons.Filled.SearchOff, "Nichts gefunden", "Versuch einen anderen Begriff oder Tag.")

            else -> {
                Text(
                    if (results.size > MAX_RESULTS) "${results.size} Treffer – die neuesten $MAX_RESULTS werden angezeigt"
                    else "${results.size} Treffer · lange drücken für Mehrfachauswahl",
                    Modifier.padding(horizontal = 16.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(vertical = 4.dp)) {
                    items(results.take(MAX_RESULTS), key = { it.node.uri.toString() }) { e ->
                        val selected = e.node.uri in selection
                        val fileTags = tagsMap[e.node.uri.toString()].orEmpty()
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface)
                                .combinedClickable(
                                    onClick = {
                                        if (selection.isNotEmpty()) {
                                            selection = if (selected) selection - e.node.uri else selection + e.node.uri
                                        } else {
                                            editing = e
                                        }
                                    },
                                    onLongClick = { selection = selection + e.node.uri },
                                )
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                if (selected) Icons.Filled.CheckCircle else iconFor(e.node.name),
                                null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(e.node.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                                Text(
                                    "${e.path} · ${Formatter.formatShortFileSize(ctx, e.node.size)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                                if (fileTags.isNotEmpty()) {
                                    Text(
                                        fileTags.joinToString("  ") { "#$it" },
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.tertiary,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    editing?.let { e ->
        TagEditDialog(
            vm = vm,
            entry = e,
            onOpen = { openFile(ctx, e.node) { vm.message = it } },
            onDismiss = { editing = null },
        )
    }

    if (bulkTag) {
        TagInputDialog(
            title = "Tag für ${selection.size} Dateien",
            suggestions = vm.allTags,
            onConfirm = { t ->
                vm.addTagTo(selection, t)
                bulkTag = false
                selection = emptySet()
            },
            onDismiss = { bulkTag = false },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagEditDialog(vm: MainViewModel, entry: DupEntry, onOpen: () -> Unit, onDismiss: () -> Unit) {
    val current = vm.tagsOf(entry.node.uri)
    var input by remember { mutableStateOf("") }
    val suggestions = vm.allTags.filter { s -> current.none { it.equals(s, ignoreCase = true) } }

    fun add(text: String) {
        val new = parseTags(text)
        if (new.isNotEmpty()) vm.setTags(entry.node.uri, current + new)
        input = ""
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(entry.node.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(entry.path, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (current.isEmpty()) {
                    Text("Noch keine Tags.", style = MaterialTheme.typography.bodyMedium)
                } else {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        current.forEach { t ->
                            InputChip(
                                selected = true,
                                onClick = { vm.setTags(entry.node.uri, current - t) },
                                label = { Text("#$t") },
                                trailingIcon = { Icon(Icons.Filled.Close, "Entfernen", Modifier.size(16.dp)) },
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text("Neuer Tag") },
                    singleLine = true,
                    trailingIcon = {
                        IconButton(onClick = { add(input) }, enabled = input.isNotBlank()) { Icon(Icons.Filled.Add, "Hinzufügen") }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (suggestions.isNotEmpty()) {
                    Text("Vorhandene Tags", style = MaterialTheme.typography.labelMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        suggestions.take(15).forEach { t ->
                            SuggestionChip(onClick = { add(t) }, label = { Text("#$t") })
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { if (input.isNotBlank()) add(input); onDismiss() }) { Text("Fertig") }
        },
        dismissButton = {
            TextButton(onClick = onOpen) {
                Icon(Icons.Filled.OpenInNew, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Öffnen")
            }
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagInputDialog(title: String, suggestions: List<String>, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var input by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text("Tag") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (suggestions.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        suggestions.take(15).forEach { t ->
                            SuggestionChip(onClick = { onConfirm(t) }, label = { Text("#$t") })
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(input) }, enabled = input.isNotBlank()) { Text("Vergeben") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

private fun iconFor(name: String) = when (Categories.of(name)) {
    "Bilder" -> Icons.Filled.Image
    "Videos" -> Icons.Filled.Movie
    "Audio" -> Icons.Filled.MusicNote
    "PDFs" -> Icons.Filled.PictureAsPdf
    "Tabellen" -> Icons.Filled.TableChart
    "Archive" -> Icons.Filled.FolderZip
    "Apps" -> Icons.Filled.Android
    else -> Icons.Filled.Description
}

/** Datei mit einer passenden App öffnen. */
fun openFile(ctx: Context, node: Node, onError: (String) -> Unit) {
    val uri = if (Docs.isFile(node.uri)) {
        FileProvider.getUriForFile(ctx, ctx.packageName + ".files", File(node.uri.path!!))
    } else {
        node.uri
    }
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, node.mime)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        ctx.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        onError("Keine App zum Öffnen von „${node.name}“ gefunden")
    } catch (e: Exception) {
        onError("Datei konnte nicht geöffnet werden")
    }
}
