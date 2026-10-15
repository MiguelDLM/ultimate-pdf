package org.ultimatepdf.core.signature

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.ultimatepdf.core.model.SignatureShape
import java.io.File
import java.util.UUID

data class SavedSignature(val id: String, val shape: SignatureShape, val name: String)

/**
 * The user's reusable signatures, stored privately in the app's files directory.
 * [items] is observable, so the reader and the settings screen stay in step.
 */
class SignatureLibrary(context: Context) {

    private val dir = File(context.filesDir, "signatures")

    /** Newest first. Empty until [load] has run. */
    val items = mutableStateListOf<SavedSignature>()

    /**
     * Reads the library from disk. Signatures saved before names existed get one from
     * [defaultName] (given their position, oldest first) and keep it from then on.
     */
    suspend fun load(defaultName: (Int) -> String) {
        val loaded = withContext(Dispatchers.IO) {
            val files = dir.listFiles { f -> f.extension == "sig" }.orEmpty().sortedBy { it.lastModified() }
            files.mapIndexedNotNull { index, f ->
                val shape = SignatureShape.decode(f.readText()) ?: return@mapIndexedNotNull null
                val nameFile = File(dir, "${f.nameWithoutExtension}.name")
                val name = nameFile.takeIf { it.exists() }?.readText()?.trim()?.takeIf { it.isNotEmpty() }
                    ?: defaultName(index + 1).also { runCatching { nameFile.writeText(it) } }
                SavedSignature(f.nameWithoutExtension, shape, name)
            }.reversed()
        }
        items.clear()
        items.addAll(loaded)
    }

    suspend fun save(shape: SignatureShape, name: String): SavedSignature {
        val saved = withContext(Dispatchers.IO) {
            dir.mkdirs()
            val id = UUID.randomUUID().toString()
            File(dir, "$id.sig").writeText(shape.encode())
            File(dir, "$id.name").writeText(name)
            SavedSignature(id, shape, name)
        }
        items.add(0, saved)
        return saved
    }

    suspend fun rename(id: String, name: String) {
        val clean = name.trim()
        if (clean.isEmpty()) return
        withContext(Dispatchers.IO) { File(dir, "$id.name").writeText(clean) }
        val index = items.indexOfFirst { it.id == id }
        if (index >= 0) items[index] = items[index].copy(name = clean)
    }

    suspend fun delete(id: String) {
        withContext(Dispatchers.IO) {
            File(dir, "$id.sig").delete()
            File(dir, "$id.name").delete()
        }
        items.removeAll { it.id == id }
    }
}
