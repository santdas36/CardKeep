package app.duely.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Read-only text field that opens a dropdown of [options]. */
@Composable
fun <T> PickerField(
    label: String,
    valueText: String,
    options: List<T>,
    optionText: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    leading: (@Composable (T) -> Unit)? = null,
    leadingSelected: (@Composable () -> Unit)? = null,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedTextField(
            value = valueText,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(label, maxLines = 1) },
            placeholder = placeholder?.let { { Text(it) } },
            leadingIcon = leadingSelected,
            trailingIcon = { Icon(Icons.Filled.ArrowDropDown, null) },
            modifier = Modifier.fillMaxWidth(),
        )
        Box(
            Modifier.matchParentSize().padding(top = 8.dp).clip(RoundedCornerShape(4.dp))
                .clickable(role = Role.DropdownList, onClickLabel = "Choose $label") { open = true },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.heightIn(max = 360.dp)) {
            options.forEach { o ->
                DropdownMenuItem(
                    text = { Text(optionText(o)) },
                    leadingIcon = leading?.let { l -> { l(o) } },
                    onClick = { onSelect(o); open = false },
                )
            }
        }
    }
}

fun ordinal(n: Int): String {
    val suffix = if (n % 100 in 11..13) "th" else when (n % 10) { 1 -> "st"; 2 -> "nd"; 3 -> "rd"; else -> "th" }
    return "$n$suffix"
}

/** One wording for reminder leads everywhere: "On due day", "1 day before", "3 days before". */
fun reminderLeadLabel(days: Int): String = when (days) {
    0 -> "On due day"
    1 -> "1 day before"
    else -> "$days days before"
}

/** Compact form for a summary of several leads: "On due day, 3 days before". */
fun reminderLeadsSummary(leads: List<Int>): String =
    if (leads.isEmpty()) "Off"
    else leads.mapIndexed { i, d -> reminderLeadLabel(d).let { if (i == 0) it else it.replaceFirstChar(Char::lowercase) } }.joinToString(", ")

/** Read-only field showing a day of month; tapping opens a 1–31 grid. */
@Composable
fun DayPickerField(label: String, day: Int?, onPick: (Int) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedTextField(
            value = day?.let { "${ordinal(it)} of month" } ?: "",
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(label) },
            placeholder = { Text("Pick a day") },
            trailingIcon = { Icon(Icons.Filled.ArrowDropDown, null) },
            modifier = Modifier.fillMaxWidth(),
        )
        Box(
            Modifier.matchParentSize().padding(top = 8.dp).clip(RoundedCornerShape(4.dp))
                .clickable(role = Role.Button, onClickLabel = "Choose $label") { open = true },
        )
    }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(label) },
            text = {
                Column {
                    (1..31).chunked(7).forEach { week ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            week.forEach { d ->
                                val sel = d == day
                                Box(
                                    Modifier.size(38.dp).clip(CircleShape)
                                        .background(if (sel) MaterialTheme.colorScheme.primary else Color.Transparent)
                                        .clickable(role = Role.Button) { onPick(d); open = false }
                                        .semantics { contentDescription = ordinal(d) },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        "$d",
                                        color = if (sel) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                                        style = MaterialTheme.typography.bodyLarge.tabular,
                                    )
                                }
                            }
                            repeat(7 - week.size) { Spacer(Modifier.size(38.dp)) }
                        }
                        Spacer(Modifier.height(4.dp))
                    }
                    Text(
                        "29–31 fall back to the last day in shorter months.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            },
            confirmButton = { TextButton(onClick = { open = false }) { Text("Cancel") } },
        )
    }
}

