package app.cardkeep.ingest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Synthetic statements only. No real statement text belongs in this repo. */
class ParserTest {

    // HDFC 2025+ layout: ₹ extracted as "C", "+" after the description marks credits, a trailing "l"
    // category bullet, and the summary as an equation row whose labels sit two lines above.
    private val hdfcLayout = """
        Sample HDFC Bank Credit Card Statement
        TEST USER Credit Card No. 512345XXXXXX4321
        Statement Date 15 Sep, 2026
        Billing Period 16 Aug, 2026 - 15 Sep, 2026
        PAYMENTS/CREDITS PURCHASES/DEBIT
        PREVIOUS STATEMENT DUES FINANCE CHARGES TOTAL AMOUNT DUE
        RECEIVED (Current Billing Cycle)
        _
        C5,000.00 C5,012.00 + C2,734.40 + C0.00 = C2,722.40
        TOTAL CREDIT LIMIT
        AVAILABLE CREDIT LIMIT AVAILABLE CASH LIMIT
        (Including Cash) MINIMUM DUE DUE DATE
        C140.00 05 Oct, 2026
        C1,00,000 C97,277 C40,000
        Domestic Transactions
        DATE & TIME TRANSACTION DESCRIPTION AMOUNT PI
        17/08/2026| 18:35 BPPY CC PAYMENT DP0000000001 (Ref# ST000000000000001) +  C 5,000.00 l
        17/08/2026| 18:35 SAMPLE GROCER BANGALORE  C 410.00 l
        19/08/2026| 00:00 10% Sample CashBack +  C 12.00 l
        22/08/2026| 00:00 10% Sample CashBack_Reversal  C 37.40 l
        22/08/2026| 07:35 PYU*SampleShopBengaluru  C 1,996.00 l
        Page 1 of 2
        DATE & TIME TRANSACTION DESCRIPTION AMOUNT PI
        13/09/2026| 12:04 Sample FoodBENGALURU  C 291.00 l
        Page 2 of 2
        SR NO. TRANSACTION AMOUNT
        1 10% Sample CashBack C 12.00
    """.trimIndent()

    // slice app-style layout (synthetic): spaced card mask, no year in the period, two-line rows.
    private val sliceLayout = """
        Sample's
        Credit card statement
        X X X X  X X X X  X X X X  1 2 3 4
        15 OCT - 14 NOV
        ₹590.50 Repay
        Due on 1 Dec
        Statement summary
        Spends ₹701.50
        Refunds & repayments ₹100.00
        Cashback ₹11.00
        Interest ₹0.00
        Surcharge ₹0.00
        Total amount due ₹590.50
        Min amount due ₹29.53
        Spends
        Sample store ₹600
        S
        14 Nov '26 • UPI
        Corner cafe ₹101.50
        16 Oct '26 • UPI
        Refunds & repayments
        Sample store ₹100
        S
        23 Oct '26
        Cashback
        Corner cafe ₹11
        C
        11 Nov '26
        monies
        Earned 701
        15 Oct - 14 Nov
        GST details
        IGST ₹0
    """.trimIndent()

    @Test fun sliceLayout() {
        val p = Parsers.forIssuer("Slice").parse(sliceLayout)
        assertEquals(LocalDate.of(2026, 11, 14), p.statementDate)
        assertEquals(LocalDate.of(2026, 10, 15), p.periodStart)
        assertEquals(LocalDate.of(2026, 12, 1), p.dueDate)
        assertEquals(59_050L, p.totalDuePaise)
        assertEquals(2_953L, p.minDuePaise)
        assertEquals("1234", p.last4)
        assertEquals(4, p.lines.size)
        assertEquals(listOf(false, false, true, true), p.lines.map { it.isCredit })
        assertEquals(701L, p.rewards?.pointsEarned)
        assertEquals(1_100L, p.rewards?.cashbackPaise)
        assertTrue(Parsers.validate(p).note, Parsers.validate(p).ok)
    }

