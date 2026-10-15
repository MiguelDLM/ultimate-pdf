package org.ultimatepdf.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.ultimatepdf.core.pdf.PageRef
import org.ultimatepdf.core.pdf.PdfThumbnails

/**
 * A rendered page preview in a paper-like frame. [overlay] draws badges on top
 * (selection marks, page numbers).
 */
@Composable
fun PageThumbnail(
    page: PageRef,
    thumbnails: PdfThumbnails,
    modifier: Modifier = Modifier,
    rotation: Int = 0,
    widthHint: Dp = 120.dp,
    borderColor: Color = Color(0x1F000000),
    borderWidth: Dp = 1.dp,
    dimmed: Boolean = false,
    overlay: @Composable BoxScope.() -> Unit = {}
) {
    val widthPx = with(LocalDensity.current) { widthHint.roundToPx() }
    var bitmap by remember(page) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(page, widthPx) { bitmap = thumbnails.render(page.uri, page.pageIndex, widthPx) }
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(0.72f)
            .clip(shape)
            .background(Color(0x0F000000))
            .border(borderWidth, borderColor, shape),
        contentAlignment = Alignment.Center
    ) {
        bitmap?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                alpha = if (dimmed) 0.35f else 1f,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        rotationZ = rotation.toFloat()
                        // A quarter-turned page is wider than the portrait cell; shrink it to fit.
                        val quarterTurn = (rotation / 90) % 2 == 1
                        scaleX = if (quarterTurn) 0.72f else 1f
                        scaleY = scaleX
                    }
            )
        }
        overlay()
    }
}