/** Bundled logo for an issuer, or null when none is available (monogram fallback). */
@androidx.annotation.DrawableRes
fun bankLogo(issuer: String): Int? = when (issuer) {
    "HDFC Bank" -> app.duely.R.drawable.sym_hdfc
    "SBI Card" -> app.duely.R.drawable.sym_sbi
    "ICICI Bank" -> app.duely.R.drawable.sym_icici
    "Axis Bank" -> app.duely.R.drawable.sym_axis
    "IDFC FIRST" -> app.duely.R.drawable.sym_idfc
    "AU Bank" -> app.duely.R.drawable.sym_au
    "RBL Bank" -> app.duely.R.drawable.sym_rbl
    "Amex" -> app.duely.R.drawable.net_amex
    "HSBC" -> app.duely.R.drawable.sym_hsbc
    "Standard Chartered" -> app.duely.R.drawable.sym_sc
    "BOB Card" -> app.duely.R.drawable.sym_bob
    "State Bank of India" -> app.duely.R.drawable.sym_sbi
    "Yes Bank" -> app.duely.R.drawable.sym_yes
    "Indian Bank" -> app.duely.R.drawable.sym_indianbank
    "Union Bank of India" -> app.duely.R.drawable.sym_union
    else -> null // wordmark-only brands (Yes, IndusInd, Federal, Slice, Kotak, ...) use a monogram
}

/** Symbols that carry their own coloured box: drawn edge-to-edge so no white ring shows. */
private val FULL_BLEED = setOf("IDFC FIRST", "AU Bank", "BOB Card", "Amex")

@androidx.annotation.DrawableRes
fun networkLogo(network: String): Int? = when (network) {
    "Visa" -> app.duely.R.drawable.net_visa
    "Mastercard" -> app.duely.R.drawable.net_mastercard
    "RuPay" -> app.duely.R.drawable.net_rupay
    "Amex" -> app.duely.R.drawable.net_amex
    "Diners" -> app.duely.R.drawable.net_diners
    else -> null
}

/**
 * Draws a pre-trimmed logo with similar visual weight across shapes: square marks fill [height],
 * wide wordmarks shrink by sqrt(1.7 / aspect) so their area stays comparable, and width is capped.
 */
@Composable
private fun BalancedLogo(
    @androidx.annotation.DrawableRes res: Int, height: androidx.compose.ui.unit.Dp,
    maxWidth: androidx.compose.ui.unit.Dp, desc: String, tint: Color? = null,
) {
    val painter = androidx.compose.ui.res.painterResource(res)
    val ratio = painter.intrinsicSize.let { if (it.height > 0f) it.width / it.height else 1f }
    var h = height * kotlin.math.min(1f, kotlin.math.sqrt(1.7f / ratio))
    if (h * ratio > maxWidth) h = maxWidth / ratio
    androidx.compose.foundation.Image(
        painter, desc,
        Modifier.size(width = h * ratio, height = h),
        contentScale = androidx.compose.ui.layout.ContentScale.Fit,
        colorFilter = tint?.let { androidx.compose.ui.graphics.ColorFilter.tint(it) },
    )
}

@Composable
private fun FixedLogo(@androidx.annotation.DrawableRes res: Int, height: androidx.compose.ui.unit.Dp, desc: String, tint: Color? = null) {
    val painter = androidx.compose.ui.res.painterResource(res)
    val ratio = painter.intrinsicSize.let { if (it.height > 0f) it.width / it.height else 1f }
    androidx.compose.foundation.Image(
        painter, desc, Modifier.size(width = height * ratio, height = height),
        contentScale = androidx.compose.ui.layout.ContentScale.Fit,
        colorFilter = tint?.let { androidx.compose.ui.graphics.ColorFilter.tint(it) },
    )
}

