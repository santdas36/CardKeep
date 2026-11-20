package app.duely.ingest

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Developer tool, skipped unless STMT_PROBE is set. Reads real statement PDFs from outside the repo
 * with the same PDFBox settings as the app, then writes the extracted text and the parse result to
 * STMT_OUT (also outside the repo). Nothing here is committed.
 *
 *   STMT_PROBE = "Issuer|path.pdf|ENVVAR_WITH_PASSWORD;..."
 */
class StatementProbe {
    @Test fun probe() {
        val spec = System.getenv("STMT_PROBE")
        assumeTrue(!spec.isNullOrBlank())
        val out = File(System.getenv("STMT_OUT") ?: error("STMT_OUT not set")).apply { mkdirs() }
        for (entry in spec!!.split(';').filter { it.isNotBlank() }) {
            val (issuer, path, pwVar) = entry.split('|')
            val pw = System.getenv(pwVar).orEmpty()
            val text = PDDocument.load(File(path), pw).use { PDFTextStripper().apply { sortByPosition = true }.getText(it) }
            val base = File(path).nameWithoutExtension.filter { it.isLetterOrDigit() }.take(24)
            File(out, "$base.txt").writeText(text)
            val p = Parsers.forIssuer(issuer).parse(text)
            val v = Parsers.validate(p)
            File(out, "$base.parsed.txt").writeText(buildString {
                appendLine("parser=${Parsers.forIssuer(issuer).name} valid=${v.ok} ${v.note}")
                appendLine("stmt=${p.statementDate} start=${p.periodStart} due=${p.dueDate} total=${p.totalDuePaise} min=${p.minDuePaise} opening=${p.openingPaise} last4=${p.last4} limit=${p.creditLimitPaise}")
                appendLine("rewards=${p.rewards}")
                appendLine("lines=${p.lines.size} debits=${p.debits} credits=${p.credits}")
                p.lines.forEach { appendLine("  ${it.date} ${if (it.isCredit) "CR" else "DR"} ${it.amountPaise} | ${it.description}") }
            })
        }
    }
}
