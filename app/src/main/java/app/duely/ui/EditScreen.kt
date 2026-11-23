package app.duely.ui

import androidx.activity.compose.BackHandler
import kotlin.math.roundToInt
import androidx.compose.ui.layout.layout
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Notes
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.Contactless
import androidx.compose.material.icons.outlined.Contacts
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
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
import app.duely.data.CardSecret
import app.duely.data.Repo
import app.duely.domain.Billing
import app.duely.domain.BinLookup
import app.duely.domain.CardNumbers
import app.duely.domain.Dates
import app.duely.domain.Issuers
import app.duely.domain.Money
import app.duely.security.Auth
import app.duely.security.EncryptedDraft
import app.duely.security.SecretDraft
import app.duely.security.Vault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
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
    /** Opens a saved card (the duplicate found on save) instead of adding this one. */
    onOpenCard: (Long) -> Unit = {},
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
    var showBank by rememberSaveable { mutableStateOf(false) }
    var netOpen by remember { mutableStateOf(false) }
    var pickingDue by remember { mutableStateOf(false) }
    var showNames by rememberSaveable { mutableStateOf(false) }
    // Saved cardholder names (a plain table, not the vault): suggestions without a fingerprint.
    val holderNames by remember { repo.holderNames.map { list -> list.map { it.name } } }.collectAsState(initial = emptyList())

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

    // A save held back because the card looks already saved: the card and its already-encrypted secrets
    // (ciphertext only; the numbers compared stay in local vals inside [findDuplicate]).
    var duplicate by remember { mutableStateOf<PendingSave?>(null) }

    suspend fun commit(card: Card, enc: EncryptedDraft?) {
        repo.save(card, enc)
        // Remember the name for next time (only a name typed now; a stored one is never decrypted here).
        if (holder.isNotBlank()) repo.useHolderName(holder)
        onDone()
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
        // The form's network counts for a duplicate check only when it came from the number or from you, not the default.
        val networkKnown = !isNew || networkManual || CardNumbers.detectNetwork(digits) != null

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
                // New cards, and edits that change the number: is this card already saved? Runs right after the
                // encrypt, so the vault's auth window is still open for comparing numbers (no second prompt).
                if (isNew || digits.length >= 12) {
                    val full = digits.takeIf { it.length >= 12 && enc?.number != null }
                    findDuplicate(repo, card, full, networkKnown)?.let { dup ->
                        duplicate = PendingSave(card, enc, dup)
                        return@launch
                    }
                }
                commit(card, enc)
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

    duplicate?.takeIf { !locked }?.let { p ->
        val d = p.match
        AlertDialog(
            onDismissRequest = { duplicate = null },
            title = { Text("You already have this card") },
            text = {
                Text("${d.nickname} (${d.issuer} ending ${d.last4}) " + if (d.archivedAt != null) "is in Archived cards." else "is already saved.")
            },
            confirmButton = {
                TextButton(onClick = {
                    duplicate = null
                    // Same card data and blobs as before: no second fingerprint prompt.
                    scope.launch {
                        saving = true
                        try {
                            commit(p.card, p.enc)
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            error = "Couldn't save the card (${e.javaClass.simpleName})."
                            scroll.animateScrollTo(0)
                        } finally {
                            saving = false
                        }
                    }
                }) { Text("Add anyway") }
            },
            dismissButton = { TextButton(onClick = { duplicate = null; onOpenCard(d.id) }) { Text("Open existing") } },
        )
    }

    if (showNames) NameSheet(
        names = holderNames,
        current = holder,
        onPick = { holder = it },
        onAdd = { scope.launch { repo.addHolderName(it)?.let { n -> holder = n } } },
        onRemove = { scope.launch { repo.removeHolderName(it) } },
        onDismiss = { showNames = false },
    )

    if (showBank) BankSheet(issuer, onSelect = { issuerManual = true; changeIssuer(it) }, onDismiss = { showBank = false })

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
                    enabled = !saving, onClick = ::save, shape = RoundedCornerShape(24.dp),
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding().imePadding()
                        .padding(horizontal = Gutter, vertical = 12.dp).height(52.dp),
                ) { Text(if (isNew) "Add card" else "Save changes", style = MaterialTheme.typography.titleSmall) }
            }
        },
    ) { pad ->
        if (!loaded) return@Scaffold
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(scroll).padding(horizontal = Gutter).padding(top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }

            // Capture first: either fills the number (and expiry, name, bank) in one go.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
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
                modifier = Modifier.fillMaxWidth(),
            )

            // Last 4 only matters when the full number is skipped.
            if (digits.length < 12) {
                Field(
                    last4, { last4 = it.filter(Char::isDigit).take(4); clear("last4") },
                    if (digits.isEmpty()) "Last 4 digits" else "Last 4 digits (number incomplete)",
                    keyboard = KeyboardType.Number, support = errors["last4"],
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
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
                    modifier = Modifier.weight(1f),
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
                    modifier = Modifier.weight(1f),
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Field(
                    holder, { holder = it }, "Name on card", hint = if (stored[3]) "Stored. Type to replace." else "Optional",
                    caps = KeyboardCapitalization.Characters,
                    trailing = { IconButton(onClick = { showNames = true }) { Icon(Icons.Outlined.Contacts, "Saved names") } },
                )
                // Top three, hidden once the field already holds one of them.
                val names = holderNames.take(3).takeUnless { n -> n.any { it.equals(holder.trim(), ignoreCase = true) } }.orEmpty()
                if (names.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    names.forEachIndexed { i, n ->
                        SuggestionChip(
                            onClick = { holder = n },
                            label = { Text(n) },
                            icon = if (i == 0) { { Icon(Icons.Outlined.History, null, Modifier.size(SuggestionChipDefaults.IconSize)) } } else null,
                            modifier = Modifier.semantics { contentDescription = "Use name $n" },
                        )
                    }
                }
            }

            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                CardKind.all.forEachIndexed { i, k ->
                    SegmentedButton(
                        selected = kind == k,
                        onClick = { kind = k; kindManual = true },
                        shape = SegmentedButtonDefaults.itemShape(i, CardKind.all.size),
                        label = { Text(CardKind.label(k)) },
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SelectField(
                    "Bank", issuer, open = showBank, onClick = { showBank = true }, modifier = Modifier.weight(1.4f),
                    leadingIcon = { BankBadge(issuer, size = 24.dp) },
                )
                SelectField(
                    "Network", network, open = netOpen, onClick = { netOpen = true }, modifier = Modifier.weight(1f),
                    inline = { NetworkMark(network) },
                ) {
                    DropdownMenu(expanded = netOpen, onDismissRequest = { netOpen = false }) {
                        Issuers.networks.forEach { n ->
                            DropdownMenuItem(
                                text = { Text(n) },
                                // Fixed-width slot so logos of different shapes line up.
                                leadingIcon = { Box(Modifier.width(44.dp), contentAlignment = Alignment.Center) { NetworkMark(n) } },
                                trailingIcon = if (n == network) { { Icon(Icons.Filled.Check, null) } } else null,
                                onClick = { network = n; networkManual = true; netOpen = false },
                            )
                        }
                    }
                }
            }

            Field(
                nickname, { nickname = it; clear("nickname") }, "Nickname", hint = "e.g. Regalia, Millennia, Atlas",
                caps = KeyboardCapitalization.Words, support = errors["nickname"],
            )

            val credit = kind == CardKind.CREDIT
            Panel(Modifier.padding(top = 4.dp), padded = false, color = MaterialTheme.colorScheme.surfaceContainer) {
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
                    chevron = true,
                    onClick = { showDesign = true },
                    // A square crop of the card art, in the same tile as the other rows' icons.
                    leading = {
                        Box(Modifier.size(TILE).clip(TileShape), contentAlignment = Alignment.Center) {
                            DesignSwatch(shownArt, Issuers.byName(issuer).color, Modifier.requiredSize(TILE * CARD_RATIO, TILE))
                        }
                    },
                )

                if (credit) {
                    TileDivider()
                    val sd = statementDay.toIntOrNull()?.takeIf { it in 1..31 }
                    ListRow(
                        icon = null,
                        title = "Due date",
                        subtitle = when {
                            shownDue != null && sd != null -> "Next due ${Dates.short(shownDue)} · statement ~${ordinal(sd)}"
                            billingSet -> "Set the statement day under Billing cycle"
                            else -> "Optional · add for reminders"
                        },
                        chevron = true,
                        onClick = { pickingDue = true },
                        leading = { IconTile(Icons.Outlined.Event) },
                    )

                    TileDivider()
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
                                val on = dDay in chosenLeads
                                FilterChip(
                                    selected = on,
                                    onClick = {
                                        val next = if (on) chosenLeads - dDay else chosenLeads + dDay
                                        leads = next.sortedDescending().joinToString(",")
                                    },
                                    label = { Text(reminderLeadLabel(dDay)) },
                                    leadingIcon = if (on) { { Icon(Icons.Filled.Check, null, Modifier.size(FilterChipDefaults.IconSize)) } } else null,
                                )
                            }
                        }
                        if (chosenLeads.isEmpty()) {
                            Text("No reminders for this card.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }

                    TileDivider()
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
                                modifier = Modifier.weight(1f), support = errors["limit"],
                            )
                            Field(
                                outstanding, { outstanding = it; clear("outstanding") }, "Outstanding", keyboard = KeyboardType.Decimal, prefix = "₹ ",
                                modifier = Modifier.weight(1f), support = errors["outstanding"],
                            )
                        }
                        Text(
                            "Leave blank if you don't know. Imported statements fill these in.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    TileDivider()
                    val off = dueOffset.toIntOrNull()
                    ExpandRow(
                        Icons.Outlined.Tune, "Billing cycle",
                        if (billingSet && sd != null && off != null) "Statement ${ordinal(sd)} · due $off days later"
                        else "Advanced · exact statement day and due offset",
                        openCycle, { openCycle = !openCycle },
                    ) {
                        Column {
                            DayPickerField(
                                label = "Statement day",
                                day = statementDay.toIntOrNull(),
                                onPick = { statementDay = it.toString(); billingSet = true; loadedDue = null; clear("statementDay") },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            errors["statementDay"]?.let { ErrorLine(it) }
                        }
                        Field(
                            dueOffset,
                            { dueOffset = it.filter(Char::isDigit).take(2); dueOffsetManual = true; billingSet = true; loadedDue = null; clear("dueOffset") },
                            "Payment due (days after statement)",
                            keyboard = KeyboardType.Number,
                            support = errors["dueOffset"],
                            info = if (billingSet) cycleDue()?.let { d ->
                                "Next: statement ${Dates.short(d.minusDays(dueOffset.toLong()))}, due ${Dates.short(d)}"
                            } else "Usually ${Issuers.byName(issuer).dueOffset} days for $issuer",
                        )
                    }
                }

                TileDivider()
                ExpandRow(
                    Icons.AutoMirrored.Outlined.Notes, "Notes",
                    notes.trim().lineSequence().firstOrNull()?.takeIf { it.isNotBlank() } ?: "Optional",
                    openNotes, { openNotes = !openNotes },
                ) {
                    Field(notes, { notes = it }, "Notes", single = false)
                }
            }
        }
    }
}

