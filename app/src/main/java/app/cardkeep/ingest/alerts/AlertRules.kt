package app.cardkeep.ingest.alerts

import app.cardkeep.ingest.TxnKind

/**
 * One alert shape: a regex plus how its match maps to a [CardAlert].
 * Named groups the regex may define: amt, cur, l4, m, d (date), lim, ref, ct (Credit/Debit).
 * Spaces in the source pattern become `\s+`; use `\s*` explicitly where whitespace is optional.
 */
internal class AlertRule(
    val issuer: String,
    val kind: String,
    val isCredit: Boolean,
    val re: Regex,
    val cardType: String? = null,
    /** Strict rules name no bank in the text, so they only run when the sender/body already says it is [issuer]. */
    val strict: Boolean = false,
    /** HDFC refund lines pad the merchant with spaces and append "CITY IND". */
    val paddedMerchant: Boolean = false,
)

internal object AlertRules {
    const val CURS = "Rs\\.?|INR|\\u20B9|USD|EUR|GBP|AED|SGD|AUD|CAD|JPY|CHF|THB|MYR|HKD|NZD|SAR|QAR|CNY|IDR"

    private fun a(name: String, cur: Boolean = false) =
        (if (cur) "(?<cur>$CURS)" else "(?:$CURS)") + "\\s*(?<$name>\\d[\\d,]*(?:\\.\\d{1,2})?)"

    private val AMT = a("amt")
    private val AMTC = a("amt", cur = true)
    private val LIM = a("lim")
    private const val L4 = "(?<l4>\\d{4})"
    private const val XL4 = "[x*]*(?<l4>\\d{4})"
    private const val M = "(?<m>.+?)"
    private const val TIME = "(?:,?\\s*\\d{1,2}:\\d{2}(?::\\d{2})?\\s*(?:[ap]m)?)?"
    private const val DMY = "\\d{1,2}[-/]\\d{1,2}[-/]\\d{2,4}"
    private const val DMON = "\\d{1,2}[-/\\s][A-Za-z]{3,9}[-/\\s,']*\\d{2,4}"
    private const val MONDY = "[A-Za-z]{3}\\s+\\d{1,2}\\s+\\d{4}(?:\\s+\\d{1,2}:\\d{2}\\s*[ap]m)?"
    private const val ISO = "\\d{4}-\\d{2}-\\d{2}[:\\sT]\\d{2}:\\d{2}:\\d{2}"

    private fun r(
        issuer: String, kind: String, credit: Boolean, pattern: String,
        cardType: String? = null, strict: Boolean = false, padded: Boolean = false,
    ) = AlertRule(issuer, kind, credit, Regex(pattern.replace(" ", "\\s+"), RegexOption.IGNORE_CASE), cardType, strict, padded)

    private const val HDFC = "HDFC Bank"
    private const val SPEND = TxnKind.SPEND
    private const val REFUND = TxnKind.REFUND
    private const val PAYMENT = TxnKind.PAYMENT
    private const val CASHBACK = TxnKind.CASHBACK

