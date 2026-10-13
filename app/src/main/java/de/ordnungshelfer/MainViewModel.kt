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
    /** Ordner, für den [index] gerade eingelesen ist (Explorer-Suche) */
    var indexRoot by mutableStateOf<String?>(null)
        private set

    /** Alle Dateien des Handys für die Home-Seite (null = noch nicht geladen) */
    var library by mutableStateOf<List<DupEntry>?>(null)
        private set
    var libraryLoading by mutableStateOf(false)
        private set

    fun loadLibrary() {
        if (libraryLoading) return
        libraryLoading = true
        viewModelScope.launch(Dispatchers.IO) {
            val l = Library.load(ctx).sortedByDescending { it.node.modified }
            withContext(Dispatchers.Main) {
                library = l
                libraryLoading = false
            }
        }
    }

    /** Zuletzt genutzter Ordner für Scans */
    var scanFolder by mutableStateOf(store.loadScanFolder())
        private set

    fun chooseScanFolder(f: String?) {
        scanFolder = f
        store.saveScanFolder(f)
    }

    /** Gescanntes PDF unter eigenem Namen im Wunschordner speichern. */
    fun saveScan(pdf: Uri, wanted: String, dest: String?, newTags: List<String>, text: String?, onDone: (Uri?) -> Unit) {
        var name = wanted.trim().replace('/', '_').trimStart('.')
        if (name.isEmpty()) { message = "Bitte einen Dateinamen eingeben"; return }
        if (!name.endsWith(".pdf", ignoreCase = true)) name += ".pdf"
        val target = dest ?: Uri.fromFile(
            File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOCUMENTS), "Scans").apply { mkdirs() }
        ).toString()
        work("Speichere Scan …") {
            val saved = Docs.copy(ctx, pdf, name, "application/pdf", Docs.rootDoc(Uri.parse(target)))
            withContext(Dispatchers.Main) {
                if (saved == null) {
                    message = "Speichern nicht möglich"
                } else {
                    if (newTags.isNotEmpty()) remapTags(emptyMap(), mapOf(saved.toString() to newTags))
                    if (!text.isNullOrBlank()) {
                        content = content + (saved.toString() to text.take(50_000))
                        store.saveContent(content)
                    }
                    savedNotice("Gespeichert", saved)
                }
                onDone(saved)
            }
        }
    }

    var gridView by mutableStateOf(store.loadGridView())
        private set

    fun toggleGridView() {
        gridView = !gridView
        store.saveGridView(gridView)
    }

    /** Erkannter Text gescannter Dokumente (Datei-URI → Text) */
    var content by mutableStateOf(store.loadContent())
        private set

    /** Favoriten (Datei-URIs) */
    var favorites by mutableStateOf(store.loadFavorites().toSet())
        private set

    /** Ordner für die Suche (leer = Standard) */
    var searchFolders by mutableStateOf(store.loadSearchFolders())
        private set
    var watching by mutableStateOf(store.loadWatch())
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
        resolvePendingDownloads()
        // Tags können außerhalb der App geändert worden sein (Umbenennen aus der Benachrichtigung)
        tags = store.loadTags()
        if (watching && allFilesAccess && !WatchState.running) WatchService.start(ctx)
    }

    /** Neue Datei aus der Überwachung: umbenennen, ggf. verschieben, Tags vergeben – in einem Schritt. */
    fun processNewFile(file: File, wanted: String, dest: String?, newTags: List<String>, onDone: (Uri?) -> Unit) {
        val name = if (file.isDirectory) wanted.trim().replace('/', '_') else fixFileName(wanted, file.name)
        if (name.isEmpty()) { message = "Bitte einen Namen eingeben"; return }
        work("Speichere …") {
            val original = Uri.fromFile(file)
            var current = original // wo die Datei gerade liegt
            var ok = true
            if (name != file.name) {
                val r = Docs.rename(ctx, current, name)
                if (r != null) current = r else ok = false
            }
            if (ok && dest != null) {
                val destUri = Docs.rootDoc(Uri.parse(dest))
                val src = File(current.path!!)
                val moved = if (src.isDirectory) {
                    // Ordner nur zwischen direkten Pfaden verschiebbar
                    val target = File(destUri.path ?: "", src.name)
                    if (Docs.isFile(destUri) && !target.exists() && src.renameTo(target)) Uri.fromFile(target) else null
                } else {
                    val node = Docs.nodeOf(ctx, current)
                    val parent = Docs.parentOf(current)
                    if (node != null && parent != null) Docs.move(ctx, current, node.name, node.mime, parent, destUri, node.name) else null
                }
                if (moved != null) current = moved else ok = false
            }
            withContext(Dispatchers.Main) {
                // Tags (vorhandene + neue) hängen an der Datei an ihrem neuen Platz
                val combined = normalizeTags(tags[original.toString()].orEmpty() + newTags)
                tags = (tags - original.toString()).let { if (combined.isEmpty()) it else it + (current.toString() to combined) }
                store.saveTags(tags)
                index = null
                if (ok) savedNotice("Gespeichert", current)
                else message = "Nicht alles hat geklappt – gibt es den Namen am Ziel schon?"
                onDone(if (ok) current else null)
            }
        }
    }

    var autoSort by mutableStateOf(store.loadAutoSort())
        private set

    fun setAutoSortNew(on: Boolean) {
        autoSort = on
        store.saveAutoSort(on)
    }

    // ---------- Hinweis-Banner mit Knöpfen ----------

    /** Hinweis unten mit Aktionen, z. B. „In den Papierkorb verschoben – Rückgängig“. */
    data class Notice(val text: String, val actions: List<Pair<String, () -> Unit>>, val id: Long = System.nanoTime())

    var notice by mutableStateOf<Notice?>(null)

    fun showNotice(text: String, vararg actions: Pair<String, () -> Unit>) {
        notice = Notice(text, actions.toList())
    }

    /** Ordner in der Suche/Explorer öffnen und die Datei markieren. */
    var revealPath by mutableStateOf<String?>(null)

    fun reveal(path: String?) { revealPath = path }

    /** Hinweis „Gespeichert“ mit „Öffnen“ und „Anzeigen“. */
    fun savedNotice(prefix: String, uri: Uri) {
        val node = Docs.nodeOf(ctx, uri)
        val name = node?.name ?: uri.lastPathSegment ?: ""
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        if (node != null) actions += "Öffnen" to { openFile(ctx, node) { message = it } }
        if (Docs.isFile(uri)) actions += "Anzeigen" to { reveal(uri.path) }
        notice = Notice("$prefix: $name", actions)
    }

    /** Gelöschte Dateien aus dem Papierkorb zurückholen (für „Rückgängig“). */
    fun restoreTrash(entries: List<TrashEntry>) = work("Stelle wieder her …") {
        val restored = entries.filter { Sorter.restore(ctx, it) != null }
        withContext(Dispatchers.Main) {
            trash = trash - restored.toSet()
            store.saveTrash(trash)
            index = null
            message = if (restored.size == entries.size) "Wiederhergestellt" else "${restored.size} von ${entries.size} wiederhergestellt"
        }
    }

    // ---------- Dateiansicht ----------

    /** Geöffnete Dateiansicht: Liste zum Durchwischen und Startposition. */
    data class ViewerState(val entries: List<DupEntry>, val index: Int)

    var viewer by mutableStateOf<ViewerState?>(null)
        private set

    /** Zählt jede Dateiänderung hoch, damit Listen sich neu laden. */
    var fsVersion by mutableIntStateOf(0)
        private set

    fun openViewer(entries: List<DupEntry>, index: Int) {
        if (entries.isNotEmpty()) viewer = ViewerState(entries, index)
    }

    fun closeViewer() {
        viewer = null
        fsVersion++
    }

    // ---------- Darstellung ----------

    /** 0 = wie System, 1 = hell, 2 = dunkel */
    var themeMode by mutableIntStateOf(store.loadThemeMode())
        private set
    var dynamicColor by mutableStateOf(store.loadDynamicColor())
        private set

    fun updateThemeMode(m: Int) { themeMode = m; store.saveThemeMode(m) }
    fun updateDynamicColor(on: Boolean) { dynamicColor = on; store.saveDynamicColor(on) }

    // ---------- App-Sperre ----------

    var lockEnabled by mutableStateOf(store.loadLock())
        private set
    /** App ist gesperrt, bis Fingerabdruck/Displaysperre bestätigt wurde. */
    var locked by mutableStateOf(store.loadLock())
        private set
    private var backgroundSince = 0L

    fun setLock(on: Boolean) {
        lockEnabled = on
        store.saveLock(on)
        message = if (on) "App-Sperre eingeschaltet" else "App-Sperre ausgeschaltet"
    }

    fun unlocked() { locked = false }
    fun onBackground() { backgroundSince = System.currentTimeMillis() }

    /** Nach mehr als 2 Minuten im Hintergrund wieder sperren. */
    fun onForeground() {
        if (lockEnabled && backgroundSince > 0 && System.currentTimeMillis() - backgroundSince > 2 * 60_000) locked = true
        backgroundSince = 0
    }

    // ---------- Tags verwalten ----------

    fun renameTag(old: String, wanted: String) {
        val new = normalizeTags(listOf(wanted)).firstOrNull() ?: return
        tags = tags.mapValues { (_, list) -> normalizeTags(list.map { if (it.equals(old, true)) new else it }) }
        store.saveTags(tags)
        rules = rules.map { r -> r.copy(tags = normalizeTags(r.tags.map { if (it.equals(old, true)) new else it })) }
        store.saveRules(rules)
        message = "Tag „$old“ heißt jetzt „$new“"
    }

    fun deleteTag(t: String) {
        tags = tags.mapValues { (_, list) -> list.filterNot { it.equals(t, true) } }.filterValues { it.isNotEmpty() }
        store.saveTags(tags)
        rules = rules.map { r -> r.copy(tags = r.tags.filterNot { it.equals(t, true) }) }
        store.saveRules(rules)
        message = "Tag „$t“ gelöscht"
    }

    // ---------- Sicherung ----------

    fun exportBackup(target: Uri) = work("Erstelle Sicherung …") {
        val ok = runCatching {
            ctx.contentResolver.openOutputStream(target, "wt")!!.use { it.write(store.exportJson().toByteArray()) }
        }.isSuccess
        withContext(Dispatchers.Main) { message = if (ok) "Sicherung gespeichert" else "Sicherung fehlgeschlagen" }
    }

    fun importBackup(source: Uri) = work("Spiele Sicherung ein …") {
        val json = runCatching { ctx.contentResolver.openInputStream(source)!!.use { it.readBytes().decodeToString() } }.getOrNull()
        val ok = json != null && store.importJson(json)
        withContext(Dispatchers.Main) {
            if (ok) reloadAll()
            message = if (ok) "Sicherung wiederhergestellt" else "Keine gültige Sicherung von CX File Manager"
        }
    }

    /** Nach dem Wiederherstellen: alle Werte neu aus dem Speicher lesen. */
    private fun reloadAll() {
        roots = store.loadRoots()
        rules = store.loadRules()
        settings = store.loadSettings()
        trash = store.loadTrash()
        tags = store.loadTags()
        favorites = store.loadFavorites().toSet()
        content = store.loadContent()
        searchFolders = store.loadSearchFolders()
        scanFolder = store.loadScanFolder()
        gridView = store.loadGridView()
        autoSort = store.loadAutoSort()
        watching = store.loadWatch()
        lockEnabled = store.loadLock()
        themeMode = store.loadThemeMode()
        dynamicColor = store.loadDynamicColor()
        undoCount = store.loadUndo().size
        plan = null
        index = null
        dupGroups = null
        refreshNames()
        if (watching && allFilesAccess) WatchService.start(ctx) else WatchService.stop(ctx)
    }

    /** Überwachung des Download-Ordners ein-/ausschalten. */
    fun setWatch(on: Boolean) {
        if (on && !allFilesAccess) {
            message = "Bitte zuerst „Zugriff auf alle Dateien“ erlauben"
            return
        }
        watching = on
        store.saveWatch(on)
        if (on) WatchService.start(ctx) else WatchService.stop(ctx)
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

    /** Bestehende Regel ändern – Position in der Liste bleibt gleich. */
    fun updateRule(id: Long, field: RuleField, pattern: String, target: String, base: String?, ruleTags: List<String>) {
        rules = rules.map {
            if (it.id == id) it.copy(
                field = field,
                pattern = pattern.trim(),
                targetFolder = target.trim().trim('/'),
                targetBase = base,
                tags = normalizeTags(ruleTags),
            ) else it
        }
        store.saveRules(rules)
        plan = null
        message = "Regel gespeichert"
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
        withContext(Dispatchers.Main) { resolvePendingDownloads() }
        val custom = searchFolders.isNotEmpty()
        val chosen = if (custom) searchFolders else {
            val bases = rules.mapNotNull { it.targetBase } + listOfNotNull(settings.targetBase)
            usableRoots + bases
        }
        val folders = chosen.filter { allFilesAccess || !it.startsWith("file:") }.distinct()
            .map { it to (rootNames[it] ?: Docs.prettyFolder(it).substringAfterLast('/')) }
        val all = Sorter.listAll(ctx, folders) { n ->
            busy = "Lese Dateien ein … ($n)"
        }.toMutableList()
        val known = all.map { it.node.uri.toString() }.toMutableSet()
        // Getaggte Dateien außerhalb dieser Ordner trotzdem finden
        val trashed = trash.map { it.uri }.toSet()
        val stale = mutableListOf<String>()
        for (key in tags.keys) {
            if (custom) break // bei eigener Ordnerauswahl nur diese Ordner zeigen
            if (key in known || key in trashed) continue
            val uri = Uri.parse(key)
            val node = Docs.nodeOf(ctx, uri)
            if (node == null) stale += key
            else all += DupEntry(node, Docs.prettyParent(uri), Docs.parentOf(uri) ?: uri, uri)
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

    /** Liest einen Ordner samt Unterordnern für die Explorer-Suche ein. */
    fun buildIndexFor(dir: File) = work("Durchsuche „${dir.name}“ …") {
        val all = Sorter.listAll(ctx, listOf(Uri.fromFile(dir).toString() to dir.name)) { n ->
            busy = "Durchsuche „${dir.name}“ … ($n)"
        }
        withContext(Dispatchers.Main) {
            index = all
            indexRoot = dir.absolutePath
        }
    }

    /** Startordner der Suche (null = Hauptordner des internen Speichers). */
    fun setSearchStart(stored: String?) {
        searchFolders = listOfNotNull(stored)
        store.saveSearchFolders(searchFolders)
        message = "Startordner gespeichert"
    }

    fun isFavorite(uri: Uri) = uri.toString() in favorites

    fun toggleFavorite(uri: Uri) {
        val k = uri.toString()
        favorites = if (k in favorites) favorites - k else favorites + k
        store.saveFavorites(favorites)
        message = if (k in favorites) "Zu Favoriten hinzugefügt" else "Aus Favoriten entfernt"
    }

    fun addSearchFolder(stored: String) {
        if (stored in searchFolders) return
        // War „Ganzer Speicher“ aktiv, gilt ab jetzt nur der gewählte Ordner
        searchFolders = if (searchesWholeStorage) listOf(stored) else searchFolders + stored
        store.saveSearchFolders(searchFolders)
        buildIndexAfterScopeChange()
    }

    fun removeSearchFolder(stored: String) {
        searchFolders = searchFolders - stored
        store.saveSearchFolders(searchFolders)
        buildIndexAfterScopeChange()
    }

    /** Ganzen Speicher durchsuchen (interner Speicher + ggf. SD-Karte). */
    fun searchWholeStorage() {
        if (!allFilesAccess) {
            message = "Dafür braucht die App „Zugriff auf alle Dateien“ (unter „Einstellung“)"
            return
        }
        searchFolders = Docs.storageRoots(ctx).map { Uri.fromFile(it.second).toString() }
        store.saveSearchFolders(searchFolders)
        buildIndexAfterScopeChange()
    }

    fun resetSearchFolders() {
        searchFolders = emptyList()
        store.saveSearchFolders(searchFolders)
        buildIndexAfterScopeChange()
    }

    val searchesWholeStorage: Boolean
        get() = searchFolders.isNotEmpty() &&
            searchFolders.toSet() == Docs.storageRoots(ctx).map { Uri.fromFile(it.second).toString() }.toSet()

    private fun buildIndexAfterScopeChange() {
        index = null
        buildIndex()
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

    // ---------- Einzelne Dateien ----------

    /** Neuer Eintrag nach Umbenennen/Verschieben, damit Liste und Detailansicht aktuell bleiben. */
    private fun entryFor(uri: Uri, root: Uri): DupEntry? {
        val node = Docs.nodeOf(ctx, uri) ?: return null
        return DupEntry(node, Docs.prettyParent(uri), Docs.parentOf(uri) ?: uri, root)
    }

    private fun replaceInIndex(old: DupEntry, new: DupEntry?) {
        index = index?.let { list ->
            val i = list.indexOfFirst { it.node.uri == old.node.uri }
            when {
                new == null -> list.filterNot { it.node.uri == old.node.uri }
                i < 0 -> listOf(new) + list
                else -> list.toMutableList().also { it[i] = new }
            }
        }
    }

    fun renameFile(e: DupEntry, wanted: String, onDone: (DupEntry) -> Unit) {
        var name = wanted.trim()
        val oldExt = extensionOf(e.node.name)
        // Endung vergessen? Dann die alte behalten.
        if (oldExt.isNotEmpty() && extensionOf(name).isEmpty()) name = "$name.$oldExt"
        when {
            name.isEmpty() || name.contains('/') || name.startsWith(".") -> { message = "Ungültiger Dateiname"; return }
            name == e.node.name -> return
        }
        work("Benenne um …") {
            val newUri = Docs.rename(ctx, e.node.uri, name)
            val updated = newUri?.let { entryFor(it, e.root) }
            withContext(Dispatchers.Main) {
                if (newUri == null || updated == null) {
                    message = "Umbenennen nicht möglich – gibt es „$name“ dort schon?"
                } else {
                    remapTags(mapOf(e.node.uri.toString() to newUri.toString()), emptyMap())
                    replaceInIndex(e, updated)
                    message = "Umbenannt in „$name“"
                    onDone(updated)
                }
            }
        }
    }

    fun moveFile(e: DupEntry, dest: String, onDone: (DupEntry) -> Unit) = work("Verschiebe …") {
        val destDir = Docs.rootDoc(Uri.parse(dest))
        val newUri = Docs.move(ctx, e.node.uri, e.node.name, e.node.mime, e.parent, destDir, e.node.name)
        val updated = newUri?.let { entryFor(it, destDir) }
        withContext(Dispatchers.Main) {
            if (newUri == null || updated == null) {
                message = "Verschieben nicht möglich"
            } else {
                remapTags(mapOf(e.node.uri.toString() to newUri.toString()), emptyMap())
                replaceInIndex(e, updated)
                savedNotice("Verschoben", newUri)
                onDone(updated)
            }
        }
    }

    fun copyFile(e: DupEntry, dest: String) = work("Kopiere …") {
        val destDir = Docs.rootDoc(Uri.parse(dest))
        val newUri = Docs.copy(ctx, e.node.uri, e.node.name, e.node.mime, destDir)
        val copy = newUri?.let { entryFor(it, destDir) }
        withContext(Dispatchers.Main) {
            if (newUri == null || copy == null) {
                message = "Kopieren nicht möglich"
            } else {
                // Die Kopie bekommt dieselben Tags
                tagsOf(e.node.uri).takeIf { it.isNotEmpty() }?.let { remapTags(emptyMap(), mapOf(newUri.toString() to it)) }
                index = index?.let { listOf(copy) + it }
                savedNotice("Kopiert", newUri)
            }
        }
    }

    // ---------- Download-Manager ----------

    /** Geteilte Datei (Anhang, Datei aus anderer App) unter eigenem Namen speichern. */
    fun saveIncoming(source: Uri, wanted: String, original: String, dest: String?, newTags: List<String>, onDone: (Uri?) -> Unit) {
        val name = fixFileName(wanted, original)
        if (name.isEmpty()) { message = "Bitte einen Dateinamen eingeben"; return }
        work("Speichere …") {
            val saved = Downloader.saveStream(ctx, source, name, dest, allFilesAccess)
            withContext(Dispatchers.Main) {
                if (saved == null) {
                    message = "Speichern nicht möglich"
                } else {
                    if (newTags.isNotEmpty()) remapTags(emptyMap(), mapOf(saved.toString() to newTags))
                    index = null
                    savedNotice("Gespeichert", saved)
                }
                onDone(saved)
            }
        }
    }

    /** Link mit eigenem Dateinamen in den Download-Ordner herunterladen. */
    fun startDownload(url: String, wanted: String, original: String, subfolder: String, mime: String?, newTags: List<String>): Boolean {
        val name = fixFileName(wanted, original)
        if (name.isEmpty()) { message = "Bitte einen Dateinamen eingeben"; return false }
        return try {
            val sub = subfolder.split('/').map { it.trim() }.filter { it.isNotEmpty() }
            // Selbst gestartete Downloads nicht noch einmal als „neu“ melden
            val top = sub.firstOrNull() ?: name
            WatchState.ignore(File(Downloader.downloadDir(), top).absolutePath, 30 * 60_000L)
            val id = Downloader.enqueue(ctx, url, name, subfolder, mime)
            if (newTags.isNotEmpty()) store.savePendingDownloads(store.loadPendingDownloads() + (id to normalizeTags(newTags)))
            message = "Download gestartet: $name"
            true
        } catch (e: Exception) {
            message = "Download nicht möglich: ${e.message}"
            false
        }
    }

    /** Fertige Downloads bekommen ihre Tags (die genaue Datei steht erst nach dem Download fest). */
    fun resolvePendingDownloads() {
        val pending = store.loadPendingDownloads()
        if (pending.isEmpty()) return
        val done = mutableMapOf<String, List<String>>()
        val remaining = pending.filter { (id, t) ->
            when (val r = Downloader.result(ctx, id)) {
                null -> true // läuft noch
                "" -> false // fehlgeschlagen
                else -> { done[r] = t; false }
            }
        }
        store.savePendingDownloads(remaining)
        if (done.isNotEmpty()) remapTags(emptyMap(), done)
    }

    // ---------- Dateimanager: Löschen, Mehrfachaktionen, Ordner ----------

    /** Dateien und Ordner (direkte Pfade) in den Papierkorb verschieben. */
    fun trashUris(uris: Collection<Uri>, onDone: () -> Unit = {}) {
        if (uris.isEmpty()) return
        work("Verschiebe in den Papierkorb …") {
            val entries = uris.mapNotNull { u ->
                val f = File(u.path ?: return@mapNotNull null)
                if (!f.exists()) return@mapNotNull null
                val node = if (f.isDirectory) {
                    Node(u, f.name, android.provider.DocumentsContract.Document.MIME_TYPE_DIR, f.walkTopDown().filter { it.isFile }.sumOf { it.length() }, f.lastModified())
                } else Docs.nodeOf(ctx, u) ?: return@mapNotNull null
                val parent = Uri.fromFile(f.parentFile ?: f)
                DupEntry(node, Docs.prettyParent(u), parent, Uri.fromFile(Docs.volumeRootOf(ctx, f)))
            }
            val added = Sorter.moveToTrash(ctx, entries) { n -> busy = "Papierkorb: $n von ${entries.size} …" }
            store.saveTrash(trash + added)
            withContext(Dispatchers.Main) {
                trash = trash + added
                index = null
                val failed = uris.size - added.size
                val text = if (failed == 0) "${added.size} in den Papierkorb verschoben" else "${added.size} verschoben, $failed nicht möglich"
                if (added.isNotEmpty()) showNotice(text, "Rückgängig" to { restoreTrash(added) }) else message = text
                onDone()
            }
        }
    }

    /** Einzelne Datei in den Papierkorb (auch für freigegebene Ordner ohne direkten Pfad). */
    fun trashEntry(e: DupEntry, onDone: () -> Unit = {}) {
        if (Docs.isFile(e.node.uri)) { trashUris(listOf(e.node.uri), onDone); return }
        work("Verschiebe in den Papierkorb …") {
            val added = Sorter.moveToTrash(ctx, listOf(e)) { }
            store.saveTrash(trash + added)
            withContext(Dispatchers.Main) {
                trash = trash + added
                index = null
                if (added.isEmpty()) message = "Löschen nicht möglich"
                else showNotice("In den Papierkorb verschoben", "Rückgängig" to { restoreTrash(added) })
                if (added.isNotEmpty()) onDone()
            }
        }
    }

    /** Mehrere Dateien/Ordner verschieben oder kopieren (Ziel: gespeicherter Ordner-Wert). */
    fun transferMany(uris: Collection<Uri>, dest: String, copy: Boolean, onDone: () -> Unit = {}) {
        if (uris.isEmpty()) return
        work(if (copy) "Kopiere …" else "Verschiebe …") {
            val destUri = Docs.rootDoc(Uri.parse(dest))
            val renamed = mutableMapOf<String, String>()
            var ok = 0
            uris.forEachIndexed { i, u ->
                busy = (if (copy) "Kopiere " else "Verschiebe ") + "${i + 1} von ${uris.size} …"
                val f = File(u.path ?: return@forEachIndexed)
                val result: Uri? = if (f.isDirectory) {
                    if (!Docs.isFile(destUri)) null
                    else (if (copy) Docs.copyDir(ctx, f, File(destUri.path!!)) else Docs.moveDir(ctx, f, File(destUri.path!!)))?.let { Uri.fromFile(it) }
                } else {
                    val node = Docs.nodeOf(ctx, u)
                    val parent = Docs.parentOf(u)
                    if (node == null || parent == null) null
                    else if (copy) Docs.copy(ctx, u, node.name, node.mime, destUri)
                    else Docs.move(ctx, u, node.name, node.mime, parent, destUri, node.name)
                }
                if (result != null) {
                    ok++
                    if (!copy) renamed[u.toString()] = result.toString()
                    else tags[u.toString()]?.let { renamed["copy:" + result] = it.joinToString("\u0000") }
                }
            }
            withContext(Dispatchers.Main) {
                val moves = renamed.filterKeys { !it.startsWith("copy:") }
                val copies = renamed.filterKeys { it.startsWith("copy:") }
                    .map { (k, v) -> k.removePrefix("copy:") to v.split("\u0000") }.toMap()
                remapTags(moves, copies)
                // Ordner verschoben: Tags/Favoriten der Dateien darin mitnehmen
                moves.forEach { (old, new) -> remapPrefix(old, new) }
                index = null
                message = (if (copy) "$ok kopiert" else "$ok verschoben") + if (ok < uris.size) ", ${uris.size - ok} nicht möglich" else ""
                onDone()
            }
        }
    }

    /** Datei oder Ordner umbenennen (direkter Pfad). */
    fun renamePath(uri: Uri, wanted: String, onDone: () -> Unit = {}) {
        val f = File(uri.path ?: return)
        val name = if (f.isDirectory) wanted.trim().replace('/', '_') else fixFileName(wanted, f.name)
        if (name.isEmpty() || name.startsWith(".")) { message = "Ungültiger Name"; return }
        if (name == f.name) return
        work("Benenne um …") {
            val r = Docs.rename(ctx, uri, name)
            withContext(Dispatchers.Main) {
                if (r == null) {
                    message = "Umbenennen nicht möglich – gibt es „$name“ schon?"
                } else {
                    remapTags(mapOf(uri.toString() to r.toString()), emptyMap())
                    remapPrefix(uri.toString(), r.toString())
                    index = null
                    message = "Umbenannt in „$name“"
                }
                onDone()
            }
        }
    }

    fun createFolder(parent: File, name: String): Boolean {
        val clean = name.trim()
        if (clean.isEmpty() || clean.contains('/') || clean.startsWith(".")) { message = "Ungültiger Name"; return false }
        val d = File(parent, clean)
        if (d.exists()) { message = "„$clean“ gibt es schon"; return false }
        WatchState.ignore(d.absolutePath)
        return d.mkdir().also { message = if (it) "Ordner „$clean“ angelegt" else "Ordner konnte nicht angelegt werden" }
    }

    /** Nach Ordner-Umbenennung/-Verschiebung: Tags, Favoriten und Texte der Dateien darin umschreiben. */
    private fun remapPrefix(oldDir: String, newDir: String) {
        val o = "$oldDir/"
        val n = "$newDir/"
        if (tags.keys.any { it.startsWith(o) }) {
            tags = tags.mapKeys { (k, _) -> if (k.startsWith(o)) n + k.removePrefix(o) else k }
            store.saveTags(tags)
        }
        if (favorites.any { it.startsWith(o) || it == oldDir }) {
            favorites = favorites.map { if (it.startsWith(o)) n + it.removePrefix(o) else if (it == oldDir) newDir else it }.toSet()
            store.saveFavorites(favorites)
        }
    }

    /** Tags folgen der Datei, wenn die App sie verschiebt. */
    private fun remapTags(renamed: Map<String, String>, added: Map<String, List<String>>) {
        if (renamed.isEmpty() && added.isEmpty()) return
        val updated = tags.toMutableMap()
        renamed.forEach { (old, new) -> updated.remove(old)?.let { updated[new] = it } }
        // Erkannter Text wandert mit
        if (renamed.keys.any { it in content }) {
            content = content.mapKeys { (k, _) -> renamed[k] ?: k }
            store.saveContent(content)
        }
        // Favoriten wandern ebenfalls mit
        if (renamed.keys.any { it in favorites }) {
            favorites = favorites.map { renamed[it] ?: it }.toSet()
            store.saveFavorites(favorites)
        }
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
                withContext(Dispatchers.Main) {
                    busy = null
                    fsVersion++
                }
            }
        }
    }
}
