package com.grinthas.standofftuner

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import kotlin.math.roundToInt

private val Accent = Color(0xFFFF9A3C)
private val Bg = Color(0xFF101216)
private val CardBg = Color(0xFF1A1D23)
private val Muted = Color(0xFF8A919C)

class MainActivity : ComponentActivity() {
    private lateinit var tweaks: Tweaks

    private var shizuku by mutableStateOf(Shell.Status.NOT_RUNNING)
    private var options by mutableStateOf(Options())
    private var applied by mutableStateOf(false)
    private var screen by mutableStateOf<Pair<Int, Int>?>(null)
    private var log by mutableStateOf("")

    private val binderListener = Shizuku.OnBinderReceivedListener { refresh() }
    private val deadListener = Shizuku.OnBinderDeadListener { refresh() }
    private val permissionListener = Shizuku.OnRequestPermissionResultListener { _, _ -> refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        tweaks = Tweaks(this)
        options = tweaks.options
        Shizuku.addBinderReceivedListenerSticky(binderListener)
        Shizuku.addBinderDeadListener(deadListener)
        Shizuku.addRequestPermissionResultListener(permissionListener)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
        }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Accent, background = Bg, surface = Bg)) {
                Surface(Modifier.fillMaxSize(), color = Bg) { Screen() }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDestroy() {
        Shizuku.removeBinderReceivedListener(binderListener)
        Shizuku.removeBinderDeadListener(deadListener)
        Shizuku.removeRequestPermissionResultListener(permissionListener)
        super.onDestroy()
    }

    private fun refresh() {
        runOnUiThread {
            shizuku = runCatching { Shell.status() }.getOrDefault(Shell.Status.NOT_RUNNING)
            applied = tweaks.applied
        }
        Thread {
            val size = if (Shell.status() == Shell.Status.READY) runCatching { tweaks.physicalSize() }.getOrNull() else null
            runOnUiThread { screen = size }
        }.start()
    }

    private fun maxRefreshRate(): Float {
        @Suppress("DEPRECATION")
        val d = if (Build.VERSION.SDK_INT >= 30) display else windowManager.defaultDisplay
        return d?.supportedModes?.maxOfOrNull { it.refreshRate } ?: 60f
    }

    private fun update(o: Options) {
        options = o
        tweaks.options = o
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    private fun openUrl(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    private suspend fun launchGame() {
        val launch = packageManager.getLaunchIntentForPackage(GAME_PACKAGE)
        if (launch == null) {
            toast("Standoff 2 не установлен")
            return
        }
        val result = withContext(Dispatchers.IO) {
            runCatching { tweaks.apply(options, maxRefreshRate()) }
        }
        result.onFailure {
            toast("Ошибка: ${it.message}")
            return
        }
        log = result.getOrThrow().joinToString("\n")
        applied = true
        startForegroundService(Intent(this, WatchService::class.java))
        startActivity(launch)
    }

    private suspend fun restoreAll() {
        withContext(Dispatchers.IO) { runCatching { tweaks.restore() } }
            .onSuccess { toast("Настройки возвращены") }
            .onFailure { toast("Ошибка: ${it.message}") }
        stopService(Intent(this, WatchService::class.java))
        log = ""
        refresh()
    }

    @Composable
    private fun Screen() {
        val scope = rememberCoroutineScope()
        val ready = shizuku == Shell.Status.READY
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column {
                Text("Standoff Tuner", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Text("Растяжение и оптимизация для Standoff 2", color = Muted)
            }

            ShizukuCard()

            Section("Растяжение экрана") {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ASPECTS.forEachIndexed { i, a ->
                        FilterChip(
                            selected = options.aspect == i,
                            onClick = { update(options.copy(aspect = i)) },
                            label = { Text(a.label) },
                        )
                    }
                }
                val ratio = ASPECTS[options.aspect].ratio
                val size = screen
                val hint = when {
                    ratio == null -> "Экран останется как есть."
                    size == null -> "Разрешение покажется после подключения Shizuku."
                    else -> {
                        val short = minOf(size.first, size.second)
                        val long = ((short * ratio).roundToInt() / 2) * 2
                        "${maxOf(size.first, size.second)}×$short → $long×$short"
                    }
                }
                Text(hint, color = Muted, fontSize = 13.sp)
            }

            Section("Оптимизация") {
                Toggle("Игровой режим «Производительность»", "Android 12+, cmd game mode", options.gameMode) {
                    update(options.copy(gameMode = it))
                }
                Toggle("Отключить анимации системы", "Быстрее отклик интерфейса", options.noAnimations) {
                    update(options.copy(noAnimations = it))
                }
                Toggle("Максимальная частота экрана", "${maxRefreshRate().toInt()} Гц на время игры", options.maxRefresh) {
                    update(options.copy(maxRefresh = it))
                }
                Toggle("Закрыть фоновые приложения", "Освобождает память перед запуском", options.killBackground) {
                    update(options.copy(killBackground = it))
                }
            }

            Button(
                onClick = { scope.launch { launchGame() } },
                enabled = ready,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color(0xFF1A1006)),
            ) { Text("Запустить Standoff 2", fontSize = 17.sp, fontWeight = FontWeight.SemiBold) }

            OutlinedButton(
                onClick = { scope.launch { restoreAll() } },
                enabled = ready,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (applied) "Вернуть всё (настройки ещё изменены)" else "Вернуть всё как было") }

            if (log.isNotEmpty()) {
                Section("Выполнено") {
                    Text(log, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Muted)
                }
            }

            Text(
                "Программа меняет только системные настройки Android и не трогает файлы игры. " +
                    "Всё возвращается автоматически после выхода из Standoff 2 или кнопкой в уведомлении.",
                color = Muted, fontSize = 12.sp,
            )
            Spacer(Modifier.height(8.dp))
        }
    }

    @Composable
    private fun ShizukuCard() {
        val (title, text) = when (shizuku) {
            Shell.Status.READY -> "Shizuku подключён" to "Всё готово."
            Shell.Status.NO_PERMISSION -> "Нужно разрешение" to "Разрешите Standoff Tuner доступ к Shizuku."
            Shell.Status.NOT_RUNNING -> "Shizuku не запущен" to
                "Установите Shizuku и запустите его через «Беспроводную отладку» (root не нужен)."
        }
        Section(title, highlight = shizuku != Shell.Status.READY) {
            Text(text, color = Muted, fontSize = 13.sp)
            when (shizuku) {
                Shell.Status.NO_PERMISSION -> Button(onClick = { Shizuku.requestPermission(1) }) { Text("Разрешить") }
                Shell.Status.NOT_RUNNING -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        val shizukuApp = packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                        if (shizukuApp != null) startActivity(shizukuApp)
                        else openUrl("https://play.google.com/store/apps/details?id=moe.shizuku.privileged.api")
                    }) { Text("Открыть Shizuku") }
                    OutlinedButton(onClick = { openUrl("https://shizuku.rikka.app/guide/setup/") }) { Text("Инструкция") }
                }
                Shell.Status.READY -> {}
            }
        }
    }

    @Composable
    private fun Section(title: String, highlight: Boolean = false, content: @Composable () -> Unit) {
        Card(
            colors = CardDefaults.cardColors(containerColor = if (highlight) Color(0xFF2A2014) else CardBg),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = Color.White)
                content()
            }
        }
    }

    @Composable
    private fun Toggle(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, color = Color.White)
                Text(subtitle, color = Muted, fontSize = 12.sp)
            }
            Switch(checked = checked, onCheckedChange = onChange)
        }
    }
}
