package app.cardkeep.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import app.cardkeep.data.Card

/**
 * Logo-free card art for popular Indian cards: each is an approximation of the real card's colours and
 * main motif (no bank marks, network logos or text), drawn in code so it's sharp at any size. A slight
 * blur on top keeps them soft and modern (Android 12+; older phones get them unblurred).
 *
 * Which art a card gets: [Card.art] null = "auto" (matched from bank + nickname, else bank colours),
 * [BANK_COLOURS] = always the bank-colour gradient, otherwise an art id.
 */
enum class Motif {
    NONE, SHEEN, GLOW, RAYS, CHEVRON, ARROW, PYRAMID, RIBBON, STREAK, ORB, GLOBE, DOTS, FACETS, PIXELS,
    SWIRL, BAND, LINES, BRUSHED, RIPPLES, RINGS, SPARKLE, MOSAIC, SPLIT, ARC, CROSS, WAVES,
}

class CardArt(
    val id: String,
    val bank: String,
    val name: String,
    /** Background gradient, top-left to bottom-right (or left to right when [horizontal]). */
    val colors: List<Long>,
    val motif: Motif = Motif.NONE,
    val accent: List<Long> = emptyList(),
    /** Nickname patterns; every word of one pattern must appear. Longer patterns win. */
    val match: List<String> = emptyList(),
    /** Light art: card text switches to dark ink. */
    val light: Boolean = false,
    /** Motif placement (fractions of width / height) for motifs that sit somewhere. */
    val x: Float = 0.75f,
    val y: Float = 0.5f,
    val horizontal: Boolean = false,
    /** The only design of a single-product issuer (slice, Roar): matches any nickname. */
    val anyName: Boolean = false,
)

const val BANK_COLOURS = "bank"

object CardArts {
    private const val HDFC = "HDFC Bank"
    private const val SBI = "SBI Card"
    private const val ICICI = "ICICI Bank"
    private const val AXIS = "Axis Bank"

