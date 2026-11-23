package com.sesnot.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Note(val id: Long, var title: String, var body: String, var updatedAt: Long)

object NoteStore {
    private var file: File? = null
    private val notes = mutableListOf<Note>()

    @Synchronized
    fun init(ctx: Context) {
        if (file != null) return
        val f = File(ctx.applicationContext.filesDir, "notes.json")
        file = f
        notes.clear()
        if (f.exists()) {
            try {
                val arr = JSONArray(f.readText())
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    notes += Note(o.getLong("id"), o.optString("title"), o.optString("body"), o.optLong("updatedAt"))
                }
            } catch (e: Exception) {
                f.renameTo(File(f.parentFile, "notes.corrupt.json"))
                notes.clear()
            }
        }
    }

    @Synchronized fun all(): List<Note> = notes.sortedByDescending { it.updatedAt }.map { it.copy() }

    @Synchronized fun get(id: Long): Note? = notes.firstOrNull { it.id == id }?.copy()

    @Synchronized fun latestId(): Long? = notes.maxOfOrNull { it.id }

    @Synchronized
    fun create(title: String, body: String): Note {
        val now = System.currentTimeMillis()
        val id = maxOf(now, (notes.maxOfOrNull { it.id } ?: 0L) + 1)
        val n = Note(id, title, body, now)
        notes += n
        save()
        return n.copy()
    }

    @Synchronized
    fun appendBody(id: Long, text: String) {
        val n = notes.firstOrNull { it.id == id } ?: return
        n.body = if (n.body.isBlank()) text else n.body + " " + text
        n.updatedAt = System.currentTimeMillis()
        save()
    }

    @Synchronized
    fun setTitle(id: Long, title: String) {
        val n = notes.firstOrNull { it.id == id } ?: return
        n.title = title
        n.updatedAt = System.currentTimeMillis()
        save()
    }

    @Synchronized
    fun update(id: Long, title: String, body: String) {
        val n = notes.firstOrNull { it.id == id } ?: return
        n.title = title
        n.body = body
        n.updatedAt = System.currentTimeMillis()
        save()
    }

    @Synchronized
    fun delete(id: Long) {
        if (notes.removeAll { it.id == id }) save()
    }

    private fun save() {
        val f = file ?: return
        val arr = JSONArray()
        for (n in notes) {
            arr.put(
                JSONObject().put("id", n.id).put("title", n.title)
                    .put("body", n.body).put("updatedAt", n.updatedAt)
            )
        }
        val tmp = File(f.parentFile, "notes.json.tmp")
        tmp.writeText(arr.toString())
        if (!tmp.renameTo(f)) {
            f.delete()
            tmp.renameTo(f)
        }
    }
}
