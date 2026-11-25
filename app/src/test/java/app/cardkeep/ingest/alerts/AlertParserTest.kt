package app.cardkeep.ingest.alerts

import app.cardkeep.ingest.TxnKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/** Synthetic messages only. No real alert text belongs in this repo. */
class AlertParserTest {

    private fun ist(y: Int, mo: Int, d: Int, h: Int = 0, mi: Int = 0, s: Int = 0): Long =
        LocalDateTime.of(y, mo, d, h, mi, s).atZone(ZoneId.of("Asia/Kolkata")).toInstant().toEpochMilli()

    private val now = ist(2026, 11, 25, 12)

    private fun parse(sender: String?, body: String) = AlertParser.parse(sender, body, now)

    private fun ok(sender: String?, body: String): CardAlert = assertNotNull("should parse: $body", parse(sender, body)).let { parse(sender, body)!! }

    private fun rejected(body: String, sender: String? = "AD-HDFCBK-S") =
        assertNull("should reject: $body", parse(sender, body))

    // ---- header / issuer

    @Test fun headerStripsDltPrefixAndSuffix() {
        assertEquals("HDFCBK", AlertParser.header("AD-HDFCBK-S"))
        assertEquals("AXISBK", AlertParser.header("VM-AXISBK-T"))
        assertEquals("SLCBNK", AlertParser.header("JD-SLCBNK"))
    }

    @Test fun headerDropsPromoAndPhoneNumbers() {
        assertNull(AlertParser.header("VM-PROMO-P"))
        assertNull(AlertParser.header("+919876543210"))
        assertNull(AlertParser.header("09876543210"))
    }

    @Test fun headerKeepsNonDltNames() {
        assertEquals("HDFC BANK", AlertParser.header("  HDFC Bank "))
    }

    @Test fun issuerFromHeaderTable() {
        assertEquals("HDFC Bank", AlertParser.issuer("AD-HDFCBK-S", "x"))
        assertEquals("HDFC Bank", AlertParser.issuer("AD-HDFCBN-S", "x"))
        assertEquals("Axis Bank", AlertParser.issuer("AD-AXISBK-S", "x"))
        assertEquals("SBI Card", AlertParser.issuer("AD-SBICRD-S", "x"))
        assertEquals("SBI Card", AlertParser.issuer("AD-MYSBIC-S", "x"))
        assertEquals("Yes Bank", AlertParser.issuer("AD-YESBNK-S", "x"))
        assertEquals("Slice", AlertParser.issuer("AD-SLCBNK-S", "x"))
        assertEquals("Unity Bank (Roar)", AlertParser.issuer("AD-UNTYCC-S", "x"))
        assertEquals("Indian Bank", AlertParser.issuer("AD-INDBNK-S", "x"))
        assertEquals("ICICI Bank", AlertParser.issuer("AD-ICICIB-S", "x"))
        assertEquals("IndusInd", AlertParser.issuer("AD-INDUSB-S", "x"))
        assertEquals("State Bank of India", AlertParser.issuer("AD-SBIINB-S", "nothing here"))
        assertNull(AlertParser.issuer("AD-ZOMATO-S", "nothing here"))
    }

    @Test fun issuerFromBodyOnly() {
        assertEquals("HDFC Bank", AlertParser.issuer(null, "Spent Rs.10 On HDFC Bank Card 1234 At X"))
        assertEquals("Axis Bank", AlertParser.issuer(null, "Spent INR 10 Axis Bank Card no. XX1234"))
        assertEquals("Yes Bank", AlertParser.issuer(null, "INR 10 spent on YES BANK Card X1234"))
        assertEquals("Slice", AlertParser.issuer(null, "Rs. 10 spent on your credit card xx1234 - slice"))
        assertEquals("Unity Bank (Roar)", AlertParser.issuer(null, "Rs. 10 spent at X. -Roarbank by Unity SFB"))
        assertEquals("Indian Bank", AlertParser.issuer(null, "Rs.10 spent using Indian Bank Debit Card XX1234"))
        assertEquals("Amex", AlertParser.issuer(null, "You've spent INR 10 on your AMEX card ** 12345"))
        assertNull(AlertParser.issuer(null, "Rs 10 spent on card ending 1234"))
    }

    @Test fun issuerFallsBackToBodyWhenHeaderUnknown() {
        assertEquals("HDFC Bank", AlertParser.issuer("ALERTS", "HDFC Bank Credit Card"))
        assertEquals("HDFC Bank", AlertParser.issuer("HDFC Bank", "anything"))
    }

