package app.cardkeep.ingest.alerts

import app.cardkeep.ingest.TxnKind
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** One completed card transaction read from a bank SMS / RCS notification. Pure data, no Android types. */
data class CardAlert(
    val issuer: String?,        // Issuers name, or null if unknown
    val last4: String?,         // card digits as shown: 4 (Amex may be 5); null if the message has none (e.g. slice repayment)
    val kind: String,           // TxnKind.SPEND / REFUND / PAYMENT / CASHBACK (reversal -> REFUND; prepaid/debit "credited to your card" -> REFUND)
    val isCredit: Boolean,      // true when money comes back to the card (refund, payment, cashback, credit)
    val amountPaise: Long,
    val currency: String,       // "INR" unless the message says another ISO code
    val merchant: String,       // cleaned; "" if none
    val atMillis: Long?,        // time from the message body if present (IST); date-only messages give 00:00 IST of that day
    val availablePaise: Long?,  // "Avl Limit"/"Avl Lmt"/"available limit"/"Avl Bal"/"Bal" if present
    val reference: String,      // UPI ref / txn ref if present, else ""
    val cardType: String?,      // "credit", "debit" or null when the message doesn't say
)

object AlertParser {
    private const val MAX_LEN = 2000
    private val IST: ZoneId = ZoneId.of("Asia/Kolkata")
    private val OPT = RegexOption.IGNORE_CASE

    // ---- Sender and issuer

    private val dltHeader = Regex("^[A-Za-z]{2}-([A-Za-z0-9&]+?)(?:-([A-Za-z]))?$")
    private val phoneLike = Regex("^\\+?[\\d\\s()-]{6,}$")

    /** "AD-HDFCBK-S" -> "HDFCBK"; null for promotional (-P) senders and plain phone numbers. Non-DLT names are uppercased/trimmed. */
    fun header(sender: String): String? {
        val s = sender.trim()
        if (s.isEmpty() || phoneLike.matches(s)) return null
        dltHeader.matchEntire(s)?.let { m ->
            if (m.groupValues[2].equals("P", ignoreCase = true)) return null
            return m.groupValues[1].uppercase()
        }
        return s.uppercase()
    }

    private val headerIssuers: List<Pair<String, String>> = listOf(
        "HDFCB" to "HDFC Bank", "HDFCCC" to "HDFC Bank",
        "AXIS" to "Axis Bank",
        "SBIC" to "SBI Card", "MYSBIC" to "SBI Card",
        "YESB" to "Yes Bank",
        "SLC" to "Slice", "SLICE" to "Slice",
        "UNTY" to "Unity Bank (Roar)", "UNITYB" to "Unity Bank (Roar)", "ROAR" to "Unity Bank (Roar)",
        "INDBN" to "Indian Bank", "INDIANB" to "Indian Bank",
        "ICIC" to "ICICI Bank",
        "KOTAK" to "Kotak", "KMBL" to "Kotak",
        "IDFC" to "IDFC FIRST",
        "INDUS" to "IndusInd",
        "AUBANK" to "AU Bank", "AUSFB" to "AU Bank", "AUBNK" to "AU Bank",
        "RBL" to "RBL Bank",
        "AMEX" to "Amex", "AEXP" to "Amex",
        "FEDBNK" to "Federal Bank", "FEDERAL" to "Federal Bank", "FEDBK" to "Federal Bank",
        "ONECARD" to "OneCard", "ONECRD" to "OneCard",
        "DCB" to "DCB Bank",
        "BOBCARD" to "BOB Card", "BOBCRD" to "BOB Card", "BOBFIN" to "BOB Card",
        "HSBC" to "HSBC",
        "SCBANK" to "Standard Chartered", "SCBNK" to "Standard Chartered", "STANCHART" to "Standard Chartered",
        // Sender names below are from other open-source parsers, not yet seen in a real inbox.
        "SBI" to "State Bank of India", "ATMSBI" to "State Bank of India",
        "BOB" to "Bank of Baroda", "BARODA" to "Bank of Baroda",
        "PNB" to "Punjab National Bank", "PUNBN" to "Punjab National Bank",
        "CANBNK" to "Canara Bank", "CANARA" to "Canara Bank",
        "UNIONB" to "Union Bank of India", "UBOI" to "Union Bank of India",
        "BOIIND" to "Bank of India", "BOIBNK" to "Bank of India", "BOI" to "Bank of India",
        "CBOI" to "Central Bank of India", "CENTBK" to "Central Bank of India",
        "IOB" to "Indian Overseas Bank",
        "UCOB" to "UCO Bank",
        "IDBI" to "IDBI Bank",
        "MAHABK" to "Bank of Maharashtra", "BOMBNK" to "Bank of Maharashtra",
        "DBS" to "DBS Bank",
        "CITI" to "Citi",
        "BANDHN" to "Bandhan Bank", "BANDHAN" to "Bandhan Bank",
        "EQUITA" to "Equitas Bank", "EQUITS" to "Equitas Bank", "EQUTAS" to "Equitas Bank",
        "UTKSFB" to "Utkarsh Bank", "UTKSPR" to "Utkarsh Bank", "UTKARS" to "Utkarsh Bank",
        "CUBANK" to "City Union Bank", "CUBLTD" to "City Union Bank",
        "KBLBNK" to "Karnataka Bank", "KARBAN" to "Karnataka Bank", "KTKBAN" to "Karnataka Bank",
        "SIBSMS" to "South Indian Bank", "SIBBNK" to "South Indian Bank",
        "DHANBK" to "Dhanlaxmi Bank",
        "JKB" to "J&K Bank",
        "SARBNK" to "Saraswat Bank", "SARASW" to "Saraswat Bank",
        "KVBANK" to "Karur Vysya Bank", "KVBNK" to "Karur Vysya Bank",
        "JTEDGE" to "CSB Bank", "CSBBNK" to "CSB Bank",
        "AIRBNK" to "Airtel Payments Bank",
        "JIOPBS" to "Jio Payments Bank",
        "IPBMSG" to "India Post Payments Bank",
        "NSDLPB" to "NSDL Payments Bank",
    )

