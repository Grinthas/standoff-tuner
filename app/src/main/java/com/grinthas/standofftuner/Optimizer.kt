package com.grinthas.standofftuner

import android.app.ActivityManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.Display

const val GAME_PACKAGE = "com.axlebolt.standoff2"

/** Результат одной проверки: ok — всё хорошо, иначе подсказка и куда перейти. */
data class Check(
    val title: String,
    val value: String,
    val ok: Boolean,
    val hint: String = "",
    val fix: Intent? = null,
)

/**
 * Оптимизация без root, ADB и Shizuku: только то, что Android разрешает обычному приложению.
 * Системные настройки (анимации, частота экрана, энергосбережение) приложение менять не может —
 * оно их проверяет и открывает нужный экран настроек.
 */
class Optimizer(private val context: Context) {
    private val am = context.getSystemService(ActivityManager::class.java)
    private val nm = context.getSystemService(NotificationManager::class.java)
    private val pm = context.getSystemService(PowerManager::class.java)
    private val prefs = context.getSharedPreferences("tuner", Context.MODE_PRIVATE)

    var doNotDisturb: Boolean
        get() = prefs.getBoolean("dnd", false)
        set(v) = prefs.edit().putBoolean("dnd", v).apply()

    fun freeRamMb(): Long = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.availMem shr 20
    fun totalRamMb(): Long = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.totalMem shr 20

    fun checks(display: Display?): List<Check> = buildList {
        add(
            Check(
                "Энергосбережение",
                if (pm.isPowerSaveMode) "включено" else "выключено",
                !pm.isPowerSaveMode,
                "Режим энергосбережения ограничивает процессор и частоту экрана. Выключите его перед игрой.",
                Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS),
            )
        )

        if (display != null) {
            val current = display.refreshRate
            val max = display.supportedModes.maxOf { it.refreshRate }
            add(
                Check(
                    "Частота экрана",
                    "${current.toInt()} из ${max.toInt()} Гц",
                    current >= max - 1,
                    "Выберите максимальную частоту (или «Высокая»/«Адаптивная») в настройках экрана.",
                    Intent(Settings.ACTION_DISPLAY_SETTINGS),
                )
            )
        }

        val scale = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        add(
            Check(
                "Анимации системы",
                if (scale == 0f) "выключены" else "×$scale",
                scale <= 0.5f,
                "Для разработчиков → «Анимация окон», «Анимация переходов», «Длительность анимации» → 0,5x или «Без анимации».",
                Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS),
            )
        )

        val free = freeRamMb()
        val total = totalRamMb()
        add(
            Check(
                "Свободная память",
                "${free} из ${total} МБ",
                free * 100 / total.coerceAtLeast(1) >= 25,
                "Мало свободной памяти: закройте лишние приложения в меню недавних (браузер, соцсети, видео).",
            )
        )

        val temp = batteryTemp()
        if (temp != null) {
            add(
                Check(
                    "Температура батареи",
                    "%.1f °C".format(temp),
                    temp < 40f,
                    "Телефон нагрет — при перегреве система снижает частоты и FPS падает. Снимите чехол, не играйте на зарядке.",
                )
            )
        }
        if (Build.VERSION.SDK_INT >= 29) {
            val status = pm.currentThermalStatus
            add(
                Check(
                    "Троттлинг",
                    when (status) {
                        PowerManager.THERMAL_STATUS_NONE -> "нет"
                        PowerManager.THERMAL_STATUS_LIGHT -> "лёгкий"
                        PowerManager.THERMAL_STATUS_MODERATE -> "средний"
                        else -> "сильный"
                    },
                    status <= PowerManager.THERMAL_STATUS_LIGHT,
                    "Система уже снижает производительность из-за нагрева. Дайте телефону остыть.",
                )
            )
        }
    }

    private fun batteryTemp(): Float? {
        val i = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val t = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        return if (t == Int.MIN_VALUE) null else t / 10f
    }

    val canControlDnd: Boolean get() = nm.isNotificationPolicyAccessGranted

    /** Включает «Не беспокоить», запоминая прежний режим. */
    fun enableDnd() {
        if (!canControlDnd) return
        if (!prefs.contains("prevFilter")) prefs.edit().putInt("prevFilter", nm.currentInterruptionFilter).apply()
        nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
    }

    /** Возвращает режим уведомлений, который был до игры. */
    fun restoreDnd() {
        if (!prefs.contains("prevFilter")) return
        if (canControlDnd) nm.setInterruptionFilter(prefs.getInt("prevFilter", NotificationManager.INTERRUPTION_FILTER_ALL))
        prefs.edit().remove("prevFilter").apply()
    }

    fun dndSettingsIntent() = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
}
