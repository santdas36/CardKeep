package app.cardkeep.autofill

import android.app.assist.AssistStructure
import android.view.View
import android.view.autofill.AutofillId
import android.view.autofill.AutofillValue
import app.cardkeep.security.SecretDraft

/** Which part of a card a checkout field wants. */
enum class Role { NUMBER, EXPIRY, MONTH, YEAR, CVV, NAME }

class Field(val id: AutofillId, val role: Role, val isList: Boolean, val options: List<String>, val maxLength: Int?)

/**
 * Finds card fields in a screen (apps and browsers): declared autofill hints first, then the HTML
 * autocomplete / name / id attributes, then the field's id and hint text.
 */
object CardFields {
    private val patterns = listOf(
        // Order matters: month/year before the generic expiry pattern.
        Role.MONTH to Regex("cc-?exp-?month|exp(iry|iration)?[-_ ]?(month|mm)\\b|creditcardexpirationmonth|card.?month", RegexOption.IGNORE_CASE),
        Role.YEAR to Regex("cc-?exp-?year|exp(iry|iration)?[-_ ]?(year|yy)|creditcardexpirationyear|card.?year", RegexOption.IGNORE_CASE),
        Role.EXPIRY to Regex("cc-?exp|expir|exp[-_ ]?date|valid.?(thru|till|through)|mm\\s*/\\s*yy|creditcardexpirationdate", RegexOption.IGNORE_CASE),
        Role.CVV to Regex("cvv|cvc|csc|cc-?csc|security.?code|card.?verification|creditcardsecuritycode", RegexOption.IGNORE_CASE),
        Role.NAME to Regex("cc-?name|name.?on.?card|card.?holder|cardholder|holder.?name", RegexOption.IGNORE_CASE),
        Role.NUMBER to Regex("cc-?number|card.?(number|no\\b|num)|cardnumber|ccnum|creditcardnumber|debit.?card|credit.?card", RegexOption.IGNORE_CASE),
    )

    fun find(structure: AssistStructure): List<Field> {
        val out = mutableListOf<Field>()
        for (i in 0 until structure.windowNodeCount) walk(structure.getWindowNodeAt(i).rootViewNode, out)
        // One field per role (the first one wins), and only screens that actually ask for a card number.
        val unique = out.distinctBy { it.role }
        return if (unique.any { it.role == Role.NUMBER }) unique else emptyList()
    }

    private fun walk(node: AssistStructure.ViewNode, out: MutableList<Field>) {
        val id = node.autofillId
        if (id != null && (node.autofillType == View.AUTOFILL_TYPE_TEXT || node.autofillType == View.AUTOFILL_TYPE_LIST)) {
            role(node)?.let { r ->
                val max = node.htmlInfo?.attributes?.firstOrNull { it.first.equals("maxlength", true) }?.second?.toIntOrNull()
                    ?: node.maxTextLength.takeIf { it > 0 }
                out += Field(id, r, node.autofillType == View.AUTOFILL_TYPE_LIST, node.autofillOptions?.map { it.toString() }.orEmpty(), max)
            }
        }
        for (i in 0 until node.childCount) walk(node.getChildAt(i), out)
    }

    private fun role(node: AssistStructure.ViewNode): Role? {
        node.autofillHints?.forEach { h ->
            when (h) {
                View.AUTOFILL_HINT_CREDIT_CARD_NUMBER -> return Role.NUMBER
                View.AUTOFILL_HINT_CREDIT_CARD_EXPIRATION_DATE -> return Role.EXPIRY
                View.AUTOFILL_HINT_CREDIT_CARD_EXPIRATION_MONTH -> return Role.MONTH
                View.AUTOFILL_HINT_CREDIT_CARD_EXPIRATION_YEAR -> return Role.YEAR
                View.AUTOFILL_HINT_CREDIT_CARD_SECURITY_CODE -> return Role.CVV
            }
        }
        val html = node.htmlInfo?.attributes.orEmpty()
            .filter { it.first.lowercase() in setOf("autocomplete", "name", "id", "placeholder", "aria-label", "label") }
            .joinToString(" ") { it.second.orEmpty() }
        val text = listOfNotNull(node.autofillHints?.joinToString(" "), html, node.idEntry, node.hint?.toString(), node.contentDescription?.toString())
            .joinToString(" ")
        if (text.isBlank()) return null
        // Plain "name" without card context is a shipping name; leave it alone.
        return patterns.firstOrNull { it.second.containsMatchIn(text) }?.first
    }

    /** The value to put in [f] for this card, or null when we don't have that detail. */
    fun value(f: Field, d: SecretDraft): AutofillValue? {
        // A bad stored month ("13/28") drops just the expiry; the other fields still fill.
        val mm = d.expiry.substringBefore('/', "").takeIf { it.length == 2 && (it.toIntOrNull() ?: 0) in 1..12 }
        val yy = d.expiry.substringAfter('/', "").takeIf { it.length == 2 }
        val text: String = when (f.role) {
            Role.NUMBER -> d.number.filter(Char::isDigit)
            Role.CVV -> d.cvv
            Role.NAME -> d.holder
            Role.EXPIRY -> if (mm == null || yy == null) "" else if ((f.maxLength ?: 5) >= 7) "$mm/20$yy" else "$mm/$yy"
            Role.MONTH -> mm.orEmpty()
            Role.YEAR -> yy?.let { if ((f.maxLength ?: 4) == 2) it else "20$it" }.orEmpty()
        }
        if (text.isBlank()) return null
        if (!f.isList) return AutofillValue.forText(text)
        // Dropdowns: match by number ("07", "7", "July") or year ("2028", "28").
        val idx = f.options.indexOfFirst { o ->
            val digits = o.filter(Char::isDigit)
            when (f.role) {
                Role.MONTH -> digits.toIntOrNull() == text.toIntOrNull() ||
                    mm?.toIntOrNull()?.takeIf { it in 1..12 }?.let { m -> o.trim().startsWith(java.time.Month.of(m).name.take(3), true) } == true
                Role.YEAR -> digits.isNotEmpty() && digits.takeLast(2) == text.takeLast(2)
                else -> o == text
            }
        }
        return if (idx >= 0) AutofillValue.forList(idx) else null
    }
}