    // ---- HDFC

    @Test fun hdfcCreditSpend() {
        val a = ok("AD-HDFCBK-S", "Spent Rs.1,250.50 On HDFC Bank Card 1234 At ..CAFE COFFEE_ On 2026-11-05:14:22:10.Not You? To Block+Reissue Call 18000000/SMS BLOCK CC 1234 to 7308080808")
        assertEquals("HDFC Bank", a.issuer)
        assertEquals("1234", a.last4)
        assertEquals(TxnKind.SPEND, a.kind)
        assertEquals(false, a.isCredit)
        assertEquals(125050L, a.amountPaise)
        assertEquals("INR", a.currency)
        assertEquals("CAFE COFFEE", a.merchant)
        assertEquals("credit", a.cardType)
        assertEquals(ist(2026, 11, 5, 14, 22, 10), a.atMillis)
    }

    @Test fun hdfcDebitSpendMultilineWithBalance() {
        val a = ok("AD-HDFCBK-S", "Spent Rs.499.00 From HDFC Bank Card x4321 At AMAZON On 2026-11-06:09:10:11\nBal Rs.12,345.67\nNot You? To Block+Reissue Call 18000000/SMS BLOCK DC 4321 to 7308080808")
        assertEquals("4321", a.last4)
        assertEquals(TxnKind.SPEND, a.kind)
        assertEquals(49900L, a.amountPaise)
        assertEquals("AMAZON", a.merchant)
        assertEquals("debit", a.cardType)
        assertEquals(1234567L, a.availablePaise)
        assertEquals(ist(2026, 11, 6, 9, 10, 11), a.atMillis)
    }

    @Test fun hdfcAtmWithdrawal() {
        val a = ok("AD-HDFCBK-S", "Withdrawn Rs.5000 From HDFC Bank Card x4321 At ATM MG ROAD On 2026-11-06:09:10:11 Bal Rs.1,000.00 Not You? BLOCK DC 4321")
        assertEquals(500000L, a.amountPaise)
        assertEquals("debit", a.cardType)
        assertEquals("ATM MG ROAD", a.merchant)
    }

    @Test fun hdfcDebitNoPin() {
        val a = ok("AD-HDFCBK-S", "ALERT:Rs.799.00 spent via HDFC BANK Debit Card xx4321 at SWIGGY on Nov 07 2026 5:42PM without PIN/OTP.")
        assertEquals("4321", a.last4)
        assertEquals(79900L, a.amountPaise)
        assertEquals("SWIGGY", a.merchant)
        assertEquals("debit", a.cardType)
        assertEquals(ist(2026, 11, 7, 17, 42), a.atMillis)
    }

    @Test fun hdfcUpiOnCreditCard() {
        val a = ok("AD-HDFCBK-S", "Txn Rs.150.00\nOn HDFC Bank Card 1234\nAt swiggy@ybl\nby UPI 612345678901\nOn 05-11\nNot you? Call 18000000")
        assertEquals(TxnKind.SPEND, a.kind)
        assertEquals("1234", a.last4)
        assertEquals(15000L, a.amountPaise)
        assertEquals("swiggy@ybl", a.merchant)
        assertEquals("612345678901", a.reference)
        assertEquals("credit", a.cardType)
        assertEquals(ist(2026, 11, 5), a.atMillis)
    }

    @Test fun hdfcUpiWithNoMerchantAndDateWrapsToPreviousYear() {
        val a = ok("AD-HDFCBK-S", "Txn Rs.20.00\nOn HDFC Bank Card 1234\nAt NA\nby UPI 612345678901\nOn 30-12\nNot you? Call 18000000")
        assertEquals("", a.merchant)
        assertEquals(ist(2025, 12, 30), a.atMillis)
    }

    @Test fun hdfcRefundTrimsPaddedMerchantAndCity() {
        val a = ok("AD-HDFCBK-S", "Alert! Rs. 899.00 refunded by AMAZON          BENGALURU    IND on 05/Nov/2026 & adjusted against HDFC Bank Credit Card 1234. It will reflect soon")
        assertEquals(TxnKind.REFUND, a.kind)
        assertEquals(true, a.isCredit)
        assertEquals(89900L, a.amountPaise)
        assertEquals("AMAZON", a.merchant)
        assertEquals("1234", a.last4)
        assertEquals(ist(2026, 11, 5), a.atMillis)
    }

