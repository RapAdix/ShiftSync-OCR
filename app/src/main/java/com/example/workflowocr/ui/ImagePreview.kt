package com.example.workflowocr

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

@Composable
fun ZoomableImage(
    bitmap: ImageBitmap,
    contentDescription: String?,
    modifier: Modifier = Modifier
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    // Track touch timestamps manually to detect double tap inside gesture loop
    var lastTapTime by remember { mutableFloatStateOf(0f) }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 200.dp, max = 500.dp)
            .clipToBounds()
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val currentTime = down.uptimeMillis.toFloat()

                    // Check for double-tap (tap within 300ms)
                    if (currentTime - lastTapTime < 300f) {
                        if (scale > 1f) {
                            scale = 1f
                            offsetX = 0f
                            offsetY = 0f
                        } else {
                            scale = 2.5f
                        }
                        down.consume()
                        lastTapTime = 0f // Reset double tap timer
                    } else {
                        lastTapTime = currentTime
                    }

                    do {
                        val event = awaitPointerEvent()
                        val zoomChange = event.calculateZoom()
                        val panChange = event.calculatePan()

                        val isMultiTouch = event.changes.size > 1
                        val isZoomed = scale > 1f

                        if (isZoomed || isMultiTouch || zoomChange != 1f) {
                            scale = (scale * zoomChange).coerceIn(1f, 5f)

                            if (scale > 1f) {
                                offsetX += panChange.x
                                offsetY += panChange.y
                            } else {
                                offsetX = 0f
                                offsetY = 0f
                            }

                            // Consume changes so outer page scroll ignores gesture while zoomed
                            event.changes.forEach { it.consume() }
                        }
                        // Unzoomed single-finger touches remain unconsumed for normal vertical scrolling
                    } while (event.changes.any { it.pressed })
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Image(
            bitmap = bitmap,
            contentDescription = contentDescription,
            modifier = Modifier.graphicsLayer(
                scaleX = scale,
                scaleY = scale,
                translationX = offsetX,
                translationY = offsetY
            )
        )
    }
}

fun Bitmap.scaleForPreview(maxWidth: Int = 1000): Bitmap {
    if (this.width <= maxWidth) return this.copy(this.config ?: Bitmap.Config.ARGB_8888, true)
    val aspectRatio = this.height.toFloat() / this.width.toFloat()
    val targetHeight = (maxWidth * aspectRatio).toInt()
    return Bitmap.createScaledBitmap(this, maxWidth, targetHeight, true)
}
