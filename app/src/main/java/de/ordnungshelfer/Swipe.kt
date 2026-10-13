package de.ordnungshelfer

import android.net.Uri
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import java.io.File

/** Aktionen aus dem Wisch-Menü einer Datei (Verschieben, Umbenennen, Tags). Dialoge werden hier verwaltet. */
class FileActions(
    val move: (Uri) -> Unit,
    val rename: (Uri) -> Unit,
    val tags: (Uri) -> Unit,
)

@Composable
fun rememberFileActions(vm: MainViewModel): FileActions {
    var moveUri by remember { mutableStateOf<Uri?>(null) }
    var renameFile by remember { mutableStateOf<File?>(null) }
    var tagsUri by remember { mutableStateOf<Uri?>(null) }
    val chooseMove = rememberFolderChooser(vm, "Verschieben nach") { dest ->
        moveUri?.let { vm.transferMany(listOf(it), dest, copy = false) }
        moveUri = null
    }

    renameFile?.let { f ->
        NameDialog(title = "Umbenennen", initial = f.name, confirm = "Umbenennen", selectBase = true, onDismiss = { renameFile = null }) { name ->
            renameFile = null
            vm.renamePath(Uri.fromFile(f), name)
        }
    }
    tagsUri?.let { u ->
        val f = File(u.path ?: "")
        val entry = DupEntry(Node(u, f.name, "", f.length(), f.lastModified()), Docs.prettyParent(u), u, u)
        TagEditDialog(vm = vm, entry = entry, onOpen = { tagsUri = null; vm.openViewer(listOf(entry), 0) }, onDismiss = { tagsUri = null })
    }

    return remember {
        FileActions(
            move = { u -> moveUri = u; chooseMove() },
            rename = { u -> u.path?.let { renameFile = File(it) } },
            tags = { u -> tagsUri = u },
        )
    }
}

/**
 * Datei-Zeile mit Wischgesten:
 *  - nach links: in den Papierkorb
 *  - nach rechts: Menü mit Verschieben, Umbenennen, Tags, Favorit
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwipeFileRow(
    vm: MainViewModel,
    actions: FileActions,
    name: String,
    subtitle: String,
    uri: Uri,
    selected: Boolean,
    swipeEnabled: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    var menuOpen by remember(uri) { mutableStateOf(false) }
    // Neuer Zustand nach jeder Dateiänderung – z. B. wenn „Rückgängig“ die Datei zurückholt
    key(uri, vm.fsVersion) {
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { v ->
            when (v) {
                // Zeile ganz nach links hinausschieben, dann in den Papierkorb
                SwipeToDismissBoxValue.EndToStart -> { vm.trashUris(listOf(uri)); true }
                SwipeToDismissBoxValue.StartToEnd -> { menuOpen = true; false }
                else -> false
            }
        },
        positionalThreshold = { it * 0.4f },
    )

    AnimatedContent(targetState = menuOpen, label = "wischmenü") { open ->
        if (open) {
            SwipeMenu(
                favorite = uri.toString() in vm.favorites,
                onMove = { menuOpen = false; actions.move(uri) },
                onRename = { menuOpen = false; actions.rename(uri) },
                onTags = { menuOpen = false; actions.tags(uri) },
                onFavorite = { menuOpen = false; vm.toggleFavorite(uri) },
                onClose = { menuOpen = false },
            )
        } else {
            SwipeToDismissBox(
                state = state,
                enableDismissFromStartToEnd = swipeEnabled,
                enableDismissFromEndToStart = swipeEnabled,
                backgroundContent = {
                    val dir = state.dismissDirection
                    val (color, icon, label, align) = when (dir) {
                        SwipeToDismissBoxValue.EndToStart -> Quad(MaterialTheme.colorScheme.error, Icons.Filled.Delete, "Papierkorb", Alignment.CenterEnd)
                        SwipeToDismissBoxValue.StartToEnd -> Quad(MaterialTheme.colorScheme.secondary, Icons.Filled.MoreHoriz, "Aktionen", Alignment.CenterStart)
                        else -> Quad(Color.Transparent, Icons.Filled.MoreHoriz, "", Alignment.Center)
                    }
                    Box(
                        Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 2.dp)
                            .clip(MaterialTheme.shapes.medium).background(color).padding(horizontal = 24.dp),
                        contentAlignment = align,
                    ) {
                        if (dir != SwipeToDismissBoxValue.Settled) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(icon, null, tint = Color.White)
                                Spacer(Modifier.width(8.dp))
                                Text(label, color = Color.White, style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                },
            ) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    FileRow(vm, name, subtitle, uri, selected, onClick, onLongClick)
                }
            }
        }
    }
    }
}

private data class Quad(val color: Color, val icon: ImageVector, val label: String, val align: Alignment)

/** Blaues Aktionsmenü nach dem Wischen nach rechts (wie im Beispiel). */
@Composable
private fun SwipeMenu(
    favorite: Boolean,
    onMove: () -> Unit,
    onRename: () -> Unit,
    onTags: () -> Unit,
    onFavorite: () -> Unit,
    onClose: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.secondary,
        contentColor = MaterialTheme.colorScheme.onSecondary,
    ) {
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            MenuItem(Icons.Filled.DriveFileMove, "Verschieben", onMove)
            MenuItem(Icons.Filled.DriveFileRenameOutline, "Umbenennen", onRename)
            MenuItem(Icons.Filled.Sell, "Tags", onTags)
            MenuItem(if (favorite) Icons.Filled.Star else Icons.Filled.StarBorder, if (favorite) "Favorit ✓" else "Favorit", onFavorite)
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Schließen") }
        }
    }
}

@Composable
private fun MenuItem(icon: ImageVector, label: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp),
        colors = ButtonDefaults.textButtonColors(contentColor = LocalContentColor.current),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null)
            Spacer(Modifier.height(2.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
    }
}
