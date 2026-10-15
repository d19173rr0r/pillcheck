package app.pillcheck

import android.Manifest
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import android.graphics.Color
import androidx.activity.SystemBarStyle
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    // Растёт при любом изменении данных (в том числе из уведомления) — экраны перечитывают Store.
    private var version by mutableIntStateOf(0)
    private val onChanged = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        Reminders.sync(this, alarm = false)
        version++
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Store.since(this)
        Store.prefs(this).registerOnSharedPreferenceChangeListener(onChanged)

        val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
        // Разрешение на уведомления появилось в Android 13; раньше они разрешены по умолчанию.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            val settings = remember(version) { Themes.load(this) }
            val dark = when (settings.mode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK, ThemeMode.BLACK -> true
            }
            // Иконки в строке состояния — тёмные на светлой теме и светлые на тёмной.
            LaunchedEffect(dark) {
                val bars = if (dark) SystemBarStyle.dark(Color.TRANSPARENT)
                else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                enableEdgeToEdge(bars, bars)
            }
            MaterialTheme(colorScheme = Themes.scheme(this, settings, dark)) {
                Surface(Modifier.fillMaxSize()) { App(version) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        Reminders.sync(this, alarm = false)
        version++ // время и разрешения могли поменяться, пока экран был скрыт
    }

    override fun onDestroy() {
        Store.prefs(this).unregisterOnSharedPreferenceChangeListener(onChanged)
        super.onDestroy()
    }
}

private enum class Tab(val title: Int, val icon: String) {
    TODAY(R.string.tab_today, "📅"),
    MEDS(R.string.tab_meds, "💊"),
    HISTORY(R.string.tab_history, "📊"),
    SETTINGS(R.string.tab_settings, "⚙️"),
}

@Composable
private fun App(version: Int) {
    var tab by rememberSaveable { mutableStateOf(Tab.TODAY) }
    var editing by remember { mutableStateOf<Med?>(null) }

    editing?.let { med ->
        BackHandler { editing = null }
        MedEditor(med, onDone = { editing = null })
        return
    }

    Scaffold(bottomBar = {
        NavigationBar {
            Tab.entries.forEach { t ->
                NavigationBarItem(
                    selected = tab == t,
                    onClick = { tab = t },
                    icon = { Text(t.icon) },
                    label = { Text(str(t.title)) },
                )
            }
        }
    }) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (tab) {
                Tab.TODAY -> TodayScreen(version, onAdd = { editing = Med() }, onEdit = { editing = it })
                Tab.MEDS -> MedsScreen(version, onEdit = { editing = it })
                Tab.HISTORY -> HistoryScreen(version)
                Tab.SETTINGS -> SettingsScreen(version)
            }
        }
    }
}
