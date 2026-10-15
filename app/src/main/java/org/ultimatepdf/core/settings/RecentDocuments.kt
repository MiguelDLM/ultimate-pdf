package org.ultimatepdf.core.settings

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.mutableStateListOf
import org.json.JSONArray
import org.json.JSONObject

data class RecentDocument(
    val uri: Uri,
    val name: String,
    val pageCount: Int,
    val sizeBytes: Long,
    val openedAt: Long
)

/** Most-recently-opened documents, newest first, persisted across launches. */
class RecentDocuments(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("recent_documents", Context.MODE_PRIVATE)

    val items = mutableStateListOf<RecentDocument>().apply { addAll(load()) }

    fun record(uri: Uri, name: String, pageCount: Int, sizeBytes: Long) {
        items.removeAll { it.uri == uri }
        items.add(0, RecentDocument(uri, name, pageCount, sizeBytes, System.currentTimeMillis()))
        while (items.size > MAX) items.removeAt(items.lastIndex)
        persist()
    }

    fun remove(uri: Uri) {
        items.removeAll { it.uri == uri }
        persist()
    }

    fun clear() {
        items.clear()
        persist()
    }

    private fun load(): List<RecentDocument> = try {
        val array = JSONArray(prefs.getString(KEY, "[]"))
        (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            RecentDocument(
                uri = Uri.parse(o.getString("uri")),
                name = o.getString("name"),
                pageCount = o.optInt("pages"),
                sizeBytes = o.optLong("size"),
                openedAt = o.optLong("openedAt")
            )
        }
    } catch (e: Exception) {
        emptyList()
    }

    private fun persist() {
        val array = JSONArray()
        items.forEach { d ->
            array.put(
                JSONObject()
                    .put("uri", d.uri.toString())
                    .put("name", d.name)
                    .put("pages", d.pageCount)
                    .put("size", d.sizeBytes)
                    .put("openedAt", d.openedAt)
            )
        }
        prefs.edit().putString(KEY, array.toString()).apply()
    }

    private companion object {
        const val KEY = "items"
        const val MAX = 50
    }
}
