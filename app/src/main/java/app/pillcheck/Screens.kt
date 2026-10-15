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

private val dayFmt = DateTimeFormatter.ofPattern("d MMMM, EEEE")

@Composable
fun TodayScreen(version: Int, onAdd: () -> Unit, onEdit: (Med) -> Unit) {
    val c = LocalContext.current
    val meds = remember(version) { Store.meds(c) }
    val now = LocalDateTime.now()

    Text("Сегодня", style = MaterialTheme.typography.headlineMedium)
    Text(now.format(dayFmt), color = MaterialTheme.colorScheme.onSurfaceVariant)
    Warnings()

    if (meds.isEmpty()) {
        Text("Добавь лекарства, и приложение будет напоминать о каждом приёме.")
        Button(onClick = onAdd, Modifier.fillMaxWidth()) { Text("Добавить лекарство") }
        return
    }
    meds.filter { it.lowStock }.forEach { m ->
        Warning("Заканчивается «${m.name}»: ${m.stockText()}. Пора купить.", "Указать новый остаток") { onEdit(m) }
    }

    val pending = Store.pending(c, meds, now)
    val leftFromYesterday = pending.filter { it.at.toLocalDate() < now.toLocalDate() }
    val today = Store.intakesOn(meds, now.toLocalDate())
    if (leftFromYesterday.isEmpty() && today.isEmpty()) {
        Text("Сегодня приёмов нет.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (pending.size >= 2) {
        Button(onClick = { pending.forEach { Store.setTaken(c, it.key, true) } }, Modifier.fillMaxWidth()) {
            Text("Выпил все пропущенные (${pending.size})")
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
        taken != null -> "✅ Выпито в ${taken.fmt()}${shiftText(i.at, taken)}"
        late < 0 -> null
        late >= Store.GIVE_UP_HOURS * 60 -> "❌ Пропущено"
        else -> "⏰ Опоздание ${minutesText(late)}"
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
                    "вчера".takeIf { yesterday },
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
                Button(onClick = { Store.setTaken(c, i.key, true) }) { Text("Выпил") }
            } else {
                TextButton(onClick = { Store.setTaken(c, i.key, false) }) { Text("Отменить") }
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
        Warning("Уведомления выключены — напоминаний не будет.", "Включить") {
            c.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, c.packageName))
        }
    }
    if (!c.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()) {
        Warning("Нет разрешения на точные напоминания — они могут опаздывать на несколько минут.", "Разрешить") {
            c.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkg))
        }
    }
    if (!c.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(c.packageName)) {
        Warning("Система может усыпить приложение и пропустить напоминание.", "Снять ограничение") {
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

    Text("Лекарства", style = MaterialTheme.typography.headlineMedium)
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
    Button(onClick = { onEdit(Med()) }, Modifier.fillMaxWidth()) { Text("+ Добавить лекарство") }
}

/** Сохранение всех данных в файл и восстановление — чтобы перенести на новый телефон. */
@Composable
fun Backup() {
    val c = LocalContext.current
    fun toast(text: String) = Toast.makeText(c, text, Toast.LENGTH_LONG).show()
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching { c.contentResolver.openOutputStream(uri)!!.use { it.write(Store.exportJson(c).toByteArray()) } }
            .onSuccess { toast("Копия сохранена") }
            .onFailure { toast("Не удалось сохранить: ${it.message}") }
    }
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching { Store.importJson(c, c.contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() }) }
            .onSuccess { toast("Данные восстановлены") }
            .onFailure { toast("Это не похоже на резервную копию приложения") }
    }
    var confirmRestore by remember { mutableStateOf(false) }

    Text("Резервная копия", style = MaterialTheme.typography.titleMedium)
    Text(
        "Лекарства и вся история в одном файле — сохрани его в облако или отправь себе, чтобы перенести на новый телефон.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { save.launch("таблетки-${LocalDate.now()}.json") }, Modifier.weight(1f)) { Text("Сохранить") }
        OutlinedButton(onClick = { confirmRestore = true }, Modifier.weight(1f)) { Text("Восстановить") }
    }
    if (confirmRestore) {
        AlertDialog(
            onDismissRequest = { confirmRestore = false },
            title = { Text("Восстановить из файла?") },
            text = { Text("Текущие лекарства и история заменятся данными из копии.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmRestore = false
                    restore.launch(arrayOf("application/json", "application/octet-stream", "text/plain"))
                }) { Text("Выбрать файл") }
            },
            dismissButton = { TextButton(onClick = { confirmRestore = false }) { Text("Отмена") } },
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
        TextButton(onClick = onDone) { Text("← Назад") }
        Text(if (isNew) "Новое лекарство" else "Изменить", style = MaterialTheme.typography.headlineMedium)

        OutlinedTextField(
            name, { name = it },
            label = { Text("Название") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            dose, { dose = it },
            label = { Text("Доза") },
            placeholder = { Text("например, 1 таблетка или 50 мг") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            note, { note = it },
            label = { Text("Заметка") },
            placeholder = { Text("например, до еды, запивать водой") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Text("Время приёма", style = MaterialTheme.typography.titleMedium)
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
                label = { Text("+ Добавить время") },
            )
        }

        Text("Как часто?", style = MaterialTheme.typography.titleMedium)
        Column {
            Freq.entries.forEach { f -> Choice(f.title, freq == f) { freq = f } }
        }
        if (freq == Freq.WEEKDAYS) {
            Text("Отметь дни, в которые нужно пить:", color = MaterialTheme.colorScheme.onSurfaceVariant)
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

        Text("Сколько пить?", style = MaterialTheme.typography.titleMedium)
        Column {
            Choice("Постоянно", !isCourse) { isCourse = false }
            Choice("Курсом — определённое число дней", isCourse) { isCourse = true }
        }
        if (isCourse) {
            OutlinedTextField(
                courseText, { v -> courseText = v.filter(Char::isDigit).take(4) },
                label = { Text("Дней в курсе") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Начать с", Modifier.weight(1f))
            TextButton(onClick = {
                DatePickerDialog(c, { _, y, m, d -> start = LocalDate.of(y, m + 1, d) },
                    start.year, start.monthValue - 1, start.dayOfMonth).show()
            }) { Text(if (start == LocalDate.now()) "сегодня" else start.dayMonth()) }
        }
        if (isCourse && courseDays != null) {
            Text(
                "Последний день: ${start.plusDays(courseDays - 1L).dayMonth()}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Следить за остатком", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Напомню купить, когда останется на ${Store.LOW_STOCK_DAYS} дней",
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
                    label = { Text("Сейчас есть, шт.") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    perDoseText, { v -> perDoseText = v.filter(Char::isDigit).take(3) },
                    label = { Text("За приём, шт.") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
            }
        }

        val problem = when {
            name.isBlank() -> "Введи название."
            times.isEmpty() -> "Добавь хотя бы одно время приёма."
            freq == Freq.WEEKDAYS && days.isEmpty() -> "Отметь хотя бы один день."
            isCourse && courseDays == null -> "Укажи, сколько дней длится курс."
            trackStock && stock == null -> "Укажи, сколько штук сейчас есть."
            trackStock && perDose == null -> "Укажи, сколько штук за один приём."
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
        ) { Text("Сохранить") }
        problem?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (!isNew) {
            OutlinedButton(onClick = { confirmDelete = true }, Modifier.fillMaxWidth()) { Text("Удалить") }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Удалить «${initial.name}»?") },
            text = { Text("Напоминания о нём прекратятся.") },
            confirmButton = {
                TextButton(onClick = {
                    Store.deleteMed(c, initial.id)
                    onDone()
                }) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Отмена") } },
        )
    }
}

private enum class Freq(val title: String) {
    DAILY("Каждый день"),
    ALTERNATE("Через день"),
    WEEKDAYS("По дням недели"),
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

    Text("История", style = MaterialTheme.typography.headlineMedium)
    val days = Store.pastDays(c, meds, today, 30)
    val streak = Store.streak(c, meds, today)
    val stats = Store.stats(c, days)

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                if (streak > 0) "🔥 $streak ${daysWord(streak)} без пропусков" else "Серия без пропусков пока не началась",
                style = MaterialTheme.typography.titleMedium,
            )
            if (stats.total > 0) {
                Text("За ${days.size} ${daysWord(days.size)}: выпито ${stats.done} из ${stats.total} (${stats.done * 100 / stats.total}%)")
                stats.avgShift?.let { Text("В среднем отклонение от графика — ${minutesText(it)}") }
            }
            TextButton(onClick = {
                c.startActivity(
                    Intent.createChooser(
                        Intent(Intent.ACTION_SEND).setType("text/plain")
                            .putExtra(Intent.EXTRA_SUBJECT, "Приём лекарств")
                            .putExtra(Intent.EXTRA_TEXT, Store.report(c)),
                        "Отправить отчёт",
                    )
                )
            }) { Text("📤 Отправить отчёт врачу") }
        }
    }

    if (days.isEmpty()) {
        Text("Здесь появятся прошлые дни.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    days.forEach { (d, intakes) ->
        val done = intakes.count { Store.takenAt(c, it) != null }
        Column(Modifier.padding(top = 8.dp)) {
            Text(
                "${d.format(dayFmt)} — $done из ${intakes.size}",
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
