package app.duely.capture

import android.nfc.tech.IsoDep
import java.security.SecureRandom
import java.time.LocalDate

/** What a contactless read gives us. The CVV is never on the chip, so it's never here. */
data class ChipCard(val number: String, val expiry: String?, val holder: String?, val network: String?, val issuer: String? = null)

/**
 * Minimal EMV contactless reader: PPSE → SELECT AID → GET PROCESSING OPTIONS → READ RECORD,
 * pulling the card number (5A or track-2 57), expiry (5F24) and name (5F20) out of the records.
 * Everything stays in memory on the phone; nothing is sent anywhere.
 */
object EmvReader {
    private val PPSE = "2PAY.SYS.DDF01".toByteArray()

    fun read(tag: IsoDep): ChipCard? {
        tag.timeout = 5000
        tag.connect()
        try {
            val aids = selectPpse(tag).ifEmpty { KNOWN_AIDS.keys.toList() }
            for (aid in aids) {
                // One odd application (bad PDOL, malformed TLV) shouldn't stop us trying the next one.
                val card = try {
                    readAid(tag, aid)
                } catch (e: android.nfc.TagLostException) {
                    throw e // card moved away; nothing more to read
                } catch (e: Exception) {
                    null
                }
                if (card != null) return card
            }
            return null
        } finally {
            runCatching { tag.close() }
        }
    }

    private fun readAid(tag: IsoDep, aid: ByteArray): ChipCard? {
        val select = tag.transceive(apdu(0x00, 0xA4, 0x04, 0x00, aid))
        if (!ok(select)) return null
        val fci = body(select)
        val pdol = Tlv.find(fci, 0x9F38)
        // Some banks put their name in the app's preferred name (9F12) or label (50), e.g. "HDFC BANK".
        val label = listOf(0x9F12, 0x50).mapNotNull { t -> Tlv.find(fci, t)?.let { String(it, Charsets.ISO_8859_1) } }.joinToString(" ")
        val gpo = tag.transceive(apdu(0x80, 0xA8, 0x00, 0x00, gpoData(pdol)))
        val fields = mutableMapOf<Int, ByteArray>()
        if (ok(gpo)) {
            val g = body(gpo)
            collect(g, fields)
            afl(g)?.let { readAfl(tag, it, fields) }
        }
        if (fields[0x5A] == null && fields[0x57] == null) bruteRead(tag, fields)
        return toCard(fields, aid)?.copy(issuer = app.duely.domain.Issuers.detect(label))
    }

    // ---- Commands ----

    private fun selectPpse(tag: IsoDep): List<ByteArray> {
        val r = tag.transceive(apdu(0x00, 0xA4, 0x04, 0x00, PPSE))
        if (!ok(r)) return emptyList()
        // Directory entries (61) each carry an AID (4F); keep the card's priority order (87) when present.
        return Tlv.all(body(r), 0x61)
            .mapNotNull { e -> Tlv.find(e, 0x4F)?.let { aid -> aid to (Tlv.find(e, 0x87)?.firstOrNull()?.toInt() ?: 99) } }
            .sortedBy { it.second }.map { it.first }
    }

    /** PDOL answered with sensible terminal values (India, INR, today), zeros for the rest. */
    private fun gpoData(pdol: ByteArray?): ByteArray {
        if (pdol == null) return byteArrayOf(0x83.toByte(), 0x00)
        val out = mutableListOf<Byte>()
        var i = 0
        // A truncated PDOL (tag or length cut off) ends the list; answer what we could parse.
        while (i < pdol.size) {
            var tag = pdol[i].toInt() and 0xFF
            i++
            if (tag and 0x1F == 0x1F) {
                if (i >= pdol.size) break
                tag = (tag shl 8) or (pdol[i].toInt() and 0xFF); i++
            }
            if (i >= pdol.size) break
            val len = pdol[i].toInt() and 0xFF
            i++
            out += value(tag, len).toList()
        }
        if (out.size > 0xFD) return byteArrayOf(0x83.toByte(), 0x00) // wouldn't fit a short APDU
        return byteArrayOf(0x83.toByte(), out.size.toByte()) + out.toByteArray()
    }

    private fun value(tag: Int, len: Int): ByteArray {
        val today = LocalDate.now()
        val v = when (tag) {
            0x9F66 -> byteArrayOf(0x36, 0x00, 0x40, 0x00) // terminal qualifiers: contactless EMV, online capable
            0x9F1A, 0x5F2A -> byteArrayOf(0x03, 0x56) // India / INR
            0x9A -> byteArrayOf(bcd(today.year % 100), bcd(today.monthValue), bcd(today.dayOfMonth))
            0x9C -> byteArrayOf(0x00) // purchase
            0x9F37 -> ByteArray(len).also { SecureRandom().nextBytes(it) }
            else -> ByteArray(len)
        }
        return if (v.size == len) v else v.copyOf(len)
    }

    private fun bcd(n: Int) = (((n / 10) shl 4) or (n % 10)).toByte()

    private fun afl(gpo: ByteArray): ByteArray? {
        Tlv.find(gpo, 0x94)?.let { return it } // format 2
        Tlv.find(gpo, 0x80)?.let { return if (it.size > 2) it.copyOfRange(2, it.size) else null } // format 1: AIP(2) + AFL
        return null
    }

