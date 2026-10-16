package de.ordnungshelfer

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Foto-Serie, z. B. ein Urlaub: Alle Fotos (und Videos) aus dem Zeitraum bekommen
 * automatisch Tags und – wenn gewünscht – einen Namen nach Muster.
 */
data class PhotoSeries(
    val id: Long,
    val name: String,
    /** erster und letzter Tag (LocalDate.toEpochDay) */
    val start: Long,
    val end: Long,
    val tags: List<String>,
    val rename: Boolean = true,
    val pattern: String = DEFAULT_PATTERN,
    /** neue Fotos im Zeitraum sofort automatisch bearbeiten */
    val autoNew: Boolean = true,
    val videos: Boolean = true,
    /** nur Kamera-Fotos (DCIM), keine Screenshots, WhatsApp-Bilder usw. */
    val cameraOnly: Boolean = true,
    /** angelegt (ms) – automatisch werden nur Fotos bearbeitet, die danach hinzukamen */
    val created: Long = System.currentTimeMillis(),
    /** bereits bearbeitete Dateien: Pfad → laufende Nummer */
    val assigned: Map<String, Int> = emptyMap(),
) {
    val startDate: LocalDate get() = LocalDate.ofEpochDay(start)
    val endDate: LocalDate get() = LocalDate.ofEpochDay(end)

    /** Läuft gerade (heute liegt im Zeitraum) */
    fun isActive(today: LocalDate = LocalDate.now()) = today.toEpochDay() in start..end
    fun isOver(today: LocalDate = LocalDate.now()) = today.toEpochDay() > end

    val rangeLabel: String
        get() {
            val s = startDate
            val e = endDate
            val full = DateTimeFormatter.ofPattern("dd.MM.yyyy")
            return when {
                s == e -> s.format(full)
                s.year == e.year -> s.format(DateTimeFormatter.ofPattern("dd.MM.")) + " – " + e.format(full)
                else -> s.format(full) + " – " + e.format(full)
            }
        }

    companion object {
        const val DEFAULT_PATTERN = "{name}_{datum}_{nr}"

        /** Platzhalter für das Namensmuster: Text → Erklärung */
        val PLACEHOLDERS = listOf(
            "{name}" to "Name der Serie",
            "{datum}" to "Aufnahmedatum (2026-10-12)",
            "{uhrzeit}" to "Uhrzeit (14-22-05)",
            "{nr}" to "Laufende Nummer (001)",
            "{reisetag}" to "Tag der Reise (1, 2, 3 …)",
        )
    }
}

/** Ein Foto aus der Mediendatenbank */
data class SeriesPhoto(val path: String, val name: String, val taken: Long, val added: Long, val mime: String)

/** Vorschau-Zeile: Foto → neuer Name und Nummer */
data class SeriesItem(val photo: SeriesPhoto, val nr: Int, val newName: String, val day: Int)

/** Was beim Anwenden passiert ist – für „Rückgängig“. */
data class SeriesChange(val seriesId: Long, val oldPath: String, val newPath: String, val tagsBefore: List<String>?)

/** Wird aufgerufen, wenn im Hintergrund Fotos bearbeitet wurden (zum Aktualisieren der Anzeige). */
object SeriesEvents {
    @Volatile var onChanged: (() -> Unit)? = null
}

object SeriesEngine {
    private val lock = Any()

    // ---------- Speichern ----------

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("ordnungshelfer", Context.MODE_PRIVATE)

    fun load(ctx: Context): List<PhotoSeries> {
        val raw = prefs(ctx).getString("photoSeries", null) ?: return emptyList()
        return runCatching {
            val a = JSONArray(raw)
            List(a.length()) { i ->
                val o = a.getJSONObject(i)
                val asg = o.optJSONObject("assigned")
                PhotoSeries(
                    id = o.getLong("id"),
                    name = o.getString("name"),
                    start = o.getLong("start"),
                    end = o.getLong("end"),
                    tags = o.optJSONArray("tags")?.let { t -> List(t.length()) { t.getString(it) } } ?: emptyList(),
                    rename = o.optBoolean("rename", true),
                    pattern = o.optString("pattern", PhotoSeries.DEFAULT_PATTERN),
                    autoNew = o.optBoolean("autoNew", true),
                    videos = o.optBoolean("videos", true),
                    cameraOnly = o.optBoolean("cameraOnly", true),
                    created = o.optLong("created", 0L),
                    assigned = asg?.keys()?.asSequence()?.associateWith { asg.getInt(it) } ?: emptyMap(),
                )
            }
        }.getOrDefault(emptyList())
    }

