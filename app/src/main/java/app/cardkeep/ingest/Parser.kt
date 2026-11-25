package app.cardkeep.ingest

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.ResolverStyle
import java.util.Locale
import kotlin.math.abs

data class ParsedLine(
    val date: LocalDate,
    val description: String,
    val amountPaise: Long,
    val isCredit: Boolean,
    val reference: String = "",
)

data class ParsedStatement(
    val statementDate: LocalDate?,
    val periodStart: LocalDate?,
    val dueDate: LocalDate?,
    val totalDuePaise: Long?,
    val minDuePaise: Long?,
    val openingPaise: Long?,
    val last4: String?,
    val lines: List<ParsedLine>,
    val rewards: Rewards? = null,
    val creditLimitPaise: Long? = null,
) {
    val debits get() = lines.filter { !it.isCredit }.sumOf { it.amountPaise }
    val credits get() = lines.filter { it.isCredit }.sumOf { it.amountPaise }
}

/**
 * What the statement says the card earned. Points-based cards fill [pointsBalance]/[pointsEarned]
 * (slice calls them "monies"); cashback cards fill [cashbackPaise] for this cycle.
 */
data class Rewards(
    val pointsBalance: Long? = null,
    val pointsEarned: Long? = null,
    val cashbackPaise: Long? = null,
    val unit: String = "points",
) {
    val isEmpty get() = pointsBalance == null && pointsEarned == null && cashbackPaise == null
}

/** Outcome of the "lines add up to the total" check. */
data class Validation(val ok: Boolean, val note: String)

interface StatementParser {
    val name: String

    /**
     * [received] is when the statement arrived (the mail's date), used by layouts that print dates
     * without a year; null when unknown (a picked file, a re-parse).
     */
    fun parse(text: String, received: LocalDate? = null): ParsedStatement
}

/**
 * Label-driven parser for Indian card statements. Each issuer gets its own instance with extra
 * label spellings; the extraction engine is shared. Works on text from PDFBox with sort-by-position.
 */
