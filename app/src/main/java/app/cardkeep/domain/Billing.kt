package app.cardkeep.domain

import app.cardkeep.data.Card
import app.cardkeep.data.Payment
import app.cardkeep.data.Statement
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.min

/**
 * The cycle a card is currently in.
 * [dueDate] is the next date the user has to act on: the latest statement's due date, or, once
 * that has been marked paid, the next cycle's.
 */
data class DueInfo(
    val statementDate: LocalDate,
    val dueDate: LocalDate,
    val daysLeft: Long,
    val overdue: Boolean,
    /** The most recent generated statement is settled (we are now showing the next cycle). */
    val lastCyclePaid: Boolean,
)

data class CardWithDue(val card: Card, val due: DueInfo) {
    val utilization: Float
        get() = if (card.limitPaise <= 0) 0f
        else (card.outstandingPaise.toFloat() / card.limitPaise).coerceIn(0f, 1f)
}

object Billing {
    fun statementDate(ym: YearMonth, day: Int): LocalDate = ym.atDay(min(day.coerceAtLeast(1), ym.lengthOfMonth()))

    /**
     * Due date source of truth: the latest parsed statement for the current cycle when there is
     * one, else statement day + offset from the card settings.
     */
    fun dueInfo(card: Card, payments: List<Payment>, today: LocalDate = LocalDate.now(), statements: List<Statement> = emptyList()): DueInfo {
        var ym = YearMonth.from(today)
        var stmt = statementDate(ym, card.statementDay)
        if (stmt.isAfter(today)) {
            ym = ym.minusMonths(1)
            stmt = statementDate(ym, card.statementDay)
        }
        var due = stmt.plusDays(card.dueOffsetDays.toLong())

        val parsed = statements.asSequence()
            .filter { it.cardId == card.id && it.dueEpochDay != null }
            .maxByOrNull { it.statementEpochDay }
        // A statement in credit ("Cr", total ≤ 0) has nothing to pay this cycle.
        var nothingDue = false
        if (parsed != null) {
            val pStmt = LocalDate.ofEpochDay(parsed.statementEpochDay)
            // Same cycle if within ~10 days of the computed statement date (banks shift for holidays).
            if (!pStmt.isAfter(today) && !pStmt.isBefore(stmt.minusDays(10))) {
                stmt = pStmt
                due = LocalDate.ofEpochDay(parsed.dueEpochDay!!)
                nothingDue = parsed.totalDuePaise?.let { it <= 0 } == true
            }
        }
        val created = Instant.ofEpochMilli(card.createdAt).atZone(ZoneId.systemDefault()).toLocalDate()
        val paidDues = payments.asSequence().filter { it.cardId == card.id }.map { it.dueEpochDay }.toSet()

        // A cycle whose due date passed before the card was added is treated as settled,
        // so a freshly added card never starts out "overdue".
        val settled = due.toEpochDay() in paidDues || due.isBefore(created) || nothingDue
        if (!settled) {
            val days = ChronoUnit.DAYS.between(today, due)
            return DueInfo(stmt, due, days, overdue = days < 0, lastCyclePaid = false)
        }
        val nextStmt = statementDate(ym.plusMonths(1), card.statementDay)
        val nextDue = nextStmt.plusDays(card.dueOffsetDays.toLong())
        return DueInfo(nextStmt, nextDue, ChronoUnit.DAYS.between(today, nextDue), overdue = false, lastCyclePaid = true)
    }

    fun parseLeads(s: String): List<Int> =
        s.split(',', ' ').mapNotNull { it.trim().toIntOrNull() }.filter { it in 0..60 }.distinct().sortedDescending()
}
