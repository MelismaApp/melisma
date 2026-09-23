package com.melisma.app.ui.background

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The port of Kawarp, Spicy Lyrics' background renderer.
 *
 * Reported as "Living looks different from Spicy Lyrics". Against the same cover — QUEEN by Kanaria,
 * blue at the edges with a grey figure and a red rose in the middle — Spicy's background sampled a
 * deep blue, rgb(0–19, 3–18, 166), and the app's a muddy brown. The old renderer over-scaled the
 * cover 2.6× so its edges were pushed off-screen, leaving the grey and red middle to average into
 * brown. Kawarp shows the whole cover, blurred, and the port sampled rgb(0–21, 0–13, 166).
 */
class KawarpTest {

    private fun image(size: Int, pixel: (x: Int, y: Int) -> Int) =
        IntArray(size * size) { pixel(it % size, it / size) }

    private fun rgb(p: Int) = Triple(p shr 16 and 255, p shr 8 and 255, p and 255)

    @Test
    fun `a cover with blue edges and a grey middle stays blue, which is the report`() {
        // The shape of QUEEN: blue all round, a grey figure in the centre, a red spot in it.
        val size = 256
        val cover = image(size) { x, y ->
            val dx = x - size / 2
            val dy = y - size / 2
            when {
                dx * dx + dy * dy < 20 * 20 -> 0xFFD0203A.toInt()
                abs(dx) < size / 4 && abs(dy) < size / 4 -> 0xFF8C8C96.toInt()
                else -> 0xFF1E28C8.toInt()
            }
        }
        val field = Kawarp.blurred(cover, size, size)
        val out = IntArray(72 * 128)
        Kawarp.render(out, 72, 128, field, null, 0f, 0f)

        // Every corner, where the old renderer put the grey middle.
        for (p in listOf(out[0], out[71], out[127 * 72], out[127 * 72 + 71])) {
            val (r, g, b) = rgb(p)
            assertTrue("corner should be blue, was ($r,$g,$b)", b > 3 * maxOf(r, g, 1))
        }
    }

    @Test
    fun `a pure blue cover comes out as the stylesheet's saturated, darkened blue`() {
        // Kawarp's saturation and Spicy's saturate(2.5) push blue to full, brightness(0.65) takes
        // it to 166 — the exact value Spicy's background measured at.
        val field = Kawarp.blurred(image(64) { _, _ -> 0xFF0000FF.toInt() }, 64, 64)
        val out = IntArray(16 * 16)
        Kawarp.render(out, 16, 16, field, null, 0f, 0f)
        assertEquals(Triple(0, 0, 166), rgb(out[8 * 16 + 8]))
    }

    @Test
    fun `blurring preserves a flat colour`() {
        val grey = 0xFF808080.toInt()
        val field = Kawarp.blurred(image(128) { _, _ -> grey }, 128, 128)
        assertTrue(field.all { abs(it - 128f / 255f) < 1e-4f })
    }

    @Test
    fun `the crossfade runs from the old cover to the new`() {
        val red = Kawarp.blurred(image(32) { _, _ -> 0xFFFF0000.toInt() }, 32, 32)
        val blue = Kawarp.blurred(image(32) { _, _ -> 0xFF0000FF.toInt() }, 32, 32)
        val start = IntArray(4)
        val end = IntArray(4)
        val alone = IntArray(4)
        Kawarp.render(start, 2, 2, red, blue, 0f, 3f)
        Kawarp.render(end, 2, 2, red, blue, 1f, 3f)
        Kawarp.render(alone, 2, 2, blue, null, 0f, 3f)
        assertTrue(rgb(start[0]).first > rgb(start[0]).third)
        assertEquals(alone.toList(), end.toList())
    }

    @Test
    fun `the easing is Kawarp's cosine`() {
        assertEquals(0f, Kawarp.ease(0f), 1e-6f)
        assertEquals(0.5f, Kawarp.ease(0.5f), 1e-6f)
        assertEquals(1f, Kawarp.ease(1f), 1e-6f)
    }

    @Test
    fun `the noise is the shader's`() {
        // Zero at the lattice origin by construction, and bounded, like the GLSL original.
        assertEquals(0f, Kawarp.snoise(0f, 0f), 1e-6f)
        var peak = 0f
        for (i in 0 until 400) {
            val v = Kawarp.snoise(i * 0.137f, i * 0.071f + 3f)
            peak = maxOf(peak, abs(v))
        }
        assertTrue("simplex noise should stay within ±1, peaked at $peak", peak <= 1.01f)
        assertTrue("and should actually vary", peak > 0.3f)
    }

    @Test
    fun `time moves the picture`() {
        val size = 64
        val cover = image(size) { x, _ -> if (x < size / 2) 0xFFFF0000.toInt() else 0xFF0000FF.toInt() }
        val field = Kawarp.blurred(cover, size, size)
        val before = IntArray(32 * 32)
        val after = IntArray(32 * 32)
        Kawarp.render(before, 32, 32, field, null, 0f, 0f)
        Kawarp.render(after, 32, 32, field, null, 0f, 60f)
        assertTrue(before.indices.any { before[it] != after[it] })
    }
}
