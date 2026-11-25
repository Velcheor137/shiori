package io.github.velcheor137.shiori.engine.typography

import io.github.velcheor137.shiori.engine.typography.Chars.isPartOfDotEllipsis
import io.github.velcheor137.shiori.engine.typography.Chars.isTerminal
import io.github.velcheor137.shiori.engine.typography.Chars.lastNonSpace
import io.github.velcheor137.shiori.engine.typography.Chars.trimSpaces
import io.github.velcheor137.shiori.engine.typography.EditSet.PRIORITY_DIALOGUE as P

/**
 * Правило D «Диалоги → тире», консервативный режим (§9.5.3 плана): D1, D2, D3a, D3b.
 * Применяется только к реплике в начале абзаца; встроенная речь после двоеточия (D4),
 * несколько реплик подряд (D5) и многоабзацные реплики (D6) не трогаются.
 */
internal object DialogueRule {
    private const val SP = "[ \\u00A0\\u202F]"
    private const val DASH = "[-‐‑‒–—―]"

    /** Разделитель «», — слова автора»: необязательная запятая, тире, пробел, буква. */
    private val authorSeparator = Regex("^$SP*(,)?$SP*$DASH$SP+(?=\\p{L})")

    /** Конец слов автора перед второй частью разорванной речи: «…, — «» или «…. — «». */
    private val authorEnd = Regex("([,.])$SP*$DASH$SP*$")

    /** Разорванная речь в одной паре кавычек: «А, — сказал он, — б». */
    private val innerBroken = Regex("[,?!…]$SP*$DASH$SP+\\p{Ll}.*?[,.]$SP*$DASH$SP+\\S")
    private val innerDash = Regex("(?<=[,.?!…])$SP*$DASH$SP+")

    fun edits(text: String, analysis: QuoteAnalysis, settings: NormalizationSettings): List<Edit> {
        if (!analysis.doubleBalanced) return emptyList()
        val tops = analysis.topLevelDouble()
        val q = tops.firstOrNull() ?: return emptyList()
        if (!isSpeechFamily(q.family, settings)) return emptyList()
        if (!Chars.isBlank(text.substring(0, q.open))) return emptyList()

        val innerEndIdx = lastNonSpace(text, q.open + 1, q.close) ?: return emptyList()
        val innerEnd = text[innerEndIdx]
        val inner = text.substring(q.open + 1, q.close)
        val afterTrim = trimSpaces(text.substring(q.close + 1))

        val leadDash = if (settings.nbspAroundDialogueDash) "—${Chars.NBSP}" else "— "
        val midDash = if (settings.nbspAroundDialogueDash) "${Chars.NBSP}— " else " — "
        val edits = mutableListOf(Edit(q.open, q.open + 1, leadDash, P, "D"))

        // D1 и D3b: одна пара кавычек на весь абзац.
        if (tops.size == 1 && (afterTrim.isEmpty() || afterTrim == ".")) {
            val broken = innerBroken.containsMatchIn(inner)
            if (!broken && !(isTerminal(innerEnd) || afterTrim == ".")) return emptyList()
            val rule = if (broken) "D3b" else "D1"
            edits += Edit(q.close, q.close + 1, "", P, rule)
            if (afterTrim == "." && isTerminal(innerEnd)) {
                val dot = text.indexOf('.', q.close + 1)
                edits += Edit(dot, dot + 1, "", P, rule)
            }
            if (broken) {
                innerDash.findAll(inner).forEach { m ->
                    edits += Edit(q.open + 1 + m.range.first, q.open + 1 + m.range.last + 1, midDash, P, rule)
                }
            }
            return edits
        }

        // D2: реплика + слова автора.
        val after = text.substring(q.close + 1)
        val sep = authorSeparator.find(after) ?: return emptyList()
        val hasComma = sep.groups[1] != null
        val endsWithStop = innerEnd in ",.?!…"
        if (!endsWithStop && !hasComma) return emptyList()
        if (innerEnd == '.' && !isPartOfDotEllipsis(text, innerEndIdx)) {
            edits += Edit(innerEndIdx, innerEndIdx + 1, ",", P, "D2")
        }
        val sepEnd = q.close + 1 + sep.range.last + 1
        edits += Edit(q.close, sepEnd, (if (endsWithStop) "" else ",") + midDash, P, "D2")

        // D3a: вторая часть разорванной речи в отдельной паре кавычек.
        val q2 = tops.getOrNull(1)
        if (q2 != null && tops.size == 2 && q2.open >= sepEnd) {
            val between = text.substring(sepEnd, q2.open)
            val m = authorEnd.find(between)
            val restTrim = trimSpaces(text.substring(q2.close + 1))
            val inner2End = lastNonSpace(text, q2.open + 1, q2.close)
            if (m != null && inner2End != null && (restTrim.isEmpty() || restTrim == ".")) {
                val punct = sepEnd + m.range.first
                edits += Edit(punct + 1, q2.open + 1, midDash, P, "D3a")
                edits += Edit(q2.close, q2.close + 1, "", P, "D3a")
                if (restTrim == "." && isTerminal(text[inner2End])) {
                    val dot = text.indexOf('.', q2.close + 1)
                    edits += Edit(dot, dot + 1, "", P, "D3a")
                }
            }
        }
        return edits
    }

    private fun isSpeechFamily(family: QuoteFamily, settings: NormalizationSettings): Boolean = when (family) {
        QuoteFamily.Guillemet, QuoteFamily.Low -> true
        QuoteFamily.Straight, QuoteFamily.Curly -> settings.straightQuotesAsSpeech
        QuoteFamily.Single -> false
    }
}