    // Period crossing the year end, with every row still in December.
    @Test fun sliceYearWrap() {
        val text = sliceLayout
            .replace("15 OCT - 14 NOV", "15 DEC - 14 JAN").replace("Due on 1 Dec", "Due on 3 Feb")
            .replace("14 Nov '26", "20 Dec '26").replace("16 Oct '26", "16 Dec '26")
            .replace("23 Oct '26", "23 Dec '26").replace("11 Nov '26", "28 Dec '26")
        for (received in listOf(null, LocalDate.of(2027, 1, 15))) {
            val p = Parsers.forIssuer("Slice").parse(text, received)
            assertEquals(LocalDate.of(2027, 1, 14), p.statementDate)
            assertEquals(LocalDate.of(2026, 12, 15), p.periodStart)
            assertEquals(LocalDate.of(2027, 2, 3), p.dueDate)
        }
    }

    // Overpaid card: "Cr" after the header amounts means a credit balance, not money owed.
    @Test fun creditBalanceIsNotDue() {
        val text = """
            Statement Period : 06 Oct 2026 to 05 Nov 2026
            Card Number XXXX XXXX XXXX 1234
            Total Amount Due: Rs. 700.00 Cr
            Minimum Amount Due: Rs. 0.00
            Payment Due Date: 25 Nov 2026
            Previous Balance 1,000.00 Cr
            10 Oct 2026 UBER INDIA 500.00 D
            18 Oct 2026 REFUND MYNTRA 200.00 C
        """.trimIndent()
        val p = Parsers.forIssuer("SBI Card").parse(text)
        assertEquals(-70_000L, p.totalDuePaise)
        assertEquals(-100_000L, p.openingPaise)
        assertEquals(0L, p.minDuePaise)
        assertTrue(Parsers.validate(p).note, Parsers.validate(p).ok)
    }

    @Test fun hdfcWrappedPaymentAndRewards() {
        val text = """
            Statement Date 15 Nov, 2026
            Billing Period 16 Oct, 2026 - 15 Nov, 2026
            Credit Card No. 00361010XXXX9999
            PREVIOUS STATEMENT DUES FINANCE CHARGES TOTAL AMOUNT DUE
            _
            C100.00 C100.00 + C563.02 + C0.00 = C563.02
            (Including Cash) MINIMUM DUE DUE DATE
            C200.00 05 Dec, 2026
            Reward Points Opening Balance Feature + Bonus Reward Disbursed Adjusted/Lapsed
            Points Earned
            1,008 1,000 8 0 0
            DATE & TIME TRANSACTION DESCRIPTION REWARDS AMOUNT PI
            15/10/2026| 12:47 SAMPLE TICKETS GURGAON + 8  C 563.02 l
            CC PAYMENT 000000000000 PayZapp (Ref#
            19/10/2026| 14:30 +  C 100.00 l
            00000000000000000000001)
            Page 1 of 2
        """.trimIndent()
        val p = Parsers.forIssuer("HDFC Bank").parse(text)
        assertEquals("9999", p.last4)
        assertEquals("SAMPLE TICKETS GURGAON", p.lines[0].description)
        assertTrue(p.lines[1].isCredit)
        assertTrue(p.lines[1].description.startsWith("CC PAYMENT"))
        assertEquals(1_008L, p.rewards?.pointsBalance)
        assertEquals(8L, p.rewards?.pointsEarned)
        assertTrue(Parsers.validate(p).note, Parsers.validate(p).ok)
    }

    // Unity SFB / Roarbank ledger layout (synthetic), including the illustration table that repeats labels.
    @Test fun unityLayout() {
        val text = """
            Roarbank
            Statement Period: 01.10.2026 - 31.10.2026
            Bill Date: 01.11.2026
            Card number: **** **** **** 1234
            Saving Account:
            Balance at the beginning of period: 0.00
            Credit Card:
            Sanctioned Credit Limit: 100,000.00
            Total Amount Due (TAD): 50.00
            Minimum Amount Due (MAD) including past dues if available: 50.00
            Payment Due Date: 27.11.2026
            Balance at the beginning of period: 99,900.00
            Date Description Credit Debit
            Amount Amount Fee Balance
            01-10-2026
            11:14:05 someone@upi 100.00 - - 100,000.00
            23-10-2026
            09:51:09 Sample Movies - 150.00 - 99,850.00
            23-10-2026
            09:52:01 Cashback 100.00 - - 99,950.00
            Minimum Amount due (MAD): INR 700
            Payment Due Date 28th July
        """.trimIndent()
        val p = Parsers.forIssuer("Unity Bank (Roar)").parse(text)
        assertEquals(LocalDate.of(2026, 11, 1), p.statementDate)
        assertEquals(LocalDate.of(2026, 11, 27), p.dueDate)
        assertEquals(5_000L, p.totalDuePaise)
        assertEquals(5_000L, p.minDuePaise)
        assertEquals(10_000L, p.openingPaise)
        assertEquals("1234", p.last4)
        assertEquals(3, p.lines.size)
        assertEquals(10_000L, p.rewards?.cashbackPaise)
        assertEquals(10_000_000L, p.creditLimitPaise)
        assertTrue(Parsers.validate(p).note, Parsers.validate(p).ok)
    }