    val all: List<CardArt> = listOf(
        // HDFC Bank
        CardArt("hdfc_millennia", HDFC, "Millennia", listOf(0xFF1A3A8C, 0xFF2D66D4), Motif.SHEEN, listOf(0xFF9CC0FF), listOf("millennia")),
        CardArt("hdfc_regalia_gold", HDFC, "Regalia Gold", listOf(0xFF2A1A10, 0xFF3A2414), Motif.BAND, listOf(0xFFC9963E, 0xFFF2CF7A), listOf("regalia gold", "gold"), horizontal = true),
        CardArt("hdfc_regalia", HDFC, "Regalia", listOf(0xFF2B160F, 0xFF4A2618), Motif.SHEEN, listOf(0xFFD08A5A), listOf("regalia")),
        CardArt("hdfc_infinia", HDFC, "Infinia", listOf(0xFF081634, 0xFF173A7A), Motif.SHEEN, listOf(0xFF5B86D6), listOf("infinia")),
        CardArt("hdfc_diners_black", HDFC, "Diners Club Black", listOf(0xFF0B0B0C, 0xFF1A1A1D), Motif.DOTS, listOf(0xFFFFFFFF), listOf("diners", "diners black", "dcb")),
        CardArt("hdfc_diners_privilege", HDFC, "Diners Club Privilege", listOf(0xFF3E1A34, 0xFF6B2D52), Motif.DOTS, listOf(0xFFF3C6DF), listOf("dcp", "diners privilege", "privilege")),
        CardArt("hdfc_phonepe_ultimo", HDFC, "PhonePe Ultimo", listOf(0xFF0E0B14, 0xFF1C1428), Motif.GLOW, listOf(0xFF6B3FD6, 0xFF2A1650), listOf("ultimo", "phonepe ultimo"), x = 0.9f, y = 0.1f),
        CardArt("hdfc_phonepe_uno", HDFC, "PhonePe UNO", listOf(0xFF4B1F8F, 0xFF7A3FD0), Motif.SHEEN, listOf(0xFFC9A8FF), listOf("uno", "phonepe uno")),
        CardArt("hdfc_biz_black", HDFC, "Biz Black", listOf(0xFF0D0D0F, 0xFF1A1A1E), Motif.ARROW, listOf(0xFF3A3B40), listOf("biz black")),
        CardArt("hdfc_biz_power", HDFC, "Biz Power", listOf(0xFF151335, 0xFF221E52), Motif.ARROW, listOf(0xFF6A5CF0), listOf("biz power", "biz")),
        CardArt("hdfc_swiggy", HDFC, "Swiggy", listOf(0xFFFF8A1F, 0xFFEC3B7A, 0xFF4B3BD8), Motif.NONE, match = listOf("swiggy", "swiggy orange", "ornge")),
        CardArt("hdfc_swiggy_blck", HDFC, "Swiggy BLCK", listOf(0xFF0E0E10, 0xFF16161A), Motif.GLOW, listOf(0xFFFF5A1F, 0xFFB8201A), listOf("swiggy black", "swiggy blck", "blck"), x = 1f, y = 1f),
        CardArt("hdfc_tata_neu_infinity", HDFC, "Tata Neu Infinity", listOf(0xFF100B20, 0xFF1A1233), Motif.ARC, listOf(0xFF8E4DFF, 0xFFD23CC4), listOf("neu", "neu infinity", "tata neu")),
        CardArt("hdfc_tata_neu_plus", HDFC, "Tata Neu Plus", listOf(0xFF3FC6F0, 0xFF6C5CE7), Motif.SHEEN, listOf(0xFFFFFFFF), listOf("neu plus")),
        CardArt("hdfc_upi_rupay", HDFC, "UPI RuPay", listOf(0xFF11142E, 0xFF1B1F45), Motif.PIXELS, listOf(0xFFFF4F9A, 0xFF3D7BFF), listOf("upi", "rupay", "biz")),
        CardArt("hdfc_moneyback", HDFC, "MoneyBack+", listOf(0xFF1AA3DD, 0xFF48C0EE), Motif.CROSS, listOf(0xFFE53935, 0xFF1A2A6C), listOf("moneyback", "money back")),
        CardArt("hdfc_freedom", HDFC, "Freedom", listOf(0xFF1554B5, 0xFF2C7BE5), Motif.ARROW, listOf(0xFFE53935), listOf("freedom")),
        CardArt("hdfc_marriott", HDFC, "Marriott Bonvoy", listOf(0xFF101012, 0xFF1C1C20), Motif.STREAK, listOf(0xFFC9A45C), listOf("marriott", "bonvoy")),
        CardArt("hdfc_indianoil", HDFC, "IndianOil", listOf(0xFFF6D7A7, 0xFFD9A35C), Motif.SHEEN, listOf(0xFFFFFFFF), listOf("indianoil", "indian oil", "iocl"), light = true),
        CardArt("hdfc_pixel", HDFC, "Pixel Play", listOf(0xFF7FD3D0, 0xFFB7EEE8), Motif.MOSAIC, listOf(0xFFFF6FA8, 0xFFFFD84D, 0xFF3AA0FF), listOf("pixel", "pixel play"), light = true),
        CardArt("hdfc_pixel_go", HDFC, "Pixel Go", listOf(0xFF3EC3F2, 0xFF7FD8F7), Motif.SHEEN, listOf(0xFFFFFFFF), listOf("pixel go"), light = true),

        // SBI Card
        CardArt("sbi_simplyclick", SBI, "SimplyCLICK", listOf(0xFF0D3527, 0xFF1E6A48), Motif.LINES, listOf(0xFF7FD1A6), listOf("simplyclick", "simply click", "click")),
        CardArt("sbi_simplysave", SBI, "SimplySAVE", listOf(0xFF1A4FA8, 0xFF2E77D0), Motif.RAYS, listOf(0xFF8FC2FF), listOf("simplysave", "simply save", "save")),
        CardArt("sbi_cashback", SBI, "Cashback", listOf(0xFF2E1A7A, 0xFF4B2AAE), Motif.CHEVRON, listOf(0xFFFFD21F), listOf("cashback", "cash back")),
        CardArt("sbi_elite", SBI, "Elite", listOf(0xFF141210, 0xFF2A231C), Motif.BRUSHED, listOf(0xFFC8A878), listOf("elite")),
        CardArt("sbi_prime", SBI, "Prime", listOf(0xFF0E0E14, 0xFF1A1A24), Motif.RIBBON, listOf(0xFFFF4D6D, 0xFFFFC93C, 0xFF3DDC97, 0xFF4D8BFF, 0xFFB45CFF), listOf("prime")),
        CardArt("sbi_octane", SBI, "BPCL Octane", listOf(0xFF0F0D0A, 0xFF1E1A12), Motif.GLOW, listOf(0xFFF2C14E, 0xFF8A5A12), listOf("octane", "bpcl"), x = 0.55f, y = 0.5f),
        CardArt("sbi_irctc", SBI, "IRCTC", listOf(0xFFF57C1F, 0xFF2E9E5B), Motif.SHEEN, listOf(0xFFFFFFFF), listOf("irctc", "railway")),
        CardArt("sbi_pulse", SBI, "Pulse", listOf(0xFF1A0E2E, 0xFF3A0E2A), Motif.RIPPLES, listOf(0xFFE5395F), listOf("pulse"), x = 0.85f, y = 0.6f),

        // ICICI Bank
        CardArt("icici_platinum", ICICI, "Platinum", listOf(0xFF0F1012, 0xFF1C1D20), Motif.STREAK, listOf(0xFFD9DCE0), listOf("platinum")),
        CardArt("icici_amazon", ICICI, "Amazon Pay", listOf(0xFF2A2E35, 0xFF15171B), Motif.BRUSHED, listOf(0xFFFFFFFF), listOf("amazon", "apay")),
        CardArt("icici_sapphiro", ICICI, "Sapphiro", listOf(0xFF0D3B8A, 0xFF5DB2F2), Motif.FACETS, match = listOf("sapphiro", "sapphire")),
        CardArt("icici_rubyx", ICICI, "Rubyx", listOf(0xFF7A0A1E, 0xFFC8102E), Motif.FACETS, match = listOf("rubyx", "ruby")),
        CardArt("icici_coral", ICICI, "Coral", listOf(0xFF5A0A0A, 0xFFC0341D), Motif.ORB, listOf(0xFFFF7A45, 0xFF8A1A0A), listOf("coral"), x = 0.2f, y = 0.5f),
        CardArt("icici_emeralde", ICICI, "Emeralde", listOf(0xFF06261E, 0xFF0F4D3C), Motif.SHEEN, listOf(0xFF3FBF95), listOf("emeralde", "emerald")),
        CardArt("icici_mmt", ICICI, "MakeMyTrip", listOf(0xFF2A1206, 0xFF8A3A10), Motif.GLOW, listOf(0xFFFFB347, 0xFFB8561A), listOf("makemytrip", "mmt"), x = 0.7f, y = 0.95f),
        CardArt("icici_hpcl", ICICI, "HPCL Super Saver", listOf(0xFFE9F1FA, 0xFFCFE0F5), Motif.MOSAIC, listOf(0xFF5BC0EB, 0xFFFDE74C, 0xFF9BC53D, 0xFFE55934), listOf("hpcl", "super saver"), light = true),

        // Axis Bank
        CardArt("axis_privilege", AXIS, "Privilege", listOf(0xFF131315, 0xFF1E1E22), Motif.PYRAMID, listOf(0xFFE0337A, 0xFF6E1238), listOf("privilege")),
        CardArt("axis_airtel", AXIS, "Airtel", listOf(0xFF121314, 0xFF1D1E20), Motif.RIBBON, listOf(0xFF6E7276, 0xFFF2F3F5, 0xFF8A8E93), listOf("airtel")),
        CardArt("axis_atlas", AXIS, "Atlas", listOf(0xFF0F0F11, 0xFF19191C), Motif.GLOBE, listOf(0xFFE23A86, 0xFF5A0E33), listOf("atlas"), x = 0.73f, y = 0.55f),
        CardArt("axis_magnus", AXIS, "Magnus", listOf(0xFF111113, 0xFF1C1C20), Motif.SWIRL, listOf(0xFFF0479A, 0xFF5C0F33), listOf("magnus")),
        CardArt("axis_ace", AXIS, "ACE", listOf(0xFF0E0B12, 0xFF1A1420), Motif.WAVES, listOf(0xFFE0408A), listOf("ace")),
        CardArt("axis_flipkart", AXIS, "Flipkart", listOf(0xFF0D0D12, 0xFF17171E), Motif.RIBBON, listOf(0xFF3D5AFE, 0xFF8E44FF, 0xFFFF4FA0), listOf("flipkart")),
        CardArt("axis_select", AXIS, "Select", listOf(0xFF1A0D14, 0xFF2A111E), Motif.RIPPLES, listOf(0xFFC2185B), listOf("select"), x = 0.75f, y = 0.55f),
        CardArt("axis_neo", AXIS, "Neo", listOf(0xFF120A10, 0xFF1E0F1A), Motif.RINGS, listOf(0xFFE0337A), listOf("neo")),
        CardArt("axis_reserve", AXIS, "Reserve", listOf(0xFF0B0B0D, 0xFF141418), Motif.ORB, listOf(0xFFFF4F9A, 0xFF8A0F45), listOf("reserve"), x = 0.3f, y = 0.5f),

        // Others
        CardArt("kotak_811", "Kotak", "811", listOf(0xFF0E1F2E, 0xFF173246), Motif.RIPPLES, listOf(0xFF5A8AAA), listOf("811"), x = 0f, y = 1f),
        CardArt("kotak_myntra", "Kotak", "Myntra", listOf(0xFFFFB38A, 0xFFFF6F91), Motif.SHEEN, listOf(0xFFFFFFFF), listOf("myntra"), light = true),
        CardArt("yes_elite", "Yes Bank", "Elite+", listOf(0xFF0A1C5A, 0xFF1A3B9E), Motif.RAYS, listOf(0xFF6EA2FF), listOf("elite")),
        CardArt("idfc_millennia", "IDFC FIRST", "Millennia", listOf(0xFF5A0F1E, 0xFF9B1B30), Motif.GLOW, listOf(0xFFFF5A6E, 0xFF9B1B30), listOf("millennia"), x = 0.85f, y = 0.15f),
        CardArt("idfc_select", "IDFC FIRST", "Select", listOf(0xFF8E1020, 0xFFC8102E), Motif.SHEEN, listOf(0xFFFF8A95), listOf("select")),
        CardArt("idfc_wealth", "IDFC FIRST", "Wealth", listOf(0xFF0E0E10, 0xFF1B1B1F), Motif.BRUSHED, listOf(0xFFFFFFFF), listOf("wealth")),
        CardArt("au_lit", "AU Bank", "LIT", listOf(0xFFF4F1FB, 0xFFE3DAF7), Motif.MOSAIC, listOf(0xFFB794F4, 0xFFFF8FB1, 0xFF7FD3FF), listOf("lit"), light = true),
        CardArt("au_zenith", "AU Bank", "Zenith+", listOf(0xFF0B2A1E, 0xFF124434), Motif.STREAK, listOf(0xFFD4AF37), listOf("zenith")),
        CardArt("amex_plat_travel", "Amex", "Platinum Travel", listOf(0xFFD3D7DC, 0xFF8E949B), Motif.SHEEN, listOf(0xFFFFFFFF), listOf("platinum", "platinum travel", "plat travel"), light = true),
        CardArt("amex_mrcc", "Amex", "Membership Rewards", listOf(0xFFB4C1CE, 0xFF7C8FA4), Motif.SHEEN, listOf(0xFFFFFFFF), listOf("mrcc", "membership", "rewards"), light = true),
        CardArt("amex_gold", "Amex", "Gold Charge", listOf(0xFFE8C66A, 0xFFB8862E), Motif.SHEEN, listOf(0xFFFFF3C4), listOf("gold"), light = true),
        CardArt("amex_smartearn", "Amex", "SmartEarn", listOf(0xFF0F6FC6, 0xFF3FA2F0), Motif.SHEEN, listOf(0xFFFFFFFF), listOf("smartearn", "smart earn")),
        CardArt("hsbc_live", "HSBC", "Live+", listOf(0xFFE9A9B3, 0xFFC8102E, 0xFF7A0A1E), Motif.SPLIT, listOf(0xFF8E0C22), listOf("live")),
        CardArt("hsbc_platinum", "HSBC", "Platinum", listOf(0xFFB9BDC3, 0xFF9EA2A8), Motif.FACETS, match = listOf("platinum"), light = true),
        CardArt("sc_ultimate", "Standard Chartered", "Ultimate", listOf(0xFF0C0C0E, 0xFF17171A), Motif.SPARKLE, listOf(0xFFD4AF37), listOf("ultimate")),
        CardArt("sc_smart", "Standard Chartered", "Smart", listOf(0xFFE9ECEF, 0xFFCED4DA), Motif.ARROW, listOf(0xFFA8B0B8), listOf("smart"), light = true),
        CardArt("indusind_legend", "IndusInd", "Legend", listOf(0xFF101012, 0xFF2A2B2F), Motif.BRUSHED, listOf(0xFFC0C0C0), listOf("legend")),
        CardArt("indusind_tiger", "IndusInd", "Tiger", listOf(0xFF0B0B0C, 0xFF151517), Motif.LINES, listOf(0xFFE0E0E0), listOf("tiger")),
        CardArt("onecard_metal", "OneCard", "Metal", listOf(0xFF141416, 0xFF24252A), Motif.BRUSHED, listOf(0xFFFFFFFF), anyName = true),
        CardArt("slice", "Slice", "slice", listOf(0xFF5A1FD1, 0xFF8B45F0), Motif.SHEEN, listOf(0xFFB98CFF), anyName = true),
        CardArt("roar", "Unity Bank (Roar)", "Roarbank", listOf(0xFF26272B, 0xFF08080A), Motif.GLOW, listOf(0xFF3A3B40, 0xFF26272B), anyName = true, x = 0.25f, y = 0.15f),
        CardArt("scapia", "Federal Bank", "Scapia", listOf(0xFFE8442B, 0xFFF2683C), Motif.SHEEN, listOf(0xFFFFB199), listOf("scapia")),

        // More variants
        CardArt("sbi_phonepe_black", SBI, "PhonePe SELECT Black", listOf(0xFF0B0B10, 0xFF15141E), Motif.RIBBON, listOf(0xFF1FC8C8, 0xFF7A4DFF), listOf("phonepe black", "select black", "phonepe select")),
        CardArt("sbi_phonepe_purple", SBI, "PhonePe PURPLE", listOf(0xFF2A0F55, 0xFF4E1F96), Motif.GLOW, listOf(0xFF9D6BFF, 0xFF4E1F96), listOf("phonepe purple", "purple"), x = 0.7f, y = 0.4f),
        CardArt("sbi_miles_elite", SBI, "Miles Elite", listOf(0xFFE9C9B4, 0xFFC9967A), Motif.RAYS, listOf(0xFFFFF1E6), listOf("miles", "miles elite"), light = true),
        CardArt("sbi_aurum", SBI, "Aurum", listOf(0xFF0B0B0C, 0xFF161617), Motif.SPARKLE, listOf(0xFFD9B45A), listOf("aurum")),
        CardArt("icici_times_black", ICICI, "Times Black", listOf(0xFF0A0A0B, 0xFF18181A), Motif.BRUSHED, listOf(0xFFB8B8B8), listOf("times", "times black")),
        CardArt("hsbc_premier", "HSBC", "Premier", listOf(0xFF0F1226, 0xFF1E2448), Motif.FACETS, match = listOf("premier")),
        CardArt("hsbc_travelone", "HSBC", "TravelOne", listOf(0xFF0C0C0D, 0xFF19191B), Motif.CHEVRON, listOf(0xFF6B6E73), listOf("travelone", "travel one", "t1")),
        CardArt("yes_marquee", "Yes Bank", "Marquee", listOf(0xFF111114, 0xFF222228), Motif.SWIRL, listOf(0xFFB9BEC6, 0xFF3A3D44), listOf("marquee")),
        CardArt("yes_reserv", "Yes Bank", "Reserv", listOf(0xFF0A1238, 0xFF16246A), Motif.SHEEN, listOf(0xFF6F8CFF), listOf("reserv", "reserve")),
        CardArt("yes_kiwi", "Yes Bank", "Kiwi", listOf(0xFF8BD650, 0xFF5DBB3A), Motif.SHEEN, listOf(0xFFE8FFD6), listOf("kiwi"), light = true),
        CardArt("bob_eterna", "BOB Card", "Eterna", listOf(0xFF071A33, 0xFF0D2C52), Motif.WAVES, listOf(0xFF2EC4E6), listOf("eterna")),
        CardArt("bob_premier", "BOB Card", "Premier", listOf(0xFF5A0E14, 0xFF9B1C24), Motif.SHEEN, listOf(0xFFFF8A8A), listOf("premier")),
        CardArt("amex_platinum_charge", "Amex", "Platinum Charge", listOf(0xFFDADDE1, 0xFFA3A8AE), Motif.BRUSHED, listOf(0xFFFFFFFF), listOf("platinum charge", "plat charge", "charge"), light = true),
        CardArt("amex_platinum_reserve", "Amex", "Platinum Reserve", listOf(0xFFB7BCC2, 0xFF7E848B), Motif.SHEEN, listOf(0xFFFFFFFF), listOf("platinum reserve", "reserve"), light = true),
    )

