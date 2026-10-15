package app.pillcheck

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import java.time.Duration
import java.time.LocalDateTime

/**
 * Виджет на рабочем столе: сегодняшние приёмы, раскрашенные по теме приложения.
 * Под размер подбирается макет: маленький — только кольцо прогресса, широкий низкий — кольцо и подпись,
 * большой — ещё и список, где кружок справа отмечает приём.
 * Обновляется при каждом [Reminders.sync].
 */
object Widget {
    fun update(c: Context) {
        val wm = AppWidgetManager.getInstance(c)
        val ids = wm.getAppWidgetIds(ComponentName(c, WidgetProvider::class.java))
        if (ids.isEmpty()) return

        val settings = Themes.load(c)
        val cs = Themes.scheme(c, settings, Themes.isDark(c, settings))
        val now = LocalDateTime.now()
        val intakes = Store.intakesOn(Store.meds(c), now.toLocalDate())
        val taken = intakes.associateWith { Store.takenAt(c, it) }
        val state = State(
            done = taken.values.count { it != null },
            total = intakes.size,
            subtitle = subtitle(intakes, taken, now),
            alarm = intakes.any { taken[it] == null && it.at <= now },
        )
        val bg = cs.surface.copy(alpha = settings.widgetAlpha / 100f)

        fun base(layout: Int, ring: Boolean = true) = header(c, layout, cs, bg, state, ring)
        val small = base(R.layout.widget_small)
        // Лаунчер сам выбирает самый крупный вариант, который влезает в размер виджета (в dp).
        val sizes = when (settings.widgetStyle) {
            WidgetStyle.RING_LIST, WidgetStyle.LIST -> {
                val ring = settings.widgetStyle == WidgetStyle.RING_LIST
                mapOf(
                    SizeF(50f, 50f) to small,
                    SizeF(130f, 50f) to base(R.layout.widget, ring).apply { setViewVisibility(R.id.widget_list, View.GONE) },
                    SizeF(130f, 100f) to base(R.layout.widget, ring).apply { list(c, this, cs, intakes, taken, now) },
                )
            }
            WidgetStyle.PILLS -> mapOf(
                SizeF(50f, 50f) to small,
                SizeF(130f, 90f) to base(R.layout.widget_pills, ring = false).apply { pills(c, this, cs, intakes, taken, now) },
            )
            WidgetStyle.RING -> mapOf(
                SizeF(50f, 50f) to small,
                SizeF(120f, 140f) to base(R.layout.widget_ring_big),
            )
        }
        wm.updateAppWidget(ids, RemoteViews(sizes))
    }

    private class State(val done: Int, val total: Int, val subtitle: String, val alarm: Boolean)

    private val pillSlots = listOf(R.id.pill_slot_0, R.id.pill_slot_1, R.id.pill_slot_2, R.id.pill_slot_3, R.id.pill_slot_4, R.id.pill_slot_5)
    private val pillIcons = listOf(R.id.pill_0, R.id.pill_1, R.id.pill_2, R.id.pill_3, R.id.pill_4, R.id.pill_5)
    private val pillChecks = listOf(R.id.pill_check_0, R.id.pill_check_1, R.id.pill_check_2, R.id.pill_check_3, R.id.pill_check_4, R.id.pill_check_5)
    private val pillTimes = listOf(R.id.pill_time_0, R.id.pill_time_1, R.id.pill_time_2, R.id.pill_time_3, R.id.pill_time_4, R.id.pill_time_5)

    /** Ряд капсул: выпитая — закрашена и с галочкой, ждущая — контур, пропущенная — красный контур. */
    private fun pills(
        c: Context, views: RemoteViews, cs: ColorScheme,
        intakes: List<Intake>, taken: Map<Intake, LocalDateTime?>, now: LocalDateTime,
    ) {
        // Больше шести не влезает — показываем шесть ближайших к текущему времени.
        val shown = if (intakes.size <= 6) intakes
        else intakes.sortedBy { kotlin.math.abs(Duration.between(now, it.at).toMinutes()) }.take(6).sortedBy { it.at }
        pillSlots.forEachIndexed { n, slot ->
            val i = shown.getOrNull(n)
            views.setViewVisibility(slot, if (i == null) View.GONE else View.VISIBLE)
            if (i == null) return@forEachIndexed
            val t = taken[i]
            val missed = t == null && i.at <= now
            views.setImageViewResource(pillIcons[n], if (t != null) R.drawable.capsule_filled else R.drawable.capsule_outline)
            views.tint(pillIcons[n], "setImageTintList", if (t != null) cs.primary else if (missed) cs.error else cs.outline)
            views.setViewVisibility(pillChecks[n], if (t != null) View.VISIBLE else View.GONE)
            views.tint(pillChecks[n], "setImageTintList", cs.onPrimary)
            views.setTextViewText(pillTimes[n], i.at.fmt())
            views.setTextColor(pillTimes[n], (if (missed) cs.error else cs.onSurfaceVariant).toArgb())
            if (t == null) views.setOnClickPendingIntent(slot, Reminders.broadcast(c, Reminders.ACTION_TAKEN, i.key))
        }
    }

