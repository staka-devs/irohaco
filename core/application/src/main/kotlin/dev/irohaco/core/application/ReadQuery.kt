package dev.irohaco.core.application

import dev.irohaco.core.domain.MediaKind
import java.text.Normalizer
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters

/** What a search sentence asks for besides its words: capture times in [fromMs, untilMs) and the kind (#203). */
data class ReadQuery(val rest: String, val fromMs: Long? = null, val untilMs: Long? = null, val kind: MediaKind? = null)

/**
 * Reads the dates and the kind out of a search sentence by fixed rules, so it works with no model on the
 * device (ARCHITECTURE §7.2): "去年の夏に京都で撮った動画" is videos from June to August last year, searched
 * for "京都". Place names stay in the words, where the place search finds them.
 */
object QueryReader {
    fun read(query: String, now: ZonedDateTime): ReadQuery {
        var text = Normalizer.normalize(query, Normalizer.Form.NFKC)
        fun take(regex: Regex): MatchResult? = regex.find(text)?.also { text = text.replaceRange(it.range, " ") }

        val kind = KINDS.firstNotNullOfOrNull { (regex, kind) -> take(regex)?.let { kind } }
        val today = now.toLocalDate()
        val span: Pair<LocalDate, LocalDate>? = RELATIVE.firstNotNullOfOrNull { (regex, span) -> take(regex)?.let { span(today) } } ?: run {
            val year = YEARS.firstNotNullOfOrNull { (regex, year) -> take(regex)?.let { year(today, it) } }
            val season = take(SEASON_JA)?.let { seasonStart(it.groupValues[1]) to false }
                ?: take(SEASON_EN)?.let { seasonStart(it.groupValues[2].lowercase()) to it.groupValues[1].isNotBlank() }
            val month = take(MONTH_JA)?.groupValues?.get(1)?.toInt()?.takeIf { it in 1..12 }
                ?: take(MONTH_EN)?.let { MONTHS.indexOf(it.value.lowercase()) + 1 }
            when {
                season != null -> latest(today, year, season.first, 3, endedOnly = season.second)
                month != null -> latest(today, year, month, 1, endedOnly = false)
                year != null -> LocalDate.of(year, 1, 1) to LocalDate.of(year + 1, 1, 1)
                else -> null
            }
        }
        val words = text.split(Regex("\\s+")).map { it.replace(EDGE_JA, "") }.filter { it.isNotEmpty() }
            .dropWhile { it.lowercase() in STOP_EN }.dropLastWhile { it.lowercase() in STOP_EN }
        val zone = now.zone
        return ReadQuery(
            // shortcut: a sentence of conditions only is still searched by its words; listing by conditions alone would need its own query.
            rest = words.joinToString(" ").ifEmpty { query.trim() },
            fromMs = span?.first?.atStartOfDay(zone)?.toInstant()?.toEpochMilli(),
            untilMs = span?.second?.atStartOfDay(zone)?.toInstant()?.toEpochMilli(),
            kind = kind,
        )
    }

    /** The months [startMonth] onward, [months] long, in [year] or else the latest such stretch that began (or ended) by [today]. */
    private fun latest(today: LocalDate, year: Int?, startMonth: Int, months: Long, endedOnly: Boolean): Pair<LocalDate, LocalDate> {
        fun at(y: Int) = LocalDate.of(y, startMonth, 1).let { it to it.plusMonths(months) }
        if (year != null) return at(year)
        val thisYear = at(today.year)
        val ready = if (endedOnly) !thisYear.second.isAfter(today) else !thisYear.first.isAfter(today)
        return if (ready) thisYear else at(today.year - 1)
    }

    private fun seasonStart(name: String) = when (name) {
        "春", "spring" -> 3
        "夏", "summer" -> 6
        "秋", "autumn", "fall" -> 9
        else -> 12
    }

    private val KINDS = listOf(
        Regex("動画|ビデオ|ムービー|\\b(videos?|movies?|clips?)\\b", RegexOption.IGNORE_CASE) to MediaKind.VIDEO,
        Regex("写真|画像|\\b(photos?|pictures?|pics?)\\b", RegexOption.IGNORE_CASE) to MediaKind.IMAGE,
    )

    private fun day(n: Long): (LocalDate) -> Pair<LocalDate, LocalDate> = { today -> today.minusDays(n) to today.minusDays(n - 1) }

    private fun week(n: Long): (LocalDate) -> Pair<LocalDate, LocalDate> = { today ->
        today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks(n).let { it to it.plusWeeks(1) }
    }

    private fun month(n: Long): (LocalDate) -> Pair<LocalDate, LocalDate> = { today -> today.withDayOfMonth(1).minusMonths(n).let { it to it.plusMonths(1) } }

    private val RELATIVE = listOf(
        Regex("一昨日|おととい|\\bday before yesterday\\b", RegexOption.IGNORE_CASE) to day(2),
        Regex("昨日|\\byesterday\\b", RegexOption.IGNORE_CASE) to day(1),
        Regex("今日|\\btoday\\b", RegexOption.IGNORE_CASE) to day(0),
        Regex("先週|\\blast week\\b", RegexOption.IGNORE_CASE) to week(1),
        Regex("今週|\\bthis week\\b", RegexOption.IGNORE_CASE) to week(0),
        Regex("先月|\\blast month\\b", RegexOption.IGNORE_CASE) to month(1),
        Regex("今月|\\bthis month\\b", RegexOption.IGNORE_CASE) to month(0),
    )

    private val YEARS = listOf<Pair<Regex, (LocalDate, MatchResult) -> Int>>(
        Regex("一昨年|おととし") to { today, _ -> today.year - 2 },
        Regex("去年|昨年|\\blast year\\b", RegexOption.IGNORE_CASE) to { today, _ -> today.year - 1 },
        Regex("今年|\\bthis year\\b", RegexOption.IGNORE_CASE) to { today, _ -> today.year },
        Regex("((?:19|20)\\d{2})年|\\b((?:19|20)\\d{2})\\b") to { _, m -> (m.groupValues[1].ifEmpty { m.groupValues[2] }).toInt() },
    )

    // A season word stands alone or before a particle, so 秋田 and 春巻き stay words.
    private val SEASON_JA = Regex("(春|夏|秋|冬)(?=の|に|は|休み|頃|ごろ|、|\\s|$)")
    private val SEASON_EN = Regex("\\b(last\\s+)?(spring|summer|autumn|fall|winter)\\b", RegexOption.IGNORE_CASE)
    private val MONTH_JA = Regex("(\\d{1,2})月")
    private val MONTHS = listOf("january", "february", "march", "april", "may", "june", "july", "august", "september", "october", "november", "december")
    private val MONTH_EN = Regex("\\b(${MONTHS.filter { it != "may" }.joinToString("|")})\\b", RegexOption.IGNORE_CASE)

    // What joins the conditions to the words: 去年の, 夏に, 京都で撮った.
    private val EDGE_JA = Regex("^(?:の|に|で|から|頃|ごろ|、)+|(?:で撮った|で撮影した|に撮った|に撮影した|撮った|撮影した|の|に|で|から|頃|ごろ|、)+$")
    private val STOP_EN = setOf("from", "in", "of", "on", "at", "during", "taken", "shot", "the", "my", "a", "an")
}
