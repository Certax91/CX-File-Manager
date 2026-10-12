package de.ordnungshelfer

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Speichert Ordner, Regeln, Einstellungen und das Rückgängig-Protokoll lokal auf dem Gerät. */
class Store(context: Context) {
    private val prefs = context.getSharedPreferences("ordnungshelfer", Context.MODE_PRIVATE)

    fun loadRoots(): List<String> = readArray("roots") { a, i -> a.getString(i) }

    fun saveRoots(roots: List<String>) {
        prefs.edit().putString("roots", JSONArray(roots).toString()).apply()
    }

    fun loadRules(): List<Rule> = readArray("rules") { a, i ->
        val o = a.getJSONObject(i)
        Rule(
            id = o.getLong("id"),
            field = runCatching { RuleField.valueOf(o.getString("field")) }.getOrDefault(RuleField.NAME_CONTAINS),
            pattern = o.getString("pattern"),
            targetFolder = o.getString("target"),
            targetBase = o.optString("base", "").ifEmpty { null },
            tags = o.optJSONArray("tags")?.let { t -> List(t.length()) { t.getString(it) } } ?: emptyList(),
        )
    }

    fun saveRules(rules: List<Rule>) {
        val a = JSONArray()
        rules.forEach {
            a.put(
                JSONObject()
                    .put("id", it.id)
                    .put("field", it.field.name)
                    .put("pattern", it.pattern)
                    .put("target", it.targetFolder)
                    .put("base", it.targetBase ?: "")
                    .put("tags", JSONArray(it.tags))
            )
        }
        prefs.edit().putString("rules", a.toString()).apply()
    }

    fun loadSettings() = Settings(
        byType = prefs.getBoolean("byType", true),
        byDate = prefs.getBoolean("byDate", false),
        includeSubfolders = prefs.getBoolean("includeSubfolders", false),
        targetBase = prefs.getString("targetBase", null),
    )

    fun saveSettings(s: Settings) {
        prefs.edit()
            .putBoolean("byType", s.byType)
            .putBoolean("byDate", s.byDate)
            .putBoolean("includeSubfolders", s.includeSubfolders)
            .putString("targetBase", s.targetBase)
            .apply()
    }

    fun loadUndo(): List<UndoEntry> = readArray("undo") { a, i ->
        val o = a.getJSONObject(i)
        UndoEntry(
            movedUri = o.getString("moved"),
            originalParent = o.getString("from"),
            currentParent = o.getString("to"),
            originalName = o.getString("name"),
            mime = o.optString("mime", "application/octet-stream"),
        )
    }

    fun saveUndo(entries: List<UndoEntry>) {
        val a = JSONArray()
        entries.forEach {
            a.put(
                JSONObject()
                    .put("moved", it.movedUri)
                    .put("from", it.originalParent)
                    .put("to", it.currentParent)
                    .put("name", it.originalName)
                    .put("mime", it.mime)
            )
        }
        prefs.edit().putString("undo", a.toString()).apply()
    }

    fun loadTrash(): List<TrashEntry> = readArray("trash") { a, i ->
        val o = a.getJSONObject(i)
        TrashEntry(
            uri = o.getString("uri"),
            originalParent = o.getString("from"),
            trashDir = o.getString("dir"),
            originalName = o.getString("name"),
            mime = o.optString("mime", "application/octet-stream"),
            deletedAt = o.getLong("at"),
            size = o.optLong("size", 0L),
        )
    }

    fun saveTrash(entries: List<TrashEntry>) {
        val a = JSONArray()
        entries.forEach {
            a.put(
                JSONObject()
                    .put("uri", it.uri)
                    .put("from", it.originalParent)
                    .put("dir", it.trashDir)
                    .put("name", it.originalName)
                    .put("mime", it.mime)
                    .put("at", it.deletedAt)
                    .put("size", it.size)
            )
        }
        prefs.edit().putString("trash", a.toString()).apply()
    }

    /** Tags je Datei: Datei-URI → Tags. */
    fun loadTags(): Map<String, List<String>> {
        val raw = prefs.getString("tags", null) ?: return emptyMap()
        return runCatching {
            val o = JSONObject(raw)
            o.keys().asSequence().associateWith { k ->
                val a = o.getJSONArray(k)
                List(a.length()) { a.getString(it) }
            }
        }.getOrDefault(emptyMap())
    }

    fun saveTags(tags: Map<String, List<String>>) {
        val o = JSONObject()
        tags.forEach { (k, v) -> if (v.isNotEmpty()) o.put(k, JSONArray(v)) }
        prefs.edit().putString("tags", o.toString()).apply()
    }

    /** Laufende Downloads, deren Datei nach Abschluss Tags bekommen soll: Download-ID → Tags. */
    fun loadPendingDownloads(): Map<Long, List<String>> {
        val raw = prefs.getString("pendingDl", null) ?: return emptyMap()
        return runCatching {
            val o = JSONObject(raw)
            o.keys().asSequence().associate { k ->
                val a = o.getJSONArray(k)
                k.toLong() to List(a.length()) { a.getString(it) }
            }
        }.getOrDefault(emptyMap())
    }