open class LabelParser(
    override val name: String,
    totalLabels: List<String> = emptyList(),
    minLabels: List<String> = emptyList(),
    dueLabels: List<String> = emptyList(),
    stmtDateLabels: List<String> = emptyList(),
    openingLabels: List<String> = emptyList(),
    private val creditWords: List<String> = emptyList(),
) : StatementParser {

    private val total = totalLabels + listOf(
        "total amount due", "total dues", "total payment due", "total amount payable", "total outstanding",
        "closing balance", "new balance", "total due",
    )
    private val min = minLabels + listOf(
        "minimum amount due", "minimum payment due", "min. amount due", "min amount due", "minimum due", "min. amt. due", "minimum amount payable",
    )
    private val due = dueLabels + listOf("payment due date", "due date", "pay by", "payment due on")
    private val stmtDate = stmtDateLabels + listOf("statement date", "statement generation date", "bill date", "billing date")
    private val opening = openingLabels + listOf(
        "opening balance", "previous balance", "previous statement dues", "previous outstanding", "last statement balance",
        "previous amount due", "opening bal",
    )
    private val period = listOf("statement period", "billing period", "billing cycle")
    private val credits = creditWords + listOf(
        "payment received", "payment - thank you", "payment thank you", "thank you", "refund", "reversal", "cashback",
        "cash back", "credit adjustment", "autopay", "bbps payment",
    )

    override fun parse(text: String, received: LocalDate?): ParsedStatement {
        val lines = text.lines().map { it.replace(' ', ' ').replace(Regex("[ \t]+"), " ").trim() }.filter { it.isNotEmpty() }
        val fields = HeaderFields(lines)

        val periodDates = fields.dates(period, 2)
        val dueDate = fields.date(due)
        // A statement date after the due date, or months before it, was read from the wrong place.
        fun sane(d: LocalDate?) = d?.takeIf { dueDate == null || (!it.isAfter(dueDate) && !it.isBefore(dueDate.minusDays(62))) }
        val stmt = sane(fields.date(stmtDate)) ?: sane(periodDates.getOrNull(1)) ?: sane(Text.bareDate(lines))
        val periodStart = periodDates.getOrNull(0)

        val txns = transactionLines(lines, stmt, periodStart)
        val equation by lazy { Text.summaryEquation(lines) }
        // A trailing "Cr" (card in credit) comes back negative: nothing is due, and the total check still adds up.
        val totalDue = fields.amount(total) ?: equation?.second
        return ParsedStatement(
            statementDate = stmt,
            periodStart = periodStart,
            dueDate = dueDate,
            totalDuePaise = totalDue,
            minDuePaise = fields.amount(min)?.coerceAtLeast(0),
            openingPaise = fields.amount(opening) ?: equation?.first ?: totalDue?.let { Text.summaryRow(lines, it) },
            last4 = Text.last4(text),
            lines = txns,
            rewards = rewards(lines, txns),
            creditLimitPaise = Text.creditLimit(lines),
        )
    }

    private fun transactionLines(lines: List<String>, stmt: LocalDate?, periodStart: LocalDate?): List<ParsedLine> {
        val matched = lines.mapNotNull { Text.TXN.matchEntire(it) }
        val anyDrCr = matched.any { it.groupValues[6].isNotEmpty() }
        // When a statement marks credits with +/-, unmarked lines are debits (e.g. "Cashback_Reversal").
        val anySigned = matched.any { it.groupValues[2].isNotEmpty() || it.groupValues[4].isNotEmpty() }
        val earliest = (periodStart ?: stmt?.minusDays(45))?.minusDays(5)
        val latest = stmt?.plusDays(1)
        val out = mutableListOf<ParsedLine>()
        for ((i, line) in lines.withIndex()) {
            val m = Text.TXN.matchEntire(line) ?: continue
            val date = Text.date(m.groupValues[1]) ?: continue
            var desc = m.groupValues[3].trim()
            // Long descriptions wrap around the date/amount row (HDFC): text above and below, "C" glyph alone on the row.
            if (desc.replace(Regex("^[+\\-]?\\s*(?:C|₹|Rs\\.?|INR)?$"), "").none(Char::isLetter)) {
                val above = lines.getOrNull(i - 1)?.takeIf { Text.TXN.matchEntire(it) == null && !isTableHeader(it) }
                val below = lines.getOrNull(i + 1)?.takeIf { Text.TXN.matchEntire(it) == null && !it.startsWith("Page ", true) && it.length < 60 }
                desc = listOfNotNull(above, below).joinToString(" ").replace("( ", "(").trim()
            }
            if (desc.none(Char::isLetter)) continue
            if (isHeaderish(desc)) continue
            if (earliest != null && date.isBefore(earliest)) continue
            if (latest != null && date.isAfter(latest)) continue
            val amount = Text.paise(m.groupValues[5]) ?: continue
            val marker = m.groupValues[6].lowercase()
            val sign = m.groupValues[2].ifEmpty { m.groupValues[4] }
            // Trailing reward-point integers and stray currency glyphs are not part of the merchant.
            desc = desc.replace(Regex("\\s+[-+]?\\s?\\d{1,6}$"), "").replace(Regex("\\s*(₹|Rs\\.?|INR|`)\\s*$"), "").trim()
            desc = Text.stripEmiLink(desc)
            val lower = desc.lowercase()
            val credit = when {
                marker.startsWith("c") -> true
                marker.startsWith("d") -> false
                sign == "+" -> true
                sign == "-" -> false
                !anyDrCr && !anySigned -> credits.any { lower.contains(it) }
                else -> false
            }
            val ref = Regex("\\b(?:ref|reference|arn)[:#\\s]*([A-Z0-9]{6,})", RegexOption.IGNORE_CASE).find(desc)?.groupValues?.get(1).orEmpty()
            out += ParsedLine(date, desc, amount, credit, ref)
        }
        return out
    }

    private fun isTableHeader(l: String): Boolean {
        val low = l.lowercase()
        return (low.contains("date") && (low.contains("description") || low.contains("amount"))) || low.contains("ckyc") ||
            low.endsWith("transactions")
    }

    /**
     * Reward points from a points table ("Reward Points | Opening Balance | Earned | Disbursed | Lapsed" with the
     * numbers on a following line: closing balance first, then opening, then earned), and cashback from a
     * "Cash Back Summary … Total ₹x" block or, failing that, the net of cashback lines.
     */
    private fun rewards(lines: List<String>, txns: List<ParsedLine>): Rewards? {
        var balance: Long? = null
        var earned: Long? = null
        val head = lines.indexOfFirst { it.lowercase().startsWith("reward points") && it.lowercase().contains("opening") }
        if (head >= 0) {
            val nums = lines.drop(head + 1).take(3).firstNotNullOfOrNull { l ->
                l.takeIf { Regex("^[\\d,\\s]+$").matches(it) }?.split(' ')?.filter { it.isNotBlank() }?.mapNotNull { it.replace(",", "").toLongOrNull() }
                    ?.takeIf { it.size >= 3 }
            }
            balance = nums?.getOrNull(0); earned = nums?.getOrNull(2)
        }
        val cbHead = lines.indexOfFirst { it.lowercase().replace(" ", "").startsWith("cashbacksummary") }
        val cashback = if (cbHead >= 0) {
            lines.drop(cbHead + 1).take(12).firstOrNull { it.lowercase().startsWith("total") }?.let { Text.amounts(it).firstOrNull() }?.let { Text.paise(it) }
        } else {
            val cb = txns.filter { it.description.contains("cashback", true) || it.description.contains("cash back", true) }
            (cb.filter { it.isCredit }.sumOf { it.amountPaise } - cb.filter { !it.isCredit }.sumOf { it.amountPaise }).takeIf { it > 0 }
        }
        return Rewards(pointsBalance = balance, pointsEarned = earned, cashbackPaise = cashback).takeUnless { it.isEmpty }
    }

    private fun isHeaderish(desc: String): Boolean {
        val l = desc.lowercase()
        return (total + min + due + opening).any { l.startsWith(it) }
    }
}

