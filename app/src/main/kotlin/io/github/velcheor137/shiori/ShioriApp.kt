package io.github.velcheor137.shiori

import android.app.Application
import android.content.Context
import android.os.Build
import android.webkit.WebView
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.disk.DiskCache
import coil3.request.crossfade
import kotlinx.coroutines.launch
import okio.Path.Companion.toOkioPath
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
    private lateinit var apis: Map<Source, LibSocialApi>
    /** Сеть и офлайн-режим (автоматически или вручную). */
    lateinit var net: OfflineState
        private set
    val offline: kotlinx.coroutines.flow.StateFlow<Boolean> get() = net.offline
    /** Текущий источник; экраны пересоздаются при переключении (см. MainActivity). */
    val source = kotlinx.coroutines.flow.MutableStateFlow(Source.Ranobe)
    /** API текущего источника. */
    val api: LibSocialApi get() = apis.getValue(source.value)
    fun apiFor(s: Source): LibSocialApi = apis.getValue(s)
    lateinit var store: LocalStore
        private set
    lateinit var ui: UiPrefs
        private set
    /** Страницы манги: свой дисковый кэш (500 МБ по умолчанию) и очередь загрузок. */
    val mangaImages by lazy { MangaImages(this, userAgent) }
    /** Счётчик изменений настроек манги — читалка перечитывает их. */
    val mangaSettingsVersion = androidx.compose.runtime.mutableIntStateOf(0)

    private val profileRepos = java.util.concurrent.ConcurrentHashMap<Source, ProfileRepository>()
    /** Кэш профиля текущего источника. */
    val profile: ProfileRepository get() = profileRepos.getOrPut(source.value) { ProfileRepository(filesDir, source.value) }
    fun clearProfiles() = Source.entries.forEach { profileRepos.getOrPut(it) { ProfileRepository(filesDir, it) }.clear() }

    private val filterRepos = java.util.concurrent.ConcurrentHashMap<Source, FilterConstantsRepo>()
    val filterConstants: FilterConstantsRepo get() = filterRepos.getOrPut(source.value) { FilterConstantsRepo(this, source.value) }

    /** Для глав, скачанных до появления офлайн-режима, дописываем карточку и список глав (когда есть сеть). */
    private suspend fun backfillOffline() {
        if (offline.value) return
        val dirs = DownloadFiles.root(this).listFiles()?.filter { it.isDirectory && !OfflineData.hasTitle(it) }.orEmpty()
        dirs.forEach { dir ->
            val id = dir.name.toLongOrNull() ?: return@forEach
            if (DownloadFiles.downloadedIds(this, id).isEmpty()) return@forEach
            val slug = store.downloads.value.firstOrNull { it.titleId == id }?.slug ?: store.progress(id)?.slug ?: return@forEach
            val api = apiFor(Source.Ranobe)
            runCatching { OfflineData.save(dir, api.title(slug).card, api.chaptersRaw(slug)) }
                .onSuccess { AppLog.i("OFFLINE", "Сохранены данные тайтла $id для офлайна") }
                .onFailure { AppLog.w("OFFLINE", "Не удалось сохранить данные тайтла $id", it) }
        }
    }

    fun setSource(s: Source) {
        if (s == source.value) return
        AppLog.i("APP", "Источник: ${source.value.label} → ${s.label}")
        ui.setString(UiPrefs.SOURCE, s.key)
        source.value = s
    }
    /** Кэш чтения глав: текущая + следующая + последние N (настройка в «Хранилище»). */
    val chapterTextCache by lazy { ChapterCache(File(cacheDir, "chapters")) { ui.int(UiPrefs.CHAPTER_CACHE_COUNT, 30) + 2 } }

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
        // Полный журнал в файл — только в debug-сборке; в release лишь предупреждения/ошибки в памяти (§11.4).
        AppLog.init(File(filesDir, "logs"), persist = BuildConfig.DEBUG)
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
        apis = Source.entries.associateWith { s ->
            if (s == Source.Ranobe) LibSocialApi(http, s.siteId)
            else LibSocialApi(LibSocialHttp(config = ApiHostConfig(siteOrigin = s.origin, siteId = s.siteId), userAgent = userAgent, tokenProvider = auth), s.siteId)
        }
        store = LocalStore(this)
        ui = UiPrefs(this)
        source.value = Source.of(ui.string(UiPrefs.SOURCE, Source.Ranobe.key))
        net = OfflineState(this, ui, appScope)
        appScope.launch { chapterTextCache.trim(); mangaImages.trim() }
        appScope.launch { backfillOffline() }
    }

    /** Обложки и картинки глав: `Referer` сайта обязателен для CDN, токен туда не уходит. */
    override fun newImageLoader(context: Context): ImageLoader {
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .header("Referer", source.value.origin + "/")
                        .header("User-Agent", userAgent)
                        .build(),
                )
            }
            .build()
        return ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { client })) }
            .crossfade(true)
            // Обложки и картинки комментариев — с явным лимитом (п. 8); меняется в «Хранилище», действует после перезапуска.
            .diskCache {
                DiskCache.Builder()
                    .directory(File(context.cacheDir, "image_cache").toOkioPath())
                    .maxSizeBytes(ui.int(UiPrefs.COVER_CACHE_MB, 150).toLong() * 1024 * 1024)
                    .build()
            }
            .build()
    }
}

val Context.shiori: ShioriApp get() = applicationContext as ShioriApp
