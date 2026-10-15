package org.ultimatepdf.core.signature

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.ultimatepdf.core.model.SignatureShape
import java.io.File
import java.util.UUID

enum class StampKind { TEXT, IMAGE }

/**
 * A reusable stamp: a line of [text] in a frame, or a vectorized picture ([shape]), drawn in
 * [colorArgb]. [name] is how it is listed.
 */
data class SavedStamp(
    val id: String,
    val name: String,
    val kind: StampKind,
    val text: String,
    val shape: SignatureShape?,
    val colorArgb: Int
)

/**
 * The user's custom stamps, kept in the app's private storage so they survive restarts and can
 * be managed from the settings. [items] is observable, newest first.
 */
class StampLibrary(context: Context) {

    private val dir = File(context.filesDir, "stamps")

    val items = mutableStateListOf<SavedStamp>()

    suspend fun load() {
        val loaded = withContext(Dispatchers.IO) {
            dir.listFiles { f -> f.extension == "json" }.orEmpty()
                .sortedByDescending { it.lastModified() }
                .mapNotNull { f -> runCatching { parse(f.nameWithoutExtension, JSONObject(f.readText())) }.getOrNull() }
        }
        items.clear()
        items.addAll(loaded)
    }

    suspend fun addText(text: String, colorArgb: Int): SavedStamp =
        add(SavedStamp(UUID.randomUUID().toString(), text, StampKind.TEXT, text, null, colorArgb))

    suspend fun addImage(shape: SignatureShape, colorArgb: Int, name: String): SavedStamp =
        add(SavedStamp(UUID.randomUUID().toString(), name, StampKind.IMAGE, "", shape, colorArgb))

    suspend fun rename(id: String, name: String) {
        val clean = name.trim()
        val index = items.indexOfFirst { it.id == id }
        if (clean.isEmpty() || index < 0) return
        val updated = items[index].copy(name = clean)
        write(updated)
        items[index] = updated
    }

    suspend fun delete(id: String) {
        withContext(Dispatchers.IO) { File(dir, "$id.json").delete() }
        items.removeAll { it.id == id }
    }

    private suspend fun add(stamp: SavedStamp): SavedStamp {
        write(stamp)
        items.add(0, stamp)
        return stamp
    }

    private suspend fun write(stamp: SavedStamp) = withContext(Dispatchers.IO) {
        dir.mkdirs()
        val json = JSONObject()
            .put("name", stamp.name)
            .put("kind", stamp.kind.name)
            .put("text", stamp.text)
            .put("color", stamp.colorArgb)
        stamp.shape?.let { json.put("shape", it.encode()) }
        File(dir, "${stamp.id}.json").writeText(json.toString())
    }

    private fun parse(id: String, json: JSONObject): SavedStamp? {
        val kind = StampKind.valueOf(json.getString("kind"))
        val shape = json.optString("shape").takeIf { it.isNotEmpty() }?.let(SignatureShape::decode)
        if (kind == StampKind.IMAGE && shape == null) return null
        return SavedStamp(id, json.optString("name", json.optString("text")), kind, json.optString("text"), shape, json.getInt("color"))
    }
}