    @Test fun hdfcRefundWithSingleSpacedCity() {
        val a = ok("AD-HDFCBK-S", "Alert! Rs. 120.00 refunded by AMAZON PAY BENGALURU IND on 05/Nov/2026 & adjusted against HDFC Bank Credit Card 1234.")
        assertEquals("AMAZON PAY", a.merchant)
    }

    @Test fun hdfcReversal() {
        val a = ok("AD-HDFCBK-S", "Transaction Reversed!On HDFC Bank CREDIT Card xx1234 Amt: Rs.350.00 By SWIGGY On 2026-11-08:10:00:05")
        assertEquals(TxnKind.REFUND, a.kind)
        assertEquals(true, a.isCredit)
        assertEquals(35000L, a.amountPaise)
        assertEquals("SWIGGY", a.merchant)
        assertEquals("credit", a.cardType)
        assertEquals(ist(2026, 11, 8, 10, 0, 5), a.atMillis)
    }

    @Test fun hdfcPaymentReceived() {
        val a = ok("AD-HDFCBK-S", "DEAR HDFCBANK CARDMEMBER, PAYMENT OF Rs. 5,000.00 RECEIVED TOWARDS YOUR CREDIT CARD ENDING WITH 1234 ON 05-11-2026.YOUR AVAILABLE LIMIT IS RS. 95,000.00")
        assertEquals(TxnKind.PAYMENT, a.kind)
        assertEquals(true, a.isCredit)
        assertEquals(500000L, a.amountPaise)
        assertEquals("1234", a.last4)
        assertEquals(9500000L, a.availablePaise)
        assertEquals(ist(2026, 11, 5), a.atMillis)
    }

    @Test fun hdfcSmartPayBillPaid() {
        val a = ok("AD-HDFCBK-S", "Bill Paid: ELECTRICITY BOARD Bill 123456 of Rs. 1,200.00 paid on 05-Nov-2026 from HDFC Bank Credit Card xxXXXXXXXXXXXX1234 via SmartPay.")
        assertEquals(TxnKind.SPEND, a.kind)
        assertEquals(false, a.isCredit)
        assertEquals(120000L, a.amountPaise)
        assertEquals("ELECTRICITY BOARD", a.merchant)
        assertEquals("1234", a.last4)
        assertEquals("credit", a.cardType)
        assertEquals(ist(2026, 11, 5), a.atMillis)
    }

    @Test fun hdfcSmartPayBillPaidOnDebitCard() {
        val a = ok("AD-HDFCBK-S", "Bill Paid: WATER BOARD Bill 998877 of Rs. 300.00 paid on 05-Nov-2026 from HDFC Bank Debit Card xxXXXXXXXXXXXX4321 via SmartPay.")
        assertEquals("debit", a.cardType)
        assertEquals("4321", a.last4)
    }

    @Test fun hdfcSuccessfullyDebitedToPayBill() {
        val a = ok("AD-HDFCBK-S", "We have successfully debited your HDFC Bank Credit card ending 1234 to pay your ELECTRICITY BOARD bill for the amount Rs 1,200.00")
        assertEquals(TxnKind.SPEND, a.kind)
        assertEquals(120000L, a.amountPaise)
        assertEquals("ELECTRICITY BOARD", a.merchant)
        assertEquals("credit", a.cardType)
    }

    // ---- Axis

    @Test fun axisSpend() {
        val a = ok("AD-AXISBK-S", "Spent INR 2,340.00\nAxis Bank Card no. XX1234\n05-11-26 14:22:10 IST\nCAFE COFFEE\nAvl Limit: INR 45,000.50\nNot you? SMS BLOCK 1234 to 919000000000")
        assertEquals("Axis Bank", a.issuer)
        assertEquals("1234", a.last4)
        assertEquals(TxnKind.SPEND, a.kind)
        assertEquals(234000L, a.amountPaise)
        assertEquals("CAFE COFFEE", a.merchant)
        assertEquals(4500050L, a.availablePaise)
        assertEquals(ist(2026, 11, 5, 14, 22, 10), a.atMillis)
    }

    @Test fun axisPaymentReceived() {
        val a = ok("AD-AXISBK-S", "Payment of INR 10,000.00 has been received towards your Axis Bank Credit Card XX1234 on 05-11-26 - Axis Bank")
        assertEquals(TxnKind.PAYMENT, a.kind)
        assertEquals(true, a.isCredit)
        assertEquals(1000000L, a.amountPaise)
        assertEquals("credit", a.cardType)
        assertEquals(ist(2026, 11, 5), a.atMillis)
    }

