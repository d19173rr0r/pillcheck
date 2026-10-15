package app.pillcheck

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import java.time.Duration
import java.time.LocalDateTime

object Reminders {
    const val ACTION_FIRE = "app.pillcheck.FIRE"
    const val ACTION_TAKEN = "app.pillcheck.TAKEN"
    const val ACTION_TAKEN_ALL = "app.pillcheck.TAKEN_ALL"
    const val ACTION_SNOOZE = "app.pillcheck.SNOOZE"
    private const val CHANNEL = "doses_vibrate"
    private const val GROUP = "doses"
    private const val SUMMARY_TAG = "summary"

    /**
     * Приводит уведомления, будильник и виджет в соответствие с расписанием и отметками.
     * У каждого висящего приёма своё уведомление; звенит оно раз в [Store.NAG_MINUTES], и только
     * когда [alarm] — то есть сработал будильник или телефон перезагрузился, а не просто открыли приложение.
     * Будильник один на всё приложение: на ближайший момент, когда надо позвонить.
     */
    fun sync(c: Context, alarm: Boolean) {
        val now = LocalDateTime.now()
        val meds = Store.meds(c)
        val pending = Store.pending(c, meds, now)
        val keys = pending.map { it.key }.toSet()

        val nm = c.getSystemService(NotificationManager::class.java)
        // Настройки канала после создания не меняются — для новой вибрации нужен новый канал.
        nm.deleteNotificationChannel("doses")
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Приём лекарств", NotificationManager.IMPORTANCE_HIGH).apply {
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 700, 300, 700, 300, 700)
            }
        )
        nm.activeNotifications
            .filter { it.tag != null && it.tag != SUMMARY_TAG && it.tag !in keys }
            .forEach { nm.cancel(it.tag, it.id) }

        // Там же лежат отложенные: ключ "snooze:<приём>" — до какого момента молчать.
        val alerts = Store.alerts(c)
        alerts.edit().apply { alerts.all.keys.filter { it.removePrefix("snooze:") !in keys }.forEach(::remove) }.apply()

        val wakeAt = mutableListOf<Long>()
        val shown = mutableListOf<Intake>()
        for (i in pending) {
            val snoozedUntil = alerts.getLong("snooze:${i.key}", 0)
            if (snoozedUntil > now.millis()) {
                nm.cancel(i.key, 0)
                wakeAt += snoozedUntil
                continue
            }
            val last = alerts.getLong(i.key, 0).takeIf { it > 0 }?.let(::fromMillis)
            val alert = alarm && (last == null || !now.isBefore(last.plusMinutes(Store.NAG_MINUTES).minusSeconds(30)))
            if (alert) alerts.edit().putLong(i.key, now.millis()).apply()
            nm.notify(i.key, 0, notification(c, i, now, alert))
            wakeAt += (if (alert) now else last ?: now).plusMinutes(Store.NAG_MINUTES).millis()
            shown += i
        }
        if (shown.size >= 2) nm.notify(SUMMARY_TAG, 0, summary(c, shown)) else nm.cancel(SUMMARY_TAG, 0)

        (0L..60L).asSequence()
            .flatMap { Store.intakesOn(meds, now.toLocalDate().plusDays(it)) }
            .firstOrNull { it.at > now && Store.takenAt(c, it) == null }
            ?.let { wakeAt += it.at.millis() }
        // В полночь — чтобы виджет переключился на новый день.
        if (meds.isNotEmpty()) wakeAt += now.toLocalDate().plusDays(1).atStartOfDay().millis()

        val fire = PendingIntent.getBroadcast(
            c, 0,
            Intent(c, ReminderReceiver::class.java).setAction(ACTION_FIRE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val am = c.getSystemService(AlarmManager::class.java)
        val at = wakeAt.minOrNull()
        when {
            at == null -> am.cancel(fire)
            // Как у приложения «Часы»: срабатывает точно даже в глубоком сне телефона.
            am.canScheduleExactAlarms() -> am.setAlarmClock(AlarmManager.AlarmClockInfo(at, openApp(c)), fire)
            // Без разрешения на точные будильники система может сдвинуть срабатывание на несколько минут.
            else -> am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, fire)
        }

        Widget.update(c)
    }

    private fun notification(c: Context, i: Intake, now: LocalDateTime, alert: Boolean): Notification {
        val late = Duration.between(i.at, now).toMinutes()
        val timing = if (late < 1) "время приёма ${i.at.fmt()}"
        else "опоздание ${minutesText(late)}, напомню через ${Store.NAG_MINUTES} мин"
        val lines = listOfNotNull(
            listOf(i.med.dose, timing).filter { it.isNotBlank() }.joinToString(" · "),
            i.med.note.ifBlank { null },
            i.med.stockText()?.takeIf { i.med.lowStock }?.let { "⚠️ Пора купить: $it" },
        )
        return builder(c)
            .setContentTitle(i.med.name)
            .setContentText(lines.first())
            .setStyle(NotificationCompat.BigTextStyle().bigText(lines.joinToString("\n")))
            .setWhen(i.at.millis())
            .setShowWhen(true)
            .setSilent(!alert)
            .setGroup(GROUP)
            .addAction(0, "Выпил ✅", broadcast(c, ACTION_TAKEN, i.key))
            .addAction(0, "Через ${Store.SNOOZE_MINUTES} мин", broadcast(c, ACTION_SNOOZE, i.key))
            .build()
    }

    /** Общее уведомление, когда висят несколько приёмов сразу, — с кнопкой «Выпил всё». */
    private fun summary(c: Context, shown: List<Intake>): Notification {
        val all = PendingIntent.getBroadcast(
            c, ACTION_TAKEN_ALL.hashCode(),
            Intent(c, ReminderReceiver::class.java).setAction(ACTION_TAKEN_ALL)
                .putExtra("keys", shown.map { it.key }.toTypedArray()),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val title = "Пора принять: ${shown.size} шт."
        return builder(c)
            .setContentTitle(title)
            .setContentText(shown.joinToString { it.med.name })
            .setStyle(NotificationCompat.InboxStyle().also { s -> shown.forEach { s.addLine("${it.at.fmt()}  ${it.med.name}") } })
            .setGroup(GROUP)
            .setGroupSummary(true)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .addAction(0, "Выпил всё ✅", all)
            .build()
    }

    private fun builder(c: Context): NotificationCompat.Builder {
        // На экране блокировки — без названий лекарств.
        val public = NotificationCompat.Builder(c, CHANNEL)
            .setSmallIcon(R.drawable.ic_pill)
            .setContentTitle("Пора принять лекарство")
            .build()
        return NotificationCompat.Builder(c, CHANNEL)
            .setSmallIcon(R.drawable.ic_pill)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .setOngoing(true)
            .setContentIntent(openApp(c))
    }

    fun broadcast(c: Context, action: String, key: String): PendingIntent = PendingIntent.getBroadcast(
        c, (action + key).hashCode(),
        Intent(c, ReminderReceiver::class.java).setAction(action).putExtra("key", key),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun openApp(c: Context): PendingIntent = PendingIntent.getActivity(
        c, 0, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
    )
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        when (i.action) {
            Reminders.ACTION_TAKEN -> Store.setTaken(c, i.getStringExtra("key") ?: return, true)
            Reminders.ACTION_TAKEN_ALL -> i.getStringArrayExtra("keys")?.forEach { Store.setTaken(c, it, true) }
            Reminders.ACTION_SNOOZE -> {
                val key = i.getStringExtra("key") ?: return
                val until = LocalDateTime.now().plusMinutes(Store.SNOOZE_MINUTES).millis()
                // Без отметки о последнем звонке — после паузы зазвонит сразу.
                Store.alerts(c).edit().putLong("snooze:$key", until).remove(key).apply()
            }
        }
        // Будильник, перезагрузка, обновление, смена времени или разрешений — звенеть можно;
        // после нажатия кнопки в уведомлении — нет.
        val fromButton = i.action in setOf(Reminders.ACTION_TAKEN, Reminders.ACTION_TAKEN_ALL, Reminders.ACTION_SNOOZE)
        Reminders.sync(c, alarm = !fromButton)
    }
}
