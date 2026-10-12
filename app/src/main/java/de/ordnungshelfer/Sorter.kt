package de.ordnungshelfer

import android.content.Context
import android.net.Uri
import java.security.MessageDigest

object Sorter {

    /** Wohin eine Datei soll: unter [base] (null = im sortierten Ordner selbst) in die Unterordner [segments]. */
    data class Target(val base: String?, val segments: List<String>, val reason: String, val tags: List<String>)

    /** Durchsucht die gewählten Ordner und berechnet, welche Datei wohin gehört. Verschiebt noch nichts. */
    fun plan(
        ctx: Context,
        roots: List<String>,
        rootNames: Map<String, String>,
        rules: List<Rule>,
        settings: Settings,
    ): List<PlannedMove> {
        val managed = Categories.names + rules.filter { it.targetBase == null }.mapNotNull { it.targetSegments.firstOrNull() }
        val bases = (rules.mapNotNull { it.targetBase } + listOfNotNull(settings.targetBase)).distinct()
        val baseDocs = bases.associateWith { Docs.rootDoc(Uri.parse(it)) }
        val baseNames = bases.associateWith { Docs.displayName(ctx, baseDocs.getValue(it)) ?: "Ablage" }
        val ctxData = WalkData(rules, settings, managed, baseDocs, baseNames, baseDocs.values.map { it.toString() }.toSet())
        val out = mutableListOf<PlannedMove>()
        for (r in roots) {
            val rootDoc = Docs.rootDoc(Uri.parse(r))
            walk(ctx, rootDoc, rootDoc, rootNames[r] ?: "Ordner", emptyList(), ctxData, out)
        }
        return out.distinctBy { it.file.uri.toString() }
    }

    private class WalkData(
        val rules: List<Rule>,
        val settings: Settings,
        val managed: Set<String>,
        val baseDocs: Map<String, Uri>,
        val baseNames: Map<String, String>,
        /** Ablageorte werden beim Durchsuchen übersprungen, damit Sortiertes nicht erneut sortiert wird. */
        val skipDirs: Set<String>,
    )

    private fun walk(
        ctx: Context,
        root: Uri,
        dir: Uri,
        rootName: String,
        path: List<String>,
        d: WalkData,
        out: MutableList<PlannedMove>,
    ) {
        for (node in Docs.children(ctx, dir)) {
            if (node.name.startsWith(".")) continue // versteckte Dateien/Ordner (auch .Papierkorb) nicht anfassen
            if (node.isDir) {
                if (node.name == "Android") continue // Systemdaten von Apps nie anfassen
                if (node.uri.toString() in d.skipDirs) continue
                // Bereits sortierte Ordner (Bilder, PDFs, eigene Zielordner …) werden nicht erneut sortiert
                val isManaged = path.isEmpty() && node.name in d.managed
                if (d.settings.includeSubfolders && !isManaged) {
                    walk(ctx, root, node.uri, rootName, path + node.name, d, out)
                }
            } else {
                val t = targetFor(ctx, node, d.rules, d.settings) ?: continue
                val baseUri = t.base?.let { d.baseDocs[it] } ?: root
                val baseName = t.base?.let { d.baseNames[it] } ?: rootName
                if (isAlreadyThere(dir, root, path, baseUri, t.segments)) continue
                out += PlannedMove(node, dir, root, rootName, path, baseUri, baseName, t.segments, t.reason, t.tags)
            }
        }
    }

    private fun isAlreadyThere(dir: Uri, root: Uri, path: List<String>, base: Uri, segments: List<String>): Boolean {
        if (base == root && segments == path) return true
        if (Docs.isFile(dir) && Docs.isFile(base)) {
            val dest = segments.fold(java.io.File(base.path!!)) { f, s -> java.io.File(f, s) }
            return dest.absolutePath == java.io.File(dir.path!!).absolutePath
        }
        return false
    }

    fun targetFor(ctx: Context, node: Node, rules: List<Rule>, s: Settings): Target? {
        val rule = rules.firstOrNull { it.matches(node.name) && (it.targetSegments.isNotEmpty() || it.targetBase != null) }
        val target = mutableListOf<String>()
        val reasons = mutableListOf<String>()
        var base: String? = s.targetBase
        when {
            rule != null -> {
                if (rule.targetBase != null) base = rule.targetBase
                target += rule.targetSegments
                reasons += "Regel: ${rule.field.label} „${rule.pattern}“"
            }
            s.byType -> {
                target += Categories.of(node.name)
                reasons += "Typ"
            }
        }
        if (s.byDate) {
            MediaDate.of(ctx, node)?.let { dt ->
                target += dt.year.toString()
                target += "%02d".format(dt.month)
                reasons += dt.source.label
            }
        }
        if (target.isEmpty() && base == null) return null
        if (rule == null && !s.byType && !s.byDate) return null
        return Target(base, target, reasons.joinToString(" · "), rule?.tags.orEmpty())
    }

