package com.agency.photoduel.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream

/**
 * Generates test photos on the device, so no binary fixture files are needed. The picture is four
 * solid quadrants (top-left red, top-right green, bottom-left blue, bottom-right yellow), which makes
 * any rotation or flip visible from four sample pixels.
 */
internal object TestImages {
    val RED = Color.rgb(230, 30, 30)
    val GREEN = Color.rgb(30, 200, 30)
    val BLUE = Color.rgb(30, 30, 230)
    val YELLOW = Color.rgb(240, 220, 20)

    fun quadrants(width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint()
        val halfW = width / 2f
        val halfH = height / 2f
        paint.color = RED
        canvas.drawRect(0f, 0f, halfW, halfH, paint)
        paint.color = GREEN
        canvas.drawRect(halfW, 0f, width.toFloat(), halfH, paint)
        paint.color = BLUE
        canvas.drawRect(0f, halfH, halfW, height.toFloat(), paint)
        paint.color = YELLOW
        canvas.drawRect(halfW, halfH, width.toFloat(), height.toFloat(), paint)
        return bitmap
    }

    /** Writes a JPEG of [quadrants] into [dir]. Returns the file. */
    fun writeJpeg(dir: File, name: String, width: Int, height: Int, quality: Int = 92): File {
        val file = File(dir, name)
        val bitmap = quadrants(width, height)
        try {
            FileOutputStream(file).use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it)) }
        } finally {
            bitmap.recycle()
        }
        return file
    }

    /** Writes a PNG whose left half is fully transparent and whose right half is opaque red. */
    fun writeHalfTransparentPng(dir: File, name: String, width: Int, height: Int): File {
        val file = File(dir, name)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
            val paint = Paint().apply { color = RED }
            canvas.drawRect(width / 2f, 0f, width.toFloat(), height.toFloat(), paint)
            FileOutputStream(file).use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            bitmap.recycle()
        }
        return file
    }

    fun setOrientation(file: File, exifOrientation: Int) {
        val exif = ExifInterface(file.absolutePath)
        exif.setAttribute(ExifInterface.TAG_ORIENTATION, exifOrientation.toString())
        exif.saveAttributes()
    }

    /** Adds a GPS position and other identifying EXIF fields to a JPEG file. */
    fun addIdentifyingExif(file: File, latitude: Double, longitude: Double, make: String) {
        val exif = ExifInterface(file.absolutePath)
        exif.setLatLong(latitude, longitude)
        exif.setAttribute(ExifInterface.TAG_MAKE, make)
        exif.setAttribute(ExifInterface.TAG_DATETIME, "2026:10:10 12:00:00")
        exif.setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, "taken at my front door")
        exif.saveAttributes()
    }

    fun uriOf(file: File): Uri = Uri.fromFile(file)

    /** Channel-wise closeness, tolerant of JPEG artefacts and scaling. */
    fun isClose(expected: Int, actual: Int, tolerance: Int = 48): Boolean =
        Math.abs(Color.red(expected) - Color.red(actual)) <= tolerance &&
            Math.abs(Color.green(expected) - Color.green(actual)) <= tolerance &&
            Math.abs(Color.blue(expected) - Color.blue(actual)) <= tolerance

    fun cacheSubDirectory(context: Context, name: String): File =
        File(context.cacheDir, name).apply { mkdirs() }
}