    private val byId = all.associateBy { it.id }

    fun byId(id: String?): CardArt? = id?.let { byId[it] }

    fun forBank(issuer: String): List<CardArt> = all.filter { it.bank == issuer }

    /** Best art for a bank + nickname, or null when nothing fits. */
    fun match(issuer: String, nickname: String): CardArt? {
        val name = nickname.lowercase().replace(Regex("[^a-z0-9+ ]"), " ")
        val words = name.split(' ').filter { it.isNotBlank() }.toSet()
        fun hits(pattern: String) = pattern.split(' ').all { w -> words.contains(w) || (w.length >= 5 && name.contains(w)) }
        val candidates = forBank(issuer)
        candidates.mapNotNull { a ->
            a.match.filter(::hits).maxOfOrNull { it.split(' ').size }?.let { a to it }
        }.maxByOrNull { it.second }?.let { return it.first }
        return candidates.firstOrNull { it.anyName }
    }

    /** The art a card shows: its chosen one, or (auto) the best match; null = bank colours. */
    fun resolve(card: Card): CardArt? = when (card.art) {
        null -> match(card.issuer, card.nickname)
        BANK_COLOURS -> null
        else -> byId(card.art)
    }
}

/** Card text colour for this card: from its art when it has one, else from the bank colour. */
fun cardInkFor(card: Card): Color {
    val art = CardArts.resolve(card) ?: return cardInk(card.color)
    return if (art.light) Color(0xFF15171C) else Color.White
}

