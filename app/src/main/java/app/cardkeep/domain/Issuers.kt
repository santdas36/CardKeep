package app.cardkeep.domain

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
        Issuer("Punjab & Sind Bank", 0xFF1E7B3C, 20),
        Issuer("Bandhan Bank", 0xFFE2231A, 20),
        Issuer("Equitas Bank", 0xFF2C5AA0, 20),
        Issuer("Utkarsh Bank", 0xFF6B2C91, 20),
        Issuer("Airtel Payments Bank", 0xFFE40000, 20),
        Issuer("Jio Payments Bank", 0xFF0F3CC9, 20),
        Issuer("India Post Payments Bank", 0xFFC8102E, 20),
        Issuer("NSDL Payments Bank", 0xFF1D3F8F, 20),
        Issuer("Other", 0xFF455A64, 20),
    )

    fun byName(name: String) = all.firstOrNull { it.name == name } ?: all.last()

    /**
     * Which bank a piece of card text names: what the camera read off the card, or the label on its chip.
     * The longest name found wins, so "State Bank of India" beats "Bank of India" and "South Indian Bank"
     * beats "Indian Bank". Whole words only, so OCR noise doesn't match short names by accident.
     */
    fun detect(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val t = " " + text.uppercase().replace(Regex("[^A-Z0-9&]+"), " ") + " "
        return ALIASES.flatMap { (bank, names) -> names.map { it to bank } }
            .filter { (alias, _) -> t.contains(" $alias ") }
            .maxByOrNull { it.first.length }?.second
    }

    private val ALIASES: Map<String, List<String>> = mapOf(
        "HDFC Bank" to listOf("HDFC", "HDFC BANK"),
        "SBI Card" to listOf("SBI CARD", "SBICARD"),
        "State Bank of India" to listOf("STATE BANK OF INDIA", "STATE BANK", "SBI"),
        "ICICI Bank" to listOf("ICICI", "ICICI BANK"),
        "Axis Bank" to listOf("AXIS BANK", "AXIS"),
        "Kotak" to listOf("KOTAK", "KOTAK MAHINDRA"),
        "Yes Bank" to listOf("YES BANK"),
        "IDFC FIRST" to listOf("IDFC", "IDFC FIRST"),
        "IndusInd" to listOf("INDUSIND", "INDUSIND BANK"),
        "AU Bank" to listOf("AU BANK", "AU SMALL FINANCE"),
        "RBL Bank" to listOf("RBL", "RBL BANK"),
        "Amex" to listOf("AMERICAN EXPRESS", "AMEX"),
        "HSBC" to listOf("HSBC"),
        "Standard Chartered" to listOf("STANDARD CHARTERED"),
        "Federal Bank" to listOf("FEDERAL BANK", "SCAPIA"),
        "BOB Card" to listOf("BOBCARD", "BOB CARD", "BOB FINANCIAL"),
        "Bank of Baroda" to listOf("BANK OF BARODA"),
        "OneCard" to listOf("ONECARD", "ONE CARD"),
        "Slice" to listOf("SLICE"),
        "Unity Bank (Roar)" to listOf("ROARBANK", "UNITY SMALL FINANCE"),
        "Punjab National Bank" to listOf("PUNJAB NATIONAL BANK", "PNB"),
        "Canara Bank" to listOf("CANARA BANK", "CANARA"),
        "Union Bank of India" to listOf("UNION BANK OF INDIA", "UNION BANK"),
        "Bank of India" to listOf("BANK OF INDIA"),
        "Indian Bank" to listOf("INDIAN BANK"),
        "Central Bank of India" to listOf("CENTRAL BANK OF INDIA"),
        "Indian Overseas Bank" to listOf("INDIAN OVERSEAS BANK", "IOB"),
        "Bank of Maharashtra" to listOf("BANK OF MAHARASHTRA"),
        "UCO Bank" to listOf("UCO BANK"),
        "IDBI Bank" to listOf("IDBI", "IDBI BANK"),
        "Citi" to listOf("CITI", "CITIBANK"),
        "DBS Bank" to listOf("DBS", "DBS BANK"),
        "DCB Bank" to listOf("DCB BANK"),
        "Barclays" to listOf("BARCLAYS"),
        "Deutsche Bank" to listOf("DEUTSCHE BANK"),
        "CSB Bank" to listOf("CSB BANK"),
        "City Union Bank" to listOf("CITY UNION BANK"),
        "Dhanlaxmi Bank" to listOf("DHANLAXMI BANK", "DHANLAXMI"),
        "J&K Bank" to listOf("J&K BANK", "JK BANK", "JAMMU KASHMIR BANK"),
        "Karnataka Bank" to listOf("KARNATAKA BANK"),
        "Karur Vysya Bank" to listOf("KARUR VYSYA BANK", "KARUR VYSYA", "KVB"),
        "South Indian Bank" to listOf("SOUTH INDIAN BANK"),
        "Saraswat Bank" to listOf("SARASWAT BANK", "SARASWAT"),
        "Abhyudaya Bank" to listOf("ABHYUDAYA BANK", "ABHYUDAYA"),
    ).filterKeys { name -> all.any { it.name == name } }

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
