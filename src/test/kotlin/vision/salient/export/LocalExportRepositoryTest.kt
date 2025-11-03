package vision.salient.export

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.time.ZoneOffset
import java.time.ZonedDateTime

class LocalExportRepositoryTest {

    @Test
    fun `ensureRootExists creates directory if missing`() {
        val tempDir = Files.createTempDirectory("wl-root").resolve("exports")
        val repository = LocalExportRepository(tempDir)

        repository.ensureRootExists()

        assertTrue(Files.exists(tempDir))
    }

    @Test
    fun `createRunDirectory builds deterministic structure`() {
        val tempDir = Files.createTempDirectory("wl-root")
        val repository = LocalExportRepository(tempDir)
        val timestamp = ZonedDateTime.of(2024, 10, 12, 15, 30, 5, 0, ZoneOffset.UTC).toInstant()

        val runDir = repository.createRunDirectory("Alice & Bob Chat", timestamp)

        assertTrue(Files.exists(runDir))
        assertEquals(tempDir.resolve("alice_bob_chat").resolve("20241012-153005"), runDir)
    }

    @Test
    fun `sanitizeChatName falls back to generic name when empty`() {
        val tempDir = Files.createTempDirectory("wl-root")
        val repository = LocalExportRepository(tempDir)

        assertEquals("chat", repository.sanitizeChatName("   "))
        assertEquals("hello_world", repository.sanitizeChatName("Hello   World"))
        assertEquals("hello_chat", repository.sanitizeChatName("Hello/Chat"))
    }
}
