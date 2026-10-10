package com.agency.timeflip.testsupport

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.agency.timeflip.domain.model.PhotoId
import com.agency.timeflip.domain.model.PhotoSlot
import com.agency.timeflip.domain.model.PhotoSourceRef
import com.agency.timeflip.domain.model.PixelSize
import com.agency.timeflip.domain.model.SourcePhoto
import com.agency.timeflip.domain.port.PhotoStore
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Writes deterministic synthetic JPEGs. Both slots share the same random structure. The AFTER image
 * is shifted, scaled and warmed, so the aligner has real features to match. No real photo is used.
 */
object SyntheticJpeg {
    val SIZE = PixelSize(900, 1200)
    private const val SEED = 20261010L

    fun write(target: File, slot: PhotoSlot) {
        target.parentFile?.mkdirs()
        val bitmap = Bitmap.createBitmap(SIZE.width, SIZE.height, Bitmap.Config.ARGB_8888)
        try {
            draw(Canvas(bitmap), slot)
            FileOutputStream(target).use { out ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)) { "JPEG encoding failed" }
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun draw(canvas: Canvas, slot: PhotoSlot) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        canvas.drawColor(Color.rgb(190, 196, 204))
        if (slot == PhotoSlot.AFTER) {
            canvas.translate(18f, -12f)
            canvas.scale(1.04f, 1.04f, SIZE.width / 2f, SIZE.height / 2f)
        }
        val random = Random(SEED)
        repeat(90) {
            paint.color = Color.rgb(random.nextInt(256), random.nextInt(256), random.nextInt(256))
            val left = random.nextInt(SIZE.width - 200).toFloat()
            val top = random.nextInt(SIZE.height - 200).toFloat()
            val side = 40f + random.nextInt(160)
            canvas.drawRect(left, top, left + side, top + side, paint)
        }
        if (slot == PhotoSlot.AFTER) {
            paint.color = Color.argb(60, 255, 170, 60)
            canvas.drawRect(0f, 0f, SIZE.width.toFloat(), SIZE.height.toFloat(), paint)
        }
    }
}

/**
 * Test double for [PhotoStore]. The picker reference is ignored. Each import writes a synthetic JPEG
 * into [fixtureDir] (under the app cache directory), so no user media is read or stored.
 */
class SyntheticPhotoStore(private val fixtureDir: File) : PhotoStore {
    private val copies = ConcurrentHashMap<PhotoId, File>()
    private val imports = AtomicInteger(0)

    /** Number of imports so far. Tests use it to know the picker result reached the import path. */
    val importCount: Int
        get() = imports.get()

    override suspend fun importPhoto(source: PhotoSourceRef, slot: PhotoSlot, nowEpochMs: Long): SourcePhoto =
        withContext(Dispatchers.IO) {
            val index = imports.incrementAndGet()
            val id = PhotoId("synthetic-${slot.name.lowercase()}-$index")
            val file = File(fixtureDir, "${id.value}.jpg")
            SyntheticJpeg.write(file, slot)
            copies[id] = file
            SourcePhoto(id = id, slot = slot, size = SyntheticJpeg.SIZE, byteSize = file.length(), importedAtEpochMs = nowEpochMs)
        }

    override suspend fun originalPath(id: PhotoId): String? =
        copies[id]?.takeIf { it.isFile }?.absolutePath

    override suspend fun thumbnailPath(id: PhotoId, maxEdge: Int): String? = originalPath(id)

    override suspend fun delete(id: PhotoId) {
        copies.remove(id)?.delete()
    }
}
