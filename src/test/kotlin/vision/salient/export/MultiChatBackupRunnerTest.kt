package vision.salient.export

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import vision.salient.adb.AdbClient
import vision.salient.adb.AdbCommandResult
import vision.salient.config.AppConfig
import vision.salient.config.DriveConfig
import vision.salient.config.ContactsConfig
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.Clock

class MultiChatBackupRunnerTest {

    @Test
    fun `collects chats across pages and delegates to single runner`() {
        val baseDir = Files.createTempDirectory("multi-run")
        val config = appConfig(baseDir)
        val adbClient = CollectorAdbClient()
        val recordedRequests = mutableListOf<SingleChatExportRequest>()
        val singleExecutor = SingleChatExecutor { request ->
            recordedRequests += request
            SingleChatExportResult.Success(request.targetChat ?: "chat", baseDir, emptyList())
        }

        val runner = MultiChatBackupRunner(
            configProvider = { config },
            adbClientFactory = { adbClient },
            singleRunnerFactory = { singleExecutor },
            clock = Clock.fixed(Instant.parse("2025-11-01T12:00:00Z"), ZoneOffset.UTC)
        )

        val result = runner.run(
            MultiChatBackupRequest(
                includeMedia = false,
                shareTarget = "Drive",
                driveFolder = "Conversations",
                dryRun = false,
                verbose = false,
                maxChats = 4,
                targetChats = null
            )
        )

        assertEquals(listOf("Alice", "Alice", "Bob", "Charlie"), result.successfulChats)
        assertEquals(4, recordedRequests.size)
        assertEquals(1, recordedRequests.first().matchIndex)
        assertEquals(2, recordedRequests[1].matchIndex) // Bob occurrence 2
    }

    @Test
    fun `dry run reports planned chats`() {
        val baseDir = Files.createTempDirectory("multi-run-dry")
        val config = appConfig(baseDir)
        val adbClient = CollectorAdbClient()
        val runner = MultiChatBackupRunner(
            configProvider = { config },
            adbClientFactory = { adbClient },
            singleRunnerFactory = {
                SingleChatExecutor { SingleChatExportResult.DryRun(it.targetChat) }
            }
        )

        val result = runner.run(
            MultiChatBackupRequest(
                includeMedia = false,
                shareTarget = "Drive",
                driveFolder = "Conversations",
                dryRun = true,
                verbose = false,
                maxChats = 2,
                targetChats = null
            )
        )

        assertTrue(result.successfulChats.isEmpty())
        assertEquals(listOf("Alice", "Alice"), result.skippedChats)
    }

    private fun appConfig(base: Path): AppConfig = AppConfig(
        deviceId = null,
        username = "tester",
        basePath = base,
        deviceSnapshotDir = "/sdcard/tmpwhats",
        localSnapshotDir = base,
        adbPath = base.resolve("adb"),
        drive = DriveConfig(
            credentialsPath = null,
            folderName = "Conversations",
            folderId = null,
            pollTimeout = Duration.ofSeconds(60),
            pollInterval = Duration.ofMillis(1000)
        ),
        contacts = ContactsConfig(
            clientSecretPath = null,
            tokenPath = null
        )
    )

    private class CollectorAdbClient : AdbClient {
        private var catCounter = 0

        override fun run(args: List<String>, timeoutMillis: Long?): AdbCommandResult {
            if (args.isEmpty()) return AdbCommandResult(args, 0, "", "")
            if (args.first() != "shell") {
                return AdbCommandResult(args, 0, "", "")
            }
            val cmd = args.drop(1)
            return when (cmd.firstOrNull()) {
                "uiautomator" -> AdbCommandResult(args, 0, "UI hierchary dumped", "")
                "cat" -> {
                    val xml = when (catCounter++) {
                        0 -> PAGE_ONE
                        1 -> PAGE_TWO
                        else -> PAGE_EMPTY
                    }
                    AdbCommandResult(args, 0, xml, "")
                }
                else -> AdbCommandResult(args, 0, "", "")
            }
        }

        companion object Xml {
            private const val TEMPLATE = """
                <hierarchy rotation="0">
                  <node resource-id="com.whatsapp:id/contact_row_container" bounds="[0,100][1080,300]">
                    <node resource-id="com.whatsapp:id/conversations_row_contact_name" text="%s"/>
                  </node>
                  <node resource-id="com.whatsapp:id/contact_row_container" bounds="[0,320][1080,520]">
                    <node resource-id="com.whatsapp:id/conversations_row_contact_name" text="%s"/>
                  </node>
                </hierarchy>
            """
            private val PAGE_ONE = TEMPLATE.format("Alice", "Alice")
            private val PAGE_TWO = TEMPLATE.format("Bob", "Charlie")
            private const val PAGE_EMPTY = "<hierarchy rotation=\"0\"/>"
        }
    }
}
