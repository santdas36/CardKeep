package app.duely.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.CacheDrawScope
import androidx.compose.ui.draw.DrawResult
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.duely.domain.CardWithDue
import app.duely.data.CardKind
import app.duely.data.hasDues
import app.duely.data.isCredit
import app.duely.domain.Dates
import app.duely.domain.Money

const val CARD_RATIO = 1.586f
val CardShape = RoundedCornerShape(18.dp)

private const val VIBRANCE = 0.30f
private const val SOFTNESS = 0.70f

fun cardBrush(color: Long): Brush {
    val base = Color(color)
    return Brush.linearGradient(listOf(lerp(base, Color.White, 0.12f), base, lerp(base, Color.Black, 0.45f)))
}

/**
 * Card background: the bank colour, a little more saturated, with four soft colour blobs (a light tint,
 * a warmer and a cooler shift, a gentle shadow). Their placement and hues are seeded by the card, so a
 * card always looks the same and two cards from one bank still look different. Built once per size.
 * Tuned to "vibrance 30, softness 70".
 */
fun Modifier.cardBackground(color: Long, seed: Long): Modifier = drawWithCache(CardBackground(color, seed))

private fun cardTone(hue: Float, sat: Float, light: Float) = Color(
    androidx.core.graphics.ColorUtils.HSLToColor(
        floatArrayOf(((hue % 360f) + 360f) % 360f, sat.coerceIn(0f, 1f), light.coerceIn(0f, 1f)),
    ),
)

private fun cardHsl(color: Long): FloatArray =
    FloatArray(3).also { androidx.core.graphics.ColorUtils.colorToHSL(color.toInt(), it) }

/** The flat colour under the blobs (what [cardBackground] fills with first). */
fun cardBase(color: Long): Color {
    val (h, s, l) = cardHsl(color)
    return cardTone(h, (s + VIBRANCE * 0.45f).coerceAtMost(1f), l + VIBRANCE * 0.04f)
}

/**
 * Text colour on a card: white on most bank colours, a dark ink on light ones (Barclays' sky blue, the
 * orange of Bank of Baroda / Bank of India), where white text is hard to read.
 */
fun cardInk(color: Long): Color = if (cardBase(color).luminance() > 0.22f) Color(0xFF15171C) else Color.White

/**
 * Builds the background for one colour and seed. A data class, so the modifier compares equal across
 * recompositions and drawWithCache keeps its brushes until the size (or colour/seed) changes.
 */
private data class CardBackground(val color: Long, val seed: Long) : (CacheDrawScope) -> DrawResult {
    override fun invoke(scope: CacheDrawScope): DrawResult = with(scope) { build() }

    private fun CacheDrawScope.build(): DrawResult {
    val rnd = java.util.Random(seed * 7919L + 13L)
    val (h, s, l) = cardHsl(color)
    fun tone(hue: Float, sat: Float, light: Float) = cardTone(hue, sat, light)
    val sat = (s + VIBRANCE * 0.45f).coerceAtMost(1f)
    val base = tone(h, sat, l + VIBRANCE * 0.04f)
    val tones = listOf(
        tone(h + 8f, sat, l + 0.22f),
        tone(h + 30f + rnd.nextFloat() * 25f, sat + 0.1f, l + 0.08f + VIBRANCE * 0.06f),
        tone(h - 30f - rnd.nextFloat() * 25f, sat + 0.1f, l + 0.05f),
        tone(h, sat * 0.9f, l - 0.12f),
    )
    val peak = 0.9f - SOFTNESS * 0.35f
    val mid = 0.4f - SOFTNESS * 0.1f
    val midStop = 0.45f + SOFTNESS * 0.15f
    val w = size.width
    val hgt = size.height
    val blobs = tones.map { t ->
        val center = Offset(rnd.nextFloat() * w, rnd.nextFloat() * hgt)
        val radius = (110f + rnd.nextFloat() * 90f) * (1f + SOFTNESS * 0.6f) / 320f * w
        Brush.radialGradient(
            0f to t.copy(alpha = peak), midStop to t.copy(alpha = mid), 1f to t.copy(alpha = 0f),
            center = center, radius = radius,
        ) to Pair(center, radius)
    }
    return onDrawBehind {
        drawRect(base)
        blobs.forEach { (brush, cr) -> drawCircle(brush, radius = cr.second, center = cr.first) }
    }
    }
}

