package app.cardkeep.ingest.alerts

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Developer tool, skipped unless ALERT_PROBE is set. Runs the alert parser over a real SMS export
 * (JSON lines with "from" and "body", e.g. from tools/sms-dump) kept outside the repo, and writes a
 * report to ALERT_OUT (also outside the repo): parsed counts per bank, and bank messages that mention
 * a card but didn't parse, with digits masked. Nothing here is committed.
 */
class AlertProbe {
    @Test fun probe() {
        val path = System.getenv("ALERT_PROBE")
        assumeTrue(!path.isNullOrBlank())
        val out = File(System.getenv("ALERT_OUT") ?: error("ALERT_OUT not set"))
        val parsed = sortedMapOf<String, Int>()
        val kinds = sortedMapOf<String, Int>()
        val examples = HashMap<String, MutableSet<String>>()
        val missed = ArrayList<String>()
        val looksLikeTxn = Regex("(?i)(spent|debited|credited|refund|reversed|txn|transaction|payment|withdrawn).{0,80}card|card.{0,80}(spent|debited|credited|refund|reversed)")
        File(path!!).readLines().filter { it.isNotBlank() }.forEach { line ->
            val from = field(line, "from") ?: return@forEach
            val body = field(line, "body") ?: return@forEach
            val a = AlertParser.parse(from, body)
            if (a != null) {
                val k = "${a.issuer} ${a.kind}${if (a.last4 == null) " (no digits)" else ""}"
                parsed.merge(a.issuer ?: "?", 1, Int::plus)
                kinds.merge(k, 1, Int::plus)
                val shape = body.replace(Regex("\\d"), "N").replace(Regex("\\s+"), " ").take(110)
                examples.getOrPut(k) { linkedSetOf() }.let { if (it.size < 3 && it.none { e -> e.take(25) == shape.take(25) }) it += "${a.merchant} | $shape" }
            } else if (AlertParser.issuer(from, body) != null && looksLikeTxn.containsMatchIn(body)) {
                missed += "$from | " + body.replace(Regex("\\d"), "N").replace(Regex("\\s+"), " ").take(220)
            }
        }
        out.writeText(buildString {
            appendLine("PARSED by bank: $parsed")
            appendLine("PARSED by kind:")
            kinds.forEach { (k, n) -> appendLine("  $n  $k"); examples[k]?.forEach { appendLine("        $it") } }
            appendLine("NOT PARSED (bank sender, mentions a card): ${missed.size}")
            missed.groupingBy { it.replace(Regex("N[N,.:/-]*"), "#").take(70) }.eachCount()
                .entries.sortedByDescending { it.value }
                .forEach { (k, n) -> appendLine("  [$n] " + missed.first { m -> m.replace(Regex("N[N,.:/-]*"), "#").take(70) == k }) }
        })
    }

    /** A string field from one JSON line (org.json isn't available in JVM tests). */
    private fun field(line: String, name: String): String? {
        val start = line.indexOf("\"$name\":\"").takeIf { it >= 0 }?.plus(name.length + 4) ?: return null
        val sb = StringBuilder()
        var i = start
        while (i < line.length) {
            val c = line[i]
            if (c == '"') return sb.toString()
            if (c == '\\' && i + 1 < line.length) {
                when (val e = line[i + 1]) {
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    'r' -> sb.append('\r')
                    'u' -> { sb.append(line.substring(i + 2, i + 6).toInt(16).toChar()); i += 4 }
                    else -> sb.append(e)
                }
                i += 2
            } else {
                sb.append(c)
                i++
            }
        }
        return null
    }
}
