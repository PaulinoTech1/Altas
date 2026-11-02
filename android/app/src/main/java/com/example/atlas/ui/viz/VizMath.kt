package com.example.atlas.ui.viz

/** Pure helpers shared by the Atlas visualizations (no Android or Compose types, so they are unit tested on the JVM). */
object VizMath {
    /** Linear position of [value] in [min]..[max] as 0..1, clamped. */
    fun fraction(value: Float, min: Float, max: Float): Float =
        if (max <= min) 0f else ((value - min) / (max - min)).coerceIn(0f, 1f)

    /**
     * A stable 32-bit hash (FNV-1a) of [key]. String.hashCode is also stable, but FNV spreads short keys like MAC
     * addresses that differ only in the last octet much better across hues and angles.
     */
    fun stableHash(key: String): Int {
        var hash = 0x811c9dc5.toInt()
        for (c in key) { hash = hash xor c.code; hash *= 0x01000193 }
        return hash
    }

    /** Stable hue in degrees (0 until 360) for a key; the same AP or device always gets the same color. */
    fun stableHue(key: String): Float = ((stableHash(key).toLong() and 0xffffffffL) % 3600L) / 10f

    /** Stable unit fraction (0 until 1) for a key, e.g. a radar angle. */
    fun stableUnit(key: String): Float = ((stableHash(key).toLong() and 0xffffffffL) % 100_000L) / 100_000f

    /** HSL (hue degrees, saturation and lightness 0..1) to packed 0xRRGGBB, so colors need no experimental Compose API. */
    fun hslToRgb(hue: Float, saturation: Float, lightness: Float): Int {
        val c = (1f - kotlin.math.abs(2f * lightness - 1f)) * saturation
        val h = ((hue % 360f) + 360f) % 360f / 60f
        val x = c * (1f - kotlin.math.abs(h % 2f - 1f))
        // Plain branches instead of a Triple so this stays allocation-free when called from a draw pass.
        val sector = h.toInt()
        val r = if (sector == 0 || sector == 5) c else if (sector == 1 || sector == 4) x else 0f
        val g = if (sector == 1 || sector == 2) c else if (sector == 0 || sector == 3) x else 0f
        val b = if (sector == 3 || sector == 4) c else if (sector == 2 || sector == 5) x else 0f
        val m = lightness - c / 2f
        return (channel(r, m) shl 16) or (channel(g, m) shl 8) or channel(b, m)
    }

    private fun channel(v: Float, m: Float) = ((v + m) * 255f + 0.5f).toInt().coerceIn(0, 255)
}
