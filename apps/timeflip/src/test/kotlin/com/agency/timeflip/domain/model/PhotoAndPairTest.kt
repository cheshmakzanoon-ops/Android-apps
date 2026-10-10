package com.agency.timeflip.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PhotoAndPairTest {

    private fun photo(id: String, slot: PhotoSlot, width: Int = 1200, height: Int = 800) = SourcePhoto(
        id = PhotoId(id),
        slot = slot,
        size = PixelSize(width, height),
        byteSize = 10L,
        importedAtEpochMs = 0L,
    )

    @Test
    fun scaledToLongestEdgeNeverUpscalesAndKeepsAspect() {
        assertEquals(PixelSize(400, 300), PixelSize(1600, 1200).scaledToLongestEdge(400))
        assertEquals(PixelSize(300, 200), PixelSize(300, 200).scaledToLongestEdge(1000))
    }

    @Test
    fun pairRejectsWrongSlotsAndSamePhoto() {
        assertThrows(IllegalArgumentException::class.java) {
            ImagePair(photo("a", PhotoSlot.AFTER), photo("b", PhotoSlot.AFTER))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ImagePair(photo("a", PhotoSlot.BEFORE), photo("a", PhotoSlot.AFTER))
        }
    }

    @Test
    fun swapExchangesIdsAndSlots() {
        val pair = ImagePair(photo("before", PhotoSlot.BEFORE), photo("after", PhotoSlot.AFTER))
        val swapped = pair.swapped()
        assertEquals(PhotoId("after"), swapped.before.id)
        assertEquals(PhotoSlot.BEFORE, swapped.before.slot)
        assertEquals(PhotoId("before"), swapped.after.id)
        assertEquals(PhotoSlot.AFTER, swapped.after.slot)
    }

    @Test
    fun pixelSizeRejectsNonPositiveDimensions() {
        assertThrows(IllegalArgumentException::class.java) { PixelSize(0, 10) }
    }
}
