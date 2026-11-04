package ru.tachimanga.common

import eu.kanade.tachiyomi.source.model.Filter
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** Parses a date with any of the given patterns. Returns 0 when nothing matches. */
fun parseDate(value: String?, vararg patterns: String, utc: Boolean = false): Long {
    if (value.isNullOrBlank()) return 0L
    for (pattern in patterns) {
        try {
            val format = SimpleDateFormat(pattern, Locale.ROOT)
            if (utc) format.timeZone = TimeZone.getTimeZone("UTC")
            val date = format.parse(value.trim()) ?: continue
            return date.time
        } catch (_: Exception) {
        }
    }
    return 0L
}

/** ISO-8601 dates like 2024-05-01T10:20:30.123Z or 2024-05-01T10:20:30+03:00. */
fun parseIsoDate(value: String?): Long {
    if (value.isNullOrBlank()) return 0L
    // Cut fractions longer than milliseconds, SimpleDateFormat can't handle them.
    val cleaned = value.trim().replace(Regex("""(\.\d{3})\d+"""), "$1")
    return parseDate(
        cleaned,
        "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
        "yyyy-MM-dd'T'HH:mm:ssXXX",
        "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
        "yyyy-MM-dd'T'HH:mm:ss'Z'",
        "yyyy-MM-dd'T'HH:mm:ss.SSS",
        "yyyy-MM-dd'T'HH:mm:ss",
        "yyyy-MM-dd",
        utc = true,
    )
}

fun String.capitalizeRu(): String = replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale("ru")) else it.toString() }

class TriStateOption(name: String, val value: String) : Filter.TriState(name)

open class TriStateGroup(name: String, options: List<Pair<String, String>>) :
    Filter.Group<TriStateOption>(name, options.map { TriStateOption(it.first, it.second) }) {
    val included: List<String> get() = state.filter { it.isIncluded() }.map { it.value }
    val excluded: List<String> get() = state.filter { it.isExcluded() }.map { it.value }
}

class CheckBoxOption(name: String, val value: String) : Filter.CheckBox(name)

open class CheckBoxGroup(name: String, options: List<Pair<String, String>>) :
    Filter.Group<CheckBoxOption>(name, options.map { CheckBoxOption(it.first, it.second) }) {
    val checked: List<String> get() = state.filter { it.state }.map { it.value }
}

open class SelectFilter(name: String, private val options: List<Pair<String, String>>, default: Int = 0) :
    Filter.Select<String>(name, options.map { it.first }.toTypedArray(), default) {
    val selected: String get() = options[state].second
}

open class SortFilter(name: String, private val options: List<Pair<String, String>>, default: Selection = Selection(0, false)) :
    Filter.Sort(name, options.map { it.first }.toTypedArray(), default) {
    val selected: String get() = options[state?.index ?: 0].second
    val ascending: Boolean get() = state?.ascending ?: false
}

class TextFilter(name: String) : Filter.Text(name)
