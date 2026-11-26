package io.github.velcheor137.shiori

import java.io.File
import java.security.MessageDigest

/**
 * Кэш чтения глав ранобэ (п. 8): сырой JSON главы на диске, вытеснение по давности открытия (LRU).
 * Лимит — число глав: текущая + следующая + последние N прочитанных. Скачанные главы сюда не входят.
 */
class ChapterCache(private val dir: File, private val limit: () -> Int) {
    private fun file(slug: String, branchId: Long, volume: String, number: String) = File(dir, sha("$slug|$branchId|$volume|$number") + ".json")

    @Synchronized
    fun get(slug: String, branchId: Long, volume: String, number: String): String? {
        val f = file(slug, branchId, volume, number)
        if (!f.exists()) return null
        f.setLastModified(System.currentTimeMillis())
        return runCatching { f.readText() }.getOrNull()
    }

    @Synchronized
    fun contains(slug: String, branchId: Long, volume: String, number: String) = file(slug, branchId, volume, number).exists()

    @Synchronized
    fun put(slug: String, branchId: Long, volume: String, number: String, raw: String) {
        dir.mkdirs()
        val f = file(slug, branchId, volume, number)
        val tmp = File(dir, f.name + ".part")
        runCatching {
            tmp.writeText(raw)
            tmp.renameTo(f)
            f.setLastModified(System.currentTimeMillis())
        }
        trim()
    }

    /** Оставляет [limit] самых свежих глав. */
    @Synchronized
    fun trim() {
        val files = dir.listFiles { f -> f.name.endsWith(".json") }?.sortedByDescending { it.lastModified() } ?: return
        files.drop(limit().coerceAtLeast(2)).forEach { it.delete() }
        dir.listFiles { f -> f.name.endsWith(".part") }?.forEach { it.delete() }
    }

    @Synchronized
    fun count(): Int = dir.listFiles { f -> f.name.endsWith(".json") }?.size ?: 0

    @Synchronized
    fun sizeBytes(): Long = dir.listFiles()?.sumOf { it.length() } ?: 0L

    @Synchronized
    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
    }

    private fun sha(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
