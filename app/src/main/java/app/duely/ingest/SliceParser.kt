package app.duely.ingest

import java.time.LocalDate
import java.time.MonthDay
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * slice statements (app-style layout):
 *   period "15 OCT - 14 NOV", "Due on 1 Dec", a "Statement summary" of labelled amounts, then
 *   sections ("Spends", "Refunds & repayments", "Cashback", ...) of two-line rows:
 *     "Bismi petshop ₹280"  [avatar letter]  "7 Nov '26 • UPI"
 * There's no opening balance line; "Carry forward" is used when present, else 0. Interest and fee
 * summary amounts become synthetic debit lines so the total check still validates the rows.
 */
class SliceParser : StatementParser {
    override val name = "slice"

    private val creditSections = listOf("refunds & repayments", "refunds and repayments", "cashback", "repayments", "refunds")
    private val debitSections = listOf("spends", "bank transfers", "fees", "emis", "ongoing emis")
    private val endSections = listOf("monies", "gst details", "important information", "glossary")
    private val feeLabels = listOf("interest", "surcharge", "late fee", "flat fee", "foreclosure charge")

    private val row = Regex("^(.+?)\\s+₹\\s?(\\d[\\d,]*(?:\\.\\d{1,2})?)$")
    private val rowDate = Regex("^(\\d{1,2} [A-Za-z]{3} '\\d{2})\\b")
    private val period = Regex("\\b(\\d{1,2}) ([A-Za-z]{3})[A-Za-z]* - (\\d{1,2}) ([A-Za-z]{3})[A-Za-z]*\\b")
    private val dueOn = Regex("(?i)due on (\\d{1,2}) ([A-Za-z]{3})")

    override fun parse(text: String, received: LocalDate?): ParsedStatement {
        val lines = text.lines().map { it.replace(' ', ' ').replace(Regex("[ \t]+"), " ").trim() }.filter { it.isNotEmpty() }
        val summary = summary(lines)

        val rows = rows(lines)
        val latestRow = rows.maxOfOrNull { it.date }
        val year = (received ?: latestRow ?: LocalDate.now()).year

        // "15 OCT - 14 NOV": the statement closes on the second date; the first may be in the previous year.
        // The period has no year. The mail arrives on or after the closing date; rows are on or before it.
        val p = lines.firstNotNullOfOrNull { period.find(it) }
        val stmt = p?.let { md(it.groupValues[3], it.groupValues[4]) }?.let { end ->
            when {
                received != null -> end.atYear(received.year).let { if (it.isAfter(received.plusDays(1))) it.minusYears(1) else it }
                latestRow != null -> end.atYear(latestRow.year).let { if (it.isBefore(latestRow.minusDays(3))) it.plusYears(1) else it }
                else -> LocalDate.now().let { now -> end.atYear(now.year).let { if (it.isAfter(now)) it.minusYears(1) else it } }
            }
        }
        val start = p?.let { md(it.groupValues[1], it.groupValues[2])?.atYear(stmt?.year ?: year) }?.let { s ->
            if (stmt != null && s.isAfter(stmt)) s.minusYears(1) else s
        }
        val due = lines.firstNotNullOfOrNull { dueOn.find(it) }?.let { m ->
            md(m.groupValues[1], m.groupValues[2])?.atYear(stmt?.year ?: year)?.let { d ->
                if (stmt != null && d.isBefore(stmt)) d.plusYears(1) else d
            }
        }

        val fees = feeLabels.mapNotNull { lab -> summary[lab]?.takeIf { it > 0 }?.let { lab to it } }
            .map { (lab, amt) -> ParsedLine(stmt ?: rows.lastOrNull()?.date ?: LocalDate.now(), lab.replaceFirstChar(Char::uppercase), amt, false) }

        val monies = lines.indexOfFirst { it.equals("monies", true) }.takeIf { it >= 0 }?.let { i ->
            lines.drop(i + 1).take(3).firstNotNullOfOrNull { Regex("(?i)^earned ([\\d,]+)$").find(it) }?.groupValues?.get(1)?.replace(",", "")?.toLongOrNull()
        }
        val rewards = Rewards(pointsEarned = monies, cashbackPaise = summary["cashback"], unit = "monies").takeUnless { it.isEmpty }

        return ParsedStatement(
            statementDate = stmt,
            periodStart = start,
            dueDate = due,
            totalDuePaise = summary["total amount due"],
            minDuePaise = summary["min amount due"] ?: summary["minimum amount due"],
            openingPaise = summary["carry forward"] ?: 0L,
            last4 = last4(lines),
            lines = rows + fees,
            rewards = rewards,
        )
    }

    /** "Label ₹1,234.50" pairs from the statement summary block. */
    private fun summary(lines: List<String>): Map<String, Long> {
        val start = lines.indexOfFirst { it.equals("statement summary", true) }
        if (start < 0) return emptyMap()
        val out = mutableMapOf<String, Long>()
        for (l in lines.drop(start + 1).take(20)) {
            val m = row.find(l) ?: continue
            val label = m.groupValues[1].lowercase().trim()
            if (label in out) break
            out[label] = Text.paise(m.groupValues[2]) ?: continue
            if (label == "min amount due" || label == "minimum amount due") break
        }
        return out
    }

    private fun rows(lines: List<String>): List<ParsedLine> {
        val out = mutableListOf<ParsedLine>()
        var credit: Boolean? = null
        val summaryEnd = lines.indexOfFirst { it.lowercase().startsWith("min amount due") }
        for ((i, l) in lines.withIndex()) {
            if (i <= summaryEnd) continue
            val low = l.lowercase()
            when {
                endSections.any { low == it } -> credit = null
                creditSections.any { low == it } -> { credit = true; continue }
                debitSections.any { low == it } -> { credit = false; continue }
            }
            val isCredit = credit ?: continue
            val m = row.find(l) ?: continue
            // The date is on the next line, or the one after a single-letter avatar.
            val dateLine = lines.subList(i + 1, minOf(i + 3, lines.size)).firstNotNullOfOrNull { rowDate.find(it) } ?: continue
            val date = Text.date(dateLine.groupValues[1].replace("'", "")) ?: continue
            val amount = Text.paise(m.groupValues[2]) ?: continue
            out += ParsedLine(date, m.groupValues[1].trim(), amount, isCredit)
        }
        return out
    }

    /** "X X X X  X X X X  X X X X  4 0 4 7" */
    private fun last4(lines: List<String>): String? = lines.firstNotNullOfOrNull { l ->
        val compact = l.replace(" ", "")
        Regex("^[Xx*•]{8,15}(\\d{4})$").find(compact)?.groupValues?.get(1)
    }

    private fun md(day: String, mon: String): MonthDay? = runCatching {
        MonthDay.parse("${day.padStart(2, '0')} ${mon.lowercase().replaceFirstChar(Char::uppercase)}", DateTimeFormatter.ofPattern("dd MMM", Locale.ENGLISH))
    }.getOrNull()
}
