package org.ultimatepdf.core.scanner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import org.ultimatepdf.core.model.OcrLine
import org.ultimatepdf.core.model.OcrPage
import org.ultimatepdf.core.model.OcrRecognizedWord
import org.ultimatepdf.core.model.PdfRect
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * On-device text recognition with ML Kit's bundled Latin-script model (works offline;
 * covers Spanish, English, French, German, Portuguese, Italian and other Latin languages).
 * Boxes are returned in the input image's pixels.
 */
class OcrEngine(@Suppress("unused") private val context: Context) {

    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    suspend fun recognize(bitmap: Bitmap): OcrPage = suspendCancellableCoroutine { cont ->
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { text ->
                val lines = text.textBlocks.flatMap { block -> block.lines }.mapNotNull { line ->
                    val box = line.boundingBox ?: return@mapNotNull null
                    OcrLine(
                        text = line.text,
                        box = box.toPdfRect(),
                        words = line.elements.mapNotNull { element ->
                            element.boundingBox?.let { OcrRecognizedWord(element.text, it.toPdfRect(), element.confidence ?: 1f) }
                        }
                    )
                }
                cont.resume(OcrPage(bitmap.width, bitmap.height, lines))
            }
            .addOnFailureListener { cont.resumeWithException(it) }
    }

    private fun Rect.toPdfRect() = PdfRect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())
}
