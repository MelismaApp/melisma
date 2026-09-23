package com.melisma.app.ui.background

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * A port of [Kawarp](https://github.com/better-lyrics/kawarp) (MIT), the renderer behind Spicy
 * Lyrics' animated background, with the settings Spicy Lyrics gives it.
 *
 * The pipeline is Kawarp's: the cover is reduced to 128×128 and put through eight Kawase blur
 * passes once per track; every frame, that blurred image is displaced by two octaves of simplex
 * noise, vignetted and saturated. On top of Kawarp, Spicy Lyrics' stylesheet applies
 * `saturate(2.5) brightness(0.65)`, which is folded into the same pass here.
 *
 * Kawarp does this in WebGL at full resolution. Here it runs on the CPU at a longer side of
 * [OUTPUT_LONG_SIDE] and is scaled up by the GPU — the image is blurred to a radius of a tenth of
 * its width before anything moves, so there is no detail a full-resolution render would add. The
 * warp is evaluated on a coarse grid and interpolated for the same reason: the noise it samples
 * varies by less than one feature across the whole screen.
 */
internal object Kawarp {

    const val SIZE = 128
    private const val BLUR_PASSES = 8
    private const val WARP_INTENSITY = 1f
    private const val SATURATION = 1.5f
    private const val CSS_SATURATE = 2.5f
    private const val CSS_BRIGHTNESS = 0.65f

    /** Kawarp's crossfade between covers, as Spicy Lyrics sets it after the first. */
    const val TRANSITION_MS = 1_000L

    /** The longer side of the rendered frame. */
    const val OUTPUT_LONG_SIDE = 128

    /** Warp grid nodes per axis. */
    private const val GRID = 17

    /**
     * The cover, reduced and blurred: [SIZE]×[SIZE] RGB, 0–1, row-major.
     *
     * Done once per cover and never per frame, as in Kawarp.
     */
    fun blurred(argb: IntArray, width: Int, height: Int): FloatArray {
        var read = downscale(argb, width, height)
        var write = FloatArray(read.size)
        for (pass in 0 until BLUR_PASSES) {
            kawasePass(read, write, pass + 0.5f)
            val swap = read
            read = write
            write = swap
        }
        return read
    }

    /**
     * One frame into [out], [outWidth]×[outHeight] opaque ARGB.
     *
     * [time] is Kawarp's accumulated time: seconds of playback, scaled by the animation speed.
     * [next] and [blend] crossfade to a new cover; [blend] is already eased.
     */
    fun render(
        out: IntArray,
        outWidth: Int,
        outHeight: Int,
        current: FloatArray,
        next: FloatArray?,
        blend: Float,
        time: Float,
    ) {
        val warpX = FloatArray(GRID * GRID)
        val warpY = FloatArray(GRID * GRID)
        warpGrid(time * 0.05f, warpX, warpY)

        val mixing = next != null && blend > 0f
        val rgb = FloatArray(3)
        val other = FloatArray(3)
        val step = GRID - 1

        for (y in 0 until outHeight) {
            val v = (y + 0.5f) / outHeight
            val gy = v * step
            val gy0 = min(gy.toInt(), step - 1)
            val ty = gy - gy0
            for (x in 0 until outWidth) {
                val u = (x + 0.5f) / outWidth
                val gx = u * step
                val gx0 = min(gx.toInt(), step - 1)
                val tx = gx - gx0

                val i00 = gy0 * GRID + gx0
                val dx = lerp2(warpX[i00], warpX[i00 + 1], warpX[i00 + GRID], warpX[i00 + GRID + 1], tx, ty)
                val dy = lerp2(warpY[i00], warpY[i00 + 1], warpY[i00 + GRID], warpY[i00 + GRID + 1], tx, ty)
                val su = (u + dx * WARP_INTENSITY).coerceIn(0f, 1f)
                val sv = (v + dy * WARP_INTENSITY).coerceIn(0f, 1f)

                sample(current, su * SIZE, sv * SIZE, rgb)
                if (mixing) {
                    sample(next!!, su * SIZE, sv * SIZE, other)
                    for (c in 0..2) rgb[c] += (other[c] - rgb[c]) * blend
                }

                out[y * outWidth + x] = finish(rgb, u - 0.5f, v - 0.5f)
            }
        }
    }

    /** The output shader, then the stylesheet's filters. */
    private fun finish(rgb: FloatArray, cx: Float, cy: Float): Int {
        val vignette = 1f - (cx * cx + cy * cy) * 0.3f
        var r = rgb[0] * vignette
        var g = rgb[1] * vignette
        var b = rgb[2] * vignette

        // Kawarp's saturation, around Rec. 601 luma, then clamped by the 8-bit canvas it writes.
        val gray = r * 0.299f + g * 0.587f + b * 0.114f
        r = (gray + (r - gray) * SATURATION).coerceIn(0f, 1f)
        g = (gray + (g - gray) * SATURATION).coerceIn(0f, 1f)
        b = (gray + (b - gray) * SATURATION).coerceIn(0f, 1f)

        // CSS saturate(), whose matrix is defined on Rec. 709 luma, then brightness().
        val s = CSS_SATURATE
        val r2 = (0.2126f + 0.7874f * s) * r + (0.7152f - 0.7152f * s) * g + (0.0722f - 0.0722f * s) * b
        val g2 = (0.2126f - 0.2126f * s) * r + (0.7152f + 0.2848f * s) * g + (0.0722f - 0.0722f * s) * b
        val b2 = (0.2126f - 0.2126f * s) * r + (0.7152f - 0.7152f * s) * g + (0.0722f + 0.9278f * s) * b

        return (0xFF shl 24) or
            (channel(r2) shl 16) or
            (channel(g2) shl 8) or
            channel(b2)
    }

    private fun channel(value: Float): Int =
        (value.coerceIn(0f, 1f) * CSS_BRIGHTNESS * 255f + 0.5f).toInt()

    /** Kawarp's domain warp, at the grid nodes. */
    private fun warpGrid(t: Float, outX: FloatArray, outY: FloatArray) {
        val step = GRID - 1
        for (gy in 0 until GRID) {
            val v = gy.toFloat() / step
            for (gx in 0 until GRID) {
                val u = gx.toFloat() / step
                val cx = u - 0.5f
                val cy = v - 0.5f
                val centerWeight = 1f - smoothstep(0f, 0.7f, sqrt(cx * cx + cy * cy))

                val n1 = snoise(u * 0.35f + t, v * 0.35f + t * 0.7f)
                val n2 = snoise(u * 0.35f - t * 0.8f + 50f, v * 0.35f + t * 0.5f + 50f)
                val n3 = snoise(u * 0.9f + t * 1.2f + 100f, v * 0.9f - t)
                val n4 = snoise(u * 0.9f - t, v * 0.9f + t * 1.1f + 100f)

                val index = gy * GRID + gx
                outX[index] = (n1 * 0.65f + n3 * 0.35f) * centerWeight
                outY[index] = (n2 * 0.65f + n4 * 0.35f) * centerWeight
            }
        }
    }

    /** Box-averages any image to [SIZE]×[SIZE]. */
    private fun downscale(argb: IntArray, width: Int, height: Int): FloatArray {
        val out = FloatArray(SIZE * SIZE * 3)
        for (y in 0 until SIZE) {
            val y0 = y * height / SIZE
            val y1 = max(y0 + 1, (y + 1) * height / SIZE)
            for (x in 0 until SIZE) {
                val x0 = x * width / SIZE
                val x1 = max(x0 + 1, (x + 1) * width / SIZE)
                var r = 0f
                var g = 0f
                var b = 0f
                for (sy in y0 until y1) {
                    for (sx in x0 until x1) {
                        val p = argb[sy * width + sx]
                        r += (p shr 16 and 0xFF)
                        g += (p shr 8 and 0xFF)
                        b += (p and 0xFF)
                    }
                }
                val n = 255f * (y1 - y0) * (x1 - x0)
                val i = (y * SIZE + x) * 3
                out[i] = r / n
                out[i + 1] = g / n
                out[i + 2] = b / n
            }
        }
        return out
    }

    /** Four diagonal taps at ±[offset] texels, averaged — Kawarp's blur shader. */
    private fun kawasePass(read: FloatArray, write: FloatArray, offset: Float) {
        val a = FloatArray(3)
        val sum = FloatArray(3)
        for (y in 0 until SIZE) {
            val py = y + 0.5f
            for (x in 0 until SIZE) {
                val px = x + 0.5f
                sum[0] = 0f
                sum[1] = 0f
                sum[2] = 0f
                sample(read, px - offset, py - offset, a); accumulate(sum, a)
                sample(read, px + offset, py - offset, a); accumulate(sum, a)
                sample(read, px - offset, py + offset, a); accumulate(sum, a)
                sample(read, px + offset, py + offset, a); accumulate(sum, a)
                val i = (y * SIZE + x) * 3
                write[i] = sum[0] * 0.25f
                write[i + 1] = sum[1] * 0.25f
                write[i + 2] = sum[2] * 0.25f
            }
        }
    }

    private fun accumulate(sum: FloatArray, a: FloatArray) {
        sum[0] += a[0]
        sum[1] += a[1]
        sum[2] += a[2]
    }

    /** Bilinear sample at texel coordinates (centres on the halves), clamped to the edge. */
    private fun sample(texture: FloatArray, px: Float, py: Float, out: FloatArray) {
        val fx = px - 0.5f
        val fy = py - 0.5f
        val x0f = floor(fx)
        val y0f = floor(fy)
        val tx = fx - x0f
        val ty = fy - y0f
        val x0 = x0f.toInt().coerceIn(0, SIZE - 1)
        val y0 = y0f.toInt().coerceIn(0, SIZE - 1)
        val x1 = (x0f.toInt() + 1).coerceIn(0, SIZE - 1)
        val y1 = (y0f.toInt() + 1).coerceIn(0, SIZE - 1)
        val i00 = (y0 * SIZE + x0) * 3
        val i10 = (y0 * SIZE + x1) * 3
        val i01 = (y1 * SIZE + x0) * 3
        val i11 = (y1 * SIZE + x1) * 3
        for (c in 0..2) {
            out[c] = lerp2(texture[i00 + c], texture[i10 + c], texture[i01 + c], texture[i11 + c], tx, ty)
        }
    }

    private fun lerp2(a: Float, b: Float, c: Float, d: Float, tx: Float, ty: Float): Float {
        val top = a + (b - a) * tx
        val bottom = c + (d - c) * tx
        return top + (bottom - top) * ty
    }

    private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /** Kawarp's crossfade easing. */
    fun ease(progress: Float): Float =
        0.5f - 0.5f * kotlin.math.cos(progress.coerceIn(0f, 1f) * Math.PI.toFloat())

    // ---- 2D simplex noise, as Kawarp's shader has it (Ashima Arts / Gustavson) ----

    internal fun snoise(vx: Float, vy: Float): Float {
        val cx = 0.211324865405187f
        val cy = 0.366025403784439f
        val cz = -0.577350269189626f
        val cw = 0.024390243902439f

        val skew = (vx + vy) * cy
        var ix = floor(vx + skew)
        var iy = floor(vy + skew)
        val unskew = (ix + iy) * cx
        val x0x = vx - ix + unskew
        val x0y = vy - iy + unskew

        val i1x = if (x0x > x0y) 1f else 0f
        val i1y = if (x0x > x0y) 0f else 1f

        val x12x = x0x + cx - i1x
        val x12y = x0y + cx - i1y
        val x12z = x0x + cz
        val x12w = x0y + cz

        ix = mod289(ix)
        iy = mod289(iy)
        val p0 = permute(permute(iy) + ix)
        val p1 = permute(permute(iy + i1y) + ix + i1x)
        val p2 = permute(permute(iy + 1f) + ix + 1f)

        var m0 = max(0.5f - (x0x * x0x + x0y * x0y), 0f)
        var m1 = max(0.5f - (x12x * x12x + x12y * x12y), 0f)
        var m2 = max(0.5f - (x12z * x12z + x12w * x12w), 0f)
        m0 *= m0; m0 *= m0
        m1 *= m1; m1 *= m1
        m2 *= m2; m2 *= m2

        val gx0 = 2f * fract(p0 * cw) - 1f
        val gx1 = 2f * fract(p1 * cw) - 1f
        val gx2 = 2f * fract(p2 * cw) - 1f
        val h0 = abs(gx0) - 0.5f
        val h1 = abs(gx1) - 0.5f
        val h2 = abs(gx2) - 0.5f
        val a0 = gx0 - floor(gx0 + 0.5f)
        val a1 = gx1 - floor(gx1 + 0.5f)
        val a2 = gx2 - floor(gx2 + 0.5f)

        m0 *= 1.79284291400159f - 0.85373472095314f * (a0 * a0 + h0 * h0)
        m1 *= 1.79284291400159f - 0.85373472095314f * (a1 * a1 + h1 * h1)
        m2 *= 1.79284291400159f - 0.85373472095314f * (a2 * a2 + h2 * h2)

        val g0 = a0 * x0x + h0 * x0y
        val g1 = a1 * x12x + h1 * x12y
        val g2 = a2 * x12z + h2 * x12w
        return 130f * (m0 * g0 + m1 * g1 + m2 * g2)
    }

    private fun mod289(x: Float): Float = x - floor(x * (1f / 289f)) * 289f

    private fun permute(x: Float): Float = mod289(((x * 34f) + 1f) * x)

    private fun fract(x: Float): Float = x - floor(x)
}
