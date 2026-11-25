package app.cardkeep.ingest.alerts

import app.cardkeep.ingest.TxnKind
import app.cardkeep.ingest.htmlToText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/** Alert emails (synthetic text in each bank's wording), parsed after HTML is turned into text. */
class EmailAlertTest {
    private fun ist(y: Int, mo: Int, d: Int, h: Int = 0, mi: Int = 0, s: Int = 0): Long =
        LocalDateTime.of(y, mo, d, h, mi, s).atZone(ZoneId.of("Asia/Kolkata")).toInstant().toEpochMilli()

    private val now = ist(2026, 11, 25, 12)

    @Test fun hdfcSpendEmail() {
        val html = """
            <html><head><style>p{color:red}</style></head><body>
            <img alt="Festive offers"><p>Dear Customer,</p><p>Greetings from HDFC Bank.</p>
            <p>We would like to inform you that <b>Rs. 426.90</b> has been debited from your HDFC Bank Credit Card ending
            <b>1234</b> towards <b>SAMPLE TICKETS COM</b> on <b>20 Nov, 2026 at 13:08:34</b>.<br>
            To check your available balance, outstanding amount, or view recent transactions, you may use:</p>
            <ul><li><b>Mycards:</b><a href="https://example.com">https://example.com</a></li></ul>
            <p><b>Important Note:</b><br>If you did not authorise this transaction, please act immediately.</p></body></html>
        """.trimIndent()
        val a = AlertParser.parseEmail("A payment was made using your Credit Card", htmlToText(html), now)!!
        assertEquals("HDFC Bank", a.issuer)
        assertEquals("1234", a.last4)
        assertEquals(TxnKind.SPEND, a.kind)
        assertEquals(42_690L, a.amountPaise)
        assertEquals("SAMPLE TICKETS COM", a.merchant.uppercase())
        assertEquals("credit", a.cardType)
        assertEquals(ist(2026, 11, 20, 13, 8, 34), a.atMillis)
    }

    @Test fun hdfcReversalEmail() {
        val text = """
            Dear Customer,
            Greetings from HDFC Bank!
            A transaction reversal of Rs. 426.90 has been initiated to your HDFC Bank Credit Card ending 1234
            From Merchant: SAMPLE TICKETS COM
            Date Time: 20 Nov, 2026 at 18:44:38
            Please allow up to 48 hours for the reversal to reflect in your card statement.
            Report Unauthorised Transaction: Submit the Online Dispute Form
        """.trimIndent()
        val a = AlertParser.parseEmail("A refund was processed to your Credit Card from the merchant", text, now)!!
        assertEquals(TxnKind.REFUND, a.kind)
        assertEquals(true, a.isCredit)
        assertEquals(42_690L, a.amountPaise)
        assertEquals("1234", a.last4)
        assertEquals(ist(2026, 11, 20, 18, 44, 38), a.atMillis)
    }

    @Test fun sliceSpendEmail() {
        val html = """
            <table><tr><td>Hi Sam,</td></tr>
            <tr><td>&#8377;123 spent on your slice credit card xx1234 via UPI.</td></tr>
            <tr><td>Transaction date</td><td>25-Nov-26</td></tr>
            <tr><td>To</td><td>Sample cafe</td></tr>
            <tr><td>RRN</td><td>600000000001</td></tr>
            <tr><td>Best,<br>Team slice</td></tr></table>
        """.trimIndent()
        val a = AlertParser.parseEmail("₹123 spent on your credit card", htmlToText(html), now)!!
        assertEquals("Slice", a.issuer)
        assertEquals("1234", a.last4)
        assertEquals(12_300L, a.amountPaise)
        assertEquals("sample cafe", a.merchant.lowercase())
        assertEquals("600000000001", a.reference)
        // A date with no time is moved to when the email arrived, if that's the same day.
        val received = ist(2026, 11, 25, 13, 47)
        assertEquals(received, EmailAlerts.betterTime(a.atMillis, received))
    }

    @Test fun offersAndOtpEmailsAreSkipped() {
        assertNull(AlertParser.parseEmail("Exclusive offers on your Credit Card", "Rs. 500 cashback offer. Apply now.", now))
        assertNull(AlertParser.parseEmail("OTP for your transaction", "Your OTP is 123456 for Rs. 426.90 at SAMPLE", now))
    }
}