    fun save(ctx: Context, list: List<PhotoSeries>) {
        val a = JSONArray()
        list.forEach { s ->
            val asg = JSONObject()
            s.assigned.forEach { (k, v) -> asg.put(k, v) }
            a.put(
                JSONObject()
                    .put("id", s.id).put("name", s.name)
                    .put("start", s.start).put("end", s.end)
                    .put("tags", JSONArray(s.tags))
                    .put("rename", s.rename).put("pattern", s.pattern)
                    .put("autoNew", s.autoNew).put("videos", s.videos).put("cameraOnly", s.cameraOnly)
                    .put("created", s.created)
                    .put("assigned", asg)
            )
        }
        prefs(ctx).edit().putString("photoSeries", a.toString()).commit()
    }

    fun update(ctx: Context, change: (List<PhotoSeries>) -> List<PhotoSeries>): List<PhotoSeries> = synchronized(lock) {
        val new = change(load(ctx))
        save(ctx, new)
        new
    }

    /** Gibt es eine Serie, die gerade neue Fotos automatisch bearbeiten soll? */
    fun needsWatching(ctx: Context): Boolean = load(ctx).any { it.autoNew && it.isActive() }

    // ---------- Fotos finden ----------

    /** Alle Fotos (und ggf. Videos) im Zeitraum der Serie, älteste zuerst. */
    fun photosIn(ctx: Context, s: PhotoSeries): List<SeriesPhoto> {
        val zone = ZoneId.systemDefault()
        val from = s.startDate.atStartOfDay(zone).toInstant().toEpochMilli()
        val to = s.endDate.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
        val types = buildList {
            add(MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE)
            if (s.videos) add(MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO)
        }.joinToString(",")
        val sel = StringBuilder()
            .append("${MediaStore.Files.FileColumns.MEDIA_TYPE} IN ($types)")
            .append(" AND ((${MediaStore.Images.ImageColumns.DATE_TAKEN} BETWEEN ? AND ?)")
            .append(" OR ((${MediaStore.Images.ImageColumns.DATE_TAKEN} IS NULL OR ${MediaStore.Images.ImageColumns.DATE_TAKEN} = 0)")
            .append(" AND ${MediaStore.MediaColumns.DATE_MODIFIED} BETWEEN ? AND ?))")
        val args = mutableListOf(from.toString(), to.toString(), (from / 1000).toString(), (to / 1000).toString())
        if (Build.VERSION.SDK_INT >= 29) sel.append(" AND ${MediaStore.MediaColumns.IS_PENDING} = 0")
        if (s.cameraOnly) {
            sel.append(" AND ${MediaStore.MediaColumns.DATA} LIKE ?")
            args += "%/DCIM/%"
        }
        val cols = arrayOf(
            MediaStore.MediaColumns.DATA,
            MediaStore.Images.ImageColumns.DATE_TAKEN,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.MIME_TYPE,
        )
        val out = ArrayList<SeriesPhoto>()
        runCatching {
            ctx.contentResolver.query(MediaStore.Files.getContentUri("external"), cols, sel.toString(), args.toTypedArray(), null)?.use { c ->
                while (c.moveToNext()) {
                    val path = c.getString(0) ?: continue
                    if (path.contains("/.") || path.contains("/Android/data/")) continue
                    if (!File(path).exists()) continue
                    val taken = c.getLong(1).takeIf { it > 0 } ?: (c.getLong(2) * 1000)
                    out += SeriesPhoto(path, path.substringAfterLast('/'), taken, c.getLong(3) * 1000, c.getString(4) ?: "image/*")
                }
            }
        }
        return out.sortedWith(compareBy({ it.taken }, { it.name }))
    }

