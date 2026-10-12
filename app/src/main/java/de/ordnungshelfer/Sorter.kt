package de.ordnungshelfer

import android.content.Context
import android.net.Uri
import java.security.MessageDigest

object Sorter {

    /** Durchsucht die gewählten Ordner und berechnet, welche Datei wohin gehört. Verschiebt noch nichts. */
    fun plan(
        ctx: Context,
        roots: List<String>,
        rootNames: Map<String, String>,
        rules: List<Rule>,
        settings: Settings,
    ): List<PlannedMove> {
        val managed = Categories.names + rules.mapNotNull { it.targetSegments.firstOrNull() }
        val out = mutableListOf<PlannedMove>()
        for (r in roots) {
            val tree = Uri.parse(r)
            val rootDoc = Docs.rootDoc(tree)
            walk(ctx, rootDoc, rootDoc, rootNames[r] ?: "Ordner", emptyList(), rules, settings, managed, out)
        }
        return out
    }

    private fun walk(
        ctx: Context,
        root: Uri,
        dir: Uri,
        rootName: String,
        path: List<String>,
        rules: List<Rule>,
        settings: Settings,
        managed: Set<String>,
        out: MutableList<PlannedMove>,
    ) {
        for (node in Docs.children(ctx, dir)) {
            if (node.name.startsWith(".")) continue // versteckte Dateien/Ordner (auch .Papierkorb) nicht anfassen
            if (node.isDir) {
                if (node.name == "Android") continue // Systemdaten von Apps nie anfassen
                // Bereits sortierte Ordner (Bilder, PDFs, eigene Zielordner …) werden nicht erneut sortiert
                val isManaged = path.isEmpty() && node.name in managed
                if (settings.includeSubfolders && !isManaged) {
                    walk(ctx, root, node.uri, rootName, path + node.name, rules, settings, managed, out)
                }
            } else {
                val (target, reason) = targetFor(ctx, node, rules, settings) ?: continue
                if (target == path) continue // liegt schon richtig
                out += PlannedMove(node, dir, root, rootName, path, target, reason)
            }
        }
    }

    fun targetFor(ctx: Context, node: Node, rules: List<Rule>, s: Settings): Pair<List<String>, String>? {
        val rule = rules.firstOrNull { it.matches(node.name) && it.targetSegments.isNotEmpty() }
        val target = mutableListOf<String>()
        val reasons = mutableListOf<String>()
        when {
            rule != null -> {
                target += rule.targetSegments
                reasons += "Regel: ${rule.field.label} „${rule.pattern}“"
            }
            s.byType -> {
                target += Categories.of(node.name)
                reasons += "Typ"
            }
        }
        if (s.byDate) {
            MediaDate.of(ctx, node)?.let { d ->
                target += d.year.toString()
                target += "%02d".format(d.month)
                reasons += d.source.label
            }
        }
        if (target.isEmpty()) return null
        return target to reasons.joinToString(" · ")
    }

    data class Result(val moved: Int, val failed: Int, val undo: List<UndoEntry>)

    fun execute(ctx: Context, moves: List<PlannedMove>, progress: (Int) -> Unit): Result {
        val cache = mutableMapOf<String, Uri>()
        val undo = mutableListOf<UndoEntry>()
        var failed = 0
        moves.forEachIndexed { i, m ->
            progress(i + 1)
            val targetDir = Docs.ensureDir(ctx, m.root, m.target, cache)
            val newUri = targetDir?.let {
                Docs.move(ctx, m.file.uri, m.file.name, m.file.mime, m.parent, it, m.file.name)
            }
            if (newUri == null) {
                failed++
            } else {
                undo += UndoEntry(newUri.toString(), m.parent.toString(), targetDir.toString(), m.file.name, m.file.mime)
            }
        }
        return Result(undo.size, failed, undo)
    }

    /** Macht die letzte Sortierung rückgängig. Gibt die Zahl der nicht zurückgeholten Dateien zurück. */
    fun undo(ctx: Context, entries: List<UndoEntry>, progress: (Int) -> Unit): Int {
        var failed = 0
        val touchedDirs = mutableSetOf<String>()
        entries.reversed().forEachIndexed { i, e ->
            progress(i + 1)
            val file = Uri.parse(e.movedUri)
            val currentName = Docs.displayName(ctx, file)
            if (currentName == null) {
                failed++
                return@forEachIndexed
            }
            val ok = Docs.move(ctx, file, currentName, e.mime, Uri.parse(e.currentParent), Uri.parse(e.originalParent), e.originalName)
            if (ok == null) failed++ else touchedDirs += e.currentParent
        }
        // Leere Ordner, die beim Sortieren entstanden sind, wieder entfernen
        touchedDirs.forEach { d ->
            val uri = Uri.parse(d)
            if (Docs.children(ctx, uri).isEmpty()) Docs.delete(ctx, uri)
        }
        return failed
    }