/** Finds labelled values, handling both "Label: value" and column headers with values on the next line. */
private class HeaderFields(private val lines: List<String>) {
    private val lower = lines.map { it.lowercase() }

    /** Signed: "1,234.00 Cr" is −1,234.00 (a credit balance). */
    fun amount(labels: List<String>): Long? = find(labels) { Text.signedAmounts(it).firstOrNull() }?.let { Text.paise(it) }

    fun date(labels: List<String>): LocalDate? = find(labels) { Text.dateTokens(it).firstOrNull() }?.let { Text.date(it) }

    fun dates(labels: List<String>, n: Int): List<LocalDate> {
        for ((i, l) in lower.withIndex()) {
            val label = labels.firstOrNull { l.contains(it) } ?: continue
            val after = lines[i].substring(l.indexOf(label) + label.length) + " " + lines.getOrElse(i + 1) { "" }
            val ds = Text.dateTokens(after).mapNotNull { Text.date(it) }
            if (ds.size >= n) return ds.take(n)
        }
        return emptyList()
    }

    private fun find(labels: List<String>, pick: (String) -> String?): String? {
        for ((i, l) in lower.withIndex()) {
            val label = labels.firstOrNull { l.contains(it) } ?: continue
            val start = l.indexOf(label)

            // Column layout: several labels on this line, values in the same order on one of the next few lines
            // (other text can sit in between, e.g. HDFC's address block). A period column holds two dates.
            val onLine = allLabelsOnLine(l)
            if (onLine.size >= 2) {
                val sameKind = onLine.filter { kindOf(it) == kindOf(label) }
                val idx = sameKind.indexOfFirst { start >= it.second && start < it.second + it.first.length }
                if (idx >= 0) {
                    val widths = sameKind.map { width(it.first) }
                    for (next in lines.drop(i + 1).take(3)) {
                        if (allLabelsOnLine(next.lowercase()).isNotEmpty()) break
                        val values = if (kindOf(label) == Kind.DATE) Text.dateTokens(next) else Text.signedAmounts(next)
                        if (values.size == widths.sum()) return values[widths.take(idx).sum()]
                    }
                }
            }

            // Inline: value after the label on the same line.
            val rest = lines[i].substring(start + label.length)
            pick(stripNextLabel(rest))?.let { return it }
            // Value on the following line.
            lines.getOrNull(i + 1)?.let { n -> if (allLabelsOnLine(n.lowercase()).isEmpty()) pick(n)?.let { return it } }
        }
        return null
    }

    /** Cut off at the next known label so "Total Dues 1,000.00 Minimum Due 50.00" doesn't bleed. */
    private fun stripNextLabel(rest: String): String {
        val l = rest.lowercase()
        val cut = ALL.mapNotNull { lab -> l.indexOf(lab).takeIf { it > 0 } }.minOrNull() ?: return rest
        return rest.substring(0, cut)
    }

    private enum class Kind { DATE, AMOUNT }

    private fun kindOf(p: Pair<String, Int>) = kindOf(p.first)
    private fun kindOf(label: String) = if (DATE_LABELS.any { label.contains(it) }) Kind.DATE else Kind.AMOUNT

