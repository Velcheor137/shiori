package io.github.velcheor137.shiori

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.view.View
import android.net.http.SslError
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceResponse
import android.webkit.WebResourceRequest
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.velcheor137.shiori.core.common.AppLog

/**
 * Вход на странице `auth.lib.social` (§10.0 плана). Открываем маршрут сайта `/ru/front/auth`: сайт сам
 * создаёт PKCE-ключ и сразу уводит на форму входа LibSocial; после входа обменивает код на токен и
 * кладёт его в `localStorage.auth` — оттуда забираем токен и стираем сессию WebView.
 * Страницы ranobelib.me скрыты за индикатором загрузки, внешние приложения и браузер не открываются.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LoginScreen(
    onSiteAuth: (String?) -> Boolean,
    onHost: (String) -> Unit,
    onDone: () -> Unit,
    onCancel: () -> Unit,
) {
    var showPage by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onCancel) { Text("Закрыть") }
            Text("Вход в аккаунт LibSocial", Modifier.padding(start = 8.dp))
        }
        Box(Modifier.fillMaxSize()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    WebView(context).apply {
                        settings.javaScriptEnabled = true // форма входа и обмен кода работают на JS
                        settings.domStorageEnabled = true // сайт хранит PKCE-ключ и токен в localStorage
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        settings.setSupportMultipleWindows(false)
                        visibility = View.INVISIBLE
                        AppLog.i(TAG, "Открываю вход. WebView UA: ${settings.userAgentString}")
                        webChromeClient = object : WebChromeClient() {
                            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                                AppLog.i(TAG, "console.${message.messageLevel()}: ${message.message().take(500)} (${message.sourceId().substringAfterLast('/')}:${message.lineNumber()})")
                                return true
                            }
                        }
                        webViewClient = AuthClient(
                            onSiteAuth = onSiteAuth,
                            onHost = onHost,
                            onShowPage = { show ->
                                showPage = show
                                visibility = if (show) View.VISIBLE else View.INVISIBLE
                            },
                            onDone = onDone,
                        )
                        loadUrl(START_URL)
                    }
                },
            )
            if (!showPage) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        }
    }
}

private class AuthClient(
    private val onSiteAuth: (String?) -> Boolean,
    private val onHost: (String) -> Unit,
    private val onShowPage: (Boolean) -> Unit,
    private val onDone: () -> Unit,
) : WebViewClient() {
    private var done = false
    private var polling = false

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        request.url.host?.let(onHost)
        // Только https внутри этого WebView; intent:, market:, http: и прочее молча блокируем.
        val block = request.url.scheme != "https"
        AppLog.i(
            TAG,
            "Переход ${if (block) "ЗАБЛОКИРОВАН" else "разрешён"}: ${request.url} " +
                "(mainFrame=${request.isForMainFrame}, redirect=${request.isRedirect}, жест=${request.hasGesture()})",
        )
        return block
    }

    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        AppLog.i(TAG, "Страница начала загрузку: $url")
        update(view, url)
    }

    override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
        AppLog.i(TAG, "Адрес в истории: $url (reload=$isReload)")
        update(view, url)
    }

    override fun onPageFinished(view: WebView, url: String) {
        AppLog.i(TAG, "Страница загружена: $url")
        update(view, url)
    }

    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
        AppLog.w(TAG, "Ошибка загрузки ${error.errorCode} «${error.description}»: ${request.url} (mainFrame=${request.isForMainFrame})")
    }

    override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
        AppLog.w(TAG, "HTTP ${response.statusCode} ${response.reasonPhrase}: ${request.url} (mainFrame=${request.isForMainFrame})")
    }

    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
        AppLog.e(TAG, "Ошибка TLS ${error.primaryError} для ${error.url} — загрузка отменена")
        handler.cancel()
    }

    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        AppLog.e(TAG, "Процесс отрисовки WebView завершился (crash=${detail.didCrash()})")
        return true
    }

    private fun update(view: WebView, url: String) {
        if (done) return
        val host = Uri.parse(url).host ?: return
        onHost(host)
        val onSite = host == SITE_HOST || host.endsWith(".$SITE_HOST")
        // ranobelib.me показываем только как «загрузку»: пользователь видит лишь форму входа.
        onShowPage(!onSite)
        if (onSite) pollToken(view, attemptsLeft = POLL_ATTEMPTS)
    }

    /** Сайт сохраняет токен асинхронно после обмена кода — проверяем localStorage несколько раз. */
    private fun pollToken(view: WebView, attemptsLeft: Int) {
        if (done || (polling && attemptsLeft == POLL_ATTEMPTS)) return
        polling = true
        AppLog.i(TAG, "Проверяю localStorage.auth, попытка ${POLL_ATTEMPTS - attemptsLeft + 1}/$POLL_ATTEMPTS, адрес ${view.url}")
        view.evaluateJavascript("localStorage.getItem('auth')") { value ->
            when {
                done -> Unit
                onSiteAuth(value) -> {
                    done = true
                    AppLog.i(TAG, "Токен получен, очищаю WebView и закрываю вход")
                    clearWebState(view)
                    onDone()
                }
                attemptsLeft > 1 -> view.postDelayed({ pollToken(view, attemptsLeft - 1) }, POLL_INTERVAL_MS)
                else -> {
                    polling = false
                    AppLog.w(TAG, "Токен так и не появился за ${POLL_ATTEMPTS * POLL_INTERVAL_MS / 1000} с")
                }
            }
        }
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

/** Маршрут сайта «login»: сразу перенаправляет на форму `auth.lib.social/auth/login`. */
private const val TAG = "LOGIN"
private const val START_URL = "https://ranobelib.me/ru/front/auth"
private const val SITE_HOST = "ranobelib.me"
private const val POLL_ATTEMPTS = 60
private const val POLL_INTERVAL_MS = 500L