    @Test fun axisCashback() {
        val a = ok("AD-AXISBK-S", "Cashback of INR 125.00 has been credited to your Sample Axis Bank Credit Card XX1234 towards your spends during Oct'26.")
        assertEquals(TxnKind.CASHBACK, a.kind)
        assertEquals(true, a.isCredit)
        assertEquals(12500L, a.amountPaise)
        assertEquals("1234", a.last4)
    }

    // ---- SBI Card

    @Test fun sbiCardSpend() {
        val a = ok("AD-SBICRD-S", "Rs.1,499.00 spent on your SBI Credit Card ending 1234 at CAFE COFFEE on 05/11/26. Trxn. not done by you? Report at https://example.com/x")
        assertEquals("SBI Card", a.issuer)
        assertEquals("1234", a.last4)
        assertEquals(149900L, a.amountPaise)
        assertEquals("CAFE COFFEE", a.merchant)
        assertEquals("credit", a.cardType)
        assertEquals(ist(2026, 11, 5), a.atMillis)
    }

    // ---- Yes Bank

    @Test fun yesBankSpend() {
        val a = ok("AD-YESBNK-S", "INR 899.00 spent on YES BANK Card X1234 @SWIGGY 05-11-2026 06:15:30 pm. Avl Lmt INR 50,000.00. SMS BLKCC 1234 to 9223000000 if not you")
        assertEquals("Yes Bank", a.issuer)
        assertEquals("1234", a.last4)
        assertEquals(89900L, a.amountPaise)
        assertEquals("SWIGGY", a.merchant)
        assertEquals(5000000L, a.availablePaise)
        assertEquals(ist(2026, 11, 5, 18, 15, 30), a.atMillis)
    }

    @Test fun yesBankPaymentReceived() {
        val a = ok("AD-YESBNK-S", "payment of Rs.3,000.00 is received towards your YES BANK Credit Card ending 1234. It will reflect in your Credit Card within 2-3 working days")
        assertEquals(TxnKind.PAYMENT, a.kind)
        assertEquals(true, a.isCredit)
        assertEquals(300000L, a.amountPaise)
        assertEquals("1234", a.last4)
    }

    // ---- slice

    @Test fun sliceSpend() {
        val a = ok("AD-SLCBNK-S", "Rs. 250.00 spent on your credit card xx1234 at SWIGGY on 05-Nov-26 (UPI Ref: 612345678901). Not you? Call 18000000 - slice")
        assertEquals("Slice", a.issuer)
        assertEquals("1234", a.last4)
        assertEquals(25000L, a.amountPaise)
        assertEquals("SWIGGY", a.merchant)
        assertEquals("612345678901", a.reference)
        assertEquals(ist(2026, 11, 5), a.atMillis)
    }

    @Test fun sliceRefund() {
        val a = ok("AD-SLCBNK-S", "Refund of Rs. 250.00 from SWIGGY has been successfully processed to your credit card xxxx1234 - slice")
        assertEquals(TxnKind.REFUND, a.kind)
        assertEquals(true, a.isCredit)
        assertEquals("SWIGGY", a.merchant)
        assertEquals("1234", a.last4)
    }

    @Test fun sliceRepaymentHasNoCardDigits() {
        val a = ok("AD-SLCBNK-S", "Repayment of Rs.2,000.00 received for the slice credit card. Thank you - slice")
        assertEquals(TxnKind.PAYMENT, a.kind)
        assertEquals(true, a.isCredit)
        assertEquals(200000L, a.amountPaise)
        assertNull(a.last4)
        assertEquals("Slice", a.issuer)
    }

    // ---- Unity / Roarbank

    @Test fun unityCreditSpend() {
        val a = ok("AD-UNTYCC-S", "Rs. 450.00 spent at CAFE COFFEE.\nCard: 1234  Time: 05/11/2026, 02:15pm.\nAvl Limit: Rs. 20,000.00.\nNot you? Call 18000000\n-Roarbank by Unity SFB")
        assertEquals("Unity Bank (Roar)", a.issuer)
        assertEquals("1234", a.last4)
        assertEquals(TxnKind.SPEND, a.kind)
        assertEquals(45000L, a.amountPaise)
        assertEquals("CAFE COFFEE", a.merchant)
        assertEquals("credit", a.cardType)
        assertEquals(2000000L, a.availablePaise)
        assertEquals(ist(2026, 11, 5, 14, 15), a.atMillis)
    }

