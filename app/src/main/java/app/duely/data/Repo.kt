package app.duely.data

import android.content.Context
import androidx.room.withTransaction
import app.duely.DuelyApp
import app.duely.domain.Billing
import app.duely.domain.CardWithDue
import app.duely.reminders.Reminders
import app.duely.security.EncryptedDraft
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.time.LocalDate

class Repo(private val dao: CardDao, private val ledgerDao: LedgerDao, private val context: Context) {

    val ledger = Ledger(ledgerDao) { cardId -> syncOutstandingFromStatement(cardId) }

    /** Every card, archived included (for history: activity, statements, detail). */
    val everyCard: Flow<List<CardWithDue>> =
        combine(dao.observeCards(), dao.observePayments(), ledgerDao.observeStatements()) { cards, payments, statements ->
            val today = LocalDate.now()
            cards.map { CardWithDue(it, Billing.dueInfo(it, payments, today, statements)) }
                // Cards without a due date (its dueDate is only a placeholder) go after those with one.
                .sortedWith(compareByDescending<CardWithDue> { it.card.pinned }.thenBy { !it.card.billingSet }.thenBy { it.due.dueDate })
        }

    /** Active cards with their current cycle, pinned first, then soonest due. */
    val cards: Flow<List<CardWithDue>> = everyCard.map { list -> list.filter { it.card.archivedAt == null } }

    /** Archived cards, most recently archived first. */
    val archived: Flow<List<CardWithDue>> =
        everyCard.map { list -> list.filter { it.card.archivedAt != null }.sortedByDescending { it.card.archivedAt } }

    val payments: Flow<List<Payment>> = dao.observePayments()

    fun card(id: Long): Flow<CardWithDue?> = everyCard.map { list -> list.firstOrNull { it.card.id == id } }

    suspend fun cardOnce(id: Long): Card? = dao.card(id)

    suspend fun allCards(): List<Card> = dao.cards()

    /** Cards that statements can be matched to (not archived). */
    suspend fun activeCards(includeAll: Boolean = false): List<Card> =
        dao.cards().filter { it.archivedAt == null && (includeAll || it.isCredit) }

    /**
     * Archive (deactivate) or restore a card. Archiving unpins it and cancels its reminders;
     * restoring schedules them again. Statements, transactions, payments and vault data are kept.
     */
    suspend fun setArchived(id: Long, archived: Boolean) {
        val c = dao.card(id) ?: return
        dao.update(c.copy(archivedAt = if (archived) System.currentTimeMillis() else null, pinned = if (archived) false else c.pinned))
        if (archived) Reminders.cancel(context, id) else reschedule(id)
    }

    suspend fun cardWithDue(id: Long): CardWithDue? {
        val c = dao.card(id) ?: return null
        return CardWithDue(c, Billing.dueInfo(c, dao.paymentsFor(id), LocalDate.now(), ledgerDao.statements()))
    }

    suspend fun snapshot(): List<CardWithDue> {
        val payments = dao.payments()
        val statements = ledgerDao.statements()
        val today = LocalDate.now()
        return dao.cards().filter { it.archivedAt == null }.map { CardWithDue(it, Billing.dueInfo(it, payments, today, statements)) }
    }

    suspend fun secret(id: Long): CardSecret? = dao.secret(id)

    /** Saved cardholder names for suggestions, most used first. */
    val holderNames: Flow<List<HolderName>> = dao.observeHolderNames()

    /** A card was saved with [name]: add it if new, and count the use. */
    suspend fun useHolderName(name: String) {
        val n = HolderName.normalise(name).takeIf { it.isNotEmpty() } ?: return
        val now = System.currentTimeMillis()
        DuelyApp.from(context).db.withTransaction {
            dao.insertHolderName(n, now)
            dao.bumpHolderName(n, now)
        }
    }

    /** Adds a name from the picker (no uses yet). Returns the stored form. */
    suspend fun addHolderName(name: String): String? {
        val n = HolderName.normalise(name).takeIf { it.isNotEmpty() } ?: return null
        dao.insertHolderName(n, System.currentTimeMillis())
        return n
    }

    suspend fun removeHolderName(name: String) = dao.deleteHolderName(name)

