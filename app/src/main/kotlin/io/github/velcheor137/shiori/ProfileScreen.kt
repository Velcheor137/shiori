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
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.runtime.LaunchedEffect
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

/** Профиль: только аккаунт (аватар, уровень, списки, жанры), уведомления, загрузки, «Настройки», выход. */
@Composable
fun ProfileScreen(nav: androidx.navigation.NavController) {
    val app = LocalContext.current.shiori
    val auth = app.auth
    val state by auth.state.collectAsStateWithLifecycle()
    var loginOpen by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

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
                scope.launch { auth.checkMe() }
            },
            onCancel = {
                AppLog.i("UI", "Вход закрыт кнопкой «Закрыть»")
                loginOpen = false
            },
        )
        return
    }

    val authorized = state as? SessionState.Authorized
    // Профиль сразу из кэша; сеть обновляет его в фоне (не чаще раза в 10 минут), экран меняется только при отличиях.
    val data by app.profile.data.collectAsStateWithLifecycle()
    val offline by app.offline.collectAsStateWithLifecycle()
    LaunchedEffect(authorized?.userId, offline) {
        if (authorized == null || offline) return@LaunchedEffect
        val uid = authorized.userId ?: run {
            AppLog.w("PROFILE", "В сессии нет id пользователя, запрашиваю /auth/me")
            auth.checkMe() // сессия обновится, эффект перезапустится с id
            return@LaunchedEffect
        }
        app.profile.refresh(app, uid)
    }
    val shown = data?.takeIf { authorized != null && (authorized.userId == null || it.userId == authorized.userId) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        when {
            authorized != null -> AccountHeader(authorized, shown)
            else -> GuestHeader(state == SessionState.ReloginRequired) { AppLog.i("UI", "Нажато «Войти»"); loginOpen = true }
        }
        shown?.folders?.takeIf { it.isNotEmpty() }?.let { folders ->
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                folders.forEach { f -> AssistChip(onClick = { nav.navigate("library") }, label = { Text("${f.name}  ${f.count}") }) }
            }
        }
        shown?.genres?.takeIf { it.isNotEmpty() }?.let { genres ->
            SectionLabel("Любимые жанры")
            genres.forEach { (name, percent) ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(name, Modifier.width(140.dp), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    LinearProgressIndicator(progress = { (percent / 100f * 4f).coerceIn(0f, 1f) }, modifier = Modifier.weight(1f).height(4.dp), drawStopIndicator = {})
                    Text(" ${percent.toInt()}%", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        if (authorized != null) NavRow(Icons.Filled.Notifications, "Уведомления") { nav.navigate("notifications") }
        NavRow(AppIcons.Download, "Загрузки") { nav.navigate("downloads") }
        NavRow(Icons.Filled.Settings, "Настройки", "Источник, тема, офлайн, хранилище, журнал") { nav.navigate("settings") }
        if (authorized != null) {
            NavRow(Icons.AutoMirrored.Filled.ExitToApp, "Выйти", tint = MaterialTheme.colorScheme.error) {
                AppLog.i("UI", "Нажато «Выйти»")
                scope.launch { auth.logout(); app.clearProfiles() }
            }
        }
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
internal fun SectionLabel(text: String) {
    Text(text, Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
}

@Composable
internal fun NavRow(icon: ImageVector, title: String, subtitle: String? = null, tint: androidx.compose.ui.graphics.Color? = null, onClick: () -> Unit) {
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