    @Test fun unityDebitSpend() {
        val a = ok("AD-UNTYCC-S", "Rs. 99.00 debited at AMAZON.\nCard: 4321 Time: 05/11/2026, 10:05am.\nAvl Balance: Rs. 1,500.00.\n-Roarbank by Unity SFB")
        assertEquals("debit", a.cardType)
        assertEquals(TxnKind.SPEND, a.kind)
        assertEquals(150000L, a.availablePaise)
        assertEquals(ist(2026, 11, 5, 10, 5), a.atMillis)
    }

    @Test fun unityCredit() {
        val a = ok("AD-UNTYCC-S", "Rs. 75.00 credited to your card 4321.\nAvl Balance: Rs. 1,575.00.\n-Roarbank by Unity SFB")
        assertEquals(TxnKind.REFUND, a.kind)
        assertEquals(true, a.isCredit)
        assertEquals(7500L, a.amountPaise)
        assertEquals("4321", a.last4)
        assertEquals(157500L, a.availablePaise)
    }

    // ---- Indian Bank

    @Test fun indianBankDebitSpend() {
        val a = ok("AD-INDBNK-S", "Rs.320.00 spent using Indian Bank Debit Card XX4321 at CAFE COFFEE on 05-11-26 14:22 hr. Avl Bal Rs.8,000.00.")
        assertEquals("Indian Bank", a.issuer)
        assertEquals("4321", a.last4)
        assertEquals(32000L, a.amountPaise)
        assertEquals("CAFE COFFEE", a.merchant)
        assertEquals("debit", a.cardType)
        assertEquals(800000L, a.availablePaise)
        assertEquals(ist(2026, 11, 5, 14, 22), a.atMillis)
    }

    // ---- best effort banks

    @Test fun iciciSpend() {
        val a = ok("AD-ICICIB-S", "INR 1,000.00 spent using ICICI Bank Card XX1234 on 05-Nov-26 on SWIGGY. Avl Limit: INR 40,000.00. If not you, call 18000000")
        assertEquals("ICICI Bank", a.issuer)
        assertEquals("1234", a.last4)
        assertEquals(100000L, a.amountPaise)
        assertEquals("SWIGGY", a.merchant)
        assertEquals(4000000L, a.availablePaise)
        assertEquals(ist(2026, 11, 5), a.atMillis)
    }

    @Test fun iciciForeignCurrency() {
        val a = ok("AD-ICICIB-S", "USD 12.50 spent using ICICI Bank Card XX1234 on 05-Nov-26 on NETFLIX. Avl Limit: INR 40,000.00.")
        assertEquals("USD", a.currency)
        assertEquals(1250L, a.amountPaise)
    }

    @Test fun idfcFirstSpend() {
        val a = ok("AD-IDFCFB-S", "INR 640.00 spent on your IDFC FIRST Bank Credit Card ending XX1234 at CAFE COFFEE on 05-NOV-2026 at 02:15 PM. Avbl Limit: INR 30,000.00")
        assertEquals("IDFC FIRST", a.issuer)
        assertEquals("1234", a.last4)
        assertEquals("CAFE COFFEE", a.merchant)
        assertEquals(3000000L, a.availablePaise)
        assertEquals(ist(2026, 11, 5), a.atMillis)
    }

    @Test fun federalSpend() {
        val a = ok("AD-FEDBNK-S", "INR 560.00 spent on your credit card ending with 1234 at CAFE COFFEE on 05-11-2026. Available limit Rs.25,000.00")
        assertEquals("Federal Bank", a.issuer)
        assertEquals("CAFE COFFEE", a.merchant)
        assertEquals(2500000L, a.availablePaise)
    }

    @Test fun auBankSpend() {
        val a = ok("AD-AUBANK-S", "INR 410.00 spent at CAFE COFFEE on AU Bank Credit Card x1234 05-11-2026 details")
        assertEquals("AU Bank", a.issuer)
        assertEquals("CAFE COFFEE", a.merchant)
        assertEquals("1234", a.last4)
    }

    @Test fun amexSpendFiveDigits() {
        val a = ok("AD-AMEXIN-S", "Alert: You've spent INR 1,200.00 on your AMEX card ** 12345 at CAFE COFFEE on 5 November 2026 at 02:30 PM IST.")
        assertEquals("Amex", a.issuer)
        assertEquals("12345", a.last4)
        assertEquals(120000L, a.amountPaise)
        assertEquals("CAFE COFFEE", a.merchant)
        assertEquals(ist(2026, 11, 5), a.atMillis)
    }