    /** Ordered: first match wins. */
    val all: List<AlertRule> = listOf(
        // ---- HDFC Bank
        r(HDFC, SPEND, false, "Spent $AMTC On HDFC Bank Card $XL4 At $M On (?<d>$ISO)", "credit"),
        r(HDFC, SPEND, false, "(?:Spent|Withdrawn) $AMTC From HDFC Bank Card $XL4 At $M On (?<d>$ISO)(?: Bal $LIM)?", "debit"),
        r(HDFC, SPEND, false, "(?:ALERT:?\\s*)?$AMTC spent via HDFC BANK Debit Card $XL4 at $M on (?<d>$MONDY) without", "debit"),
        r(HDFC, SPEND, false, "Txn $AMTC On HDFC Bank Card $XL4 At $M by UPI (?<ref>\\w+) On (?<d>\\d{1,2}-\\d{1,2})(?![-\\d])", "credit"),
        r(HDFC, REFUND, true, "$AMTC refunded by $M on (?<d>$DMON) & adjusted against HDFC Bank Credit Card $XL4", "credit", padded = true),
        r(HDFC, REFUND, true, "Transaction Reversed!?\\s*On HDFC Bank CREDIT Card $XL4 Amt:\\s*$AMTC By $M On (?<d>$ISO)", "credit"),
        r(HDFC, PAYMENT, true, "PAYMENT OF $AMTC RECEIVED TOWARDS YOUR CREDIT CARD ENDING (?:WITH )?$XL4 ON (?<d>$DMY)(?:\\W+YOUR AVAILABLE LIMIT IS $LIM)?", "credit"),
        r(HDFC, SPEND, false, "Bill Paid: $M Bill \\S+ of $AMTC paid on (?<d>$DMON) from HDFC Bank (?<ct>Credit|Debit) Card $XL4 via SmartPay"),
        r(HDFC, SPEND, false, "successfully debited your HDFC Bank (?<ct>Credit|Debit) card ending $XL4 to pay your $M bill for the amount $AMTC"),

        // ---- Axis Bank
        r("Axis Bank", SPEND, false, "Spent $AMTC Axis Bank (?:Credit )?Card no\\.? $XL4 (?<d>$DMY$TIME) IST $M(?: Avl (?:Limit|Lmt):?\\s*$LIM)?(?= Not you|\\s*\\z)"),
        r("Axis Bank", PAYMENT, true, "Payment of $AMTC has been received towards your Axis Bank Credit Card $XL4 on (?<d>$DMY)", "credit"),
        r("Axis Bank", CASHBACK, true, "Cashback of $AMTC has been credited to your (?:.+? )?Axis Bank Credit Card $XL4", "credit"),

        // ---- SBI Card
        r("SBI Card", SPEND, false, "$AMTC spent on your SBI (?:Credit )?Card ending (?:with )?$XL4 at $M on (?<d>$DMY)", "credit"),
        r("SBI Card", SPEND, false, "Transaction of $AMTC made on your SBI (?:Credit )?Card ending (?:with )?$XL4 at $M on (?<d>$DMY)", "credit"),
        r("SBI Card", PAYMENT, true, "payment of $AMTC (?:has been |was )?(?:received|credited)(?: to| towards| on)? your SBI (?:Credit )?Card ending (?:with )?$XL4", "credit"),

        // ---- Yes Bank
        r("Yes Bank", SPEND, false, "$AMTC spent on YES BANK Card $XL4 @\\s*(?:UPI_)?$M (?<d>$DMY$TIME)\\.?\\s*Avl Lmt $LIM"),
        r("Yes Bank", PAYMENT, true, "payment of $AMTC is received towards your YES BANK Credit Card ending $XL4", "credit"),

        // ---- slice (credit card over UPI)
        r("Slice", SPEND, false, "$AMTC spent on your credit card $XL4 at $M on (?<d>$DMON) \\(UPI Ref:?\\s*(?<ref>\\w+)\\)", "credit"),
        r("Slice", REFUND, true, "Refund of $AMTC from $M has been successfully processed to your credit card $XL4", "credit"),
        r("Slice", PAYMENT, true, "Repayment of $AMTC received for the slice credit card", "credit"),

        // ---- Unity Small Finance Bank / Roarbank
        r("Unity Bank (Roar)", SPEND, false, "$AMTC spent at $M\\.\\s*Card:\\s*$L4\\s*Time:\\s*(?<d>$DMY$TIME)\\.?\\s*Avl Limit:\\s*$LIM", "credit"),
        r("Unity Bank (Roar)", SPEND, false, "$AMTC debited at $M\\.\\s*Card:\\s*$L4\\s*Time:\\s*(?<d>$DMY$TIME)\\.?\\s*Avl Balance:\\s*$LIM", "debit"),
        r("Unity Bank (Roar)", REFUND, true, "$AMTC credited to your card $L4\\.?(?:\\s*Avl Balance:\\s*$LIM)?"),

        // ---- Indian Bank (debit)
        r("Indian Bank", SPEND, false, "$AMTC spent using Indian Bank Debit Card $XL4 at $M on (?<d>$DMY$TIME)\\s*hr\\.?(?:\\s*Avl Bal\\s*$LIM)?", "debit"),

        // ---- Best effort, from other open-source parsers' test messages (not yet seen in a real inbox)
        r("BOB Card", SPEND, false, "(?:ALERT:?\\s*)?$AMTC (?:is )?spent on your BOBCARD ending $XL4 at $M on (?<d>$DMY$TIME)(?:.*?Available credit limit is $LIM)?", "credit"),
        r("HSBC", SPEND, false, "Your HSBC credit\\s*card $XL4 used at $M for $AMTC on (?<d>$DMY)", "credit"),
        r("Punjab National Bank", SPEND, false, "debited with $AMTC,?\\s*(?<d>$DMY$TIME) thru card $XL4", "debit", strict = true),
        r("ICICI Bank", SPEND, false, "$AMTC spent using ICICI Bank Card $XL4 on (?<d>$DMON) on $M\\.\\s*Avl Limit:?\\s*$LIM"),
        r("ICICI Bank", SPEND, false, "$AMTC spent using ICICI Bank Card $XL4 on (?<d>$DMON) on $M\\.?\\s*\\z"),
        r("IDFC FIRST", SPEND, false, "$AMTC spent on your IDFC FIRST Bank Credit Card ending $XL4 at $M on (?<d>$DMON)(?:.*?Avbl?\\s*Limit:?\\s*$LIM)?", "credit"),
        r("Federal Bank", SPEND, false, "$AMTC spent on your credit card ending with $XL4 at $M on (?<d>$DMY)(?:.*?Available\\s*limit:?\\s*$LIM)?", "credit", strict = true),
        r("AU Bank", SPEND, false, "$AMTC spent at $M on AU Bank Credit Card $XL4 (?<d>$DMY)", "credit"),
        r("Amex", SPEND, false, "(?:You've )?spent $AMTC on your (?:AMEX|American Express)(?: Card)?\\s*\\*+\\s*(?<l4>\\d{4,5}) at $M on (?<d>\\d{1,2} [A-Za-z]+ \\d{4})"),
        r("Amex", SPEND, false, "A charge of $AMTC has been made on your (?:Amex )?Card No\\.?\\s*\\*+\\s*(?<l4>\\d{4,5}) at $M on (?<d>$DMY)"),
        r("OneCard", SPEND, false, "spent $AMTC (?:at $M )?(?:on|using|with) (?:your )?(?:\\w+ ){0,2}card ending (?:with )?$XL4", strict = true),
    )

