package io.github.velcheor137.shiori.engine.ranges

import java.math.BigDecimal

/**
 * Диапазоны глав для загрузки (§10.8 плана):
 * `1-100`, `120-150, 155, 160-170`, `12.5`, `т3` / `v3` (весь том), `т2:5-10`, `т2:5`, `все` / `all`.
 * Номера сравниваются как десятичные числа; выбор идёт по реальному списку глав перевода.
 */
object ChapterRanges {
    sealed interface Item {
        data object All : Item
        data class Range(val volume: BigDecimal?, val from: BigDecimal, val to: BigDecimal) : Item
        data class Volume(val volume: BigDecimal) : Item
    }

    data class ParseError(val position: Int, val message: String)

    data class Parsed(val items: List<Item>, val errors: List<ParseError>)

    /** Глава каталога: том, номер (строки из API) и произвольный ключ. */
    data class Entry<K>(val key: K, val volume: String, val number: String)

    data class Resolved<K>(
        val selected: List<K>,
        /** Номера из запроса, которых нет в этом переводе (только для одиночных номеров). */
        val missing: List<String>,
        /** Номера, встречающиеся в нескольких томах, если том не указан. */
        val ambiguous: List<String>,
    )

    fun parse(input: String): Parsed {
        val items = ArrayList<Item>()
        val errors = ArrayList<ParseError>()
        var pos = 0
        for (rawPart in input.split(',', ';')) {
            val part = rawPart.trim()
            val start = pos + rawPart.indexOf(part).coerceAtLeast(0)
            pos += rawPart.length + 1
            if (part.isEmpty()) continue
            val lower = part.lowercase()
            if (lower == "все" || lower == "all") {
                items += Item.All
                continue
            }
            val volMatch = Regex("^[тvТV]\\s*(\\d+(?:[.,]\\d+)?)\\s*(?::\\s*(.+))?$").find(part)
            if (volMatch != null) {
                val vol = num(volMatch.groupValues[1])!!
                val rest = volMatch.groupValues[2]
                if (rest.isBlank()) {
                    items += Item.Volume(vol)
                } else {
                    range(rest, vol)?.let { items += it } ?: errors.add(ParseError(start, "«$part»: ожидалось «т2:5» или «т2:5-10»"))
                }
                continue
            }
            range(part, null)?.let { items += it } ?: errors.add(ParseError(start, "«$part»: не понял, пример: 1-100, 155, т3"))
        }
        return Parsed(items, errors)
    }

    private fun range(text: String, volume: BigDecimal?): Item.Range? {
        val m = Regex("^(\\d+(?:[.,]\\d+)?)\\s*(?:[-‐‑‒–—―]\\s*(\\d+(?:[.,]\\d+)?))?$").find(text.trim()) ?: return null
        val a = num(m.groupValues[1]) ?: return null
        val b = m.groupValues[2].takeIf { it.isNotEmpty() }?.let(::num) ?: a
        return Item.Range(volume, minOf(a, b), maxOf(a, b))
    }

    private fun num(s: String): BigDecimal? = s.replace(',', '.').toBigDecimalOrNull()

    fun <K> resolve(parsed: Parsed, entries: List<Entry<K>>): Resolved<K> {
        val chosen = LinkedHashSet<K>()
        val missing = ArrayList<String>()
        val ambiguous = LinkedHashSet<String>()
        for (item in parsed.items) {
            when (item) {
                Item.All -> entries.forEach { chosen += it.key }
                is Item.Volume -> entries.filter { num(it.volume)?.compareTo(item.volume) == 0 }.forEach { chosen += it.key }
                is Item.Range -> {
                    val hits = entries.filter { e ->
                        val n = num(e.number) ?: return@filter false
                        val volOk = item.volume == null || num(e.volume)?.compareTo(item.volume) == 0
                        volOk && n >= item.from && n <= item.to
                    }
                    if (hits.isEmpty() && item.from == item.to) missing += item.from.stripTrailingZeros().toPlainString()
                    if (item.volume == null) {
                        hits.groupBy { num(it.number)!!.stripTrailingZeros() }.filterValues { list -> list.map { it.volume }.distinct().size > 1 }
                            .keys.forEach { ambiguous += it.toPlainString() }
                    }
                    hits.forEach { chosen += it.key }
                }
            }
        }
        val order = entries.mapIndexed { i, e -> e.key to i }.toMap()
        return Resolved(chosen.sortedBy { order[it] }, missing, ambiguous.toList())
    }
}