    private fun subtitle(intakes: List<Intake>, taken: Map<Intake, LocalDateTime?>, now: LocalDateTime): String {
        val missed = intakes.filter { taken[it] == null && it.at <= now }
        val next = intakes.firstOrNull { taken[it] == null && it.at > now }
        return when {
            intakes.isEmpty() -> str(R.string.w_none)
            missed.isNotEmpty() -> str(R.string.w_due, missed.joinToString { it.at.fmt() })
            next != null -> str(R.string.w_next, next.at.fmt(), next.med.name)
            else -> str(R.string.w_all_done)
        }
    }

    private fun header(c: Context, layout: Int, cs: ColorScheme, bg: Color, s: State, ring: Boolean) =
        RemoteViews(c.packageName, layout).apply {
            tint(android.R.id.background, "setBackgroundTintList", bg)
            setOnClickPendingIntent(android.R.id.background, Reminders.openApp(c))

            val count = if (s.total == 0) "—" else "${s.done}/${s.total}"
            if (ring) {
                setProgressBar(R.id.widget_ring, 100, if (s.total == 0) 0 else s.done * 100 / s.total, false)
                tint(R.id.widget_ring, "setProgressTintList", if (s.alarm) cs.error else cs.primary)
                tint(R.id.widget_ring, "setProgressBackgroundTintList", cs.surfaceContainerHighest)
                setTextViewText(R.id.widget_count, count)
                setTextColor(R.id.widget_count, cs.onSurface.toArgb())
            } else if (layout == R.layout.widget) {
                setViewVisibility(R.id.widget_ring_box, View.GONE)
            }

            setTextViewText(R.id.widget_subtitle, s.subtitle)
            setTextColor(R.id.widget_subtitle, (if (s.alarm) cs.error else cs.onSurfaceVariant).toArgb())
            if (layout == R.layout.widget || layout == R.layout.widget_pills) {
                setTextViewText(R.id.widget_title, if (ring || s.total == 0) str(R.string.tab_today) else str(R.string.w_title_count, count))
                setTextColor(R.id.widget_title, cs.onSurface.toArgb())
            }
        }

    private fun list(
        c: Context, views: RemoteViews, cs: ColorScheme,
        intakes: List<Intake>, taken: Map<Intake, LocalDateTime?>, now: LocalDateTime,
    ) {
        // Кружок в строке шлёт ReminderReceiver «выпил»; какой именно приём — дописывает строка.
        val template = PendingIntent.getBroadcast(
            c, 1,
            Intent(c, ReminderReceiver::class.java).setAction(Reminders.ACTION_TAKEN),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        views.setPendingIntentTemplate(R.id.widget_list, template)

        val items = RemoteViews.RemoteCollectionItems.Builder().setHasStableIds(true).setViewTypeCount(1)
        intakes.forEachIndexed { n, i ->
            val t = taken[i]
            val missed = t == null && i.at <= now
            val row = RemoteViews(c.packageName, R.layout.widget_row).apply {
                tint(R.id.row, "setBackgroundTintList", if (missed) cs.errorContainer else cs.surfaceContainerHigh)
                val text = if (missed) cs.onErrorContainer else cs.onSurface
                setTextViewText(R.id.row_time, i.at.fmt())
                setTextColor(R.id.row_time, (if (missed) cs.error else cs.primary).toArgb())
                setTextViewText(R.id.row_name, i.med.name)
                setTextColor(R.id.row_name, (if (t != null) text.copy(alpha = 0.6f) else text).toArgb())
                val details = if (t != null) str(R.string.w_taken_at, t.fmt()) else listOf(i.med.dose, i.med.note).filter { it.isNotBlank() }.joinToString(" · ")
                setTextViewText(R.id.row_dose, details)
                setViewVisibility(R.id.row_dose, if (details.isEmpty()) View.GONE else View.VISIBLE)
                setTextColor(R.id.row_dose, (if (missed) cs.onErrorContainer else cs.onSurfaceVariant).toArgb())

                if (t != null) {
                    setInt(R.id.row_check, "setBackgroundResource", R.drawable.circle_filled)
                    tint(R.id.row_check, "setBackgroundTintList", cs.primary)
                    tint(R.id.row_check, "setImageTintList", cs.onPrimary)
                } else {
                    setInt(R.id.row_check, "setBackgroundResource", R.drawable.circle_outline)
                    tint(R.id.row_check, "setBackgroundTintList", if (missed) cs.error else cs.outline)
                    tint(R.id.row_check, "setImageTintList", Color.Transparent)
                    setOnClickFillInIntent(R.id.row_check, Intent().putExtras(Bundle().apply { putString("key", i.key) }))
                }
            }
            items.addItem(n.toLong(), row)
        }
        views.setRemoteAdapter(R.id.widget_list, items.build())
    }

    private fun RemoteViews.tint(id: Int, method: String, color: Color) =
        setColorStateList(id, method, ColorStateList.valueOf(color.toArgb()))
}

class WidgetProvider : AppWidgetProvider() {
    override fun onUpdate(c: Context, wm: AppWidgetManager, ids: IntArray) = Widget.update(c)

    override fun onAppWidgetOptionsChanged(c: Context, wm: AppWidgetManager, id: Int, options: Bundle) = Widget.update(c)
}