    // Limit raised from 1,00,000 to 1,50,000 mid-cycle: once as an explicit row, once only as a jump in Balance.
    @Test fun unityLimitChange() {
        fun stmt(limitRow: Boolean) = """
            Statement Period: 01.10.2026 - 31.10.2026
            Bill Date: 01.11.2026
            Card number: **** **** **** 1234
            Credit Card:
            Sanctioned Credit Limit: 150,000.00
            Total Amount Due (TAD): 1,200.00
            Minimum Amount Due (MAD) including past dues if available: 60.00
            Payment Due Date: 27.11.2026
            Balance at the beginning of period: 99,000.00
            05-10-2026
            10:00:00 Sample Store - 200.00 - 98,800.00
            ${if (limitRow) "12-10-2026\n10:00:00 Credit limit enhancement 50,000.00 - - 148,800.00" else ""}
            20-10-2026
            10:00:00 Sample Cafe - ${if (limitRow) "0.00" else "0.00"} - ${if (limitRow) "148,800.00" else "148,800.00"}
        """.trimIndent()
        for (row in listOf(true, false)) {
            val p = Parsers.forIssuer("Unity Bank (Roar)").parse(stmt(row))
            assertEquals("opening with row=$row", 100_000L, p.openingPaise) // 1,00,000 − 99,000 at the start
            assertTrue("no limit row as a transaction (row=$row)", p.lines.none { it.description.contains("limit", true) })
            assertTrue(Parsers.validate(p).note, Parsers.validate(p).ok)
        }
    }

    // Newer Roarbank layout (synthetic): Balance is the debt as a negative number; a description can start on the date's line.
    @Test fun unityOwedLayout() {
        val text = """
            Statement Period: 01.07.2026 - 31.07.2026
            Bill Date: 01.08.2026
            Card number: **** **** **** 1234
            Saving Account:
            Balance at the beginning of period: 0.00
            Credit Card:
            Sanctioned Credit Limit: 100,000.00
            Total Amount Due (TAD): 330.12
            Minimum Amount Due (MAD) including past dues if available: 330.12
            Payment Due Date: 28.08.2026
            Balance at the beginning of period: -1,536.29
            Total Credit transactions for the period: 1,536.29
            Total Debit transactions for the period: 330.12
            01-07-2026
            11:02:04 someone@upi 1,536.29 - - 0.00
            02-07-2026
            20:04:06 SAMPLE STORE - 70.00 - -70.00
            31-07-2026 SAMPLE CINEMAS
            09:22:52 PRIVATE LIMITED - 260.12 - -330.12
        """.trimIndent()
        val p = Parsers.forIssuer("Unity Bank (Roar)").parse(text)
        assertEquals(153_629L, p.openingPaise)
        assertEquals(3, p.lines.size)
        assertEquals("SAMPLE CINEMAS PRIVATE LIMITED", p.lines[2].description)
        assertTrue(Parsers.validate(p).note, Parsers.validate(p).ok)
    }