    /** How many values a column holds: a period is a from–to pair. */
    private fun width(label: String) = if (label.endsWith("period")) 2 else 1

    private fun allLabelsOnLine(l: String): List<Pair<String, Int>> {
        val hits = mutableListOf<Pair<String, Int>>()
        for (lab in ALL.sortedByDescending { it.length }) {
            var from = 0
            while (true) {
                val at = l.indexOf(lab, from)
                if (at < 0) break
                if (hits.none { at >= it.second && at < it.second + it.first.length }) hits += lab to at
                from = at + lab.length
            }
        }
        return hits.sortedBy { it.second }
    }

    companion object {
        val DATE_LABELS = listOf("date", "pay by", "due on", "period")
        val ALL = listOf(
            "statement period", "billing period",
            "total amount due", "total dues", "total payment due", "total amount payable", "total outstanding", "closing balance",
            "minimum amount due", "minimum payment due", "min. amount due", "min amount due", "minimum due",
            "payment due date", "due date", "statement date", "statement generation date", "bill date",
            "opening balance", "previous balance", "previous statement dues", "previous outstanding", "credit limit",
            "available credit limit", "available cash limit", "cash limit",
        )
    }
}

object Text {
    /**
     * HDFC prints a clickable "EMI" link (convert this purchase to EMI) before eligible lines, and it
     * comes out as a leading "EMI" word. Real EMI lines say what they are ("EMI PRINCIPAL", "EMI INTEREST",
     * "EMI 3/6", processing fee…), so those keep it.
     */
    private val emiLink = Regex(
        "^EMI\\s+(?!(?:PRINCIPAL|PRIN\\b|INTEREST|INT\\b|INT\\.|PROC|PROCESSING|CONV|CONVERSION|FORECLOS|PRE-?CLOS|AMOUNT|AMT\\b|INST|INSTALL|\\d+\\s*/\\s*\\d+|LOAN|BOOKED|CANCEL))",
        RegexOption.IGNORE_CASE,
    )

    fun stripEmiLink(desc: String): String = desc.replace(emiLink, "").trim().ifEmpty { desc }

    private const val MON = "jan|feb|mar|apr|may|jun|jul|aug|sep|sept|oct|nov|dec"
    private const val DATE_RX =
        "(?:\\d{1,2}[/\\-.]\\d{1,2}[/\\-.]\\d{2,4}|\\d{1,2}[ \\-](?:$MON)[a-z]*[ \\-,]*\\d{2,4}|(?:$MON)[a-z]* \\d{1,2},? \\d{4})"
    private const val AMOUNT_RX = "(?<![\\d,.])\\d+(?:,\\d+)*\\.\\d{2}(?!\\d)"

    private val DATE = Regex(DATE_RX, RegexOption.IGNORE_CASE)
    private val AMOUNT = Regex(AMOUNT_RX)

    /**
     * date[| time] [date] [+|-] description [+|-] [₹] amount [Cr|Dr] [bullet]
     * Groups: 1 date, 2 leading sign, 3 description, 4 trailing sign, 5 amount, 6 Cr/Dr marker.
     * "C" before the amount is the ₹ glyph as some PDFs (HDFC) extract it; a trailing lone "l"/"•" is
     * the spend-category bullet.
     */
    val TXN = Regex(
        "^($DATE_RX)(?:\\s*\\|?\\s*\\d{1,2}:\\d{2}(?::\\d{2})?)?\\s+(?:$DATE_RX\\s+)?([+-])?\\s*(.+?)\\s+([+-])?\\s*(?:₹|Rs\\.?|INR|`|C)?\\s*($AMOUNT_RX)\\s*(Cr|CR|cr|C|Dr|DR|dr|D)?\\.?(?:\\s+[l•●])?$",
        RegexOption.IGNORE_CASE,
    )

    private val MONEY_TOKEN = Regex("^(?:C|₹|Rs\\.?|INR)?\\s?(\\d{1,3}(?:,\\d{2,3})*(?:\\.\\d{2})?)$")