    /**
     * Vorschau: Fotos der Serie, die noch nicht bearbeitet wurden, mit neuer Nummer und neuem Namen.
     * [onlyNew] = nur Fotos, die nach dem Anlegen der Serie aufs Handy kamen.
     */
    fun preview(ctx: Context, s: PhotoSeries, onlyNew: Boolean = false): List<SeriesItem> {
        val photos = photosIn(ctx, s)
            .filter { it.path !in s.assigned }
            .filter { !onlyNew || it.added >= s.created - 60_000 }
        return number(s, photos)
    }

    /** Laufende Nummern und Namen vergeben (in Aufnahme-Reihenfolge, ohne Lücken). */
    fun number(s: PhotoSeries, photos: List<SeriesPhoto>): List<SeriesItem> {
        var next = (s.assigned.values.maxOrNull() ?: 0) + 1
        val usedNames = HashSet<String>()
        return photos.map { p ->
            val nr = next++
            val day = (localDate(p.taken).toEpochDay() - s.start + 1).toInt().coerceAtLeast(1)
            val name = if (s.rename) uniqueIn(File(p.path).parentFile, formatName(s, p, nr, day), p.name, usedNames) else p.name
            SeriesItem(p, nr, name, day)
        }
    }

    private fun localDate(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate()

    /** Name nach Muster, Endung bleibt erhalten. */
    fun formatName(s: PhotoSeries, p: SeriesPhoto, nr: Int, day: Int): String {
        val t: LocalDateTime = Instant.ofEpochMilli(p.taken).atZone(ZoneId.systemDefault()).toLocalDateTime()
        val base = s.pattern.ifBlank { PhotoSeries.DEFAULT_PATTERN }
            .replace("{name}", s.name.trim())
            .replace("{datum}", t.format(DateTimeFormatter.ofPattern("yyyy-MM-dd")))
            .replace("{uhrzeit}", t.format(DateTimeFormatter.ofPattern("HH-mm-ss")))
            .replace("{nr}", nr.toString().padStart(3, '0'))
            .replace("{reisetag}", day.toString())
            .replace(Regex("""[\\/:*?"<>|]"""), "_")
            .trim().trimStart('.')
            .ifEmpty { "Foto_$nr" }
        val ext = extensionOf(p.name)
        return if (ext.isEmpty()) base else "$base.$ext"
    }

    /** Beispielname für die Anzeige im Editor */
    fun example(s: PhotoSeries): String {
        val t = s.startDate.atTime(14, 22, 5).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        return formatName(s.copy(name = s.name.ifBlank { "Urlaub" }), SeriesPhoto("", "x.jpg", t, t, "image/jpeg"), 1, 1)
    }

    private fun uniqueIn(dir: File?, wanted: String, current: String, used: MutableSet<String>): String {
        if (wanted == current) return wanted.also { used += it }
        val dot = wanted.lastIndexOf('.')
        val base = if (dot > 0) wanted.substring(0, dot) else wanted
        val ext = if (dot > 0) wanted.substring(dot) else ""
        var name = wanted
        var i = 2
        while (name in used || (dir != null && File(dir, name).exists())) name = "$base ($i)$ext".also { i++ }
        used += name
        return name
    }

    // ---------- Anwenden ----------

    /**
     * Fotos umbenennen und mit Tags versehen. Tags werden direkt im Speicher der App abgelegt.
     * Liefert die Änderungen (für „Rückgängig“).
     */
    fun apply(ctx: Context, seriesId: Long, items: List<SeriesItem>): List<SeriesChange> = synchronized(lock) {
        val s = load(ctx).firstOrNull { it.id == seriesId } ?: return emptyList()
        val store = Store(ctx)
        val tags = store.loadTags().toMutableMap()
        val favorites = store.loadFavorites().toMutableList()
        val changes = ArrayList<SeriesChange>()
        val assigned = s.assigned.toMutableMap()
        val scan = ArrayList<String>()
        for (it in items) {
            val src = File(it.photo.path)
            if (!src.exists()) continue
            var dest = src
            if (s.rename && it.newName != src.name) {
                val target = File(src.parentFile, it.newName)
                WatchState.ignore(target.absolutePath)
                if (!target.exists() && src.renameTo(target)) {
                    dest = target
                    scan += src.absolutePath
                    scan += target.absolutePath
                }
            }
            val oldKey = android.net.Uri.fromFile(src).toString()
            val newKey = android.net.Uri.fromFile(dest).toString()
            val before = tags.remove(oldKey)
            tags[newKey] = normalizeTags(before.orEmpty() + s.tags)
            if (oldKey != newKey) {
                val fi = favorites.indexOf(oldKey)
                if (fi >= 0) favorites[fi] = newKey
            }
            assigned[dest.absolutePath] = it.nr
            changes += SeriesChange(s.id, src.absolutePath, dest.absolutePath, before)
        }
        store.saveTags(tags.filterValues { it.isNotEmpty() })
        store.saveFavorites(favorites)
        save(ctx, load(ctx).map { if (it.id == s.id) it.copy(assigned = assigned) else it })
        if (scan.isNotEmpty()) runCatching { MediaScannerConnection.scanFile(ctx, scan.toTypedArray(), null, null) }
        changes
    }

    /** Änderungen zurücknehmen: alte Namen und alte Tags wiederherstellen. */
    fun undo(ctx: Context, changes: List<SeriesChange>): Int = synchronized(lock) {
        val store = Store(ctx)
        val tags = store.loadTags().toMutableMap()
        val favorites = store.loadFavorites().toMutableList()
        val scan = ArrayList<String>()
        var ok = 0
        val removed = HashMap<Long, MutableSet<String>>()
        for (c in changes) {
            val cur = File(c.newPath)
            var now = cur
            if (c.newPath != c.oldPath) {
                val old = File(c.oldPath)
                if (cur.exists() && !old.exists() && cur.renameTo(old)) {
                    now = old
                    scan += cur.absolutePath
                    scan += old.absolutePath
                }
            }
            val curKey = android.net.Uri.fromFile(cur).toString()
            val nowKey = android.net.Uri.fromFile(now).toString()
            tags.remove(curKey)
            if (!c.tagsBefore.isNullOrEmpty()) tags[nowKey] = c.tagsBefore
            val fi = favorites.indexOf(curKey)
            if (fi >= 0) favorites[fi] = nowKey
            removed.getOrPut(c.seriesId) { HashSet() } += c.newPath
            ok++
        }
        store.saveTags(tags)
        store.saveFavorites(favorites)
        save(ctx, load(ctx).map { s -> removed[s.id]?.let { r -> s.copy(assigned = s.assigned - r) } ?: s })
        if (scan.isNotEmpty()) runCatching { MediaScannerConnection.scanFile(ctx, scan.toTypedArray(), null, null) }
        ok
    }

    /**
     * Neue Fotos aller laufenden Serien mit „automatisch“ bearbeiten.
     * Liefert je Serie die Anzahl bearbeiteter Fotos.
     */
    fun catchUp(ctx: Context): Map<PhotoSeries, Int> {
        if (!Docs.hasAllFilesAccess(ctx)) return emptyMap()
        val today = LocalDate.now()
        val done = LinkedHashMap<PhotoSeries, Int>()
        // auch am Tag nach dem Ende noch nachziehen (z. B. Fotos kurz vor Mitternacht)
        load(ctx).filter { it.autoNew && today.toEpochDay() in it.start..(it.end + 1) }.forEach { s ->
            val items = preview(ctx, s, onlyNew = true)
            if (items.isNotEmpty()) {
                val n = apply(ctx, s.id, items).size
                if (n > 0) done[s] = n
            }
        }
        if (done.isNotEmpty()) SeriesEvents.onChanged?.invoke()
        return done
    }
}