    data class Result(
        val moved: Int,
        val failed: Int,
        val undo: List<UndoEntry>,
        /** alte URI → neue URI, damit Tags mitwandern */
        val renamed: Map<String, String>,
        /** neue URI → Tags aus Regeln */
        val autoTags: Map<String, List<String>>,
    )

    fun execute(ctx: Context, moves: List<PlannedMove>, progress: (Int) -> Unit): Result {
        val cache = mutableMapOf<String, Uri>()
        val undo = mutableListOf<UndoEntry>()
        val renamed = mutableMapOf<String, String>()
        val autoTags = mutableMapOf<String, List<String>>()
        var failed = 0
        moves.forEachIndexed { i, m ->
            progress(i + 1)
            val targetDir = Docs.ensureDir(ctx, m.base, m.target, cache)
            val newUri = targetDir?.let {
                Docs.move(ctx, m.file.uri, m.file.name, m.file.mime, m.parent, it, m.file.name)
            }
            if (newUri == null) {
                failed++
            } else {
                undo += UndoEntry(newUri.toString(), m.parent.toString(), targetDir.toString(), m.file.name, m.file.mime)
                renamed[m.file.uri.toString()] = newUri.toString()
                if (m.tags.isNotEmpty()) autoTags[newUri.toString()] = m.tags
            }
        }
        return Result(undo.size, failed, undo, renamed, autoTags)
    }

    /** Macht die letzte Sortierung rückgängig. Liefert (Fehlschläge, alte URI → neue URI). */
    fun undo(ctx: Context, entries: List<UndoEntry>, progress: (Int) -> Unit): Pair<Int, Map<String, String>> {
        var failed = 0
        val renamed = mutableMapOf<String, String>()
        val touchedDirs = mutableSetOf<String>()
        entries.reversed().forEachIndexed { i, e ->
            progress(i + 1)
            val file = Uri.parse(e.movedUri)
            val currentName = Docs.displayName(ctx, file)
            if (currentName == null) {
                failed++
                return@forEachIndexed
            }
            val back = Docs.move(ctx, file, currentName, e.mime, Uri.parse(e.currentParent), Uri.parse(e.originalParent), e.originalName)
            if (back == null) {
                failed++
            } else {
                touchedDirs += e.currentParent
                renamed[e.movedUri] = back.toString()
            }
        }
        // Leere Ordner, die beim Sortieren entstanden sind, wieder entfernen
        touchedDirs.forEach { d ->
            val uri = Uri.parse(d)
            if (Docs.children(ctx, uri).isEmpty()) Docs.delete(ctx, uri)
        }
        return failed to renamed
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

    /** Alle Dateien der Ordner (inkl. Unterordner) – für die Suche. */
    fun listAll(ctx: Context, folders: List<Pair<String, String>>): List<DupEntry> {
        val out = mutableListOf<DupEntry>()
        for ((stored, name) in folders) {
            val root = Docs.rootDoc(Uri.parse(stored))
            collect(ctx, root, root, name, out)
        }
        return out.distinctBy { it.node.uri.toString() }
    }

    private fun collect(ctx: Context, root: Uri, dir: Uri, path: String, out: MutableList<DupEntry>) {
        for (n in Docs.children(ctx, dir)) {
            if (n.name.startsWith(".") || n.name == "Android") continue
            if (n.isDir) collect(ctx, root, n.uri, "$path/${n.name}", out)
            else out += DupEntry(n, path, dir, root)
        }
    }

    private fun hash(ctx: Context, uri: Uri): String? {
        return try {
            val md = MessageDigest.getInstance("SHA-256")
            val input = ctx.contentResolver.openInputStream(uri) ?: return null
            input.use {
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = it.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            md.digest().joinToString("") { b -> "%02x".format(b) }
        } catch (e: Exception) {
            null
        }
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
    fun restore(ctx: Context, t: TrashEntry): Uri? {
        val uri = Uri.parse(t.uri)
        val name = Docs.displayName(ctx, uri) ?: return null
        return Docs.move(ctx, uri, name, t.mime, Uri.parse(t.trashDir), Uri.parse(t.originalParent), t.originalName)
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

