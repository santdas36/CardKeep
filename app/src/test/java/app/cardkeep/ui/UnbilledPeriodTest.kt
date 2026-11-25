package app.cardkeep.ui

import app.cardkeep.data.Card
import app.cardkeep.data.CardKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class UnbilledPeriodTest {
    private fun card(day: Int = 16, kind: String = CardKind.CREDIT, billing: Boolean = true) =
        Card(nickname = "A", issuer = "HDFC Bank", network = "Visa", last4 = "1234", statementDay = day, color = 0, kind = kind, billingSet = billing)

    @Test fun afterClosingDayTheOldCycleIsBilled() {
        // Closes on the 16th; on 25 Nov the 16 Oct – 16 Nov spends are already billed.
        val p = UnbilledPeriod.of(card(), lastStatementDay = null, today = LocalDate.of(2026, 11, 25))
        assertEquals(LocalDate.of(2026, 11, 17), p.start)
        assertEquals(LocalDate.of(2026, 12, 16), p.end)
        assertTrue(p.isCycle)
    }

    @Test fun beforeClosingDayTheCycleIsStillOpen() {
        val p = UnbilledPeriod.of(card(), lastStatementDay = null, today = LocalDate.of(2026, 11, 10))
        assertEquals(LocalDate.of(2026, 10, 17), p.start)
        assertEquals(LocalDate.of(2026, 11, 16), p.end)
    }

    @Test fun aNewerStatementWins() {
        val stmt = LocalDate.of(2026, 11, 18).toEpochDay()
        val p = UnbilledPeriod.of(card(), lastStatementDay = stmt, today = LocalDate.of(2026, 11, 25))
        assertEquals(LocalDate.of(2026, 11, 19), p.start)
    }

    @Test fun debitCardsUseTheMonth() {
        val p = UnbilledPeriod.of(card(kind = CardKind.DEBIT), lastStatementDay = null, today = LocalDate.of(2026, 11, 25))
        assertEquals(LocalDate.of(2026, 11, 1), p.start)
        assertFalse(p.isCycle)
    }
}
