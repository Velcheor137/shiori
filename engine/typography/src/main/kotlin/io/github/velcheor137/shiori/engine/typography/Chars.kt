package io.github.velcheor137.shiori.engine.typography

internal object Chars {
    const val NBSP = ' '

    fun isDash(c: Char): Boolean =
        c == '-' || c == '‐' || c == '‑' || c == '‒' || c == '–' || c == '—' || c == '―'

    fun isSpace(c: Char): Boolean = c == ' ' || c == NBSP || c == ' ' || c == '\t'

    fun isTerminal(c: Char): Boolean = c == '.' || c == '?' || c == '!' || c == '…'

    fun isCyrillic(c: Char): Boolean = c in 'Ѐ'..'ӿ'

    /** Индекс последнего непробельного символа в диапазоне [from, until) или null. */
    fun lastNonSpace(text: String, from: Int, until: Int): Int? {
        var i = until - 1
        while (i >= from) {
            if (!isSpace(text[i])) return i
            i--
        }
        return null
    }

    fun isBlank(text: String): Boolean = text.all { isSpace(it) }

    fun trimSpaces(text: String): String = text.trim { isSpace(it) }

    /** Точка — часть многоточия из точек («...», «?..», «!..»). */
    fun isPartOfDotEllipsis(text: String, index: Int): Boolean {
        if (text[index] != '.') return false
        val prev = text.getOrNull(index - 1)
        val next = text.getOrNull(index + 1)
        return prev == '.' || next == '.' || prev == '?' || prev == '!'
    }

    /** Только буквы и цифры — инвариант безопасности нормализации (§9.4). */
    fun lettersAndDigits(text: String): String = buildString(text.length) {
        for (c in text) if (c.isLetterOrDigit()) append(c)
    }
}
