package app.pillcheck

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.UUID

data class Med(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val dose: String = "",
    val times: List<LocalTime> = emptyList(),
    val days: Set<DayOfWeek> = DayOfWeek.entries.toSet(),
    /** 1 — каждый день, 2 — через день (считая от [start]). */
    val interval: Int = 1,
    val start: LocalDate = LocalDate.now(),
    /** Длительность курса в днях; null — принимать постоянно. */
    val courseDays: Int? = null,
    /** Заметка к приёму: «до еды», «запивать водой». */
    val note: String = "",
    /** Сколько штук осталось в упаковке; null — остаток не отслеживается. */
    val stock: Int? = null,
    /** Сколько штук за один приём — на столько уменьшается [stock]. */
    val perDose: Int = 1,
) {
    /** Последний день курса. */
    val end: LocalDate? get() = courseDays?.let { start.plusDays(it - 1L) }

    /** Сколько штук уходит в среднем за день. */
    private val perDay: Double get() = times.size * perDose * days.size / 7.0 / interval

    /** На сколько дней хватит остатка; null — остаток не отслеживается. */
    fun daysLeft(): Int? = stock?.let { if (perDay > 0) (it / perDay).toInt() else null }

    val lowStock: Boolean get() = (daysLeft() ?: Int.MAX_VALUE) <= Store.LOW_STOCK_DAYS

    fun stockText(): String? = stock?.let { s -> "осталось $s шт." + (daysLeft()?.let { ", примерно на $it дн." } ?: "") }

    fun scheduledOn(date: LocalDate): Boolean =
        !date.isBefore(start) && (end == null || !date.isAfter(end)) &&
            date.dayOfWeek in days && ChronoUnit.DAYS.between(start, date) % interval == 0L
}

/** Дата начала для лекарств, добавленных до появления курсов. LocalDate.EPOCH есть только с Android 14. */
val LONG_AGO: LocalDate = LocalDate.of(1970, 1, 1)

/** Один конкретный приём: лекарство в конкретный день и время. */
data class Intake(val med: Med, val at: LocalDateTime) {
    val key get() = "${med.id}_$at"
}

/**
 * Лекарства и отметки о приёме. Лежит в SharedPreferences: список лекарств одной JSON-строкой,
 * отметка о приёме — ключ `taken_<приём>` со временем, когда нажали «Выпил».
 */
object Store {
    /** Как часто повторять напоминание, пока приём не отмечен. */
    const val NAG_MINUTES = 10L

    /** Через сколько часов перестать напоминать и считать приём пропущенным. */
    const val GIVE_UP_HOURS = 12L

    /** На сколько откладывает кнопка «Через 15 мин». */
    const val SNOOZE_MINUTES = 15L

    /** Когда остатка хватает на столько дней или меньше — пора покупать. */
    const val LOW_STOCK_DAYS = 5

    fun prefs(c: Context) = c.getSharedPreferences("doses", Context.MODE_PRIVATE)

    /** Когда последний раз звенели по каждому висящему приёму — отдельно, чтобы не дёргать экран. */
    fun alerts(c: Context) = c.getSharedPreferences("alerts", Context.MODE_PRIVATE)

    fun meds(c: Context): List<Med> = JSONArray(prefs(c).getString("meds", "[]")).let { a ->
        (0 until a.length()).map { fromJson(a.getJSONObject(it)) }
    }

    fun saveMed(c: Context, m: Med) {
        val list = meds(c)
        writeMeds(c, if (list.any { it.id == m.id }) list.map { if (it.id == m.id) m else it } else list + m)
    }

    fun deleteMed(c: Context, id: String) = writeMeds(c, meds(c).filter { it.id != id })

    private fun writeMeds(c: Context, list: List<Med>) =
        prefs(c).edit().putString("meds", JSONArray(list.map(::toJson)).toString()).apply()

    private fun toJson(m: Med) = JSONObject()
        .put("id", m.id)
        .put("name", m.name)
        .put("dose", m.dose)
        .put("times", JSONArray(m.times.map { it.toString() }))
        .put("days", JSONArray(m.days.map { it.value }))
        .put("interval", m.interval)
        .put("start", m.start.toString())
        .apply { m.courseDays?.let { put("courseDays", it) } }
        .put("note", m.note)
        .apply { m.stock?.let { put("stock", it) } }
        .put("perDose", m.perDose)

