package io.github.velcheor137.shiori

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.velcheor137.shiori.core.common.AppLog
import io.github.velcheor137.shiori.data.libsocial.ApiException
import io.github.velcheor137.shiori.engine.document.ImageBlock
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest

/**
 * Скачанные главы (§10.8 плана): сырой JSON ответа API (исходник не меняется) + картинки.
 * Путь: files/downloads/{titleId}/{versionId}.json, картинки — files/downloads/{titleId}/img/{sha256(url)}.
 */
object DownloadFiles {
    fun dir(context: Context, titleId: Long) = File(context.filesDir, "downloads/$titleId")
    fun chapter(context: Context, titleId: Long, versionId: Long) = File(dir(context, titleId), "$versionId.json")
    fun image(context: Context, titleId: Long, url: String) = File(dir(context, titleId), "img/" + sha256(url))
    fun isDownloaded(context: Context, titleId: Long, versionId: Long) = chapter(context, titleId, versionId).exists()

    fun downloadedIds(context: Context, titleId: Long): Set<Long> =
        dir(context, titleId).listFiles()?.mapNotNull { it.name.removeSuffix(".json").takeIf { n -> it.name.endsWith(".json") }?.toLongOrNull() }?.toSet().orEmpty()

    fun sizeBytes(context: Context, titleId: Long): Long = dir(context, titleId).walkTopDown().filter { it.isFile }.sumOf { it.length() }

    fun deleteTitle(context: Context, titleId: Long) = dir(context, titleId).deleteRecursively()

    fun writeAtomic(file: File, bytes: ByteArray) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".part")
        tmp.outputStream().use { it.write(bytes); it.fd.sync() }
        check(tmp.renameTo(file)) { "Не удалось сохранить ${file.name}" }
    }

    private fun sha256(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}

object Downloader {
    private const val WORK = "shiori-downloads"

    fun enqueue(context: Context, tasks: List<DownloadTask>, wifiOnly: Boolean) {
        val store = context.shiori.store
        store.updateDownloads { old ->
            val keys = tasks.map { it.key }.toSet()
            old.filterNot { it.key in keys } + tasks
        }
        AppLog.i("DOWNLOAD", "В очередь добавлено глав: ${tasks.size}, только Wi‑Fi: $wifiOnly")
        start(context, wifiOnly)
    }

    fun start(context: Context, wifiOnly: Boolean = false) {
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK, ExistingWorkPolicy.KEEP, request)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK)
        context.shiori.store.updateDownloads { list -> list.filter { it.state == DownloadTask.State.Done || it.state == DownloadTask.State.Failed } }
        AppLog.i("DOWNLOAD", "Очередь отменена")
    }

    fun retryFailed(context: Context) {
        context.shiori.store.updateDownloads { list -> list.map { if (it.state == DownloadTask.State.Failed) it.copy(state = DownloadTask.State.Queued, error = null) else it } }
        start(context)
    }

    fun clearFinished(context: Context) = context.shiori.store.updateDownloads { list -> list.filter { it.state == DownloadTask.State.Queued || it.state == DownloadTask.State.Running } }
}

/** Последовательная загрузка очереди: 1 запрос к API за раз, пауза между главами, уважение 429. */
class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val app = context.applicationContext as ShioriApp
    private val imageClient by lazy { OkHttpClient() }

    override suspend fun doWork(): Result {
        val store = app.store
        store.updateDownloads { list -> list.map { if (it.state == DownloadTask.State.Running) it.copy(state = DownloadTask.State.Queued) else it } }
        setForegroundSafe(0, store.downloads.value.count { it.state == DownloadTask.State.Queued })
        var done = 0
        var rateLimits = 0
        while (true) {
            val task = store.downloads.value.firstOrNull { it.state == DownloadTask.State.Queued } ?: break
            val total = done + store.downloads.value.count { it.state == DownloadTask.State.Queued }
            setForegroundSafe(done, total, "${task.titleName}: т${task.volume} г${task.number}")
            mark(task, DownloadTask.State.Running)
            try {
                if (!DownloadFiles.isDownloaded(app, task.titleId, task.versionId)) {
                    val raw = app.api.chapterRaw(task.slug, task.volume, task.number, task.branchId)
                    val content = app.api.parseChapter(raw, task.volume, task.number, task.branchId)
                    if (content.restricted) throw ApiException(null, "Глава платная или в раннем доступе — пропущена")
                    if (task.withImages) {
                        content.blocks.filterIsInstance<ImageBlock>().forEach { img ->
                            val file = DownloadFiles.image(app, task.titleId, img.url)
                            if (!file.exists()) runCatching { fetchImage(img.url)?.let { DownloadFiles.writeAtomic(file, it) } }
                                .onFailure { AppLog.w("DOWNLOAD", "Картинка не скачалась: ${img.url}", it) }
                        }
                    }
                    DownloadFiles.writeAtomic(DownloadFiles.chapter(app, task.titleId, task.versionId), raw.toByteArray())
                }
                mark(task, DownloadTask.State.Done)
                done++
                rateLimits = 0
                delay(500)
            } catch (e: ApiException) {
                if (e.code == 429) {
                    rateLimits++
                    mark(task, DownloadTask.State.Queued)
                    val wait = 15_000L * rateLimits
                    AppLog.w("DOWNLOAD", "Лимит запросов (429), пауза ${wait / 1000} с")
                    if (rateLimits >= 5) return Result.retry()
                    delay(wait)
                } else {
                    AppLog.w("DOWNLOAD", "Глава т${task.volume} г${task.number} не скачана: ${e.message}")
                    mark(task, DownloadTask.State.Failed, e.message)
                }
            } catch (e: io.github.velcheor137.shiori.core.network.OfflineException) {
                mark(task, DownloadTask.State.Queued)
                AppLog.w("DOWNLOAD", "Нет сети — продолжу, когда появится")
                return Result.retry()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) {
                    mark(task, DownloadTask.State.Queued)
                    throw e
                }
                AppLog.e("DOWNLOAD", "Ошибка загрузки главы", e)
                mark(task, DownloadTask.State.Failed, e.message)
            }
        }
        AppLog.i("DOWNLOAD", "Очередь завершена, скачано глав: $done")
        return Result.success()
    }

    private fun mark(task: DownloadTask, state: DownloadTask.State, error: String? = null) =
        app.store.updateDownloads { list -> list.map { if (it.key == task.key) it.copy(state = state, error = error) else it } }

    private fun fetchImage(url: String): ByteArray? {
        val req = Request.Builder().url(url).header("Referer", "https://ranobelib.me/").build()
        imageClient.newCall(req).execute().use { r -> return if (r.isSuccessful) r.body.bytes() else null }
    }

    private suspend fun setForegroundSafe(done: Int, total: Int, text: String = "Подготовка…") {
        runCatching { setForeground(foregroundInfo(done, total, text)) }.onFailure { AppLog.w("DOWNLOAD", "Не удалось показать уведомление загрузки", it) }
    }

    private fun foregroundInfo(done: Int, total: Int, text: String): ForegroundInfo {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Загрузка глав", NotificationManager.IMPORTANCE_LOW))
        val n = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Shiori: загрузка глав $done из $total")
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(total.coerceAtLeast(1), done, total == 0)
            .build()
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else ForegroundInfo(NOTIFICATION_ID, n)
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(0, 0, "Подготовка…")

    private companion object {
        const val CHANNEL = "downloads"
        const val NOTIFICATION_ID = 4101
    }
}
