package app.duely.capture

import app.duely.domain.CardNumbers

/** What the camera saw on a card. Pure text parsing, so it's unit-tested without a camera. */
data class ScannedCard(val number: String?, val expiry: String?, val issuer: String? = null)

object CardText {
    // 4-4-4-4 (Visa/MC/RuPay), 4-6-5 (Amex), 4-6-4 (Diners), or a plain run; OCR may mix spaces.
    private val numberRun = Regex("(?<!\\d)(\\d[\\d ]{12,22}\\d)(?!\\d)")
    private val expiryRe = Regex("(?<!\\d)(0[1-9]|1[0-2])\\s?[/\\-]\\s?(\\d{2}|20\\d{2})(?!\\d)")

    /** Common OCR slips on embossed digits. Only applied inside digit-looking runs. */
    private fun normalize(line: String) = line.map {
        when (it) { 'O', 'o', 'D', 'Q' -> '0'; 'I', 'l', '|' -> '1'; 'S' -> '5'; 'B' -> '8'; 'Z' -> '2'; else -> it }
    }.joinToString("")

    fun parse(text: String): ScannedCard {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        var number: String? = null
        for (l in lines + lines.joinToString(" ")) {
            val candidates = (numberRun.findAll(l) + numberRun.findAll(normalize(l))).map { it.value.filter(Char::isDigit) }
            number = candidates.firstOrNull { it.length in 14..19 && CardNumbers.luhnValid(it) }
            if (number != null) break
        }
        val expiry = latestExpiry(text)
        return ScannedCard(number, expiry, app.duely.domain.Issuers.detect(text))
    }

    /** Cards show "valid from" and "valid thru"; the later date is the expiry. Returns "MM/YY". */
    private fun latestExpiry(text: String): String? =
        expiryRe.findAll(text).map { m ->
            val mm = m.groupValues[1]
            val yy = m.groupValues[2].takeLast(2)
            Triple(yy.toInt(), mm.toInt(), "$mm/$yy")
        }.maxWithOrNull(compareBy({ it.first }, { it.second }))?.third
}