    private fun issuerFromHeader(h: String): String? {
        val key = h.replace(" ", "")
        return headerIssuers.firstOrNull { key.startsWith(it.first) }?.second
    }

    private val bodyIssuers: List<Pair<Regex, String>> = listOf(
        "hdfc" to "HDFC Bank",
        "\\baxis\\b" to "Axis Bank",
        "sbi\\s*(?:credit\\s*)?card" to "SBI Card",
        "\\byes\\s*bank" to "Yes Bank",
        "\\bslice\\b" to "Slice",
        "roar\\s*bank|unity\\s*(?:small\\s*finance\\s*)?bank|unity\\s*sfb" to "Unity Bank (Roar)",
        "(?<!south )\\bindian\\s*bank" to "Indian Bank",
        "icici" to "ICICI Bank",
        "kotak" to "Kotak",
        "idfc" to "IDFC FIRST",
        "indusind" to "IndusInd",
        "\\bau\\s*(?:small\\s*finance\\s*)?bank" to "AU Bank",
        "\\brbl\\b" to "RBL Bank",
        "\\bamex\\b|american\\s*express" to "Amex",
        "federal\\s*bank" to "Federal Bank",
        "one\\s*card" to "OneCard",
        "\\bdcb\\b" to "DCB Bank",
        "bob\\s*card" to "BOB Card",
        "hsbc" to "HSBC",
        "standard\\s*chartered" to "Standard Chartered",
        "state\\s*bank\\s*of\\s*india|\\bsbi\\b" to "State Bank of India",
        "bank\\s*of\\s*baroda" to "Bank of Baroda",
        "punjab\\s*national\\s*bank|\\bpnb\\b" to "Punjab National Bank",
        "canara\\s*bank" to "Canara Bank",
        "union\\s*bank" to "Union Bank of India",
        "(?<!state |central |union )\\bbank\\s*of\\s*india" to "Bank of India",
        "central\\s*bank\\s*of\\s*india" to "Central Bank of India",
        "indian\\s*overseas\\s*bank|\\biob\\b" to "Indian Overseas Bank",
        "\\buco\\s*bank" to "UCO Bank",
        "\\bidbi\\b" to "IDBI Bank",
        "bank\\s*of\\s*maharashtra" to "Bank of Maharashtra",
        "\\bdbs\\b" to "DBS Bank",
        "\\bciti\\b" to "Citi",
        "bandhan" to "Bandhan Bank",
        "equitas" to "Equitas Bank",
        "utkarsh|supercard" to "Utkarsh Bank",
        "city\\s*union\\s*bank" to "City Union Bank",
        "karnataka\\s*bank" to "Karnataka Bank",
        "south\\s*indian\\s*bank" to "South Indian Bank",
        "dhanlaxmi" to "Dhanlaxmi Bank",
        "j\\s*&\\s*k\\s*bank|jammu\\s*(?:&|and)\\s*kashmir\\s*bank" to "J&K Bank",
        "saraswat" to "Saraswat Bank",
        "karur\\s*vysya" to "Karur Vysya Bank",
        "\\bcsb\\b|jupiter" to "CSB Bank",
        "airtel\\s*payments\\s*bank" to "Airtel Payments Bank",
        "jio\\s*payments\\s*bank" to "Jio Payments Bank",
        "india\\s*post\\s*payments\\s*bank|\\bippb\\b" to "India Post Payments Bank",
        "nsdl\\s*payments\\s*bank" to "NSDL Payments Bank",
    ).map { Regex(it.first, OPT) to it.second }

