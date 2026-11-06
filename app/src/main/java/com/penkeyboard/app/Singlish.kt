package com.penkeyboard.app

/**
 * Singlish (Sinhala written in English letters) → Sinhala Unicode.
 * "mama gedara yanawa" → "මම ගෙදර යනව", "kohomada" → "කොහොමද".
 * Lowercase t/d/n/l are the common sounds (ත ද න ල); capitals give ට ඩ ණ ළ.
 */
object Singlish {

    // Longest first. Case-sensitive keys are tried before lowercase ones.
    private val consonants = listOf(
        "chh" to "ඡ", "Sh" to "ෂ", "kh" to "ඛ", "gh" to "ඝ", "ch" to "ච", "jh" to "ඣ",
        "Th" to "ඨ", "Dh" to "ඪ", "th" to "ත", "dh" to "ද", "ph" to "ඵ", "bh" to "භ",
        "sh" to "ශ", "ny" to "ඤ", "gn" to "ඥ",
        "T" to "ට", "D" to "ඩ", "N" to "ණ", "L" to "ළ",
        "k" to "ක", "g" to "ග", "c" to "ච", "j" to "ජ", "t" to "ත", "d" to "ද",
        "n" to "න", "p" to "ප", "b" to "බ", "m" to "ම", "y" to "ය", "r" to "ර",
        "l" to "ල", "w" to "ව", "v" to "ව", "s" to "ස", "h" to "හ", "f" to "ෆ",
        "z" to "ස", "q" to "ක"
    )

    // vowel → (sign after a consonant, standalone letter)
    private val vowels = listOf(
        "aae" to ("ෑ" to "ඈ"), "aa" to ("ා" to "ආ"), "ae" to ("ැ" to "ඇ"),
        "ai" to ("ෛ" to "ඓ"), "au" to ("ෞ" to "ඖ"), "a" to ("" to "අ"),
        "ii" to ("ී" to "ඊ"), "i" to ("ි" to "ඉ"),
        "ee" to ("ේ" to "ඒ"), "e" to ("ෙ" to "එ"),
        "uu" to ("ූ" to "ඌ"), "u" to ("ු" to "උ"),
        "oo" to ("ෝ" to "ඕ"), "o" to ("ො" to "ඔ")
    )

    private const val HAL = "්"

    /** Only plain English letters (and spaces/punctuation) can be converted. */
    fun canConvert(text: String) =
        text.any { it in 'a'..'z' || it in 'A'..'Z' } &&
            text.all { it in 'a'..'z' || it in 'A'..'Z' || it == ' ' || it in ".,?!'-" }

    fun convert(input: String): String {
        // "Mama" at the start of a sentence means "mama", not retroflex letters.
        val text = input.split(' ').joinToString(" ") { w ->
            if (w.length > 1 && w[0].isUpperCase() && w.substring(1).none { it.isUpperCase() })
                w[0].lowercase() + w.substring(1) else w
        }
        val sb = StringBuilder()
        var i = 0
        while (i < text.length) {
            val c = match(consonants, text, i)
            if (c != null) {
                sb.append(c.second)
                i += c.first.length
                val v = match(vowels, text, i)
                if (v != null) { sb.append(v.second.first); i += v.first.length }
                else sb.append(HAL)
                continue
            }
            val v = match(vowels, text, i)
            if (v != null) { sb.append(v.second.second); i += v.first.length; continue }
            sb.append(text[i]); i++
        }
        return sb.toString()
    }

    private fun <T> match(table: List<Pair<String, T>>, s: String, at: Int): Pair<String, T>? {
        for (e in table) if (s.startsWith(e.first, at)) return e
        val lower = s.lowercase()
        for (e in table) if (e.first == e.first.lowercase() && lower.startsWith(e.first, at)) return e
        return null
    }
}
