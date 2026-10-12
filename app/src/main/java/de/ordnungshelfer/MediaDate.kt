package de.ordnungshelfer

import android.content.Context
import android.media.MediaMetadataRetriever
import androidx.exifinterface.media.ExifInterface
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Ermittelt das „echte“ Datum einer Datei, in dieser Reihenfolge:
 *  1. Aufnahmedatum aus den Foto- (EXIF) bzw. Videodaten
 *  2. Datum im Dateinamen, z. B. IMG_20261003_141522.jpg, Screenshot_2026-10-03…, IMG-20261003-WA0001.jpg
 *  3. Änderungsdatum der Datei
 */
object MediaDate {

    enum class Source(val label: String) {
        CAPTURE("Aufnahmedatum"),
        NAME("Datum aus Name"),
        MODIFIED("Änderungsdatum"),
    }

    data class Result(val year: Int, val month: Int, val source: Source)

    private val datePattern = Regex("""(?<!\d)((?:19|20)\d{2})[-_.:]?(\d{2})[-_.:]?(\d{2})(?!\d)""")

    fun of(ctx: Context, node: Node): Result? {
        when (Categories.of(node.name)) {
            "Bilder" -> exifDate(ctx, node)?.let { return it }
            "Videos" -> videoDate(ctx, node)?.let { return it }
        }
        find(node.name)?.let { (y, m) -> return Result(y, m, Source.NAME) }
        if (node.modified <= 0) return null
        val d = Instant.ofEpochMilli(node.modified).atZone(ZoneId.systemDefault())
        return Result(d.year, d.monthValue, Source.MODIFIED)
    }

    /** Sucht das erste gültige Datum in einem Text. */
    private fun find(text: String?): Pair<Int, Int>? {
        if (text == null) return null
        val maxYear = LocalDate.now().year + 1
        for (m in datePattern.findAll(text)) {
            val y = m.groupValues[1].toInt()
            val mo = m.groupValues[2].toInt()
            val d = m.groupValues[3].toInt()
            if (y in 1990..maxYear && mo in 1..12 && d in 1..31) return y to mo
        }
        return null
    }

    private fun exifDate(ctx: Context, node: Node): Result? = try {
        ctx.contentResolver.openInputStream(node.uri)?.use { input ->
            val exif = ExifInterface(input)
            val raw = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                ?: exif.getAttribute(ExifInterface.TAG_DATETIME_DIGITIZED)
            find(raw)?.let { (y, m) -> Result(y, m, Source.CAPTURE) }
        }
    } catch (e: Exception) {
        null
    }

    private fun videoDate(ctx: Context, node: Node): Result? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(ctx, node.uri)
            // Format z. B. „20261003T141522.000Z“
            find(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE))
                ?.let { (y, m) -> Result(y, m, Source.CAPTURE) }
        } catch (e: Exception) {
            null
        } finally {
            runCatching { r.release() }
        }
    }
}
