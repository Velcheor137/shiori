package io.github.velcheor137.shiori

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import io.github.velcheor137.shiori.core.common.AppLog
import io.github.velcheor137.shiori.data.auth.MeCheck
import io.github.velcheor137.shiori.data.auth.SessionState
import io.github.velcheor137.shiori.data.libsocial.BookmarkFolder
import io.github.velcheor137.shiori.data.libsocial.UserProfile
import kotlinx.coroutines.launch

private data class ProfileData(val user: UserProfile?, val folders: List<BookmarkFolder>, val genres: List<Pair<String, Float>>, val error: String? = null)

/** Профиль: шапка аккаунта из API (аватар, ник, уровень), списки, разделы настроек, выход. */
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

    val authorized = state as? SessionState.Authorized
    // Профиль грузится по частям: шапка, затем списки, затем жанры. Если в сессии нет id
    // (старый вход), берём его из /api/auth/me — сессия обновится, и загрузка начнётся заново.
    val data by produceState<ProfileData?>(null, authorized != null, authorized?.userId) {
        if (authorized == null) { value = null; return@produceState }
        val uid = authorized.userId ?: run {
            AppLog.w("PROFILE", "В сессии нет id пользователя, запрашиваю /auth/me")
            val me = auth.checkMe()
            if (me.userId == null) value = ProfileData(null, emptyList(), emptyList(), error = "Не удалось получить профиль: ${me.failure ?: "нет id"}")
            return@produceState
        }
        val user = runCatching { app.api.user(uid) }.onFailure { AppLog.w("PROFILE", "Профиль $uid не загрузился", it) }
        value = ProfileData(user.getOrNull(), emptyList(), emptyList(), error = user.exceptionOrNull()?.let { "Профиль не загрузился: ${it.userMessage()}" })
        val folders = runCatching { app.api.folders(uid) }.getOrDefault(emptyList())
        value = value?.copy(folders = folders)
        value = value?.copy(genres = app.api.userTopGenres(uid).take(5))
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        when {
            authorized != null -> AccountHeader(authorized, data)
            else -> GuestHeader(state == SessionState.ReloginRequired) { AppLog.i("UI", "Нажато «Войти»"); loginOpen = true }
        }
        data?.folders?.takeIf { it.isNotEmpty() }?.let { folders ->
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                folders.forEach { f -> AssistChip(onClick = { nav.navigate("library") }, label = { Text("${f.name}  ${f.count}") }) }
            }
        }
        data?.genres?.takeIf { it.isNotEmpty() }?.let { genres ->
            SectionLabel("Любимые жанры")
            genres.forEach { (name, percent) ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(name, Modifier.width(140.dp), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    LinearProgressIndicator(progress = { (percent / 100f * 4f).coerceIn(0f, 1f) }, modifier = Modifier.weight(1f).height(4.dp), drawStopIndicator = {})
                    Text(" ${percent.toInt()}%", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        SectionLabel("Источник")
        val source by app.source.collectAsStateWithLifecycle()
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Source.entries.forEach { src ->
                FilterChip(selected = source == src, onClick = { app.setSource(src) }, label = { Text(src.label + if (src.images) " · манга" else " · ранобэ") })
            }
        }
        Text(
            "Аккаунт общий. Прогресс, закладки, фильтры и кэш у каждого источника свои.",
            Modifier.padding(horizontal = 16.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        SectionLabel("Приложение")
        if (authorized != null) NavRow(Icons.Filled.Notifications, "Уведомления") { nav.navigate("notifications") }
        NavRow(AppIcons.Download, "Загрузки") { nav.navigate("downloads") }
        NavRow(AppIcons.Storage, "Хранилище", "Кэш глав и картинок, лимиты") { nav.navigate("storage") }
        val appTheme by app.store.appTheme.collectAsStateWithLifecycle()
        // Подпись — в своей строке, чипы — под ней (иначе на узком экране «Тема» сжимается в столбик).
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(AppIcons.Palette, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(16.dp))
            Text("Тема")
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 56.dp, end = 16.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AppTheme.entries.forEach { t ->
                FilterChip(selected = appTheme == t, onClick = { app.store.setAppTheme(t) }, label = { Text(t.label, maxLines = 1) })
            }
        }
        if (authorized != null) {
            val sync by app.store.syncAccount.collectAsStateWithLifecycle()
            Row(Modifier.fillMaxWidth().clickable { app.store.setSyncAccount(!sync) }.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(AppIcons.Sync, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text("Прогресс в закладку аккаунта")
                    Text("При выходе из главы — глава и абзац в закладку на сайте", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = sync, onCheckedChange = { app.store.setSyncAccount(it) })
            }
        }
        NavRow(AppIcons.Article, "Журнал", "Для диагностики, никуда не отправляется") { logOpen = true }

        if (authorized != null) {
            SectionLabel("Аккаунт")
            NavRow(Icons.Filled.CheckCircle, if (busy) "Проверяю…" else "Проверить вход", check?.let { c ->
                if (c.failure == null) "Вход работает: ${c.username}" else "Не прошла: ${c.failure}${c.code?.let { ", HTTP $it" } ?: ""}"
            } ?: "Сессия до " + java.text.DateFormat.getDateTimeInstance().format(java.util.Date(authorized.expiresAtMillis))) {
                if (!busy) { AppLog.i("UI", "Нажато «Проверить вход»"); runCheck() }
            }
            NavRow(Icons.AutoMirrored.Filled.ExitToApp, "Выйти", tint = MaterialTheme.colorScheme.error) {
                AppLog.i("UI", "Нажато «Выйти»")
                scope.launch { auth.logout(); check = null }
            }
        }
        Text(
            "Shiori ${BuildConfig.VERSION_NAME} — неофициальный клиент RanobeLib. Не связан с RanobeLib/LibSocial.",
            Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AccountHeader(s: SessionState.Authorized, data: ProfileData?) {
    val user = data?.user
    Box(Modifier.fillMaxWidth().padding(12.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
        user?.background?.let {
            AsyncImage(it, null, contentScale = ContentScale.Crop, alpha = 0.25f, modifier = Modifier.matchParentSize())
        }
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(72.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                val name = user?.username ?: s.username.orEmpty()
                Text(name.take(1).uppercase(), color = MaterialTheme.colorScheme.onPrimary, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                user?.avatar?.let { AsyncImage(it, "Аватар", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(user?.username ?: s.username ?: "—", fontFamily = TitleFont, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                user?.level?.let { level ->
                    Text(
                        "Уровень $level" + (user.totalPoints?.let { " · ${"%,d".format(it).replace(',', ' ')} очков" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    user.levelPercent?.let {
                        LinearProgressIndicator(progress = { it / 100f }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).height(4.dp), drawStopIndicator = {})
                    }
                }
                val meta = listOfNotNull(
                    user?.createdAt?.take(4)?.let { "на сайте с $it" },
                    user?.loginStreak?.takeIf { it > 0 }?.let { "серия входов $it дн." },
                ).joinToString(" · ")
                if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                when {
                    data == null -> Text("Загружаю профиль…", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    data.error != null -> Text(data.error, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
    user?.about?.let { Text(it, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall, maxLines = 4, overflow = TextOverflow.Ellipsis) }
}

@Composable
private fun GuestHeader(expired: Boolean, onLogin: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(12.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Профиль", fontFamily = TitleFont, fontWeight = FontWeight.SemiBold, fontSize = 22.sp)
        Text(
            if (expired) "Сессия истекла — войдите снова." else "Вы не вошли. Без входа доступны каталог и открытые главы, но не ваши списки и комментарии.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onLogin) { Text("Войти в аккаунт") }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun NavRow(icon: ImageVector, title: String, subtitle: String? = null, tint: androidx.compose.ui.graphics.Color? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = tint ?: MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = tint ?: MaterialTheme.colorScheme.onSurface)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        if (tint == null) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
