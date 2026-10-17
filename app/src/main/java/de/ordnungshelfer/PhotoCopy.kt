package de.ordnungshelfer

import android.content.Context
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Ein Foto, das kopiert werden soll (Quelle kann eine Datei oder eine Auswahl aus der Galerie sein). */
data class CopySource(val uri: Uri, val name: String, val taken: Long, val mime: String)

/** Geplante Kopie: Quelle → neuer Name */
data class CopyPlan(val src: CopySource, val newName: String, val nr: Int)

/** Fotos kopieren, nach Muster benennen und mit Tags versehen. */
object PhotoCopy {

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("ordnungshelfer", Context.MODE_PRIVATE)

    // zuletzt verwendete Einstellungen
    fun lastBase(ctx: Context): String? = prefs(ctx).getString("copyBase", null)
    fun lastPattern(ctx: Context): String = prefs(ctx).getString("copyPattern", null) ?: PhotoSeries.DEFAULT_PATTERN
    fun lastSubfolder(ctx: Context): Boolean = prefs(ctx).getBoolean("copySub", true)
    fun saveLast(ctx: Context, base: String?, pattern: String, sub: Boolean) {
        prefs(ctx).edit().putString("copyBase", base).putString("copyPattern", pattern).putBoolean("copySub", sub).apply()
    }