/**
 * The card's background, filling its (clipped) parent: the chosen or matched art with a slight blur,
 * or the bank-colour gradient.
 */
@Composable
fun CardSurface(card: Card, modifier: Modifier = Modifier) {
    val art = remember(card.art, card.issuer, card.nickname) { CardArts.resolve(card) }
    if (art == null) {
        Box(modifier.cardBackground(card.color, card.id))
    } else {
        ArtSurface(art, modifier)
    }
}

/**
 * One art. Blurred, it's rendered once per art and size into a small bitmap, softened with a box blur on
 * a background thread and scaled up when drawn; every card showing it reuses that bitmap, so scrolling
 * and spreading the stack only draws an image. Until it's ready the art is drawn unblurred, so a card is
 * never blank. Same result on every Android version. Unblurred (thumbnails) it's drawn directly.
 */
@Composable
fun ArtSurface(art: CardArt, modifier: Modifier = Modifier, blur: Boolean = true) {
    val scope = rememberCoroutineScope()
    val version = remember { mutableIntStateOf(0) }
    Box(
        modifier.drawWithCache {
            val w = size.width.toInt()
            val h = size.height.toInt()
            val bg = artBackground(art, size.width, size.height)
            val plain = onDrawBehind { drawRect(bg); drawMotif(art, size.width, size.height) }
            if (!blur || w <= 0 || h <= 0) return@drawWithCache plain
            version.intValue // re-run when the bitmap lands
            val key = "${art.id}@${w}x$h"
            val bmp = ArtBitmaps.get(key)
            if (bmp == null) {
                scope.launch(kotlinx.coroutines.Dispatchers.Default) {
                    val made = runCatching { renderBlurred(art, w, h) }.getOrNull() ?: return@launch
                    ArtBitmaps.put(key, made)
                    version.intValue++
                }
                return@drawWithCache plain
            }
            onDrawBehind {
                drawImage(bmp, dstSize = androidx.compose.ui.unit.IntSize(w, h), filterQuality = androidx.compose.ui.graphics.FilterQuality.Low)
            }
        },
    )
}