    private fun fromJson(o: JSONObject) = Med(
        id = o.getString("id"),
        name = o.getString("name"),
        dose = o.optString("dose"),
        times = o.getJSONArray("times").let { a -> (0 until a.length()).map { LocalTime.parse(a.getString(it)) } },
        days = o.getJSONArray("days").let { a -> (0 until a.length()).map { DayOfWeek.of(a.getInt(it)) }.toSet() },
        interval = o.optInt("interval", 1),
        // У лекарств, добавленных до появления курсов, даты начала нет — считаем, что давно.
        start = o.optString("start").ifEmpty { null }?.let(LocalDate::parse) ?: LONG_AGO,
        courseDays = if (o.has("courseDays")) o.getInt("courseDays") else null,
        note = o.optString("note"),
        stock = if (o.has("stock")) o.getInt("stock") else null,
        perDose = o.optInt("perDose", 1),
    )

    fun takenAt(c: Context, i: Intake): LocalDateTime? =
        prefs(c).getLong("taken_${i.key}", 0).takeIf { it > 0 }?.let(::fromMillis)

    /** Отмечает приём (или снимает отметку) и списывает таблетки из остатка (или возвращает). */
    fun setTaken(c: Context, key: String, taken: Boolean) {
        val p = prefs(c)
        if (p.contains("taken_$key") == taken) return
        p.edit().apply {
            if (taken) putLong("taken_$key", System.currentTimeMillis()) else remove("taken_$key")
        }.apply()
        val med = meds(c).find { it.id == key.substringBefore('_') } ?: return
        med.stock?.let { s -> saveMed(c, med.copy(stock = (if (taken) s - med.perDose else s + med.perDose).coerceAtLeast(0))) }
    }

    /** Приёмы на день по расписанию, по порядку. */
    fun intakesOn(meds: List<Med>, date: LocalDate): List<Intake> = meds
        .filter { it.scheduledOn(date) }
        .flatMap { m -> m.times.map { Intake(m, date.atTime(it)) } }
        .sortedBy { it.at }

    /** Приёмы, время которых наступило, а отметки нет и напоминать ещё не поздно. */
    fun pending(c: Context, meds: List<Med>, now: LocalDateTime): List<Intake> =
        (intakesOn(meds, now.toLocalDate().minusDays(1)) + intakesOn(meds, now.toLocalDate()))
            .filter { it.at <= now && Duration.between(it.at, now).toHours() < GIVE_UP_HOURS && takenAt(c, it) == null }

    /** День первого запуска — раньше него история не показывается. */
    fun since(c: Context): LocalDate =
        prefs(c).getString("since", null)?.let(LocalDate::parse)
            ?: LocalDate.now().also { prefs(c).edit().putString("since", it.toString()).apply() }

    /** Прошлые дни с приёмами, от вчера назад, не раньше первого запуска. */
    fun pastDays(c: Context, meds: List<Med>, today: LocalDate, limit: Int): List<Pair<LocalDate, List<Intake>>> {
        val since = since(c)
        return generateSequence(today.minusDays(1)) { it.minusDays(1) }
            .takeWhile { !it.isBefore(since) }
            .take(limit)
            .map { it to intakesOn(meds, it) }
            .filter { it.second.isNotEmpty() }
            .toList()
    }

    /** Сколько дней подряд выпито всё: от вчера назад, плюс сегодня, если сегодня уже всё отмечено. */
    fun streak(c: Context, meds: List<Med>, today: LocalDate): Int {
        val todayDone = intakesOn(meds, today).let { l -> l.isNotEmpty() && l.all { takenAt(c, it) != null } }
        val past = pastDays(c, meds, today, Int.MAX_VALUE).takeWhile { (_, l) -> l.all { takenAt(c, it) != null } }.size
        return past + if (todayDone) 1 else 0
    }

    class Stats(val total: Int, val done: Int, val avgShift: Long?)

    /** Сводка за прошлые дни: сколько приёмов выпито и насколько в среднем сдвигались от графика. */
    fun stats(c: Context, days: List<Pair<LocalDate, List<Intake>>>): Stats {
        val all = days.flatMap { it.second }
        val shifts = all.mapNotNull { i -> takenAt(c, i)?.let { Duration.between(i.at, it).toMinutes() } }
        return Stats(all.size, shifts.size, shifts.takeIf { it.isNotEmpty() }?.map { kotlin.math.abs(it) }?.average()?.toLong())
    }

