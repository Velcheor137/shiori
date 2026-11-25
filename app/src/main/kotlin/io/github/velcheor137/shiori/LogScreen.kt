package io.github.velcheor137.shiori

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import io.github.velcheor137.shiori.core.common.AppLog

/** Журнал диагностики: просмотр, «Поделиться» файлом, копирование, очистка. */
@Composable
fun LogScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var text by remember { mutableStateOf(AppLog.read()) }
    val vertical = rememberScrollState()
    LaunchedEffect(text) { vertical.scrollTo(vertical.maxValue) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
            TextButton(onClick = onBack) { Text("Назад") }
            TextButton(onClick = { share(context) }) { Text("Поделиться") }
            TextButton(onClick = { copy(context, text) }) { Text("Копировать") }
            TextButton(onClick = {
                AppLog.clear()
                AppLog.i("APP", "Журнал очищен")
                text = AppLog.read()
            }) { Text("Очистить") }
        }
        SelectionContainer {
            Text(
                text = text.ifEmpty { "Журнал пуст" },
                modifier = Modifier.fillMaxSize().verticalScroll(vertical).horizontalScroll(rememberScrollState()).padding(8.dp),
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                lineHeight = 13.sp,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private fun share(context: Context) {
    val file = AppLog.logFile?.takeIf { it.exists() }
    if (file == null) {
        // Release-сборка: журнал только в памяти (предупреждения и ошибки сессии) — отправляем текстом.
        val text = AppLog.read()
        if (text.isBlank()) return Toast.makeText(context, "Журнал пуст", Toast.LENGTH_SHORT).show()
        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text.takeLast(100_000)), "Отправить журнал"))
        return
    }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.logs", file)
    val intent = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .putExtra(Intent.EXTRA_SUBJECT, "Shiori — журнал")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(intent, "Отправить журнал"))
}

private fun copy(context: Context, text: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    clipboard.setPrimaryClip(ClipData.newPlainText("Shiori log", text.takeLast(200_000)))
    Toast.makeText(context, "Журнал скопирован", Toast.LENGTH_SHORT).show()
}
