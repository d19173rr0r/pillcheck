package app.pillcheck

import android.app.Application
import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import java.util.Locale

/** Держит контекст приложения, чтобы строки на нужном языке были доступны и вне экранов: в уведомлениях, виджете, отчёте. */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        ctx = this
    }

    companion object {
        lateinit var ctx: Context
    }
}

/** Текущий язык приложения — для дат и названий дней недели. */
fun appLocale(): Locale = App.ctx.resources.configuration.locales[0]

fun str(@StringRes id: Int, vararg args: Any): String = App.ctx.getString(id, *args)

fun plural(@PluralsRes id: Int, n: Int, vararg args: Any): String =
    App.ctx.resources.getQuantityString(id, n, *(if (args.isEmpty()) arrayOf<Any>(n) else args))
