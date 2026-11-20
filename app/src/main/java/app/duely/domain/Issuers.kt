package app.duely.domain

/**
 * Issuer presets: card colour and a typical statement-to-due gap. Users can override both.
 *
 * [statementFrom]: exact addresses statements are commonly sent from. Searched first (fast and precise).
 * They're best-known values, not confirmed by the banks; real senders are also learned from imports.
 * [senders]: domain words/substrings of the bank's mail domains, including the RBI-mandated ".bank.in"
 * domains (2025). Used as the second-level search and to tell which bank an email is from.
 */
data class Issuer(
    val name: String,
    val color: Long,
    val dueOffset: Int,
    val senders: List<String> = emptyList(),
    val statementFrom: List<String> = emptyList(),
)

object Issuers {
    val all = listOf(
        Issuer(
            "HDFC Bank", 0xFF004C8F, 20, listOf("hdfcbank", "hdfc.bank.in"),
            listOf("emailstatements.cards@hdfcbank.net", "emailstatements.cards@hdfcbank.bank.in", "emailstatements.cards@hdfc.bank.in"),
        ),
        Issuer("SBI Card", 0xFF27408B, 20, listOf("sbicard"), listOf("statements@sbicard.com")),
        Issuer("ICICI Bank", 0xFFAE282E, 18, listOf("icicibank", "icici.bank.in"), listOf("credit_cards@icicibank.com", "credit_cards@icici.bank.in")),
        Issuer("Axis Bank", 0xFF97144D, 18, listOf("axisbank", "axis.bank.in"), listOf("cc.statements@axisbank.com", "cc.statements@axis.bank.in")),
        Issuer("Kotak", 0xFFD9232D, 18, listOf("kotak"), listOf("cardstatement@kotak.com", "cardstatement@kotak.bank.in")),
        Issuer("Yes Bank", 0xFF00518F, 18, listOf("yesbank", "yes.bank.in")),
        Issuer("IDFC FIRST", 0xFF9C1D26, 20, listOf("idfcfirstbank", "idfcbank", "idfcfirst.bank.in"), listOf("statements@idfcfirstbank.com")),
        Issuer("IndusInd", 0xFF7A1F2B, 20, listOf("indusind")),
        Issuer("AU Bank", 0xFF6A2C91, 15, listOf("aubank", "au.bank.in")),
        Issuer("RBL Bank", 0xFF1F3A93, 15, listOf("rblbank", "rbl.bank.in")),
        Issuer("Amex", 0xFF2E77BB, 20, listOf("americanexpress", "aexp")),
        Issuer("HSBC", 0xFFC8102E, 18, listOf("hsbc")),
        Issuer("Standard Chartered", 0xFF0F7D5A, 20, listOf("sc.com", "standardchartered", "sc.bank.in")),
        Issuer("Federal Bank", 0xFF0E4D92, 20, listOf("federalbank", "federal.bank.in")),
        Issuer("BOB Card", 0xFFE4572E, 20, listOf("bobcard", "bobfinancial")),
        Issuer("OneCard", 0xFF2B2B2B, 18, listOf("getonecard", "onecard")),
        Issuer("Slice", 0xFF5B2BD8, 15, listOf("sliceit", "slicebank", "nesfb", "slice.bank.in")),
        Issuer("Unity Bank (Roar)", 0xFF13434F, 15, listOf("unitybank.co.in", "theunitybank", "unitybank", "roarbank", "unity.bank.in")),
        // More banks (cards are fully supported; statements from these aren't imported automatically yet).
        Issuer("State Bank of India", 0xFF22409A, 20),
        Issuer("Bank of Baroda", 0xFFF26522, 20),
        Issuer("Punjab National Bank", 0xFFA6192E, 20),
        Issuer("Canara Bank", 0xFF0072BC, 20),
        Issuer("Union Bank of India", 0xFFD71920, 20),
        Issuer("Bank of India", 0xFFF37021, 20),
        Issuer("Indian Bank", 0xFF1D4E89, 20),
        Issuer("Central Bank of India", 0xFFC8102E, 20),
        Issuer("Indian Overseas Bank", 0xFF0057A8, 20),
        Issuer("Bank of Maharashtra", 0xFF1B3F8B, 20),
        Issuer("UCO Bank", 0xFF0A5BA7, 20),
        Issuer("IDBI Bank", 0xFF00836C, 20),
        Issuer("Citi", 0xFF056DAE, 20),
        Issuer("DBS Bank", 0xFFE21D38, 20),
        Issuer("DCB Bank", 0xFF2D3E8C, 20),
        Issuer("Barclays", 0xFF00AEEF, 20),
        Issuer("Deutsche Bank", 0xFF0018A8, 20),
        Issuer("CSB Bank", 0xFF8A1538, 20),
        Issuer("City Union Bank", 0xFF7B1F3A, 20),
        Issuer("Dhanlaxmi Bank", 0xFF8B1E3F, 20),
        Issuer("J&K Bank", 0xFF006A4E, 20),
        Issuer("Karnataka Bank", 0xFFB5121B, 20),
        Issuer("Karur Vysya Bank", 0xFF1F5130, 20),
        Issuer("South Indian Bank", 0xFF9E1B32, 20),
        Issuer("Saraswat Bank", 0xFF7A2E8E, 20),
        Issuer("Abhyudaya Bank", 0xFF2E5E9E, 20),
        Issuer("Other", 0xFF455A64, 20),
    )

