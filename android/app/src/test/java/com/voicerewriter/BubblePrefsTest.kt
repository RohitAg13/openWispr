package com.voicerewriter

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Covers the restore side of the bubble-position fix. The read/write of SharedPreferences needs a
 * device, but the placement decision is where the bugs live, so that part is a pure function.
 */
class BubblePrefsTest {

    private val screenW = 1080
    private val screenH = 2400
    private val size = 168

    private fun resolve(x: Int, y: Int) =
        BubblePrefs.resolvePosition(x, y, 48, 600, screenW, screenH, size)

    @Test
    fun `an unset position falls back to the default corner`() {
        assertEquals(48 to 600, resolve(BubblePrefs.UNSET, BubblePrefs.UNSET))
    }

    @Test
    fun `a half-written position still falls back rather than mixing a default with a saved axis`() {
        assertEquals(48 to 600, resolve(300, BubblePrefs.UNSET))
        assertEquals(48 to 600, resolve(BubblePrefs.UNSET, 300))
    }

    @Test
    fun `a position that fits is returned untouched`() {
        assertEquals(300 to 900, resolve(300, 900))
    }

    @Test
    fun `zero is a real position, not treated as unset`() {
        assertEquals(0 to 0, resolve(0, 0))
    }

    @Test
    fun `a position saved on a wider screen is pulled back into view`() {
        // Dragged to the right edge in landscape (2400 wide), then restored in portrait.
        assertEquals((screenW - size) to 900, resolve(2400 - size, 900))
    }

    @Test
    fun `a negative position is pulled back on screen`() {
        // FLAG_LAYOUT_NO_LIMITS lets a drag push the bubble past the edge.
        assertEquals(0 to 0, resolve(-200, -50))
    }

    @Test
    fun `the bubble is never placed with its body below the bottom edge`() {
        val (_, y) = resolve(100, screenH + 500)
        assertEquals(screenH - size, y)
    }

    @Test
    fun `a bubble larger than the screen is pinned to the origin instead of throwing`() {
        // coerceIn(0, negative) would throw; the degenerate case has to collapse to 0.
        assertEquals(0 to 0, BubblePrefs.resolvePosition(500, 500, 48, 600, 100, 100, 400))
    }
}
