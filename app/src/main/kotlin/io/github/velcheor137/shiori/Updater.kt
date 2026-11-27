package io.github.velcheor137.shiori

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.IntentCompat
import io.github.velcheor137.shiori.core.common.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Версия так же, как в app/build.gradle.kts: MAJOR*1_000_000 + MINOR*10_000 + PATCH*100 + стадия (alpha 1–29, beta 31–59, rc 61–89, финал 99). */
object AppVersion {
    private val re = Regex("""^v?(\d+)\.(\d+)\.(\d+)(?:-(alpha|beta|rc)\.(\d+))?$""")

    fun code(version: String): Int? {
        val m = re.matchEntire(version.trim()) ?: return null
        val (major, minor, patch, stage, n) = m.destructured
        val num = n.toIntOrNull() ?: 0
        if (stage.isNotEmpty() && num !in 1..29) return null
        val stageCode = when (stage) {
            "alpha" -> num
            "beta" -> 30 + num
            "rc" -> 60 + num
            else -> 99
        }
        return major.toInt() * 1_000_000 + minor.toInt() * 10_000 + patch.toInt() * 100 + stageCode
    }
}

/** Релиз на GitHub с APK и файлом контрольной суммы (`shiori-X.apk`, `shiori-X.apk.sha256`). */
data class AppRelease(
    val tag: String,
    val version: String,
    val code: Int,
    val apkName: String,
    val apkUrl: String,
    val apkSize: Long,
    val shaUrl: String?,
    val notes: String,
    val prerelease: Boolean,
)

object ReleaseParser {
    private val json = Json { ignoreUnknownKeys = true }

    /** Ответ `GET /repos/{owner}/{repo}/releases`: черновики и релизы без APK пропускаются. */
    fun parse(body: String): List<AppRelease> {
        val arr = json.parseToJsonElement(body) as? JsonArray ?: return emptyList()
        return arr.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            if (o.bool("draft") == true) return@mapNotNull null
            val tag = o.str("tag_name") ?: return@mapNotNull null
            val code = AppVersion.code(tag) ?: return@mapNotNull null
            val assets = (o["assets"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            val apk = assets.firstOrNull { it.str("name")?.endsWith(".apk") == true } ?: return@mapNotNull null
            val apkName = apk.str("name")!!
            val sha = assets.firstOrNull { it.str("name") == "$apkName.sha256" }
            AppRelease(
                tag = tag,
                version = tag.removePrefix("v"),
                code = code,
                apkName = apkName,
                apkUrl = apk.str("browser_download_url") ?: return@mapNotNull null,
                apkSize = (apk["size"] as? JsonPrimitive)?.longOrNull ?: 0L,
                shaUrl = sha?.str("browser_download_url"),
                notes = cleanNotes(o.str("body").orEmpty()),
                prerelease = o.bool("prerelease") == true,
            )
        }
    }

    /** Самый новый релиз новее установленной версии. */
    fun newest(list: List<AppRelease>, currentCode: Int): AppRelease? = list.filter { it.code > currentCode }.maxByOrNull { it.code }

    /** Текст релиза без служебной приписки про проверку файла (после `---`). */
    fun cleanNotes(body: String): String = body.replace("\r", "").substringBefore("\n---").trim()

    /** Первая «слово» файла `.sha256` (формат `sha256sum`). */
    fun parseSha(text: String): String? = text.trim().split(Regex("\\s+")).firstOrNull()?.lowercase()?.takeIf { it.matches(Regex("[0-9a-f]{64}")) }

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.bool(k: String) = (this[k] as? JsonPrimitive)?.booleanOrNull
}

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val release: AppRelease) : UpdateState
    /** [progress] 0..1, или -1, если размер неизвестен. */
    data class Downloading(val release: AppRelease, val progress: Float) : UpdateState
    /** Нужно разрешение «Установка неизвестных приложений» для Shiori. */
    data class NeedsPermission(val release: AppRelease, val file: File) : UpdateState
    data class Installing(val release: AppRelease) : UpdateState
    /** Система просит подтвердить установку — окно открывает активность. */
    data class Confirm(val release: AppRelease, val intent: Intent) : UpdateState
    data class Failed(val message: String, val release: AppRelease?) : UpdateState
}

