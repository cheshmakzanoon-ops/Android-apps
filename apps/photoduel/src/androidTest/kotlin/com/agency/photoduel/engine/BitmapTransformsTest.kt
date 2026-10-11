package com.agency.photoduel.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.agency.photoduel.engine.android.BitmapTransforms
import com.agency.photoduel.engine.pure.ImageGeometry
import com.agency.photoduel.engine.pure.PixelSize
import kotlin.math.floor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Checks that the Android Matrix built for each EXIF orientation puts every pixel where the pure,
 * JVM-tested reference transform (`OrientationTransform.mapPixel`) says it goes.
 */
@RunWith(AndroidJUnit4::class)
class BitmapTransformsTest {

    @Test
    fun matrixAgreesWithThePureReferenceForAllEightOrientations() {
        val storedW = 6
        val storedH = 4
        for (orientation in 1..8) {
            val transform = ImageGeometry.transformFor(orientation)
            val upright = transform.orientedSize(storedW, storedH)
            val matrix = BitmapTransforms.uprightMatrix(storedW, storedH, transform, upright)
            for (y in 0 until storedH) {
                for (x in 0 until storedW) {
                    val point = floatArrayOf(x + 0.5f, y + 0.5f)
                    matrix.mapPoints(point)
                    val expected = transform.mapPixel(x, y, storedW, storedH)
                    assertEquals("orientation $orientation pixel ($x,$y) x", expected.x, floor(point[0]).toInt())
                    assertEquals("orientation $orientation pixel ($x,$y) y", expected.y, floor(point[1]).toInt())
                }
            }
        }
    }

    @Test
    fun matrixScalesTheUprightImageToTheTargetSize() {
        val transform = ImageGeometry.transformFor(6)
        val matrix = BitmapTransforms.uprightMatrix(3000, 2000, transform, PixelSize(800, 1200))
        val corner = floatArrayOf(3000f, 2000f) // bottom-right of the stored image
        matrix.mapPoints(corner)
        // Rotated a quarter turn clockwise, stored bottom-right lands bottom-left of the upright image.
        assertEquals(0f, corner[0], 0.01f)
        assertEquals(1200f, corner[1], 0.01f)
        val origin = floatArrayOf(0f, 0f)
        matrix.mapPoints(origin)
        assertEquals(800f, origin[0], 0.01f)
        assertEquals(0f, origin[1], 0.01f)
        assertTrue(matrix.isAffine)
    }
}
