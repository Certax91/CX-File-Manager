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

    private fun <T> readArray(key: String, read: (JSONArray, Int) -> T): List<T> {
        val raw = prefs.getString(key, null) ?: return emptyList()
        return runCatching {
            val a = JSONArray(raw)
            List(a.length()) { read(a, it) }
        }.getOrDefault(emptyList())
    }
}