    private fun readAfl(tag: IsoDep, afl: ByteArray, into: MutableMap<Int, ByteArray>) {
        for (e in afl.indices step 4) {
            if (e + 3 >= afl.size) break
            val sfi = (afl[e].toInt() and 0xFF) shr 3
            for (rec in (afl[e + 1].toInt() and 0xFF)..(afl[e + 2].toInt() and 0xFF)) {
                val r = tag.transceive(apdu(0x00, 0xB2, rec, (sfi shl 3) or 4, null))
                if (ok(r)) collect(body(r), into)
            }
        }
    }

    /** Some cards answer GPO oddly; read the usual record files directly. */
    private fun bruteRead(tag: IsoDep, into: MutableMap<Int, ByteArray>) {
        for (sfi in 1..4) for (rec in 1..4) {
            val r = runCatching { tag.transceive(apdu(0x00, 0xB2, rec, (sfi shl 3) or 4, null)) }.getOrNull() ?: continue
            if (ok(r)) collect(body(r), into)
            if (into[0x5A] != null || into[0x57] != null) return
        }
    }

    private fun collect(data: ByteArray, into: MutableMap<Int, ByteArray>) {
        for (t in listOf(0x5A, 0x57, 0x5F24, 0x5F20, 0x9F0B)) Tlv.find(data, t)?.let { into.putIfAbsent(t, it) }
    }

    private fun toCard(f: Map<Int, ByteArray>, aid: ByteArray): ChipCard? {
        val track2 = f[0x57]?.let(::hex)
        val pan = (f[0x5A]?.let(::hex) ?: track2?.substringBefore('D'))?.trimEnd('F')?.filter(Char::isDigit)
        if (pan == null || pan.length !in 12..19) return null
        // Expiry: 5F24 is YYMMDD; track 2 has YYMM right after the separator.
        val yymm = f[0x5F24]?.let(::hex)?.take(4) ?: track2?.substringAfter('D', "")?.take(4)
        val expiry = yymm?.takeIf { it.length == 4 && it.all(Char::isDigit) }?.let { "${it.substring(2, 4)}/${it.substring(0, 2)}" }
        val name = (f[0x5F20] ?: f[0x9F0B])?.let { String(it, Charsets.ISO_8859_1) }
            ?.replace('/', ' ')?.replace(Regex("\\s+"), " ")?.trim()
            ?.takeIf { n -> n.length > 2 && n.any(Char::isLetter) && !n.contains("CARDHOLDER", true) && !n.contains("VALUED", true) }
        return ChipCard(pan, expiry, name, networkFor(aid))
    }

    private fun networkFor(aid: ByteArray): String? {
        val h = hex(aid)
        return KNOWN_AIDS.entries.firstOrNull { h.startsWith(hex(it.key)) }?.value
    }

    private val KNOWN_AIDS = linkedMapOf(
        bytes("A0000000031010") to "Visa",
        bytes("A0000000041010") to "Mastercard",
        bytes("A0000005241010") to "RuPay",
        bytes("A00000002501") to "Amex",
        bytes("A0000001523010") to "Diners",
        bytes("A0000000032010") to "Visa",
        bytes("A0000000043060") to "Mastercard",
    )

    // ---- Bytes ----

    private fun apdu(cla: Int, ins: Int, p1: Int, p2: Int, data: ByteArray?): ByteArray =
        if (data == null) byteArrayOf(cla.toByte(), ins.toByte(), p1.toByte(), p2.toByte(), 0x00)
        else byteArrayOf(cla.toByte(), ins.toByte(), p1.toByte(), p2.toByte(), data.size.toByte()) + data + byteArrayOf(0x00)

    private fun ok(r: ByteArray) = r.size >= 2 && r[r.size - 2] == 0x90.toByte() && r[r.size - 1] == 0x00.toByte()
    private fun body(r: ByteArray) = r.copyOf(r.size - 2)
    private fun hex(b: ByteArray) = b.joinToString("") { "%02X".format(it) }
    private fun bytes(h: String) = ByteArray(h.length / 2) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

/** BER-TLV, just enough for EMV: multi-byte tags, long lengths, recursion into constructed tags. */
internal object Tlv {
    private class Item(val tag: Int, val value: ByteArray, val constructed: Boolean)

    private fun items(data: ByteArray): List<Item> {
        val out = mutableListOf<Item>()
        var i = 0
        while (i < data.size) {
            val first = data[i].toInt() and 0xFF
            if (first == 0x00 || first == 0xFF) { i++; continue } // padding
            var tag = first
            i++
            if (first and 0x1F == 0x1F) {
                do { if (i >= data.size) return out; tag = (tag shl 8) or (data[i].toInt() and 0xFF); i++ } while (data[i - 1].toInt() and 0x80 != 0)
            }
            if (i >= data.size) return out
            var len = data[i].toInt() and 0xFF
            i++
            if (len and 0x80 != 0) {
                val n = len and 0x7F
                len = 0
                repeat(n) { if (i < data.size) { len = (len shl 8) or (data[i].toInt() and 0xFF); i++ } }
            }
            if (len < 0 || i + len > data.size) return out
            out += Item(tag, data.copyOfRange(i, i + len), first and 0x20 != 0)
            i += len
        }
        return out
    }

    /** First value with [tag], searching nested templates. */
    fun find(data: ByteArray, tag: Int): ByteArray? {
        for (it in items(data)) {
            if (it.tag == tag) return it.value
            if (it.constructed) find(it.value, tag)?.let { v -> return v }
        }
        return null
    }

    /** Every value with [tag], searching nested templates. */
    fun all(data: ByteArray, tag: Int): List<ByteArray> = items(data).flatMap {
        (if (it.tag == tag) listOf(it.value) else emptyList()) + (if (it.constructed) all(it.value, tag) else emptyList())
    }
}
