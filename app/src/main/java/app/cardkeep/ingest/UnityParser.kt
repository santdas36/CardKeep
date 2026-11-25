package app.cardkeep.ingest

import java.time.LocalDate

/**
 * Unity Small Finance Bank (Roarbank) statements: a running-balance ledger.
 *   "Statement Period: 01.10.2026 - 31.10.2026", "Bill Date: 01.11.2026", "Total Amount Due (TAD): 0.00", …
 *   rows: "23-10-2026" then "09:51:09 BookMyShow - 191.63 - 149,808.37"  (credit, debit, fee, balance)
 * Older statements: "Balance" is available credit, so the opening debt is the limit at the start − available
 * balance at the start. If the limit changed during the period, the Balance column jumps without a matching
 * amount (or a row says so); that change is taken out of the limit for the opening, and such rows aren't
 * counted as transactions.
 * Newer statements: "Balance" is what you owe, as a negative number ("-330.12"), so the opening debt is the
 * starting balance with its sign flipped. A long description can start on the date's line:
 *   "31-07-2026 BIGTREE ENTERTAINMENT" then "09:22:52 PRIVATE LIMITED - 260.12 - -330.12"
 */
private const val AMT = "(\\d[\\d,]*\\.\\d{2}|-)"
private val LIMIT_ROW = Regex("credit\\s*limit|limit\\s*(enhance|increase|decrease|reduc|change|revis)", RegexOption.IGNORE_CASE)

class UnityParser : StatementParser {
    override val name = "unity"

    private val dateOnly = Regex("^(\\d{2}-\\d{2}-\\d{4})$")
    private val dateWithText = Regex("^(\\d{2}-\\d{2}-\\d{4})\\s+(\\D.*)$")
    private val rowRest = Regex("^(?:\\d{1,2}:\\d{2}(?::\\d{2})?\\s+)?(.+?)\\s+$AMT\\s+$AMT\\s+$AMT\\s+(-?\\d[\\d,]*\\.\\d{2})$")
    private val oneLine = Regex("^(\\d{2}-\\d{2}-\\d{4})\\s+(?:\\d{1,2}:\\d{2}(?::\\d{2})?\\s+)?(.+?)\\s+$AMT\\s+$AMT\\s+$AMT\\s+(-?\\d[\\d,]*\\.\\d{2})$")

    private val time = Regex("^\\d{1,2}:\\d{2}(?::\\d{2})?\\s*")

    /**
     * The rest of a description wrapped under a one-line row: "VENKATESWAR … -195.00" then "19:59:15 A MESS",
     * or several lines ("STATE", "TRANSPORT"…). The column is about 11 characters wide, so a piece that long
     * was cut mid-word and joins without a space.
     */
    private fun wrapped(lines: List<String>, row: Int): String {
        val out = StringBuilder()
        var last = lines[row].let { oneLine.matchEntire(it)?.groupValues?.get(2)?.trim() }.orEmpty()
        for (next in lines.drop(row + 1).take(6)) {
            if (next.startsWith("*") || next.startsWith("Date ", true) || next.startsWith("Amount ", true)) break
            if (oneLine.matches(next) || dateOnly.matches(next) || dateWithText.matches(next)) break
            if (next.contains(':') && !time.containsMatchIn(next)) break // a "Label: value" line, not part of the row
            val piece = next.replace(time, "").trim()
            if (piece.isEmpty()) continue
            out.append(if (last.substringAfterLast(' ').length >= 12) "" else " ").append(piece)
            last = piece
        }
        return out.toString()
    }