/** Draws [art] at 1/[DOWN] size, box-blurs it (three passes, close to a Gaussian) and returns the bitmap. */
private fun renderBlurred(art: CardArt, w: Int, h: Int): ImageBitmap {
    val sw = (w + DOWN - 1) / DOWN
    val sh = (h + DOWN - 1) / DOWN
    val img = ImageBitmap(sw, sh)
    androidx.compose.ui.graphics.drawscope.CanvasDrawScope().draw(
        androidx.compose.ui.unit.Density(1f), androidx.compose.ui.unit.LayoutDirection.Ltr,
        androidx.compose.ui.graphics.Canvas(img), Size(sw.toFloat(), sh.toFloat()),
    ) {
        drawRect(artBackground(art, sw.toFloat(), sh.toFloat()))
        drawMotif(art, sw.toFloat(), sh.toFloat())
    }
    val r = (w * BLUR / DOWN).roundToIntSafe().coerceIn(1, 12)
    val bitmap = img.asAndroidBitmap()
    val px = IntArray(sw * sh)
    bitmap.getPixels(px, 0, sw, 0, 0, sw, sh)
    val tmp = IntArray(px.size)
    repeat(3) { boxBlur(px, tmp, sw, sh, r, horizontal = true); boxBlur(tmp, px, sw, sh, r, horizontal = false) }
    bitmap.setPixels(px, 0, sw, 0, 0, sw, sh)
    return img
}

private fun Float.roundToIntSafe() = kotlin.math.round(this).toInt()

