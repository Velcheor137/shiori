package io.github.velcheor137.shiori

import android.app.Application
import android.content.Context
import android.os.Build
import android.webkit.WebView
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import io.github.velcheor137.shiori.core.common.AppLog
import io.github.velcheor137.shiori.core.network.ApiHostConfig
import io.github.velcheor137.shiori.core.network.LibSocialHttp
import io.github.velcheor137.shiori.core.security.TokenVault
import io.github.velcheor137.shiori.data.auth.AuthRepository
import io.github.velcheor137.shiori.data.libsocial.ChapterEntry
import io.github.velcheor137.shiori.data.libsocial.LibSocialApi
import io.github.velcheor137.shiori.data.libsocial.TitleCard
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class ShioriApp : Application(), SingletonImageLoader.Factory {
    lateinit var auth: AuthRepository
        private set
    lateinit var api: LibSocialApi
        private set
    lateinit var store: LocalStore
        private set

    /** Список глав тайтла в памяти, чтобы читалка не запрашивала его заново после карточки. */
    val chapterCache = ConcurrentHashMap<String, List<ChapterEntry>>()
    val titleCache = ConcurrentHashMap<String, TitleCard>()
    /** Подписи переводов тайтла (команды) по branchId — для меток в комментариях и читалке. */
    val branchLabels = ConcurrentHashMap<String, Map<Long, String>>()
    val accountBookmarks = ConcurrentHashMap<String, Long>()
    val appScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)

    private lateinit var userAgent: String

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
        userAgent = LibSocialHttp.userAgent(BuildConfig.VERSION_NAME, Build.VERSION.RELEASE)
        auth = AuthRepository(TokenVault(this))
        val http = LibSocialHttp(config = ApiHostConfig(), userAgent = userAgent, tokenProvider = auth)
        auth.http = http
        api = LibSocialApi(http)
        store = LocalStore(this)
    }

    /** Обложки и картинки глав: `Referer` сайта обязателен для CDN, токен туда не уходит. */
    override fun newImageLoader(context: Context): ImageLoader {
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .header("Referer", "https://ranobelib.me/")
                        .header("User-Agent", userAgent)
                        .build(),
                )
            }
            .build()
        return ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { client })) }
            .crossfade(true)
            .build()
    }
}

val Context.shiori: ShioriApp get() = applicationContext as ShioriApp
