package de.ordnungshelfer

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import java.io.File

/** Schneller Überblick über alle Dateien des Handys über die Android-Mediendatenbank (für die Home-Seite). */
object Library {

    fun load(ctx: Context): List<DupEntry> {
        val out = ArrayList<DupEntry>()
        val cols = arrayOf(
            MediaStore.Files.FileColumns.DATA,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.DATE_MODIFIED,
            MediaStore.Files.FileColumns.MIME_TYPE,
        )
        val parentCache = HashMap<String, Pair<String, Uri>>()
        try {
            ctx.contentResolver.query(
                MediaStore.Files.getContentUri("external"), cols,
                "${MediaStore.Files.FileColumns.SIZE} > 0", null, null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val path = c.getString(0) ?: continue
                    // Versteckte Ordner, App-Daten und Papierkorb auslassen
                    if (path.contains("/.") || path.contains("/Android/data/") || path.contains("/Android/obb/")) continue
                    val name = path.substringAfterLast('/')
                    val parentPath = path.substringBeforeLast('/')
                    val (pretty, parentUri) = parentCache.getOrPut(parentPath) {
                        val u = Uri.fromFile(File(parentPath))
                        Docs.prettyPath(u) to u
                    }
                    val node = Node(
                        uri = Uri.fromFile(File(path)),
                        name = name,
                        mime = c.getString(3) ?: "application/octet-stream",
                        size = c.getLong(1),
                        modified = c.getLong(2) * 1000,
                    )
                    out += DupEntry(node, pretty, parentUri, parentUri)
                }
            }
        } catch (e: Exception) {
            // ohne Berechtigung liefert die Datenbank nichts
        }
        return out
    }

    /** Belegter und gesamter Speicher des internen Speichers in Byte. */
    fun storage(): Pair<Long, Long> = try {
        val s = StatFs(Environment.getExternalStorageDirectory().absolutePath)
        (s.totalBytes - s.availableBytes) to s.totalBytes
    } catch (e: Exception) {
        0L to 0L
    }
}