/** A save waiting on the "already in your wallet" answer. */
private class PendingSave(val card: Card, val enc: EncryptedDraft?, val match: Card)

/**
 * A saved card (active ones first, then archived) that [card] duplicates, or null.
 * With the [full] number typed, a candidate whose stored number decrypts is compared exactly. That needs the
 * vault's auth window from the save that just happened; if it has closed (or the key is lost) the number is
 * treated as unknown. Without a comparable number: same bank and last four (and network, when [networkKnown]).
 * Decrypted numbers live only in local vals here.
 */
private suspend fun findDuplicate(repo: Repo, card: Card, full: String?, networkKnown: Boolean): Card? {
    val candidates = repo.allCards().filter { it.id != card.id && it.last4 == card.last4 }.sortedBy { it.archivedAt != null }
    for (c in candidates) {
        val blob = if (full != null) repo.secret(c.id)?.numberEnc else null
        val theirs = blob?.let { b ->
            withContext(Dispatchers.IO) { runCatching { Vault.decrypt(CardSecret(c.id, b, null, null, null)).number }.getOrNull() }
        }
        if (theirs != null) {
            if (theirs.filter(Char::isDigit) == full) return c else continue
        }
        if (c.issuer == card.issuer && (!networkKnown || c.network == card.network)) return c
    }
    return null
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

/** Leading tile size and shape shared by every row of the form's panel, so titles line up. */
private val TILE = 36.dp
private val TileShape = RoundedCornerShape(10.dp)

@Composable
private fun IconTile(icon: ImageVector) = Box(
    Modifier.size(TILE).clip(TileShape).background(MaterialTheme.colorScheme.surfaceContainerHighest),
    contentAlignment = Alignment.Center,
) { Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp)) }

