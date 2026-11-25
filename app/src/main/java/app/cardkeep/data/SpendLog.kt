package app.cardkeep.data

import android.util.Base64
import app.cardkeep.ingest.Categorizer
import app.cardkeep.ingest.DefaultCategories
import app.cardkeep.ingest.TxnKind
import app.cardkeep.ingest.alerts.CardAlert
import kotlinx.coroutines.flow.Flow
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs

/**
 * The spend log: card transactions from bank alerts and manual entries, and the check against statements.
 * Statements stay the bank's record; this never changes them.
 */
class SpendLog(private val dao: SpendDao, private val ledger: LedgerDao) {

    val spends: Flow<List<Spend>> = dao.observeSpends()
    val misses: Flow<List<AlertMiss>> = dao.observeMisses()

    private val zone: ZoneId get() = ZoneId.systemDefault()

    fun day(millis: Long): Long = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate().toEpochDay()

    /**
     * Logs [alert] against [card]. [receivedAt] is used when the alert has no time of its own.
     * Returns the new spend, or null when it was already logged (same message, or the same transaction
     * from another source a few minutes apart).
     */
    suspend fun record(alert: CardAlert, card: Card, source: String, receivedAt: Long): Spend? {
        val at = alert.atMillis?.takeIf { abs(it - receivedAt) < 3 * DAY } ?: receivedAt
        val key = sha("alert|${card.id}|${alert.kind}|${alert.isCredit}|${alert.amountPaise}|${at / 60_000}|${normal(alert.merchant)}")
        if (dao.countNear(card.id, alert.amountPaise, alert.isCredit, at - NEAR, at + NEAR) > 0) return null
        val s = Spend(
            cardId = card.id, atMillis = at, epochDay = day(at), amountPaise = alert.amountPaise, isCredit = alert.isCredit,
            kind = alert.kind, currency = alert.currency, merchant = alert.merchant,
            categoryId = if (alert.kind == TxnKind.SPEND || alert.kind == TxnKind.REFUND) categorizer().categorise(alert.merchant) else null,
            note = "", source = source, availablePaise = alert.availablePaise, reference = alert.reference,
            dedupeKey = key, review = SpendReview.NONE, statementId = null, statementTxnId = null,
        )
        val id = dao.insert(s)
        if (id == -1L) return null
        reconcileCard(card.id)
        return dao.spend(id)
    }

    /** Adds or edits a spend by hand. */
    suspend fun save(s: Spend): Long {
        val fixed = s.copy(epochDay = day(s.atMillis))
        val id = if (s.id == 0L) {
            dao.insert(
                fixed.copy(
                    dedupeKey = sha("manual|${s.cardId}|${s.atMillis}|${s.amountPaise}|${System.nanoTime()}"),
                    categoryId = s.categoryId ?: categorizer().categorise(s.merchant),
                    source = SpendSource.MANUAL,
                ),
            )
        } else fixed.id.also { dao.update(fixed) }
        reconcileCard(s.cardId)
        return id
    }

    suspend fun delete(s: Spend) {
        dao.delete(s.id)
        reconcileCard(s.cardId)
    }

    suspend fun setNote(s: Spend, note: String) = dao.update(s.copy(note = note.trim()))

    /** Accepts a flagged spend as is. */
    suspend fun dismiss(s: Spend) = dao.update(s.copy(review = SpendReview.DISMISSED))

    /** Sets one spend's category; with [pattern], also saves a rule and applies it everywhere. */
    suspend fun setCategory(s: Spend, categoryId: Long?, pattern: String?) {
        dao.update(s.copy(categoryId = categoryId))
        val p = pattern?.trim()?.lowercase()
        if (!p.isNullOrBlank() && categoryId != null) {
            ledger.deleteRulesFor(p)
            ledger.insertRule(CategoryRule(categoryId = categoryId, pattern = p, priority = DefaultCategories.USER_PRIORITY))
            ledger.recategorise(p, categoryId)
            dao.recategorise(p, categoryId)
        }
    }

    /** A statement line that has no spend, added to the log from the review. */
    suspend fun addFromStatement(t: Txn) {
        val at = LocalDate.ofEpochDay(t.epochDay).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        dao.insert(
            Spend(
                cardId = t.cardId, atMillis = at, epochDay = t.epochDay, amountPaise = t.amountPaise, isCredit = t.isCredit,
                kind = t.kind, currency = "INR", merchant = t.merchant, categoryId = t.categoryId, note = t.note,
                source = SpendSource.MANUAL, availablePaise = null, reference = t.reference,
                dedupeKey = sha("stmt|${t.id}|${t.dedupeKey}"), review = SpendReview.NONE, statementId = null, statementTxnId = null,
            ),
        )
        reconcileCard(t.cardId)
    }

    // ---- Alerts that couldn't be logged ----

    suspend fun miss(sender: String, body: String, reason: String, source: String) {
        dao.pruneMisses(System.currentTimeMillis() - 7 * DAY)
        if (dao.countMiss(sender, body) == 0) dao.insertMiss(AlertMiss(sender = sender, body = body, at = System.currentTimeMillis(), reason = reason, source = source))
    }

