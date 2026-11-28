package io.github.velcheor137.shiori

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.util.LruCache
import io.github.velcheor137.shiori.core.common.AppLog
import io.github.velcheor137.shiori.data.libsocial.ImageServers
import io.github.velcheor137.shiori.data.libsocial.MangaPage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Фрагмент страницы по высоте исходника: длинные вебтун-полосы режутся, чтобы не декодировать гигантский bitmap. */
data class Tile(val page: Int, val index: Int, val top: Int, val bottom: Int, val width: Int, val full: Int = bottom) {
    val aspect: Float get() = width.toFloat() / (bottom - top).coerceAtLeast(1)
}

object MangaTiles {
    /** Высота фрагмента: не больше 3000 px исходника и не больше ~4 Мп. */
    fun split(page: Int, width: Int, height: Int): List<Tile> {
        if (width <= 0 || height <= 0) return listOf(Tile(page, 0, 0, 0, 0))
        val max = minOf(3000, 4_000_000 / width).coerceAtLeast(256)
        if (height <= max * 6 / 5) return listOf(Tile(page, 0, 0, height, width, height))
        val n = (height + max - 1) / max
        val step = (height + n - 1) / n
        return (0 until n).map { i -> Tile(page, i, i * step, minOf(height, (i + 1) * step), width, height) }
    }
}

/**
 * Страницы манги (этап B): дисковый кэш со своим лимитом (по умолчанию 500 МБ, LRU), перебор серверов,
 * не больше 5 загрузок одновременно (2 — для видимых, 3 — для предзагрузки), пауза 0.2 с между запросами,
 * повтор на 429/5xx. Скачанное декодируется фрагментами через [BitmapRegionDecoder].
 */