    /** Name, Aufnahmezeit und Typ einer Quelle lesen. */
    fun read(ctx: Context, uri: Uri): CopySource? = runCatching {
        var name: String? = null
        var takenMs = 0L
        var modifiedMs = 0L
        var mime: String? = null
        if (uri.scheme == "file") {
            val f = File(uri.path ?: return null)
            if (!f.isFile) return null
            name = f.name
            modifiedMs = f.lastModified()
        } else {
            mime = ctx.contentResolver.getType(uri)
            runCatching {
                ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        fun col(n: String) = c.getColumnIndex(n).takeIf { it >= 0 }
                        name = col(OpenableColumns.DISPLAY_NAME)?.let { c.getString(it) }
                        takenMs = col(MediaStore.Images.ImageColumns.DATE_TAKEN)?.let { c.getLong(it) } ?: 0L
                        modifiedMs = (col(MediaStore.MediaColumns.DATE_MODIFIED)?.let { c.getLong(it) } ?: 0L) * 1000
                    }
                }
            }
        }
        val n = name ?: uri.lastPathSegment ?: "Foto"
        val m = mime ?: android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(extensionOf(n)) ?: "application/octet-stream"
        if (takenMs <= 0) takenMs = embeddedDate(ctx, uri, m)
        val t = SeriesEngine.captureTime(n, takenMs, modifiedMs).takeIf { it > 0 } ?: System.currentTimeMillis()
        CopySource(uri, n, t, m)
    }.getOrNull()

    /** Aufnahmedatum aus den Foto- bzw. Videodaten (0 = unbekannt). */
    private fun embeddedDate(ctx: Context, uri: Uri, mime: String): Long {
        if (mime.startsWith("image")) {
            return runCatching {
                ctx.contentResolver.openInputStream(uri)?.use { input ->
                    val ex = ExifInterface(input)
                    val raw = ex.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) ?: ex.getAttribute(ExifInterface.TAG_DATETIME)
                    raw?.let {
                        LocalDateTime.parse(it.trim(), DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss"))
                            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                    }
                }
            }.getOrNull() ?: 0L
        }
        if (mime.startsWith("video")) {
            val r = MediaMetadataRetriever()
            return try {
                r.setDataSource(ctx, uri)
                // z. B. „20261003T141522.000Z“ (UTC)
                val raw = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE) ?: return 0L
                LocalDateTime.parse(raw.take(15), DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss"))
                    .toInstant(ZoneOffset.UTC).toEpochMilli().takeIf { it > 0 && !raw.startsWith("1904") } ?: 0L
            } catch (e: Exception) {
                0L
            } finally {
                runCatching { r.release() }
            }
        }
        return 0L
    }

    /** Nächste freie Nummer im Zielordner (z. B. nach „…_Mallorca_007.jpg“ kommt 8). */
    fun nextNumber(dir: File, name: String): Int {
        if (name.isBlank()) return 1
        val re = Regex("_" + Regex.escape(name.trim()) + """_(\d+)""")
        val max = dir.list()?.mapNotNull { re.find(it)?.groupValues?.get(1)?.toIntOrNull() }?.maxOrNull() ?: 0
        return max + 1
    }

    /** Namen planen: Reihenfolge nach Aufnahmezeit, freie Namen im Zielordner. */
    fun plan(sources: List<CopySource>, name: String, pattern: String, dir: File): List<CopyPlan> {
        if (sources.isEmpty()) return emptyList()
        val sorted = sources.sortedWith(compareBy({ it.taken }, { it.name }))
        val zone = ZoneId.systemDefault()
        val firstDay = java.time.Instant.ofEpochMilli(sorted.first().taken).atZone(zone).toLocalDate().toEpochDay()
        val series = PhotoSeries(id = 0, name = name, start = firstDay, end = firstDay, tags = emptyList(), pattern = pattern, waFolders = emptyList())
        var nr = nextNumber(dir, name)
        val used = HashSet<String>()
        return sorted.map { src ->
            val day = (java.time.Instant.ofEpochMilli(src.taken).atZone(zone).toLocalDate().toEpochDay() - firstDay + 1).toInt()
            val wanted = SeriesEngine.formatName(series, SeriesPhoto("", src.name, src.taken, src.taken, src.mime), nr, day)
            val dot = wanted.lastIndexOf('.')
            val base = if (dot > 0) wanted.substring(0, dot) else wanted
            val ext = if (dot > 0) wanted.substring(dot) else ""
            var candidate = wanted
            var i = 2
            while (candidate in used || File(dir, candidate).exists()) candidate = "$base ($i)$ext".also { i++ }
            used += candidate
            CopyPlan(src, candidate, nr++)
        }
    }

    /** Ergebnis je Foto: Quelle → neue Datei; [moved] = Original wurde verschoben (sonst Kopie). */
    data class Done(val src: Uri, val dest: File, val moved: Boolean)

    /**
     * Kopieren oder verschieben. Verschieben geht nur bei echten Dateien (aus der Ordneransicht);
     * Fotos aus der Galerie-Auswahl werden immer kopiert. Das Aufnahmedatum wird als Dateidatum übernommen.
     */
    fun transfer(ctx: Context, plans: List<CopyPlan>, dir: File, move: Boolean): List<Done> {
        if (!dir.exists()) {
            WatchState.ignore(dir.absolutePath)
            dir.mkdirs()
        }
        val out = ArrayList<Done>()
        val scan = ArrayList<String>()
        for (p in plans) {
            val dest = File(dir, p.newName)
            if (dest.exists()) continue
            WatchState.ignore(dest.absolutePath)
            val srcFile = if (p.src.uri.scheme == "file") p.src.uri.path?.let { File(it) } else null
            if (move && srcFile != null && srcFile.isFile) {
                if (moveFile(srcFile, dest)) {
                    out += Done(p.src.uri, dest, true)
                    scan += srcFile.absolutePath
                    scan += dest.absolutePath
                }
                continue
            }
            val ok = runCatching {
                ctx.contentResolver.openInputStream(p.src.uri)?.use { input ->
                    dest.outputStream().use { input.copyTo(it) }
                } != null
            }.getOrDefault(false)
            if (ok) {
                dest.setLastModified(p.src.taken)
                out += Done(p.src.uri, dest, false)
                scan += dest.absolutePath
            } else {
                dest.delete()
            }
        }
        if (scan.isNotEmpty()) runCatching { MediaScannerConnection.scanFile(ctx, scan.toTypedArray(), null, null) }
        return out
    }

    private fun moveFile(src: File, dest: File): Boolean {
        if (dest.exists()) return false
        dest.parentFile?.mkdirs()
        if (src.renameTo(dest)) return true
        return runCatching {
            src.copyTo(dest)
            if (dest.length() == src.length()) src.delete() else { dest.delete(); false }
        }.getOrDefault(false)
    }

    /** Rückgängig: Kopien löschen, verschobene Fotos an ihren alten Platz zurück. */
    fun undo(ctx: Context, done: List<Done>): Int {
        val scan = ArrayList<String>()
        var ok = 0
        for (d in done) {
            if (d.moved) {
                val back = File(d.src.path ?: continue)
                if (!back.exists() && moveFile(d.dest, back)) { ok++; scan += back.absolutePath }
            } else if (d.dest.delete()) ok++
            scan += d.dest.absolutePath
        }
        runCatching { MediaScannerConnection.scanFile(ctx, scan.toTypedArray(), null, null) }
        done.map { it.dest.parentFile }.distinct().forEach { d -> if (d?.list()?.isEmpty() == true) d.delete() }
        return ok
    }

    fun lastMove(ctx: Context): Boolean = prefs(ctx).getBoolean("copyMove", false)
    fun saveMove(ctx: Context, move: Boolean) { prefs(ctx).edit().putBoolean("copyMove", move).apply() }
}
