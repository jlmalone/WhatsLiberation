package vision.salient.export

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import vision.salient.adb.AdbClient
import vision.salient.adb.AdbCommandResult
import vision.salient.config.AppConfig
import vision.salient.config.DriveConfig
import vision.salient.config.ContactsConfig
import vision.salient.drive.DriveDownloader
import java.nio.file.Path
import java.nio.file.Files
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.ArrayDeque

class SingleChatExportRunnerTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `dry run returns intent without touching adb`() {
        val config = buildConfig()
        var adbInvoked = false
        val runner = SingleChatExportRunner(
            configProvider = { config },
            adbClientFactory = {
                adbInvoked = true
                error("ADB should not be requested during dry-run")
            },
            repositoryFactory = { LocalExportRepository(it) },
            clock = Clock.systemUTC(),
            sleeper = Sleeper { }
        )

        val result = runner.run(
            SingleChatExportRequest(
                targetChat = "Beatrice in Bali",
                dryRun = true,
                verbose = false,
                includeMedia = false,
                shareTarget = "Drive"
            )
        )

        assertTrue(result is SingleChatExportResult.DryRun)
        assertFalse(adbInvoked, "Dry-run should not instantiate the ADB client")
    }

    @Test
    fun `successful run orchestrates export workflow`() {
        val config = buildConfig()
        val responses = buildHappyPathResponses(SHARE_SHEET_MY_DRIVE_XML)

        val scriptedAdb = ScriptedAdbClient(responses)
        val clock = Clock.fixed(Instant.parse("2025-11-01T12:00:00Z"), ZoneOffset.UTC)

        val runner = SingleChatExportRunner(
            configProvider = { config },
            adbClientFactory = { scriptedAdb },
            repositoryFactory = { LocalExportRepository(it) },
            clock = clock,
            sleeper = Sleeper { }
        )

        val result = runner.run(
            SingleChatExportRequest(
                targetChat = "Beatrice in Bali",
                dryRun = false,
                verbose = false,
                includeMedia = false,
                shareTarget = "Drive"
            )
        )

        assertTrue(result is SingleChatExportResult.Success)
        val success = result as SingleChatExportResult.Success
        assertEquals("Beatrice in Bali", success.chatName)
        assertTrue(success.runDirectory.toString().contains("beatrice_in_bali/20251101-120000"))
        assertTrue(
            success.exportedFiles.any {
                val name = it.fileName.toString()
                name.contains("BEATRICE_IN_BALI_FROM_CHAT_20251101") && name.endsWith(".txt")
            },
            "Expected renamed export file"
        )
        assertTrue(scriptedAdb.responsesExhausted(), "All scripted ADB responses should be consumed")
    }

    @Test
    fun `include media selects the include button`() {
        val config = buildConfig()
        val responses = buildHappyPathResponses(SHARE_SHEET_MY_DRIVE_XML)
        val scriptedAdb = ScriptedAdbClient(responses)
        val clock = Clock.fixed(Instant.parse("2025-11-01T12:00:00Z"), ZoneOffset.UTC)

        val runner = SingleChatExportRunner(
            configProvider = { config },
            adbClientFactory = { scriptedAdb },
            repositoryFactory = { LocalExportRepository(it) },
            clock = clock,
            sleeper = Sleeper { }
        )

        val result = runner.run(
            SingleChatExportRequest(
                targetChat = "Beatrice in Bali",
                dryRun = false,
                verbose = false,
                includeMedia = true,
                shareTarget = "Drive"
            )
        )

        assertTrue(result is SingleChatExportResult.Success)
        val tapCommands = scriptedAdb.recordedCommands().filter { it.take(3) == listOf("shell", "input", "tap") }
        assertTrue(
            tapCommands.any { it == listOf("shell", "input", "tap", "775", "1270") },
            "Include-media path should tap the Include Media button"
        )
        assertTrue(scriptedAdb.responsesExhausted())
    }

    @Test
    fun `missing share target yields failure`() {
        val config = buildConfig()
        val responses = buildFailureResponses()
        val scriptedAdb = ScriptedAdbClient(responses)
        val clock = Clock.fixed(Instant.parse("2025-11-01T12:00:00Z"), ZoneOffset.UTC)

        val runner = SingleChatExportRunner(
            configProvider = { config },
            adbClientFactory = { scriptedAdb },
            repositoryFactory = { LocalExportRepository(it) },
            clock = clock,
            sleeper = Sleeper { }
        )

        val result = runner.run(
            SingleChatExportRequest(
                targetChat = "Beatrice in Bali",
                dryRun = false,
                verbose = false,
                includeMedia = false,
                shareTarget = "Drive"
            )
        )

        assertTrue(result is SingleChatExportResult.Failure)
        val failure = result as SingleChatExportResult.Failure
        assertTrue(failure.reason.contains("Unable to locate share target"))
        assertTrue(scriptedAdb.responsesExhausted(), "Failure path should consume all scripted responses")
    }

    @Test
    fun `drive downloader artifacts are appended when available`() {
        val config = buildConfig()
        val responses = buildHappyPathResponses(SHARE_SHEET_MY_DRIVE_XML)
        val scriptedAdb = ScriptedAdbClient(responses)
        val clock = Clock.fixed(Instant.parse("2025-11-01T12:00:00Z"), ZoneOffset.UTC)

        val runner = SingleChatExportRunner(
            configProvider = { config },
            adbClientFactory = { scriptedAdb },
            repositoryFactory = { LocalExportRepository(it) },
            driveDownloaderFactory = { _, _, _ -> RecordingDriveDownloader() },
            clock = clock,
            sleeper = Sleeper { }
        )

        val result = runner.run(
            SingleChatExportRequest(
                targetChat = "Beatrice in Bali",
                dryRun = false,
                verbose = false,
                includeMedia = true,
                shareTarget = "Drive"
            )
        )

        assertTrue(result is SingleChatExportResult.Success)
        val success = result as SingleChatExportResult.Success
        assertTrue(success.exportedFiles.any { it.fileName.toString().startsWith("BEATRICE_IN_BALI_FROM_CHAT_20251101") && it.fileName.toString().endsWith(".zip") })
        assertTrue(scriptedAdb.responsesExhausted())
    }

    private fun buildConfig(): AppConfig = AppConfig(
        deviceId = null,
        username = "tester",
        basePath = tempDir,
        deviceSnapshotDir = "/sdcard/tmpwhatslib",
        localSnapshotDir = tempDir,
        adbPath = tempDir.resolve("adb"),
        drive = DriveConfig(
            credentialsPath = null,
            folderName = "Conversations",
            folderId = null,
            pollTimeout = Duration.ofSeconds(10),
            pollInterval = Duration.ofMillis(500)
        ),
        contacts = ContactsConfig(
            clientSecretPath = null,
            tokenPath = null
        )
    )

    private class ScriptedAdbClient(private val responses: ArrayDeque<StubResponse>) : AdbClient {
        private val recorded = mutableListOf<List<String>>()

        override fun run(args: List<String>, timeoutMillis: Long?): AdbCommandResult {
            recorded += args
            if (args.isNotEmpty() && args.first() == "pull") {
                val target = args.getOrNull(2)?.let { Path.of(it) }
                target?.let {
                    Files.createDirectories(it.parent)
                    if (!Files.exists(it)) Files.createFile(it)
                }
            }
            val response = if (responses.isEmpty()) {
                error("No scripted response available for command: $args")
            } else {
                responses.removeFirst()
            }
            return AdbCommandResult(args, response.exitCode, response.stdout, response.stderr)
        }

        fun responsesExhausted(): Boolean = responses.isEmpty()

        fun recordedCommands(): List<List<String>> = recorded.toList()
    }

    private class RecordingDriveDownloader : DriveDownloader {
        override fun downloadExport(chatName: String, includeMedia: Boolean, runDirectory: Path, desiredFileName: String?): List<Path> {
            val fileName = desiredFileName ?: "drive-export.zip"
            val target = runDirectory.resolve(fileName)
            if (!Files.exists(target)) {
                Files.createFile(target)
            }
            return listOf(target)
        }
    }

    private data class StubResponse(
        val stdout: String = "",
        val stderr: String = "",
        val exitCode: Int = 0
    )

    companion object Fixtures {
        private const val CHAT_LIST_XML = """
            <hierarchy>
              <node resource-id="com.whatsapp:id/contact_row_container" bounds="[0,100][1080,300]">
                <node resource-id="com.whatsapp:id/conversations_row_contact_name" text="Beatrice in Bali"/>
              </node>
            </hierarchy>
        """

        private const val CHAT_DETAIL_XML = """
            <hierarchy>
              <node resource-id="com.whatsapp:id/menuitem_overflow" bounds="[900,0][1080,160]"/>
            </hierarchy>
        """

        private const val CHAT_OVERFLOW_XML = """
            <hierarchy>
              <node resource-id="com.whatsapp:id/title" text="More" bounds="[100,900][980,1040]"/>
            </hierarchy>
        """

        private const val CHAT_MORE_MENU_XML = """
            <hierarchy>
              <node resource-id="com.whatsapp:id/title" text="Export chat" bounds="[100,500][980,660]"/>
            </hierarchy>
        """

        private const val EXPORT_DIALOG_XML = """
            <hierarchy>
              <node resource-id="android:id/button3" text="Without media" bounds="[150,1200][500,1340]"/>
              <node resource-id="android:id/button1" text="Include media" bounds="[600,1200][950,1340]"/>
            </hierarchy>
        """

        private const val SHARE_SHEET_MY_DRIVE_XML = """
            <hierarchy>
              <node resource-id="android:id/text1" text="My Drive" bounds="[450,1500][900,1600]"/>
            </hierarchy>
        """

        private const val DRIVE_UPLOAD_XML = """
            <hierarchy>
              <node resource-id="com.google.android.apps.docs:id/upload_folder_autocomplete" text="My Drive" bounds="[84,1112][996,1264]"/>
              <node resource-id="com.google.android.apps.docs:id/save_button" text="Upload" bounds="[800,150][1040,280]"/>
            </hierarchy>
        """

        private const val DRIVE_UPLOAD_AFTER_FOLDER_XML = """
            <hierarchy>
              <node resource-id="com.google.android.apps.docs:id/upload_folder_autocomplete" text="Conversations" bounds="[84,1112][996,1264]"/>
              <node resource-id="com.google.android.apps.docs:id/save_button" text="Upload" bounds="[800,150][1040,280]"/>
            </hierarchy>
        """

        private const val DRIVE_FOLDER_PICKER_XML = """
            <hierarchy>
              <node resource-id="com.google.android.apps.docs:id/browse_list_item_title" text="My Drive" bounds="[84,334][996,391]"/>
            </hierarchy>
        """

        private const val DRIVE_FOLDER_PICKER_MYDRIVE_XML = """
            <hierarchy>
              <node resource-id="com.google.android.apps.docs:id/browse_list_item_title" text="Conversations" bounds="[84,600][996,700]"/>
            </hierarchy>
        """

        private const val DRIVE_FOLDER_PICKER_AFTER_SELECT_XML = """
            <hierarchy>
              <node resource-id="com.google.android.apps.docs:id/browse_list_item_title" text="Conversations" bounds="[84,600][996,700]"/>
              <node resource-id="android:id/button1" text="Select" bounds="[800,1800][1000,1900]"/>
            </hierarchy>
        """

        private const val DRIVE_POST_UPLOAD_XML = """
            <hierarchy>
              <node resource-id="android:id/text1" text="Done" bounds="[100,400][400,500]"/>
            </hierarchy>
        """

        private const val SHARE_SHEET_EMPTY_XML = """
            <hierarchy>
              <node resource-id="android:id/text1" text="Slack" bounds="[0,1500][400,1600]"/>
            </hierarchy>
        """
    }

    private fun buildHappyPathResponses(shareSheetXml: String): ArrayDeque<StubResponse> = ArrayDeque<StubResponse>().apply {
        add(StubResponse()) // mkdir
        add(StubResponse()) // launch
        add(StubResponse(stdout = "UI hierchary dumped"))
        add(StubResponse(stdout = CHAT_LIST_XML))
        add(StubResponse()) // tap chat
        add(StubResponse(stdout = "UI hierchary dumped"))
        add(StubResponse(stdout = CHAT_DETAIL_XML))
        add(StubResponse()) // tap overflow
        add(StubResponse(stdout = "UI hierchary dumped"))
        add(StubResponse(stdout = CHAT_OVERFLOW_XML))
        add(StubResponse()) // tap more
        add(StubResponse(stdout = "UI hierchary dumped"))
        add(StubResponse(stdout = CHAT_MORE_MENU_XML))
        add(StubResponse()) // tap export
        add(StubResponse(stdout = "UI hierchary dumped"))
        add(StubResponse(stdout = EXPORT_DIALOG_XML))
        add(StubResponse()) // confirm button
        add(StubResponse(stdout = "UI hierchary dumped"))
        add(StubResponse(stdout = shareSheetXml))
        add(StubResponse()) // tap share target
        add(StubResponse(stdout = "UI hierchary dumped"))
        add(StubResponse(stdout = DRIVE_UPLOAD_XML))
        add(StubResponse()) // tap folder field
        add(StubResponse(stdout = "UI hierchary dumped"))
        add(StubResponse(stdout = DRIVE_FOLDER_PICKER_XML))
        add(StubResponse()) // tap My Drive
        add(StubResponse(stdout = "UI hierchary dumped"))
        add(StubResponse(stdout = DRIVE_FOLDER_PICKER_MYDRIVE_XML))
        add(StubResponse()) // tap folder option
        add(StubResponse(stdout = "UI hierchary dumped"))
        add(StubResponse(stdout = DRIVE_FOLDER_PICKER_AFTER_SELECT_XML))
        add(StubResponse()) // tap select button
        add(StubResponse(stdout = "UI hierchary dumped"))
        add(StubResponse(stdout = DRIVE_UPLOAD_AFTER_FOLDER_XML))
        add(StubResponse()) // tap upload
        add(StubResponse(stdout = "UI hierchary dumped"))
        add(StubResponse(stdout = DRIVE_POST_UPLOAD_XML))
        add(StubResponse(stdout = "WhatsApp Chat with Beatrice in Bali.txt\n"))
        add(StubResponse(stdout = ""))
        add(StubResponse()) // pull
        add(StubResponse()) // cleanup immediate
        add(StubResponse()) // cleanup finally
    }

    private fun buildFailureResponses(): ArrayDeque<StubResponse> = ArrayDeque<StubResponse>().apply {
        add(StubResponse()) // mkdir
        add(StubResponse()) // launch
        add(StubResponse(stdout = "UI hierchary dumped"))
        add(StubResponse(stdout = CHAT_LIST_XML))
        add(StubResponse()) // tap chat
        add(StubResponse(stdout = "UI hierchary dumped"))
        add(StubResponse(stdout = CHAT_DETAIL_XML))
        add(StubResponse()) // tap overflow
        add(StubResponse(stdout = "UI hierchary dumped"))
        add(StubResponse(stdout = CHAT_OVERFLOW_XML))
        add(StubResponse()) // tap more
        add(StubResponse(stdout = "UI hierchary dumped"))
        add(StubResponse(stdout = CHAT_MORE_MENU_XML))
        add(StubResponse()) // tap export
        add(StubResponse(stdout = "UI hierchary dumped"))
        add(StubResponse(stdout = EXPORT_DIALOG_XML))
        add(StubResponse()) // confirm button
        add(StubResponse(stdout = "UI hierchary dumped"))
        add(StubResponse(stdout = SHARE_SHEET_EMPTY_XML))
        add(StubResponse()) // swipe attempt 1
        add(StubResponse(stdout = "UI hierchary dumped"))
        add(StubResponse(stdout = SHARE_SHEET_EMPTY_XML))
        add(StubResponse()) // swipe attempt 2
        add(StubResponse(stdout = "UI hierchary dumped"))
        add(StubResponse(stdout = SHARE_SHEET_EMPTY_XML))
        add(StubResponse()) // cleanup (finally)
    }
}
