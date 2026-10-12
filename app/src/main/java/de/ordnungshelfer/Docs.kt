package de.ordnungshelfer

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.webkit.MimeTypeMap
import androidx.core.content.ContextCompat
import java.io.File

/**
 * Dateizugriff auf zwei Wegen:
 *  - content://… : Ordner, die über die Android-Ordnerauswahl freigegeben wurden (Storage Access Framework)
 *  - file://…    : direkte Pfade wie /storage/emulated/0/Download, nur mit „Zugriff auf alle Dateien“
 */
object Docs {

    fun isFile(uri: Uri): Boolean = uri.scheme == "file"
    private fun f(uri: Uri): File = File(uri.path!!)

    // ---------- Berechtigung „Zugriff auf alle Dateien“ ----------

    fun hasAllFilesAccess(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 30) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }

    /** Häufige Ordner für das schnelle Hinzufügen (nur die, die es auf dem Gerät gibt). */
    fun commonFolders(): List<Pair<String, File>> {
        val base = Environment.getExternalStorageDirectory()
        return listOf(
            "Download" to Environment.DIRECTORY_DOWNLOADS,
            "Documents" to Environment.DIRECTORY_DOCUMENTS,
            "Pictures" to Environment.DIRECTORY_PICTURES,
            "Movies" to Environment.DIRECTORY_MOVIES,
            "Music" to Environment.DIRECTORY_MUSIC,
        ).map { (label, dir) -> label to File(base, dir) }.filter { it.second.isDirectory }
    }

    // ---------- Allgemein ----------

    /** Startpunkt eines gespeicherten Ordners. */
    fun rootDoc(root: Uri): Uri =
        if (isFile(root)) root
        else DocumentsContract.buildDocumentUriUsingTree(root, DocumentsContract.getTreeDocumentId(root))

    fun displayName(ctx: Context, uri: Uri): String? {
        if (isFile(uri)) return f(uri).takeIf { it.exists() }?.name
        return try {
            ctx.contentResolver.query(uri, arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Lesbarer Pfad, z. B. „Interner Speicher/Download“. */
    fun prettyPath(root: Uri): String {
        if (isFile(root)) {
            val p = root.path ?: return root.toString()
            val base = Environment.getExternalStorageDirectory().absolutePath
            return if (p.startsWith(base)) "Interner Speicher" + p.removePrefix(base) else p
        }
        val id = runCatching { DocumentsContract.getTreeDocumentId(root) }.getOrNull() ?: return root.toString()
        val volume = id.substringBefore(':')
        val path = id.substringAfter(':', "")
        val volName = if (volume == "primary") "Interner Speicher" else "SD-Karte ($volume)"
        return if (path.isEmpty()) volName else "$volName/$path"
    }

    private fun mimeOf(name: String): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(extensionOf(name)) ?: "application/octet-stream"

    fun children(ctx: Context, dir: Uri): List<Node> {
        if (isFile(dir)) {
            val files = f(dir).listFiles() ?: return emptyList()
            return files.map { file ->
                Node(
                    uri = Uri.fromFile(file),
                    name = file.name,
                    mime = if (file.isDirectory) Document.MIME_TYPE_DIR else mimeOf(file.name),
                    size = if (file.isFile) file.length() else 0L,
                    modified = file.lastModified(),
                )
            }
        }
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(dir, DocumentsContract.getDocumentId(dir))
        val cols = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
        )
        val out = mutableListOf<Node>()
        try {
            ctx.contentResolver.query(childrenUri, cols, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    val name = c.getString(1) ?: continue
                    out += Node(
                        uri = DocumentsContract.buildDocumentUriUsingTree(dir, id),
                        name = name,
                        mime = c.getString(2) ?: "application/octet-stream",
                        size = if (c.isNull(3)) 0L else c.getLong(3),
                        modified = if (c.isNull(4)) 0L else c.getLong(4),
                    )
                }
            }
        } catch (e: Exception) {
            // Ordner nicht lesbar – überspringen
        }
        return out
    }

    /** Sucht bzw. erstellt Unterordner. Der Cache verhindert, dass derselbe Ordner mehrfach gesucht wird. */
    fun ensureDir(ctx: Context, parent: Uri, segments: List<String>, cache: MutableMap<String, Uri>): Uri? {
        var current = parent
        var key = parent.toString()
        for (seg in segments) {
            key += "/$seg"
            val cached = cache[key]
            if (cached != null) {
                current = cached
                continue
            }
            current = if (isFile(current)) {
                val d = File(f(current), seg)
                if (!d.isDirectory && !d.mkdir()) return null
                Uri.fromFile(d)
            } else {
                val existing = children(ctx, current).firstOrNull { it.isDir && it.name == seg }
                existing?.uri
                    ?: runCatching {
                        DocumentsContract.createDocument(ctx.contentResolver, current, Document.MIME_TYPE_DIR, seg)
                    }.getOrNull()
                    ?: return null
            }
            cache[key] = current
        }
        return current
    }

    fun delete(ctx: Context, uri: Uri): Boolean {
        if (isFile(uri)) {
            val file = f(uri)
            val ok = file.delete()
            if (ok) scan(ctx, file.absolutePath)
            return ok
        }
        return runCatching { DocumentsContract.deleteDocument(ctx.contentResolver, uri) }.getOrDefault(false)
    }

    /** Galerie & Co. über geänderte Pfade informieren, damit keine „Geister-Bilder“ bleiben. */
    private fun scan(ctx: Context, vararg paths: String) {
        runCatching { MediaScannerConnection.scanFile(ctx, paths, null, null) }
    }

    /**
     * Verschiebt eine Datei von [fromParent] nach [toParent] unter dem Namen [desiredName].
     * Gibt es dort schon eine Datei mit dem Namen, wird „Name (2).ext“ verwendet.
     * Liefert die neue URI oder null bei Fehler. Es wird nie etwas überschrieben.
     */
    fun move(ctx: Context, file: Uri, currentName: String, mime: String, fromParent: Uri, toParent: Uri, desiredName: String): Uri? {
        if (isFile(file) && isFile(toParent)) return moveFile(ctx, f(file), f(toParent), desiredName)

        val existingNames = children(ctx, toParent).map { it.name }.toSet()
        val finalName = uniqueName(desiredName, existingNames)

        // Aus einem freigegebenen Ordner (content://) in einen direkten Pfad (file://): kopieren, dann löschen
        if (isFile(toParent)) {
            val dest = File(f(toParent), finalName)
            val copied = runCatching {
                ctx.contentResolver.openInputStream(file)!!.use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
            }.isSuccess
            if (!copied || !delete(ctx, file)) {
                dest.delete()
                return null
            }
            scan(ctx, dest.absolutePath)
            return Uri.fromFile(dest)
        }

        // 1. Schneller, direkter Weg (gleicher Speicher, Name bleibt)
        if (finalName == currentName) {
            val moved = runCatching {
                DocumentsContract.moveDocument(ctx.contentResolver, file, fromParent, toParent)
            }.getOrNull()
            if (moved != null) return moved
        }

        // 2. Rückfallweg: kopieren, dann Original löschen
        val dest = runCatching {
            DocumentsContract.createDocument(ctx.contentResolver, toParent, mime, finalName)
        }.getOrNull() ?: return null
        val copied = runCatching {
            ctx.contentResolver.openInputStream(file)!!.use { input ->
                ctx.contentResolver.openOutputStream(dest)!!.use { output -> input.copyTo(output) }
            }
        }.isSuccess
        if (!copied || !delete(ctx, file)) {
            delete(ctx, dest)
            return null
        }
        return dest
    }

    private fun moveFile(ctx: Context, src: File, destDir: File, desiredName: String): Uri? {
        if (!src.exists() || !destDir.isDirectory) return null
        val existing = destDir.list()?.toSet() ?: emptySet()
        val dest = File(destDir, uniqueName(desiredName, existing))
        val ok = src.renameTo(dest) || try {
            src.copyTo(dest)
            if (src.delete()) true else { dest.delete(); false }
        } catch (e: Exception) {
            dest.delete()
            false
        }
        if (!ok) return null
        scan(ctx, src.absolutePath, dest.absolutePath)
        return Uri.fromFile(dest)
    }

    /** Infos zu einer einzelnen Datei, oder null, wenn es sie nicht (mehr) gibt. */
    fun nodeOf(ctx: Context, uri: Uri): Node? {
        if (isFile(uri)) {
            val file = f(uri)
            if (!file.isFile) return null
            return Node(uri, file.name, mimeOf(file.name), file.length(), file.lastModified())
        }
        return try {
            ctx.contentResolver.query(
                uri,
                arrayOf(Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED),
                null, null, null,
            )?.use { c ->
                if (!c.moveToFirst()) return null
                Node(
                    uri = uri,
                    name = c.getString(0) ?: return null,
                    mime = c.getString(1) ?: "application/octet-stream",
                    size = if (c.isNull(2)) 0L else c.getLong(2),
                    modified = if (c.isNull(3)) 0L else c.getLong(3),
                )
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Ordnerpfad einer Datei zur Anzeige, z. B. „Interner Speicher/Download/PDFs“. */
    fun prettyParent(uri: Uri): String {
        if (isFile(uri)) return prettyPath(Uri.fromFile(f(uri).parentFile ?: f(uri)))
        val id = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull() ?: return ""
        val volume = id.substringBefore(':')
        val path = id.substringAfter(':', "").substringBeforeLast('/', "")
        val volName = if (volume == "primary") "Interner Speicher" else "SD-Karte"
        return if (path.isEmpty()) volName else "$volName/$path"
    }

    /** Unterordner eines direkten Pfads für den Ordnerpicker (ohne versteckte Ordner), alphabetisch. */
    fun subfolders(dir: File): List<File> =
        (dir.listFiles() ?: emptyArray())
            .filter { it.isDirectory && !it.name.startsWith(".") }
            .sortedBy { it.name.lowercase() }

    /** Speicherorte (interner Speicher und ggf. SD-Karte) als Startpunkte für den Ordnerpicker. */
    fun storageRoots(ctx: Context): List<Pair<String, File>> {
        val internal = Environment.getExternalStorageDirectory()
        val out = mutableListOf("Interner Speicher" to internal)
        ctx.getExternalFilesDirs(null).filterNotNull().forEach { appDir ->
            // /storage/XXXX-XXXX/Android/data/<paket>/files → /storage/XXXX-XXXX
            val root = File(appDir.absolutePath.substringBefore("/Android/"))
            if (root.absolutePath != internal.absolutePath && root.canRead()) out += "SD-Karte" to root
        }
        return out
    }

    /** Ein Ordner-URI (file:// oder Ordnerfreigabe) als lesbarer Name, z. B. „Interner Speicher/Archiv“. */
    fun prettyFolder(stored: String): String = prettyPath(Uri.parse(stored))

    private fun uniqueName(name: String, existing: Set<String>): String {
        if (name !in existing) return name
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 2
        while ("$base ($i)$ext" in existing) i++
        return "$base ($i)$ext"
    }
}
