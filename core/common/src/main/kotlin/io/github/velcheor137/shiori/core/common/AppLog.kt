package io.github.velcheor137.shiori.core.common

import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Журнал приложения для диагностики (§11.4 плана): пишется в файл на устройстве и никуда не отправляется.
 * Пользователь сам делится им с экрана «Журнал». Секреты (токены, коды OAuth, cookies) маскируются
 * в [Redactor] до записи.
 */
object AppLog {
    private const val MAX_BYTES = 2L * 1024 * 1024
    private val time = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
    private var file: File? = null
    private val memory = ArrayDeque<String>()

    @Synchronized
    fun init(dir: File) {
        dir.mkdirs()
        file = File(dir, "shiori.log")
    }

    val logFile: File? get() = file

    fun i(tag: String, message: String) = write("I", tag, message, null)

    fun w(tag: String, message: String, error: Throwable? = null) = write("W", tag, message, error)

    fun e(tag: String, message: String, error: Throwable? = null) = write("E", tag, message, error)

    @Synchronized
    fun read(): String = file?.takeIf { it.exists() }?.readText() ?: memory.joinToString("\n")

    @Synchronized
    fun clear() {
        memory.clear()
        file?.delete()
        file?.parentFile?.let { File(it, "shiori.log.1").delete() }
    }

    @Synchronized
    private fun write(level: String, tag: String, message: String, error: Throwable?) {
        val line = buildString {
            append(LocalDateTime.now().format(time)).append(' ').append(level).append(' ').append(tag).append(": ")
            append(Redactor.redact(message))
            if (error != null) {
                append("\n    ").append(error::class.java.name).append(": ").append(Redactor.redact(error.message.orEmpty()))
                error.stackTrace.take(8).forEach { append("\n      at ").append(it) }
                error.cause?.let { append("\n    caused by ").append(it::class.java.name).append(": ").append(Redactor.redact(it.message.orEmpty())) }
            }
        }
        memory.addLast(line)
        while (memory.size > 500) memory.removeFirst()
        val f = file ?: return
        runCatching {
            if (f.length() > MAX_BYTES) {
                val old = File(f.parentFile, "shiori.log.1")
                old.delete()
                f.renameTo(old)
            }
            f.appendText(line + "\n")
        }
    }
}

/** Маскирование секретов в строках журнала. */
object Redactor {
    private val rules: List<Pair<Regex, String>> = listOf(
        // JSON-поля с токенами и кодами
        Regex("(\"(?:access_token|refresh_token|token|code|code_verifier|code_challenge|state|password|cookie)\"\\s*:\\s*\")[^\"]*(\")", RegexOption.IGNORE_CASE) to "$1***$2",
        // Параметры URL
        Regex("([?&#](?:code|state|code_verifier|code_challenge|access_token|refresh_token|token|password)=)[^&#\\s]*", RegexOption.IGNORE_CASE) to "$1***",
        // Заголовки
        Regex("((?:Authorization|Cookie|Set-Cookie)\\s*[:=]\\s*)[^\\n]+", RegexOption.IGNORE_CASE) to "$1***",
        Regex("(Bearer\\s+)[A-Za-z0-9._~+/=-]+", RegexOption.IGNORE_CASE) to "$1***",
        // Длинные JWT-подобные строки
        Regex("eyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]+") to "***jwt***",
    )

    fun redact(text: String): String = rules.fold(text) { acc, (re, repl) -> re.replace(acc, repl) }
}