    suspend fun clearMisses() = dao.clearMisses()

    // ---- Checking against statements ----

    /**
     * Re-checks every spend of [cardId] against its statements. Idempotent: run after any change to the
     * card's spends or statements. Returns how many spends are newly flagged.
     */
    suspend fun reconcileCard(cardId: Long): Int {
        val spends = dao.spendsFor(cardId)
        if (spends.isEmpty()) return 0
        val statements = ledger.statements().filter { it.cardId == cardId }.sortedBy { it.statementEpochDay }
        val lines = statements.associate { it.id to ledger.txnsFor(it.id) }
        val periods = statements.mapIndexed { i, s ->
            val start = s.periodStartEpochDay ?: statements.getOrNull(i - 1)?.let { it.statementEpochDay + 1 } ?: (s.statementEpochDay - 30)
            Period(s, start, s.statementEpochDay, statements.getOrNull(i + 1))
        }
        val used = HashSet<Long>()
        val changed = ArrayList<Spend>()
        var flagged = 0
        // Manual spends and ones already accepted take part (so their lines aren't reused) but keep their state.
        for (s in spends.sortedBy { it.atMillis }) {
            val p = periods.firstOrNull { s.epochDay in it.start..it.end }
            val result = if (p == null) Result(SpendReview.NONE, null, null) else check(s, p, lines, used)
            result.txn?.let { used += it.id }
            val review = if (s.review == SpendReview.DISMISSED && result.review != SpendReview.MATCHED) SpendReview.DISMISSED else result.review
            if (review != s.review || result.txn?.id != s.statementTxnId) {
                if (SpendReview.flagged(review) && !SpendReview.flagged(s.review)) flagged++
                changed += s.copy(review = review, statementId = result.statement?.id, statementTxnId = result.txn?.id)
            }
        }
        if (changed.isNotEmpty()) dao.updateAll(changed)
        return flagged
    }

    private class Period(val statement: Statement, val start: Long, val end: Long, val next: Statement?)
    private class Result(val review: String, val statement: Statement?, val txn: Txn?)

    private fun check(s: Spend, p: Period, lines: Map<Long, List<Txn>>, used: Set<Long>): Result {
        // Spends near the closing date often post in the next statement.
        val pool = (lines[p.statement.id].orEmpty().map { p.statement to it } +
            (p.next?.let { n -> lines[n.id].orEmpty().map { n to it } } ?: emptyList()))
            .filter { (_, t) -> t.id !in used && t.isCredit == s.isCredit && t.epochDay - s.epochDay in -1..4 }
        val foreign = s.currency != "INR"
        val exact = pool.filter { (_, t) -> !foreign && t.amountPaise == s.amountPaise }
            .minWithOrNull(compareByDescending<Pair<Statement, Txn>> { similarity(s.merchant, it.second.merchant) }.thenBy { abs(it.second.epochDay - s.epochDay) })
        if (exact != null) return Result(SpendReview.MATCHED, exact.first, exact.second)
        val near = pool.filter { (_, t) -> similarity(s.merchant, t.merchant) >= 0.5 }
            .minByOrNull { abs(it.second.epochDay - s.epochDay) }
        if (near != null) {
            // Spends abroad are billed in rupees, so a different amount is expected there.
            val review = if (foreign) SpendReview.MATCHED else SpendReview.DIFFERS
            return Result(review, near.first, near.second)
        }
        val settled = p.next != null || s.epochDay <= p.end - LATE_DAYS
        return Result(if (settled) SpendReview.MISSING else SpendReview.NONE, null, null)
    }

    private suspend fun categorizer() = Categorizer(ledger.rules())

    companion object {
        private const val DAY = 86_400_000L
        private const val NEAR = 3 * 60_000L
        /** Spends this close to the closing date may only show up on the next statement. */
        private const val LATE_DAYS = 3

        private fun normal(s: String) = s.lowercase().replace(Regex("\\s+"), " ").trim()

        private fun words(s: String) = s.lowercase().replace(Regex("[^a-z0-9 ]"), " ").split(' ')
            .filter { it.length >= 3 && it.any(Char::isLetter) }.toSet()

        /** 0..1: how alike two merchant names are. Handles truncated names ("SWIGGY INST" vs "SWIGGY INSTAMART"). */
        fun similarity(a: String, b: String): Double {
            val x = a.lowercase().filter(Char::isLetterOrDigit)
            val y = b.lowercase().filter(Char::isLetterOrDigit)
            if (x.isEmpty() || y.isEmpty()) return 0.0
            val n = minOf(x.length, y.length, 8)
            if (n >= 5 && (x.take(n) == y.take(n) || x.contains(y.take(n)) || y.contains(x.take(n)))) return 1.0
            val wa = words(a)
            val wb = words(b)
            if (wa.isEmpty() || wb.isEmpty()) return 0.0
            val shared = wa.count { w -> wb.any { it.startsWith(w) || w.startsWith(it) } }
            return shared.toDouble() / minOf(wa.size, wb.size)
        }

        private fun sha(s: String): String =
            Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(s.toByteArray()), Base64.NO_WRAP or Base64.NO_PADDING)
    }
}
