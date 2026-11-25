package io.github.velcheor137.shiori.engine.typography

import io.github.velcheor137.shiori.engine.typography.Chars.isDash
import io.github.velcheor137.shiori.engine.typography.Chars.isSpace

internal enum class QuoteFamily(val isDouble: Boolean) {
    Guillemet(true), // « »
    Low(true), // „ “
    Curly(true), // “ ”
    Straight(true), // " "
    Single(false), // ' ' и ‘ ’
}

/** Спаренные кавычки: индексы открывающей и закрывающей в тексте абзаца. */
internal data class QuoteSpan(val open: Int, val close: Int, val family: QuoteFamily) {
    val isDouble: Boolean get() = family.isDouble
}

internal class QuoteAnalysis(
    val spans: List<QuoteSpan>,
    /** Все двойные кавычки спарены и среди них нет неоднозначных. */
    val doubleBalanced: Boolean,
) {
    /** Двойные пары, не вложенные в другие двойные пары, по порядку. */
    fun topLevelDouble(): List<QuoteSpan> = spans
        .filter { s -> s.isDouble && spans.none { o -> o !== s && o.isDouble && o.open < s.open && o.close > s.close } }
        .sortedBy { it.open }

    /** Ближайшая двойная пара, внутри которой лежит [span]. */
    fun enclosingDouble(span: QuoteSpan): QuoteSpan? = spans
        .filter { it.isDouble && it.open < span.open && it.close > span.close }
        .maxByOrNull { it.open }
}

/**
 * Поиск и спаривание кавычек в одном абзаце (§9.5.1–9.5.2 плана).
 * Апострофы внутри слов, штрихи после цифр и сокращения вида '90 кавычками не считаются.
 */
internal object QuoteScanner {
    private data class Open(val index: Int, val family: QuoteFamily)

    fun analyze(text: String): QuoteAnalysis {
        val stack = ArrayList<Open>()
        val spans = ArrayList<QuoteSpan>()
        var balanced = true

        fun hasOpen(family: QuoteFamily) = stack.any { it.family == family }

        /** Закрывает ближайшую открытую кавычку семейства; false — если пары нет или пришлось бросить двойные. */
        fun close(index: Int, family: QuoteFamily): Boolean {
            val at = stack.indexOfLast { it.family == family }
            if (at < 0) return false
            var clean = true
            while (stack.size - 1 > at) {
                if (stack.removeAt(stack.size - 1).family.isDouble) clean = false
            }
            val open = stack.removeAt(at)
            spans += QuoteSpan(open.index, index, family)
            return clean
        }

        for (i in text.indices) {
            val prev = text.getOrNull(i - 1)
            val next = text.getOrNull(i + 1)
            when (val c = text[i]) {
                '«' -> stack += Open(i, QuoteFamily.Guillemet)
                '»' -> if (!close(i, QuoteFamily.Guillemet)) balanced = false
                '„' -> stack += Open(i, QuoteFamily.Low)
                '“' -> if (hasOpen(QuoteFamily.Low)) {
                    if (!close(i, QuoteFamily.Low)) balanced = false
                } else {
                    stack += Open(i, QuoteFamily.Curly)
                }
                '”' -> when {
                    hasOpen(QuoteFamily.Curly) -> if (!close(i, QuoteFamily.Curly)) balanced = false
                    hasOpen(QuoteFamily.Low) -> if (!close(i, QuoteFamily.Low)) balanced = false
                    else -> balanced = false
                }
                '"' -> {
                    if (prev != null && prev.isDigit()) continue // дюймы: 10"
                    when (role(prev, next)) {
                        Role.Open -> stack += Open(i, QuoteFamily.Straight)
                        Role.Close -> if (!close(i, QuoteFamily.Straight)) balanced = false
                        Role.Ambiguous -> {
                            balanced = false
                            if (hasOpen(QuoteFamily.Straight)) close(i, QuoteFamily.Straight) else stack += Open(i, QuoteFamily.Straight)
                        }
                    }
                }
                '\'', '‘', '’' -> {
                    if (prev != null && next != null && prev.isLetter() && next.isLetter()) continue // апостроф: д'Артаньян
                    if (prev != null && prev.isDigit()) continue // штрих: 5'
                    if (c != '‘' && next != null && next.isDigit() && (prev == null || isSpace(prev))) continue // '90-е
                    when (c) {
                        '‘' -> stack += Open(i, QuoteFamily.Single)
                        '’' -> if (hasOpen(QuoteFamily.Single)) close(i, QuoteFamily.Single)
                        else -> {
                            val r = role(prev, next)
                            if (hasOpen(QuoteFamily.Single) && r != Role.Open) {
                                close(i, QuoteFamily.Single)
                            } else if (r == Role.Open) {
                                stack += Open(i, QuoteFamily.Single)
                            }
                        }
                    }
                }
            }
        }
        if (stack.any { it.family.isDouble }) balanced = false
        return QuoteAnalysis(spans, balanced)
    }

    private enum class Role { Open, Close, Ambiguous }

    private fun role(prev: Char?, next: Char?): Role {
        val leftBoundary = prev == null || isSpace(prev) || prev in "([{:«„“\"'" || isDash(prev)
        val rightBoundary = next == null || isSpace(next) || next in ".,;:!?…)]}»“”\"'" || isDash(next)
        return when {
            leftBoundary && !rightBoundary -> Role.Open
            !leftBoundary && rightBoundary -> Role.Close
            else -> Role.Ambiguous
        }
    }
}
