package app.formkit.core.pdf

import kotlinx.serialization.Serializable

/** Pages [first] to [last], counted from 1 the way people number pages, both included. */
@Serializable
data class PageRange(val first: Int, val last: Int) {
    init {
        require(first in 1..last) { "Invalid page range $first-$last" }
    }

    val count: Int get() = last - first + 1

    override fun toString(): String = if (first == last) "$first" else "$first-$last"
}

sealed interface RangeParse {
    data class Valid(val ranges: List<PageRange>) : RangeParse
    data object Empty : RangeParse

    /** [token] isn't a page number or a range, like "a" or "5-2". */
    data class Invalid(val token: String) : RangeParse

    /** [page] doesn't exist in a document of [pageCount] pages. */
    data class OutOfBounds(val page: Int, val pageCount: Int) : RangeParse
}

/** Reads page lists typed the usual way: "1-3, 5, 8-" (8 to the end) or "-2" (the start to 2). */
object PageRanges {

    fun parse(text: String, pageCount: Int): RangeParse {
        require(pageCount > 0) { "A document has at least one page" }
        val tokens = text.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return RangeParse.Empty

        val ranges = ArrayList<PageRange>(tokens.size)
        for (token in tokens) {
            val compact = token.filterNot { it.isWhitespace() }.replace('–', '-').replace('—', '-')
            val parts = compact.split('-')
            val first: Int
            val last: Int
            when (parts.size) {
                1 -> {
                    first = parts[0].toIntOrNull() ?: return RangeParse.Invalid(token)
                    last = first
                }
                2 -> {
                    if (parts[0].isEmpty() && parts[1].isEmpty()) return RangeParse.Invalid(token)
                    first = if (parts[0].isEmpty()) 1 else parts[0].toIntOrNull() ?: return RangeParse.Invalid(token)
                    last = if (parts[1].isEmpty()) pageCount else parts[1].toIntOrNull() ?: return RangeParse.Invalid(token)
                }
                else -> return RangeParse.Invalid(token)
            }
            if (first < 1) return RangeParse.OutOfBounds(first, pageCount)
            if (first > pageCount) return RangeParse.OutOfBounds(first, pageCount)
            if (last > pageCount) return RangeParse.OutOfBounds(last, pageCount)
            if (last < first) return RangeParse.Invalid(token)
            ranges += PageRange(first, last)
        }
        return RangeParse.Valid(ranges)
    }

    /** Zero-based page indexes in the order they were listed, each page once. */
    fun pageIndices(ranges: List<PageRange>): List<Int> {
        val seen = LinkedHashSet<Int>()
        for (range in ranges) for (page in range.first..range.last) seen += page - 1
        return seen.toList()
    }

    /** The shortest way to write [pages] (zero-based), for example "1-3, 5". */
    fun format(pages: Collection<Int>): String {
        if (pages.isEmpty()) return ""
        val sorted = pages.distinct().sorted()
        val parts = ArrayList<String>()
        var start = sorted[0]
        var previous = start
        for (page in sorted.drop(1) + Int.MIN_VALUE) {
            if (page == previous + 1) {
                previous = page
                continue
            }
            parts += if (start == previous) "${start + 1}" else "${start + 1}-${previous + 1}"
            start = page
            previous = page
        }
        return parts.joinToString(", ")
    }
}
