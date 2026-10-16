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
    /** Quellen: Kamera (DCIM), WhatsApp, alles andere (Screenshots, Downloads …) */
    val fromCamera: Boolean = true,
    val fromWhatsApp: Boolean = true,
    val fromOther: Boolean = false,
    /** WhatsApp-Ordner (absolute Pfade, mit Unterordnern). Leer = automatisch am Namen erkennen */
    val waFolders: List<String> = SeriesEngine.defaultWhatsAppFolders(),
    /** Ordner für „Andere“ (mit Unterordnern). Leer = alle übrigen Ordner */
    val otherFolders: List<String> = emptyList(),
    /** angelegt (ms) – automatisch werden nur Fotos bearbeitet, die danach hinzukamen */
    val created: Long = System.currentTimeMillis(),
    /** bereits bearbeitete Dateien: Pfad → laufende Nummer */
    val assigned: Map<String, Int> = emptyMap(),
    /** Fotos in einen eigenen Unterordner mit dem Namen der Serie verschieben */
    val moveToFolder: Boolean = true,
    /** Hauptordner (absoluter Pfad), in dem der Unterordner angelegt wird. null = DCIM */
    val baseFolder: String? = null,
) {
    /** Name des Unterordners (Name der Serie ohne unerlaubte Zeichen) */
    val folderName: String
        get() = name.trim().replace(Regex("""[\\/:*?"<>|]"""), "_").trim('.', ' ').ifEmpty { "Fotoserie" }

    /** Hauptordner – ohne Auswahl der Kamera-Ordner DCIM */
    val baseDir: File
        get() = baseFolder?.let { File(it) }
            ?: android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DCIM)

    /** Zielordner der Fotos, null = Fotos bleiben, wo sie sind */
    val targetDir: File? get() = if (moveToFolder) File(baseDir, folderName) else null

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
        const val DEFAULT_PATTERN = "{JJMMTT}_{HHMM}_{name}_{nr}"
        /** früheres Standardmuster – gespeicherte Serien damit bekommen das neue */
        const val OLD_DEFAULT = "{name}_{datum}_{nr}"

        /** Platzhalter für das Namensmuster: Text → Erklärung */
        val PLACEHOLDERS = listOf(
            "{name}" to "Name der Serie",
            "{JJMMTT}" to "Aufnahmedatum kurz (261012)",
            "{HHMM}" to "Uhrzeit kurz (1422)",
            "{datum}" to "Aufnahmedatum lang (2026-10-12)",
            "{uhrzeit}" to "Uhrzeit lang (14-22-05)",
            "{nr}" to "Laufende Nummer (001)",
            "{reisetag}" to "Tag der Reise (1, 2, 3 …)",
        )
    }
}

/** Ein Foto aus der Mediendatenbank */
data class SeriesPhoto(val path: String, val name: String, val taken: Long, val added: Long, val mime: String)

