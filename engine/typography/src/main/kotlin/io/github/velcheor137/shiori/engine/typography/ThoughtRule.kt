package io.github.velcheor137.shiori.engine.typography

import io.github.velcheor137.shiori.engine.typography.Chars.isCyrillic
import io.github.velcheor137.shiori.engine.typography.Chars.isDash
import io.github.velcheor137.shiori.engine.typography.Chars.isPartOfDotEllipsis
import io.github.velcheor137.shiori.engine.typography.Chars.isSpace
import io.github.velcheor137.shiori.engine.typography.EditSet.PRIORITY_THOUGHT as P

/**
 * Правило T «Мысли → ёлочки» (§9.5.4 плана): пара одинарных кавычек вокруг русского текста.
 * Внутри сохраняемых двойных кавычек используются „лапки“ (вложенные кавычки по русской норме).
 */
internal object ThoughtRule {
    fun edits(text: String, analysis: QuoteAnalysis, convertedDoubles: Set<QuoteSpan>): List<Edit> {
        val edits = ArrayList<Edit>()
        for (span in analysis.spans) {
            if (span.family != QuoteFamily.Single || !isThought(text, span)) continue
            val enclosing = analysis.enclosingDouble(span)
            val nested = enclosing != null && enclosing !in convertedDoubles
            val (open, close) = if (nested) "„" to "“" else "«" to "»"

            edits += Edit(span.open, span.open + 1, open, P, "T")
            val lastInner = span.close - 1
            val afterClose = text.getOrNull(span.close + 1)
            val movePeriod = text[lastInner] == '.' && !isPartOfDotEllipsis(text, lastInner) &&
                afterClose != ',' && !(afterClose != null && isDash(afterClose))
            edits += when {
                movePeriod && afterClose == '.' -> Edit(lastInner, span.close + 1, close, P, "T")
                movePeriod -> Edit(lastInner, span.close + 1, "$close.", P, "T")
                else -> Edit(span.close, span.close + 1, close, P, "T")
            }
        }
        return edits
    }

    private fun isThought(text: String, span: QuoteSpan): Boolean {
        if (span.close - span.open < 3) return false
        val before = text.getOrNull(span.open - 1)
        val first = text[span.open + 1]
        val last = text[span.close - 1]
        val after = text.getOrNull(span.close + 1)
        val leftOk = before == null || isSpace(before) || before in "(:«„“\"" || isDash(before)
        val firstOk = first.isLetter()
        val lastOk = last.isLetterOrDigit() || last in ".?!…,"
        val rightOk = after == null || isSpace(after) || after in ".,;:!?…»“”)" || isDash(after)
        if (!(leftOk && firstOk && lastOk && rightOk)) return false
        val inner = text.substring(span.open + 1, span.close)
        if (inner.none { isCyrillic(it) }) return false
        // Внутри не должно остаться одиночных кавычек, кроме апострофов в словах.
        for (i in inner.indices) {
            val c = inner[i]
            if (c == '\'' || c == '‘' || c == '’') {
                val p = inner.getOrNull(i - 1)
                val n = inner.getOrNull(i + 1)
                if (!(p != null && n != null && p.isLetter() && n.isLetter())) return false
            }
        }
        return true
    }
}
