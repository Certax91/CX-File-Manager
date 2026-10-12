package de.ordnungshelfer

import android.app.Application
import android.content.Intent
import android.net.Uri
import java.io.File
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx: Application get() = getApplication()
    private val store = Store(app)

    var roots by mutableStateOf(store.loadRoots())
        private set
    var rootNames by mutableStateOf<Map<String, String>>(emptyMap())
        private set
    var rules by mutableStateOf(store.loadRules())
        private set
    var settings by mutableStateOf(store.loadSettings())
        private set

    /** null = noch keine Vorschau erstellt */
    var plan by mutableStateOf<List<PlannedMove>?>(null)
        private set
    var selected by mutableStateOf<Set<Int>>(emptySet())

    var dupGroups by mutableStateOf<List<List<DupEntry>>?>(null)
        private set
    var dupSelected by mutableStateOf<Set<Uri>>(emptySet())

    var undoCount by mutableIntStateOf(store.loadUndo().size)
        private set
    var trash by mutableStateOf(store.loadTrash())
        private set

    /** Tags je Datei-URI */
    var tags by mutableStateOf(store.loadTags())
        private set

    /** Alle Dateien für die Suche. null = noch nicht eingelesen. */
    var index by mutableStateOf<List<DupEntry>?>(null)
        private set
    var allFilesAccess by mutableStateOf(Docs.hasAllFilesAccess(app))
        private set
    var busy by mutableStateOf<String?>(null)
        private set
    var message by mutableStateOf<String?>(null)

    init {
        // Ordner entfernen, für die Android den Zugriff nicht mehr gewährt
        val granted = ctx.contentResolver.persistedUriPermissions.map { it.uri.toString() }.toSet()
        val valid = roots.filter { it in granted || it.startsWith("file:") }
        if (valid != roots) {
            roots = valid
            store.saveRoots(valid)
        }
        refreshNames()
        purgeExpiredTrash()
    }

    /** Wird beim Zurückkehren in die App aufgerufen, z. B. nach dem Erteilen der Berechtigung. */
    fun refreshAccess() {
        allFilesAccess = Docs.hasAllFilesAccess(ctx)
        refreshNames()
    }

    /** Ordner, auf die die App gerade zugreifen kann (file://-Ordner nur mit „Zugriff auf alle Dateien“). */
    private val usableRoots: List<String>
        get() = roots.filter { allFilesAccess || !it.startsWith("file:") }

    private fun refreshNames() {
        rootNames = roots.associateWith { Docs.displayName(ctx, Docs.rootDoc(Uri.parse(it))) ?: "Ordner" }
    }

    // ---------- Ordner ----------

    fun addRoot(uri: Uri) {
        runCatching {
            ctx.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        val s = uri.toString()
        if (s !in roots) {
            roots = roots + s
            store.saveRoots(roots)
            refreshNames()
            plan = null
            dupGroups = null
        }
    }

    fun addFileRoot(dir: File) {
        addRoot(Uri.fromFile(dir))
    }

    /** Dauerhaften Zugriff auf einen über die Android-Ordnerauswahl gewählten Ordner sichern. */
    fun grantFolder(uri: Uri) {
        runCatching {
            ctx.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
    }

    /** Anzeigename eines gespeicherten Ordners (Ablageort, Quellordner). */
    fun folderLabel(stored: String): String = Docs.prettyFolder(stored)

    fun removeRoot(s: String) {
        runCatching {
            ctx.contentResolver.releasePersistableUriPermission(
                Uri.parse(s),
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        roots = roots - s
        store.saveRoots(roots)
        refreshNames()
        plan = null
        dupGroups = null
    }

    fun updateSettings(s: Settings) {
        settings = s
        store.saveSettings(s)
        plan = null
    }

    // ---------- Regeln ----------

    fun addRule(field: RuleField, pattern: String, target: String, base: String?, ruleTags: List<String>) {
        rules = rules + Rule(System.currentTimeMillis(), field, pattern.trim(), target.trim().trim('/'), base, normalizeTags(ruleTags))
        store.saveRules(rules)
        plan = null
    }

    fun deleteRule(rule: Rule) {
        rules = rules - rule
        store.saveRules(rules)
        plan = null
    }

    fun moveRule(rule: Rule, up: Boolean) {
        val i = rules.indexOf(rule)
        val j = if (up) i - 1 else i + 1
        if (i < 0 || j !in rules.indices) return
        rules = rules.toMutableList().also { it[i] = it[j]; it[j] = rule }
        store.saveRules(rules)
        plan = null
    }

    // ---------- Sortieren ----------

    fun buildPlan() = work("Durchsuche Ordner …") {
        val p = Sorter.plan(ctx, usableRoots, rootNames, rules, settings)
        withContext(Dispatchers.Main) {
            plan = p
            selected = p.indices.toSet()
        }
    }

    fun toggle(i: Int) {
        selected = if (i in selected) selected - i else selected + i
    }

    fun selectAll(all: Boolean) {
        selected = if (all) plan.orEmpty().indices.toSet() else emptySet()
    }

    fun executePlan() {
        val moves = plan.orEmpty().filterIndexed { i, _ -> i in selected }
        if (moves.isEmpty()) return
        work("Verschiebe …") {
            val r = Sorter.execute(ctx, moves) { n -> busy = "Verschiebe $n von ${moves.size} …" }
            store.saveUndo(r.undo)
            withContext(Dispatchers.Main) {
                remapTags(r.renamed, r.autoTags)
                index = null
                undoCount = r.undo.size
                plan = null
                selected = emptySet()
                message = if (r.failed == 0) "${r.moved} Dateien sortiert"
                else "${r.moved} Dateien sortiert, ${r.failed} konnten nicht verschoben werden"
            }
        }
    }

    fun undoLast() {
        val entries = store.loadUndo()
        if (entries.isEmpty()) return
        work("Mache rückgängig …") {
            val (failed, renamed) = Sorter.undo(ctx, entries) { n -> busy = "Hole zurück $n von ${entries.size} …" }
            store.saveUndo(emptyList())
            withContext(Dispatchers.Main) {
                remapTags(renamed, emptyMap())
                index = null
                undoCount = 0
                plan = null
                message = if (failed == 0) "Letzte Sortierung rückgängig gemacht"
                else "${entries.size - failed} zurückgeholt, $failed nicht mehr auffindbar"
            }
        }
    }

    // ---------- Duplikate ----------

    fun findDuplicates() = work("Suche Duplikate …") {
        val groups = Sorter.findDuplicates(ctx, usableRoots, rootNames) { busy = it }
        withContext(Dispatchers.Main) {
            dupGroups = groups
            // Vorauswahl: alle Kopien außer der ältesten Datei je Gruppe
            dupSelected = groups.flatMap { g -> g.drop(1).map { it.node.uri } }.toSet()
        }
    }

    fun toggleDup(uri: Uri) {
        dupSelected = if (uri in dupSelected) dupSelected - uri else dupSelected + uri
    }

    /** Verschiebt die ausgewählten Duplikate in den Papierkorb (30 Tage wiederherstellbar). */
    fun trashSelectedDuplicates() {
        val chosen = dupGroups.orEmpty().flatten().filter { it.node.uri in dupSelected }
        if (chosen.isEmpty()) return
        work("Verschiebe in den Papierkorb …") {
            val added = Sorter.moveToTrash(ctx, chosen) { n -> busy = "Papierkorb: $n von ${chosen.size} …" }
            val newTrash = trash + added
            store.saveTrash(newTrash)
            val movedNames = added.map { it.originalParent + "/" + it.originalName }.toSet()
            withContext(Dispatchers.Main) {
                trash = newTrash
                dupGroups = dupGroups.orEmpty()
                    .map { g -> g.filter { (it.parent.toString() + "/" + it.node.name) !in movedNames } }
                    .filter { it.size > 1 }
                dupSelected = emptySet()
                val failed = chosen.size - added.size
                message = if (failed == 0) "${added.size} Duplikate in den Papierkorb verschoben"
                else "${added.size} verschoben, $failed nicht möglich"
            }
        }
    }

    fun restoreFromTrash(t: TrashEntry) = work("Stelle wieder her …") {
        val ok = Sorter.restore(ctx, t) != null
        val newTrash = if (ok || Docs.displayName(ctx, Uri.parse(t.uri)) == null) trash - t else trash
        store.saveTrash(newTrash)
        withContext(Dispatchers.Main) {
            trash = newTrash
            dupGroups = null // Liste ist nicht mehr aktuell
            message = if (ok) "„${t.originalName}“ wiederhergestellt"
            else "„${t.originalName}“ konnte nicht wiederhergestellt werden"
        }
    }

    fun emptyTrash() {
        val all = trash
        if (all.isEmpty()) return
        work("Leere Papierkorb …") {
            val remaining = all.filterNot { Sorter.purge(ctx, it) }
            store.saveTrash(remaining)
            withContext(Dispatchers.Main) {
                trash = remaining
                message = "${all.size - remaining.size} Dateien endgültig gelöscht"
            }
        }
    }

    /** Beim Start: alles, was länger als 30 Tage im Papierkorb liegt, endgültig löschen. */
    private fun purgeExpiredTrash() {
        val expired = trash.filter { Sorter.isExpired(it) }
        if (expired.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val gone = expired.filter { Sorter.purge(ctx, it) }.toSet()
            withContext(Dispatchers.Main) {
                trash = trash - gone
                store.saveTrash(trash)
            }
        }
    }

    // ---------- Tags & Suche ----------

    /** Liest alle Dateien der Ordner und Ablageorte ein, plus alle getaggten Dateien. */
    fun buildIndex() = work("Lese Dateien ein …") {
        val bases = (rules.mapNotNull { it.targetBase } + listOfNotNull(settings.targetBase))
            .filter { allFilesAccess || !it.startsWith("file:") }
        val folders = (usableRoots + bases).distinct().map { it to (rootNames[it] ?: Docs.prettyFolder(it).substringAfterLast('/')) }
        val all = Sorter.listAll(ctx, folders).toMutableList()
        val known = all.map { it.node.uri.toString() }.toMutableSet()
        // Getaggte Dateien außerhalb dieser Ordner trotzdem finden
        val trashed = trash.map { it.uri }.toSet()
        val stale = mutableListOf<String>()
        for (key in tags.keys) {
            if (key in known || key in trashed) continue
            val uri = Uri.parse(key)
            val node = Docs.nodeOf(ctx, uri)
            if (node == null) stale += key
            else all += DupEntry(node, Docs.prettyParent(uri), uri, uri)
        }
        withContext(Dispatchers.Main) {
            if (stale.isNotEmpty()) {
                // Dateien, die es nicht mehr gibt, aus der Tag-Liste entfernen
                tags = tags - stale.toSet()
                store.saveTags(tags)
            }
            index = all.sortedByDescending { it.node.modified }
        }
    }

    /** Alle vergebenen Tags, die häufigsten zuerst. */
    val allTags: List<String>
        get() = tags.values.flatten()
            .groupBy { it.lowercase() }
            .entries.sortedWith(compareByDescending<Map.Entry<String, List<String>>> { it.value.size }.thenBy { it.key })
            .map { it.value.first() }

    fun tagsOf(uri: Uri): List<String> = tags[uri.toString()].orEmpty()

    fun setTags(uri: Uri, newTags: List<String>) {
        val key = uri.toString()
        val clean = normalizeTags(newTags)
        tags = if (clean.isEmpty()) tags - key else tags + (key to clean)
        store.saveTags(tags)
    }

    /** Einen Tag mehreren Dateien auf einmal geben. */
    fun addTagTo(uris: Collection<Uri>, tag: String) {
        val t = normalizeTags(listOf(tag)).firstOrNull() ?: return
        val updated = tags.toMutableMap()
        uris.forEach { u ->
            val key = u.toString()
            updated[key] = normalizeTags(updated[key].orEmpty() + t)
        }
        tags = updated
        store.saveTags(tags)
        message = "Tag „$t“ an ${uris.size} Dateien vergeben"
    }

    /** Tags folgen der Datei, wenn die App sie verschiebt. */
    private fun remapTags(renamed: Map<String, String>, added: Map<String, List<String>>) {
        if (renamed.isEmpty() && added.isEmpty()) return
        val updated = tags.toMutableMap()
        renamed.forEach { (old, new) -> updated.remove(old)?.let { updated[new] = it } }
        added.forEach { (uri, t) -> updated[uri] = normalizeTags(updated[uri].orEmpty() + t) }
        tags = updated
        store.saveTags(tags)
    }

    private fun work(label: String, block: suspend () -> Unit) {
        if (busy != null) return
        busy = label
        viewModelScope.launch(Dispatchers.IO) {
            try {
                block()
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { message = "Fehler: ${e.message}" }
            } finally {
                withContext(Dispatchers.Main) { busy = null }
            }
        }
    }
}
