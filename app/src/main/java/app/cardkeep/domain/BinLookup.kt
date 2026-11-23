package app.cardkeep.domain

import android.content.Context

/** What the first six digits say about a card. Either field may be unknown. */
data class BinInfo(val issuer: String?, val kind: String?)

/**
 * Offline BIN (first six digits) lookup for Indian cards, from a small bundled table
 * (assets/bins_in.csv, derived from iannuttall/binlist-data and venelinkochev/bin-list-data, CC BY 4.0). Nothing is sent anywhere.
 * Coverage is partial: unknown prefixes simply return null.
 */
object BinLookup {
    @Volatile private var table: Map<Int, BinInfo>? = null

    private fun load(context: Context): Map<Int, BinInfo> = table ?: synchronized(this) {
        table ?: runCatching {
            context.assets.open("bins_in.csv").bufferedReader().useLines { lines ->
                lines.filter { it.isNotBlank() && !it.startsWith("#") }.mapNotNull { line ->
                    val p = line.split(',')
                    val bin = p.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
                    val kind = when (p.getOrNull(2)) { "c" -> "credit"; "d" -> "debit"; "p" -> "prepaid"; else -> null }
                    bin to BinInfo(p.getOrNull(1)?.takeIf { it.isNotBlank() }, kind)
                }.toMap()
            }
        }.getOrDefault(emptyMap()).also { table = it }
    }

    fun find(context: Context, digits: String): BinInfo? {
        if (digits.length < 6) return null
        return load(context)[digits.take(6).toIntOrNull() ?: return null]
    }
}
