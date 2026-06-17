package com.example.workflowocr

import android.graphics.Bitmap
import android.util.Log
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

object ImageProcessor {
    fun createThresh(gray: Mat): Mat {
        // 1. Create a mask of the "Paper" area,
        // // Background is pure black (0) after rotation
        val validMask = Mat()
        Imgproc.threshold(gray, validMask, 1.0, 255.0, Imgproc.THRESH_BINARY)

        // FILL THE HOLES: This removes the table lines from the mask
        // so they don't get deleted later.
        val kernelSize = 25.0 // Large enough to cover thickest line/text
        val closeElement = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(kernelSize, kernelSize))
        Imgproc.morphologyEx(validMask, validMask, Imgproc.MORPH_CLOSE, closeElement)

        // Erode the mask to "shrink" the valid area away from the edges
        // This ensures that after rotation the high-contrast transition at the image edge is ignored.
        val maskErosionSize = 6.0 // px
        val maskElement = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(maskErosionSize, maskErosionSize))
        Imgproc.erode(validMask, validMask, maskElement)

        val thresh = Mat()
        Core.normalize(gray, thresh, 0.0, 255.0, Core.NORM_MINMAX)
        // Apply adaptive threshold to get binary image
        Imgproc.adaptiveThreshold(
            thresh, thresh, 255.0,
            Imgproc.ADAPTIVE_THRESH_MEAN_C,
            Imgproc.THRESH_BINARY_INV,
            55, 8.0
        )
        Log.d("DEBUG", ">> thresh size = ${thresh.rows()} x ${thresh.cols()}")
        Log.d("DEBUG", "thresh nonZero = ${Core.countNonZero(thresh)}")

        Core.bitwise_and(thresh, validMask, thresh)

        // Cleanup mask
        validMask.release()
        maskElement.release()
        return thresh
    }

    fun reduceMoireNoise(sourceBitmap: Bitmap, upscale: Boolean = false): Bitmap {
        val grayMat = bitmapToGrayMat(sourceBitmap)

        if (upscale) {
            // 1. Upscale image slightly if it is a narrow column (ML Kit needs letters to be at least 16x16px)
            if (sourceBitmap.width < 300) {
                val scaleFactor = 2.0
                val targetSize = Size(grayMat.width() * scaleFactor, grayMat.height() * scaleFactor)
                Imgproc.resize(grayMat, grayMat, targetSize, 0.0, 0.0, Imgproc.INTER_CUBIC)
            }
        }

        // 2. Reduce Moiré noise using Bilateral Filter
        // d = 9, sigmaColor and sigmaSpace around 75 are good defaults for screen patterns
        val denoisedMat = Mat()
        Imgproc.bilateralFilter(grayMat, denoisedMat, 9, 75.0, 75.0)

        // 3. Adaptive Thresholding to isolate text and handle uneven glare
        val threshMat = Mat()
        Imgproc.adaptiveThreshold(
            denoisedMat,
            threshMat,
            255.0,
            Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
            Imgproc.THRESH_BINARY,
            25,
            15.0 // Constant subtracted from the mean
        )

        // 4. Clean up broken text pixels (Morphological Closing)
        val finalMat = Mat()
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
        Imgproc.morphologyEx(threshMat, finalMat, Imgproc.MORPH_CLOSE, kernel)

        val resultBitmap = matToBitmap(finalMat)

        // Clean up memory for intermediate Mats
        grayMat.release()
        denoisedMat.release()
        threshMat.release()
        kernel.release()
        finalMat.release()

        return resultBitmap
    }

    /** Convert an Android Bitmap -> OpenCV grayscale Mat */
    fun bitmapToGrayMat(bitmap: Bitmap): Mat {
        Log.d("DEBUG", "bitmapToGrayMat size before: ${bitmap.width} width, ${bitmap.height} height")
        val rgba = Mat()
        Utils.bitmapToMat(bitmap, rgba)

        val gray = Mat()

        // Check how many channels the Mat has
        if (rgba.channels() == 1) {
            // Bitmap is already grayscale (1 channel) - Just copy rgba directly into gray
            rgba.copyTo(gray)
        } else if (rgba.channels() == 3) {
            // Bitmap is 3 channels (RGB)
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGB2GRAY)
        } else {
            // Bitmap is 4 channels (RGBA)
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
        }
        Log.d("DEBUG", "bitmapToGrayMat size after: ${gray.width()} width, ${gray.height()} height, ${gray.rows()} rows, ${gray.cols()} cols")

        rgba.release()
        return gray
    }

    /** Convert OpenCV Mat -> Android Bitmap (ARGB_8888) for display */
    fun matToBitmap(mat: Mat): Bitmap {
        // Null or empty Mat? — return safe 1×1 bitmap
        if (mat.empty() || mat.cols() <= 0 || mat.rows() <= 0) {
            Log.d("DEBUG", "matToBitmap: mat is empty")
            return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        }

        val bmp = Bitmap.createBitmap(mat.cols(), mat.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(mat, bmp)
        return bmp
    }
}