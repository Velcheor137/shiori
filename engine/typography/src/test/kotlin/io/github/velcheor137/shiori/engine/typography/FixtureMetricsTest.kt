package io.github.velcheor137.shiori.engine.typography

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Критерий SPIKE-TYPO-001 (§9.7 плана): консервативный режим — 0 ложных преобразований на негативных
 * фикстурах и не менее 90 % верных результатов на позитивных. Отчёт пишется в build/reports/typography.
 */
class FixtureMetricsTest {
    private val fixtures = Fixtures.all()

    @Test
    fun fixtureSetIsLargeEnough() {
        assertTrue(fixtures.size >= 80, "Ожидалось не меньше 80 фикстур, есть ${fixtures.size}")
    }

    @Test
    fun negativeFixturesAreNeverChanged() {
        val failures = fixtures.filter { it.kind == Fixture.Kind.Negative }.mapNotNull { f ->
            val actual = ParagraphNormalizer.normalize(f.input, f.settings).text
            if (actual == f.expected) null else describe(f, actual)
        }
        if (failures.isNotEmpty()) fail("Ложные преобразования (${failures.size}):\n" + failures.joinToString("\n"))
    }

    @Test
    fun positiveAccuracyAndReport() {
        val results = fixtures.map { f -> f to ParagraphNormalizer.normalize(f.input, f.settings) }
        val positives = results.filter { it.first.kind == Fixture.Kind.Positive }
        val passed = positives.count { (f, r) -> r.text == f.expected }
        val accuracy = passed.toDouble() / positives.size
        val negatives = results.filter { it.first.kind == Fixture.Kind.Negative }
        val falsePositives = negatives.count { (f, r) -> r.text != f.expected }
        val fallbacks = results.count { it.second.fallback }

        val report = buildString {
            appendLine("SPIKE-TYPO-001: метрики нормализации (консервативный режим)")
            appendLine("Фикстур всего: ${fixtures.size}")
            appendLine("Позитивные: $passed / ${positives.size} (${"%.1f".format(accuracy * 100)} %)")
            appendLine("Негативные: ложных преобразований $falsePositives / ${negatives.size}")
            appendLine("Откатов по инварианту «буквы и цифры»: $fallbacks")
            appendLine("Сработавшие правила: " + results.flatMap { it.second.rules }.groupingBy { it }.eachCount().toSortedMap())
            appendLine()
            appendLine("Непрошедшие позитивные:")
            positives.filter { (f, r) -> r.text != f.expected }.forEach { (f, r) -> appendLine(describe(f, r.text)) }
            appendLine()
            appendLine("Известные неоднозначности (не входят в метрики):")
            results.filter { it.first.kind == Fixture.Kind.Known }.forEach { (f, r) ->
                val mark = if (r.text == f.expected) "совпадает с желаемым" else "отличается от желаемого"
                appendLine("${f.id} [$mark]")
                appendLine("  вход:     ${Fixtures.encode(f.input)}")
                appendLine("  желаемое: ${Fixtures.encode(f.expected)}")
                appendLine("  факт:     ${Fixtures.encode(r.text)}")
            }
        }
        System.getProperty("shiori.reportDir")?.let { dir ->
            File(dir).apply { mkdirs() }.resolve("metrics.txt").writeText(report)
        }
        println(report)
        assertTrue(accuracy >= 0.9, "Точность на позитивных ${"%.1f".format(accuracy * 100)} % < 90 %")
    }

    private fun describe(f: Fixture, actual: String) = buildString {
        append("${f.id} (${f.file})\n")
        append("  вход:     ${Fixtures.encode(f.input)}\n")
        append("  ожидание: ${Fixtures.encode(f.expected)}\n")
        append("  факт:     ${Fixtures.encode(actual)}")
    }
}
