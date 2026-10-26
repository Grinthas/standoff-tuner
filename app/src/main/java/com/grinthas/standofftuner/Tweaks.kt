package com.grinthas.standofftuner

import android.content.Context
import kotlin.math.roundToInt

const val GAME_PACKAGE = "com.axlebolt.standoff2"

/** Соотношение сторон экрана (длинная сторона / короткая). null — без растяжения. */
data class Aspect(val label: String, val ratio: Float?)

val ASPECTS = listOf(
    Aspect("Выкл", null),
    Aspect("4:3", 4f / 3f),
    Aspect("5:4", 5f / 4f),
    Aspect("16:10", 16f / 10f),
    Aspect("16:9", 16f / 9f),
)

/** Настройки пользователя. */
data class Options(
    val aspect: Int = 1,
    val gameMode: Boolean = true,
    val noAnimations: Boolean = true,
    val maxRefresh: Boolean = true,
    val killBackground: Boolean = false,
)

/**
 * Применение и откат изменений. Всё меняется только системными командами
 * (wm, settings, cmd game, am) — файлы игры не трогаются.
 */
class Tweaks(context: Context) {
    private val prefs = context.getSharedPreferences("tuner", Context.MODE_PRIVATE)

    var options: Options
        get() = Options(
            aspect = prefs.getInt("aspect", 1).coerceIn(ASPECTS.indices),
            gameMode = prefs.getBoolean("gameMode", true),
            noAnimations = prefs.getBoolean("noAnimations", true),
            maxRefresh = prefs.getBoolean("maxRefresh", true),
            killBackground = prefs.getBoolean("killBackground", false),
        )
        set(o) = prefs.edit()
            .putInt("aspect", o.aspect)
            .putBoolean("gameMode", o.gameMode)
            .putBoolean("noAnimations", o.noAnimations)
            .putBoolean("maxRefresh", o.maxRefresh)
            .putBoolean("killBackground", o.killBackground)
            .apply()

    /** Применены ли изменения, которые ещё не откатили. */
    val applied: Boolean get() = prefs.getBoolean("applied", false)

    /** Физическое разрешение экрана, например 1080x2400. */
    fun physicalSize(): Pair<Int, Int>? {
        val m = Regex("""Physical size:\s*(\d+)x(\d+)""").find(Shell.run("wm size")) ?: return null
        return m.groupValues[1].toInt() to m.groupValues[2].toInt()
    }

    /** Разрешение для растяжения: короткая сторона та же, длинная = короткая × ratio. */
    fun stretchedSize(ratio: Float): Pair<Int, Int>? {
        val (w, h) = physicalSize() ?: return null
        val short = minOf(w, h)
        val long = ((short * ratio).roundToInt() / 2) * 2
        return if (w <= h) short to long else long to short
    }

    fun apply(o: Options, refreshRate: Float): List<String> {
        val log = mutableListOf<String>()
        fun exec(cmd: String) {
            val out = Shell.run(cmd)
            log += if (out.isEmpty()) cmd else "$cmd → $out"
        }
        val edit = prefs.edit()

        // Сначала откатываем прошлый запуск, чтобы не сохранить «изменённые» значения как исходные.
        if (applied) restore()

        ASPECTS[o.aspect].ratio?.let { ratio ->
            stretchedSize(ratio)?.let { (w, h) -> exec("wm size ${w}x$h") }
        }
        if (o.noAnimations) {
            for (key in ANIMATION_KEYS) {
                edit.putString(key, Shell.run("settings get global $key"))
                exec("settings put global $key 0")
            }
        }
        if (o.maxRefresh) {
            for (key in REFRESH_KEYS) {
                edit.putString(key, Shell.run("settings get system $key"))
                exec("settings put system $key $refreshRate")
            }
        }
        if (o.gameMode) exec("cmd game mode performance $GAME_PACKAGE")
        if (o.killBackground) exec("am kill-all")

        edit.putBoolean("applied", true)
            .putBoolean("savedAnimations", o.noAnimations)
            .putBoolean("savedRefresh", o.maxRefresh)
            .putBoolean("savedGameMode", o.gameMode)
            .commit()
        return log
    }

    /** Возвращает всё, что поменял apply(). Безопасно вызывать повторно. */
    fun restore() {
        Shell.run("wm size reset")
        if (prefs.getBoolean("savedAnimations", false)) {
            for (key in ANIMATION_KEYS) putOrDelete("global", key, prefs.getString(key, null))
        }
        if (prefs.getBoolean("savedRefresh", false)) {
            for (key in REFRESH_KEYS) putOrDelete("system", key, prefs.getString(key, null))
        }
        if (prefs.getBoolean("savedGameMode", false)) {
            Shell.run("cmd game mode standard $GAME_PACKAGE")
        }
        prefs.edit()
            .putBoolean("applied", false)
            .putBoolean("savedAnimations", false)
            .putBoolean("savedRefresh", false)
            .putBoolean("savedGameMode", false)
            .commit()
    }

    private fun putOrDelete(namespace: String, key: String, value: String?) {
        if (value == null || value == "null" || value.isBlank()) {
            Shell.run("settings delete $namespace $key")
        } else {
            Shell.run("settings put $namespace $key $value")
        }
    }

    fun gameRunning(): Boolean = Shell.run("pidof $GAME_PACKAGE").isNotBlank()

    companion object {
        val ANIMATION_KEYS = listOf("window_animation_scale", "transition_animation_scale", "animator_duration_scale")
        val REFRESH_KEYS = listOf("peak_refresh_rate", "min_refresh_rate")
    }
}
