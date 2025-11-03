package vision.salient.drive

import com.google.api.services.drive.Drive
import com.google.api.services.drive.model.File
import com.google.api.services.drive.model.FileList
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.json.jackson2.JacksonFactory
import com.google.auth.http.HttpCredentialsAdapter
import com.google.auth.oauth2.ServiceAccountCredentials
import com.google.auth.oauth2.UserCredentials
import org.slf4j.LoggerFactory
import vision.salient.config.ContactsConfig
import vision.salient.config.DriveConfig
import vision.salient.export.Sleeper
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

class GoogleDriveDownloader(
    private val driveConfig: DriveConfig,
    private val contactsConfig: ContactsConfig?,
    private val clock: Clock,
    private val sleeper: Sleeper,
) : DriveDownloader {

    private val logger = LoggerFactory.getLogger(GoogleDriveDownloader::class.java)
    private val serviceRef = AtomicReference<Drive?>()

    override fun downloadExport(chatName: String, includeMedia: Boolean, runDirectory: Path, desiredFileName: String?): List<Path> {
        val service = serviceRef.updateAndGet { current -> current ?: createDriveService() }
            ?: return emptyList()

        val folderId = resolveFolderId(service) ?: return emptyList()

        val expectedPrefix = buildExpectedPrefix(chatName)
        val timeoutAt = clock.instant().plus(driveConfig.pollTimeout)

        while (clock.instant().isBefore(timeoutAt)) {
            val candidate = findLatestMatchingFile(service, folderId, expectedPrefix)
            if (candidate != null) {
                return listOf(downloadFile(service, candidate, runDirectory, desiredFileName))
            }
            sleeper.sleep(driveConfig.pollInterval.toMillis())
        }

        logger.warn(
            "Timed out after {} seconds waiting for Drive export starting with '{}' in folder {}",
            driveConfig.pollTimeout.seconds,
            expectedPrefix,
            folderId
        )
        return emptyList()
    }

    private fun createDriveService(): Drive? {
        driveConfig.credentialsPath?.let { serviceAccountPath ->
            val service = runCatching {
                Files.newInputStream(serviceAccountPath).use { inputStream ->
                    val credentials = ServiceAccountCredentials.fromStream(inputStream)
                    val transport = GoogleNetHttpTransport.newTrustedTransport()
                    val jsonFactory = JacksonFactory.getDefaultInstance()
                    Drive.Builder(transport, jsonFactory, HttpCredentialsAdapter(credentials))
                        .setApplicationName("WhatsLiberation")
                        .build()
                }
            }.onFailure {
                logger.error("Failed to initialise Google Drive client: {}", it.message, it)
            }.getOrNull()
            if (service != null) return service
        }

        val contacts = contactsConfig
        if (contacts?.tokenPath != null) {
            return runCatching {
                Files.newInputStream(contacts.tokenPath).use { tokenStream ->
                    val credentials = UserCredentials.fromStream(tokenStream)
                    val transport = GoogleNetHttpTransport.newTrustedTransport()
                    val jsonFactory = JacksonFactory.getDefaultInstance()
                    Drive.Builder(transport, jsonFactory, HttpCredentialsAdapter(credentials))
                        .setApplicationName("WhatsLiberation")
                        .build()
                }
            }.onFailure {
                logger.error("Failed to initialise Drive client via OAuth credentials: {}", it.message)
            }.getOrNull()
        }

        logger.debug("No Drive credentials configured; skipping Drive download")
        return null
    }

    private fun resolveFolderId(service: Drive): String? {
        driveConfig.folderId?.let { return it }
        val name = driveConfig.folderName?.takeIf { it.isNotBlank() }
        if (name == null) {
            logger.warn("Drive folder name or ID must be provided to download exports")
            return null
        }

        val queryName = escapeQueryValue(name)
        val query = "mimeType = 'application/vnd.google-apps.folder' and name = '$queryName' and 'root' in parents and trashed = false"
        return try {
            val folders: FileList = service.files().list()
                .setQ(query)
                .setFields("files(id,name)")
                .setPageSize(5)
                .execute()
            val match = folders.files?.firstOrNull()
            if (match == null) {
                logger.error("Drive folder '{}' not found in My Drive", name)
                null
            } else {
                match.id
            }
        } catch (ex: IOException) {
            logger.error("Failed to query Drive folder '{}': {}", name, ex.message, ex)
            null
        }
    }

    private fun findLatestMatchingFile(service: Drive, folderId: String, expectedPrefix: String): File? {
        val nameQuery = escapeQueryValue(expectedPrefix)
        val query = "name contains '$nameQuery' and '$folderId' in parents and trashed = false"
        return try {
            val result = service.files().list()
                .setQ(query)
                .setOrderBy("createdTime desc")
                .setFields("files(id,name,createdTime,size,mimeType)")
                .setPageSize(5)
                .execute()
            result.files?.firstOrNull()
        } catch (ex: IOException) {
            logger.warn("Drive query failed while waiting for export: {}", ex.message)
            null
        }
    }

    private fun downloadFile(service: Drive, file: File, runDirectory: Path, desiredFileName: String?): Path {
        val targetName = desiredFileName ?: sanitizeFileName(file.name ?: "export")
        if (desiredFileName != null) {
            runCatching {
                service.files().update(file.id, File().setName(desiredFileName)).execute()
            }.onFailure {
                logger.warn("Unable to rename Drive export '{}' to '{}': {}", file.name, desiredFileName, it.message)
            }
        }
        val targetPath = runDirectory.resolve(targetName)
        try {
            Files.newOutputStream(targetPath).use { outputStream ->
                service.files().get(file.id).executeMediaAndDownloadTo(outputStream)
            }
            logger.info("Downloaded Drive export '{}' to {}", file.name, targetPath)
        } catch (ex: IOException) {
            logger.error("Failed to download Drive file '{}': {}", file.name, ex.message, ex)
            throw ex
        }
        return targetPath
    }

    private fun buildExpectedPrefix(chatName: String): String {
        return "WhatsApp Chat with " + chatName.replace("\n", " ").trim()
    }

    private fun escapeQueryValue(value: String): String = value.replace("'", "\\'")

    private fun sanitizeFileName(name: String): String {
        return name.replace("[\\\\/:*?\"<>|]".toRegex(), "_")
    }
}
