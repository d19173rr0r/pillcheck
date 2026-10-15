package app.pillcheck

import android.content.Context
import android.content.res.Configuration
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.core.graphics.ColorUtils

enum class WidgetStyle(val title: String, val hint: String) {
    RING_LIST("Кольцо и список", "прогресс и все приёмы с кнопками"),
    LIST("Список", "приёмы с галочками: что выпито, что ещё ждёт"),
    PILLS("Таблетки", "ряд капсул: выпитые закрашены, нажми на капсулу — отметится"),
    RING("Только кольцо", "сколько выпито и когда следующий"),
}

enum class ThemeMode(val title: String) {
    SYSTEM("Как в системе"),
    LIGHT("Светлая"),
    DARK("Тёмная"),
    BLACK("Чёрная (AMOLED)"),
}

data class ThemeSettings(
    val mode: ThemeMode = ThemeMode.SYSTEM,
    /** Цвет акцента; null — брать цвета из обоев (Material You). */
    val accent: Int? = null,
    /** Непрозрачность фона виджета, 0–100. */
    val widgetAlpha: Int = 90,
    val widgetStyle: WidgetStyle = WidgetStyle.RING_LIST,
)

object Themes {
    val swatches = listOf(
        0xFFE53935, 0xFFFB8C00, 0xFFFDD835, 0xFF43A047, 0xFF00897B, 0xFF039BE5,
        0xFF1E88E5, 0xFF3949AB, 0xFF8E24AA, 0xFFD81B60, 0xFF6D4C41, 0xFF757575,
    ).map { it.toInt() }

    fun load(c: Context): ThemeSettings {
        val p = Store.prefs(c)
        return ThemeSettings(
            mode = p.getString("theme_mode", null)?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM,
            accent = if (p.contains("theme_accent")) p.getInt("theme_accent", 0) else null,
            widgetAlpha = p.getInt("widget_alpha", 90),
            widgetStyle = p.getString("widget_style", null)?.let { runCatching { WidgetStyle.valueOf(it) }.getOrNull() } ?: WidgetStyle.RING_LIST,
        )
    }

    fun save(c: Context, s: ThemeSettings) = Store.prefs(c).edit().apply {
        putString("theme_mode", s.mode.name)
        if (s.accent != null) putInt("theme_accent", s.accent) else remove("theme_accent")
        putInt("widget_alpha", s.widgetAlpha)
        putString("widget_style", s.widgetStyle.name)
    }.apply()

    fun isDark(c: Context, s: ThemeSettings): Boolean = when (s.mode) {
        ThemeMode.SYSTEM -> c.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        ThemeMode.LIGHT -> false
        ThemeMode.DARK, ThemeMode.BLACK -> true
    }

    fun scheme(c: Context, s: ThemeSettings, dark: Boolean): ColorScheme {
        val base = when {
            s.accent != null -> seeded(s.accent, dark)
            dark -> dynamicDarkColorScheme(c)
            else -> dynamicLightColorScheme(c)
        }
        return if (s.mode == ThemeMode.BLACK) base.black() else base
    }

    /** Чёрный фон, а карточки — чуть светлее, чтобы их было видно. */
    private fun ColorScheme.black() = copy(
        background = Color.Black,
        surface = Color.Black,
        surfaceDim = Color.Black,
        surfaceContainerLowest = Color.Black,
        surfaceContainerLow = Color(0xFF0B0B0B),
        surfaceContainer = Color(0xFF111111),
        surfaceContainerHigh = Color(0xFF171717),
        surfaceContainerHighest = Color(0xFF1E1E1E),
        surfaceVariant = Color(0xFF1E1E1E),
        surfaceBright = Color(0xFF262626),
    )

    /** Палитра из одного цвета: тот же оттенок разной светлоты, фоны — едва подкрашенные. */
    private fun seeded(argb: Int, dark: Boolean): ColorScheme {
        val hsl = FloatArray(3).also { ColorUtils.colorToHSL(argb, it) }
        val h = hsl[0]
        val s = hsl[1].coerceIn(0.05f, 0.9f)
        val h2 = (h + 60f) % 360f
        val n = s * 0.12f
        val nv = s * 0.25f
        fun t(l: Float, sat: Float = s, hue: Float = h) = Color.hsl(hue, sat, l)
        return if (!dark) lightColorScheme().copy(
            primary = t(0.40f), onPrimary = Color.White,
            primaryContainer = t(0.88f), onPrimaryContainer = t(0.12f),
            inversePrimary = t(0.80f), surfaceTint = t(0.40f),
            secondary = t(0.40f, s * 0.4f), onSecondary = Color.White,
            secondaryContainer = t(0.90f, s * 0.4f), onSecondaryContainer = t(0.12f, s * 0.4f),
            tertiary = t(0.40f, s * 0.5f, h2), onTertiary = Color.White,
            tertiaryContainer = t(0.90f, s * 0.5f, h2), onTertiaryContainer = t(0.12f, s * 0.5f, h2),
            background = t(0.98f, n), onBackground = t(0.10f, n),
            surface = t(0.98f, n), onSurface = t(0.10f, n),
            surfaceVariant = t(0.90f, nv), onSurfaceVariant = t(0.30f, nv),
            outline = t(0.50f, nv), outlineVariant = t(0.80f, nv),
            surfaceContainerLowest = Color.White, surfaceContainerLow = t(0.96f, n),
            surfaceContainer = t(0.94f, n), surfaceContainerHigh = t(0.92f, n), surfaceContainerHighest = t(0.90f, n),
            surfaceBright = t(0.98f, n), surfaceDim = t(0.87f, n),
            inverseSurface = t(0.19f, n), inverseOnSurface = t(0.95f, n),
        ) else darkColorScheme().copy(
            primary = t(0.80f), onPrimary = t(0.20f),
            primaryContainer = t(0.30f), onPrimaryContainer = t(0.90f),
            inversePrimary = t(0.40f), surfaceTint = t(0.80f),
            secondary = t(0.80f, s * 0.4f), onSecondary = t(0.20f, s * 0.4f),
            secondaryContainer = t(0.30f, s * 0.4f), onSecondaryContainer = t(0.90f, s * 0.4f),
            tertiary = t(0.80f, s * 0.5f, h2), onTertiary = t(0.20f, s * 0.5f, h2),
            tertiaryContainer = t(0.30f, s * 0.5f, h2), onTertiaryContainer = t(0.90f, s * 0.5f, h2),
            background = t(0.07f, n), onBackground = t(0.90f, n),
            surface = t(0.07f, n), onSurface = t(0.90f, n),
            surfaceVariant = t(0.30f, nv), onSurfaceVariant = t(0.80f, nv),
            outline = t(0.60f, nv), outlineVariant = t(0.30f, nv),
            surfaceContainerLowest = t(0.04f, n), surfaceContainerLow = t(0.10f, n),
            surfaceContainer = t(0.12f, n), surfaceContainerHigh = t(0.17f, n), surfaceContainerHighest = t(0.22f, n),
            surfaceBright = t(0.24f, n), surfaceDim = t(0.07f, n),
            inverseSurface = t(0.90f, n), inverseOnSurface = t(0.19f, n),
        )
    }
}