    @Test fun oneCardSpend() {
        val a = ok("AD-ONECRD-S", "You have spent Rs.350.00 at CAFE COFFEE using your OneCard card ending 1234.")
        assertEquals("OneCard", a.issuer)
        assertEquals("1234", a.last4)
        assertEquals(35000L, a.amountPaise)
    }

    // ---- generic fallback

    @Test fun genericSpendWithKnownIssuerFromSender() {
        val a = ok("AD-KOTAKB-S", "Rs 300.00 spent on card ending 5678 at ZOMATO on 01-11-26. Avl Bal Rs 9,000.00")
        assertEquals("Kotak", a.issuer)
        assertEquals("5678", a.last4)
        assertEquals(TxnKind.SPEND, a.kind)
        assertEquals(30000L, a.amountPaise)
        assertEquals("ZOMATO", a.merchant)
        assertEquals(900000L, a.availablePaise)
        assertEquals(ist(2026, 11, 1), a.atMillis)
    }

    @Test fun genericUnknownIssuerAndForeignCurrency() {
        val a = ok(null, "USD 25.00 spent on Card XX9876 at NETFLIX.")
        assertNull(a.issuer)
        assertEquals("9876", a.last4)
        assertEquals("USD", a.currency)
        assertEquals(2500L, a.amountPaise)
        assertEquals("NETFLIX", a.merchant)
    }

    @Test fun genericLowercaseCardX() {
        val a = ok("AD-RBLBNK-S", "₹ 1,23,456.78 debited from your card x1234 at AMAZON.")
        assertEquals("RBL Bank", a.issuer)
        assertEquals(12345678L, a.amountPaise)
        assertEquals("AMAZON", a.merchant)
    }

    @Test fun genericRefundAndPayment() {
        val r = ok("AD-RBLBNK-S", "Refund of Rs.200.00 from AMAZON credited to your card ending 1234.")
        assertEquals(TxnKind.REFUND, r.kind)
        assertEquals(true, r.isCredit)
        assertEquals("AMAZON", r.merchant)
        val p = ok("AD-RBLBNK-S", "Payment of Rs.700.00 received on your credit card ending 1234. Thank you")
        assertEquals(TxnKind.PAYMENT, p.kind)
        assertEquals("credit", p.cardType)
    }

    @Test fun genericNeedsCardDigits() {
        rejected("Rs 300.00 spent at ZOMATO on 01-11-26", sender = "AD-KOTAKB-S")
    }

    // ---- date formats and sender handling

    @Test fun dateFormats() {
        assertEquals(ist(2026, 11, 5, 14, 22, 10), AlertParser.parseDate("2026-11-05:14:22:10", now))
        assertEquals(ist(2026, 11, 5, 14, 22, 10), AlertParser.parseDate("05-11-26 14:22:10", now))
        assertEquals(ist(2026, 11, 5), AlertParser.parseDate("05/11/26", now))
        assertEquals(ist(2026, 11, 5), AlertParser.parseDate("05-NOV-26", now))
        assertEquals(ist(2026, 11, 5), AlertParser.parseDate("05/nov/2026", now))
        assertEquals(ist(2026, 11, 5, 18, 15, 30), AlertParser.parseDate("05-11-2026 06:15:30 PM", now))
        assertEquals(ist(2026, 11, 5, 14, 15), AlertParser.parseDate("05/11/2026, 02:15pm", now))
        assertEquals(ist(2026, 11, 5, 14, 22), AlertParser.parseDate("05-11-26 14:22", now))
        assertEquals(ist(2026, 11, 7, 17, 42), AlertParser.parseDate("Nov 07 2026 5:42PM", now))
        assertEquals(ist(2026, 11, 7, 0, 5), AlertParser.parseDate("Nov 07 2026 12:05AM", now))
        assertEquals(ist(2026, 11, 20), AlertParser.parseDate("20-11", now))
        assertEquals(ist(2025, 11, 26), AlertParser.parseDate("26-11", now))
        assertNull(AlertParser.parseDate("31-02-26", now))
    }

    @Test fun senderOptionalForKnownBankText() {
        val a = ok(null, "Spent Rs.100.00 On HDFC Bank Card 1234 At SWIGGY On 2026-11-05:14:22:10.Not You?")
        assertEquals("HDFC Bank", a.issuer)
    }

    @Test fun promotionalSenderStillParsesBodyShape() {
        // The caller drops -P senders via header(); parse() itself judges the body.
        assertNull(AlertParser.header("AD-HDFCBK-P"))
    }

