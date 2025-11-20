package com.example.workflowocr

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc

object TableDetector {

    // Input: a grayscale Mat
    // Output: List of Rects representing detected cells
    fun detectTableCells(gray: Mat): List<Rect> {
        val thresh = Mat()
        // Apply adaptive threshold to get binary image
        Imgproc.adaptiveThreshold(
            gray, thresh, 255.0,
            Imgproc.ADAPTIVE_THRESH_MEAN_C,
            Imgproc.THRESH_BINARY_INV,
            15, 10.0
        )

        // Detect horizontal and vertical lines separately
        val horizontal = Mat(thresh.size(), CvType.CV_8UC1)
        val vertical = Mat(thresh.size(), CvType.CV_8UC1)
        horizontal.setTo(Scalar(0.0))
        vertical.setTo(Scalar(0.0))

        // Horizontal lines
        val horizontalsize = (thresh.cols() / 15)
        val horizontalStructure = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(horizontalsize.toDouble(), 1.0))
        Imgproc.erode(thresh, horizontal, horizontalStructure)
        Imgproc.dilate(horizontal, horizontal, horizontalStructure)

        // Vertical lines
        val verticalsize = (thresh.rows() / 15)
        val verticalStructure = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(1.0, verticalsize.toDouble()))
        Imgproc.erode(thresh, vertical, verticalStructure)
        Imgproc.dilate(vertical, vertical, verticalStructure)

        // Combine horizontal and vertical lines to get table mask
        val mask = Mat()
        Core.add(horizontal, vertical, mask)

        // Find contours (each contour ~ a cell)
        val contours = mutableListOf<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(mask, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)

        val cells = mutableListOf<Rect>()
        for (contour in contours) {
            val rect = Imgproc.boundingRect(contour)
            if (rect.width > 20 && rect.height > 20) { // filter out tiny noise
                cells.add(rect)
            }
        }

        // cleanup
        thresh.release()
        horizontal.release()
        vertical.release()
        mask.release()
        hierarchy.release()
        contours.forEach { it.release() }


        // Sort cells by row, then column
        return cells.sortedWith(compareBy({ it.y }, { it.x }))
    }

    /** Convert an Android Bitmap -> OpenCV grayscale Mat */
    fun bitmapToGrayMat(bitmap: Bitmap): Mat {
        val mat = Mat()
        Utils.bitmapToMat(bitmap, mat)                 // RGBA/ARGB -> Mat
        val gray = Mat()
        Imgproc.cvtColor(mat, gray, Imgproc.COLOR_BGR2GRAY) // convert to gray
        mat.release()
        return gray
    }

    /** Convert OpenCV Mat -> Android Bitmap (ARGB_8888) for display */
    fun matToBitmap(mat: Mat): Bitmap {
        // If mat is single-channel (gray), convert to RGBA for display
        val displayMat = if (mat.channels() == 1) {
            val tmp = Mat()
            Imgproc.cvtColor(mat, tmp, Imgproc.COLOR_GRAY2RGBA)
            tmp
        } else {
            mat
        }

        val bmp = Bitmap.createBitmap(displayMat.cols(), displayMat.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(displayMat, bmp)
        if (displayMat !== mat) displayMat.release()
        return bmp
    }

    fun drawCellBoxes(bitmap: Bitmap, cells: List<Rect>): Bitmap {
        val mat = Mat()
        Utils.bitmapToMat(bitmap, mat)

        for (rect in cells) {
            Imgproc.rectangle(
                mat,
                Point(rect.x.toDouble(), rect.y.toDouble()),
                Point((rect.x + rect.width).toDouble(), (rect.y + rect.height).toDouble()),
                Scalar(255.0, 0.0, 0.0), // red rectangle
                3
            )
        }

        val output = Bitmap.createBitmap(mat.cols(), mat.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(mat, output)
        return output
    }
}
