package com.example.workflowocr

import android.graphics.Bitmap
import android.util.Log
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc

object TableDetector {

    data class TableDetectionResult(
        val cells: List<Rect>,
        val thresh: Mat,
        val mask: Mat
    )

    // Input: a grayscale Mat
    // Output: List of Rects representing detected cells
    fun detectTableCells(gray: Mat): TableDetectionResult {

        val thresh = Mat()
        // Apply adaptive threshold to get binary image
        Imgproc.adaptiveThreshold(
            gray, thresh, 255.0,
            Imgproc.ADAPTIVE_THRESH_MEAN_C,
            Imgproc.THRESH_BINARY_INV,
            15, 10.0
        )
        Log.d("DEBUG", ">> thresh size = ${thresh.rows()} x ${thresh.cols()}")
        Log.d("DEBUG", "thresh nonZero = ${Core.countNonZero(thresh)}")

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
        Log.d("DEBUG", ">> horizontal after erode/dilate = ${horizontal.rows()} x ${horizontal.cols()}")
        Log.d("DEBUG", "horizontal nonZero = ${Core.countNonZero(horizontal)}")

        // Vertical lines
        val verticalsize = (thresh.rows() / 15)
        val verticalStructure = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(1.0, verticalsize.toDouble()))
        Imgproc.erode(thresh, vertical, verticalStructure)
        Imgproc.dilate(vertical, vertical, verticalStructure)
        Log.d("DEBUG", ">> vertical after erode/dilate = ${vertical.rows()} x ${vertical.cols()}")
        Log.d("DEBUG", "vertical nonZero = ${Core.countNonZero(vertical)}")

        // Combine horizontal and vertical lines to get table mask
        val mask = Mat()
        Core.add(horizontal, vertical, mask)
        Log.d("DEBUG", ">> mask size = ${mask.rows()} x ${mask.cols()}")
        Log.d("DEBUG", "mask nonZero = ${Core.countNonZero(mask)}")

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
        //TODO should I clean?

        // Sort cells by row, then column
        return TableDetectionResult(
            cells.sortedWith(compareBy({ it.y }, { it.x })),
            thresh,
            mask
        )
    }

    /** Convert an Android Bitmap -> OpenCV grayscale Mat */
    fun bitmapToGrayMat(bitmap: Bitmap): Mat {
        val rgba = Mat()
        Utils.bitmapToMat(bitmap, rgba)

        val gray = Mat()
        Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)

        rgba.release()
        return gray
    }

    /** Convert OpenCV Mat -> Android Bitmap (ARGB_8888) for display */
    fun matToBitmap(mat: Mat): Bitmap {
        // Null or empty Mat? — return safe 1×1 bitmap
        if (mat.empty() || mat.cols() <= 0 || mat.rows() <= 0) {
            return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        }

        val bmp = Bitmap.createBitmap(mat.cols(), mat.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(mat, bmp)
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
