package com.example.workflowocr

import android.graphics.Bitmap
import android.util.Log
import android.util.Rational
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

object CameraTargetGeometry {
    // Horizontal sizing: The zone occupies 15% of the total layout width, perfectly centered
    const val WIDTH_RATIO = 0.15f
    const val LEFT_RATIO = (1.0f - WIDTH_RATIO) / 2f

    // Vertical sizing: The zone runs from 7% down to 85% of the total layout height
    const val TOP_RATIO = 0.07f
    const val BOTTOM_RATIO = 0.85f
    const val HEIGHT_RATIO = BOTTOM_RATIO - TOP_RATIO // 0.70f
}

@Composable
fun CameraScreen(
    onImageCaptured: (Bitmap) -> Unit,
    onBackClicked: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }

    // 🟢 Feedback States
    var isProcessing by remember { mutableStateOf(false) }
    var showFlashOverlay by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {

        // 1. Live Camera Viewfinder Layer
        AndroidView(
            factory = { ctx -> PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } },
            modifier = Modifier.fillMaxSize(),
            update = { previewView ->
                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()

                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }

                    imageCapture = ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        // Force the capture to use the same rotation as the screen viewport
                        .setTargetRotation(previewView.display.rotation)
                        .build()

                    val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                    try {
                        cameraProvider.unbindAll()

                        // Build a coordinate ViewPort tied to physical layout bounds
                        val viewPort = previewView.viewPort ?: ViewPort.Builder(
                            Rational(previewView.width, previewView.height),
                            previewView.display.rotation
                        ).setScaleType(ViewPort.FILL_CENTER).build()

                        // Group the use cases together under this viewport context
                        val useCaseGroup = UseCaseGroup.Builder()
                            .addUseCase(preview)
                            .addUseCase(imageCapture!!)
                            .setViewPort(viewPort) // 🟢 Binds both use cases to the exact same aspect crop matrix
                            .build()

                        // Bind the group instead of independent use cases
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            cameraSelector,
                            useCaseGroup
                        )
                    } catch (exc: Exception) {
                        Log.e("CameraZoneOverlay", "Use case binding failed", exc)
                    }
                }, ContextCompat.getMainExecutor(context))
            }
        )

        // 2. Custom Target Guide Box Overlay
        Canvas(modifier = Modifier.fillMaxSize()) {
            val canvasWidth = size.width
            val canvasHeight = size.height

            val zoneWidth = canvasWidth * CameraTargetGeometry.WIDTH_RATIO
            val zoneLeft = canvasWidth * CameraTargetGeometry.LEFT_RATIO
            val zoneTop = canvasHeight * CameraTargetGeometry.TOP_RATIO
            val zoneBottom = canvasHeight * CameraTargetGeometry.BOTTOM_RATIO

            drawRect(color = Color.Black.copy(alpha = 0.5f), size = size)
            drawRect(color = Color.Transparent, topLeft = Offset(zoneLeft, zoneTop), size = Size(zoneWidth, zoneBottom - zoneTop), blendMode = BlendMode.Clear)
            drawLine(color = Color.Cyan, start = Offset(zoneLeft, zoneTop), end = Offset(zoneLeft, zoneBottom), strokeWidth = 3.dp.toPx())
            drawLine(color = Color.Cyan, start = Offset(zoneLeft + zoneWidth, zoneTop), end = Offset(zoneLeft + zoneWidth, zoneBottom), strokeWidth = 3.dp.toPx())
        }

        // 3. 🟢 THE SHUTTER FLASH LAYER
        // Instantly intercepts the UI with a white curtain that fades out rapidly
        AnimatedVisibility(
            visible = showFlashOverlay,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Box(modifier = Modifier.fillMaxSize().background(Color.White))
        }

        // 4. Control Interface Layer
        Column(
            modifier = Modifier.fillMaxSize().padding(bottom = 32.dp),
            verticalArrangement = Arrangement.Bottom,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = if (isProcessing) "PROCESSING DIGITS..." else "ALIGN DIGIT COLUMN INSIDE BLUE ZONE",
                style = MaterialTheme.typography.labelLarge,
                color = Color.White,
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            )

            Spacer(modifier = Modifier.height(24.dp))

            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(72.dp)) {
                if (isProcessing) {
                    // Show active loading progress circle instead of the button
                    CircularProgressIndicator(color = Color.Cyan, strokeWidth = 4.dp)
                } else {
                    // Shutter Capture Button
                    IconButton(
                        onClick = {
                            val captureUseCase = imageCapture ?: return@IconButton

                            // Fire immediate UI feedback states
                            isProcessing = true
                            showFlashOverlay = true

                            // Pop the flash off screen after 80 milliseconds
                            scope.launch {
                                delay(80L)
                                showFlashOverlay = false
                            }

                            captureUseCase.takePicture(
                                ContextCompat.getMainExecutor(context),
                                object : ImageCapture.OnImageCapturedCallback() {
                                    override fun onCaptureSuccess(imageProxy: ImageProxy) {
                                        try {
                                            val finalUprightBitmap = imageProxy.toBitmapCroppedRotated()

                                            if (finalUprightBitmap != null) {
                                                onImageCaptured(finalUprightBitmap)
                                            } else {
                                                isProcessing = false
                                            }
                                        } catch (e: Exception) {
                                            Log.e("CameraZoneOverlay", "Crop-then-rotate transformation sequence failed", e)
                                            isProcessing = false
                                        } finally {
                                            // Essential to prevent memory leakage
                                            imageProxy.close()
                                        }
                                    }

                                    override fun onError(exception: ImageCaptureException) {
                                        Log.e("CameraZoneOverlay", "Photo capture failed", exception)
                                        isProcessing = false // Reset button on failure
                                    }
                                }
                            )
                        },
                        modifier = Modifier.fillMaxSize()
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Trigger Shutter Capture Action",
                            tint = Color.Cyan,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
        }
    }
}

fun ImageProxy.toBitmapCroppedRotated(): Bitmap? {
    // 1. Extract the RAW, UNROTATED bitmap from the byte array first
    val buffer = planes[0].buffer
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)

    val rawBitmap =
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null

    // 2. Get the Crop Rectangle calculated by CameraX ViewPort
    val rect = cropRect
    val rotationDegrees = imageInfo.rotationDegrees

    // 3. STEP A: Crop the image while it is still in its raw orientation
    // This ensures CameraX's cropRect mapping coordinates line up flawlessly
    val safeLeft = rect.left.coerceIn(0, rawBitmap.width - 1)
    val safeTop = rect.top.coerceIn(0, rawBitmap.height - 1)
    val safeWidth = rect.width().coerceIn(1, rawBitmap.width - safeLeft)
    val safeHeight = rect.height().coerceIn(1, rawBitmap.height - safeTop)

    val croppedRawBitmap = Bitmap.createBitmap(
        rawBitmap,
        safeLeft,
        safeTop,
        safeWidth,
        safeHeight
    )

    rawBitmap.recycle()

    // 4. STEP B: Now rotate ONLY the cropped slice into portrait orientation
    return if (rotationDegrees != 0) {
        val matrix = android.graphics.Matrix().apply {
            postRotate(rotationDegrees.toFloat())
        }
        Bitmap.createBitmap(
            croppedRawBitmap,
            0, 0,
            croppedRawBitmap.width,
            croppedRawBitmap.height,
            matrix,
            true
        ).also {
            // Clean up intermediate cropped bitmap allocation
            if (it != croppedRawBitmap) croppedRawBitmap.recycle()
        }
    } else {
        croppedRawBitmap
    }
}