    /**
     * Total credit limit. Inline ("Sanctioned Credit Limit: 150,000.00") or HDFC's column block, where
     * "TOTAL CREDIT LIMIT" heads a group whose values ("C2,48,000 C2,45,204 C99,200") come a few lines
     * later as a row of whole-rupee amounts. "Available" limits are never taken.
     */
    fun creditLimit(lines: List<String>): Long? {
        for ((i, l) in lines.withIndex()) {
            val low = l.lowercase()
            if (!low.contains("credit limit") || low.contains("available credit limit") && !low.contains("total credit limit")) continue
            if (low.contains(':') && !low.contains("total credit limit")) {
                amounts(l.substringAfter(':')).firstOrNull()?.let { return paise(it) }
                continue
            }
            for (next in lines.drop(i + 1).take(6)) {
                val toks = next.split(' ').filter { it.isNotBlank() }
                    .let { t -> t.zipWithNext { a, b -> if (a == "C" || a == "₹") "$a$b" else null }.filterNotNull().ifEmpty { null } ?: t }
                val vals = toks.mapNotNull { MONEY_TOKEN.matchEntire(it)?.groupValues?.get(1) }
                if (vals.size >= 2 && vals.size == toks.size && vals.none { it.contains('.') }) return paise(vals.first())
            }
        }
        return null
    }

    /**
     * Summary rows written as an equation, e.g. HDFC's
     * "₹66,759.08 ₹68,996.24 + ₹12,443.40 + ₹0.00 = ₹10,206.00" (opening − credits + debits + charges = total).
     * Returns (opening, total) in paise.
     */
    fun summaryEquation(lines: List<String>): Pair<Long, Long>? {
        for (l in lines) {
            if (l.count { it == '=' } != 1) continue
            val left = amounts(l.substringBefore('='))
            val right = amounts(l.substringAfter('='))
            if (left.size >= 3 && right.size == 1) {
                return (paise(left.first()) ?: continue) to (paise(right.first()) ?: continue)
            }
        }
        return null
    }

    /**
     * The account summary as a bare row of figures whose headings sit elsewhere, e.g. HDFC's
     * "5,237.48 5,365.48 15,975.82 0.00 15,848.00" (opening, credits, debits, charges, total) or Axis's
     * "4,080.80 Dr 4,080.80 30.00 4,551.82 0.00 0.00 4,521.82 Dr". The row must end in [totalDue] and the
     * figures between must add or take away to get there from the first one, which is the opening balance.
     */
    fun summaryRow(lines: List<String>, totalDue: Long): Long? {
        for (l in lines) {
            val v = amounts(l).mapNotNull { paise(it) }
            if (v.size !in 4..8 || abs(v.last() - abs(totalDue)) > 100) continue
            val middle = v.subList(1, v.size - 1)
            val fits = (0 until (1 shl middle.size)).any { mask ->
                val sum = v.first() + middle.withIndex().sumOf { (k, x) -> if (mask and (1 shl k) != 0) x else -x }
                abs(sum - v.last()) <= 100
            }
            if (fits) return v.first()
        }
        return null
    }

    private val BARE_DATE = Regex("(?:(\\w+)\\s+)?date\\s*:\\s*($DATE_RX)", RegexOption.IGNORE_CASE)
    private val NOT_STATEMENT = setOf("due", "payment", "birth", "transaction", "txn", "posting", "value", "expiry", "issue")

    /** A lone "Date: 15/03/2026" next to the address (HDFC), when no statement date label is found. */
    fun bareDate(lines: List<String>): LocalDate? =
        lines.firstNotNullOfOrNull { l ->
            BARE_DATE.findAll(l).firstOrNull { it.groupValues[1].lowercase() !in NOT_STATEMENT }?.let { date(it.groupValues[2]) }
        }

    fun amounts(s: String): List<String> = AMOUNT.findAll(s).map { it.value }.toList()

    private val SIGNED_AMOUNT = Regex("($AMOUNT_RX)(\\s*(?:Cr|CR|cr)\\b)?")

    /** Like [amounts], but an amount followed by "Cr" (a credit balance) comes back with a leading minus. */
    fun signedAmounts(s: String): List<String> =
        SIGNED_AMOUNT.findAll(s).map { m -> if (m.groupValues[2].isNotEmpty()) "-" + m.groupValues[1] else m.groupValues[1] }.toList()
    fun dateTokens(s: String): List<String> = DATE.findAll(s).map { it.value }.toList()

    fun paise(s: String): Long? = s.replace(",", "").toBigDecimalOrNull()?.movePointRight(2)?.toLong()

