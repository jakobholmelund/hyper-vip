package io.github.jakobholmelund.hypervip.match

import java.util.Locale

/** Plain-Kotlin number comparison, so it can be unit tested without Android. */
object PhoneNumbers {
    /** Trailing digits two differently formatted numbers must share before they count as the same. */
    private const val MIN_SUFFIX_MATCH = 7

    /** Digits with an optional leading '+'. A "00" international prefix becomes '+'. */
    fun normalize(raw: String?): String {
        if (raw == null) return ""
        val out = StringBuilder()
        for (c in raw) {
            if (c in '0'..'9') out.append(c)
            else if (c == '+' && out.isEmpty()) out.append(c)
        }
        return if (out.startsWith("00")) "+" + out.substring(2) else out.toString()
    }

    /**
     * True when [a] and [b] are the same number written differently, e.g. "+45 12 34 56 78" and
     * "12345678", or "+44 7700 900123" and "07700 900123". Short numbers must match exactly.
     */
    fun matches(a: String?, b: String?): Boolean {
        val x = comparable(a)
        val y = comparable(b)
        if (x.isEmpty() || y.isEmpty()) return false
        if (x == y) return true
        val overlap = minOf(x.length, y.length)
        if (overlap < MIN_SUFFIX_MATCH) return false
        return x.takeLast(overlap) == y.takeLast(overlap)
    }

    /** True for text such as "+45 12 34 56 78" that is a phone number rather than a name. */
    fun looksLikeNumber(text: CharSequence): Boolean =
        text.count { it in '0'..'9' } >= 3 &&
            text.all { it in '0'..'9' || it in " +-(). " || Character.getType(it) == Character.FORMAT.toInt() }

    /** Digits only, without the international '+' or a national trunk prefix ("0"). */
    private fun comparable(raw: String?): String {
        val n = normalize(raw)
        return if (n.startsWith("+")) n.substring(1) else n.trimStart('0')
    }
}

object Names {
    private val whitespace = Regex("\\s+")

    /** Case-insensitive, ignores extra whitespace and invisible direction marks some apps add. */
    fun normalize(name: CharSequence?): String {
        if (name == null) return ""
        val visible = buildString {
            for (c in name) if (Character.getType(c) != Character.FORMAT.toInt()) append(c)
        }
        return visible.trim().replace(whitespace, " ").lowercase(Locale.ROOT)
    }

    fun matches(a: CharSequence?, b: CharSequence?): Boolean {
        val x = normalize(a)
        return x.isNotEmpty() && x == normalize(b)
    }

    /** True when [text] contains [name] as whole words, e.g. "Incoming call from Mom" mentions "mom". */
    fun mentions(text: CharSequence?, name: CharSequence?): Boolean {
        val haystack = normalize(text)
        val needle = normalize(name)
        if (needle.isEmpty()) return false
        var from = 0
        while (true) {
            val i = haystack.indexOf(needle, from)
            if (i < 0) return false
            val before = haystack.getOrNull(i - 1)
            val after = haystack.getOrNull(i + needle.length)
            if ((before == null || !before.isLetterOrDigit()) && (after == null || !after.isLetterOrDigit())) return true
            from = i + 1
        }
    }
}
