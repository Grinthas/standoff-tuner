package com.grinthas.standofftuner

import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

/** Выполнение команд от имени shell через Shizuku (root не нужен). */
object Shell {

    enum class Status { NOT_RUNNING, NO_PERMISSION, READY }

    fun status(): Status = when {
        !Shizuku.pingBinder() -> Status.NOT_RUNNING
        Shizuku.isPreV11() -> Status.NOT_RUNNING
        Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> Status.NO_PERMISSION
        else -> Status.READY
    }

    // Shizuku.newProcess скрыт в API 13, но остаётся рабочим способом запустить команду.
    private val newProcess by lazy {
        Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java,
        ).apply { isAccessible = true }
    }

    /** Выполняет команду и возвращает её вывод (stdout + stderr). */
    fun run(command: String): String {
        val process = newProcess.invoke(null, arrayOf("sh", "-c", "$command 2>&1"), null, null) as Process
        val out = process.inputStream.bufferedReader().readText()
        process.waitFor()
        return out.trim()
    }
}