    @Test fun oversizedBodyIgnored() {
        val body = "Spent Rs.100.00 On HDFC Bank Card 1234 At SWIGGY On 2026-11-05:14:22:10 " + "x".repeat(2100)
        assertNull(parse("AD-HDFCBK-S", body))
        assertNull(parse("AD-HDFCBK-S", "   "))
    }

    // ---- rejections

    @Test fun rejectsOtp() {
        rejected("123456 is your OTP for transaction of Rs.500.00 on HDFC Bank Card 1234 at SWIGGY. Do not share")
        rejected("Use verification code 482913 to pay Rs.500 on card ending 1234")
    }

    @Test fun rejectsOffers() {
        rejected("Get 10% off: offer on HDFC Bank Card 1234. Spend Rs.5000 and win a gift voucher")
        rejected("Your gift code is ABC123. Spent Rs.500 on card ending 1234")
    }

    @Test fun rejectsDueAndReminders() {
        rejected("Amount Due\nRs.4,500.00 on HDFC Bank Credit Card 1234 by 15-11-2026")
        rejected("Payment reminder: Rs.4,500.00 is due on your Axis Bank Credit Card XX1234")
        rejected("Min due Rs.200.00 on card ending 1234 pay by 15-11-2026")
    }

    @Test fun rejectsStatements() {
        rejected("HDFC Bank Credit Card XX1234 Statement:\nTotal due: Rs.4,500.00\nMin.due: Rs.200.00\nPay by 15-11-2026\nView: https://example.com/s")
        rejected("E-statement of SBI Credit Card ending XX12 dated 05/11/2026 has been mailed. Total Amt Due Rs 4,500.00; Min Amt Due Rs 200.00", "AD-SBICRD-S")
        rejected("YES BANK Credit Card XX1234 Nov-26 statement: Total due INR 4,500.00 Min due INR 200.00 Due by 15-Nov-2026.", "AD-YESBNK-S")
    }

    @Test fun rejectsScheduledDebits() {
        rejected("SmartPay Scheduled: Rs.1,200.00 for ELECTRICITY BOARD will auto-debit from HDFC Bank Credit Card xx1234 on 10-11-2026")
        rejected("Rs.500.00 will be debited from your card ending 1234 tomorrow")
    }

    @Test fun rejectsDeclinedAndFailed() {
        rejected("Your transaction on HDFC Bank card for 500.00 at SWIGGY is declined due to wrong PIN")
        rejected("TXN DECLINED: Rs.500.00 on 05-11-2026 at SWIGGY on HDFC Bank Credit Card 1234. Reason: insufficient limit")
        rejected("Txn on Axis Bank Credit Card XX1234 has been declined as the card is blocked", "AD-AXISBK-S")
        rejected("Transaction of Rs.500.00 on card ending 1234 failed")
    }

    @Test fun rejectsRefundInitiated() {
        rejected("Refund initiated: Amt: Rs.899.00 on HDFC Bank Credit Card 1234. Will be credited in 5-7 days")
    }

    @Test fun rejectsCardControls() {
        rejected("Your transaction limit on Axis Bank Credit Card XX1234 has been updated to INR 50,000", "AD-AXISBK-S")
        rejected("Usage limit modified for HDFC Bank Credit Card 1234: online Rs.20,000.00")
        rejected("Your HDFC Bank Debit Card 4321 is locked")
        rejected("Your SBI Credit Card ending 1234 has been activated", "AD-SBICRD-S")
        rejected("Card 1234 is now active for international use")
        rejected("Card 1234 linked for UPI Txn", "AD-UNTYCC-S")
        rejected("Set up your PIN for HDFC Bank Credit Card 1234 today")
    }

    @Test fun rejectsDisputesAndLoans() {
        rejected("Dispute Alert! We have received your dispute for Rs.500.00 on HDFC Bank Credit Card 1234")
        rejected("Pre-approved loan of Rs.2,00,000 for you. Disbursal in 5 minutes on card ending 1234", "AD-YESBNK-S")
    }

    @Test fun rejectsAccountOnlyMessages() {
        rejected("Sent Rs.500.00\nFrom HDFC Bank A/C *1234\nTo SOMEONE\nOn 05/11/26\nRef 612345678901")
        rejected("Rs.5,000.00 credited to HDFC Bank A/c XX1234 on 05-11-26 from VPA x@ybl (UPI 612345678901)")
        rejected("A/c *1234 debited Rs.500.00 on 05-11-26 to X. UPI Ref 612345678901", "AD-INDBNK-S")
    }