    // Roarbank, Jan 2026 layout (synthetic): no card starting balance (savings has one), rows on one line, descriptions wrapped below.
    @Test fun unityWrappedRows() {
        val text = """
            Statement Period: 01.01.2026 - 31.01.2026
            Bill Date: 01.02.2026
            Card number: 652974****1234
            Saving Account:
            Balance at the beginning of period: 0.00
            Credit Card:
            Credit limit: 5,000.00
            Total Amount Due (TAD): 390.00
            Minimum Amount Due (MAD) including past dues if available: 390.00
            Payment Due Date: 25.02.2026
            Date Description Credit Amount Debit Amount Fee Balance
            01-01-2026 1234567890@up 1,269.00 - - 0.00
            09:28:33 i
            04-01-2026 SAMPLE - 155.00 - -155.00
            16:51:22 STATE
            TRANSPORT
            31-01-2026 Kalyani R - 235.00 - -390.00
            12:58:45
            *Note that Charges, including GST, reduce the available credit limit
        """.trimIndent()
        val p = Parsers.forIssuer("Unity Bank (Roar)").parse(text)
        assertEquals(126_900L, p.openingPaise)
        assertEquals(listOf("1234567890@upi", "SAMPLE STATE TRANSPORT", "Kalyani R"), p.lines.map { it.description })
        assertTrue(Parsers.validate(p).note, Parsers.validate(p).ok)
    }

    // SBI-style (synthetic): tax on a fee on an undated line, and a statement with nothing to pay and no due date.
    @Test fun undatedTaxLineAndNothingDue() {
        val fee = """
            *Total Amount Due ( ` )
            1,009.00
            Statement Date Payment Due Date
            16 Jun 2026 06 Jul 2026
            Previous Balance Reversals & other Purchases & Other Fee, Taxes & Total Outstanding
            ( ` ) Credits ( ` ) Debits ( ` ) Interest Charges ( ` ) ( ` )
            0.00 0.00 420.00 588.82 1,009.00
            16 Jun 26 ANNUAL FEE CHARGED (EXCL TAX 89.82) 499.00 D
            IGST DB @ 18.00% 89.82 D
            28 May 26 SAMPLE STORE Bangalore IND 420.00 D
        """.trimIndent()
        val p = Parsers.forIssuer("SBI Card").parse(fee)
        assertEquals(3, p.lines.size)
        assertEquals("IGST DB @ 18.00%", p.lines[1].description)
        assertTrue(Parsers.validate(p).note, Parsers.validate(p).ok)

        val nothing = """
            *Total Amount Due ( ` )
            0.00
            Statement Date
            16 Aug 2026
            Payment Due Date
            NO PAYMENT REQUIRED
            Previous Balance Reversals & other Purchases & Other Fee, Taxes & Total Outstanding
            ( ` ) Credits ( ` ) Debits ( ` ) Interest Charges ( ` ) ( ` )
            2,438.37 2,439.00 0.00 0.00 1.00 CR
            17 Jul 26 PAYMENT RECEIVED 000DP0161981 2,439.00 C
        """.trimIndent()
        val q = Parsers.forIssuer("SBI Card").parse(nothing)
        assertEquals(243_837L, q.openingPaise)
        assertTrue(Parsers.validate(q).note, Parsers.validate(q).ok)
    }

    // HDFC Diners (synthetic): column values a few lines below their headings, a bare "Date:", and the summary as a row of figures.
    @Test fun hdfcDinersLayout() {
        val text = """
            Diners Club International Credit Card Statement
            Name : SAMPLE NAME Statement for HDFC Bank Credit Card
            Email : someone@example.com Statement Card No: 3610 10XXXX 4321
            0 Address : 1 Sample Street Date:15/03/2026 AAN : 0001
            Payment Due Date Total Dues Minimum Amount Due
            GST No :
            0
            03/I2n 0 c2as6e you wish to update 04/04/2026 1,100.00 100.00
            If the Minimum Amount Due is paid, Opening Payment/ Purchase/ Finance
            Total Dues
            than the Total Amount Due, Interest Balance Credits Debits Charges
            any) on an average daily reducing balance method. 500.00 500.00 1,100.00 0.00 1,100.00
            Domestic Transactions
            Date Transaction Description Feature Reward Amount (in Rs.)
            17/02/2026 10:06:39 CC PAYMENT 123456 PayZapp (Ref# 000001) 500.00 Cr
            27/02/2026 20:24:37 SAMPLE STORE SALEM 18 1,100.00
        """.trimIndent()
        val p = Parsers.forIssuer("HDFC Bank").parse(text)
        assertEquals(LocalDate.of(2026, 3, 15), p.statementDate)
        assertEquals(LocalDate.of(2026, 4, 4), p.dueDate)
        assertEquals(110_000L, p.totalDuePaise)
        assertEquals(50_000L, p.openingPaise)
        assertEquals("4321", p.last4)
        assertTrue(Parsers.validate(p).note, Parsers.validate(p).ok)
    }

