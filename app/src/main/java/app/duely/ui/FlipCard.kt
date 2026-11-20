package app.duely.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.duely.domain.CardNumbers
import app.duely.domain.CardWithDue
import app.duely.security.SecretDraft

/** Which secret fields exist in the vault (so the back can say "Not saved" without decrypting). */
data class StoredFields(val number: Boolean, val expiry: Boolean, val cvv: Boolean, val holder: Boolean)

/**
 * The card face that turns over to show the decrypted details while [revealed] is set.
 * [progress] (1 → 0) draws the auto-hide countdown along the bottom edge of the back.
 */
@Composable
fun FlipCard(
    cw: CardWithDue,
    revealed: SecretDraft?,
    stored: StoredFields,
    progress: Float,
    onCopy: (label: String, value: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Keep the last values while flipping back, so the back doesn't blank out mid-turn.
    var shown by remember { mutableStateOf<SecretDraft?>(null) }
    if (revealed != null) shown = revealed
    val angle by animateFloatAsState(
        if (revealed != null) 180f else 0f, tween(600, easing = FastOutSlowInEasing), label = "flip",
    )
    Box(
        modifier.fillMaxWidth().graphicsLayer {
            rotationY = angle
            cameraDistance = 16f * density
        },
    ) {
        if (angle <= 90f) CardFace(cw)
        else Box(Modifier.graphicsLayer { rotationY = 180f }) { CardBack(cw, shown, stored, progress, onCopy) }
    }
}

@Composable
private fun CardBack(cw: CardWithDue, s: SecretDraft?, stored: StoredFields, progress: Float, onCopy: (String, String) -> Unit) {
    val c = cw.card
    // Same ink as the front: dark on light bank colours.
    val ink = remember(c.color, c.art, c.issuer, c.nickname) { cardInkFor(c) }
    Box(
        Modifier.fillMaxWidth().aspectRatio(CARD_RATIO)
            .shadow(10.dp, CardShape).clip(CardShape)
            .border(1.dp, Color.White.copy(alpha = 0.10f), CardShape),
    ) {
      CardSurface(c, Modifier.matchParentSize())
      CappedFontScale {
        Column(Modifier.fillMaxSize()) {
            // Magnetic stripe, doubling as the hint line.
            Box(
                Modifier.padding(top = 14.dp).fillMaxWidth().height(30.dp).background(Color(0xE6101114)),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Text("Tap a detail to copy", color = Color.White.copy(alpha = 0.55f), fontSize = 11.sp, modifier = Modifier.padding(end = 16.dp))
            }
            Column(
                Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.SpaceEvenly,
            ) {
                Field("Card number", stored.number, s?.number?.let(CardNumbers::format), copyValue = s?.number, big = true, ink = ink, onCopy = onCopy, modifier = Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth()) {
                    Field("Expiry", stored.expiry, s?.expiry, s?.expiry, ink = ink, onCopy = onCopy, modifier = Modifier.weight(1f))
                    Field("CVV", stored.cvv, s?.cvv, s?.cvv, ink = ink, onCopy = onCopy, modifier = Modifier.weight(1f))
                    Spacer(Modifier.weight(0.6f))
                }
                Field("Name on card", stored.holder, s?.holder, s?.holder, ink = ink, onCopy = onCopy, modifier = Modifier.fillMaxWidth())
            }
        }
      }
        // Auto-hide countdown along the bottom edge.
        Box(
            Modifier.align(Alignment.BottomStart).fillMaxWidth(progress.coerceIn(0f, 1f)).height(3.dp)
                .background(ink.copy(alpha = 0.55f)),
        )
    }
}

@Composable
private fun Field(
    label: String, stored: Boolean, value: String?, copyValue: String?,
    big: Boolean = false, ink: Color = Color.White, onCopy: (String, String) -> Unit, modifier: Modifier = Modifier,
) {
    val canCopy = stored && !copyValue.isNullOrEmpty()
    Column(
        modifier.clip(RoundedCornerShape(10.dp))
            .clickable(enabled = canCopy, role = Role.Button, onClickLabel = "Copy $label") { onCopy(label, copyValue!!.replace(" ", "")) }
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(label.uppercase(), color = ink.copy(alpha = 0.66f), fontSize = 10.sp, letterSpacing = 1.sp)
        Text(
            if (!stored) "Not saved" else value ?: "••••",
            color = if (stored) ink else ink.copy(alpha = 0.5f),
            style = TextStyle(fontFamily = Inter, 
                fontSize = if (big) 20.sp else 16.sp,
                fontWeight = if (stored) FontWeight.Medium else FontWeight.Normal,
                letterSpacing = if (big) 1.5.sp else 0.5.sp,
            ).tabular,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}
