package io.github.velcheor137.shiori

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class ProfileRepositoryTest {
    @Test
    fun publishesOnlyChangesAndPersists() {
        val dir = Files.createTempDirectory("profile").toFile()
        val repo = ProfileRepository(dir, Source.Ranobe)
        val first = ProfileData(7, ProfileUser(7, "vel"), listOf(ProfileFolder(1, "Читаю", 3)), updatedAt = 1)
        repo.publish(first)
        // То же содержимое с новым временем — экран не перерисовывается (значение то же).
        repo.publish(first.copy(updatedAt = 2))
        assertSame(first, repo.data.value)
        val changed = first.copy(folders = listOf(ProfileFolder(1, "Читаю", 4)), updatedAt = 3)
        repo.publish(changed)
        assertEquals(changed, repo.data.value)
        // Новый экземпляр читает сохранённое с диска сразу.
        assertEquals(changed, ProfileRepository(dir, Source.Ranobe).data.value)
        assertNull(ProfileRepository(dir, Source.Manga).data.value)
        repo.clear()
        assertNull(ProfileRepository(dir, Source.Ranobe).data.value)
        dir.deleteRecursively()
    }
}
