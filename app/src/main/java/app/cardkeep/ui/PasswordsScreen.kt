package app.cardkeep.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.cardkeep.data.Ledger
import app.cardkeep.ingest.Passwords
import app.cardkeep.ingest.PdfProfile
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * What statement PDFs are unlocked with. Used by email sync and by manual import.
 * Remembered passwords (ones you typed to unlock a statement) are listed masked.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PasswordsScreen(ledger: Ledger, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var loaded by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var dob by remember { mutableStateOf<LocalDate?>(null) }
    var pan by remember { mutableStateOf("") }
    var mobile by remember { mutableStateOf("") }
    var patterns by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf(listOf<String>()) }

    LaunchedEffect(Unit) {
        val p = ledger.profile(ledger.mailSourceOnce())
        name = p.name; dob = p.dob; pan = p.pan; mobile = p.mobile
        patterns = p.templates.filter { it.contains('{') }.joinToString("\n")
        saved = p.templates.filterNot { it.contains('{') }
        loaded = true
    }

    fun save(andBack: Boolean) = scope.launch {
        ledger.saveProfile(
            PdfProfile(
                name = name.trim(), dob = dob, pan = pan.trim(), mobile = mobile.trim(),
                templates = patterns.lines().map { it.trim() }.filter { it.isNotEmpty() } + saved,
            ),
        )
        android.widget.Toast.makeText(context, "Saved", android.widget.Toast.LENGTH_SHORT).show()
        if (andBack) onBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Statement passwords") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
        bottomBar = {
            if (loaded) Button(onClick = { save(true) }, modifier = Modifier.fillMaxWidth().imePadding().padding(16.dp)) { Text("Save") }
        },
    ) { pad ->
        if (!loaded) return@Scaffold
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = Gutter).padding(bottom = 24.dp)) {
            Text(
                "Banks lock statement PDFs with things like your name and birth date. CardKeep tries the usual patterns for each bank. Stored encrypted on this phone; never sent anywhere.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            SectionHeader("About you")
            Panel {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        name, { name = it }, label = { Text("Name as on card") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false),
                    )
                    DateField("Date of birth", dob, { dob = it }, initialPick = LocalDate.of(1990, 1, 1), clearable = true)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(
                            pan, { pan = it.uppercase().filter(Char::isLetterOrDigit).take(10) }, label = { Text("PAN") }, singleLine = true,
                            modifier = Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false),
                        )
                        OutlinedTextField(
                            mobile, { mobile = it.filter(Char::isDigit).take(10) }, label = { Text("Mobile") }, singleLine = true,
                            modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        )
                    }
                }
            }
            SectionHeader("Extra patterns")
            Panel {
                OutlinedTextField(
                    patterns, { patterns = it }, label = { Text("One per line (optional)") }, minLines = 2, modifier = Modifier.fillMaxWidth(),
                    supportingText = { Text("Use " + Passwords.TOKENS.joinToString("  ") { it.first }) },
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                )
            }
            if (saved.isNotEmpty()) {
                SectionHeader("Remembered passwords")
                Panel(padded = false) {
                    saved.forEachIndexed { i, pw ->
                        if (i > 0) RowDivider()
                        ListRow(
                            Icons.Outlined.Key, pw.take(2) + "•".repeat((pw.length - 2).coerceAtLeast(2)), "Saved when you unlocked a statement",
                            action = { IconButton(onClick = { saved = saved - pw; save(false) }) { Icon(Icons.Outlined.Delete, "Forget", tint = MaterialTheme.colorScheme.onSurfaceVariant) } },
                        )
                    }
                }
            }
        }
    }
}