/** Divider between panel rows, inset to the text (16 padding + tile + 14 gap). */
@Composable
private fun TileDivider() =
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(start = 16.dp + TILE + 14.dp))

/**
 * Read-only outlined field that opens a picker: a sheet via [onClick], or the dropdown in [menu]. With [inline]
 * (e.g. a logo) that content is drawn in place of the text; the value stays for screen readers.
 */
@Composable
private fun SelectField(
    label: String,
    value: String,
    open: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: (@Composable () -> Unit)? = null,
    inline: (@Composable () -> Unit)? = null,
    menu: @Composable () -> Unit = {},
) {
    Box(modifier) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(label, maxLines = 1) },
            leadingIcon = leadingIcon,
            trailingIcon = { Icon(if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, null) },
            textStyle = if (inline != null) LocalTextStyle.current.copy(color = Color.Transparent) else LocalTextStyle.current,
            modifier = Modifier.fillMaxWidth(),
        )
        // The outline starts 8dp down (room for the floating label): overlays match the box, not the label.
        if (inline != null) Box(Modifier.matchParentSize().padding(top = 8.dp, start = 16.dp), contentAlignment = Alignment.CenterStart) { inline() }
        Box(
            Modifier.matchParentSize().padding(top = 8.dp).clip(RoundedCornerShape(4.dp))
                .semantics { contentDescription = "$label: $value" }
                .clickable(role = Role.DropdownList, onClickLabel = "Choose $label", onClick = onClick),
        )
        menu()
    }
}