/** Vorschau-Zeile: Foto → neuer Name und Nummer */
data class SeriesItem(val photo: SeriesPhoto, val nr: Int, val newName: String, val day: Int, val dir: File) {
    val newPath: String get() = File(dir, newName).absolutePath
}

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
                    pattern = o.optString("pattern", PhotoSeries.DEFAULT_PATTERN).let { if (it == PhotoSeries.OLD_DEFAULT) PhotoSeries.DEFAULT_PATTERN else it },
                    autoNew = o.optBoolean("autoNew", true),
                    videos = o.optBoolean("videos", true),
                    fromCamera = o.optBoolean("fromCamera", true),
                    fromWhatsApp = o.optBoolean("fromWhatsApp", true),
                    // ältere Serien: „nur Kamera“ aus = alle Quellen
                    fromOther = o.optBoolean("fromOther", !o.optBoolean("cameraOnly", true)),
                    waFolders = o.optJSONArray("waFolders")?.let { f -> List(f.length()) { f.getString(it) } }
                        ?: SeriesEngine.defaultWhatsAppFolders(),
                    otherFolders = o.optJSONArray("otherFolders")?.let { f -> List(f.length()) { f.getString(it) } } ?: emptyList(),
                    created = o.optLong("created", 0L),
                    assigned = asg?.keys()?.asSequence()?.associateWith { asg.getInt(it) } ?: emptyMap(),
                    moveToFolder = o.optBoolean("move", true),
                    baseFolder = o.optString("base", "").ifEmpty { null },
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
                    .put("autoNew", s.autoNew).put("videos", s.videos)
                    .put("fromCamera", s.fromCamera).put("fromWhatsApp", s.fromWhatsApp).put("fromOther", s.fromOther)
                    .put("waFolders", JSONArray(s.waFolders)).put("otherFolders", JSONArray(s.otherFolders))
                    .put("created", s.created)
                    .put("assigned", asg)
                    .put("move", s.moveToFolder)
                    .put("base", s.baseFolder ?: "")
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
        val taken = MediaStore.Images.ImageColumns.DATE_TAKEN
        val modified = MediaStore.MediaColumns.DATE_MODIFIED
        // Grob vorfiltern (etwas großzügiger wegen Zeitzonen); WhatsApp-Dateien zusätzlich über das Datum im Namen
        val pad = 86_400_000L
        val sel = StringBuilder()
            .append("${MediaStore.Files.FileColumns.MEDIA_TYPE} IN ($types)")
            .append(" AND (($taken BETWEEN ? AND ?)")
            .append(" OR (($taken IS NULL OR $taken = 0) AND $modified BETWEEN ? AND ?)")
            .append(" OR ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?)")
        val args = mutableListOf(
            (from - pad).toString(), (to + pad).toString(),
            ((from - pad) / 1000).toString(), ((to + pad) / 1000).toString(),
            "%-WA%",
        )
        if (Build.VERSION.SDK_INT >= 29) sel.append(" AND ${MediaStore.MediaColumns.IS_PENDING} = 0")
        val cols = arrayOf(
            MediaStore.MediaColumns.DATA,
            taken,
            modified,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.MIME_TYPE,
        )
        val out = ArrayList<SeriesPhoto>()
        runCatching {
            ctx.contentResolver.query(MediaStore.Files.getContentUri("external"), cols, sel.toString(), args.toTypedArray(), null)?.use { c ->
                while (c.moveToNext()) {
                    val path = c.getString(0) ?: continue
                    if (path.contains("/.") || path.contains("/Android/data/")) continue
                    val name = path.substringAfterLast('/')
                    if (!matchesSource(s, path, name)) continue
                    val t = captureTime(name, c.getLong(1), c.getLong(2) * 1000)
                    if (t !in from..to) continue
                    if (!File(path).exists()) continue
                    out += SeriesPhoto(path, name, t, c.getLong(3) * 1000, c.getString(4) ?: "image/*")
                }
            }
        }
        return out.sortedWith(compareBy({ it.taken }, { it.name }))
    }

    enum class Source { CAMERA, WHATSAPP, OTHER }

    /** WhatsApp: IMG-20261015-WA0004.jpg, VID-20260719-WA0043.mp4 */
    private val waName = Regex("""^(?:IMG|VID|PTT|AUD|STK|DOC)-(\d{4})(\d{2})(\d{2})-WA\d+""", RegexOption.IGNORE_CASE)
    /** Kamera & Co.: IMG_20261015_142233.jpg, 20261015_142233.jpg, Screenshot_20261015-142233… */
    private val stampName = Regex("""(?<!\d)((?:19|20)\d{2})(\d{2})(\d{2})[_-](\d{2})(\d{2})(\d{2})(?!\d)""")

    private fun under(path: String, folder: String) = path.startsWith(folder.trimEnd('/') + "/")

    /** Gehört die Datei zu einer der gewählten Quellen der Serie? */
    fun matchesSource(s: PhotoSeries, path: String, name: String): Boolean {
        val auto = sourceOf(path, name)
        val inWa = if (s.waFolders.isEmpty()) auto == Source.WHATSAPP else s.waFolders.any { under(path, it) }
        val isCam = path.contains("/DCIM/") && !inWa
        val inOther = if (s.otherFolders.isEmpty()) !isCam && !inWa && auto != Source.WHATSAPP else s.otherFolders.any { under(path, it) }
        return (s.fromCamera && isCam) || (s.fromWhatsApp && inWa) || (s.fromOther && inOther)
    }

    /** Übliche WhatsApp-Ordner, die es auf dem Handy gibt (sonst die Standardordner neuerer Android-Versionen). */
    fun defaultWhatsAppFolders(): List<String> {
        val root = android.os.Environment.getExternalStorageDirectory().absolutePath
        val candidates = listOf(
            "$root/Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images",
            "$root/Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Video",
            "$root/WhatsApp/Media/WhatsApp Images",
            "$root/WhatsApp/Media/WhatsApp Video",
            "$root/Android/media/com.whatsapp.w4b/WhatsApp Business/Media/WhatsApp Business Images",
            "$root/Android/media/com.whatsapp.w4b/WhatsApp Business/Media/WhatsApp Business Video",
        )
        return candidates.filter { File(it).isDirectory }.ifEmpty { candidates.take(2) }
    }

    fun sourceOf(path: String, name: String): Source = when {
        waName.containsMatchIn(name) || path.contains("/WhatsApp/", ignoreCase = true) -> Source.WHATSAPP
        path.contains("/DCIM/") -> Source.CAMERA
        else -> Source.OTHER
    }

    /**
     * Aufnahmezeit eines Fotos:
     *  - WhatsApp: Datum aus dem Namen (WhatsApp entfernt die Kameradaten). Uhrzeit vom Speichern,
     *    wenn sie zum selben Tag passt, sonst 00:00.
     *  - sonst Aufnahmedatum aus den Fotodaten, dann Datum+Uhrzeit aus dem Namen, zuletzt Änderungsdatum.
     */
    fun captureTime(name: String, takenMs: Long, modifiedMs: Long): Long {
        val zone = ZoneId.systemDefault()
        waName.find(name)?.let { m ->
            val d = runCatching { LocalDate.of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt()) }.getOrNull()
            if (d != null) {
                val ref = (if (takenMs > 0) takenMs else modifiedMs).takeIf { it > 0 }
                    ?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDateTime() }
                val time = if (ref != null && ref.toLocalDate() == d) ref.toLocalTime() else java.time.LocalTime.MIDNIGHT
                return d.atTime(time).atZone(zone).toInstant().toEpochMilli()
            }
        }
        if (takenMs > 0) return takenMs
        stampName.find(name)?.let { m ->
            val g = m.groupValues
            runCatching {
                return LocalDateTime.of(g[1].toInt(), g[2].toInt(), g[3].toInt(), g[4].toInt(), g[5].toInt(), g[6].toInt())
                    .atZone(zone).toInstant().toEpochMilli()
            }
        }
        return modifiedMs
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
            val src = File(p.path)
            val dir = s.targetDir ?: src.parentFile ?: File("/")
            val wanted = if (s.rename) formatName(s, p, nr, day) else p.name
            val name = uniqueIn(dir, wanted, src, usedNames)
            SeriesItem(p, nr, name, day, dir)
        }
    }

    private fun localDate(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate()

    /** Name nach Muster, Endung bleibt erhalten. */
    fun formatName(s: PhotoSeries, p: SeriesPhoto, nr: Int, day: Int): String {
        val t: LocalDateTime = Instant.ofEpochMilli(p.taken).atZone(ZoneId.systemDefault()).toLocalDateTime()
        val base = s.pattern.ifBlank { PhotoSeries.DEFAULT_PATTERN }
            .replace("{name}", s.name.trim())
            .replace("{JJMMTT}", t.format(DateTimeFormatter.ofPattern("yyMMdd")), ignoreCase = true)
            .replace("{HHMM}", t.format(DateTimeFormatter.ofPattern("HHmm")), ignoreCase = true)
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

    /** Anzeige des Zielordners, z. B. „DCIM/Mallorca 2026“ */
    fun targetLabel(s: PhotoSeries): String {
        val d = s.copy(name = s.name.ifBlank { "Urlaub" }).targetDir ?: return "Fotos bleiben in ihrem Ordner"
        return Docs.prettyPath(android.net.Uri.fromFile(d)).removePrefix("Interner Speicher/")
    }

    /** Freier Name im Zielordner (auch gegenüber den anderen Fotos dieser Vorschau). */
    private fun uniqueIn(dir: File, wanted: String, src: File, used: MutableSet<String>): String {
        val dot = wanted.lastIndexOf('.')
        val base = if (dot > 0) wanted.substring(0, dot) else wanted
        val ext = if (dot > 0) wanted.substring(dot) else ""
        var name = wanted
        var i = 2
        while (true) {
            val f = File(dir, name)
            val taken = f.absolutePath in used || (f.exists() && f.absolutePath != src.absolutePath)
            if (!taken) break
            name = "$base ($i)$ext"
            i++
        }
        used += File(dir, name).absolutePath
        return name
    }

    /** Verschieben/Umbenennen – auch auf eine Speicherkarte (dann kopieren und Original löschen). */
    private fun moveFile(src: File, dest: File): Boolean {
        if (dest.exists()) return false
        dest.parentFile?.mkdirs()
        if (src.renameTo(dest)) return true
        return runCatching {
            src.copyTo(dest)
            if (dest.length() == src.length()) src.delete() else { dest.delete(); false }
        }.getOrDefault(false).also { ok -> if (!ok && dest.exists() && src.exists()) dest.delete() }
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
            val target = File(it.newPath)
            if (target.absolutePath != src.absolutePath) {
                target.parentFile?.let { d -> if (!d.exists()) { WatchState.ignore(d.absolutePath); d.mkdirs() } }
                WatchState.ignore(target.absolutePath)
                if (moveFile(src, target)) {
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
                if (cur.exists() && !old.exists() && moveFile(cur, old)) {
                    now = old
                    scan += cur.absolutePath
                    scan += old.absolutePath
                    // leer gewordenen Serien-Ordner wieder entfernen
                    cur.parentFile?.let { d -> if (d.absolutePath != old.parentFile?.absolutePath && d.list()?.isEmpty() == true) d.delete() }
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