/** Issuer symbol on a square white tile; issuers without a bundled symbol get a coloured monogram. */
@Composable
fun BankBadge(issuer: String, modifier: Modifier = Modifier, size: androidx.compose.ui.unit.Dp = 36.dp) {
    val logo = bankLogo(issuer)
    if (logo != null) {
        val bleed = issuer in FULL_BLEED
        Box(
            modifier.size(size).clip(RoundedCornerShape(size * 0.28f)).background(Color.White),
            contentAlignment = Alignment.Center,
        ) {
            if (bleed) {
                androidx.compose.foundation.Image(
                    androidx.compose.ui.res.painterResource(logo), issuer, Modifier.matchParentSize(),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                )
            } else {
                androidx.compose.foundation.Image(
                    androidx.compose.ui.res.painterResource(logo), issuer, Modifier.size(size * 0.70f),
                    contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                )
            }
        }
        return
    }
    val code = app.duely.domain.Issuers.short(issuer)
    val color = Color(app.duely.domain.Issuers.byName(issuer).color)
    Box(
        modifier.size(size).clip(RoundedCornerShape(size * 0.28f)).background(Color.White)
            .semantics { contentDescription = issuer },
        contentAlignment = Alignment.Center,
    ) {
        val fs = when (code.length) { 1, 2 -> 0.42f; 3 -> 0.34f; 4 -> 0.27f; else -> 0.22f }
        Text(
            code, color = color, fontWeight = FontWeight.Black, letterSpacing = (-0.3).sp, maxLines = 1,
            fontSize = (size.value * fs).sp,
        )
    }
}

/**
 * Network logo. [onDark] (card faces) shows wordmarks as white silhouettes like printed cards;
 * Mastercard and Amex keep their colours, and Diners (which has white inside) sits on a small chip.
 */
@Composable
fun NetworkMark(network: String, modifier: Modifier = Modifier, onDark: Boolean = false, cardInk: Color = Color.White) {
    // A light card (dark ink) is a light background: brand-coloured wordmarks read fine there.
    val lightCard = onDark && cardInk != Color.White
    val logo = networkLogo(network)
    if (logo != null) {
        // On cards and in dark theme, dark wordmarks become white; in forms (light) they keep brand colours.
        val darkBg = (onDark && !lightCard) || (!onDark && MaterialTheme.colorScheme.surface.luminance() < 0.5f)
        val scale = if (onDark) 1f else 0.8f
        // Heights tuned by eye so heavy VISA lettering and the Mastercard circles read the same size.
        val h = when (network) { "Visa" -> 17.dp; "RuPay" -> 19.dp; "Diners" -> 18.dp; else -> 26.dp } * scale
        when {
            darkBg && network == "Diners" -> Box(
                modifier.clip(RoundedCornerShape(6.dp)).background(Color.White).padding(horizontal = 6.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center,
            ) { FixedLogo(logo, h, network) }
            else -> Box(modifier, contentAlignment = Alignment.Center) {
                val keep = !darkBg || network == "Mastercard" || network == "Amex"
                FixedLogo(logo, h, network, tint = if (keep) null else Color.White)
            }
        }
        return
    }
    val ink = if (onDark) cardInk else Color(0xFF1A1F71)
    Box(modifier.semantics { contentDescription = network }, contentAlignment = Alignment.Center) {
        when (network) {
            "Mastercard" -> Canvas(Modifier.size(width = 34.dp, height = 21.dp)) {
                val r = size.height / 2
                val left = Offset(r, r)
                val right = Offset(size.width - r, r)
                drawCircle(Color(0xFFEB001B), r, left)
                drawCircle(Color(0xFFF79E1B), r, right)
                val lens = Path().apply { addOval(Rect(left, r)) }
                clipPath(lens) { drawCircle(Color(0xFFFF5F00), r, right) }
            }
            "Visa" -> Text(
                "VISA", color = ink, fontSize = 17.sp, fontWeight = FontWeight.Black,
                fontStyle = FontStyle.Italic, letterSpacing = 0.5.sp,
            )
            "RuPay" -> Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = ink)) { append("Ru") }
                    withStyle(SpanStyle(color = if (onDark) Color(0xFFFFB066) else Color(0xFFF58220))) { append("Pay") }
                    withStyle(SpanStyle(color = Color(0xFF2E9E4F))) { append(" ▸") }
                },
                fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, fontStyle = FontStyle.Italic,
            )
            "Amex" -> Text(
                "AMEX", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp,
                modifier = Modifier.clip(RoundedCornerShape(3.dp)).background(Color(0xFF2E77BB))
                    .padding(horizontal = 6.dp, vertical = 3.dp),
            )
            "Diners" -> Text("Diners Club", color = ink, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            else -> Text(network, color = ink, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
    }
}