    // Axis (synthetic): a period column takes two dates; the summary figures sit two lines under their equation.
    @Test fun axisPeriodColumn() {
        val text = """
            Total Payment Due Minimum Payment Due Statement Period Payment Due Date Statement Generation Date
            1,020.00 Dr 100.00 Dr 14/06/2026 - 12/07/2026 01/08/2026 12/07/2026
            Card Number 653036******4321
            Previous Balance - Payments - Credits + Purchase + Cash Advance + Other Debit&Charges =Total Payment Due Making only the minimum payment every
            month would result in the repayment stretching
            500.00 Dr 500.00 30.00 1,050.00 0.00 0.00 1,020.00 Dr over years
            14/06/2026 BBPS PAYMENT RECEIVED - ABC123 500.00 Cr
            19/06/2026 SAMPLE STORE 1,050.00 Dr
            08/07/2026 CASHBACK CREDIT 30.00 Cr
        """.trimIndent()
        val p = Parsers.forIssuer("Axis Bank").parse(text)
        assertEquals(LocalDate.of(2026, 7, 12), p.statementDate)
        assertEquals(LocalDate.of(2026, 8, 1), p.dueDate)
        assertEquals(50_000L, p.openingPaise)
        assertEquals(3, p.lines.size)
        assertTrue(Parsers.validate(p).note, Parsers.validate(p).ok)
    }

    @Test fun hdfcLayout() {
        val p = Parsers.forIssuer("HDFC Bank").parse(hdfcLayout)
        assertEquals(LocalDate.of(2026, 9, 15), p.statementDate)
        assertEquals(LocalDate.of(2026, 10, 5), p.dueDate)
        assertEquals(272_240L, p.totalDuePaise)
        assertEquals(14_000L, p.minDuePaise)
        assertEquals(500_000L, p.openingPaise)
        assertEquals("4321", p.last4)
        assertEquals(6, p.lines.size)
        assertEquals(listOf(true, false, true, false, false, false), p.lines.map { it.isCredit })
        assertEquals("SAMPLE GROCER BANGALORE", p.lines[1].description)
        assertEquals(41_000L, p.lines[1].amountPaise)
        assertEquals(10_000_000L, p.creditLimitPaise)
        assertTrue(Parsers.validate(p).note, Parsers.validate(p).ok)
    }

    // Column layout: labels on one line, values on the next.
    private val columnLayout = """
        Credit Card Statement
        Card No: 4321 XXXX XXXX 9876
        Statement Date Payment Due Date Total Dues Minimum Amount Due
        05/11/2026 25/11/2026 12,450.50 625.00
        Opening Balance 3,000.00
        Domestic Transactions
        Date Transaction Description Amount (in Rs.)
        08/10/2026 SWIGGY BANGALORE 450.00
        12/10/2026 AMAZON PAY INDIA PVT LTD 8,999.00
        15/10/2026 PAYMENT RECEIVED - THANK YOU 3,000.00 Cr
        20/10/2026 BIGBASKET 2,999.50
        02/11/2026 IGST ON FEES 2.00
    """.trimIndent()

    // Inline layout with month-name dates and a period line.
    private val inlineLayout = """
        Statement Period : 06 Oct 2026 to 05 Nov 2026
        Card Number XXXX XXXX XXXX 1234
        Total Amount Due: Rs. 5,500.00
        Minimum Amount Due: Rs. 275.00
        Payment Due Date: 25 Nov 2026
        Previous Balance 1,000.00
        10 Oct 2026 UBER INDIA 500.00 D
        11 Oct 2026 NETFLIX COM 649.00 D
        18 Oct 2026 REFUND MYNTRA 149.00 C
        21 Oct 2026 IRCTC TICKET 3,500.00 D
    """.trimIndent()

    @Test fun columnLayoutAddsUp() {
        val p = Parsers.forIssuer("HDFC Bank").parse(columnLayout)
        assertEquals(LocalDate.of(2026, 11, 5), p.statementDate)
        assertEquals(LocalDate.of(2026, 11, 25), p.dueDate)
        assertEquals(1_245_050L, p.totalDuePaise)
        assertEquals(62_500L, p.minDuePaise)
        assertEquals(300_000L, p.openingPaise)
        assertEquals("9876", p.last4)
        assertEquals(5, p.lines.size)
        assertTrue(p.lines.single { it.description.startsWith("PAYMENT") }.isCredit)
        assertTrue(Parsers.validate(p).note, Parsers.validate(p).ok)
    }

