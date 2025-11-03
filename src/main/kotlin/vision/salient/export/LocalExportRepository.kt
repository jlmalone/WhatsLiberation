package vision.salient.export

import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

class LocalExportRepository(private val root: Path) {
    private val logger by lazy { LoggerFactory.getLogger(LocalExportRepository::class.java) }
    private val timestampFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC)

    fun ensureRootExists(): Path {
        if (!Files.exists(root)) {
            Files.createDirectories(root)
            logger.info("Created export root at {}", root)
        }
        return root
    }

    fun createRunDirectory(chatName: String, timestamp: Instant = Instant.now()): Path {
        val safeChat = sanitizeChatName(chatName)
        val runDir = root.resolve(safeChat).resolve(timestampFormatter.format(timestamp))
        Files.createDirectories(runDir)
        return runDir
    }

    fun sanitizeChatName(chatName: String): String {
        val normalized = chatName.trim().replace("\u00A0", " ")
        val collapsedWhitespace = normalized.replace("\\s+".toRegex(), "_")
        val sanitized = collapsedWhitespace.replace("[^A-Za-z0-9_-]".toRegex(), "_")
        val collapsedDelimiters = sanitized.replace("_+".toRegex(), "_")
        val trimmed = collapsedDelimiters.trim('_').lowercase()
        return trimmed.ifBlank { "chat" }.take(MAX_FOLDER_NAME_LENGTH)
    }

    companion object {
        private const val MAX_FOLDER_NAME_LENGTH = 80
    }
}