    /** Findet inhaltsgleiche Dateien: erst nach Größe vorsortiert, dann per SHA-256 verglichen. */
    fun findDuplicates(
        ctx: Context,
        roots: List<String>,
        rootNames: Map<String, String>,
        progress: (String) -> Unit,
    ): List<List<DupEntry>> {
        val all = mutableListOf<DupEntry>()
        for (r in roots) {
            progress("Lese Ordner …")
            val root = Docs.rootDoc(Uri.parse(r))
            collect(ctx, root, root, rootNames[r] ?: "Ordner", all)
        }
        val candidates = all.filter { it.node.size > 0 }.groupBy { it.node.size }.values.filter { it.size > 1 }
        val total = candidates.sumOf { it.size }
        var done = 0
        val groups = mutableListOf<List<DupEntry>>()
        for (sameSize in candidates) {
            val byHash = sameSize.groupBy { entry ->
                done++
                progress("Vergleiche Datei $done von $total")
                hash(ctx, entry.node.uri)
            }
            byHash.filterKeys { it != null }.values
                .filter { it.size > 1 }
                .forEach { g -> groups += g.sortedBy { it.node.modified } } // älteste zuerst = Original
        }
        return groups.sortedByDescending { it.first().node.size * (it.size - 1) }
    }

    private fun collect(ctx: Context, root: Uri, dir: Uri, path: String, out: MutableList<DupEntry>) {
        for (n in Docs.children(ctx, dir)) {
            if (n.name.startsWith(".") || n.name == "Android") continue
            if (n.isDir) collect(ctx, root, n.uri, "$path/${n.name}", out)
            else out += DupEntry(n, path, dir, root)
        }
    }

    private fun hash(ctx: Context, uri: Uri): String? = try {
        val md = MessageDigest.getInstance("SHA-256")
        ctx.contentResolver.openInputStream(uri)?.use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        } ?: return null
        md.digest().joinToString("") { "%02x".format(it) }
    } catch (e: Exception) {
        null
    }

    // ---------- Papierkorb ----------

    const val TRASH_DIR = ".Papierkorb"
    const val TRASH_DAYS = 30
    private const val DAY_MS = 24L * 60 * 60 * 1000

    /** Verschiebt Dateien in den Ordner „.Papierkorb“ des jeweiligen Ordners (für Galerie-Apps unsichtbar). */
    fun moveToTrash(ctx: Context, entries: List<DupEntry>, progress: (Int) -> Unit): List<TrashEntry> {
        val cache = mutableMapOf<String, Uri>()
        val out = mutableListOf<TrashEntry>()
        entries.forEachIndexed { i, e ->
            progress(i + 1)
            val dir = Docs.ensureDir(ctx, e.root, listOf(TRASH_DIR), cache) ?: return@forEachIndexed
            val moved = Docs.move(ctx, e.node.uri, e.node.name, e.node.mime, e.parent, dir, e.node.name)
                ?: return@forEachIndexed
            out += TrashEntry(
                uri = moved.toString(),
                originalParent = e.parent.toString(),
                trashDir = dir.toString(),
                originalName = e.node.name,
                mime = e.node.mime,
                deletedAt = System.currentTimeMillis(),
                size = e.node.size,
            )
        }
        return out
    }

    /** Holt eine Datei aus dem Papierkorb an ihren alten Platz zurück. */
    fun restore(ctx: Context, t: TrashEntry): Boolean {
        val uri = Uri.parse(t.uri)
        val name = Docs.displayName(ctx, uri) ?: return false
        return Docs.move(ctx, uri, name, t.mime, Uri.parse(t.trashDir), Uri.parse(t.originalParent), t.originalName) != null
    }

    /** Löscht endgültig. true, wenn die Datei danach weg ist. */
    fun purge(ctx: Context, t: TrashEntry): Boolean {
        val uri = Uri.parse(t.uri)
        return Docs.delete(ctx, uri) || Docs.displayName(ctx, uri) == null
    }

    fun daysLeft(t: TrashEntry): Int =
        (TRASH_DAYS - ((System.currentTimeMillis() - t.deletedAt) / DAY_MS).toInt()).coerceAtLeast(0)

    fun isExpired(t: TrashEntry): Boolean = System.currentTimeMillis() - t.deletedAt > TRASH_DAYS * DAY_MS
}
