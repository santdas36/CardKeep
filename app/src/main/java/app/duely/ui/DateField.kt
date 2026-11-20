package app.duely.ui

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import app.duely.domain.Dates
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** Read-only field that opens a calendar. Dates are plain days (UTC midnight inside the picker). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateField(
    label: String,
    value: LocalDate?,
    onChange: (LocalDate?) -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth(),
    initialPick: LocalDate = LocalDate.now(),
    clearable: Boolean = false,
) {
    var open by remember { mutableStateOf(false) }
    val source = remember { MutableInteractionSource() }
    LaunchedEffect(source) {
        source.interactions.collect { if (it is PressInteraction.Release) open = true }
    }
    OutlinedTextField(
        value = value?.let(Dates::long) ?: "", onValueChange = {}, readOnly = true, singleLine = true,
        label = { Text(label) }, trailingIcon = { Icon(Icons.Outlined.CalendarMonth, null) },
        interactionSource = source, modifier = modifier,
    )
    if (open) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = (value ?: initialPick).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    open = false
                    state.selectedDateMillis?.let { onChange(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                }) { Text("OK") }
            },
            dismissButton = {
                if (clearable && value != null) TextButton(onClick = { open = false; onChange(null) }) { Text("Clear") }
                else TextButton(onClick = { open = false }) { Text("Cancel") }
            },
        ) { DatePicker(state) }
    }
}
