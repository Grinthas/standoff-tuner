package com.grinthas.standofftuner

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Accent = Color(0xFFFF9A3C)
private val Bg = Color(0xFF101216)
private val CardBg = Color(0xFF1A1D23)
private val Muted = Color(0xFF8A919C)
private val Good = Color(0xFF6FCF7F)
private val Warn = Color(0xFFF0B04A)

class MainActivity : ComponentActivity() {
    private lateinit var opt: Optimizer

    private var checks by mutableStateOf<List<Check>>(emptyList())
    private var dnd by mutableStateOf(false)
    private var dndAllowed by mutableStateOf(false)

    // Игра запускалась из приложения: при возвращении вернём режим уведомлений.
    private var launchedGame = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        opt = Optimizer(this)
        dnd = opt.doNotDisturb
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Accent, background = Bg, surface = Bg)) {
                Surface(Modifier.fillMaxSize(), color = Bg) { Screen() }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (launchedGame) {
            launchedGame = false
            opt.restoreDnd()
        }
        refresh()
    }

    private fun refresh() {
        @Suppress("DEPRECATION")
        val display = if (Build.VERSION.SDK_INT >= 30) display else windowManager.defaultDisplay
        checks = opt.checks(display)
        dndAllowed = opt.canControlDnd
    }

    private fun open(intent: Intent?) {
        if (intent == null) return
        runCatching { startActivity(intent) }.onFailure {
            // Некоторые прошивки не поддерживают отдельные экраны — открываем общие настройки.
            runCatching { startActivity(Intent(android.provider.Settings.ACTION_SETTINGS)) }
        }
    }

    private fun launchGame() {
        val launch = packageManager.getLaunchIntentForPackage(GAME_PACKAGE)
        if (launch == null) {
            Toast.makeText(this, "Standoff 2 не установлен", Toast.LENGTH_LONG).show()
            open(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$GAME_PACKAGE")))
            return
        }
        if (dnd) opt.enableDnd()
        launchedGame = true
        startActivity(launch)
    }

    @Composable
    private fun Screen() {
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
                Text("Подготовка телефона к игре на максимальном FPS", color = Muted)
            }

            val okCount = checks.count { it.ok }
            Section("Готовность к игре: $okCount из ${checks.size}") {
                checks.forEachIndexed { i, c ->
                    if (i > 0) HorizontalDivider(color = Color(0xFF2A2E36))
                    CheckRow(c)
                }
                OutlinedButton(onClick = { refresh() }) { Text("Проверить снова") }
            }

            Section("Во время игры") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Режим «Не беспокоить»", color = Color.White)
                        Text(
                            "Уведомления не всплывают поверх игры и не отнимают FPS. Возвращается, когда вы вернётесь в приложение.",
                            color = Muted, fontSize = 12.sp,
                        )
                    }
                    Switch(
                        checked = dnd && dndAllowed,
                        onCheckedChange = {
                            if (!dndAllowed) open(opt.dndSettingsIntent())
                            else { dnd = it; opt.doNotDisturb = it }
                        },
                    )
                }
                if (!dndAllowed) {
                    Text("Нужно разрешить доступ к режиму «Не беспокоить» для Standoff Tuner.", color = Warn, fontSize = 12.sp)
                }
            }

            Button(
                onClick = { launchGame() },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color(0xFF1A1006)),
            ) { Text("Запустить Standoff 2", fontSize = 17.sp, fontWeight = FontWeight.SemiBold) }

            Section("Ещё советы") {
                listOf(
                    "Включите игровой режим телефона (на vivo/iQOO — «Ultra Game Mode» или «Игровой центр») и добавьте туда Standoff 2.",
                    "В самой игре выберите частоту кадров выше, чем «Высокая», если телефон позволяет, и выключите тени.",
                    "Не играйте на зарядке и в чехле — нагрев снижает частоты процессора.",
                    "Перед игрой закройте браузер, соцсети и видео в меню недавних приложений.",
                ).forEach { Text("• $it", color = Muted, fontSize = 13.sp) }
            }

            Text(
                "Без root и ADB Android не даёт приложениям менять системные настройки, поэтому " +
                    "Standoff Tuner проверяет их и открывает нужный экран. Файлы игры не изменяются.",
                color = Muted, fontSize = 12.sp,
            )
            Spacer(Modifier.height(8.dp))
        }
    }

    @Composable
    private fun CheckRow(c: Check) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(vertical = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (c.ok) "✓" else "!", color = if (c.ok) Good else Warn, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(end = 10.dp))
                Text(c.title, color = Color.White, modifier = Modifier.weight(1f))
                Text(c.value, color = if (c.ok) Good else Warn, fontSize = 13.sp)
            }
            if (!c.ok) {
                Text(c.hint, color = Muted, fontSize = 12.sp)
                c.fix?.let { intent ->
                    Button(onClick = { open(intent) }) { Text("Открыть настройки") }
                }
            }
        }
    }

    @Composable
    private fun Section(title: String, content: @Composable () -> Unit) {
        Card(
            colors = CardDefaults.cardColors(containerColor = CardBg),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = Color.White)
                content()
            }
        }
    }
}