    // ---- more Indian banks (formats from other open-source parsers' tests; synthetic values)

    @Test fun bobCardSpend() {
        val r = ok("AX-BOBCRD-S", "ALERT: INR 1,250.50 is spent on your BOBCARD ending 4321 at AMAZON on 05-11-2026 14:22:10. Available credit limit is Rs 48,749.50 -Bank of Baroda")
        assertEquals("BOB Card", r.issuer)
        assertEquals("4321", r.last4)
        assertEquals(125050L, r.amountPaise)
        assertEquals("AMAZON", r.merchant)
        assertEquals(4874950L, r.availablePaise)
        assertEquals(ist(2026, 11, 5, 14, 22, 10), r.atMillis)
    }

    @Test fun hsbcCreditCardSpend() {
        val r = ok("JD-HSBCIN-S", "Your HSBC creditcard xxxxx9876 used at SWIGGY for INR 345.00 on 05-11-26.")
        assertEquals("HSBC", r.issuer)
        assertEquals("9876", r.last4)
        assertEquals(34500L, r.amountPaise)
        assertEquals("SWIGGY", r.merchant)
        assertEquals("credit", r.cardType)
    }

    @Test fun pnbDebitCardThruCard() {
        val r = ok("VM-PNBSMS-S", "A/c XX1111 debited with Rs.2000.00,05-11-2026 10:15:00 thru card XX2222 . Bal 5000.00 CR. If not done, fwd SMS to 9999999999 to block card")
        assertEquals("Punjab National Bank", r.issuer)
        assertEquals("2222", r.last4)
        assertEquals(200000L, r.amountPaise)
        assertEquals("debit", r.cardType)
    }

    @Test fun newSenderHeadersMapToBanks() {
        assertEquals("Bank of Baroda", AlertParser.issuer("AD-BOBTXN-S", "x"))
        assertEquals("BOB Card", AlertParser.issuer("AD-BOBCRD-S", "x"))
        assertEquals("SBI Card", AlertParser.issuer("AD-SBICRD-S", "x"))
        assertEquals("State Bank of India", AlertParser.issuer("AD-SBIUPI-S", "x"))
        assertEquals("Canara Bank", AlertParser.issuer("AD-CANBNK-S", "x"))
        assertEquals("Union Bank of India", AlertParser.issuer("AD-UNIONB-S", "x"))
        assertEquals("CSB Bank", AlertParser.issuer("AD-JTEDGE-S", "x"))
        assertEquals("Utkarsh Bank", AlertParser.issuer("AD-UTKSPR-S", "x"))
    }

    @Test fun bankOfIndiaNotConfusedWithLongerNames() {
        assertEquals("Central Bank of India", AlertParser.issuer(null, "Rs 100 spent on Central Bank of India Debit Card XX1234"))
        assertEquals("Union Bank of India", AlertParser.issuer(null, "Rs 100 spent on Union Bank of India Debit Card XX1234"))
        assertEquals("Bank of India", AlertParser.issuer(null, "Rs 100 spent on Bank of India Debit Card XX1234"))
    }

    @Test fun kotakCreditCardViaGeneric() {
        val r = ok("AD-KOTAKB-S", "Rs.499.00 spent on Kotak Credit Card x5678 at NETFLIX on 05/11/2026. Avl limit Rs.20,000.00")
        assertEquals("Kotak", r.issuer)
        assertEquals("5678", r.last4)
        assertEquals(49900L, r.amountPaise)
        assertEquals(TxnKind.SPEND, r.kind)
    }

    @Test fun linkWordsDontReject() {
        val r = ok("JD-SBICRD-S", "Rs.1,234.00 spent on your SBI Credit Card ending 4321 at AMAZON on 05/11/26. Trxn. not done by you? Report at https://sbicard.com/Dispute")
        assertEquals("SBI Card", r.issuer)
        assertEquals(123400L, r.amountPaise)
    }

    @Test fun cashbackWithCongratulations() {
        val r = ok("AD-AXISBK-S", "Congratulations! Cashback of INR 150 has been credited to your Airtel Axis Bank Credit Card XX4321 towards your spends during JUL'26.")
        assertEquals(TxnKind.CASHBACK, r.kind)
        assertEquals(15000L, r.amountPaise)
        rejected("Congratulations on your first transaction with the AXIS BANK Credit Card! You've unlocked a welcome benefit of Amazon eVoucher worth INR 500.", sender = "AD-AXISBK-S")
    }
}
