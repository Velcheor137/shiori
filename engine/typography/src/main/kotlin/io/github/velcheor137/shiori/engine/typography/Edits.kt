package io.github.velcheor137.shiori.engine.typography

/** Замена диапазона исходного текста [start, end) на [replacement]. */
internal data class Edit(
    val start: Int,
    val end: Int,
    val replacement: String,
    val priority: Int,
    val rule: String,
) {
    init {
        require(start in 0..end) { "Bad edit range $start..$end" }
    }

    fun overlaps(other: Edit): Boolean = when {
        start == end || other.start == other.end -> false
        else -> start < other.end && other.start < end
    }
}

internal object EditSet {
    const val PRIORITY_DIALOGUE = 3
    const val PRIORITY_THOUGHT = 2

    /** Отбрасывает правки, пересекающиеся с более приоритетными; возвращает отсортированные по позиции. */
    fun resolve(edits: List<Edit>): List<Edit> {
        val accepted = ArrayList<Edit>()
        for (e in edits.sortedWith(compareByDescending<Edit> { it.priority }.thenBy { it.start })) {
            if (accepted.none { it.overlaps(e) || (it.start == e.start && it.end == e.end) }) accepted += e
        }
        return accepted.sortedWith(compareBy<Edit> { it.start }.thenBy { it.end })
    }

    fun apply(text: String, sorted: List<Edit>): Pair<String, OffsetMap> {
        val out = StringBuilder(text.length + 16)
        val segments = ArrayList<OffsetMap.Segment>(sorted.size)
        var cursor = 0
        for (e in sorted) {
            out.append(text, cursor, e.start)
            val dstStart = out.length
            out.append(e.replacement)
            segments += OffsetMap.Segment(e.start, e.end, dstStart, out.length)
            cursor = e.end
        }
        out.append(text, cursor, text.length)
        return out.toString() to OffsetMap(segments)
    }
}

/**
 * Перевод позиций между исходным текстом абзаца и отображаемым (после правок).
 * Якоря чтения хранятся в исходных координатах (§9.6 плана).
 */
class OffsetMap internal constructor(private val segments: List<Segment>) {
    internal data class Segment(val srcStart: Int, val srcEnd: Int, val dstStart: Int, val dstEnd: Int)

    val isIdentity: Boolean get() = segments.isEmpty()

    fun toDisplay(source: Int): Int {
        val s = segments.lastOrNull { it.srcStart <= source } ?: return source
        return if (source < s.srcEnd) {
            (s.dstStart + (source - s.srcStart)).coerceAtMost(s.dstEnd)
        } else {
            source - s.srcEnd + s.dstEnd
        }
    }

    fun toSource(display: Int): Int {
        val s = segments.lastOrNull { it.dstStart <= display } ?: return display
        return if (display < s.dstEnd) {
            (s.srcStart + (display - s.dstStart)).coerceAtMost(s.srcEnd)
        } else {
            display - s.dstEnd + s.srcEnd
        }
    }

    companion object {
        val Identity = OffsetMap(emptyList())
    }
}
