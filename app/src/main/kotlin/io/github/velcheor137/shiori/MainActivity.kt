package io.github.velcheor137.shiori

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.velcheor137.shiori.core.common.AppLog
import io.github.velcheor137.shiori.data.auth.AuthRepository
import io.github.velcheor137.shiori.data.auth.MeCheck
import io.github.velcheor137.shiori.data.auth.SessionState
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val auth = (application as ShioriApp).auth
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize().safeDrawingPadding()) { AccountScreen(auth) }
            }
        }
    }
}

@Composable
private fun AccountScreen(auth: AuthRepository) {
    val state by auth.state.collectAsStateWithLifecycle()
    var loginOpen by remember { mutableStateOf(false) }
    var logOpen by remember { mutableStateOf(false) }
    var check by remember { mutableStateOf<MeCheck?>(null) }
    var busy by remember { mutableStateOf(false) }
    val hosts = remember { mutableStateListOf<String>() }
    val scope = rememberCoroutineScope()

    fun runCheck() {
        busy = true
        scope.launch {
            check = auth.checkMe()
            busy = false
        }
    }

    if (logOpen) {
        BackHandler { logOpen = false }
        LogScreen(onBack = { logOpen = false })
        return
    }

    if (loginOpen) {
        BackHandler {
            AppLog.i("UI", "Вход закрыт кнопкой «Назад»")
            loginOpen = false
        }
        LoginScreen(
            onSiteAuth = auth::acceptSiteAuth,
            onHost = { if (it !in hosts) hosts += it },
            onDone = {
                loginOpen = false
                runCheck()
            },
            onCancel = {
                AppLog.i("UI", "Вход закрыт кнопкой «Закрыть»")
                loginOpen = false
            },
        )
        return
    }

    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Shiori — неофициальный клиент RanobeLib", style = MaterialTheme.typography.titleMedium)
        when (val s = state) {
            SessionState.Guest -> Text("Вы не вошли (гость).")
            SessionState.ReloginRequired -> Text("Сессия истекла — войдите снова.")
            is SessionState.Authorized -> {
                Text("Аккаунт: ${s.username ?: "—"} (id ${s.userId ?: "—"})")
                Text("Токен действует до: " + DateFormat.getDateTimeInstance().format(Date(s.expiresAtMillis)))
            }
        }
        if (state is SessionState.Authorized) {
            Button(onClick = { AppLog.i("UI", "Нажато «Проверить вход»"); runCheck() }, enabled = !busy) {
                Text(if (busy) "Проверяю…" else "Проверить вход (/auth/me)")
            }
            OutlinedButton(onClick = { AppLog.i("UI", "Нажато «Выйти»"); scope.launch { auth.logout(); check = null } }) { Text("Выйти") }
        } else {
            Button(onClick = { AppLog.i("UI", "Нажато «Войти»"); hosts.clear(); loginOpen = true }) { Text("Войти в аккаунт") }
        }
        OutlinedButton(onClick = { logOpen = true }) { Text("Журнал") }
        check?.let { c ->
            Text(
                buildString {
                    append("Проверка: ")
                    append(if (c.failure == null) "успешно" else "ошибка ${c.failure}")
                    c.code?.let { append(", HTTP $it") }
                    append(", домен ${c.host}")
                    c.username?.let { append(", пользователь $it") }
                },
            )
        }
        if (hosts.isNotEmpty()) {
            Text("Хосты страницы входа:", style = MaterialTheme.typography.labelLarge)
            Text(hosts.joinToString("\n"), style = MaterialTheme.typography.bodySmall)
        }
    }
}