    private fun issuerFromBody(body: String): String? =
        bodyIssuers.mapNotNull { (re, name) -> re.find(body)?.let { it.range.first to name } }
            .minByOrNull { it.first }?.second

    /** Issuer from the sender header, else from the body text (RCS notifications may only show a brand name). */
    fun issuer(sender: String?, body: String): String? =
        sender?.let { header(it) }?.let { issuerFromHeader(it) } ?: issuerFromBody(body)

    // ---- Parsing

    private val newlines = Regex("\\s*[\\r\\n]+\\s*")

    /** Null when the message is not a completed card transaction. */
    fun parse(sender: String?, body: String): CardAlert? = parse(sender, body, System.currentTimeMillis())

    internal fun parse(sender: String?, body: String, now: Long): CardAlert? {
        if (body.isBlank() || body.length > MAX_LEN) return null
        val text = newlines.replace(body, " ").trim()
        if (AlertRules.isRejected(text)) return null
        val resolved = issuer(sender, body)
        for (rule in AlertRules.all) {
            if (rule.strict && rule.issuer != resolved) continue
            val m = rule.re.find(text) ?: continue
            return fromRule(rule, m, text, now)
        }
        return generic(resolved, text, now)
    }

    private fun group(m: MatchResult, name: String): String? =
        runCatching { m.groups[name]?.value }.getOrNull()

    private fun fromRule(rule: AlertRule, m: MatchResult, text: String, now: Long): CardAlert? {
        val amount = group(m, "amt")?.let(::paise) ?: return null
        return CardAlert(
            issuer = rule.issuer,
            last4 = group(m, "l4"),
            kind = rule.kind,
            isCredit = rule.isCredit,
            amountPaise = amount,
            currency = currency(group(m, "cur")),
            merchant = cleanMerchant(group(m, "m"), rule.paddedMerchant),
            atMillis = group(m, "d")?.let { parseDate(it, now) } ?: scanDate(text, now),
            availablePaise = group(m, "lim")?.let(::paise) ?: scanAvailable(text),
            reference = group(m, "ref") ?: scanReference(text),
            cardType = rule.cardType ?: group(m, "ct")?.lowercase() ?: cardTypeOf(text),
        )
    }

    // ---- Generic fallback: spend/credit verb + amount + card digits

    private val cardDigits = Regex(
        "\\bcard\\b(?:\\s*no\\.?)?(?:\\s*:)?(?:\\s*ending(?:\\s*with|\\s*in)?)?\\s*[x*]*\\s*(\\d{4,5})\\b", OPT,
    )
    private val anyAmount = Regex("(?<![A-Za-z])(?<cur>${AlertRules.CURS})\\s*(?<amt>\\d[\\d,]*(?:\\.\\d{1,2})?)", OPT)
    private val balanceWords = Regex("avl|avbl|avail|limit|lmt|bal|total|min", OPT)

    private val refundWord = Regex("refund|revers", OPT)
    private val cashbackWord = Regex("cash ?back", OPT)
    private val paymentWord = Regex("(?:payment|repayment|paid).{0,60}(?:received|credited)|(?:received|credited).{0,40}payment|repayment", OPT)
    private val spendWord = Regex("\\b(?:spent|debited|purchase|purchased|withdrawn|withdrawal|charged|paid|swiped)\\b", OPT)
    private val creditWord = Regex("\\b(?:credited|received)\\b", OPT)
    private val merchantAt = Regex(
        "(?:\\bat\\s+(?!\\d{1,2}:\\d{2})|@\\s*)(?<m>[^;,]{2,40}?)" +
            "(?=\\s+(?:on|using|via|with|for|avl|avbl|available|bal|ref|not|if|call|sms|card|upi|dated|from)\\b|[;,]|\\.(?:\\s|\\z)|\\s*\\z)", OPT,
    )
    private val merchantFrom = Regex(
        "(?:\\bfrom\\s+|\\bby\\s+)(?<m>[^;,]{2,40}?)" +
            "(?=\\s+(?:on|using|via|with|for|has|was|is|credited|refunded|reversed|to|avl|avbl|available|ref|not|if|call|sms|card)\\b|[;,]|\\.(?:\\s|\\z)|\\s*\\z)", OPT,
    )