    private val formats: List<DateTimeFormatter> = listOf(
        "d/M/uuuu", "d-M-uuuu", "d.M.uuuu", "d/M/uu", "d-M-uu", "d.M.uu",
        "d MMM uuuu", "d-MMM-uuuu", "d MMM uu", "d-MMM-uu", "d MMMM uuuu", "d-MMMM-uuuu", "d MMM, uuuu",
        "MMM d, uuuu", "MMMM d, uuuu", "MMM d uuuu", "MMMM d uuuu",
    ).map {
        DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern(it).toFormatter(Locale.ENGLISH).withResolverStyle(ResolverStyle.STRICT)
    }

    fun date(raw: String): LocalDate? {
        val s = raw.trim().replace(Regex("\\s+"), " ").replace(Regex("(?i)sept"), "Sep")
        for (f in formats) runCatching { return LocalDate.parse(s, f) }
        return null
    }

    /** Last four digits from a masked card number like "XXXX XXXX XXXX 1234" or "4321XXXXXXXX1234". */
    fun last4(text: String): String? =
        Regex("(?:[Xx*•]{4}[ -]?){2,3}(\\d{4})\\b").find(text)?.groupValues?.get(1)
            ?: Regex("\\b\\d{4,8}[Xx*•]{4,8}(\\d{4})\\b").find(text)?.groupValues?.get(1)
            // Diners' 14 digits: "3610 10XXXX 1352".
            ?: Regex("\\b\\d{4} \\d{2}[Xx*•]{4,6} (\\d{4})\\b").find(text)?.groupValues?.get(1)
}

object Parsers {
    private val generic = LabelParser("generic")

    private val byIssuer: Map<String, StatementParser> = mapOf(
        "HDFC Bank" to LabelParser("hdfc", totalLabels = listOf("total dues"), openingLabels = listOf("opening balance")),
        "SBI Card" to LabelParser("sbi", totalLabels = listOf("total amount due"), openingLabels = listOf("previous balance")),
        "ICICI Bank" to LabelParser("icici", totalLabels = listOf("total amount due"), openingLabels = listOf("previous balance")),
        "Axis Bank" to LabelParser("axis", totalLabels = listOf("total payment due"), openingLabels = listOf("previous balance")),
        "Yes Bank" to LabelParser("yes", totalLabels = listOf("total amount due"), openingLabels = listOf("previous balance")),
        "Kotak" to LabelParser("kotak", totalLabels = listOf("total amount due"), openingLabels = listOf("previous amount due")),
        "IDFC FIRST" to LabelParser("idfc", totalLabels = listOf("total amount due"), openingLabels = listOf("opening balance")),
        "Amex" to LabelParser("amex", totalLabels = listOf("closing balance"), openingLabels = listOf("opening balance")),
        "Slice" to SliceParser(),
        "Unity Bank (Roar)" to UnityParser(),
    )

    fun forIssuer(issuer: String): StatementParser = byIssuer[issuer] ?: generic

    /**
     * The total check from the PDR: opening + debits - credits must equal the total due (₹1 slack
     * for rounding). Anything else is "needs review", never a silent import.
     */
    fun validate(p: ParsedStatement): Validation {
        if (p.totalDuePaise == null) return Validation(false, "Couldn't find the total amount due")
        if (p.dueDate == null) return Validation(false, "Couldn't find the payment due date")
        if (p.lines.isEmpty() && p.totalDuePaise != 0L && p.totalDuePaise != p.openingPaise) {
            return Validation(false, "No transaction lines found")
        }
        val opening = p.openingPaise ?: return Validation(false, "Couldn't find the opening balance to check the lines against")
        val computed = opening + p.debits - p.credits
        val diff = computed - p.totalDuePaise
        return if (abs(diff) <= 100) Validation(true, "")
        else Validation(false, "Lines don't add up: opening + debits − credits is off by ${app.cardkeep.domain.Money.inr(abs(diff), true)}")
    }

    fun validate(totalDue: Long?, dueKnown: Boolean, opening: Long?, debits: Long, credits: Long): Validation =
        when {
            totalDue == null -> Validation(false, "Total amount due is missing")
            !dueKnown -> Validation(false, "Payment due date is missing")
            opening == null -> Validation(false, "Opening balance is missing")
            abs(opening + debits - credits - totalDue) <= 100 -> Validation(true, "")
            else -> Validation(false, "Lines don't add up: off by ${app.cardkeep.domain.Money.inr(abs(opening + debits - credits - totalDue), true)}")
        }
}
