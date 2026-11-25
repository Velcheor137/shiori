package io.github.velcheor137.shiori

import android.app.Application
import android.os.Build
import android.webkit.WebView
import io.github.velcheor137.shiori.core.common.AppLog
import io.github.velcheor137.shiori.core.network.ApiHostConfig
import io.github.velcheor137.shiori.core.network.LibSocialHttp
import io.github.velcheor137.shiori.core.security.TokenVault
import io.github.velcheor137.shiori.data.auth.AuthRepository
import java.io.File

class ShioriApp : Application() {
    lateinit var auth: AuthRepository
        private set

    override fun onCreate() {
        super.onCreate()
        AppLog.init(File(filesDir, "logs"))
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            AppLog.e("CRASH", "Необработанное исключение в потоке ${thread.name}", error)
            previous?.uncaughtException(thread, error)
        }
        val webView = runCatching { WebView.getCurrentWebViewPackage() }.getOrNull()
        AppLog.i(
            "APP",
            "Запуск Shiori ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}, ${BuildConfig.BUILD_TYPE}); " +
                "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}); устройство ${Build.MANUFACTURER} ${Build.MODEL}; " +
                "WebView ${webView?.packageName} ${webView?.versionName}",
        )
        auth = AuthRepository(TokenVault(this))
        auth.http = LibSocialHttp(
            config = ApiHostConfig(),
            userAgent = LibSocialHttp.userAgent(BuildConfig.VERSION_NAME, Build.VERSION.RELEASE),
            tokenProvider = auth,
        )
    }
}