    private fun generic(issuer: String?, text: String, now: Long): CardAlert? {
        val l4 = cardDigits.find(text)?.groupValues?.get(1) ?: return null
        val amt = anyAmount.findAll(text).firstOrNull { m ->
            !balanceWords.containsMatchIn(text.substring(maxOf(0, m.range.first - 20), m.range.first))
        } ?: return null
        val (kind, credit) = when {
            refundWord.containsMatchIn(text) -> TxnKind.REFUND to true
            cashbackWord.containsMatchIn(text) && creditWord.containsMatchIn(text) -> TxnKind.CASHBACK to true
            paymentWord.containsMatchIn(text) -> TxnKind.PAYMENT to true
            spendWord.containsMatchIn(text) -> TxnKind.SPEND to false
            creditWord.containsMatchIn(text) -> TxnKind.REFUND to true
            else -> return null
        }
        val rawMerchant = if (kind == TxnKind.SPEND || kind == TxnKind.REFUND) {
            merchantAt.find(text)?.groups?.get("m")?.value
                ?: if (kind == TxnKind.REFUND) merchantFrom.find(text)?.groups?.get("m")?.value else null
        } else null
        return CardAlert(
            issuer = issuer,
            last4 = l4,
            kind = kind,
            isCredit = credit,
            amountPaise = paise(amt.groups["amt"]!!.value) ?: return null,
            currency = currency(amt.groups["cur"]?.value),
            merchant = cleanMerchant(rawMerchant, false),
            atMillis = scanDate(text, now),
            availablePaise = scanAvailable(text),
            reference = scanReference(text),
            cardType = cardTypeOf(text),
        )
    }

    // ---- Field helpers

    private fun paise(s: String): Long? = runCatching {
        BigDecimal(s.replace(",", "").trimEnd('.')).movePointRight(2).setScale(0, RoundingMode.HALF_UP).toLong()
    }.getOrNull()

    private fun currency(raw: String?): String = when {
        raw == null -> "INR"
        raw.startsWith("rs", ignoreCase = true) || raw == "₹" -> "INR"
        else -> raw.uppercase()
    }

    private val paddedSplit = Regex("\\s{2,}")
    private val cityState = Regex("\\s+\\S+\\s+(?:IND|MAH|KAR)$", OPT)
    private val countryOnly = Regex("\\s+(?:IND|MAH|KAR)$", OPT)
    private val spaces = Regex("\\s+")

    internal fun cleanMerchant(raw: String?, padded: Boolean): String {
        var s = raw?.trim() ?: return ""
        if (padded) {
            val parts = s.split(paddedSplit)
            s = if (parts.size > 1) parts[0] else s.replace(cityState, "").replace(countryOnly, "")
        }
        s = spaces.replace(s, " ").trim { it.isWhitespace() || it in ".,;:-_*" }
        return if (s.equals("NA", ignoreCase = true)) "" else s
    }

    private val debitCard = Regex("debit card|\\bblock\\s*dc\\b", OPT)
    private val creditCard = Regex("credit card|\\bblock\\s*cc\\b|\\bblkcc\\b", OPT)

    private fun cardTypeOf(text: String): String? = when {
        debitCard.containsMatchIn(text) -> "debit"
        creditCard.containsMatchIn(text) -> "credit"
        else -> null
    }

    private val availableRe = Regex(
        "(?:\\bavl|\\bavbl|\\bavail(?:able)?)\\.?\\s*(?:limit|lmt|bal(?:ance)?)\\s*(?:is|:|-)?\\s*(?:${AlertRules.CURS})\\s*(\\d[\\d,]*(?:\\.\\d{1,2})?)" +
            "|\\bbal(?:ance)?\\s*:?\\s*(?:${AlertRules.CURS})\\s*(\\d[\\d,]*(?:\\.\\d{1,2})?)", OPT,
    )

    private fun scanAvailable(text: String): Long? =
        availableRe.find(text)?.let { m -> (m.groups[1] ?: m.groups[2])?.value?.let(::paise) }

    private val referenceRe = Regex(
        "\\bRef(?:erence)?\\b\\.?(?:\\s*(?:No|Number)\\.?)?\\s*[:#-]?\\s*((?=[A-Za-z0-9]*\\d)[A-Za-z0-9]{6,})|\\bby UPI\\s+(\\d{6,})", OPT,
    )

    private fun scanReference(text: String): String =
        referenceRe.find(text)?.let { (it.groups[1] ?: it.groups[2])?.value }.orEmpty()

    // ---- Dates (all IST)

