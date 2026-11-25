package io.github.velcheor137.shiori.core.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Хранилище секрета сессии; в приложении — [TokenVault], в тестах — память. */
interface SecretStore {
    fun write(plain: ByteArray)
    fun read(): ByteArray?
    fun clear()
}

/**
 * Зашифрованное хранилище сессии (§11.1 плана): AES-256-GCM, ключ в Android Keystore.
 * Формат файла: версия(1) | iv(12) | шифротекст+тег.
 */
class TokenVault(context: Context) : SecretStore {
    private val file = File(context.filesDir, "vault/session.bin")

    @Synchronized
    override fun write(plain: ByteArray) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(plain)
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "session.bin.part")
        tmp.outputStream().use { out ->
            out.write(VERSION)
            out.write(cipher.iv)
            out.write(encrypted)
            out.fd.sync()
        }
        check(tmp.renameTo(file)) { "Не удалось сохранить сессию" }
    }

    /** null — сессии нет или ключ стал недоступен (тогда файл удаляется, нужен повторный вход). */
    @Synchronized
    override fun read(): ByteArray? {
        if (!file.exists()) return null
        return try {
            val bytes = file.readBytes()
            require(bytes.size > 1 + IV_SIZE && bytes[0].toInt() == VERSION)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, bytes, 1, IV_SIZE))
            cipher.doFinal(bytes, 1 + IV_SIZE, bytes.size - 1 - IV_SIZE)
        } catch (e: Exception) {
            clear()
            null
        }
    }

    @Synchronized
    override fun clear() {
        file.delete()
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "shiori_session_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val VERSION = 1
        const val IV_SIZE = 12
        const val TAG_BITS = 128
    }
}
