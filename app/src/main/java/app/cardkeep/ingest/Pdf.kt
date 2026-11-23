package app.cardkeep.ingest

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.security.MessageDigest

object Pdf {
    @Volatile private var ready = false

    fun init(context: Context) {
        if (!ready) synchronized(this) {
            if (!ready) { PDFBoxResourceLoader.init(context.applicationContext); ready = true }
        }
    }

    sealed interface Result {
        data class Text(val text: String) : Result
        data object Locked : Result
        data class Failed(val reason: String) : Result
    }

    /** Tries no password, then each candidate, in memory. The PDF is never written to disk. */
    fun extract(bytes: ByteArray, candidates: List<String>): Result {
        for (pw in (listOf("") + candidates).distinct()) {
            try {
                PDDocument.load(bytes, pw).use { doc ->
                    val stripper = PDFTextStripper().apply { sortByPosition = true }
                    return Result.Text(stripper.getText(doc))
                }
            } catch (_: InvalidPasswordException) {
                continue
            } catch (e: Exception) {
                return Result.Failed(e.javaClass.simpleName + (e.message?.let { ": " + it.take(120) } ?: ""))
            } catch (e: OutOfMemoryError) {
                return Result.Failed("PDF too large")
            }
        }
        return Result.Locked
    }

    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
