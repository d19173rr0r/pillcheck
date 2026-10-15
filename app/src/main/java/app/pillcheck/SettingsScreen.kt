package app.pillcheck

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.material3.OutlinedButton
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.material3.dynamicDarkColorScheme

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(version: Int) {
    val c = LocalContext.current
    val s = remember(version) { Themes.load(c) }
    fun save(new: ThemeSettings) = Themes.save(c, new)
    var customOpen by remember { mutableStateOf(false) }

    Text(str(R.string.tab_settings), style = MaterialTheme.typography.headlineMedium)

    Text(str(R.string.theme_title), style = MaterialTheme.typography.titleMedium)
    Column {
        ThemeMode.entries.forEach { m -> Choice(str(m.title), s.mode == m) { save(s.copy(mode = m)) } }
    }

    Text(str(R.string.color_title), style = MaterialTheme.typography.titleMedium)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Swatch(
            Brush.sweepGradient(listOf(Color(0xFFE53935), Color(0xFFFDD835), Color(0xFF43A047), Color(0xFF1E88E5), Color(0xFF8E24AA), Color(0xFFE53935))),
            mark = dynamicDarkColorScheme(c).primary,
            selected = s.accent == null,
        ) { save(s.copy(accent = null)) }
        Themes.swatches.forEach { argb ->
            Swatch(Brush.linearGradient(listOf(Color(argb), Color(argb))), Color(argb), s.accent == argb) {
                save(s.copy(accent = argb))
            }
        }
        val custom = s.accent?.takeIf { it !in Themes.swatches }
        Swatch(
            Brush.linearGradient(listOf(Color(custom ?: 0xFF9E9E9E.toInt()), Color(custom ?: 0xFF424242.toInt()))),
            Color(custom ?: 0xFF9E9E9E.toInt()),
            selected = custom != null,
            label = "+",
        ) { customOpen = true }
    }
    Text(
        when {
            s.accent == null -> str(R.string.color_wallpaper)
            s.accent in Themes.swatches -> str(R.string.color_pick_hint)
            else -> str(R.string.color_custom)
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Text(str(R.string.widget_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
    Column {
        WidgetStyle.entries.forEach { w ->
            Choice(str(w.title), s.widgetStyle == w) { save(s.copy(widgetStyle = w)) }
            Text(
                str(w.hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 32.dp, bottom = 8.dp),
            )
        }
    }
    var alpha by remember(s.widgetAlpha) { mutableFloatStateOf(s.widgetAlpha.toFloat()) }
    Text(str(R.string.widget_opacity, alpha.toInt()))
    Slider(
        alpha, { alpha = it },
        valueRange = 0f..100f,
        onValueChangeFinished = { save(s.copy(widgetAlpha = alpha.toInt())) },
    )
    Text(
        str(R.string.widget_resize_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    val wm = AppWidgetManager.getInstance(c)
    if (wm.isRequestPinAppWidgetSupported) {
        OutlinedButton(
            onClick = { wm.requestPinAppWidget(ComponentName(c, WidgetProvider::class.java), null, null) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(str(R.string.widget_add)) }
    }

    // Выбор языка отдельно для приложения есть в системе с Android 13.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Text(str(R.string.language_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
        Text(
            str(R.string.language_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(
            onClick = {
                c.startActivity(Intent(Settings.ACTION_APP_LOCALE_SETTINGS, Uri.parse("package:${c.packageName}")))
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(str(R.string.language_button)) }
    }

    Backup()

    if (customOpen) {
        CustomColorDialog(s.accent ?: MaterialTheme.colorScheme.primary.toArgb(), onDismiss = { customOpen = false }) {
            save(s.copy(accent = it))
            customOpen = false
        }
    }
}

@Composable
private fun Swatch(fill: Brush, mark: Color, selected: Boolean, label: String? = null, onClick: () -> Unit) {
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(fill)
            .border(if (selected) 3.dp else 0.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        val text = if (selected) "✓" else label
        if (text != null) Text(text, color = if (mark.luminance() > 0.5f) Color.Black else Color.White, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun CustomColorDialog(initial: Int, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    val hsv = remember { FloatArray(3).also { android.graphics.Color.colorToHSV(initial, it) } }
    var h by remember { mutableFloatStateOf(hsv[0]) }
    var sat by remember { mutableFloatStateOf(hsv[1]) }
    var v by remember { mutableFloatStateOf(hsv[2].coerceAtLeast(0.2f)) }
    val color = Color.hsv(h, sat, v)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(str(R.string.color_custom)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    Box(Modifier.size(72.dp).clip(CircleShape).background(color))
                }
                Text(str(R.string.hue))
                Slider(h, { h = it }, valueRange = 0f..359f)
                Text(str(R.string.saturation))
                Slider(sat, { sat = it }, valueRange = 0f..1f)
                Text(str(R.string.brightness))
                Slider(v, { v = it }, valueRange = 0.2f..1f)
            }
        },
        confirmButton = { TextButton(onClick = { onPick(color.toArgb()) }) { Text(str(R.string.btn_done)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(str(R.string.btn_cancel)) } },
    )
}
