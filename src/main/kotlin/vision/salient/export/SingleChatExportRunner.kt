package vision.salient.export

import org.slf4j.LoggerFactory
import vision.salient.WhatsAppXmlParser
import vision.salient.adb.AdbClient
import vision.salient.adb.AdbCommandResult
import vision.salient.adb.RealAdbClient
import vision.salient.contacts.GoogleContactsClient
import vision.salient.config.AppConfig
import vision.salient.config.Config
import vision.salient.drive.DriveDownloader
import vision.salient.drive.GoogleDriveDownloader
import vision.salient.model.WhatsAppChat
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

fun interface Sleeper {
    fun sleep(millis: Long)
}

data class SingleChatExportRequest(
    val targetChat: String?,
    val dryRun: Boolean,
    val verbose: Boolean,
    val includeMedia: Boolean,
    val shareTarget: String = "Drive",
    val driveFolder: String = "Conversations",
    val matchIndex: Int? = null,
    val channelPrefix: String? = null,
)

sealed interface SingleChatExportResult {
    data class DryRun(val plannedChat: String?) : SingleChatExportResult
    data class Success(
        val chatName: String,
        val runDirectory: Path,
        val exportedFiles: List<Path>
    ) : SingleChatExportResult

    data class Failure(val reason: String, val cause: Throwable? = null) : SingleChatExportResult
}

