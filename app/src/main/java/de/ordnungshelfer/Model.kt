package de.ordnungshelfer

import android.net.Uri

/** Worauf eine eigene Regel prüft. */
enum class RuleField(val label: String, val hint: String, val numeric: Boolean = false) {
    NAME_CONTAINS("Name enthält", "Text im Dateinamen"),
    EXTENSION("Endung ist", "Endung, z. B. pdf"),
    PATH_CONTAINS("Pfad enthält", "z. B. WhatsApp oder Telegram"),
    OLDER_THAN("Älter als", "Anzahl Tage, z. B. 90", numeric = true),
    LARGER_THAN("Größer als", "Größe in MB, z. B. 100", numeric = true),
}

/** Eigene Regel, z. B. Name enthält „Rechnung“ → Ordner „Rechnungen“. Regeln haben Vorrang vor dem Dateityp. */
data class Rule(
    val id: Long,
    val field: RuleField,
    val pattern: String,
    val targetFolder: String,
    /** Fester Ablageort (Ordnerpicker). null = innerhalb des jeweils sortierten Ordners. */
    val targetBase: String? = null,
    /** Tags, die Dateien beim Sortieren durch diese Regel automatisch bekommen. */
    val tags: List<String> = emptyList(),
) {
    fun matches(fileName: String): Boolean = matches(Node(Uri.EMPTY, fileName, "", 0L, 0L))

    fun matches(node: Node): Boolean {
        val p = pattern.trim()
        if (p.isEmpty()) return false
        return when (field) {
            RuleField.NAME_CONTAINS -> node.name.contains(p, ignoreCase = true)
            RuleField.EXTENSION -> extensionOf(node.name).equals(p.removePrefix("."), ignoreCase = true)
            RuleField.PATH_CONTAINS -> Uri.decode(node.uri.toString()).contains(p, ignoreCase = true)
            RuleField.OLDER_THAN -> {
                val days = p.toLongOrNull() ?: return false
                node.modified > 0 && System.currentTimeMillis() - node.modified > days * 24 * 60 * 60 * 1000
            }
            RuleField.LARGER_THAN -> {
                val mb = p.replace(',', '.').toDoubleOrNull() ?: return false
                node.size > mb * 1024 * 1024
            }
        }
    }

    /** Lesbare Beschreibung, z. B. „Älter als 90 Tage“. */
    val description: String
        get() = when (this.field) {
            RuleField.OLDER_THAN -> "Älter als $pattern Tage"
            RuleField.LARGER_THAN -> "Größer als $pattern MB"
            else -> "${this.field.label} „$pattern“"
        }

    /** Zielordner, "/" erlaubt Unterordner, z. B. "Finanzen/Rechnungen". */
    val targetSegments: List<String>
        get() = targetFolder.split('/').map { it.trim() }.filter { it.isNotEmpty() }
}

data class Settings(
    val byType: Boolean = true,
    val byDate: Boolean = false,
    val includeSubfolders: Boolean = false,
    /** Gemeinsamer Ablageort für alle sortierten Dateien. null = im jeweils sortierten Ordner. */
    val targetBase: String? = null,
)

/** Eine Datei oder ein Ordner, gelesen über das Storage Access Framework. */
data class Node(
    val uri: Uri,
    val name: String,
    val mime: String,
    val size: Long,
    val modified: Long,
) {
    val isDir: Boolean get() = mime == android.provider.DocumentsContract.Document.MIME_TYPE_DIR
}

/** Ein geplanter Verschiebe-Vorgang für die Vorschau. */
data class PlannedMove(
    val file: Node,
    val parent: Uri,
    val root: Uri,
    val rootName: String,
    val currentPath: List<String>,
    /** Ordner, unter dem [target] angelegt wird (der sortierte Ordner oder ein gewählter Ablageort). */
    val base: Uri,
    val baseName: String,
    val target: List<String>,
    val reason: String,
    val tags: List<String> = emptyList(),
)

/** Merkt sich eine Verschiebung, damit sie rückgängig gemacht werden kann. */
data class UndoEntry(
    val movedUri: String,
    val originalParent: String,
    val currentParent: String,
    val originalName: String,
    val mime: String,
)

data class DupEntry(val node: Node, val path: String, val parent: Uri, val root: Uri)

/** Eine Datei im Papierkorb (Ordner „.Papierkorb“ im jeweiligen Ordner). */
data class TrashEntry(
    val uri: String,
    val originalParent: String,
    val trashDir: String,
    val originalName: String,
    val mime: String,
    val deletedAt: Long,
    val size: Long,
)

fun extensionOf(name: String): String =
    if (name.contains('.')) name.substringAfterLast('.').lowercase() else ""

object Categories {
    private val map: List<Pair<String, Set<String>>> = listOf(
        "Bilder" to setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp", "tif", "tiff", "svg", "raw", "dng"),
        "Videos" to setOf("mp4", "mkv", "mov", "avi", "webm", "3gp", "m4v", "wmv"),
        "Audio" to setOf("mp3", "m4a", "aac", "wav", "ogg", "opus", "flac", "amr", "wma"),
        "PDFs" to setOf("pdf"),
        "Dokumente" to setOf("doc", "docx", "odt", "rtf", "txt", "md", "pages"),
        "Tabellen" to setOf("xls", "xlsx", "ods", "csv", "numbers"),
        "Präsentationen" to setOf("ppt", "pptx", "odp", "key"),
        "Archive" to setOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz"),
        "Apps" to setOf("apk", "apks", "xapk", "aab"),
        "E-Books" to setOf("epub", "mobi", "azw", "azw3"),
        "Kontakte & Termine" to setOf("vcf", "ics"),
    )
    const val OTHER = "Sonstiges"

    val names: Set<String> = map.map { it.first }.toSet() + OTHER

    fun of(fileName: String): String {
        val ext = extensionOf(fileName)
        return map.firstOrNull { ext in it.second }?.first ?: OTHER
    }
}

/** Normalisiert Tags: ohne „#“, ohne Leerzeichen am Rand, keine Dubletten (Groß-/Kleinschreibung egal). */
fun normalizeTags(raw: Collection<String>): List<String> =
    raw.map { it.trim().removePrefix("#").trim() }
        .filter { it.isNotEmpty() }
        .distinctBy { it.lowercase() }

fun parseTags(text: String): List<String> = normalizeTags(text.split(',', ';'))