    /** Inserts or updates [card]; non-null fields of [secrets] replace the stored ones. */
    suspend fun save(card: Card, secrets: EncryptedDraft?): Long {
        // The edit form doesn't know about archiving; keep whatever state the stored row has.
        val id = if (card.id == 0L) dao.insert(card) else card.id.also { dao.update(card.copy(archivedAt = dao.card(it)?.archivedAt)) }
        if (secrets != null) {
            val old = dao.secret(id)
            dao.upsertSecret(
                CardSecret(
                    cardId = id,
                    numberEnc = secrets.number ?: old?.numberEnc,
                    expiryEnc = secrets.expiry ?: old?.expiryEnc,
                    cvvEnc = secrets.cvv ?: old?.cvvEnc,
                    holderEnc = secrets.holder ?: old?.holderEnc,
                ),
            )
        }
        reschedule(id)
        return id
    }

    suspend fun clearCvv(id: Long) {
        val old = dao.secret(id) ?: return
        dao.upsertSecret(CardSecret(id, old.numberEnc, old.expiryEnc, null, old.holderEnc))
    }

    suspend fun delete(id: Long) {
        Reminders.cancel(context, id)
        dao.delete(id)
    }

    /**
     * Pins (up to [app.duely.ui.MAX_PINS]) or unpins a card. When the limit is reached, [replacing] names the
     * pinned card that makes room; without it the pin is refused and false is returned.
     */
    suspend fun setPinned(card: Card, pinned: Boolean, replacing: Long? = null): Boolean =
        // Check first and write both rows together, so a refused pin changes nothing.
        DuelyApp.from(context).db.withTransaction {
            if (pinned) {
                val others = dao.cards().count { it.pinned && it.archivedAt == null && it.id != card.id && it.id != replacing }
                if (others >= app.duely.ui.MAX_PINS) return@withTransaction false
                replacing?.takeIf { it != card.id }?.let { id -> dao.card(id)?.let { dao.update(it.copy(pinned = false)) } }
            }
            dao.card(card.id)?.let { dao.update(it.copy(pinned = pinned)) }
            true
        }

    suspend fun setOutstanding(id: Long, paise: Long) {
        val c = dao.card(id) ?: return
        dao.update(c.copy(outstandingPaise = paise))
    }

    /** Records a payment against the cycle due on [dueEpochDay] and silences its reminders. */
    suspend fun markPaid(cardId: Long, dueEpochDay: Long, amountPaise: Long) {
        val c = dao.card(cardId) ?: return
        dao.insertPayment(Payment(cardId = cardId, dueEpochDay = dueEpochDay, paidOnEpochDay = LocalDate.now().toEpochDay(), amountPaise = amountPaise))
        dao.update(c.copy(outstandingPaise = (c.outstandingPaise - amountPaise).coerceAtLeast(0)))
        reschedule(cardId)
    }

    suspend fun deletePayment(p: Payment) {
        dao.deletePayment(p.id)
        reschedule(p.cardId)
    }

    /** After a statement import or edit: outstanding = newest statement's total due minus payments against it. */
    private suspend fun syncOutstandingFromStatement(cardId: Long) {
        var c = dao.card(cardId) ?: return
        val mine = ledgerDao.statements().filter { it.cardId == cardId }
        // The newest statement sets the card's limit and billing cycle.
        mine.maxByOrNull { it.statementEpochDay }?.let { s ->
            val offset = s.dueEpochDay?.let { (it - s.statementEpochDay).toInt() }?.takeIf { it in 0..60 }
            c = c.copy(
                limitPaise = s.creditLimitPaise?.takeIf { it > 0 } ?: c.limitPaise,
                statementDay = LocalDate.ofEpochDay(s.statementEpochDay).dayOfMonth,
                dueOffsetDays = offset ?: c.dueOffsetDays,
                billingSet = true, // a real statement gives the cycle, even if it was skipped when adding the card
            )
        }
        val latest = mine.filter { it.totalDuePaise != null }.maxByOrNull { it.statementEpochDay }
        if (latest != null) {
            val paid = dao.paymentsFor(cardId).filter { it.dueEpochDay == latest.dueEpochDay }.sumOf { it.amountPaise }
            c = c.copy(outstandingPaise = (latest.totalDuePaise!! - paid).coerceAtLeast(0))
        }
        dao.update(c)
        reschedule(cardId)
    }

    private suspend fun reschedule(id: Long) {
        val cw = cardWithDue(id) ?: return
        if (cw.card.archivedAt != null || !cw.card.hasDues) Reminders.cancel(context, id) else Reminders.schedule(context, cw)
    }
}