    fun byName(name: String) = all.firstOrNull { it.name == name } ?: all.last()

    /** Monogram for the bank badge. */
    fun short(name: String): String = when (name) {
        "HDFC Bank" -> "HDFC"; "SBI Card" -> "SBI"; "ICICI Bank" -> "ICICI"; "Axis Bank" -> "AXIS"
        "Kotak" -> "kotak"; "Yes Bank" -> "YES"; "IDFC FIRST" -> "IDFC"; "IndusInd" -> "IIB"
        "AU Bank" -> "AU"; "RBL Bank" -> "RBL"; "Amex" -> "AMEX"; "HSBC" -> "HSBC"
        "Standard Chartered" -> "SC"; "Federal Bank" -> "FED"; "BOB Card" -> "bob"; "OneCard" -> "ONE"
        "Slice" -> "slice"; "Unity Bank (Roar)" -> "ROAR"
        "State Bank of India" -> "SBI"; "Bank of Baroda" -> "bob"; "Punjab National Bank" -> "PNB"; "Canara Bank" -> "CAN"
        "Union Bank of India" -> "UBI"; "Bank of India" -> "BOI"; "Indian Bank" -> "IB"; "Central Bank of India" -> "CBI"
        "Indian Overseas Bank" -> "IOB"; "Bank of Maharashtra" -> "BOM"; "UCO Bank" -> "UCO"; "IDBI Bank" -> "IDBI"
        "Citi" -> "citi"; "DBS Bank" -> "DBS"; "DCB Bank" -> "DCB"; "Barclays" -> "BARC"; "Deutsche Bank" -> "DB"
        "CSB Bank" -> "CSB"; "City Union Bank" -> "CUB"; "Dhanlaxmi Bank" -> "DLB"; "J&K Bank" -> "J&K"
        "Karnataka Bank" -> "KBL"; "Karur Vysya Bank" -> "KVB"; "South Indian Bank" -> "SIB"; "Saraswat Bank" -> "SRS"
        "Abhyudaya Bank" -> "ABH"
        else -> name.filter(Char::isLetter).take(3).uppercase().ifEmpty { "•" }
    }

    fun bySender(from: String): Issuer? {
        val f = from.lowercase()
        return all.firstOrNull { iss -> iss.statementFrom.any { f.contains(it) } || iss.senders.any { f.contains(it) } }
    }

    val networks = listOf("Visa", "Mastercard", "RuPay", "Amex", "Diners")
}
