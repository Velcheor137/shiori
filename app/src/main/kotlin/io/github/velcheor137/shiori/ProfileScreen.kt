package io.github.velcheor137.shiori

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.velcheor137.shiori.core.common.AppLog
import io.github.velcheor137.shiori.data.auth.MeCheck
import io.github.velcheor137.shiori.data.auth.SessionState
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** Профиль: вход/выход, проверка сессии, журнал. */
@Composable
fun ProfileScreen(nav: androidx.navigation.NavController) {
    val app = LocalContext.current.shiori
    val auth = app.auth
    val state by auth.state.collectAsStateWithLifecycle()
    var loginOpen by remember { mutableStateOf(false) }
    var logOpen by remember { mutableStateOf(false) }
    var check by remember { mutableStateOf<MeCheck?>(null) }
    var busy by remember { mutableStateOf(false) }
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
            onHost = {},
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

    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Профиль", fontFamily = TitleFont, fontWeight = FontWeight.SemiBold, fontSize = 24.sp)
        when (val s = state) {
            SessionState.Guest -> Text("Вы не вошли. Без входа доступны каталог и открытые главы, но не ваши списки.")
            SessionState.ReloginRequired -> Text("Сессия истекла — войдите снова.")
            is SessionState.Authorized -> {
                Text("Аккаунт: ${s.username ?: "—"}", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Сессия действует до " + DateFormat.getDateTimeInstance().format(Date(s.expiresAtMillis)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (state is SessionState.Authorized) {
            OutlinedButton(onClick = { AppLog.i("UI", "Нажато «Проверить вход»"); runCheck() }, enabled = !busy) {
                Text(if (busy) "Проверяю…" else "Проверить вход")
            }
            OutlinedButton(onClick = { AppLog.i("UI", "Нажато «Выйти»"); scope.launch { auth.logout(); check = null } }) { Text("Выйти") }
        } else {
            Button(onClick = { AppLog.i("UI", "Нажато «Войти»"); loginOpen = true }) { Text("Войти в аккаунт") }
        }
        check?.let { c ->
            Text(
                if (c.failure == null) "Вход работает: ${c.username}" else "Проверка не прошла: ${c.failure}${c.code?.let { ", HTTP $it" } ?: ""}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        val sync by app.store.syncAccount.collectAsStateWithLifecycle()
        if (state is SessionState.Authorized) {
            SwitchRow("Сохранять прогресс в закладку аккаунта RanobeLib", sync) { app.store.setSyncAccount(it) }
            Text(
                "При выходе из главы Shiori запишет главу и абзац в вашу закладку на сайте. По умолчанию выключено.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val appTheme by app.store.appTheme.collectAsStateWithLifecycle()
        Text("Тема приложения", style = MaterialTheme.typography.titleSmall)
        androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AppTheme.entries.forEach { t ->
                androidx.compose.material3.FilterChip(selected = appTheme == t, onClick = { app.store.setAppTheme(t) }, label = { Text(t.label) })
            }
        }
        OutlinedButton(onClick = { nav.navigate("downloads") }) { Text("Загрузки") }
        OutlinedButton(onClick = { logOpen = true }) { Text("Журнал") }
        Text(
            "Shiori ${BuildConfig.VERSION_NAME} — неофициальный клиент RanobeLib. Не связан с RanobeLib/LibSocial.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
