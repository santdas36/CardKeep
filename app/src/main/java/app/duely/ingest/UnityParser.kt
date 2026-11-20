package app.duely.ingest

import java.time.LocalDate

/**
 * Unity Small Finance Bank (Roarbank) statements: a running-balance ledger.
 *   "Statement Period: 01.10.2026 - 31.10.2026", "Bill Date: 01.11.2026", "Total Amount Due (TAD): 0.00", …
 *   rows: "23-10-2026" then "09:51:09 BookMyShow - 191.63 - 149,808.37"  (credit, debit, fee, balance)
 * The "Balance" is available credit, so the opening debt is the limit at the start − available balance at the start.
 * If the limit changed during the period, the Balance column jumps without a matching amount (or a row says
 * so); that change is taken out of the limit for the opening, and such rows aren't counted as transactions.
 */
private const val AMT = "(\\d[\\d,]*\\.\\d{2}|-)"
private val LIMIT_ROW = Regex("credit\\s*limit|limit\\s*(enhance|increase|decrease|reduc|change|revis)", RegexOption.IGNORE_CASE)

class UnityParser : StatementParser {
    override val name = "unity"

    private val dateOnly = Regex("^(\\d{2}-\\d{2}-\\d{4})$")
    private val rowRest = Regex("^(?:\\d{1,2}:\\d{2}(?::\\d{2})?\\s+)?(.+?)\\s+$AMT\\s+$AMT\\s+$AMT\\s+(-?\\d[\\d,]*\\.\\d{2})$")
    private val oneLine = Regex("^(\\d{2}-\\d{2}-\\d{4})\\s+(?:\\d{1,2}:\\d{2}(?::\\d{2})?\\s+)?(.+?)\\s+$AMT\\s+$AMT\\s+$AMT\\s+(-?\\d[\\d,]*\\.\\d{2})$")

    override fun parse(text: String, received: LocalDate?): ParsedStatement {
        val lines = text.lines().map { it.replace(' ', ' ').replace(Regex("[ \t]+"), " ").trim() }.filter { it.isNotEmpty() }

        // First "Label: value" line wins; later pages carry an illustration table with the same labels.
        fun value(prefix: String, last: Boolean = false): String? {
            val hits = lines.filter { it.startsWith(prefix, true) && it.contains(':') }
            return (if (last) hits.lastOrNull() else hits.firstOrNull())?.substringAfter(':')?.trim()
        }
        fun amount(prefix: String, last: Boolean = false) = value(prefix, last)?.let { Text.amounts(it).firstOrNull() }?.let { Text.paise(it) }
        fun date(prefix: String) = value(prefix)?.let { Text.dateTokens(it).firstOrNull() }?.let { Text.date(it) }

        val period = value("Statement Period")?.let { Text.dateTokens(it).mapNotNull(Text::date) }.orEmpty()
        val limit = amount("Sanctioned Credit Limit")
        val startAvailable = amount("Balance at the beginning of period", last = true) // the credit card section comes after savings

        val rows = mutableListOf<ParsedLine>()
        var limitDelta = 0L // net limit change during the period
        var balance: Long? = startAvailable
        for ((i, l) in lines.withIndex()) {
            val (dateStr, m) = oneLine.matchEntire(l)?.let { it.groupValues[1] to it.groupValues.drop(1) }
                ?: dateOnly.matchEntire(l)?.let { d -> lines.getOrNull(i + 1)?.let { rowRest.matchEntire(it) }?.let { d.groupValues[1] to listOf(d.groupValues[1]) + it.groupValues.drop(1) } }
                ?: continue
            val day = Text.date(dateStr) ?: continue
            val desc = m[1].trim()
            val credit = Text.paise(m[2].takeIf { it != "-" } ?: "") ?: 0L
            val debit = Text.paise(m[3].takeIf { it != "-" } ?: "") ?: 0L
            val fee = Text.paise(m[4].takeIf { it != "-" } ?: "") ?: 0L
            val after = Text.paise(m[5].removePrefix("-"))?.let { if (m[5].startsWith("-")) -it else it }

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

        // Opening debt uses the limit that applied at the start of the period.
        val opening = if (limit != null && startAvailable != null) ((limit - limitDelta) - startAvailable).coerceAtLeast(0) else null

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
