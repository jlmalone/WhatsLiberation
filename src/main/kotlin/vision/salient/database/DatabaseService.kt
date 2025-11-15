package vision.salient.database

import org.slf4j.LoggerFactory
import vision.salient.config.DatabaseConfig
import java.io.File
import java.security.MessageDigest
import java.time.Instant

/**
 * High-level database service for WhatsLiberation.
 *
 * This service provides a clean API for the export workflow to interact
 * with the persistent registry, handling all database operations through
 * the underlying repositories.
 *
 * Features:
 * - Automatic initialization and migration
 * - Transaction management
 * - High-level export tracking
 * - Incremental backup support
 * - Contact caching
 */
class DatabaseService(
    private val config: DatabaseConfig,
    private val manager: DatabaseManager = DatabaseManager(config.path),
    private val chatRepo: ChatRepository = ChatRepository(),
    private val exportRepo: ExportRunRepository = ExportRunRepository(),
    private val contactsRepo: ContactsCacheRepository = ContactsCacheRepository()
) {
    private val logger = LoggerFactory.getLogger(DatabaseService::class.java)

    private var initialized = false

    /**
     * Initialize the database service.
     *
     * This must be called before any other operations.
     * Safe to call multiple times (idempotent).
     */
    fun initialize() {
        if (initialized) {
            logger.debug("Database service already initialized")
            return
        }

        if (!config.enabled) {
            logger.info("Database is disabled in configuration")
            return
        }

        try {
            logger.info("Initializing database service...")
            manager.initialize()

            if (config.vacuumOnStartup) {
                logger.info("Running database VACUUM...")
                manager.vacuum()
            }

            val stats = manager.getStatistics()
            logger.info(
                "Database initialized: {} chats, {} exports, {} cached contacts, {} bytes",
                stats.totalChats,
                stats.totalExportRuns,
                stats.cachedContacts,
                stats.databaseSizeBytes
            )

            initialized = true

        } catch (e: Exception) {
            logger.error("Failed to initialize database service", e)
            throw e
        }
    }

    /**
     * Check if database is enabled and initialized.
     */
    fun isAvailable(): Boolean = config.enabled && initialized

    /**
     * Record the start of an export for a chat.
     *
     * This finds or creates the chat record and returns its ID.
     *
     * @return Chat entity, or null if database is disabled
     */
    fun recordExportStart(
        chatName: String,
        occurrenceIndex: Int = 0,
        channelPrefix: String? = null
    ): ChatEntity? {
        if (!isAvailable()) return null

        return manager.executeTransaction {
            chatRepo.findOrCreate(chatName, occurrenceIndex, channelPrefix)
        }
    }

    /**
     * Record the completion of a successful export.
     *
     * @param chatId Chat ID from recordExportStart()
     * @param filePath Path to exported file
     * @param includedMedia Whether media was included
     * @param durationMs Export duration in milliseconds
     * @param driveFileId Optional Google Drive file ID
     */
    fun recordExportSuccess(
        chatId: Int,
        filePath: String,
        includedMedia: Boolean,
        durationMs: Long,
        driveFileId: String? = null
    ): ExportRunEntity? {
        if (!isAvailable()) return null

        return manager.executeTransaction {
            // Calculate file hash and size
            val file = File(filePath)
            val fileSize = if (file.exists()) file.length() else null
            val fileHash = if (file.exists()) calculateFileHash(file) else null

            val input = CreateExportRunInput(
                chatId = chatId,
                outcome = ExportOutcome.SUCCESS,
                localFilePath = filePath,
                fileSizeBytes = fileSize,
                fileHash = fileHash,
                driveFileId = driveFileId,
                includedMedia = includedMedia,
                durationMs = durationMs
            )

            exportRepo.create(input)
        }
    }

    /**
     * Record a failed export attempt.
     *
     * @param chatId Chat ID from recordExportStart()
     * @param errorMessage Error description
     * @param durationMs Export duration in milliseconds (time until failure)
     */
    fun recordExportFailure(
        chatId: Int,
        errorMessage: String,
        durationMs: Long? = null
    ): ExportRunEntity? {
        if (!isAvailable()) return null

        return manager.executeTransaction {
            val input = CreateExportRunInput(
                chatId = chatId,
                outcome = ExportOutcome.FAILED,
                errorMessage = errorMessage,
                durationMs = durationMs
            )

            exportRepo.create(input)
        }
    }

    /**
     * Record a skipped export (e.g., already exported recently).
     *
     * @param chatId Chat ID from recordExportStart()
     * @param reason Why the export was skipped
     */
    fun recordExportSkipped(
        chatId: Int,
        reason: String
    ): ExportRunEntity? {
        if (!isAvailable()) return null

        return manager.executeTransaction {
            val input = CreateExportRunInput(
                chatId = chatId,
                outcome = ExportOutcome.SKIPPED,
                errorMessage = reason
            )

            exportRepo.create(input)
        }
    }

    /**
     * Update chat with Google Contacts information.
     *
     * @param chatId Chat ID
     * @param contactId Google Contacts ID
     * @param phoneNumber Phone number
     */
    fun updateChatContactInfo(chatId: Int, contactId: String?, phoneNumber: String?) {
        if (!isAvailable()) return

        manager.executeTransaction {
            chatRepo.updateContactInfo(chatId, contactId, phoneNumber)

            // Also cache in contacts table if both values present
            if (contactId != null && phoneNumber != null) {
                val chat = chatRepo.findById(chatId)
                if (chat != null) {
                    contactsRepo.upsert(CreateContactCacheInput(
                        phoneNumber = phoneNumber,
                        contactId = contactId,
                        displayName = chat.chatName
                    ))
                }
            }
        }
    }

    /**
     * Look up cached contact by phone number.
     *
     * @return Cached contact, or null if not found or stale
     */
    fun getCachedContact(phoneNumber: String, maxAgeHours: Int = 168): ContactCacheEntity? {
        if (!isAvailable()) return null

        return manager.executeTransaction {
            if (contactsRepo.isStale(phoneNumber, maxAgeHours)) {
                null
            } else {
                contactsRepo.findByPhoneNumber(phoneNumber)
            }
        }
    }

    /**
     * Cache a contact lookup result.
     *
     * @param phoneNumber Phone number
     * @param contactId Google Contacts ID
     * @param displayName Display name
     */
    fun cacheContact(phoneNumber: String, contactId: String, displayName: String): ContactCacheEntity? {
        if (!isAvailable()) return null

        return manager.executeTransaction {
            contactsRepo.upsert(CreateContactCacheInput(
                phoneNumber = phoneNumber,
                contactId = contactId,
                displayName = displayName
            ))
        }
    }

    /**
     * Check if a chat should be backed up based on incremental backup policy.
     *
     * @param chatName Chat to check
     * @param occurrenceIndex Occurrence index for duplicates
     * @param channelPrefix Channel prefix
     * @param olderThanHours Only backup if last successful export was more than X hours ago
     * @return true if chat should be backed up
     */
    fun shouldBackupChat(
        chatName: String,
        occurrenceIndex: Int = 0,
        channelPrefix: String? = null,
        olderThanHours: Int = 24
    ): Boolean {
        if (!isAvailable()) return true // If DB disabled, backup everything

        return manager.executeTransaction {
            val chat = chatRepo.findOrCreate(chatName, occurrenceIndex, channelPrefix)
            !exportRepo.hasRecentSuccessfulExport(chat.id, olderThanHours)
        }
    }

    /**
     * Get list of chats needing incremental backup.
     *
     * @param olderThanHours Backup chats not exported in last X hours
     * @return List of chat entities needing backup
     */
    fun getChatsNeedingBackup(olderThanHours: Int = 24): List<ChatEntity> {
        if (!isAvailable()) return emptyList()

        return manager.executeTransaction {
            chatRepo.getChatsNeedingBackup(olderThanHours)
        }
    }

    /**
     * Get export statistics.
     *
     * @param since Optional start time for stats calculation
     * @return Export statistics
     */
    fun getExportStatistics(since: Instant? = null): ExportStatistics {
        if (!isAvailable()) {
            return ExportStatistics(0, 0, 0, 0, null, 0)
        }

        return manager.executeTransaction {
            exportRepo.getStatistics(since)
        }
    }

    /**
     * Get all chats with their statistics.
     */
    fun getAllChatsWithStats(): List<ChatWithStats> {
        if (!isAvailable()) return emptyList()

        return manager.executeTransaction {
            chatRepo.getAllChatsWithStats()
        }
    }

    /**
     * Get database statistics.
     */
    fun getDatabaseStatistics(): DatabaseStatistics? {
        if (!isAvailable()) return null

        return manager.getStatistics()
    }

    /**
     * Create a backup of the database.
     *
     * @param backupPath Optional custom backup path
     * @return Path to backup file
     */
    fun createBackup(backupPath: java.nio.file.Path? = null): java.nio.file.Path? {
        if (!isAvailable()) return null

        return manager.createBackup(backupPath)
    }

    /**
     * Clean up old cache entries.
     *
     * @param olderThanDays Delete cache entries older than X days
     * @return Number of deleted entries
     */
    fun cleanupOldCacheEntries(olderThanDays: Int = 90): Int {
        if (!isAvailable()) return 0

        return manager.executeTransaction {
            contactsRepo.clearOldEntries(olderThanDays)
        }
    }

    /**
     * Close the database service.
     */
    fun close() {
        if (initialized) {
            manager.close()
            initialized = false
            logger.info("Database service closed")
        }
    }

    /**
     * Calculate SHA-256 hash of a file for deduplication.
     */
    private fun calculateFileHash(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                md.update(buffer, 0, bytesRead)
            }
        }
        val digest = md.digest()
        return digest.joinToString("") { "%02x".format(it) }
    }
}

/**
 * Factory for creating DatabaseService instances.
 */
object DatabaseServiceFactory {
    private var instance: DatabaseService? = null

    /**
     * Get or create singleton database service.
     *
     * @param config Database configuration
     * @return DatabaseService instance
     */
    fun getInstance(config: DatabaseConfig): DatabaseService {
        if (instance == null) {
            instance = DatabaseService(config)
            instance?.initialize()
        }
        return instance!!
    }

    /**
     * Reset singleton (for testing).
     */
    fun reset() {
        instance?.close()
        instance = null
    }
}
