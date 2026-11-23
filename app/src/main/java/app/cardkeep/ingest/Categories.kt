package app.cardkeep.ingest

import app.cardkeep.data.Category
import app.cardkeep.data.CategoryRule

/** Starter categories and merchant rules, seeded once. Users edit from there. */
object DefaultCategories {
    const val USER_PRIORITY = 100
    private const val SEED_PRIORITY = 10

    val seed: List<Triple<String, Long, List<String>>> = listOf(
        Triple("Food & dining", 0xFFEF6C00, listOf("swiggy", "zomato", "eatsure", "domino", "mcdonald", "kfc", "starbucks", "restaurant", "cafe", "pizza", "burger", "chaayos", "haldiram", "subway", "dunkin", "eatclub")),
        Triple("Groceries", 0xFF43A047, listOf("bigbasket", "blinkit", "zepto", "instamart", "dmart", "avenue supermart", "jiomart", "grofers", "more retail", "nature's basket", "ratnadeep", "spencer", "star bazaar", "milkbasket", "country delight")),
        Triple("Shopping", 0xFF8E24AA, listOf("amazon", "flipkart", "myntra", "ajio", "nykaa", "meesho", "tatacliq", "croma", "reliance digital", "decathlon", "ikea", "lifestyle", "westside", "pantaloons", "h&m", "zara", "uniqlo", "apple")),
        Triple("Travel", 0xFF1E88E5, listOf("makemytrip", "goibibo", "cleartrip", "irctc", "indigo", "interglobe", "air india", "vistara", "akasa", "spicejet", "uber", "olacabs", "ola cabs", "rapido", "yatra", "agoda", "booking.com", "airbnb", "oyo", "ixigo", "redbus", "fastag")),
        Triple("Fuel", 0xFF6D4C41, listOf("fuel", "petrol", "hpcl", "bpcl", "iocl", "indian oil", "shell", "hp pay", "filling station", "service station")),
        Triple("Bills & utilities", 0xFF00897B, listOf("airtel", "jio", "vodafone", "bescom", "electricity", "tata power", "adani", "act fibernet", "bsnl", "broadband", "recharge", "billdesk", "bbps", "mahanagar gas", "water board", "torrent power", "tneb", "msedcl")),
        Triple("Entertainment", 0xFFD81B60, listOf("netflix", "hotstar", "spotify", "prime video", "youtube", "bookmyshow", "pvr", "inox", "sonyliv", "zee5", "jiocinema", "steam", "playstation")),
        Triple("Health", 0xFFE53935, listOf("pharmacy", "apollo", "1mg", "pharmeasy", "netmeds", "hospital", "clinic", "medplus", "practo", "cult.fit", "cultfit", "diagnostic")),
        Triple("Payments", 0xFF2E7D32, listOf("payment received", "thank you", "autopay", "neft", "imps", "upi payment", "bbps payment", "payment - ", "repayment")),
        Triple("Fees & interest", 0xFF757575, listOf("interest", "finance charge", "late fee", "late payment", "annual fee", "membership fee", "joining fee", "gst", "igst", "cgst", "sgst", "surcharge", "overlimit", "cash advance fee")),
        Triple("Education", 0xFF3949AB, listOf("school", "college", "university", "udemy", "coursera", "byju", "unacademy")),
        Triple("Other", 0xFF90A4AE, emptyList()),
    )

    fun rules(categoryIds: Map<String, Long>): List<CategoryRule> =
        seed.flatMap { (name, _, patterns) -> patterns.map { CategoryRule(categoryId = categoryIds.getValue(name), pattern = it, priority = SEED_PRIORITY) } }

    fun categories(): List<Category> = seed.map { (name, color, _) -> Category(name = name, color = color) }
}

class Categorizer(private val rules: List<CategoryRule>) {
    private val sorted = rules.sortedWith(compareByDescending<CategoryRule> { it.priority }.thenByDescending { it.pattern.length })

    fun categorise(merchant: String): Long? {
        val m = merchant.lowercase()
        return sorted.firstOrNull { m.contains(it.pattern.lowercase()) }?.categoryId
    }

    companion object {
        /** A reusable rule key from a raw merchant line: "SWIGGY*ORDER 1234 BANGALORE IN" -> "swiggy". */
        fun merchantKey(merchant: String): String {
            val words = merchant.lowercase()
                .replace(Regex("[*#_/]"), " ")
                .replace(Regex("\\b(www\\.|\\.com|\\.in|pvt|ltd|private|limited|india|bangalore|bengaluru|mumbai|delhi|gurgaon|gurugram|noida|pune|hyderabad|chennai|kolkata|in)\\b"), " ")
                .split(Regex("\\s+")).filter { w -> w.length >= 3 && w.any(Char::isLetter) && w.count(Char::isDigit) < 3 }
            return words.take(2).joinToString(" ").ifBlank { merchant.lowercase().take(12).trim() }
        }
    }
}

/** What a statement line is, separate from its category: only spends and fees are spending. */
object TxnKind {
    const val SPEND = "spend"
    const val PAYMENT = "payment"
    const val REFUND = "refund"
    const val CASHBACK = "cashback"
    const val FEE = "fee"

    private val payment = Regex(
        "payment|payzapp|bppy|thank you|autopay|auto debit|repayment|neft|imps|rtgs|bbps|bill ?desk|received|^upi[- ]|cc pay",
        RegexOption.IGNORE_CASE,
    )
    private val refund = Regex("refund|reversal|reversed|cancel", RegexOption.IGNORE_CASE)
    private val cashback = Regex("cash ?back|reward|\\bmonies\\b", RegexOption.IGNORE_CASE)
    private val fee = Regex(
        "^fee:|\\bfee\\b|\\bcharges?\\b|\\bgst\\b|igst|cgst|sgst|interest|finance charge|late pay|surcharge|mark-?up|overlimit|\\bemi\\b.*\\b(interest|int|processing|proc)\\b",
        RegexOption.IGNORE_CASE,
    )

    fun classify(description: String, isCredit: Boolean): String = when {
        cashback.containsMatchIn(description) -> CASHBACK // reversals are debits of the same kind
        isCredit && refund.containsMatchIn(description) -> REFUND
        isCredit && payment.containsMatchIn(description) -> PAYMENT
        isCredit -> REFUND
        fee.containsMatchIn(description) -> FEE
        else -> SPEND
    }

    fun label(kind: String) = when (kind) {
        PAYMENT -> "Payment"; REFUND -> "Refund"; CASHBACK -> "Cashback"; FEE -> "Fee"; else -> "Spend"
    }

    /** Counts toward "spent": purchases and fees, less refunds. Payments and cashback are separate. */
    fun spendSign(kind: String, isCredit: Boolean): Int = when (kind) {
        SPEND, FEE -> if (isCredit) -1 else 1
        REFUND -> -1
        else -> 0
    }
}