/** A panel row that opens to show [content] below it; only its height animates. */
@Composable
private fun ExpandRow(
    icon: ImageVector, title: String, subtitle: String, open: Boolean, onToggle: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    ListRow(
        icon = null, title = title, subtitle = subtitle, onClick = onToggle,
        leading = { IconTile(icon) },
        action = {
            Icon(
                if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                if (open) "Collapse $title" else "Expand $title",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    )
    // Height goes 0..full on one tween both ways; the content stays drawn (clipped) until fully closed,
    // so collapsing shrinks smoothly instead of dropping first.
    val progress by animateFloatAsState(if (open) 1f else 0f, tween(260, easing = FastOutSlowInEasing), label = "expand")
    if (open || progress > 0f) {
        Column(
            Modifier.fillMaxWidth().clipToBounds()
                .layout { measurable, constraints ->
                    val p = measurable.measure(constraints)
                    val h = (p.height * progress).roundToInt()
                    layout(p.width, h) { p.placeRelative(0, 0) }
                }
                .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

@Composable
private fun ErrorLine(text: String) = Text(
    text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
    modifier = Modifier.padding(start = 16.dp, top = 4.dp),
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
    trailing: (@Composable () -> Unit)? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = hint?.let { { Text(it) } },
        prefix = prefix?.let { { Text(it) } },
        trailingIcon = trailing,
        singleLine = single,
        minLines = if (single) 1 else 3,
        isError = support != null,
        supportingText = (support ?: info)?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(keyboardType = keyboard, capitalization = caps, autoCorrectEnabled = keyboard == KeyboardType.Text),
        // Spacing comes from the parent column (12dp between fields), not from each field.
        modifier = modifier.fillMaxWidth(),
    )
}
