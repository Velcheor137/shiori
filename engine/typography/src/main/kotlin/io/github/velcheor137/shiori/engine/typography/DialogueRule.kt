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
        val literary = settings.mode == NormalizationMode.Extended
        val leadDash = if (settings.nbspAroundDialogueDash) "—${Chars.NBSP}" else "— "
        if (!analysis.doubleBalanced) return if (literary) multiParagraphStart(text, leadDash) else emptyList()
        val tops = analysis.topLevelDouble()
        val q = tops.firstOrNull() ?: return emptyList()
        if (!isSpeechFamily(q.family, settings)) return emptyList()
        if (!Chars.isBlank(text.substring(0, q.open))) return emptyList()
        if (literary) replicaChain(text, tops, leadDash)?.let { return it }

        val innerEndIdx = lastNonSpace(text, q.open + 1, q.close) ?: return emptyList()
        val innerEnd = text[innerEndIdx]
        val inner = text.substring(q.open + 1, q.close)
        val afterTrim = trimSpaces(text.substring(q.close + 1))

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

        // L3 (литературный): реплика, сразу за которой идёт мысль в одинарных кавычках, — с новой строки.
        if (literary && tops.size == 1 && isTerminal(innerEnd)) {
            val rest = text.substring(q.close + 1)
            val lead = rest.length - rest.trimStart { Chars.isSpace(it) }.length
            val next = rest.getOrNull(lead)
            if (lead > 0 && (next == '\'' || next == '‘')) {
                edits += Edit(q.close, q.close + 1 + lead, "\n", P, "L3")
                return edits
            }
        }

        // D2: реплика + слова автора.
        val after = text.substring(q.close + 1)
        val sep = authorSeparator.find(after) ?: return emptyList()
        val hasComma = sep.groups[1] != null
        val endsWithStop = innerEnd in ",.?!…"
        // L1 (литературный): «Уходи» — прошептала она. Без запятой, но слова автора начинаются с глагола речи.
        if (!endsWithStop && !hasComma && !(literary && startsWithSpeechVerb(after.substring(sep.range.last + 1)))) return emptyList()
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
        QuoteFamily.Straight, QuoteFamily.Curly -> settings.straightQuotesAsSpeech || settings.mode == NormalizationMode.Extended
        QuoteFamily.Single -> false
    }

    /** L2: «А?» «Б!» — несколько реплик подряд без слов автора: каждая с новой строки через тире. */
    private fun replicaChain(text: String, tops: List<QuoteSpan>, leadDash: String): List<Edit>? {
        if (tops.size < 2 || tops.any { it.family != QuoteFamily.Guillemet }) return null
        for (i in tops.indices) {
            val end = lastNonSpace(text, tops[i].open + 1, tops[i].close) ?: return null
            if (!isTerminal(text[end])) return null
            val gapEnd = if (i + 1 < tops.size) tops[i + 1].open else text.length
            val gap = text.substring(tops[i].close + 1, gapEnd)
            if (!Chars.isBlank(gap)) return null
        }
        val edits = mutableListOf(Edit(tops[0].open, tops[0].open + 1, leadDash, P, "L2"))
        for (i in tops.indices) {
            if (i + 1 < tops.size) edits += Edit(tops[i].close, tops[i + 1].open + 1, "\n$leadDash", P, "L2")
            else edits += Edit(tops[i].close, tops[i].close + 1, "", P, "L2")
        }
        return edits
    }

    /** L4: абзац открывает многоабзацную реплику («… без закрывающей) — ставим тире. */
    private fun multiParagraphStart(text: String, leadDash: String): List<Edit> {
        val start = text.indexOfFirst { !Chars.isSpace(it) }
        if (start < 0 || text[start] != '«') return emptyList()
        if (text.count { it == '«' } != 1 || text.any { it == '»' || it == '"' || it == '“' || it == '”' || it == '„' }) return emptyList()
        if (text.getOrNull(start + 1)?.isLetter() != true) return emptyList()
        return listOf(Edit(start, start + 1, leadDash, P, "L4"))
    }

    private val speechVerbStems = listOf(
        "сказа", "ответи", "отвеча", "спроси", "спрашива", "крикну", "крича", "закрича", "прошепта", "шепта", "шепну",
        "пробормота", "бормота", "произне", "буркну", "заяви", "воскликну", "проговори", "повтори", "добави", "возрази",
        "подума", "дума", "отозва", "рявкну", "прорыча", "пискну", "выдохну", "хмыкну", "фыркну", "протяну", "перебил",
        "перебива", "поинтересова", "уточни", "предложи", "согласи", "отреза", "процеди", "простона", "всхлипну", "прошипе",
        "продолжи", "закончи", "позва", "окликну", "взмоли", "поясни", "объясни", "призна", "напомни", "предупреди",
        "приказа", "скомандова", "потребова", "вмеша", "сообщи", "огрызну", "броси", "выпали", "выкрикну", "промолви",
        "пропе", "ахну", "охну", "замети", "отмети", "усмехну", "хохотну", "засмея", "рассмея", "улыбну", "вздохну",
        "протараторил", "залепета", "пролепета", "простонал", "зашепта", "заорал", "завопил", "взвизгну", "прохрипе",
        "откликну", "поддакну", "передразни", "вставил", "отрапортова", "доложи", "подтверди", "мелькнул", "пронеслось",
    )

    private fun startsWithSpeechVerb(author: String): Boolean {
        val word = author.trimStart().takeWhile { it.isLetter() }
        return word.isNotEmpty() && word[0].isLowerCase() && speechVerbStems.any { word.startsWith(it) }
    }
}
