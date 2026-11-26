package io.github.velcheor137.shiori

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import io.github.velcheor137.shiori.core.common.AppLog
import io.github.velcheor137.shiori.data.auth.MeCheck
import io.github.velcheor137.shiori.data.auth.SessionState
import kotlinx.coroutines.launch

/** «Настройки»: источник, тема, синхронизация, офлайн-режим, хранилище, журнал, проверка входа. */
@Composable
fun SettingsScreen(nav: NavController) {
    val app = LocalContext.current.shiori
    val session by app.auth.state.collectAsStateWithLifecycle()
    val authorized = session as? SessionState.Authorized
    val scope = rememberCoroutineScope()
    var check by remember { mutableStateOf<MeCheck?>(null) }
    var busy by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") }
            Text("Настройки", fontFamily = TitleFont, fontWeight = FontWeight.SemiBold, fontSize = 20.sp)
        }

        SectionLabel("Источник")
        SourcePicker(Modifier.padding(horizontal = 16.dp))
        Text(
            "Аккаунт общий. Прогресс, закладки, фильтры и кэш у каждого источника свои.",
            Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        SectionLabel("Оформление")
        val appTheme by app.store.appTheme.collectAsStateWithLifecycle()
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppTheme.entries.forEach { t ->
                FilterChip(selected = appTheme == t, onClick = { app.store.setAppTheme(t) }, label = { Text(t.label, maxLines = 1) })
            }
        }

        SectionLabel("Чтение")
        val manualOffline by app.net.manual.collectAsStateWithLifecycle()
        val offline by app.offline.collectAsStateWithLifecycle()
        SwitchItem(
            AppIcons.CloudOff,
            "Офлайн-режим",
            when {
                manualOffline -> "Включён вручную: только скачанное"
                offline -> "Сейчас нет сети — включился сам"
                else -> "Включается сам без сети; можно включить вручную"
            },
            manualOffline,
        ) { app.net.setManual(it) }
        if (authorized != null) {
            val sync by app.store.syncAccount.collectAsStateWithLifecycle()
            SwitchItem(AppIcons.Sync, "Прогресс в закладку аккаунта", "При выходе из главы — глава и абзац (страница) в закладку на сайте", sync) {
                app.store.setSyncAccount(it)
            }
        }

        SectionLabel("Данные")
        NavRow(AppIcons.Storage, "Хранилище", "Кэш глав и картинок, лимиты") { nav.navigate("storage") }
        NavRow(AppIcons.Article, "Журнал", "Для диагностики, никуда не отправляется") { nav.navigate("log") }

        if (authorized != null) {
            SectionLabel("Аккаунт")
            NavRow(Icons.Filled.CheckCircle, if (busy) "Проверяю…" else "Проверить вход", check?.let { c ->
                if (c.failure == null) "Вход работает: ${c.username}" else "Не прошла: ${c.failure}${c.code?.let { ", HTTP $it" } ?: ""}"
            } ?: "Сессия до " + java.text.DateFormat.getDateTimeInstance().format(java.util.Date(authorized.expiresAtMillis))) {
                if (!busy) {
                    AppLog.i("UI", "Нажато «Проверить вход»")
                    busy = true
                    scope.launch { check = app.auth.checkMe(); busy = false }
                }
            }
        }

        Text(
            "Shiori ${BuildConfig.VERSION_NAME} — неофициальный клиент RanobeLib и MangaLib. Не связан с LibSocial.",
            Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SwitchItem(icon: ImageVector, title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