    /** Текстовый отчёт за последние [limit] дней — чтобы отправить врачу. */
    fun report(c: Context, limit: Int = 30): String {
        val meds = meds(c)
        val days = pastDays(c, meds, LocalDate.now(), limit)
        val s = stats(c, days)
        return buildString {
            append("Приём лекарств")
            if (days.isNotEmpty()) append(": ${days.last().first.dayMonth()} — ${days.first().first.dayMonth()}")
            append("\n\nЛекарства:\n")
            meds.forEach { m ->
                append("• ${m.name}")
                if (m.dose.isNotBlank()) append(", ${m.dose}")
                append(" — ${m.times.joinToString { it.fmt() }}, ${scheduleText(m)}")
                if (m.note.isNotBlank()) append(" (${m.note})")
                append("\n")
            }
            if (s.total > 0) {
                append("\nВыпито ${s.done} из ${s.total} (${s.done * 100 / s.total}%)\n")
                s.avgShift?.let { append("Среднее отклонение от графика: ${minutesText(it)}\n") }
            }
            val missed = days.flatMap { it.second }.filter { takenAt(c, it) == null }.reversed()
            if (missed.isNotEmpty()) {
                append("\nПропущено:\n")
                missed.forEach { append("${it.at.toLocalDate().dayMonth()} ${it.at.fmt()} — ${it.med.name}\n") }
            }
            append("\nПо дням:\n")
            days.reversed().forEach { (d, intakes) ->
                append("${d.dayMonth()}: ")
                append(intakes.joinToString("; ") { i ->
                    val t = takenAt(c, i)
                    "${i.at.fmt()} ${i.med.name} " + if (t == null) "— пропущено" else "— ${t.fmt()}${shiftText(i.at, t)}"
                })
                append("\n")
            }
        }
    }

    /** Все данные одним JSON — для резервной копии в файл. */
    fun exportJson(c: Context): String = JSONObject().apply {
        prefs(c).all.forEach { (k, v) -> put(k, v) }
    }.toString(2)

    /** Заменяет все данные содержимым резервной копии. Бросает исключение, если файл не тот. */
    fun importJson(c: Context, text: String) {
        val o = JSONObject(text)
        JSONArray(o.getString("meds")).let { a -> (0 until a.length()).forEach { fromJson(a.getJSONObject(it)) } }
        prefs(c).edit().clear().apply {
            o.keys().forEach { k ->
                when {
                    k.startsWith("taken_") -> putLong(k, o.getLong(k))
                    k == "meds" || k == "since" -> putString(k, o.getString(k))
                }
            }
        }.apply()
    }
}

fun LocalDateTime.millis(): Long = atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
fun fromMillis(ms: Long): LocalDateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneId.systemDefault())

private val hhmm = DateTimeFormatter.ofPattern("HH:mm")

fun LocalTime.fmt(): String = format(hhmm)
fun LocalDateTime.fmt(): String = format(hhmm)

fun DayOfWeek.short(): String =
    getDisplayName(TextStyle.SHORT, Locale.getDefault()).replaceFirstChar { it.titlecase() }

private val dayMonth = DateTimeFormatter.ofPattern("d MMMM")

fun LocalDate.dayMonth(): String = format(dayMonth)

fun scheduleText(m: Med): String = when {
    m.interval == 2 -> "через день"
    m.days.size == 7 -> "каждый день"
    else -> DayOfWeek.entries.filter { it in m.days }.joinToString { it.short() }
}

/** «день 5 из 14, до 28 октября»; null, если лекарство без курса. */
fun courseText(m: Med, today: LocalDate): String? {
    val days = m.courseDays ?: return null
    val end = m.end!!
    return when {
        today.isBefore(m.start) -> "курс $days дн. с ${m.start.dayMonth()}"
        today.isAfter(end) -> "курс завершён ${end.dayMonth()}"
        today == end -> "последний день курса"
        else -> "день ${ChronoUnit.DAYS.between(m.start, today) + 1} из $days, до ${end.dayMonth()}"
    }
}

/** 1 день, 2 дня, 5 дней, 21 день. */
fun daysWord(n: Int): String = when {
    n % 100 in 11..14 -> "дней"
    n % 10 == 1 -> "день"
    n % 10 in 2..4 -> "дня"
    else -> "дней"
}

/** 47 → «47 мин», 130 → «2 ч 10 мин». */
fun minutesText(m: Long): String = when {
    m < 60 -> "$m мин"
    m % 60 == 0L -> "${m / 60} ч"
    else -> "${m / 60} ч ${m % 60} мин"
}

/** Насколько приём сдвинулся от графика: « (+47 мин)», пусто если в пределах 5 минут. */
fun shiftText(due: LocalDateTime, taken: LocalDateTime): String {
    val m = Duration.between(due, taken).toMinutes()
    return when {
        m > 5 -> " (+${minutesText(m)})"
        m < -5 -> " (−${minutesText(-m)})"
        else -> ""
    }
}
