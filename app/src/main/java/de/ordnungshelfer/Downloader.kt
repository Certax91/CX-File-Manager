package de.ordnungshelfer

import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Herunterladen mit eigenem Dateinamen und Speichern geteilter Dateien. */
object Downloader {

    private val urlPattern = Regex("""https?://[^\s"'<>]+""")

    /** Erster Link in einem geteilten Text, z. B. aus „Link teilen“ im Browser. */
    fun findUrl(text: String?): String? = text?.let { urlPattern.find(it)?.value?.trimEnd('.', ',', ')', ']') }

    data class Probe(val name: String, val mime: String?)

    /** Fragt beim Server nach dem echten Dateinamen (Content-Disposition) und Dateityp. */
    fun probe(url: String): Probe {
        var disposition: String? = null
        var mime: String? = null
        try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.requestMethod = "HEAD"
            c.instanceFollowRedirects = true
            c.connectTimeout = 8000
            c.readTimeout = 8000
            c.connect()
            disposition = c.getHeaderField("Content-Disposition")
            mime = c.contentType?.substringBefore(';')?.trim()
            c.disconnect()
        } catch (e: Exception) {
            // ohne Netz oder HEAD nicht erlaubt → Name aus dem Link raten
        }
        val name = URLUtil.guessFileName(url, disposition, mime)
        return Probe(name, mime?.takeIf { it != "application/octet-stream" })
    }

    /**
     * Startet einen Download über den Android-Download-Manager (läuft auch weiter, wenn die App zu ist).
     * Gespeichert wird in Download/[subfolder]/[name].
     */
    fun enqueue(ctx: Context, url: String, name: String, subfolder: String, mime: String?): Long {
        val sub = subfolder.split('/').map { it.trim() }.filter { it.isNotEmpty() }.joinToString("/")
        val req = DownloadManager.Request(Uri.parse(url))
            .setTitle(name)
            .setDescription("CX File Manager")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, if (sub.isEmpty()) name else "$sub/$name")
        mime?.let { req.setMimeType(it) }
        val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        return dm.enqueue(req)
    }

    /** Ergebnis eines Downloads: Datei-URI, null = läuft noch, "" = fehlgeschlagen. */
    fun result(ctx: Context, id: Long): String? {
        val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        dm.query(DownloadManager.Query().setFilterById(id))?.use { c ->
            if (!c.moveToFirst()) return ""
            return when (c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    val local = c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI)) ?: return ""
                    val u = Uri.parse(local)
                    if (u.scheme == "file") Uri.fromFile(File(u.path!!)).toString() else local
                }
                DownloadManager.STATUS_FAILED -> ""
                else -> null
            }
        }
        return ""
    }

    fun downloadDir(): File = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)

    /**
     * Speichert eine geteilte Datei (E-Mail-Anhang, Datei aus einer anderen App) unter neuem Namen.
     * [dest] = gewählter Ordner, oder null für den Download-Ordner.
     */
    fun saveStream(ctx: Context, source: Uri, name: String, dest: String?, allFilesAccess: Boolean): Uri? {
        val mime = ctx.contentResolver.getType(source)
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(extensionOf(name))
            ?: "application/octet-stream"
        if (dest != null) return Docs.copy(ctx, source, name, mime, Docs.rootDoc(Uri.parse(dest)))
        if (allFilesAccess || Build.VERSION.SDK_INT < 29) {
            val dir = downloadDir().apply { mkdirs() }
            return Docs.copy(ctx, source, name, mime, Uri.fromFile(dir))
        }
        // Ohne „Zugriff auf alle Dateien“: über die Android-Mediendatenbank in „Download“ schreiben
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = ctx.contentResolver
        val target = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        val ok = runCatching {
            resolver.openInputStream(source)!!.use { input ->
                resolver.openOutputStream(target)!!.use { output -> input.copyTo(output) }
            }
        }.isSuccess
        if (!ok) {
            resolver.delete(target, null, null)
            return null
        }
        resolver.update(target, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        return target
    }
}

/** Vom Nutzer eingegebenen Namen bereinigen; fehlt die Endung, wird die ursprüngliche angehängt. */
fun fixFileName(wanted: String, original: String): String {
    var name = wanted.trim().replace('/', '_').replace('\\', '_').trimStart('.')
    val oldExt = extensionOf(original)
    if (oldExt.isNotEmpty() && extensionOf(name).isEmpty()) name = "$name.$oldExt"
    return name
}