/** One box-blur pass along rows or columns (edges clamped), ARGB channels separately. */
private fun boxBlur(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
    val len = if (horizontal) w else h
    val lines = if (horizontal) h else w
    val div = 2 * r + 1
    for (line in 0 until lines) {
        fun at(i: Int): Int {
            val c = i.coerceIn(0, len - 1)
            return if (horizontal) src[line * w + c] else src[c * w + line]
        }
        var sa = 0; var sr = 0; var sg = 0; var sb = 0
        for (i in -r..r) { val p = at(i); sa += p ushr 24; sr += (p shr 16) and 0xFF; sg += (p shr 8) and 0xFF; sb += p and 0xFF }
        for (i in 0 until len) {
            val out = ((sa / div) shl 24) or ((sr / div) shl 16) or ((sg / div) shl 8) or (sb / div)
            if (horizontal) dst[line * w + i] = out else dst[i * w + line] = out
            val add = at(i + r + 1); val rem = at(i - r)
            sa += (add ushr 24) - (rem ushr 24)
            sr += ((add shr 16) and 0xFF) - ((rem shr 16) and 0xFF)
            sg += ((add shr 8) and 0xFF) - ((rem shr 8) and 0xFF)
            sb += (add and 0xFF) - (rem and 0xFF)
        }
    }
}

/** Arts are rendered at this fraction of their size before blurring: the upscale adds softness for free. */
private const val DOWN = 3

/** Rendered (blurred) arts by id and pixel size, shared by every card. Bounded by bytes. */
private object ArtBitmaps : android.util.LruCache<String, ImageBitmap>(24 * 1024 * 1024) {
    override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
}

private fun artBackground(art: CardArt, w: Float, h: Float): Brush {
    val stops = art.colors.map { Color(it) }
    return if (stops.size == 1) Brush.linearGradient(listOf(stops[0], stops[0]))
    else Brush.linearGradient(stops, Offset.Zero, if (art.horizontal) Offset(w, 0f) else Offset(w, h))
}

/** Blur radius as a share of the card width: enough to soften edges, not to smear the motif. */
private const val BLUR = 0.012f

private fun c(v: Long, a: Float = 1f) = Color(v).copy(alpha = a)

