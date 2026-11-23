package app.cardkeep.domain

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

object Money {
    /** Indian digit grouping: 125000_00 paise -> "₹1,25,000". */
    fun inr(paise: Long, withPaise: Boolean = false): String {
        val a = abs(paise)
        val rupees = (a / 100).toString()
        val grouped = if (rupees.length <= 3) rupees else {
            var rest = rupees.dropLast(3)
            val parts = ArrayDeque<String>()
            while (rest.length > 2) {
                parts.addFirst(rest.takeLast(2)); rest = rest.dropLast(2)
            }
            if (rest.isNotEmpty()) parts.addFirst(rest)
            parts.joinToString(",") + "," + rupees.takeLast(3)
        }
        val frac = a % 100
        val tail = if (withPaise && frac != 0L) "." + frac.toString().padStart(2, '0') else ""
        return (if (paise < 0) "-" else "") + "₹" + grouped + tail
    }

    /** "1,25,000.50" -> 12500050. Blank -> 0. Invalid -> null. */
    fun parse(input: String): Long? {
        val t = input.replace(",", "").replace("₹", "").trim()
        if (t.isEmpty()) return 0
        return try {
            t.toBigDecimal().movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact()
                .takeIf { it >= 0 }
        } catch (_: Exception) {
            null
        }
    }

    fun toInput(paise: Long): String =
        if (paise == 0L) "" else if (paise % 100 == 0L) (paise / 100).toString()
        else BigDecimal(paise).movePointLeft(2).toPlainString()
}

object Dates {
    private val short = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
    private val long = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)
    private val weekday = DateTimeFormatter.ofPattern("EEE, d MMM", Locale.ENGLISH)

    fun short(d: LocalDate): String = d.format(short)
    fun long(d: LocalDate): String = d.format(long)
    fun weekday(d: LocalDate): String = d.format(weekday)

    fun relative(days: Long): String = when {
        days < -1 -> "${-days} days overdue"
        days == -1L -> "1 day overdue"
        days == 0L -> "today"
        days == 1L -> "tomorrow"
        else -> "in $days days"
    }
}

object CardNumbers {
    fun luhnValid(digits: String): Boolean {
        if (digits.length !in 12..19 || digits.any { !it.isDigit() }) return false
        var sum = 0
        digits.reversed().forEachIndexed { i, ch ->
            var d = ch - '0'
            if (i % 2 == 1) { d *= 2; if (d > 9) d -= 9 }
            sum += d
        }
        return sum % 10 == 0
    }

    fun detectNetwork(digits: String): String? {
        if (digits.isEmpty()) return null
        val p2 = digits.take(2).toIntOrNull() ?: -1
        val p3 = digits.take(3).toIntOrNull() ?: -1
        val p4 = digits.take(4).toIntOrNull() ?: -1
        return when {
            digits.startsWith("4") -> "Visa"
            p2 in 34..34 || p2 == 37 -> "Amex"
            p2 in 51..55 || p4 in 2221..2720 -> "Mastercard"
            p2 == 36 || p2 == 38 || p3 in 300..305 -> "Diners"
            p2 == 60 || p2 == 65 || p2 == 81 || p2 == 82 || p3 == 508 || p3 == 353 || p3 == 356 -> "RuPay"
            else -> null
        }
    }

    /** Groups digits for display; Amex is 4-6-5, everything else blocks of 4. */
    fun format(digits: String): String =
        if (digits.length == 15 && (digits.startsWith("34") || digits.startsWith("37")))
            "${digits.substring(0, 4)} ${digits.substring(4, 10)} ${digits.substring(10)}"
        else digits.chunked(4).joinToString(" ")
}