    override fun parse(text: String, received: LocalDate?): ParsedStatement {
        val lines = text.lines().map { it.replace(' ', ' ').replace(Regex("[ \t]+"), " ").trim() }.filter { it.isNotEmpty() }

        // First "Label: value" line wins; later pages carry an illustration table with the same labels.
        fun value(prefix: String, last: Boolean = false): String? {
            val hits = lines.filter { it.startsWith(prefix, true) && it.contains(':') }
            return (if (last) hits.lastOrNull() else hits.firstOrNull())?.substringAfter(':')?.trim()
        }
        fun amount(prefix: String, last: Boolean = false) = value(prefix, last)?.let { Text.amounts(it).firstOrNull() }?.let { Text.paise(it) }
        fun signedPaise(x: String) = Text.paise(x.removePrefix("-"))?.let { if (x.startsWith("-")) -it else it }
        fun cell(x: String) = Text.paise(x.takeIf { it != "-" } ?: "") ?: 0L
        fun date(prefix: String) = value(prefix)?.let { Text.dateTokens(it).firstOrNull() }?.let { Text.date(it) }

        val period = value("Statement Period")?.let { Text.dateTokens(it).mapNotNull(Text::date) }.orEmpty()
        val limit = amount("Sanctioned Credit Limit") ?: amount("Credit limit")
        // Only the credit card section's starting balance; the savings section above has one too, and some
        // statements leave it out for the card (then it's worked back from the first row).
        val cardSection = lines.indexOfFirst { it.startsWith("Credit Card:", true) }
        val startBalance = if (cardSection < 0) null else lines.drop(cardSection)
            .firstOrNull { it.startsWith("Balance at the beginning of period", true) && it.contains(':') }
            ?.substringAfter(':')?.trim()
            ?.let { v -> Text.amounts(v).firstOrNull()?.let { Text.paise(it) }?.let { if (v.startsWith("-")) -it else it } }

        val rows = mutableListOf<ParsedLine>()
        var limitDelta = 0L // net limit change during the period
        var balance: Long? = startBalance
        var anyNegative = false
        var firstStart: Long? = null // the balance before the first row, worked back from it
        for ((i, l) in lines.withIndex()) {
            val (dateStr, m) = oneLine.matchEntire(l)?.let { it.groupValues[1] to listOf(it.groupValues[1], it.groupValues[2] + wrapped(lines, i)) + it.groupValues.drop(3) }
                ?: dateOnly.matchEntire(l)?.let { d -> lines.getOrNull(i + 1)?.let { rowRest.matchEntire(it) }?.let { d.groupValues[1] to listOf(d.groupValues[1]) + it.groupValues.drop(1) } }
                ?: dateWithText.matchEntire(l)?.let { d ->
                    lines.getOrNull(i + 1)?.let { rowRest.matchEntire(it) }?.let { r ->
                        d.groupValues[1] to listOf(d.groupValues[1], d.groupValues[2].trim() + " " + r.groupValues[1].trim()) + r.groupValues.drop(2)
                    }
                }
                ?: continue
            val day = Text.date(dateStr) ?: continue
            val desc = m[1].trim()
            val credit = cell(m[2])
            val debit = cell(m[3])
            val fee = cell(m[4])
            val after = signedPaise(m[5])
            if (after != null && after < 0) anyNegative = true
            if (firstStart == null && after != null) firstStart = after - credit + debit + fee

            if (LIMIT_ROW.containsMatchIn(desc)) {
                // A limit change, not a transaction.
                limitDelta += credit - debit
                if (after != null) balance = after
                continue
            }
            if (credit > 0) rows += ParsedLine(day, desc, credit, true)
            if (debit > 0) rows += ParsedLine(day, desc, debit, false)
            if (fee > 0) rows += ParsedLine(day, "Fee: $desc", fee, false)
            if (after != null) {
                val expected = balance?.let { it + credit - debit - fee }
                // Available credit moved by more than the row explains: the limit changed in between.
                if (expected != null && kotlin.math.abs(after - expected) > 100) limitDelta += after - expected
                balance = after
            }
        }

        // Newer layout: the balance is the debt, at or below zero. Older: available credit, which sits near the limit.
        val owedLayout = anyNegative || (startBalance ?: firstStart)?.let { it <= 0 } == true
        val opening = when {
            owedLayout -> (startBalance ?: firstStart)?.let { -it }
            // Opening debt uses the limit that applied at the start of the period.
            limit != null && startBalance != null -> ((limit - limitDelta) - startBalance).coerceAtLeast(0)
            else -> null
        }

        val cashback = rows.filter { it.description.contains("cashback", true) }
            .sumOf { if (it.isCredit) it.amountPaise else -it.amountPaise }.takeIf { it > 0 }

        return ParsedStatement(
            statementDate = date("Bill Date") ?: period.getOrNull(1),
            periodStart = period.getOrNull(0),
            dueDate = date("Payment Due Date"),
            totalDuePaise = amount("Total Amount Due"),
            minDuePaise = amount("Minimum Amount Due"),
            openingPaise = opening,
            last4 = Text.last4(text) ?: Regex("[*Xx]{4}\\s+(\\d{4})\\b").find(text)?.groupValues?.get(1),
            lines = rows,
            rewards = cashback?.let { Rewards(cashbackPaise = it) },
            creditLimitPaise = limit,
        )
    }
}