class SingleChatExportRunner(
    private val configProvider: () -> AppConfig = { Config.appConfig },
    private val adbClientFactory: (AppConfig) -> AdbClient = { RealAdbClient(it) },
    private val repositoryFactory: (Path) -> LocalExportRepository = { LocalExportRepository(it) },
    private val driveDownloaderFactory: (AppConfig, Sleeper, Clock) -> DriveDownloader? = { config, sleeper, clock ->
        val hasServiceAccount = config.drive.credentialsPath != null
        val hasOauth = config.contacts.clientSecretPath != null && config.contacts.tokenPath != null
        if (!hasServiceAccount && !hasOauth) {
            null
        } else {
            GoogleDriveDownloader(config.drive, config.contacts, clock, sleeper)
        }
    },
    private val contactsClientFactory: (AppConfig) -> GoogleContactsClient? = { config ->
        if (config.contacts.clientSecretPath == null || config.contacts.tokenPath == null) null
        else GoogleContactsClient(config.contacts)
    },
    private val clock: Clock = Clock.systemUTC(),
    private val sleeper: Sleeper = Sleeper { Thread.sleep(it) }
) {

    private val logger = LoggerFactory.getLogger(SingleChatExportRunner::class.java)

    fun run(request: SingleChatExportRequest): SingleChatExportResult {
        val config = configProvider()
        val repository = repositoryFactory(config.localSnapshotDir)
        repository.ensureRootExists()

        if (request.dryRun) {
            logger.info(
                "Dry-run: would export chat '{}' (includeMedia={}, shareTarget={}, driveFolder={}, matchIndex={})",
                request.targetChat ?: "<most recent>",
                request.includeMedia,
                request.shareTarget,
                request.driveFolder,
                request.matchIndex ?: 1
            )
            return SingleChatExportResult.DryRun(request.targetChat)
        }

        val adbClient = adbClientFactory(config)
        val driveDownloader = driveDownloaderFactory(config, sleeper, clock)
        val contactsClient = contactsClientFactory(config)
        val workflow = Workflow(
            config = config,
            repository = repository,
            adbClient = adbClient,
            request = request,
            clock = clock,
            sleeper = sleeper,
            logger = logger,
            driveDownloader = driveDownloader,
            contactsClient = contactsClient,
        )

        return try {
            workflow.execute()
        } catch (ex: ExportException) {
            logger.error(ex.message ?: "Single chat export failed", ex)
            SingleChatExportResult.Failure(ex.message ?: "Single chat export failed", ex)
        } catch (ex: Exception) {
            logger.error("Unexpected failure during export", ex)
            SingleChatExportResult.Failure("Unexpected failure during export: ${ex.message}", ex)
        }
    }

    private class Workflow(
        private val config: AppConfig,
        private val repository: LocalExportRepository,
        private val adbClient: AdbClient,
        private val request: SingleChatExportRequest,
        private val clock: Clock,
        private val sleeper: Sleeper,
        private val logger: org.slf4j.Logger,
        private val driveDownloader: DriveDownloader?,
        private val contactsClient: GoogleContactsClient?,
    ) {

        private val remoteStageDir = config.deviceSnapshotDir.trimEnd('/')
        private val remoteStageDirEscaped = remoteStageDir.replace(" ", "\\ ")
        private lateinit var runDirectory: Path
        private lateinit var selectedChatName: String
        private val exportedFiles = mutableListOf<Path>()
        private var contactMetadata: ContactMetadata? = null

        fun execute(): SingleChatExportResult {
            try {
                ensureDeviceStageDir()
                launchWhatsApp()

                val chatListXml = dumpUi("chat_list.xml")
                val chats = WhatsAppXmlParser.parseConversationList(chatListXml)
                if (chats.isEmpty()) {
                    throw ExportException("No chats found on the conversation list screen")
                }

                val targetChat = selectChat(chats)
                selectedChatName = targetChat.name
                runDirectory = repository.createRunDirectory(selectedChatName, Instant.now(clock))
                writeSnapshot("chat_list.xml", chatListXml)
                logger.info("Selected chat '{}' at coordinates ({}, {})", targetChat.name, targetChat.centerX, targetChat.centerY)

                tap(targetChat.centerX, targetChat.centerY, "open chat ${targetChat.name}")
                sleeper.sleep(650)

                val chatDetailXml = dumpUi("chat_detail.xml")
                writeSnapshot("chat_detail.xml", chatDetailXml)
                val overflowCenter = WhatsAppXmlParser.findNodeCenter(chatDetailXml, "com.whatsapp:id/menuitem_overflow")
                    ?: throw ExportException("Unable to locate overflow menu on chat detail screen")

                contactMetadata = buildContactMetadata(chatDetailXml)

                tap(overflowCenter.first, overflowCenter.second, "open overflow menu")
                sleeper.sleep(400)

                val overflowXml = dumpUi("chat_overflow.xml")
                writeSnapshot("chat_overflow.xml", overflowXml)
                val moreCenter = WhatsAppXmlParser.findNodeCenterWithText(
                    overflowXml,
                    targetResId = "com.whatsapp:id/title",
                    targetText = "More",
                    ignoreCase = true
                ) ?: throw ExportException("Unable to locate 'More' option in overflow menu")

                tap(moreCenter.first, moreCenter.second, "open 'More' submenu")
                sleeper.sleep(350)

                val moreMenuXml = dumpUi("chat_more_menu.xml")
                writeSnapshot("chat_more_menu.xml", moreMenuXml)
                val exportCenter = WhatsAppXmlParser.findNodeCenterWithText(
                    moreMenuXml,
                    targetResId = "com.whatsapp:id/title",
                    targetText = "Export chat",
                    ignoreCase = true
                ) ?: throw ExportException("Unable to locate 'Export chat' menu option")

                tap(exportCenter.first, exportCenter.second, "select 'Export chat'")
                sleeper.sleep(350)

                val exportDialogXml = dumpUi("export_chat_dialog.xml")
                writeSnapshot("export_chat_dialog.xml", exportDialogXml)
                val exportButtonCenter = locateExportButton(exportDialogXml)
                if (exportButtonCenter != null) {
                    tap(exportButtonCenter.first, exportButtonCenter.second, "confirm export (includeMedia=${request.includeMedia})")
                    sleeper.sleep(750)
                } else {
                    logger.info("Export dialog not shown; proceeding directly to share sheet")
                }

                selectShareTarget()
                completeShareTargetFlow()

                if (exportedFiles.isEmpty()) {
                    logger.warn("Export completed but no local files were collected. Check Drive upload or device storage.")
                }

                cleanupStage()
                return SingleChatExportResult.Success(selectedChatName, runDirectory, exportedFiles.toList())
            } finally {
                runCatching { cleanupStage() }
            }
        }

        private fun ensureDeviceStageDir() {
            adbClient.shell("mkdir", "-p", remoteStageDir).ensureSuccess("create device staging directory")
        }

        private fun launchWhatsApp() {
            adbClient.shell("am", "start", "-n", "com.whatsapp/com.whatsapp.HomeActivity")
                .ensureSuccess("launch WhatsApp home activity")
            sleeper.sleep(500)
        }

        private fun selectChat(chats: List<WhatsAppChat>): WhatsAppChat {
            val target = request.targetChat?.trim()
            if (target.isNullOrEmpty()) {
                return chats.first()
            }

            val matchIndex = (request.matchIndex ?: 1).coerceAtLeast(1)
            val normalizedTarget = normalizeName(target)

            val directMatches = chats.filter { normalizeName(it.name) == normalizedTarget }
            if (directMatches.size >= matchIndex) {
                return directMatches[matchIndex - 1]
            }

            val containsMatches = chats.filter { normalizeName(it.name).contains(normalizedTarget) }
            if (containsMatches.size >= matchIndex) {
                return containsMatches[matchIndex - 1]
            }

            throw ExportException("Chat '$target' not found on the conversation list")
        }

        private fun selectShareTarget() {
            val maxScrollAttempts = 2
            var attempt = 0
            var shareSheetXml = dumpUi("export_share_sheet_attempt${attempt}.xml")
            writeSnapshot("export_share_sheet_attempt${attempt}.xml", shareSheetXml)

            var shareCenter = findShareTarget(shareSheetXml)
            while (shareCenter == null && attempt < maxScrollAttempts) {
                logger.debug("Share target '{}' not visible, performing swipe attempt {}", request.shareTarget, attempt + 1)
                adbClient.shell("input", "swipe", "540", "2200", "540", "1600", "250")
                    .ensureSuccess("scroll share sheet")
                sleeper.sleep(400)
                attempt += 1
                shareSheetXml = dumpUi("export_share_sheet_attempt${attempt}.xml")
                writeSnapshot("export_share_sheet_attempt${attempt}.xml", shareSheetXml)
                shareCenter = findShareTarget(shareSheetXml)
            }

            if (shareCenter == null) {
                throw ExportException("Unable to locate share target '${request.shareTarget}'")
            }

            tap(shareCenter.first, shareCenter.second, "select share target ${request.shareTarget}")
            sleeper.sleep(1200)
        }

        private fun findShareTarget(xml: String): Pair<Int, Int>? {
            val normalizedTargets = buildShareTargetCandidates()
            for (candidate in normalizedTargets) {
                val exact = WhatsAppXmlParser.findNodeCenterWithText(
                    xml,
                    targetResId = "android:id/text1",
                    targetText = candidate,
                    ignoreCase = true
                )
                if (exact != null) {
                    return exact
                }
                val center = WhatsAppXmlParser.findNodeCenterContainingText(
                    xml,
                    targetResId = "android:id/text1",
                    fragment = candidate,
                    ignoreCase = true
                )
                if (center != null) {
                    return center
                }
            }
            return null
        }

        private fun buildShareTargetCandidates(): List<String> {
            val base = request.shareTarget.trim()
            val candidates = mutableListOf(base)
            if (base.equals("drive", ignoreCase = true)) {
                candidates += listOf("my drive", "google drive")
            }
            return candidates.distinct()
        }

        private fun completeShareTargetFlow() {
            val lowerTarget = request.shareTarget.lowercase()
            when {
                lowerTarget.contains("drive") -> handleDriveUpload()
                else -> logger.warn("Share target '{}' is not fully automated; manual completion may be required.", request.shareTarget)
            }
        }

        private fun handleDriveUpload() {
            val slug = request.shareTarget.lowercase().replace("[^a-z0-9]+".toRegex(), "_").trim('_').ifEmpty { "drive" }
            var uploadScreenXml = fetchDriveUploadScreen(slug)
            uploadScreenXml = ensureDriveTargetFolder(uploadScreenXml, slug, request.driveFolder)

            val metadata = contactMetadata ?: ContactMetadata(selectedChatName, null, null)
            val exportTimestamp = Instant.now(clock)
            val extension = if (request.includeMedia) "zip" else "txt"
            val baseFileName = buildExportBaseName(metadata, request.channelPrefix, exportTimestamp)
            val finalFileName = if (extension.isBlank()) baseFileName else "$baseFileName.$extension"

            val uploadButtonCenter = WhatsAppXmlParser.findNodeCenter(uploadScreenXml, "com.google.android.apps.docs:id/save_button")
                ?: WhatsAppXmlParser.findNodeCenterWithText(
                    uploadScreenXml,
                    targetResId = "com.google.android.apps.docs:id/save_button",
                    targetText = "Upload",
                    ignoreCase = true
                )
                ?: throw ExportException("Unable to locate Google Drive upload button")

            tap(uploadButtonCenter.first, uploadButtonCenter.second, "confirm Drive upload")
            sleeper.sleep(1500)

            val confirmationXml = dumpUi("${slug}_post_upload.xml")
            writeSnapshot("${slug}_post_upload.xml", confirmationXml)

            val deviceArtifacts = pullExportedArtifacts()
            val driveArtifacts = driveDownloader?.let { downloader ->
                runCatching {
                    downloader.downloadExport(
                        chatName = selectedChatName,
                        includeMedia = request.includeMedia,
                        runDirectory = runDirectory,
                        desiredFileName = finalFileName
                    )
                }.onFailure {
                    logger.warn("Drive download failed: {}", it.message)
                }.getOrNull() ?: emptyList()
            } ?: emptyList()

            val renamedArtifacts = renameArtifacts(deviceArtifacts + driveArtifacts, finalFileName)
            exportedFiles.addAll(renamedArtifacts)
        }

        private fun locateExportButton(exportDialogXml: String): Pair<Int, Int>? {
            val withoutMediaButton = WhatsAppXmlParser.findNodeCenter(exportDialogXml, "android:id/button3")
            val includeMediaButton = WhatsAppXmlParser.findNodeCenter(exportDialogXml, "android:id/button1")

            return when {
                request.includeMedia && includeMediaButton != null -> includeMediaButton
                request.includeMedia && includeMediaButton == null && withoutMediaButton != null -> {
                    logger.warn("Include media button missing; falling back to text-only export")
                    withoutMediaButton
                }
                !request.includeMedia && withoutMediaButton != null -> withoutMediaButton
                !request.includeMedia && withoutMediaButton == null && includeMediaButton != null -> includeMediaButton
                else -> null
            }
        }

        private fun fetchDriveUploadScreen(slug: String): String {
            repeat(4) { attempt ->
                val name = if (attempt == 0) "${slug}_upload.xml" else "${slug}_upload_attempt$attempt.xml"
                val xml = dumpUi(name)
                writeSnapshot(name, xml)
                if (xml.contains("com.google.android.apps.docs:id/upload_folder_autocomplete") ||
                    xml.contains("com.google.android.apps.docs:id/save_button")) {
                    return xml
                }
                sleeper.sleep(700)
            }
            throw ExportException("Google Drive upload sheet did not appear")
        }

        private fun ensureDriveTargetFolder(currentUploadXml: String, slug: String, desired: String): String {
            if (driveFolderMatches(currentUploadXml, desired)) {
                return currentUploadXml
            }

            val folderFieldCenter = WhatsAppXmlParser.findNodeCenter(currentUploadXml, "com.google.android.apps.docs:id/upload_folder_autocomplete")
                ?: WhatsAppXmlParser.findNodeCenter(currentUploadXml, "com.google.android.apps.docs:id/upload_folder_textinput")
                ?: throw ExportException("Unable to locate Google Drive folder selector field")

            tap(folderFieldCenter.first, folderFieldCenter.second, "open Drive folder picker")
            sleeper.sleep(600)

            var pickerXml = dumpUi("${slug}_folder_picker.xml")
            writeSnapshot("${slug}_folder_picker.xml", pickerXml)

            var folderCenter = findDriveFolderOption(pickerXml, desired)

            if (folderCenter == null) {
                val myDriveCenter = findDriveFolderOption(pickerXml, "My Drive")
                if (myDriveCenter != null) {
                    tap(myDriveCenter.first, myDriveCenter.second, "enter My Drive root")
                    sleeper.sleep(700)
                    pickerXml = dumpUi("${slug}_folder_picker_mydrive.xml")
                    writeSnapshot("${slug}_folder_picker_mydrive.xml", pickerXml)
                    folderCenter = findDriveFolderOption(pickerXml, desired)
                }
            }

            folderCenter ?: throw ExportException("Folder '$desired' not found in Drive picker")

            tap(folderCenter.first, folderCenter.second, "select Drive folder $desired")
            sleeper.sleep(700)

            val maybeConfirmationXml = dumpUi("${slug}_folder_picker_after_select.xml")
            writeSnapshot("${slug}_folder_picker_after_select.xml", maybeConfirmationXml)

            val selectButton = WhatsAppXmlParser.findNodeCenterContainingText(
                maybeConfirmationXml,
                targetResId = "android:id/button1",
                fragment = "select",
                ignoreCase = true
            ) ?: WhatsAppXmlParser.findNodeCenterContainingText(
                maybeConfirmationXml,
                targetResId = "com.google.android.apps.docs:id/positive_button",
                fragment = "select",
                ignoreCase = true
            ) ?: WhatsAppXmlParser.findNodeCenterContainingText(
                maybeConfirmationXml,
                targetResId = "",
                fragment = "select",
                ignoreCase = true
            ) ?: findNodeCenterByText(maybeConfirmationXml, "Select")

            if (selectButton != null) {
                tap(selectButton.first, selectButton.second, "confirm Drive folder selection")
                sleeper.sleep(600)
            }

            val refreshedUploadXml = dumpUi("${slug}_upload_after_folder.xml")
            writeSnapshot("${slug}_upload_after_folder.xml", refreshedUploadXml)

            val refreshedLabel = extractDriveFolderLabel(refreshedUploadXml)
            if (refreshedLabel != null && !refreshedLabel.contains(desired, ignoreCase = true)) {
                throw ExportException("Drive folder field did not update to '$desired'")
            }
            return refreshedUploadXml
        }

        private fun driveFolderMatches(uploadXml: String, desired: String): Boolean {
            val current = extractDriveFolderLabel(uploadXml)
            return current?.contains(desired, ignoreCase = true) == true
        }

        private fun extractDriveFolderLabel(uploadXml: String): String? {
            return runCatching {
                val factory = DocumentBuilderFactory.newInstance()
                val builder = factory.newDocumentBuilder()
                val doc = builder.parse(InputSource(StringReader(uploadXml)))
                val nodes = doc.getElementsByTagName("node")
                for (i in 0 until nodes.length) {
                    val element = nodes.item(i) as? Element ?: continue
                    if (element.getAttribute("resource-id") == "com.google.android.apps.docs:id/upload_folder_autocomplete") {
                        val text = element.getAttribute("text")
                        if (text.isNotBlank()) return@runCatching text
                    }
                }
                null
            }.getOrNull()
        }

        private fun findDriveFolderOption(xml: String, folderName: String): Pair<Int, Int>? {
            val candidates = listOf(
                "com.google.android.apps.docs:id/browse_list_item_title",
                "android:id/text1",
                ""
            )
            for (resId in candidates) {
                val exact = WhatsAppXmlParser.findNodeCenterWithText(
                    xml,
                    targetResId = resId,
                    targetText = folderName,
                    ignoreCase = true
                )
                if (exact != null) {
                    return exact
                }
                val contains = WhatsAppXmlParser.findNodeCenterContainingText(
                    xml,
                    targetResId = resId,
                    fragment = folderName,
                    ignoreCase = true
                )
                if (contains != null) {
                    return contains
                }
            }
            return findNodeCenterByText(xml, folderName)
        }

        private fun findNodeCenterByText(xml: String, folderName: String): Pair<Int, Int>? {
            return runCatching {
                val builder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                val doc = builder.parse(InputSource(StringReader(xml)))
                val nodes = doc.getElementsByTagName("node")
                val matches = mutableListOf<Element>()
                for (i in 0 until nodes.length) {
                    val element = nodes.item(i) as? Element ?: continue
                    val value = element.getAttribute("text").takeIf { it.isNotBlank() }
                        ?: element.getAttribute("content-desc").takeIf { it.isNotBlank() }
                        ?: continue
                    if (value.equals(folderName, ignoreCase = true)) {
                        matches.add(element)
                    }
                }
                if (matches.isEmpty()) {
                    for (i in 0 until nodes.length) {
                        val element = nodes.item(i) as? Element ?: continue
                        val value = element.getAttribute("text").takeIf { it.isNotBlank() }
                            ?: element.getAttribute("content-desc").takeIf { it.isNotBlank() }
                            ?: continue
                        if (value.contains(folderName, ignoreCase = true)) {
                            matches.add(element)
                        }
                    }
                }
                val target = matches.firstOrNull() ?: return@runCatching null
                parseBoundsCenter(target.getAttribute("bounds"))
            }.getOrNull()
        }

        private fun parseBoundsCenter(bounds: String): Pair<Int, Int>? {
            val regex = "\\[(\\d+),(\\d+)]\\[(\\d+),(\\d+)]".toRegex()
            val matchResult = regex.find(bounds) ?: return null
            val (x1, y1, x2, y2) = matchResult.destructured
            val centerX = (x1.toInt() + x2.toInt()) / 2
            val centerY = (y1.toInt() + y2.toInt()) / 2
            return centerX to centerY
        }

        private fun pullExportedArtifacts(): List<Path> {
            val results = mutableListOf<Path>()
            val expectedPrefix = "WhatsApp Chat with ${selectedChatName}".replace("\n", " ")
            val directories = listOf(
                "/sdcard/Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Documents",
                "/sdcard/Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Documents/Sent"
            )

            for (directory in directories) {
                val escapedDir = directory.replace(" ", "\\ ")
                val listResult = adbClient.shell("ls", "-t", "-1", escapedDir)
                if (!listResult.isSuccess) continue
                val candidates = listResult.stdout
                    .lineSequence()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .filter { it.contains(expectedPrefix, ignoreCase = true) }
                    .take(2)
                    .toList()
                for (fileName in candidates) {
                    val remotePath = "$directory/$fileName"
                    val localPath = runDirectory.resolve(fileName)
                    val pullResult = adbClient.run("pull", remotePath, localPath.toString())
                    if (pullResult.isSuccess) {
                        logger.info("Pulled exported artifact '{}' to {}", fileName, localPath)
                        results.add(localPath)
                    } else {
                        logger.warn("Failed to pull '{}' from device: {}", remotePath, pullResult.stderr.trim())
                    }
                }
            }

            return results.distinct()
        }

        private fun tap(x: Int, y: Int, description: String) {
            adbClient.shell("input", "tap", x.toString(), y.toString())
                .ensureSuccess("adb tap for $description")
        }

        private fun dumpUi(fileName: String): String {
            val remotePath = "$remoteStageDir/$fileName"
            adbClient.shell("uiautomator", "dump", remotePath)
                .ensureSuccess("uiautomator dump for $fileName")

            val remoteEscaped = remotePath.replace(" ", "\\ ")
            val catResult = adbClient.shell("cat", remoteEscaped)
            catResult.ensureSuccess("retrieve UI XML $fileName")
            return catResult.stdout
        }

        private fun writeSnapshot(fileName: String, contents: String) {
            if (!::runDirectory.isInitialized) return
            Files.writeString(runDirectory.resolve(fileName), contents, StandardCharsets.UTF_8)
        }

        private fun buildContactMetadata(chatDetailXml: String): ContactMetadata {
            val phoneMatches = PHONE_REGEX.findAll(chatDetailXml).map { it.value }.toList()
            val normalizedPhones = phoneMatches.map { normalizePhoneDigits(it) }.filter { it.isNotEmpty() }.distinct()
            val phone = if (normalizedPhones.size == 1) normalizedPhones.first() else null
            val contactInfo = phone?.let { contactsClient?.lookupByPhone(it) }
            val contactId = contactInfo?.resourceName?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            val displayName = contactInfo?.displayName ?: selectedChatName
            return ContactMetadata(displayName, contactId, phone)
        }

        private fun buildExportBaseName(metadata: ContactMetadata, channelPrefix: String?, timestamp: Instant): String {
            val segments = mutableListOf<String>()
            channelPrefix?.takeIf { it.isNotBlank() }?.let { segments += sanitizeForFile(it, upper = true) }
            segments += sanitizeForFile(metadata.displayName, upper = true)
            segments += "FROM"
            segments += "CHAT"
            metadata.contactId?.let { segments += sanitizeForFile(it, upper = true) }
            metadata.phoneDigits?.let { segments += it }
            segments += DATE_FORMATTER.format(timestamp)
            return segments.joinToString("_")
        }

        private fun renameArtifacts(paths: List<Path>, preferredName: String): List<Path> {
            if (paths.isEmpty()) return emptyList()
            val (base, extension) = splitName(preferredName)
            val results = mutableListOf<Path>()
            var counter = 0
            for (path in paths) {
                if (!Files.exists(path)) continue
                val targetName = if (counter == 0) preferredName else "${base}_${counter}$extension"
                val targetPath = runDirectory.resolve(targetName)
                val finalPath = if (path == targetPath) {
                    path
                } else {
                    Files.move(path, targetPath, StandardCopyOption.REPLACE_EXISTING)
                }
                results.add(finalPath)
                counter += 1
            }
            return results
        }

        private fun cleanupStage() {
            adbClient.shell("rm", "-f", "$remoteStageDirEscaped/*.xml")
                .ensureSuccess("cleanup device staging directory")
        }
    }
}

