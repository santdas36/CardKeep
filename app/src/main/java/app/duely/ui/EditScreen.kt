package app.duely.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Notes
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.Contactless
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import app.duely.data.Card
import app.duely.data.CardKind
import app.duely.data.Repo
import app.duely.domain.Billing
import app.duely.domain.BinLookup
import app.duely.domain.CardNumbers
import app.duely.domain.Dates
import app.duely.domain.Issuers
import app.duely.domain.Money
import app.duely.security.Auth
import app.duely.security.SecretDraft
import app.duely.security.Vault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset

/** Fields near the top of the form: an error in one of these scrolls the form back up. */
private val TOP_FIELDS = setOf("number", "last4", "expiry", "cvv", "nickname")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditScreen(
    cardId: Long, repo: Repo, defaultLeads: String, deviceSecure: Boolean, onDone: () -> Unit,
    /** "scan" or "tap": open the camera or the NFC sheet straight away (from an app shortcut). */
    startWith: String? = null,
) {
    val activity = LocalContext.current as FragmentActivity
    val scope = rememberCoroutineScope()
    val scroll = rememberScrollState()
    val isNew = cardId == 0L

    var loaded by rememberSaveable { mutableStateOf(isNew) }
    var original by rememberSaveable { mutableStateOf<Long?>(null) } // createdAt of the edited card
    var pinned by rememberSaveable { mutableStateOf(false) }

    var nickname by rememberSaveable { mutableStateOf("") }
    // New cards start unassigned ("Other") until the card, its BIN or you say which bank it is.
    var issuer by rememberSaveable { mutableStateOf(Issuers.all.last().name) }
    var network by rememberSaveable { mutableStateOf("Visa") }
    var last4 by rememberSaveable { mutableStateOf("") }
    var limit by rememberSaveable { mutableStateOf("") }
    var outstanding by rememberSaveable { mutableStateOf("") }
    var statementDay by rememberSaveable { mutableStateOf("") }
    var dueOffset by rememberSaveable { mutableStateOf(Issuers.all.first().dueOffset.toString()) }
    // A new card starts without a cycle; it's set by picking the due date (or the exact fields under Advanced).
    var billingSet by rememberSaveable { mutableStateOf(false) }
    // The edited card's current due (from its statements and payments), shown until the cycle is changed.
    var loadedDue by rememberSaveable { mutableStateOf<Long?>(null) }
    var leads by rememberSaveable { mutableStateOf(defaultLeads) }
    var notes by rememberSaveable { mutableStateOf("") }
    var kind by rememberSaveable { mutableStateOf(CardKind.CREDIT) }
    var art by rememberSaveable { mutableStateOf<String?>(null) }

    // Secrets: never pre-filled. Blank keeps whatever is stored. Plain remember on purpose: a saveable
    // value would sit in plaintext in the saved-instance Bundle, outside the vault.
    var number by remember { mutableStateOf("") }
    var expiry by remember { mutableStateOf("") }
    var cvv by remember { mutableStateOf("") }
    var holder by remember { mutableStateOf("") }
    var stored by rememberSaveable { mutableStateOf(listOf(false, false, false, false)) }

    var networkManual by rememberSaveable { mutableStateOf(false) }
    var issuerManual by rememberSaveable { mutableStateOf(false) }
    // The bank name was read off the card itself (camera text or chip label): more reliable than the BIN table.
    var issuerFromCard by rememberSaveable { mutableStateOf(false) }
    var cardIssuer by remember { mutableStateOf<String?>(null) } // just read by Scan / Tap, applied below
    var kindManual by rememberSaveable { mutableStateOf(false) }
    var dueOffsetManual by rememberSaveable { mutableStateOf(false) }

    var openCycle by rememberSaveable { mutableStateOf(false) }
    var openReminders by rememberSaveable { mutableStateOf(false) }
    var openLimit by rememberSaveable { mutableStateOf(false) }
    var openNotes by rememberSaveable { mutableStateOf(false) }
    var showDesign by rememberSaveable { mutableStateOf(false) }
    var pickingDue by remember { mutableStateOf(false) }

    // Field errors show under their field; [error] is only for what no field owns (vault, screen lock).
    var errors by remember { mutableStateOf(mapOf<String, String>()) }
    fun clear(key: String) { if (key in errors) errors = errors - key }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    val canTap = nfcAvailable()
    var tapping by remember { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(false) }
    // The camera and the NFC sheet are windows above the lock cover, and must not read cards while
    // locked: keep them out of composition until unlocked (they come back if still wanted).
    val locked = LocalLocked.current

    // From a shortcut: start once, and only after unlock (this screen is opened behind the lock).
    var started by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(locked) {
        if (locked || started || startWith == null) return@LaunchedEffect
        started = true
        if (startWith == "scan") {
            scanning = true
        } else if (startWith == "tap") {
            // Static shortcuts can't be hidden on phones without NFC: say so and leave the form open.
            if (canTap) tapping = true
            else android.widget.Toast.makeText(activity, "This phone can't read cards by tap. Scan it or type the details.", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    if (tapping && !locked) TapCardSheet(
        onRead = { c ->
            tapping = false
            number = c.number
            last4 = c.number.takeLast(4)
            (c.network ?: CardNumbers.detectNetwork(c.number))?.let { if (!networkManual) network = it }
            c.expiry?.let { expiry = it }
            c.holder?.let { holder = it.uppercase() }
            c.issuer?.let { cardIssuer = it }
            errors = errors - "number" - "last4" - "expiry"
        },
        onDismiss = { tapping = false },
    )

    if (scanning && !locked) ScanCardScreen(
        onResult = { s ->
            scanning = false
            s.number?.let { n ->
                number = n
                last4 = n.takeLast(4)
                CardNumbers.detectNetwork(n)?.let { if (!networkManual) network = it }
            }
            s.expiry?.let { expiry = it }
            s.issuer?.let { cardIssuer = it }
            errors = errors - "number" - "last4" - "expiry"
        },
        onDismiss = { scanning = false },
    )

    LaunchedEffect(cardId) {
        if (loaded) return@LaunchedEffect
        val cw = repo.cardWithDue(cardId) ?: return@LaunchedEffect onDone()
        val c = cw.card
        nickname = c.nickname; issuer = c.issuer; network = c.network; last4 = c.last4
        limit = Money.toInput(c.limitPaise); outstanding = Money.toInput(c.outstandingPaise)
        statementDay = c.statementDay.toString(); dueOffset = c.dueOffsetDays.toString()
        billingSet = c.billingSet
        // A card with a real cycle keeps its offset when a new due date is picked; only the statement day moves.
        dueOffsetManual = c.billingSet
        loadedDue = cw.due.dueDate.toEpochDay().takeIf { c.billingSet }
        leads = c.reminderLeads; notes = c.notes; kind = c.kind; original = c.createdAt; pinned = c.pinned; art = c.art
        repo.secret(cardId)?.let { stored = listOf(it.numberEnc != null, it.expiryEnc != null, it.cvvEnc != null, it.holderEnc != null) }
        loaded = true
    }

    /** The next due date the cycle fields give, or null while they're incomplete. */
    fun cycleDue(): LocalDate? {
        val d = statementDay.toIntOrNull()?.takeIf { it in 1..31 } ?: return null
        val o = dueOffset.toIntOrNull()?.takeIf { it in 0..60 } ?: return null
        return nextDue(d, o)
    }

    // From the picked due date: offset = the bank's usual one (unless set by hand), statement day = due - offset.
    fun pickDue(due: LocalDate) {
        val off = dueOffset.toIntOrNull()?.takeIf { dueOffsetManual && it in 0..60 } ?: Issuers.byName(issuer).dueOffset
        dueOffset = off.toString()
        statementDay = due.minusDays(off.toLong()).dayOfMonth.toString()
        billingSet = true; loadedDue = null
        errors = errors - "statementDay" - "dueOffset"
    }

    // New cards follow the bank's usual offset; a due date already picked stays put and the statement day moves.
    fun changeIssuer(name: String) {
        if (issuer == name) return
        val keepDue = if (billingSet) cycleDue() else null
        issuer = name
        if (isNew && !dueOffsetManual) {
            val off = Issuers.byName(name).dueOffset
            dueOffset = off.toString()
            keepDue?.let { statementDay = it.minusDays(off.toLong()).dayOfMonth.toString() }
        }
    }

    LaunchedEffect(cardIssuer) {
        val name = cardIssuer ?: return@LaunchedEffect
        if (!issuerManual) { changeIssuer(name); issuerFromCard = true }
        cardIssuer = null
    }

    val digits = number.filter(Char::isDigit)
    // New cards: the first six digits pre-fill bank and type from the bundled offline BIN table,
    // unless you've already picked them yourself.
    val appContext = LocalContext.current.applicationContext
    LaunchedEffect(digits.take(6)) {
        if (!isNew || digits.length < 6) return@LaunchedEffect
        val info = withContext(Dispatchers.IO) { BinLookup.find(appContext, digits) } ?: return@LaunchedEffect
        info.issuer?.let { name -> Issuers.all.firstOrNull { it.name == name } }?.let { iss ->
            if (!issuerManual && !issuerFromCard) changeIssuer(iss.name)
        }
        info.kind?.let { if (!kindManual) kind = it }
    }
    val numberWarning = when {
        digits.isEmpty() -> null
        digits.length < 12 -> "Too short"
        !CardNumbers.luhnValid(digits) -> "Doesn't look like a valid card number"
        else -> null
    }

    fun save() {
        error = null
        val l4 = if (digits.length >= 12) digits.takeLast(4) else last4.trim()
        val credit = kind == CardKind.CREDIT
        // Only a credit card with a due date has a real cycle to check. Otherwise the fields are hidden or
        // placeholders: keep a sane stored value (or the default) without asking.
        val cycle = credit && billingSet
        val day = statementDay.trim().toIntOrNull().let { if (cycle) it else it?.takeIf { d -> d in 1..31 } ?: 1 }
        val offset = dueOffset.trim().toIntOrNull().let { if (cycle) it else it?.takeIf { o -> o in 0..60 } ?: Issuers.byName(issuer).dueOffset }
        // Limit and balance are optional: blank parses as 0 (unknown). Debit and prepaid never keep a stale outstanding.
        val limitP = Money.parse(limit).let { if (credit) it else it ?: 0L }
        val outP = if (credit) Money.parse(outstanding) else 0L
        errors = buildMap {
            if (nickname.isBlank()) put("nickname", "Give the card a nickname")
            if (digits.isNotEmpty() && numberWarning != null)
                put("number", "Check the card number" + if (stored[0]) ", or clear it to keep the stored one" else ", or clear it")
            if (!Regex("\\d{4}").matches(l4)) put("last4", "Enter the last four digits")
            if (expiry.isNotBlank() && !Regex("(0[1-9]|1[0-2])/\\d{2}").matches(expiry.trim())) put("expiry", "Use MM/YY")
            if (cvv.isNotBlank() && !Regex("\\d{3,4}").matches(cvv.trim())) put("cvv", "3 or 4 digits")
            if (day == null || day !in 1..31) put("statementDay", "Pick the statement day, or the due date above")
            if (offset == null || offset !in 0..60) put("dueOffset", "0 to 60 days")
            if (limitP == null) put("limit", "Not a valid amount")
            if (outP == null) put("outstanding", "Not a valid amount")
        }
        if (errors.isNotEmpty()) {
            // Open the sections that hold an error, then bring the first one into view.
            val opened = (("statementDay" in errors || "dueOffset" in errors) && !openCycle) ||
                (("limit" in errors || "outstanding" in errors) && !openLimit)
            if ("statementDay" in errors || "dueOffset" in errors) openCycle = true
            if ("limit" in errors || "outstanding" in errors) openLimit = true
            val top = errors.keys.any { it in TOP_FIELDS }
            scope.launch {
                if (opened && !top) delay(300) // let the section finish expanding
                scroll.animateScrollTo(if (top) 0 else scroll.maxValue)
            }
            return
        }

        val card = Card(
            id = cardId,
            nickname = nickname.trim(),
            issuer = issuer,
            network = network,
            last4 = l4,
            limitPaise = limitP!!,
            outstandingPaise = outP!!,
            statementDay = day!!,
            dueOffsetDays = offset!!,
            color = Issuers.byName(issuer).color,
            pinned = pinned,
            reminderLeads = Billing.parseLeads(leads).joinToString(","),
            notes = notes.trim(),
            createdAt = original ?: System.currentTimeMillis(),
            kind = kind,
            art = art,
            billingSet = billingSet,
        )
        val draft = SecretDraft(digits, expiry.trim(), cvv.trim(), holder.trim())

        scope.launch {
            saving = true
            try {
                val enc = if (draft.isEmpty) null else {
                    if (!deviceSecure) {
                        error = "Set a screen lock on this phone before storing card numbers."
                        scroll.animateScrollTo(0)
                        return@launch
                    }
                    Auth.withVault(activity, "Save card details") { withContext(Dispatchers.IO) { Vault.encrypt(draft) } }
                        ?: return@launch
                }
                repo.save(card, enc)
                onDone()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                error = if (e is Vault.KeyLost) e.message
                else "Couldn't secure card details (${e.javaClass.simpleName}). Nothing was saved."
                scroll.animateScrollTo(0)
            } finally {
                saving = false
            }
        }
    }

    // Unsaved-changes guard: compare the visible fields with what was loaded (or the blank form).
    fun snapshot() = listOf(
        nickname, issuer, network, last4, limit, outstanding, statementDay, dueOffset, billingSet, leads, notes, kind, art.orEmpty(),
    ).joinToString("\u0000")
    var baseline by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(loaded) { if (loaded && baseline == null) baseline = snapshot() }
    val dirty = loaded && (
        (baseline != null && snapshot() != baseline) ||
            number.isNotEmpty() || expiry.isNotEmpty() || cvv.isNotEmpty() || holder.isNotEmpty()
        )
    fun close() { if (dirty && !saving) confirmDiscard = true else onDone() }
    BackHandler(enabled = dirty && !saving) { confirmDiscard = true }
    if (confirmDiscard) {
        ConfirmDialog(
            title = "Discard changes?",
            body = if (isNew) "This card hasn't been added yet. What you've typed will be lost." else "Your edits to this card haven't been saved.",
            confirmLabel = "Discard",
            onDismiss = { confirmDiscard = false },
            onConfirm = { confirmDiscard = false; onDone() },
        )
    }

    if (showDesign) DesignSheet(issuer, nickname, art, onSelect = { art = it }, onDismiss = { showDesign = false })

    val shownDue = if (!billingSet) null else loadedDue?.let(LocalDate::ofEpochDay) ?: cycleDue()
    if (pickingDue && !locked) DueDateDialog(
        initial = shownDue,
        removable = billingSet,
        onPick = { pickingDue = false; pickDue(it) },
        onRemove = { pickingDue = false; billingSet = false; loadedDue = null },
        onDismiss = { pickingDue = false },
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isNew) "Add card" else "Edit card") },
                navigationIcon = { IconButton(onClick = ::close) { Icon(Icons.Filled.Close, "Cancel") } },
            )
        },
        bottomBar = {
            if (loaded) Surface(color = MaterialTheme.colorScheme.background) {
                Button(
                    enabled = !saving, onClick = ::save,
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding().imePadding()
                        .padding(horizontal = Gutter, vertical = 12.dp).height(52.dp),
                ) { Text(if (isNew) "Add card" else "Save changes", style = MaterialTheme.typography.titleSmall) }
            }
        },
    ) { pad ->
        if (!loaded) return@Scaffold
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(scroll).padding(bottom = 24.dp)) {
            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = Gutter + 4.dp, vertical = 8.dp))
            }

            // Capture first: either fills the number (and expiry, name) in one go.
            Row(
                Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                FilledTonalButton(onClick = { scanning = true }, modifier = Modifier.weight(1f).height(48.dp)) {
                    Icon(Icons.Outlined.PhotoCamera, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Scan card")
                }
                if (canTap) FilledTonalButton(onClick = { tapping = true }, modifier = Modifier.weight(1f).height(48.dp)) {
                    Icon(Icons.Outlined.Contactless, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Tap card")
                }
            }

            val detected = CardNumbers.detectNetwork(digits)
            OutlinedTextField(
                value = number,
                onValueChange = { v ->
                    number = v.filter(Char::isDigit).take(19)
                    val d = number.filter(Char::isDigit)
                    CardNumbers.detectNetwork(d)?.let { if (!networkManual) network = it }
                    if (d.length >= 12) last4 = d.takeLast(4)
                    clear("number"); clear("last4")
                },
                label = { Text("Card number") },
                placeholder = { Text(if (stored[0]) "Stored. Type to replace." else "Optional") },
                singleLine = true,
                isError = errors["number"] != null || numberWarning != null,
                supportingText = (errors["number"] ?: numberWarning)?.let { { Text(it) } },
                trailingIcon = { if (detected != null) NetworkMark(detected, Modifier.padding(end = 12.dp)) },
                visualTransformation = CardNumberSpacing,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 4.dp),
            )

            // Last 4 only matters when the full number is skipped.
            if (digits.length < 12) {
                Field(
                    last4, { last4 = it.filter(Char::isDigit).take(4); clear("last4") },
                    if (digits.isEmpty()) "Last 4 digits" else "Last 4 digits (number incomplete)",
                    keyboard = KeyboardType.Number, support = errors["last4"],
                )
            }

            Row(
                Modifier.padding(horizontal = Gutter),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.Top,
            ) {
                OutlinedTextField(
                    value = expiry.filter(Char::isDigit),
                    onValueChange = { v ->
                        val d = v.filter(Char::isDigit).take(4)
                        expiry = if (d.length > 2) d.take(2) + "/" + d.drop(2) else d
                        clear("expiry")
                    },
                    label = { Text("Expiry") },
                    placeholder = { Text(if (stored[1]) "••/••" else "MM/YY") },
                    singleLine = true,
                    isError = errors["expiry"] != null,
                    supportingText = errors["expiry"]?.let { { Text(it) } },
                    visualTransformation = ExpirySlash,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, autoCorrectEnabled = false),
                    modifier = Modifier.weight(1f).padding(vertical = 4.dp),
                )
                OutlinedTextField(
                    value = cvv,
                    onValueChange = { cvv = it.filter(Char::isDigit).take(4); clear("cvv") },
                    label = { Text("CVV") },
                    placeholder = { Text(if (stored[2]) "•••" else "123") },
                    singleLine = true,
                    isError = errors["cvv"] != null,
                    supportingText = errors["cvv"]?.let { { Text(it) } },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, autoCorrectEnabled = false),
                    modifier = Modifier.weight(1f).padding(vertical = 4.dp),
                )
            }
            Field(
                holder, { holder = it }, "Name on card", hint = if (stored[3]) "Stored. Type to replace." else "Optional",
                caps = KeyboardCapitalization.Characters,
            )

            // What the number told us (or the defaults): each chip opens its list to correct it.
            FlowRow(
                Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ChipPicker(
                    "Bank", issuer, Issuers.all, { it.name },
                    onSelect = { issuerManual = true; changeIssuer(it.name) },
                    optionLeading = { BankBadge(it.name, size = 28.dp) },
                    leading = { BankBadge(issuer, size = 20.dp) },
                ) { Text(issuer) }
                ChipPicker(
                    "Network", network, Issuers.networks, { it },
                    onSelect = { network = it; networkManual = true },
                    optionLeading = { NetworkMark(it) },
                ) { NetworkMark(network, Modifier.padding(vertical = 4.dp)) }
                ChipPicker(
                    "Type", CardKind.label(kind), CardKind.all, { CardKind.label(it) },
                    onSelect = { kind = it; kindManual = true },
                ) { Text(CardKind.label(kind)) }
            }

            Field(
                nickname, { nickname = it; clear("nickname") }, "Nickname", hint = "e.g. Regalia, Millennia, Atlas",
                caps = KeyboardCapitalization.Words, support = errors["nickname"],
            )

            val credit = kind == CardKind.CREDIT
            Spacer(Modifier.height(12.dp))
            Panel(Modifier.padding(horizontal = Gutter), padded = false) {
                val matched = CardArts.match(issuer, nickname)
                val chosen = CardArts.byId(art)
                val shownArt = when (art) { null -> matched; BANK_COLOURS -> null; else -> chosen }
                ListRow(
                    icon = null,
                    title = "Design",
                    subtitle = when {
                        art == null -> "Auto · " + (matched?.name ?: "Bank colours")
                        art == BANK_COLOURS || chosen == null -> "Bank colours"
                        chosen.bank != issuer -> "${chosen.name} · ${chosen.bank}"
                        else -> chosen.name
                    },
                    onClick = { showDesign = true },
                    leading = {
                        DesignSwatch(
                            shownArt, Issuers.byName(issuer).color,
                            Modifier.width(52.dp).aspectRatio(CARD_RATIO).clip(RoundedCornerShape(6.dp)),
                        )
                    },
                    action = { TextButton(onClick = { showDesign = true }) { Text("Change") } },
                )

                if (credit) {
                    RowDivider()
                    val sd = statementDay.toIntOrNull()?.takeIf { it in 1..31 }
                    ListRow(
                        icon = Icons.Outlined.Event,
                        title = "Due date",
                        subtitle = when {
                            shownDue != null && sd != null -> "Next due ${Dates.short(shownDue)} · statement ~${ordinal(sd)}"
                            billingSet -> "Set the statement day under Billing cycle"
                            else -> "Optional · add for reminders"
                        },
                        onClick = { pickingDue = true },
                        action = { TextButton(onClick = { pickingDue = true }) { Text(if (billingSet) "Change" else "Add") } },
                    )
                    RowDivider()
                    val off = dueOffset.toIntOrNull()
                    ExpandRow(
                        Icons.Outlined.Tune, "Billing cycle",
                        if (billingSet && sd != null && off != null) "Statement ${ordinal(sd)} · due $off days later"
                        else "Advanced · exact statement day and due offset",
                        openCycle, { openCycle = !openCycle },
                    ) {
                        DayPickerField(
                            label = "Statement day",
                            day = statementDay.toIntOrNull(),
                            onPick = { statementDay = it.toString(); billingSet = true; loadedDue = null; clear("statementDay") },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        )
                        errors["statementDay"]?.let { ErrorLine(it) }
                        Field(
                            dueOffset,
                            { dueOffset = it.filter(Char::isDigit).take(2); dueOffsetManual = true; billingSet = true; loadedDue = null; clear("dueOffset") },
                            "Payment due (days after statement)",
                            keyboard = KeyboardType.Number, padded = false, modifier = Modifier.fillMaxWidth(),
                            support = errors["dueOffset"],
                            info = if (billingSet) cycleDue()?.let { d ->
                                "Next: statement ${Dates.short(d.minusDays(dueOffset.toLong()))}, due ${Dates.short(d)}"
                            } else "Usually ${Issuers.byName(issuer).dueOffset} days for ${issuer}",
                        )
                    }

                    RowDivider()
                    val chosenLeads = Billing.parseLeads(leads).toSet()
                    ExpandRow(
                        Icons.Outlined.NotificationsNone, "Reminders",
                        reminderLeadsSummary(chosenLeads.sortedDescending()) + if (billingSet) "" else " · once the due date is set",
                        openReminders, { openReminders = !openReminders },
                    ) {
                        // Toggle chips; any custom day already saved stays selectable.
                        val opts = (listOf(7, 5, 3, 2, 1, 0) + chosenLeads).distinct().sortedDescending()
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            opts.forEach { dDay ->
                                FilterChip(
                                    selected = dDay in chosenLeads,
                                    onClick = {
                                        val next = if (dDay in chosenLeads) chosenLeads - dDay else chosenLeads + dDay
                                        leads = next.sortedDescending().joinToString(",")
                                    },
                                    label = { Text(reminderLeadLabel(dDay)) },
                                )
                            }
                        }
                        if (chosenLeads.isEmpty()) {
                            Text(
                                "No reminders for this card.", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }

                    RowDivider()
                    val limitP = Money.parse(limit)?.takeIf { it > 0 }
                    val outP = Money.parse(outstanding)?.takeIf { it > 0 }
                    ExpandRow(
                        Icons.Outlined.AccountBalanceWallet, "Limit and balance",
                        listOfNotNull(limitP?.let { "${Money.inr(it)} limit" }, outP?.let { "${Money.inr(it)} outstanding" })
                            .joinToString(" · ").ifEmpty { "Optional" },
                        openLimit, { openLimit = !openLimit },
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                            Field(
                                limit, { limit = it; clear("limit") }, "Credit limit", keyboard = KeyboardType.Decimal, prefix = "₹ ",
                                modifier = Modifier.weight(1f), padded = false, support = errors["limit"],
                            )
                            Field(
                                outstanding, { outstanding = it; clear("outstanding") }, "Outstanding", keyboard = KeyboardType.Decimal, prefix = "₹ ",
                                modifier = Modifier.weight(1f), padded = false, support = errors["outstanding"],
                            )
                        }
                        Text(
                            "Leave blank if you don't know. Imported statements fill these in.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                RowDivider()
                ExpandRow(
                    Icons.AutoMirrored.Outlined.Notes, "Notes",
                    notes.trim().lineSequence().firstOrNull()?.takeIf { it.isNotBlank() } ?: "Optional",
                    openNotes, { openNotes = !openNotes },
                ) {
                    Field(notes, { notes = it }, "Notes", single = false, padded = false, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

/** The next due date on or after [today] for a statement on [day] (clamped) and a due [offset] days later. */
private fun nextDue(day: Int, offset: Int, today: LocalDate = LocalDate.now()): LocalDate {
    // Offsets are at most 60 days, so a cycle that started three months back is the earliest that can still be open.
    var ym = YearMonth.from(today).minusMonths(3)
    while (true) {
        val due = Billing.statementDate(ym, day).plusDays(offset.toLong())
        if (!due.isBefore(today)) return due
        ym = ym.plusMonths(1)
    }
}

/** Calendar for the next payment due date. Plain days: UTC midnight inside the picker, like [DateField]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DueDateDialog(initial: LocalDate?, removable: Boolean, onPick: (LocalDate) -> Unit, onRemove: () -> Unit, onDismiss: () -> Unit) {
    val state = rememberDatePickerState(
        initialSelectedDateMillis = initial?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli(),
        initialDisplayedMonthMillis = (initial ?: LocalDate.now()).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                enabled = state.selectedDateMillis != null,
                onClick = { state.selectedDateMillis?.let { onPick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) } },
            ) { Text("OK") }
        },
        dismissButton = {
            if (removable) TextButton(onClick = onRemove) { Text("Remove") }
            else TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    ) {
        DatePicker(
            state,
            title = { Text("Next payment due date", Modifier.padding(start = 24.dp, end = 12.dp, top = 16.dp)) },
        )
    }
}

/** An assist chip showing the current value; tapping opens the options as a dropdown (like [PickerField]). */
@Composable
private fun <T> ChipPicker(
    label: String,
    valueText: String,
    options: List<T>,
    optionText: (T) -> String,
    onSelect: (T) -> Unit,
    optionLeading: (@Composable (T) -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        AssistChip(
            onClick = { open = true },
            label = content,
            leadingIcon = leading,
            trailingIcon = { Icon(Icons.Filled.ArrowDropDown, null, Modifier.size(18.dp)) },
            modifier = Modifier.semantics { contentDescription = "$label: $valueText. Change $label" },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.heightIn(max = 360.dp)) {
            options.forEach { o ->
                DropdownMenuItem(
                    text = { Text(optionText(o)) },
                    leadingIcon = optionLeading?.let { l -> { l(o) } },
                    onClick = { onSelect(o); open = false },
                )
            }
        }
    }
}

/** A [ListRow] that opens to show [content] below it. */
@Composable
private fun ExpandRow(icon: ImageVector, title: String, subtitle: String, open: Boolean, onToggle: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val turn by animateFloatAsState(if (open) 180f else 0f, label = "expand")
    ListRow(
        icon = icon, title = title, subtitle = subtitle, onClick = onToggle,
        action = {
            Icon(
                Icons.Filled.KeyboardArrowDown, if (open) "Collapse $title" else "Expand $title",
                tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.rotate(turn),
            )
        },
    )
    AnimatedVisibility(open) {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp), content = content)
    }
}

@Composable
private fun ErrorLine(text: String) = Text(
    text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
    modifier = Modifier.padding(start = 16.dp, bottom = 4.dp),
)

/** Shows digits in groups of four without putting spaces in the value (keeps the cursor sane). */
private object CardNumberSpacing : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val raw = text.text
        val out = raw.chunked(4).joinToString(" ")
        val map = object : OffsetMapping {
            override fun originalToTransformed(offset: Int) = if (offset == 0) 0 else (offset + (offset - 1) / 4).coerceAtMost(out.length)
            override fun transformedToOriginal(offset: Int) = (offset - offset / 5).coerceIn(0, raw.length)
        }
        return TransformedText(AnnotatedString(out), map)
    }
}

/** Expiry digits "MMYY" shown as "MM/YY"; the value itself stays digits only. */
private object ExpirySlash : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val raw = text.text
        val out = if (raw.length > 2) raw.take(2) + "/" + raw.drop(2) else raw
        val map = object : OffsetMapping {
            override fun originalToTransformed(offset: Int) = (if (offset <= 2) offset else offset + 1).coerceAtMost(out.length)
            override fun transformedToOriginal(offset: Int) = (if (offset <= 2) offset else offset - 1).coerceIn(0, raw.length)
        }
        return TransformedText(AnnotatedString(out), map)
    }
}

@Composable
private fun Field(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    hint: String? = null,
    keyboard: KeyboardType = KeyboardType.Text,
    caps: KeyboardCapitalization = KeyboardCapitalization.Sentences,
    single: Boolean = true,
    support: String? = null,
    info: String? = null,
    prefix: String? = null,
    padded: Boolean = true,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = hint?.let { { Text(it) } },
        prefix = prefix?.let { { Text(it) } },
        singleLine = single,
        minLines = if (single) 1 else 3,
        isError = support != null,
        supportingText = (support ?: info)?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(keyboardType = keyboard, capitalization = caps, autoCorrectEnabled = keyboard == KeyboardType.Text),
        modifier = (if (padded) modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 4.dp) else modifier.padding(vertical = 4.dp)),
    )
}
