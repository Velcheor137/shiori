package io.github.velcheor137.shiori

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Вход на настоящей странице сайта (§10.0 плана). Логин и пароль вводятся на сайте, приложение их не
 * видит. После входа сайт кладёт токен в `localStorage.auth` — забираем его и очищаем WebView.
 *
 * @param onSiteAuth получает результат `localStorage.getItem('auth')`; возвращает true, если в нём был токен.
 * @param onHost сообщает хосты, по которым ходила страница входа (нужны для списка разрешённых хостов).
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LoginScreen(
    onSiteAuth: (String?) -> Boolean,
    onHost: (String) -> Unit,
    onDone: () -> Unit,
    onCancel: () -> Unit,
) {
    val handled = remember { booleanArrayOf(false) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onCancel) { Text("Закрыть") }
            Text("Вход на ranobelib.me", Modifier.padding(start = 8.dp))
        }
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                WebView(context).apply {
                    settings.javaScriptEnabled = true // страница входа сайта работает на JS
                    settings.domStorageEnabled = true // сайт хранит токен в localStorage
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.setSupportMultipleWindows(false)
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            val url = request.url
                            url.host?.let(onHost)
                            if (url.scheme == "https") return false
                            // intent:, market:, http: и прочее — не открываем внутри.
                            if (url.scheme == "http") {
                                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url)) }
                            }
                            return true
                        }

                        override fun onPageFinished(view: WebView, url: String) {
                            Uri.parse(url).host?.let(onHost)
                            if (handled[0] || Uri.parse(url).host?.endsWith("ranobelib.me") != true) return
                            view.evaluateJavascript("localStorage.getItem('auth')") { value ->
                                if (!handled[0] && onSiteAuth(value)) {
                                    handled[0] = true
                                    clearWebState(view)
                                    onDone()
                                }
                            }
                        }
                    }
                    loadUrl(START_URL)
                }
            },
        )
    }
}

/** Токен теперь живёт только в хранилище приложения: стираем сессию сайта во WebView. */
private fun clearWebState(view: WebView) {
    view.stopLoading()
    view.clearHistory()
    view.clearCache(true)
    WebStorage.getInstance().deleteAllData()
    CookieManager.getInstance().removeAllCookies(null)
    CookieManager.getInstance().flush()
}

private const val START_URL = "https://ranobelib.me/ru"
