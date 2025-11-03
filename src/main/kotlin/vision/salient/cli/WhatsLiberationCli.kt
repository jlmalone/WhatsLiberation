package vision.salient.cli

import kotlinx.cli.ArgParser
import kotlinx.cli.ArgType
import kotlinx.cli.default
import org.slf4j.LoggerFactory
import vision.salient.config.Config
import vision.salient.export.SingleChatExportRequest
import vision.salient.export.SingleChatExportResult
import vision.salient.export.SingleChatExportRunner
import vision.salient.export.MultiChatBackupRequest
import vision.salient.export.MultiChatBackupResult
import vision.salient.export.MultiChatBackupRunner
import kotlin.system.exitProcess
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

fun main(args: Array<String>) {
    val exitCode = WhatsLiberationCli().run(args)
    exitProcess(exitCode)
}

class WhatsLiberationCli(
    private val singleChatRunner: SingleChatExportRunner = SingleChatExportRunner(),
    private val multiChatRunner: MultiChatBackupRunner = MultiChatBackupRunner()
) {

    private val logger by lazy { LoggerFactory.getLogger(WhatsLiberationCli::class.java) }

    fun run(rawArgs: Array<String>): Int {
        if (rawArgs.isEmpty()) {
            println("Usage: whatsliberation <command> [options]\nTry 'whatsliberation single-chat --help' for details.")
            return 1
        }

        val parser = ArgParser("whatsliberation")
        val command by parser.argument(ArgType.String, description = "Command to execute")
        val dryRun by parser.option(ArgType.Boolean, fullName = "dry-run", description = "Run without executing ADB commands").default(false)
        val verbose by parser.option(ArgType.Boolean, shortName = "v", description = "Enable verbose logging").default(false)
        val targetChat by parser.option(ArgType.String, fullName = "target-chat", description = "Chat name to export")
        val includeMedia by parser.option(ArgType.Boolean, fullName = "include-media", description = "Export with media attachments").default(false)
        val shareTarget by parser.option(ArgType.String, fullName = "share-target", description = "Share target label (e.g. Drive)").default("Drive")
        val driveFolder by parser.option(ArgType.String, fullName = "drive-folder", description = "Google Drive folder to upload into").default("Conversations")
        val channelPrefix by parser.option(ArgType.String, fullName = "channel-prefix", description = "Optional prefix for exported filenames (e.g. HK)")
        val maxChats by parser.option(ArgType.Int, fullName = "max-chats", description = "Limit the number of chats to export during full backup")
        val chatListPath by parser.option(ArgType.String, fullName = "chat-list", description = "Path to newline-delimited chat names to export")

        try {
            parser.parse(rawArgs)
        } catch (ex: Exception) {
            println(ex.message ?: "Failed to parse arguments")
            return 1
        }

        configureLogging(verbose)

        val validation = Config.validation()
        validation.warnings.forEach { logger.warn(it) }
        if (!validation.isValid) {
            validation.errors.forEach { logger.error(it) }
            return 2
        }

        return when (command.lowercase()) {
            "single-chat" -> runSingleChat(targetChat, dryRun, verbose, includeMedia, shareTarget, driveFolder, channelPrefix)
            "all-chats" -> runAllChats(dryRun, verbose, includeMedia, shareTarget, driveFolder, channelPrefix, maxChats, chatListPath)
            else -> {
                logger.error("Unknown command '$command'")
                3
            }
        }
    }

    private fun configureLogging(verbose: Boolean) {
        val level = if (verbose) "debug" else "info"
        System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", level)
        System.setProperty("org.slf4j.simpleLogger.showDateTime", "true")
        System.setProperty("org.slf4j.simpleLogger.showThreadName", "false")
    }

    private fun runSingleChat(
        targetChat: String?,
        dryRun: Boolean,
        verbose: Boolean,
        includeMedia: Boolean,
        shareTarget: String,
        driveFolder: String,
        channelPrefix: String?,
    ): Int {
        val result = singleChatRunner.run(
            SingleChatExportRequest(
                targetChat = targetChat,
                dryRun = dryRun,
                verbose = verbose,
                includeMedia = includeMedia,
                shareTarget = shareTarget,
                driveFolder = driveFolder,
                channelPrefix = channelPrefix,
            )
        )

        return when (result) {
            is SingleChatExportResult.DryRun -> {
                logger.info(
                    "Dry-run complete. targetChat={} includeMedia={} shareTarget={}",
                    targetChat ?: "<most recent>",
                    includeMedia,
                    shareTarget
                )
                0
            }
            is SingleChatExportResult.Success -> {
                logger.info(
                    "Export complete for '{}' -> run directory {}",
                    result.chatName,
                    result.runDirectory
                )
                if (result.exportedFiles.isEmpty()) {
                    logger.warn("No exported artifacts were collected; review the run directory for UI snapshots")
                } else {
                    result.exportedFiles.forEach { logger.info("Captured artifact: {}", it) }
                }
                0
            }
            is SingleChatExportResult.Failure -> {
                logger.error("Export failed: {}", result.reason)
                4
            }
        }
    }

    private fun runAllChats(
        dryRun: Boolean,
        verbose: Boolean,
        includeMedia: Boolean,
        shareTarget: String,
        driveFolder: String,
        channelPrefix: String?,
        maxChats: Int?,
        chatListPath: String?,
    ): Int {
        val targetChats = chatListPath?.let { path ->
            kotlin.runCatching {
                java.nio.file.Files.readAllLines(java.nio.file.Path.of(path))
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
            }.onFailure {
                logger.error("Unable to read chat list from {}: {}", path, it.message)
                return 5
            }.getOrNull()
        }

        val result = multiChatRunner.run(
            MultiChatBackupRequest(
                includeMedia = includeMedia,
                shareTarget = shareTarget,
                driveFolder = driveFolder,
                dryRun = dryRun,
                verbose = verbose,
                maxChats = maxChats,
                targetChats = targetChats,
                channelPrefix = channelPrefix
            )
        )

        logMultiChatOutcome(result)
        writeBackupSummary(result)
        return if (result.failedChats.isEmpty()) 0 else 6
    }

    private fun logMultiChatOutcome(result: MultiChatBackupResult) {
        if (result.successfulChats.isNotEmpty()) {
            logger.info("Exported {} chat(s)", result.successfulChats.size)
            result.successfulChats.forEach { logger.info("✔ {}", it) }
        }

        if (result.downloadedFiles.isNotEmpty()) {
            logger.info("Downloaded artifacts:")
            result.downloadedFiles.forEach { logger.info("  {}", it) }
        }

        if (result.skippedChats.isNotEmpty()) {
            logger.warn("Skipped chats:")
            result.skippedChats.forEach { logger.warn("  {}", it) }
        }

        if (result.failedChats.isNotEmpty()) {
            logger.error("Failed chats:")
            result.failedChats.forEach { (chat, reason) ->
                logger.error("  {} -> {}", chat, reason)
            }
        }
    }

    private fun writeBackupSummary(result: MultiChatBackupResult) {
        val summaryDir = Config.localSnapshotDir
        kotlin.runCatching { Files.createDirectories(summaryDir) }
        val timestamp = SUMMARY_FORMATTER.format(Instant.now())
        val summaryPath = summaryDir.resolve("backup-summary-$timestamp.json")
        val json = buildString {
            appendLine("{")
            appendLine("  \"successfulChats\": ${result.successfulChats.toJsonArray()},")
            appendLine("  \"downloadedFiles\": ${result.downloadedFiles.toJsonArray()},")
            appendLine("  \"skippedChats\": ${result.skippedChats.toJsonArray()},")
            appendLine("  \"failedChats\": [")
            result.failedChats.forEachIndexed { index, (chat, reason) ->
                append("    {\"chat\": \"")
                append(escapeJson(chat))
                append("\", \"reason\": \"")
                append(escapeJson(reason))
                append("\"}")
                if (index < result.failedChats.lastIndex) append(",")
                appendLine()
            }
            appendLine("  ]")
            appendLine("}")
        }
        kotlin.runCatching {
            Files.writeString(summaryPath, json)
            logger.info("Backup summary written to {}", summaryPath)
        }.onFailure {
            logger.warn("Unable to write backup summary: {}", it.message)
        }
    }

    private fun escapeJson(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"")

    private fun List<String>.toJsonArray(): String =
        joinToString(prefix = "[", postfix = "]") { "\"${escapeJson(it)}\"" }

    companion object {
        private val SUMMARY_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC)
    }
}
