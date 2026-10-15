@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package app.pillcheck

import android.app.NotificationManager
import android.app.AlarmManager
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

@Composable
fun TodayScreen(version: Int, onAdd: () -> Unit, onEdit: (Med) -> Unit) {
    val c = LocalContext.current
    val meds = remember(version) { Store.meds(c) }
    val now = LocalDateTime.now()

    Text(str(R.string.tab_today), style = MaterialTheme.typography.headlineMedium)
    Text(now.toLocalDate().dayFull(), color = MaterialTheme.colorScheme.onSurfaceVariant)
    Warnings()

    if (meds.isEmpty()) {
        Text(str(R.string.empty_meds))
        Button(onClick = onAdd, Modifier.fillMaxWidth()) { Text(str(R.string.add_med)) }
        return
    }
    meds.filter { it.lowStock }.forEach { m ->
        Warning(str(R.string.low_stock, m.name, m.stockText() ?: ""), str(R.string.set_new_stock)) { onEdit(m) }
    }

    val pending = Store.pending(c, meds, now)
    val leftFromYesterday = pending.filter { it.at.toLocalDate() < now.toLocalDate() }
    val today = Store.intakesOn(meds, now.toLocalDate())
    if (leftFromYesterday.isEmpty() && today.isEmpty()) {
        Text(str(R.string.no_doses_today), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (pending.size >= 2) {
        Button(onClick = { pending.forEach { Store.setTaken(c, it.key, true) } }, Modifier.fillMaxWidth()) {
            Text(str(R.string.took_all_missed, pending.size))
        }
    }
    (leftFromYesterday + today).forEach { IntakeCard(it, now) }
}

@Composable
private fun IntakeCard(i: Intake, now: LocalDateTime) {
    val c = LocalContext.current
    val taken = Store.takenAt(c, i)
    val late = Duration.between(i.at, now).toMinutes()
    val status = when {
        taken != null -> str(R.string.status_taken, taken.fmt(), shiftText(i.at, taken))
        late < 0 -> null
        late >= Store.GIVE_UP_HOURS * 60 -> str(R.string.status_missed)
        else -> str(R.string.status_late, minutesText(late))
    }
    val yesterday = i.at.toLocalDate() < now.toLocalDate()
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(i.at.fmt(), style = MaterialTheme.typography.titleLarge)
            Column(Modifier.weight(1f)) {
                Text(i.med.name, style = MaterialTheme.typography.titleMedium)
                val details = listOfNotNull(
                    str(R.string.yesterday).takeIf { yesterday },
                    i.med.dose.ifBlank { null },
                    courseText(i.med, i.at.toLocalDate()),
                )
                if (details.isNotEmpty()) Text(details.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                if (i.med.note.isNotBlank()) {
                    Text("📝 ${i.med.note}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
                status?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            }
            if (taken == null) {
                Button(onClick = { Store.setTaken(c, i.key, true) }) { Text(str(R.string.btn_taken)) }
            } else {
                TextButton(onClick = { Store.setTaken(c, i.key, false) }) { Text(str(R.string.btn_undo)) }
            }
        }
    }
}

/** Всё, из-за чего напоминание может не прийти или опоздать. */
@Composable
private fun Warnings() {
    val c = LocalContext.current
    val pkg = Uri.parse("package:${c.packageName}")
    // Работает на любой версии Android и ловит и запрет разрешения, и выключенные в настройках уведомления.
    if (!c.getSystemService(NotificationManager::class.java).areNotificationsEnabled()) {
        Warning(str(R.string.warn_notifications), str(R.string.warn_notifications_action)) {
            c.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, c.packageName))
        }
    }
    if (!c.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()) {
        Warning(str(R.string.warn_exact), str(R.string.warn_exact_action)) {
            c.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkg))
        }
    }
    if (!c.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(c.packageName)) {
        Warning(str(R.string.warn_battery), str(R.string.warn_battery_action)) {
            c.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg))
        }
    }
}