/**
 * Обновления с GitHub Releases: проверка при запуске (не чаще раза в 6 часов, только release-сборка),
 * скачивание APK в кэш, проверка sha256, имени пакета и подписи, установка через [PackageInstaller].
 * Когда Shiori уже ставила себя сама, Android 12+ обновляет без лишнего подтверждения.
 */
class Updater(private val app: ShioriApp, private val userAgent: String) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state
    /** Показывать ли окно обновления (его можно скрыть, загрузка продолжится). */
    val prompt = MutableStateFlow(false)
    private var job: Job? = null
    private val dir get() = File(app.cacheDir, "updates")
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .addInterceptor { chain -> chain.proceed(chain.request().newBuilder().header("User-Agent", userAgent).build()) }
        .build()

    val supported: Boolean get() = !BuildConfig.DEBUG
    val autoEnabled: Boolean get() = app.ui.bool(UiPrefs.UPDATE_AUTO, true)

    fun setAuto(v: Boolean) = app.ui.setBool(UiPrefs.UPDATE_AUTO, v)

    fun autoCheck() {
        if (!supported || !autoEnabled || app.offline.value) return
        if (System.currentTimeMillis() - app.ui.long(UiPrefs.UPDATE_LAST_CHECK, 0L) < CHECK_INTERVAL_MS) return
        if (_state.value !is UpdateState.Idle && _state.value !is UpdateState.UpToDate && _state.value !is UpdateState.Failed) return
        check(manual = false)
    }

    fun check(manual: Boolean) {
        if (job?.isActive == true) return
        job = app.appScope.launch {
            _state.value = UpdateState.Checking
            runCatching { fetchReleases() }
                .onSuccess { list ->
                    app.ui.setLong(UiPrefs.UPDATE_LAST_CHECK, System.currentTimeMillis())
                    val next = ReleaseParser.newest(list, BuildConfig.VERSION_CODE)
                    AppLog.i(TAG, "Проверка обновлений: установлена ${BuildConfig.VERSION_NAME}, новее — ${next?.tag ?: "нет"}")
                    if (next == null) {
                        _state.value = UpdateState.UpToDate
                    } else {
                        _state.value = UpdateState.Available(next)
                        prompt.value = true
                    }
                }
                .onFailure {
                    if (it is CancellationException) throw it
                    AppLog.w(TAG, "Проверка обновлений не удалась", it)
                    _state.value = if (manual) UpdateState.Failed("Не удалось проверить: ${it.userMessage()}", null) else UpdateState.Idle
                }
        }
    }

    fun download(r: AppRelease) {
        if (job?.isActive == true) return
        prompt.value = true
        job = app.appScope.launch {
            runCatching {
                val file = fetchApk(r)
                verify(file, r)
                file
            }.onSuccess { install(r, it) }
                .onFailure {
                    if (it is CancellationException) throw it
                    AppLog.e(TAG, "Обновление ${r.tag} не скачалось", it)
                    _state.value = UpdateState.Failed(it.message ?: it.userMessage(), r)
                }
        }
    }

    /** Повтор установки (после выдачи разрешения). */
    fun install(r: AppRelease, file: File) {
        if (!app.packageManager.canRequestPackageInstalls()) {
            _state.value = UpdateState.NeedsPermission(r, file)
            return
        }
        _state.value = UpdateState.Installing(r)
        runCatching {
            val installer = app.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(app.packageName)
                setSize(file.length())
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
            val id = installer.createSession(params)
            installer.openSession(id).use { session ->
                session.openWrite("base.apk", 0, file.length()).use { out ->
                    file.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                val callback = PendingIntent.getBroadcast(
                    app,
                    id,
                    Intent(app, UpdateInstallReceiver::class.java).putExtra(EXTRA_TAG, r.tag),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                )
                session.commit(callback.intentSender)
            }
            AppLog.i(TAG, "Установка ${r.tag} передана системе")
        }.onFailure {
            AppLog.e(TAG, "Установка ${r.tag} не началась", it)
            _state.value = UpdateState.Failed("Установка не началась: ${it.message}", r)
        }
    }

    internal fun onInstallStatus(status: Int, message: String?, confirm: Intent?) {
        val r = when (val s = _state.value) {
            is UpdateState.Installing -> s.release
            is UpdateState.Confirm -> s.release
            else -> null
        }
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> if (confirm != null && r != null) {
                _state.value = UpdateState.Confirm(r, confirm)
                prompt.value = true
            }
            PackageInstaller.STATUS_SUCCESS -> AppLog.i(TAG, "Обновление установлено")
            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                AppLog.i(TAG, "Установка отменена")
                _state.value = r?.let { UpdateState.Available(it) } ?: UpdateState.Idle
            }
            else -> {
                AppLog.w(TAG, "Установка не прошла: статус $status, $message")
                val why = when (status) {
                    PackageInstaller.STATUS_FAILURE_CONFLICT -> "конфликт с установленной версией (другая подпись?)"
                    PackageInstaller.STATUS_FAILURE_STORAGE -> "не хватает места"
                    PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "несовместимо с устройством"
                    PackageInstaller.STATUS_FAILURE_BLOCKED -> "заблокировано системой"
                    else -> message ?: "код $status"
                }
                _state.value = UpdateState.Failed("Установка не прошла: $why", r)
            }
        }
    }

    /** Окно подтверждения открыто — ждём результата. */
    fun confirmShown(r: AppRelease) {
        if (_state.value is UpdateState.Confirm) _state.value = UpdateState.Installing(r)
    }

    fun hide() {
        prompt.value = false
        val s = _state.value
        if (s is UpdateState.Failed || s is UpdateState.UpToDate) _state.value = UpdateState.Idle
    }

    private fun fetchReleases(): List<AppRelease> {
        val req = Request.Builder().url("https://api.github.com/repos/$REPO/releases?per_page=15")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("GitHub ответил HTTP ${r.code}")
            return ReleaseParser.parse(r.body.string())
        }
    }

    private fun fetchApk(r: AppRelease): File {
        dir.mkdirs()
        val target = File(dir, r.apkName)
        dir.listFiles()?.filter { it.name != target.name }?.forEach { it.delete() }
        _state.value = UpdateState.Downloading(r, if (r.apkSize > 0) 0f else -1f)
        if (target.exists() && target.length() == r.apkSize) return target
        val part = File(dir, r.apkName + ".part")
        client.newCall(Request.Builder().url(r.apkUrl).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Файл не скачался: HTTP ${resp.code}")
            val total = resp.body.contentLength().takeIf { it > 0 } ?: r.apkSize
            var done = 0L
            var shown = -1
            resp.body.byteStream().use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val pct = (done * 100 / total).toInt()
                            if (pct != shown) {
                                shown = pct
                                _state.value = UpdateState.Downloading(r, pct / 100f)
                            }
                        }
                    }
                }
            }
        }
        if (!part.renameTo(target)) throw IOException("Не удалось сохранить файл")
        return target
    }

    private fun verify(file: File, r: AppRelease) {
        val shaUrl = r.shaUrl ?: run { file.delete(); throw IOException("В релизе нет контрольной суммы — установка отменена") }
        val expected = client.newCall(Request.Builder().url(shaUrl).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Контрольная сумма не скачалась: HTTP ${resp.code}")
            ReleaseParser.parseSha(resp.body.string()) ?: throw IOException("Файл контрольной суммы не распознан")
        }
        val actual = MessageDigest.getInstance("SHA-256").let { md ->
            file.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            md.digest().joinToString("") { "%02x".format(it) }
        }
        if (actual != expected) {
            file.delete()
            throw IOException("Контрольная сумма не совпала — файл повреждён, скачайте ещё раз")
        }
        val pm = app.packageManager
        val archive = pm.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNING_CERTIFICATES)
            ?: run { file.delete(); throw IOException("Файл не похож на APK") }
        if (archive.packageName != app.packageName) {
            file.delete()
            throw IOException("В файле другое приложение (${archive.packageName})")
        }
        val installed = pm.getPackageInfo(app.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val sign = { info: android.content.pm.PackageInfo ->
            info.signingInfo?.apkContentsSigners.orEmpty().map { s -> MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) } }.toSet()
        }
        if (sign(archive) != sign(installed)) {
            file.delete()
            throw IOException("Подпись нового APK не совпадает с установленной версией")
        }
        AppLog.i(TAG, "APK ${r.tag} проверен: sha256, пакет и подпись совпадают")
    }

    companion object {
        const val REPO = "Velcheor137/shiori"
        const val EXTRA_TAG = "update_tag"
        private const val TAG = "UPDATE"
        private const val CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L
    }
}

/** Результат установки из [PackageInstaller]. */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
        context.shiori.updater.onInstallStatus(status, message, confirm)
    }
}