private class ExportException(message: String) : RuntimeException(message)

private fun AdbClient.shell(vararg args: String): AdbCommandResult = run(listOf("shell") + args.toList())

private fun AdbCommandResult.ensureSuccess(step: String) {
    if (!isSuccess) {
        throw ExportException("ADB step '$step' failed (exit=$exitCode): ${stderr.trim()}")
    }
}

private data class ContactMetadata(
    val displayName: String,
    val contactId: String?,
    val phoneDigits: String?,
)

private val PHONE_REGEX = Regex("\\+[0-9][0-9\\s\\-()]{4,}")
private val DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC)

private fun normalizePhoneDigits(value: String): String = value.filter { it.isDigit() }

private fun sanitizeForFile(raw: String, upper: Boolean = false): String {
    val collapsed = raw.trim().replace("\\s+".toRegex(), "_")
    val sanitized = collapsed.replace("[^A-Za-z0-9_-]".toRegex(), "_").replace("_+".toRegex(), "_").trim('_')
    val result = if (sanitized.isEmpty()) "CHAT" else sanitized
    return if (upper) result.uppercase() else result
}

private fun splitName(name: String): Pair<String, String> {
    val index = name.lastIndexOf('.')
    return if (index == -1) name to "" else name.substring(0, index) to name.substring(index)
}

private fun normalizeName(value: String): String =
    value.lowercase()
        .replace("\\s+".toRegex(), " ")
        .replace("[^a-z0-9 ]".toRegex(), "")
        .trim()