@Composable
private fun Warning(text: String, action: String, onClick: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.padding(16.dp)) {
            Text(text, color = MaterialTheme.colorScheme.onErrorContainer)
            TextButton(onClick = onClick) { Text(action) }
        }
    }
}

@Composable
fun MedsScreen(version: Int, onEdit: (Med) -> Unit) {
    val c = LocalContext.current
    val meds = remember(version) { Store.meds(c) }

    Text(str(R.string.tab_meds), style = MaterialTheme.typography.headlineMedium)
    meds.forEach { m ->
        Card(onClick = { onEdit(m) }, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(m.name, style = MaterialTheme.typography.titleMedium)
                if (m.dose.isNotBlank()) Text(m.dose)
                Text(
                    "${m.times.joinToString { it.fmt() }} · ${scheduleText(m)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                courseText(m, LocalDate.now())?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
                if (m.note.isNotBlank()) Text("📝 ${m.note}", style = MaterialTheme.typography.bodySmall)
                m.stockText()?.let {
                    Text(
                        "📦 ${it.replaceFirstChar(Char::titlecase)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (m.lowStock) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
    Button(onClick = { onEdit(Med()) }, Modifier.fillMaxWidth()) { Text(str(R.string.add_med_plus)) }
}

/** Сохранение всех данных в файл и восстановление — чтобы перенести на новый телефон. */
@Composable
fun Backup() {
    val c = LocalContext.current
    fun toast(text: String) = Toast.makeText(c, text, Toast.LENGTH_LONG).show()
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching { c.contentResolver.openOutputStream(uri)!!.use { it.write(Store.exportJson(c).toByteArray()) } }
            .onSuccess { toast(str(R.string.backup_saved)) }
            .onFailure { toast(str(R.string.backup_save_failed, it.message ?: "")) }
    }
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching { Store.importJson(c, c.contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() }) }
            .onSuccess { toast(str(R.string.backup_restored)) }
            .onFailure { toast(str(R.string.backup_bad_file)) }
    }
    var confirmRestore by remember { mutableStateOf(false) }

    Text(str(R.string.backup_title), style = MaterialTheme.typography.titleMedium)
    Text(
        str(R.string.backup_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { save.launch(str(R.string.backup_file_name, LocalDate.now().toString())) }, Modifier.weight(1f)) { Text(str(R.string.btn_save)) }
        OutlinedButton(onClick = { confirmRestore = true }, Modifier.weight(1f)) { Text(str(R.string.btn_restore)) }
    }
    if (confirmRestore) {
        AlertDialog(
            onDismissRequest = { confirmRestore = false },
            title = { Text(str(R.string.restore_title)) },
            text = { Text(str(R.string.restore_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmRestore = false
                    restore.launch(arrayOf("application/json", "application/octet-stream", "text/plain"))
                }) { Text(str(R.string.btn_choose_file)) }
            },
            dismissButton = { TextButton(onClick = { confirmRestore = false }) { Text(str(R.string.btn_cancel)) } },
        )
    }
}

@Composable
fun MedEditor(initial: Med, onDone: () -> Unit) {
    val c = LocalContext.current
    val isNew = remember { Store.meds(c).none { it.id == initial.id } }
    var name by remember { mutableStateOf(initial.name) }
    var dose by remember { mutableStateOf(initial.dose) }
    var times by remember { mutableStateOf(initial.times) }
    var freq by remember {
        mutableStateOf(
            when {
                initial.interval == 2 -> Freq.ALTERNATE
                initial.days.size == 7 -> Freq.DAILY
                else -> Freq.WEEKDAYS
            }
        )
    }
    var days by remember { mutableStateOf(if (freq == Freq.WEEKDAYS) initial.days else emptySet()) }
    var start by remember { mutableStateOf(initial.start.takeIf { it != LONG_AGO } ?: LocalDate.now()) }
    var isCourse by remember { mutableStateOf(initial.courseDays != null) }
    var courseText by remember { mutableStateOf(initial.courseDays?.toString() ?: "") }
    val courseDays = courseText.toIntOrNull()?.takeIf { it in 1..3650 }
    var note by remember { mutableStateOf(initial.note) }
    var trackStock by remember { mutableStateOf(initial.stock != null) }
    var stockText by remember { mutableStateOf(initial.stock?.toString() ?: "") }
    var perDoseText by remember { mutableStateOf(initial.perDose.toString()) }
    val stock = stockText.toIntOrNull()
    val perDose = perDoseText.toIntOrNull()?.takeIf { it in 1..100 }
    var confirmDelete by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        TextButton(onClick = onDone) { Text(str(R.string.btn_back)) }
        Text(str(if (isNew) R.string.editor_new else R.string.editor_edit), style = MaterialTheme.typography.headlineMedium)

        OutlinedTextField(
            name, { name = it },
            label = { Text(str(R.string.field_name)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            dose, { dose = it },
            label = { Text(str(R.string.field_dose)) },
            placeholder = { Text(str(R.string.field_dose_hint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            note, { note = it },
            label = { Text(str(R.string.field_note)) },
            placeholder = { Text(str(R.string.field_note_hint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Text(str(R.string.times_title), style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            times.forEach { t ->
                InputChip(
                    selected = false,
                    onClick = { times = times - t },
                    label = { Text(t.fmt()) },
                    trailingIcon = { Text("✕") },
                )
            }
            AssistChip(
                onClick = {
                    val suggest = times.lastOrNull()?.plusHours(12) ?: LocalTime.of(9, 0)
                    TimePickerDialog(c, { _, h, m ->
                        times = (times + LocalTime.of(h, m)).distinct().sorted()
                    }, suggest.hour, suggest.minute, true).show()
                },
                label = { Text(str(R.string.add_time)) },
            )
        }

        Text(str(R.string.freq_title), style = MaterialTheme.typography.titleMedium)
        Column {
            Freq.entries.forEach { f -> Choice(str(f.title), freq == f) { freq = f } }
        }
        if (freq == Freq.WEEKDAYS) {
            Text(str(R.string.weekdays_hint), color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DayOfWeek.entries.forEach { d ->
                    FilterChip(
                        selected = d in days,
                        onClick = { days = if (d in days) days - d else days + d },
                        label = { Text(d.short()) },
                        leadingIcon = if (d in days) {
                            { Text("✓") }
                        } else null,
                    )
                }
            }
        }

        Text(str(R.string.duration_title), style = MaterialTheme.typography.titleMedium)
        Column {
            Choice(str(R.string.duration_forever), !isCourse) { isCourse = false }
            Choice(str(R.string.duration_course), isCourse) { isCourse = true }
        }
        if (isCourse) {
            OutlinedTextField(
                courseText, { v -> courseText = v.filter(Char::isDigit).take(4) },
                label = { Text(str(R.string.course_days)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(str(R.string.start_from), Modifier.weight(1f))
            TextButton(onClick = {
                DatePickerDialog(c, { _, y, m, d -> start = LocalDate.of(y, m + 1, d) },
                    start.year, start.monthValue - 1, start.dayOfMonth).show()
            }) { Text(if (start == LocalDate.now()) str(R.string.today_lower) else start.dayMonth()) }
        }
        if (isCourse && courseDays != null) {
            Text(
                str(R.string.last_day, start.plusDays(courseDays - 1L).dayMonth()),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(str(R.string.track_stock), style = MaterialTheme.typography.titleMedium)
                Text(
                    str(R.string.track_stock_hint, Store.LOW_STOCK_DAYS),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = trackStock, onCheckedChange = { trackStock = it })
        }
        if (trackStock) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    stockText, { v -> stockText = v.filter(Char::isDigit).take(5) },
                    label = { Text(str(R.string.stock_now)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    perDoseText, { v -> perDoseText = v.filter(Char::isDigit).take(3) },
                    label = { Text(str(R.string.per_dose)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
            }
        }

        val problem = when {
            name.isBlank() -> str(R.string.err_name)
            times.isEmpty() -> str(R.string.err_times)
            freq == Freq.WEEKDAYS && days.isEmpty() -> str(R.string.err_days)
            isCourse && courseDays == null -> str(R.string.err_course)
            trackStock && stock == null -> str(R.string.err_stock)
            trackStock && perDose == null -> str(R.string.err_per_dose)
            else -> null
        }
        Button(
            onClick = {
                Store.saveMed(
                    c, initial.copy(
                        name = name.trim(),
                        dose = dose.trim(),
                        times = times,
                        days = if (freq == Freq.WEEKDAYS) days else DayOfWeek.entries.toSet(),
                        interval = if (freq == Freq.ALTERNATE) 2 else 1,
                        start = start,
                        courseDays = if (isCourse) courseDays else null,
                        note = note.trim(),
                        stock = if (trackStock) stock else null,
                        perDose = if (trackStock) perDose ?: 1 else initial.perDose,
                    )
                )
                onDone()
            },
            enabled = problem == null,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(str(R.string.btn_save)) }
        problem?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (!isNew) {
            OutlinedButton(onClick = { confirmDelete = true }, Modifier.fillMaxWidth()) { Text(str(R.string.btn_delete)) }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(str(R.string.delete_title, initial.name)) },
            text = { Text(str(R.string.delete_text)) },
            confirmButton = {
                TextButton(onClick = {
                    Store.deleteMed(c, initial.id)
                    onDone()
                }) { Text(str(R.string.btn_delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(str(R.string.btn_cancel)) } },
        )
    }
}

private enum class Freq(val title: Int) {
    DAILY(R.string.freq_daily),
    ALTERNATE(R.string.freq_alternate),
    WEEKDAYS(R.string.freq_weekdays),
}

/** Строка с радиокнопкой; нажимается вся целиком. */
@Composable
fun Choice(text: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected, onClick = onClick, role = Role.RadioButton),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(text, Modifier.padding(start = 8.dp))
    }
}

@Composable
fun HistoryScreen(version: Int) {
    val c = LocalContext.current
    val meds = remember(version) { Store.meds(c) }
    val today = LocalDate.now()

    Text(str(R.string.tab_history), style = MaterialTheme.typography.headlineMedium)
    val days = Store.pastDays(c, meds, today, 30)
    val streak = Store.streak(c, meds, today)
    val stats = Store.stats(c, days)

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                if (streak > 0) plural(R.plurals.streak, streak) else str(R.string.streak_none),
                style = MaterialTheme.typography.titleMedium,
            )
            if (stats.total > 0) {
                Text(plural(R.plurals.stats_period, days.size, days.size, stats.done, stats.total, stats.done * 100 / stats.total))
                stats.avgShift?.let { Text(str(R.string.avg_shift, minutesText(it))) }
            }
            TextButton(onClick = {
                c.startActivity(
                    Intent.createChooser(
                        Intent(Intent.ACTION_SEND).setType("text/plain")
                            .putExtra(Intent.EXTRA_SUBJECT, str(R.string.report_title))
                            .putExtra(Intent.EXTRA_TEXT, Store.report(c)),
                        str(R.string.share_report),
                    )
                )
            }) { Text(str(R.string.send_to_doctor)) }
        }
    }

    if (days.isEmpty()) {
        Text(str(R.string.history_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    days.forEach { (d, intakes) ->
        val done = intakes.count { Store.takenAt(c, it) != null }
        Column(Modifier.padding(top = 8.dp)) {
            Text(
                str(R.string.history_day, d.dayFull(), done, intakes.size),
                style = MaterialTheme.typography.titleSmall,
                color = if (done == intakes.size) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
            intakes.forEach { i ->
                val t = Store.takenAt(c, i)
                val mark = if (t == null) "❌" else "✅ ${t.fmt()}${shiftText(i.at, t)}"
                Text("${i.at.fmt()}  ${i.med.name}  $mark", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
