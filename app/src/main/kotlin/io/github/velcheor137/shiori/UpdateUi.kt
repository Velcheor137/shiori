package io.github.velcheor137.shiori

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.velcheor137.shiori.core.common.AppLog

/** Окно обновления поверх любого экрана: найдено → «Обновить» → загрузка → установка системой. */
@Composable
fun UpdateDialog() {
    val context = LocalContext.current
    val updater = context.shiori.updater
    val state by updater.state.collectAsStateWithLifecycle()
    val prompt by updater.prompt.collectAsStateWithLifecycle()

    // Подтверждение установки открывает система; запускаем его из активности.
    val s0 = state
    LaunchedEffect(s0) {
        if (s0 is UpdateState.Confirm) {
            runCatching { context.startActivity(s0.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                .onFailure { AppLog.e("UPDATE", "Окно установки не открылось", it) }
            updater.confirmShown(s0.release)
        }
    }
    // Вернулись из настроек с разрешением — продолжаем установку.
    LifecycleResumeEffect(s0) {
        if (s0 is UpdateState.NeedsPermission && context.packageManager.canRequestPackageInstalls()) updater.install(s0.release, s0.file)
        onPauseOrDispose { }
    }

    if (!prompt) return
    when (val s = state) {
        is UpdateState.Available -> AlertDialog(
            onDismissRequest = updater::hide,
            title = { Text("Доступна версия ${s.release.version}") },
            text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    Text(
                        "Установлена ${BuildConfig.VERSION_NAME}. Новая версия скачается с GitHub" +
                            (if (s.release.apkSize > 0) " (${s.release.apkSize / (1024 * 1024)} МБ)" else "") + ", файл будет проверен перед установкой.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (s.release.notes.isNotBlank()) {
                        Text(
                            s.release.notes.take(1500),
                            Modifier.padding(top = 12.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { updater.download(s.release) }) { Text("Обновить") } },
            dismissButton = { TextButton(onClick = updater::hide) { Text("Позже") } },
        )
        is UpdateState.Downloading -> AlertDialog(
            onDismissRequest = updater::hide,
            title = { Text("Скачиваю ${s.release.version}") },
            text = {
                Column {
                    if (s.progress < 0) LinearProgressIndicator(Modifier.fillMaxWidth())
                    else LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth())
                    Text(
                        if (s.progress < 0) "Загрузка…" else "${(s.progress * 100).toInt()}%",
                        Modifier.padding(top = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = { TextButton(onClick = updater::hide) { Text("В фоне") } },
        )
        is UpdateState.Installing, is UpdateState.Confirm -> AlertDialog(
            onDismissRequest = updater::hide,
            title = { Text("Установка") },
            text = { Text("Файл проверен. Подтвердите установку в окне системы — приложение перезапустится.") },
            confirmButton = { TextButton(onClick = updater::hide) { Text("Скрыть") } },
        )
        is UpdateState.NeedsPermission -> AlertDialog(
            onDismissRequest = updater::hide,
            title = { Text("Нужно разрешение") },
            text = { Text("Чтобы Shiori могла поставить обновление, разрешите ей установку приложений: «Разрешить из этого источника». Затем вернитесь — установка продолжится.") },
            confirmButton = {
                TextButton(onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }) { Text("Открыть настройки") }
            },
            dismissButton = { TextButton(onClick = updater::hide) { Text("Позже") } },
        )
        is UpdateState.Failed -> AlertDialog(
            onDismissRequest = updater::hide,
            title = { Text("Обновление") },
            text = { Text(s.message) },
            confirmButton = {
                if (s.release != null) TextButton(onClick = { updater.download(s.release) }) { Text("Повторить") }
                else TextButton(onClick = updater::hide) { Text("Ок") }
            },
            dismissButton = { if (s.release != null) TextButton(onClick = updater::hide) { Text("Закрыть") } },
        )
        else -> Unit
    }
}

/** Подпись строки «Проверить обновления» в настройках. */
fun UpdateState.summary(): String = when (this) {
    UpdateState.Idle -> "Установлена ${BuildConfig.VERSION_NAME}"
    UpdateState.Checking -> "Проверяю…"
    UpdateState.UpToDate -> "Установлена последняя версия (${BuildConfig.VERSION_NAME})"
    is UpdateState.Available -> "Доступна ${release.version} — нажмите, чтобы обновить"
    is UpdateState.Downloading -> if (progress < 0) "Скачиваю ${release.version}…" else "Скачиваю ${release.version}: ${(progress * 100).toInt()}%"
    is UpdateState.NeedsPermission -> "Нужно разрешение на установку"
    is UpdateState.Installing, is UpdateState.Confirm -> "Устанавливаю…"
    is UpdateState.Failed -> message
}