    private const val MON = "(?:jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*"
    private const val T_OPT = "(?:[,\\s]+(\\d{1,2}):(\\d{2})(?::(\\d{2}))?\\s*([ap]m)?)?"
    private val months = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")

    private val isoDate = Regex("^(\\d{4})-(\\d{2})-(\\d{2})(?:[:\\sT]+(\\d{2}):(\\d{2}):(\\d{2}))?")
    private val monDayYear = Regex("^($MON)\\s+(\\d{1,2}),?\\s+(\\d{4})(?:\\s+(\\d{1,2}):(\\d{2})\\s*([ap]m)?)?", OPT)
    private val dayMonYear = Regex("^(\\d{1,2})[-/\\s]($MON)[-/\\s,']*(\\d{2,4})$T_OPT", OPT)
    private val dayMonthYear = Regex("^(\\d{1,2})[-/](\\d{1,2})[-/](\\d{2,4})$T_OPT", OPT)
    private val dayMonth = Regex("^(\\d{1,2})[-/](\\d{1,2})$")

    private val dateFinder = Regex(
        "\\d{4}-\\d{2}-\\d{2}(?:[:\\sT]+\\d{2}:\\d{2}:\\d{2})?" +
            "|\\b$MON\\s+\\d{1,2},?\\s+\\d{4}(?:\\s+\\d{1,2}:\\d{2}\\s*[ap]m)?" +
            "|\\b\\d{1,2}[-/\\s]$MON[-/\\s,']*\\d{2,4}(?:[,\\s]+\\d{1,2}:\\d{2}(?::\\d{2})?\\s*(?:[ap]m)?)?" +
            "|\\b\\d{1,2}[-/]\\d{1,2}[-/]\\d{2,4}(?:[,\\s]+\\d{1,2}:\\d{2}(?::\\d{2})?\\s*(?:[ap]m)?)?", OPT,
    )

    private fun scanDate(text: String, now: Long): Long? = dateFinder.find(text)?.let { parseDate(it.value, now) }

    private fun year(s: String) = s.toInt().let { if (s.length <= 2) 2000 + it else it }

    private fun hour(h: String?, ampm: String?): Int {
        val v = h?.toIntOrNull() ?: return 0
        return when (ampm?.lowercase()) {
            "pm" -> v % 12 + 12
            "am" -> v % 12
            else -> v
        }
    }

    private fun at(date: LocalDate, h: String?, mi: String?, s: String?, ampm: String?): Long =
        LocalDateTime.of(date, java.time.LocalTime.of(hour(h, ampm), mi?.toInt() ?: 0, s?.toInt() ?: 0))
            .atZone(IST).toInstant().toEpochMilli()

    private fun monthNo(name: String) = months.indexOf(name.take(3).lowercase()) + 1

    internal fun parseDate(raw: String, now: Long): Long? = runCatching {
        val s = raw.trim()
        isoDate.find(s)?.let { m ->
            val g = m.groupValues
            return@runCatching at(LocalDate.of(g[1].toInt(), g[2].toInt(), g[3].toInt()), g[4].ifEmpty { null }, g[5].ifEmpty { null }, g[6].ifEmpty { null }, null)
        }
        monDayYear.find(s)?.let { m ->
            val g = m.groupValues
            return@runCatching at(LocalDate.of(g[3].toInt(), monthNo(g[1]), g[2].toInt()), g[4].ifEmpty { null }, g[5].ifEmpty { null }, null, g[6].ifEmpty { null })
        }
        dayMonYear.find(s)?.let { m ->
            val g = m.groupValues
            return@runCatching at(LocalDate.of(year(g[3]), monthNo(g[2]), g[1].toInt()), g[4].ifEmpty { null }, g[5].ifEmpty { null }, g[6].ifEmpty { null }, g[7].ifEmpty { null })
        }
        dayMonthYear.find(s)?.let { m ->
            val g = m.groupValues
            return@runCatching at(LocalDate.of(year(g[3]), g[2].toInt(), g[1].toInt()), g[4].ifEmpty { null }, g[5].ifEmpty { null }, g[6].ifEmpty { null }, g[7].ifEmpty { null })
        }
        dayMonth.find(s)?.let { m ->
            val today = java.time.Instant.ofEpochMilli(now).atZone(IST).toLocalDate()
            val d = m.groupValues[1].toInt()
            val mo = m.groupValues[2].toInt()
            var date = LocalDate.of(today.year, mo, d)
            if (date.isAfter(today)) date = LocalDate.of(today.year - 1, mo, d)
            return@runCatching at(date, null, null, null, null)
        }
        null
    }.getOrNull()
}