data class BadgeStyle(val text: String, val bg: Color, val fg: Color)

// On the card itself: fixed, vivid, since they sit on the card colour rather than the app theme.
private val CardRed = Color(0xFFFF5A5F)
private val CardAmber = Color(0xFFF5A524)

fun dueBadge(cw: CardWithDue): BadgeStyle {
    val d = cw.due
    return when {
        d.overdue -> BadgeStyle(if (d.daysLeft == -1L) "Overdue 1d" else "Overdue ${-d.daysLeft}d", CardRed, Color.White)
        d.lastCyclePaid -> BadgeStyle("Paid", Color.White.copy(alpha = 0.18f), Color.White)
        d.daysLeft == 0L -> BadgeStyle("Due today", CardRed, Color.White)
        d.daysLeft <= 2 -> BadgeStyle("Due in ${d.daysLeft}d", CardRed, Color.White)
        d.daysLeft <= 5 -> BadgeStyle("Due in ${d.daysLeft}d", CardAmber, Color(0xFF231600))
        else -> BadgeStyle("Due in ${d.daysLeft}d", Color.White.copy(alpha = 0.18f), Color.White)
    }
}

@Composable
fun DueBadge(cw: CardWithDue, ink: Color = Color.White) {
    var s = dueBadge(cw)
    // The neutral (white-on-translucent) styles follow the card's ink on light cards.
    if (ink != Color.White && s.fg == Color.White && s.bg != CardRed) s = s.copy(bg = ink.copy(alpha = 0.12f), fg = ink)
    Text(
        s.text,
        color = s.fg,
        style = TextStyle(fontFamily = Inter, fontSize = 12.sp, fontWeight = FontWeight.SemiBold).tabular,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(s.bg).padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

/**
 * The card itself. The top [STRIP_HEIGHT] holds issuer, nickname, last four and the due badge, so
 * a card peeking out of the stack is still identifiable.
 */
@Composable
fun CardFace(cw: CardWithDue, modifier: Modifier = Modifier, ownShadow: Boolean = true) {
    val c = cw.card
    val onCard = remember(c.color, c.art, c.issuer, c.nickname) { cardInkFor(c) }
    val dim = onCard.copy(alpha = if (onCard == Color.White) 0.72f else 0.68f)
    Box(
        modifier
            .fillMaxWidth()
            .aspectRatio(CARD_RATIO)
            .then(if (ownShadow) Modifier.shadow(10.dp, CardShape) else Modifier)
            .clip(CardShape)
            .border(1.dp, Color.White.copy(alpha = 0.10f), CardShape)
            .semantics {
                contentDescription = "${c.issuer} ${c.nickname}, ending ${c.last4}, " +
                    if (c.hasDues) "due ${Dates.long(cw.due.dueDate)}, outstanding ${Money.inr(c.outstandingPaise)}"
                    else if (c.isCredit) "due date not set, outstanding ${Money.inr(c.outstandingPaise)}"
                    else "${CardKind.label(c.kind)} card"
            },
    ) {
      CardSurface(c, Modifier.matchParentSize())
      CappedFontScale {
        Column(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 14.dp)) {
            Row(Modifier.fillMaxWidth().height(STRIP_HEIGHT - 28.dp), verticalAlignment = Alignment.CenterVertically) {
                BankBadge(c.issuer, size = 34.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            c.nickname, color = onCard, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                        )
                        if (c.pinned) {
                            Spacer(Modifier.width(6.dp))
                            Icon(Icons.Filled.PushPin, "Pinned", tint = dim, modifier = Modifier.size(13.dp))
                        }
                    }
                    Text("${c.issuer}  ·  •• ${c.last4}", color = dim, style = TextStyle(fontFamily = Inter, fontSize = 12.sp).tabular, maxLines = 1)
                }
                if (c.hasDues) DueBadge(cw, onCard)
            }
            Spacer(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Chip()
                Spacer(Modifier.width(14.dp))
                Text(
                    "••••  ••••  ••••  ${c.last4}", color = onCard,
                    style = TextStyle(fontFamily = Inter, fontSize = 18.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.Medium).tabular,
                )
            }
            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.SpaceBetween) {
                if (c.isCredit && !c.billingSet) {
                    // No cycle yet: no due date to show, only a balance if one was entered.
                    if (c.outstandingPaise > 0) Column {
                        Text("OUTSTANDING", color = dim, fontSize = 10.sp, letterSpacing = 1.sp)
                        Text(Money.inr(c.outstandingPaise), color = onCard, style = TextStyle(fontFamily = Inter, fontSize = 20.sp, fontWeight = FontWeight.SemiBold).tabular)
                    }
                    Text("ADD DUE DATE", color = dim, fontSize = 11.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.Medium)
                } else if (c.isCredit) {
                    Column {
                        Text("OUTSTANDING", color = dim, fontSize = 10.sp, letterSpacing = 1.sp)
                        Text(Money.inr(c.outstandingPaise), color = onCard, style = TextStyle(fontFamily = Inter, fontSize = 20.sp, fontWeight = FontWeight.SemiBold).tabular)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(if (cw.due.lastCyclePaid) "NEXT DUE" else "PAY BY", color = dim, fontSize = 10.sp, letterSpacing = 1.sp)
                        Text(Dates.short(cw.due.dueDate), color = onCard, style = TextStyle(fontFamily = Inter, fontSize = 15.sp, fontWeight = FontWeight.Medium).tabular)
                    }
                } else {
                    Text(CardKind.label(c.kind).uppercase(), color = dim, fontSize = 12.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Medium)
                }
                if (!(c.issuer == "Amex" && c.network == "Amex")) NetworkMark(c.network, onDark = true, cardInk = onCard)
            }
            if (c.isCredit && c.limitPaise > 0) {
                Spacer(Modifier.height(10.dp))
                UtilBar(cw.utilization, Modifier.fillMaxWidth(), track = onCard.copy(alpha = 0.18f), low = onCard)
            }
        }
      }
    }
}

