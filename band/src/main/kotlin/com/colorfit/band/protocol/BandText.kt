package com.colorfit.band.protocol

/**
 * Turns a phone notification into the text the band shows, the way NoiseFit
 * Track does it (docs/band-protocol.md, "Notification text"):
 *
 *  1. [compose] — `title:body` (`d3/e.b`).
 *  2. [clean] — U+2005/U+2006 → space, first `": "` → `":"`, every `\p{C}` char
 *     dropped (`d3/b.l`).
 *  3. [replaceEmoji] — emoji from the SDK's table → `[emoji]` (`ca/h.b`).
 *  4. [encode] — UTF-8, cut to the byte budget with "…" appended (`x8/a.a`).
 */
object BandText {

    /** Byte budget when the band doesn't report one: the SDK's small-screen limit. */
    const val DEFAULT_MAX_BYTES = 92

    private val controlChars = Regex("\\p{C}")

    /**
     * `d3/e.b`: the title is put in front unless the body already starts with
     * it, it's just the app's name, or it's "WhatsApp".
     */
    fun compose(title: String?, body: String?, appLabel: String?): String {
        val t = title?.trim().orEmpty()
        val b = body?.trim().orEmpty()
        if (b.isEmpty()) return t
        val withTitle = t.isNotEmpty() && !b.startsWith(t) && t != appLabel && t != "WhatsApp"
        return if (withTitle) (if (t.endsWith(":")) t else "$t:") + b else b
    }

    fun clean(text: String): String =
        text.replace(' ', ' ').replace(' ', ' ').replaceFirst(": ", ":").replace(controlChars, "")

    /** `ca/h.b`: "📹" is spelled out first, then every emoji in the table becomes `[emoji]`. */
    fun replaceEmoji(text: String): String {
        val s = text.replace("📹", "[Video Camera]")
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            if (isEmoji(cp)) out.append("[emoji]") else out.appendCodePoint(cp)
            i += Character.charCount(cp)
        }
        return out.toString()
    }

    /** `x8/a.a`: drop characters from the end until it fits, then add "…" if anything went. */
    fun encode(text: String, maxBytes: Int): ByteArray {
        var s = text
        var cut = false
        while (s.isNotEmpty() && s.toByteArray(Charsets.UTF_8).size > maxBytes) {
            // Whole code points, so an emoji's surrogate pair is never split.
            s = s.substring(0, s.offsetByCodePoints(s.length, -1))
            cut = true
        }
        return (if (cut) s + "…" else s).toByteArray(Charsets.UTF_8)
    }

    /** The full pipeline for one notification. */
    fun forBand(title: String?, body: String?, appLabel: String?, maxBytes: Int = DEFAULT_MAX_BYTES): ByteArray =
        encode(replaceEmoji(clean(compose(title, body, appLabel))), maxBytes)

    private fun isEmoji(cp: Int): Boolean {
        // Binary search over [start, end] pairs.
        var lo = 0
        var hi = EMOJI_RANGES.size / 2 - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val start = EMOJI_RANGES[mid * 2]
            val end = EMOJI_RANGES[mid * 2 + 1]
            when {
                cp < start -> hi = mid - 1
                cp > end -> lo = mid + 1
                else -> return true
            }
        }
        return false
    }

    /**
     * Inclusive code-point ranges from `ca/h.f()` (606 calls, 1800 code points,
     * extracted mechanically). The SDK stores them as 16-bit `char`s, which also
     * catches some CJK compatibility characters by accident; exact code points
     * are used here.
     */
    private val EMOJI_RANGES = intArrayOf(
        0xA9, 0xA9, 0xAE, 0xAE, 0x200D, 0x200D, 0x203C, 0x203C, 0x2049, 0x2049, 0x20E3, 0x20E3,
        0x2122, 0x2122, 0x2139, 0x2139, 0x2194, 0x2199, 0x21A9, 0x21AA, 0x231A, 0x231B,
        0x2328, 0x2328, 0x2388, 0x2388, 0x23CF, 0x23CF, 0x23E9, 0x23F3, 0x23F8, 0x23FA,
        0x24C2, 0x24C2, 0x25AA, 0x25AB, 0x25B6, 0x25B6, 0x25C0, 0x25C0, 0x25FB, 0x25FE,
        0x2600, 0x2605, 0x2607, 0x2612, 0x2614, 0x2685, 0x2690, 0x2705, 0x2708, 0x2712,
        0x2714, 0x2714, 0x2716, 0x2716, 0x271D, 0x271D, 0x2721, 0x2721, 0x2728, 0x2728,
        0x2733, 0x2734, 0x2744, 0x2744, 0x2747, 0x2747, 0x274C, 0x274C, 0x274E, 0x274E,
        0x2753, 0x2755, 0x2757, 0x2757, 0x2763, 0x2767, 0x2795, 0x2797, 0x27A1, 0x27A1,
        0x27B0, 0x27B0, 0x27BF, 0x27BF, 0x2934, 0x2935, 0x2B05, 0x2B07, 0x2B1B, 0x2B1C,
        0x2B50, 0x2B50, 0x2B55, 0x2B55, 0x3030, 0x3030, 0x303D, 0x303D, 0x3297, 0x3297,
        0x3299, 0x3299, 0xFE0E, 0xFE0E, 0x1F000, 0x1F02B, 0x1F030, 0x1F093, 0x1F0A0, 0x1F0AE,
        0x1F0B1, 0x1F0BF, 0x1F0C1, 0x1F0CF, 0x1F0D1, 0x1F0F5, 0x1F12F, 0x1F12F, 0x1F170, 0x1F171,
        0x1F17E, 0x1F17F, 0x1F18E, 0x1F18E, 0x1F191, 0x1F19A, 0x1F1E6, 0x1F1FF, 0x1F201, 0x1F202,
        0x1F21A, 0x1F21A, 0x1F22F, 0x1F22F, 0x1F232, 0x1F23A, 0x1F250, 0x1F251, 0x1F260, 0x1F265,
        0x1F300, 0x1F53D, 0x1F546, 0x1F64F, 0x1F680, 0x1F6D4, 0x1F6E0, 0x1F6EC, 0x1F6F0, 0x1F6F9,
        0x1F7D5, 0x1F7D8, 0x1F910, 0x1F93A, 0x1F93C, 0x1F93E, 0x1F940, 0x1F945, 0x1F947, 0x1F970,
        0x1F973, 0x1F976, 0x1F97A, 0x1F97A, 0x1F97C, 0x1F9A2, 0x1F9B0, 0x1F9B9, 0x1F9C0, 0x1F9C2,
        0x1F9D0, 0x1F9FF, 0x1FA60, 0x1FA6D, 0xE007F, 0xE007F,
    )
}