    @Test fun inlineLayoutAddsUp() {
        val p = Parsers.forIssuer("SBI Card").parse(inlineLayout)
        assertEquals(LocalDate.of(2026, 10, 6), p.periodStart)
        assertEquals(LocalDate.of(2026, 11, 5), p.statementDate)
        assertEquals(LocalDate.of(2026, 11, 25), p.dueDate)
        assertEquals(550_000L, p.totalDuePaise)
        assertEquals(27_500L, p.minDuePaise)
        assertEquals("1234", p.last4)
        assertEquals(4, p.lines.size)
        assertTrue(p.lines.single { it.description.contains("MYNTRA") }.isCredit)
        assertTrue(Parsers.validate(p).note, Parsers.validate(p).ok)
    }

    @Test fun missingLineIsFlaggedNotImported() {
        val p = Parsers.forIssuer("SBI Card").parse(inlineLayout.lines().filterNot { it.contains("IRCTC") }.joinToString("\n"))
        val v = Parsers.validate(p)
        assertFalse(v.ok)
        assertTrue(v.note.contains("off by"))
    }

    @Test fun txnKinds() {
        assertEquals(TxnKind.PAYMENT, TxnKind.classify("BPPY CC PAYMENT DP0000000001", true))
        assertEquals(TxnKind.PAYMENT, TxnKind.classify("PAYMENT RECEIVED - THANK YOU", true))
        assertEquals(TxnKind.REFUND, TxnKind.classify("REFUND MYNTRA", true))
        assertEquals(TxnKind.REFUND, TxnKind.classify("Sample store", true))
        assertEquals(TxnKind.CASHBACK, TxnKind.classify("10% Sample CashBack", true))
        assertEquals(TxnKind.CASHBACK, TxnKind.classify("10% Sample CashBack_Reversal", false))
        assertEquals(TxnKind.FEE, TxnKind.classify("IGST ON FEES", false))
        assertEquals(TxnKind.FEE, TxnKind.classify("Fee: someone@upi", false))
        assertEquals(TxnKind.FEE, TxnKind.classify("FINANCE CHARGES", false))
        assertEquals(TxnKind.SPEND, TxnKind.classify("AIRTEL RECHARGE", false))
        assertEquals(TxnKind.SPEND, TxnKind.classify("SWIGGY BANGALORE", false))
        // HDFC's "convert to EMI" link label is not an EMI line.
        assertEquals("Flipkart Internet PVTBanglore", Text.stripEmiLink("EMI Flipkart Internet PVTBanglore"))
        assertEquals(TxnKind.SPEND, TxnKind.classify("EMI Flipkart Internet PVTBanglore", false))
        assertEquals("EMI INTEREST 2/6", Text.stripEmiLink("EMI INTEREST 2/6"))
        assertEquals("EMI PRINCIPAL 2/6", Text.stripEmiLink("EMI PRINCIPAL 2/6"))
        assertEquals("EMI 2/6 SAMPLE STORE", Text.stripEmiLink("EMI 2/6 SAMPLE STORE"))
        assertEquals(TxnKind.FEE, TxnKind.classify("EMI INTEREST 2/6", false))
        assertEquals(TxnKind.FEE, TxnKind.classify("EMI PROCESSING FEE", false))
        // Refunds reduce spending; payments and cashback don't count.
        assertEquals(-1, TxnKind.spendSign(TxnKind.REFUND, true))
        assertEquals(0, TxnKind.spendSign(TxnKind.PAYMENT, true))
        assertEquals(0, TxnKind.spendSign(TxnKind.CASHBACK, true))
    }

    @Test fun passwordCandidates() {
        val c = Passwords.candidates(PdfProfile(name = "Dash Kumar", dob = LocalDate.of(1990, 5, 14)), listOf("1234"))
        assertTrue("DASH1405" in c)
        assertTrue("dash1405" in c)
        assertTrue("140519901234" in c)
        assertFalse(c.any { it.contains("{") })
    }
}
