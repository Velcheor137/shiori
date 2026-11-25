package io.github.velcheor137.shiori.engine.typography

/**
 * Результат нормализации абзаца.
 *
 * @property text текст для отображения.
 * @property offsets перевод позиций исходный ↔ отображаемый.
 * @property rules коды сработавших правил (D1, D2, D3a, D3b, T).
 * @property fallback true, если правки нарушили инвариант и показан исходный текст.
 */
data class NormalizedText(
    val text: String,
    val offsets: OffsetMap,
    val rules: List<String>,
    val fallback: Boolean,
) {
    companion object {
        fun identity(text: String, fallback: Boolean = false) =
            NormalizedText(text, OffsetMap.Identity, emptyList(), fallback)
    }
}

/**
 * Нормализация оформления одного абзаца (§9.4 плана).
 *
 * Все кавычки классифицируются по ИСХОДНОМУ тексту за один проход, поэтому «ёлочки», созданные для
 * мыслей, не могут стать репликами. Правила меняют только знаки препинания и пробелы: если буквы или
 * цифры после правок отличаются, возвращается исходный текст ([NormalizedText.fallback]).
 */
object ParagraphNormalizer {
    fun normalize(text: String, settings: NormalizationSettings): NormalizedText {
        if (!settings.anyEnabled || text.isEmpty()) return NormalizedText.identity(text)

        val analysis = QuoteScanner.analyze(text)
        val dialogue = if (settings.dialogues) DialogueRule.edits(text, analysis, settings) else emptyList()
        val converted = analysis.spans
            .filter { s -> s.isDouble && dialogue.any { it.start <= s.open && s.open < it.end } }
            .toSet()
        val thought = if (settings.thoughts) ThoughtRule.edits(text, analysis, converted) else emptyList()

        val resolved = EditSet.resolve(dialogue + thought)
        if (resolved.isEmpty()) return NormalizedText.identity(text)

        val (out, offsets) = EditSet.apply(text, resolved)
        if (Chars.lettersAndDigits(out) != Chars.lettersAndDigits(text)) {
            return NormalizedText.identity(text, fallback = true)
        }
        val rules = resolved.map { it.rule }.filter { it != "D" }.ifEmpty { listOf("D") }.distinct()
        return NormalizedText(out, offsets, rules, fallback = false)
    }
}