/**
 * Card text is laid out like a printed card, at a fixed aspect ratio: follow the system font size only up
 * to a point, so large font settings don't clip the rows.
 */
@Composable
fun CappedFontScale(content: @Composable () -> Unit) {
    val d = LocalDensity.current
    if (d.fontScale <= MAX_CARD_FONT_SCALE) content()
    else CompositionLocalProvider(LocalDensity provides Density(d.density, MAX_CARD_FONT_SCALE), content = content)
}

private const val MAX_CARD_FONT_SCALE = 1.15f

@Composable
fun UtilBar(fraction: Float, modifier: Modifier = Modifier, track: Color = Color.White.copy(alpha = 0.18f), low: Color = Color.White) {
    val fill = when {
        fraction >= 0.7f -> Danger
        fraction >= 0.3f -> Amber
        else -> low
    }
    Box(modifier.height(4.dp).clip(RoundedCornerShape(2.dp)).background(track)) {
        Box(Modifier.fillMaxWidth(fraction).height(4.dp).clip(RoundedCornerShape(2.dp)).background(fill))
    }
}

/** EMV chip, drawn. */
@Composable
private fun Chip() {
    Box(
        Modifier.size(width = 34.dp, height = 26.dp).clip(RoundedCornerShape(5.dp))
            .background(Brush.linearGradient(listOf(Color(0xFFE9D9A6), Color(0xFFC9A961), Color(0xFFE6D08F)))),
    ) {
        Box(Modifier.align(Alignment.Center).fillMaxWidth().height(1.dp).background(Color(0x55000000)))
        Box(Modifier.align(Alignment.Center).width(1.dp).fillMaxSize().padding(vertical = 5.dp).background(Color(0x55000000)))
    }
}

val STRIP_HEIGHT = 64.dp
