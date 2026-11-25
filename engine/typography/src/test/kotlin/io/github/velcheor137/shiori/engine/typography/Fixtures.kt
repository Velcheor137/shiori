package io.github.velcheor137.shiori.engine.typography

data class Fixture(
    val id: String,
    val file: String,
    val settings: NormalizationSettings,
    val input: String,
    val expected: String,
) {
    val kind: Kind = when {
        id.startsWith("NEG-") -> Kind.Negative
        id.startsWith("KNOWN-") -> Kind.Known
        else -> Kind.Positive
    }

    enum class Kind { Positive, Negative, Known }
}

object Fixtures {
    private const val NBSP_MARK = '⍽'
    private val files = listOf("dialogue.txt", "thoughts.txt", "negative.txt", "known.txt", "literary.txt")

    fun all(): List<Fixture> = files.flatMap { load(it) }

    fun load(file: String): List<Fixture> {
        val stream = Fixtures::class.java.getResourceAsStream("/fixtures/$file")
            ?: error("Fixture file not found: $file")
        val lines = stream.bufferedReader(Charsets.UTF_8).readLines()
        val result = ArrayList<Fixture>()
        var header: String? = null
        var input: String? = null
        for ((n, line) in lines.withIndex()) {
            when {
                line.startsWith("### ") -> {
                    check(header == null) { "$file:${n + 1}: header without body" }
                    header = line.removePrefix("### ")
                }
                line.startsWith("<<< ") || line == "<<<" -> input = line.removePrefix("<<<").removePrefix(" ")
                line.startsWith(">>> ") || line == ">>>" -> {
                    val h = checkNotNull(header) { "$file:${n + 1}: expected without header" }
                    val i = checkNotNull(input) { "$file:${n + 1}: expected without input" }
                    result += parse(file, h, decode(i), decode(line.removePrefix(">>>").removePrefix(" ")))
                    header = null
                    input = null
                }
            }
        }
        check(header == null) { "$file: unterminated fixture $header" }
        val duplicates = result.groupBy { it.id }.filterValues { it.size > 1 }.keys
        check(duplicates.isEmpty()) { "$file: duplicate ids $duplicates" }
        return result
    }

    private fun decode(s: String) = s.replace(NBSP_MARK, Chars.NBSP).replace('⏎', '\n')

    fun encode(s: String) = s.replace(Chars.NBSP, NBSP_MARK).replace('\n', '⏎')

    private fun parse(file: String, header: String, input: String, expected: String): Fixture {
        val parts = header.split("|").map { it.trim() }
        val id = parts.first()
        val attrs = parts.drop(1).associate { p ->
            val (k, v) = p.split("=", limit = 2).let { it[0].trim() to it.getOrElse(1) { "" }.trim() }
            k to v.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        }
        val rules = attrs["rules"].orEmpty()
        val opts = attrs["opts"].orEmpty()
        val settings = NormalizationSettings(
            dialogues = "dialogues" in rules,
            thoughts = "thoughts" in rules,
            straightQuotesAsSpeech = "straight" in opts,
            nbspAroundDialogueDash = "nonbsp" !in opts,
            mode = if ("literary" in opts) NormalizationMode.Extended else NormalizationMode.Conservative,
        )
        return Fixture(id, file, settings, input, expected)
    }
}
