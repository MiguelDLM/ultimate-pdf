package org.ultimatepdf.ui.scanner

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.ultimatepdf.core.model.QuadCorners
import org.ultimatepdf.core.model.ScanFilter
import org.ultimatepdf.core.scanner.ScanMode
import org.ultimatepdf.core.scanner.ScanPipeline
import java.io.File
import java.io.FileOutputStream

/**
 * One capture. The photo lives in [file] (JPEG); only [thumbnail] stays in memory.
 * [corners] are in the coordinates of the stored photo ([width] × [height]).
 */
class ScanCapture(
    val id: Long,
    val file: File,
    val width: Int,
    val height: Int,
    val thumbnail: Bitmap,
    corners: QuadCorners,
    filter: ScanFilter
) {
    var corners by mutableStateOf(corners)
    var filter by mutableStateOf(filter)
    var filterIntensity by androidx.compose.runtime.mutableFloatStateOf(0.5f)
    var rotation by mutableIntStateOf(0)
    /** Processed preview (cropped, filtered, rotated) at screen resolution. */
    var preview by mutableStateOf<List<Bitmap>>(emptyList())
}

/** Captures of the current scan and the choices that apply to all of them. */
class ScanSession(private val dir: File, private val pipeline: ScanPipeline) {
    var mode by mutableStateOf(ScanMode.DOCUMENT)
    val captures = mutableStateListOf<ScanCapture>()
    var selected by mutableIntStateOf(0)
    var defaultFilter by mutableStateOf(ScanFilter.MAGIC_COLOR)
    var ocrEnabled by mutableStateOf(true)
    private var nextId = 0L

    /** ID cards need exactly front and back. */
    val isComplete: Boolean get() = if (mode == ScanMode.ID_CARD) captures.size == 2 else captures.isNotEmpty()

    /** Stores [photo] (already upright) and adds it with auto-detected corners. */
    suspend fun add(photo: Bitmap) {
        val capture = withContext(Dispatchers.Default) {
            dir.mkdirs()
            val id = nextId++
            val file = File(dir, "capture_$id.jpg")
            FileOutputStream(file).use { photo.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            val corners = pipeline.detectCorners(photo)
            val thumbScale = THUMB_SIZE.toFloat() / maxOf(photo.width, photo.height)
            val thumb = Bitmap.createScaledBitmap(photo, (photo.width * thumbScale).toInt(), (photo.height * thumbScale).toInt(), true)
            ScanCapture(id, file, photo.width, photo.height, thumb, corners, defaultFilter)
        }
        captures += capture
        selected = captures.lastIndex
    }

    fun remove(capture: ScanCapture) {
        capture.file.delete()
        captures.remove(capture)
        selected = selected.coerceIn(0, (captures.size - 1).coerceAtLeast(0))
    }

    fun clear() {
        captures.forEach { it.file.delete() }
        captures.clear()
        selected = 0
    }

    private companion object {
        const val THUMB_SIZE = 320
    }
}