private fun DrawScope.drawMotif(art: CardArt, w: Float, h: Float) {
    val a = art.accent
    val a0 = a.getOrElse(0) { 0xFFFFFFFF }
    val a1 = a.getOrElse(1) { a0 }
    when (art.motif) {
        Motif.NONE -> Unit

        Motif.SHEEN -> {
            val p = Path().apply {
                moveTo(-0.06f * w, 0.74f * h)
                cubicTo(0.25f * w, 0.45f * h, 0.53f * w, 0.84f * h, 1.06f * w, 0.35f * h)
                lineTo(1.06f * w, 0.55f * h)
                cubicTo(0.56f * w, 0.99f * h, 0.28f * w, 0.6f * h, -0.06f * w, 0.94f * h)
                close()
            }
            drawPath(p, Brush.horizontalGradient(listOf(c(a0, 0f), c(a0, 0.35f), c(a0, 0f))))
            val p2 = Path().apply {
                moveTo(-0.06f * w, 0.35f * h)
                cubicTo(0.37f * w, 0.1f * h, 0.62f * w, 0.6f * h, 1.06f * w, 0.2f * h)
                lineTo(1.06f * w, 0.27f * h)
                cubicTo(0.62f * w, 0.7f * h, 0.34f * w, 0.2f * h, -0.06f * w, 0.45f * h)
                close()
            }
            drawPath(p2, Brush.horizontalGradient(listOf(c(a0, 0f), c(a0, 0.16f), c(a0, 0f))))
        }

        Motif.GLOW -> {
            val center = Offset(art.x * w, art.y * h)
            drawRect(Brush.radialGradient(listOf(c(a0, 0.85f), c(a1, 0.45f), c(a1, 0f)), center, 0.9f * w))
        }

        Motif.RAYS -> {
            listOf(Triple(0.44f, 0.72f, 0.22f), Triple(0.6f, 0.84f, 0.16f), Triple(0.72f, 0.94f, 0.12f)).forEach { (bot, top, alpha) ->
                val p = Path().apply {
                    moveTo(bot * w, h); lineTo(top * w, 0f); lineTo((top + 0.06f) * w, 0f); lineTo((bot + 0.09f) * w, h); close()
                }
                drawPath(p, c(a0, alpha))
            }
        }

        Motif.CHEVRON -> {
            val cx = 0.5f * w
            val p = Path().apply {
                moveTo(0.72f * w, 0.1f * h); lineTo(cx - 0.06f * w, 0.5f * h); lineTo(0.72f * w, 0.9f * h)
                lineTo(0.78f * w, 0.9f * h); lineTo(cx + 0.0f * w, 0.5f * h); lineTo(0.78f * w, 0.1f * h); close()
            }
            drawPath(p, c(a0))
        }

        Motif.ARROW -> {
            val p = Path().apply {
                moveTo(0.42f * w, 0.18f * h); lineTo(0.66f * w, 0.5f * h); lineTo(0.42f * w, 0.82f * h)
                lineTo(0.5f * w, 0.82f * h); lineTo(0.74f * w, 0.5f * h); lineTo(0.5f * w, 0.18f * h); close()
            }
            drawPath(p, c(a0, 0.9f))
        }

        Motif.PYRAMID -> {
            val p = Path().apply { moveTo(0.67f * w, 0.09f * h); lineTo(w, h); lineTo(0.34f * w, h); close() }
            drawPath(p, Brush.linearGradient(listOf(c(a0), c(a1)), Offset(0.5f * w, 0f), Offset(w, h)))
            val shade = Path().apply { moveTo(0.67f * w, 0.09f * h); lineTo(0.78f * w, h); lineTo(0.34f * w, h); close() }
            drawPath(shade, Color.Black.copy(alpha = 0.18f))
        }

        Motif.RIBBON -> {
            val cols = a.map { c(it, 0.9f) }.ifEmpty { listOf(Color.White) }
            val brush = if (cols.size == 1) Brush.horizontalGradient(listOf(cols[0], cols[0])) else Brush.linearGradient(cols, Offset(0.15f * w, h), Offset(0.85f * w, 0f))
            val p = Path().apply {
                moveTo(0.19f * w, 0.84f * h)
                cubicTo(0.12f * w, 0.45f * h, 0.38f * w, 0.15f * h, 0.66f * w, 0.25f * h)
                cubicTo(0.84f * w, 0.32f * h, 0.9f * w, 0.6f * h, 0.78f * w, 0.79f * h)
                cubicTo(0.69f * w, 0.6f * h, 0.5f * w, 0.45f * h, 0.34f * w, 0.6f * h)
                cubicTo(0.27f * w, 0.67f * h, 0.22f * w, 0.77f * h, 0.19f * w, 0.84f * h)
                close()
            }
            drawPath(p, brush)
        }

        Motif.STREAK -> {
            val beam = Brush.horizontalGradient(listOf(c(a0, 0f), c(a0, 0.8f), c(a0, 0.1f)))
            drawPath(Path().apply { moveTo(0f, 0.35f * h); lineTo(w, 0.05f * h); lineTo(w, 0.11f * h); lineTo(0f, 0.39f * h); close() }, beam)
            drawPath(Path().apply { moveTo(0f, 0.42f * h); lineTo(w, 0.17f * h); lineTo(w, 0.19f * h); lineTo(0f, 0.43f * h); close() }, beam, alpha = 0.5f)
        }

        Motif.ORB, Motif.GLOBE -> {
            val center = Offset(art.x * w, art.y * h)
            val r = 0.4f * h
            drawCircle(Brush.radialGradient(listOf(c(a0), c(a1)), Offset(center.x - r * 0.3f, center.y - r * 0.3f), r * 1.4f), r, center)
            if (art.motif == Motif.GLOBE) {
                val step = r / 8f
                var yy = center.y - r
                while (yy < center.y + r) {
                    var xx = center.x - r
                    while (xx < center.x + r) {
                        if ((xx - center.x) * (xx - center.x) + (yy - center.y) * (yy - center.y) < r * r) {
                            drawCircle(Color.Black.copy(alpha = 0.35f), step * 0.22f, Offset(xx, yy))
                        }
                        xx += step
                    }
                    yy += step
                }
            }
        }

        Motif.DOTS -> {
            val step = w / 40f
            val cx = 0.62f * w; val cy = 0.5f * h; val rx = 0.47f * w; val ry = 0.4f * h
            var yy = step / 2
            while (yy < h) {
                var xx = step / 2
                while (xx < w) {
                    val dx = (xx - cx) / rx; val dy = (yy - cy) / ry
                    if (dx * dx + dy * dy < 1f) drawCircle(c(a0, 0.14f), step * 0.13f, Offset(xx, yy))
                    xx += step
                }
                yy += step
            }
        }

        Motif.FACETS -> {
            val pts = listOf(
                0f to 0f, 0.28f to 0.3f, 0f to 0.6f, 0.56f to 0f, 0.62f to 0.45f, 1f to 0.15f, 1f to 0.7f,
                0.38f to 0.8f, 0.19f to 1f, 0f to 1f, 0.81f to 1f, 1f to 1f, 1f to 0f, 0.36f to 0.22f,
            ).map { Offset(it.first * w, it.second * h) }
            val tris = listOf(
                intArrayOf(0, 1, 2), intArrayOf(1, 3, 4), intArrayOf(2, 1, 7), intArrayOf(4, 5, 6), intArrayOf(7, 4, 10),
                intArrayOf(3, 12, 5), intArrayOf(2, 7, 8), intArrayOf(4, 6, 10), intArrayOf(1, 4, 7), intArrayOf(0, 3, 13),
                intArrayOf(8, 9, 2), intArrayOf(10, 6, 11),
            )
            val shades = listOf(0.12f, -0.08f, 0.06f, 0.16f, -0.12f, 0.08f, -0.05f, -0.1f, 0.1f, 0.04f, 0.02f, -0.06f)
            tris.forEachIndexed { i, t ->
                val p = Path().apply { moveTo(pts[t[0]].x, pts[t[0]].y); lineTo(pts[t[1]].x, pts[t[1]].y); lineTo(pts[t[2]].x, pts[t[2]].y); close() }
                val s = shades[i]
                drawPath(p, if (s > 0) Color.White.copy(alpha = s) else Color.Black.copy(alpha = -s))
            }
        }

        Motif.PIXELS -> {
            val cell = 0.045f * w
            val gap = 0.011f * w
            val brush = Brush.linearGradient(listOf(c(a0), c(a1)), Offset(0f, 0f), Offset(0.45f * w, h))
            val rnd = java.util.Random(art.id.hashCode().toLong())
            for (row in 0 until 7) for (col in 0 until 6) {
                if (rnd.nextFloat() < 0.5f) {
                    val x = 0.075f * w + col * (cell + gap)
                    val y = 0.2f * h + row * (cell + gap)
                    drawRect(brush, Offset(x, y), Size(cell, cell))
                }
            }
        }

        Motif.SWIRL -> {
            val p = Path().apply {
                moveTo(0.12f * w, 0.74f * h)
                cubicTo(0.28f * w, 0.3f * h, 0.53f * w, 0.2f * h, 0.81f * w, 0.3f * h)
                cubicTo(0.66f * w, 0.35f * h, 0.5f * w, 0.54f * h, 0.44f * w, 0.84f * h)
                cubicTo(0.37f * w, 0.69f * h, 0.25f * w, 0.69f * h, 0.12f * w, 0.74f * h)
                close()
            }
            drawPath(p, Brush.linearGradient(listOf(c(a0, 0.95f), c(a1, 0.9f)), Offset(0.1f * w, 0f), Offset(0.8f * w, h)))
            val s = Path().apply { moveTo(0.22f * w, 0.79f * h); cubicTo(0.38f * w, 0.5f * h, 0.56f * w, 0.4f * h, 0.75f * w, 0.42f * h) }
            drawPath(s, c(a0, 0.35f), style = Stroke(0.006f * w, cap = StrokeCap.Round))
        }

        Motif.BAND -> {
            drawRect(Brush.horizontalGradient(listOf(Color.Transparent, c(a0, 0.0f), c(a0, 0.9f), c(a1)), 0.45f * w, w))
            val p = Path().apply { moveTo(0.69f * w, 0f); lineTo(0.81f * w, 0f); lineTo(0.62f * w, h); lineTo(0.53f * w, h); close() }
            drawPath(p, c(a1, 0.18f))
        }

        Motif.LINES -> {
            val step = w / 22f
            rotate(35f, Offset(w / 2, h / 2)) {
                var x = -w
                while (x < 2 * w) {
                    drawLine(c(a0, 0.12f), Offset(x, -h), Offset(x, 2 * h), strokeWidth = step * 0.14f)
                    x += step
                }
            }
        }

        Motif.BRUSHED -> {
            val rnd = java.util.Random(art.id.hashCode().toLong())
            var y = 0f
            while (y < h) {
                drawLine(c(a0, 0.03f + rnd.nextFloat() * 0.05f), Offset(0f, y), Offset(w, y), strokeWidth = h / 180f)
                y += h / 70f
            }
            drawRect(Brush.linearGradient(listOf(c(a0, 0f), c(a0, 0.1f), c(a0, 0f)), Offset(0.2f * w, 0f), Offset(0.8f * w, h)))
        }

        Motif.RIPPLES -> {
            val center = Offset(art.x * w, art.y * h)
            for (i in 1..9) {
                val r = i * 0.075f * w
                drawCircle(c(a0, 0.55f - i * 0.05f), r, center, style = Stroke(0.008f * w))
            }
        }

        Motif.RINGS -> {
            drawCircle(c(a0, 0.85f), 0.2f * h * 1.5f, Offset(0.62f * w, 0.55f * h), style = Stroke(0.09f * h))
            drawCircle(c(a0, 0.7f), 0.16f * h * 1.5f, Offset(0.86f * w, 0.42f * h), style = Stroke(0.08f * h))
        }

        Motif.SPARKLE -> {
            val rnd = java.util.Random(art.id.hashCode().toLong())
            repeat(140) {
                val x = rnd.nextFloat() * w
                val y = rnd.nextFloat() * h
                val bias = x / w // denser and brighter toward the right
                if (rnd.nextFloat() < 0.35f + bias * 0.6f) drawCircle(c(a0, 0.25f + bias * 0.6f), w * (0.002f + rnd.nextFloat() * 0.004f), Offset(x, y))
            }
        }

        Motif.MOSAIC -> {
            val cols = a.ifEmpty { listOf(0xFFFFFFFF) }
            val rnd = java.util.Random(art.id.hashCode().toLong())
            val cell = 0.11f * w
            var i = 0
            var y = 0.08f * h
            while (y < h) {
                var x = 0.52f * w
                while (x < w) {
                    if (rnd.nextFloat() < 0.6f) drawRoundRectSafe(c(cols[i++ % cols.size], 0.55f), Offset(x, y), Size(cell * 0.86f, cell * 0.86f), cell * 0.18f)
                    x += cell
                }
                y += cell
            }
        }

        Motif.SPLIT -> {
            drawPath(Path().apply { moveTo(0f, h); lineTo(w, 0.2f * h); lineTo(w, h); close() }, c(a0, 0.25f))
        }

        Motif.ARC -> {
            val p = Path().apply {
                moveTo(0f, 0f); lineTo(0.41f * w, 0f)
                cubicTo(0.28f * w, 0.35f * h, 0.34f * w, 0.69f * h, 0.53f * w, h)
                lineTo(0f, h); close()
            }
            drawPath(p, Brush.linearGradient(listOf(c(a0, 0.9f), c(a1, 0.9f)), Offset.Zero, Offset(0.5f * w, h)))
        }

        Motif.CROSS -> {
            val sw = 0.075f * w
            drawLine(c(a0), Offset(0.58f * w, 0.15f * h), Offset(0.88f * w, 0.85f * h), sw, StrokeCap.Round)
            drawLine(c(a1), Offset(0.88f * w, 0.15f * h), Offset(0.58f * w, 0.85f * h), sw, StrokeCap.Round)
        }

        Motif.WAVES -> {
            for (i in 0 until 14) {
                val off = i * 0.018f * h
                val p = Path().apply {
                    moveTo(0f, 0.7f * h + off)
                    cubicTo(0.3f * w, 0.2f * h + off, 0.55f * w, 0.95f * h - off, w, 0.35f * h + off)
                }
                drawPath(p, c(a0, 0.5f - i * 0.03f), style = Stroke(0.004f * w))
            }
        }
    }
}

private fun DrawScope.drawRoundRectSafe(color: Color, topLeft: Offset, size: Size, radius: Float) =
    drawRoundRect(color, topLeft, size, androidx.compose.ui.geometry.CornerRadius(radius, radius))