class MangaImages(private val app: ShioriApp, userAgent: String) {
    private val dir = File(app.cacheDir, "manga")
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor { chain -> chain.proceed(chain.request().newBuilder().header("User-Agent", userAgent).build()) }
        .build()
    private val visible = Semaphore(2)
    private val background = Semaphore(3)
    private val pacing = Mutex()
    private var lastStart = 0L
    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<File>>()
    /** Серверы картинок у каждого сайта свои (MangaLib — imglib/cdnlibs, HentaiLib — hentaicdn). */
    private val servers = HashMap<Source, List<String>>()
    private val serversLock = Mutex()
    private val preferred = ConcurrentHashMap<Source, Int>()
    private var writes = 0
    private val memory = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 6).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    val limitBytes: Long get() = app.ui.int(UiPrefs.MANGA_CACHE_MB, 500).toLong() * 1024 * 1024

    fun file(path: String) = File(dir, sha(path))

    fun isCached(path: String) = file(path).exists()

    private suspend fun serverList(src: Source): List<String> = serversLock.withLock {
        servers[src] ?: runCatching { app.apiFor(src).imageServers().ifEmpty { throw IOException("Пустой список серверов") } }
            .onFailure { AppLog.w("MANGA", "Список серверов ${src.label} не загрузился, беру запасной", it) }
            .getOrDefault(ImageServers.fallback(src.siteId))
            .also { servers[src] = it; AppLog.i("MANGA", "Серверы картинок ${src.label}: $it") }
    }

    /**
     * Файл страницы: из кэша или с сервера. [priority] — страница на экране: у неё свои слоты, и она не стоит
     * в очереди предзагрузки (фоновая загрузка занимает место в общем списке, только когда реально начинается).
     */
    suspend fun load(path: String, priority: Boolean): File {
        val f = file(path)
        if (f.exists()) {
            f.setLastModified(System.currentTimeMillis())
            return f
        }
        return (if (priority) visible else background).withPermit { fetch(path, f) }
    }

    private suspend fun fetch(path: String, f: File): File {
        if (f.exists()) return f
        val mine = CompletableDeferred<File>()
        val existing = inFlight.putIfAbsent(path, mine)
        if (existing != null) return existing.await()
        try {
            return download(path, f).also { mine.complete(it) }
        } catch (e: Throwable) {
            mine.completeExceptionally(e)
            throw e
        } finally {
            inFlight.remove(path)
        }
    }

    private suspend fun download(path: String, target: File): File = withContext(Dispatchers.IO) {
        if (target.exists()) return@withContext target
        // Страницы — того сайта, что открыт сейчас (экраны пересоздаются при смене источника).
        val src = app.source.value.takeIf { it.images } ?: Source.Manga
        val list = serverList(src)
        var last: Exception? = null
        for (k in list.indices) {
            val si = ((preferred[src] ?: 0) + k) % list.size
            val url = ImageServers.url(list[si], path)
            for (attempt in 0 until 3) {
                pace()
                val code = try {
                    client.newCall(Request.Builder().url(url).header("Referer", src.origin + "/").build()).execute().use { r ->
                        if (r.isSuccessful) {
                            val bytes = r.body.bytes()
                            dir.mkdirs()
                            val tmp = File(dir, target.name + ".part")
                            tmp.writeBytes(bytes)
                            if (!tmp.renameTo(target)) throw IOException("Не сохранил страницу")
                            preferred[src] = si
                            if (++writes % 20 == 0) trim()
                            return@withContext target
                        }
                        r.code
                    }
                } catch (e: IOException) {
                    last = e
                    -1
                }
                // 429/5xx и обрывы — повтор с паузой; 403/404 — следующий сервер.
                if (code == 429 || code >= 500 || code == -1) {
                    if (code != -1) last = IOException("HTTP $code")
                    delay(800L * (attempt + 1))
                    continue
                }
                last = IOException("HTTP $code")
                break
            }
            AppLog.w("MANGA", "Сервер ${list[si]} не отдал страницу: ${last?.message}")
        }
        throw last ?: IOException("Страница не загрузилась")
    }

    private suspend fun pace() = pacing.withLock {
        val wait = lastStart + 200 - System.currentTimeMillis()
        if (wait > 0) delay(wait)
        lastStart = System.currentTimeMillis()
    }

    /** Размер скачанной страницы (если API не прислал). */
    fun bounds(file: File): Pair<Int, Int> {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, o)
        return o.outWidth to o.outHeight
    }

    /** Фрагмент страницы под ширину экрана [targetWidth] (уменьшение только кратно двум). */
    suspend fun tile(path: String, tile: Tile, targetWidth: Int, priority: Boolean = true): Bitmap {
        val key = "$path#${tile.top}-${tile.bottom}@$targetWidth"
        memory.get(key)?.let { return it }
        val f = load(path, priority)
        return withContext(Dispatchers.IO) {
            val (w, h) = bounds(f)
            var sample = 1
            while (w / (sample * 2) >= targetWidth) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = if (tile.bottom <= 0 || (tile.top == 0 && tile.bottom >= tile.full)) {
                BitmapFactory.decodeFile(f.path, opts)
            } else {
                // Координаты фрагмента — в размерах из API; если файл другого размера, масштабируем.
                val k = if (tile.width > 0) w.toFloat() / tile.width else 1f
                val decoder = BitmapRegionDecoder.newInstance(f.path)
                try {
                    decoder.decodeRegion(Rect(0, (tile.top * k).toInt().coerceIn(0, h - 1), w, (tile.bottom * k).toInt().coerceIn(1, h)), opts)
                } finally {
                    decoder.recycle()
                }
            } ?: run {
                f.delete() // битый файл — скачаем заново при повторе
                throw IOException("Не удалось декодировать страницу ($w×$h)")
            }
            memory.put(key, bmp)
            bmp
        }
    }

    /** Целая страница, вписанная в [maxSide] (для постраничных режимов). */
    suspend fun whole(path: String, maxSide: Int): Bitmap {
        val key = "$path@whole$maxSide"
        memory.get(key)?.let { return it }
        val f = load(path, priority = true)
        return withContext(Dispatchers.IO) {
            val (w, h) = bounds(f)
            var sample = 1
            while (maxOf(w, h) / sample > maxSide * 2 || (w / sample) * (h / sample) * 4L > 60_000_000L) sample *= 2
            val bmp = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: run { f.delete(); throw IOException("Не удалось декодировать страницу") }
            memory.put(key, bmp)
            bmp
        }
    }

    /**
     * Постепенная предзагрузка: страницы по порядку от [from] (затем начало главы), через очередь фоновых
     * загрузок. Ошибки не прерывают очередь — видимая страница повторит загрузку сама.
     */
    suspend fun preload(pages: List<MangaPage>, from: Int) {
        val order = pages.indices.drop(from.coerceIn(0, pages.size)) + pages.indices.take(from.coerceIn(0, pages.size))
        kotlinx.coroutines.coroutineScope {
            order.forEach { i ->
                val p = pages[i].path
                if (!isCached(p)) launch { runCatching { load(p, priority = false) } }
            }
        }
    }

    @Synchronized
    fun trim() {
        val files = dir.listFiles()?.filter { it.isFile && !it.name.endsWith(".part") }?.sortedByDescending { it.lastModified() } ?: return
        var total = 0L
        files.forEach { f ->
            total += f.length()
            if (total > limitBytes) f.delete()
        }
    }

    fun sizeBytes(): Long = dir.listFiles()?.sumOf { it.length() } ?: 0L

    fun clear() {
        memory.evictAll()
        dir.listFiles()?.forEach { it.delete() }
    }

    private fun sha(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
