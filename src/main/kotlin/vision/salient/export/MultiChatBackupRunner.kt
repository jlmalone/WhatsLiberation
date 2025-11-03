package vision.salient.export

import org.slf4j.LoggerFactory
import vision.salient.WhatsAppXmlParser
import vision.salient.adb.AdbClient
import vision.salient.adb.AdbCommandResult
import vision.salient.adb.RealAdbClient
import vision.salient.config.AppConfig
import vision.salient.config.Config
import java.nio.file.Path
import java.time.Clock
import java.util.LinkedHashSet

class MultiChatBackupRunner(
    private val configProvider: () -> AppConfig = { Config.appConfig },
    private val adbClientFactory: (AppConfig) -> AdbClient = { RealAdbClient(it) },
    private val singleRunnerFactory: () -> SingleChatExecutor = {
        val runner = SingleChatExportRunner()
        SingleChatExecutor { runner.run(it) }
    },
    private val clock: Clock = Clock.systemUTC()
) {

    private val logger = LoggerFactory.getLogger(MultiChatBackupRunner::class.java)

    fun run(request: MultiChatBackupRequest): MultiChatBackupResult {
        val config = configProvider()
        val adbClient = adbClientFactory(config)

        val discoveries = if (request.targetChats.isNullOrEmpty()) {
            val collector = ChatCollector(config, adbClient)
            collector.collectChats(request.maxChats ?: Int.MAX_VALUE)
        } else {
            request.targetChats.mapIndexed { index, name ->
                ChatSelection(name, index + 1)
            }
        }

        if (discoveries.isEmpty()) {
            logger.warn("No chats discovered for backup")
            return MultiChatBackupResult(emptyList(), emptyList(), emptyList(), emptyList())
        }

        val singleRunner = singleRunnerFactory()
        val successfulChats = mutableListOf<String>()
        val downloadedFiles = mutableListOf<Path>()
        val failures = mutableListOf<Pair<String, String>>()
        val skipped = mutableListOf<String>()

        if (request.dryRun) {
            discoveries.forEach {
                logger.info("Dry-run: would export '{}' (occurrence {})", it.name, it.occurrence)
            }
            return MultiChatBackupResult(
                successfulChats = emptyList(),
                downloadedFiles = emptyList(),
                skippedChats = discoveries.map { it.name },
                failedChats = emptyList()
            )
        }

        for (selection in discoveries) {
            val singleRequest = SingleChatExportRequest(
                targetChat = selection.name,
                dryRun = false,
                verbose = request.verbose,
                includeMedia = request.includeMedia,
                shareTarget = request.shareTarget,
                driveFolder = request.driveFolder,
                matchIndex = selection.occurrence,
                channelPrefix = request.channelPrefix
            )

            when (val result = singleRunner.run(singleRequest)) {
                is SingleChatExportResult.Success -> {
                    successfulChats += selection.name
                    downloadedFiles += result.exportedFiles
                }
                is SingleChatExportResult.Failure -> {
                    failures += selection.name to (result.reason)
                }
                is SingleChatExportResult.DryRun -> {
                    skipped += selection.name
                }
            }
        }

        val uniqueDownloads = downloadedFiles.map { it.toString() }.distinct()
        return MultiChatBackupResult(
            successfulChats = successfulChats,
            downloadedFiles = uniqueDownloads,
            skippedChats = skipped,
            failedChats = failures
        )
    }

    private class ChatCollector(
        private val config: AppConfig,
        private val adbClient: AdbClient,
    ) {
        private val logger = LoggerFactory.getLogger(ChatCollector::class.java)
        private val remoteDir = config.deviceSnapshotDir.trimEnd('/')
        private val encountered = LinkedHashSet<ChatSelection>()
        private val nameCounts = mutableMapOf<String, Int>()

        fun collectChats(limit: Int): List<ChatSelection> {
            adbClient.shellChecked("mkdir", "-p", remoteDir)
            launchWhatsApp()

            var stagnantPasses = 0
            while (encountered.size < limit) {
                val newChats = scrapeCurrentPage()
                if (newChats.isEmpty()) {
                    stagnantPasses += 1
                    if (stagnantPasses >= 2) break
                } else {
                    stagnantPasses = 0
                }
                if (encountered.size >= limit) break
                scrollDown()
            }

            logger.info("Discovered {} chat entries for backup", encountered.size)
            return encountered.toList().take(limit)
        }

        private fun scrapeCurrentPage(): List<ChatSelection> {
            val remotePath = "$remoteDir/collector_chat_list.xml"
            adbClient.shellChecked("uiautomator", "dump", remotePath)
            val xmlResult = adbClient.shell("cat", remotePath)
            if (!xmlResult.isSuccess) {
                logger.warn("Failed to read chat list xml: {}", xmlResult.stderr.trim())
                return emptyList()
            }
            val parsed = WhatsAppXmlParser.parseConversationList(xmlResult.stdout)
            val selections = mutableListOf<ChatSelection>()
            for (chat in parsed) {
                val nextIndex = (nameCounts[chat.name] ?: 0) + 1
                val candidate = ChatSelection(chat.name, nextIndex)
                if (encountered.add(candidate)) {
                    nameCounts[chat.name] = nextIndex
                    selections += candidate
                }
            }
            return selections
        }

        private fun launchWhatsApp() {
            adbClient.shellChecked("am", "start", "-n", "com.whatsapp/com.whatsapp.HomeActivity")
            Thread.sleep(600)
        }

        private fun scrollDown() {
            adbClient.shellChecked("input", "swipe", "540", "2000", "540", "600", "400")
            Thread.sleep(500)
        }
    }
}

data class MultiChatBackupRequest(
    val includeMedia: Boolean,
    val shareTarget: String,
    val driveFolder: String,
    val dryRun: Boolean,
    val verbose: Boolean,
    val maxChats: Int? = null,
    val targetChats: List<String>? = null,
    val channelPrefix: String? = null,
)

data class MultiChatBackupResult(
    val successfulChats: List<String>,
    val downloadedFiles: List<String>,
    val skippedChats: List<String>,
    val failedChats: List<Pair<String, String>>,
)

private fun AdbClient.shell(vararg args: String): AdbCommandResult = run(listOf("shell") + args.toList())

private fun AdbClient.shellChecked(vararg args: String) {
    val result = shell(*args)
    if (!result.isSuccess) {
        throw IllegalStateException("ADB command '${args.joinToString(" ")}' failed: ${result.stderr.trim()}")
    }
}

data class ChatSelection(
    val name: String,
    val occurrence: Int,
)

fun interface SingleChatExecutor {
    fun run(request: SingleChatExportRequest): SingleChatExportResult
}