    // ---- Rejection: anything that is not a completed card transaction.

    private val withoutOtp = Regex("without\\s+(?:pin\\s*/\\s*)?otp")

    private val reject: List<Regex> = listOf(
        // OTP and codes
        "\\b(?:otp|one[- ]time (?:password|pin)|verification code|passcode|cvv)\\b",
        // promos
        "\\boffers?\\b|\\bpromo|\\bvouchers?\\b|\\bcoupons?\\b|gift (?:card|code|voucher)|apply now|pre-?approved|t&c",
        // dues, reminders, statements
        "\\bis due\\b|(?:total|min(?:imum)?|amount|amt)\\.?\\s*(?:amt\\.?\\s*)?due|\\bdue (?:on|by|date)\\b|\\bpay (?:by|before)\\b|reminder|statement|overdue|\\bunpaid\\b",
        // scheduled / future
        "will be (?:debited|charged|credited)|will auto-?debit|\\bscheduled\\b|auto-?debit (?:has been )?(?:set|registered)|\\bmandate\\b|\\bupcoming\\b",
        // declined, failed, not yet credited
        "declin|\\bfailed\\b|\\bfailure\\b|unsuccessful|refund (?:has been )?initiated|not (?:been )?(?:processed|successful)|could not be processed|insufficient",
        // card controls
        "limit\\s+(?:has been\\s+|is\\s+|was\\s+)?(?:successfully\\s+)?(?:updated|modified|changed|increased|reduced|revised|set)",
        "(?:updated|modified|changed|increased|reduced|revised)\\s+(?:your\\s+)?(?:\\w+\\s+){0,3}limit",
        "\\b(?:locked|unlocked|activated|deactivated)\\b|\\bnow active\\b|card is active|has been (?:blocked|enabled|disabled|hotlisted|reissued)|linked for upi",
        "set(?:ting)?(?:\\s+up)?\\s+(?:your\\s+)?(?:card\\s+)?pin|\\bpin\\s+(?:set|changed|generated)",
        // disputes, loans, payment requests
        "dispute|\\bloan\\b|disburs|payment request|collect request|requested (?:you )?(?:to )?pay",
    ).map { Regex(it, RegexOption.IGNORE_CASE) }

    // Links can contain words like "Dispute" or "offers" (e.g. "Report at sbicard.com/Dispute").
    private val link = Regex("\\bhttps?://\\S+|\\b[a-z0-9-]+(?:\\.[a-z0-9-]+)+/\\S*")

    fun isRejected(text: String): Boolean {
        val t = withoutOtp.replace(link.replace(text.lowercase(), " "), " ")
        return reject.any { it.containsMatchIn(t) }
    }
}