    fun savePendingDownloads(p: Map<Long, List<String>>) {
        val o = JSONObject()
        p.forEach { (k, v) -> o.put(k.toString(), JSONArray(v)) }
        prefs.edit().putString("pendingDl", o.toString()).apply()
    }

    /** Ordner, die die Suche durchsucht. Leer = Standard (Ordner zum Aufräumen + Ablageorte). */
    fun loadSearchFolders(): List<String> = readArray("searchFolders") { a, i -> a.getString(i) }

    fun saveSearchFolders(f: List<String>) {
        prefs.edit().putString("searchFolders", JSONArray(f).toString()).apply()
    }

    fun loadFavorites(): List<String> = readArray("favorites") { a, i -> a.getString(i) }

    fun saveFavorites(f: Collection<String>) {
        prefs.edit().putString("favorites", JSONArray(f.toList()).toString()).apply()
    }

    fun loadScanFolder(): String? = prefs.getString("scanFolder", null)

    fun saveScanFolder(f: String?) {
        prefs.edit().putString("scanFolder", f).apply()
    }

    fun loadGridView(): Boolean = prefs.getBoolean("gridView", false)
    fun saveGridView(on: Boolean) { prefs.edit().putBoolean("gridView", on).apply() }

    /** Erkannter Text gescannter Dokumente: Datei-URI → Text (für die Volltextsuche). */
    fun loadContent(): Map<String, String> {
        val raw = prefs.getString("content", null) ?: return emptyMap()
        return runCatching {
            val o = JSONObject(raw)
            o.keys().asSequence().associateWith { o.getString(it) }
        }.getOrDefault(emptyMap())
    }

    fun saveContent(c: Map<String, String>) {
        val o = JSONObject()
        c.forEach { (k, v) -> o.put(k, v) }
        prefs.edit().putString("content", o.toString()).apply()
    }

    fun loadAutoSort(): Boolean = prefs.getBoolean("watchAutoSort", false)
    fun saveAutoSort(on: Boolean) { prefs.edit().putBoolean("watchAutoSort", on).apply() }

    fun loadLock(): Boolean = prefs.getBoolean("appLock", false)
    fun saveLock(on: Boolean) { prefs.edit().putBoolean("appLock", on).apply() }

    /** Alle Einstellungen, Regeln, Tags, Favoriten usw. als JSON (Sicherung). */
    fun exportJson(): String {
        val data = JSONObject()
        prefs.all.forEach { (k, v) ->
            val e = JSONObject()
            when (v) {
                is String -> e.put("t", "s").put("v", v)
                is Boolean -> e.put("t", "b").put("v", v)
                is Int -> e.put("t", "i").put("v", v)
                is Long -> e.put("t", "l").put("v", v)
                is Float -> e.put("t", "f").put("v", v.toDouble())
                is Set<*> -> e.put("t", "set").put("v", JSONArray(v.map { it.toString() }))
                else -> return@forEach
            }
            data.put(k, e)
        }
        return JSONObject()
            .put("app", "CX File Manager")
            .put("version", 1)
            .put("created", System.currentTimeMillis())
            .put("data", data)
            .toString(2)
    }

    /** Sicherung zurückspielen – ersetzt alle aktuellen Daten. Liefert false bei ungültiger Datei. */
    fun importJson(json: String): Boolean {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return false
        if (root.optString("app") != "CX File Manager") return false
        val data = root.optJSONObject("data") ?: return false
        val ed = prefs.edit().clear()
        data.keys().forEach { k ->
            val e = data.getJSONObject(k)
            when (e.optString("t")) {
                "s" -> ed.putString(k, e.getString("v"))
                "b" -> ed.putBoolean(k, e.getBoolean("v"))
                "i" -> ed.putInt(k, e.getInt("v"))
                "l" -> ed.putLong(k, e.getLong("v"))
                "f" -> ed.putFloat(k, e.getDouble("v").toFloat())
                "set" -> {
                    val a = e.getJSONArray("v")
                    ed.putStringSet(k, List(a.length()) { a.getString(it) }.toSet())
                }
            }
        }
        return ed.commit()
    }

    fun loadWatch(): Boolean = prefs.getBoolean("watchDownloads", false)

    fun saveWatch(on: Boolean) {
        prefs.edit().putBoolean("watchDownloads", on).apply()
    }

    private fun <T> readArray(key: String, read: (JSONArray, Int) -> T): List<T> {
        val raw = prefs.getString(key, null) ?: return emptyList()
        return runCatching {
            val a = JSONArray(raw)
            List(a.length()) { read(a, it) }
        }.getOrDefault(emptyList())
    }
}
