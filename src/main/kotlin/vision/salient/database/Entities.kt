package vision.salient.database

import java.time.Instant

/**
 * Data classes representing database entities.
 *
 * These POJOs are used for type-safe data access and provide
 * a clean API for working with database records.
 */

/**
 * Represents a WhatsApp chat in the registry.
 *
 * @property id Unique identifier
 * @property chatName Display name from WhatsApp
 * @property normalizedName Lowercase normalized name for fuzzy matching
 * @property occurrenceIndex Index for duplicate names (0-based)
 * @property contactId Google Contacts ID (nullable)
 * @property phoneNumber Phone number (nullable)
 * @property channelPrefix Channel prefix (e.g., "HK" for WhatsApp Business)
 * @property contentHash SHA-256 hash of chat content for deduplication
 * @property createdAt When this chat was first discovered
 * @property updatedAt Last update timestamp
 */
data class ChatEntity(
    val id: Int,
    val chatName: String,
    val normalizedName: String,
    val occurrenceIndex: Int,
    val contactId: String?,
    val phoneNumber: String?,
    val channelPrefix: String?,
    val contentHash: String?,
    val createdAt: Instant,
    val updatedAt: Instant
)

/**
 * Represents an export run attempt.
 *
 * @property id Unique identifier
 * @property chatId Reference to the chat
 * @property exportTimestamp When the export was initiated
 * @property outcome Result of export (SUCCESS, FAILED, SKIPPED)
 * @property errorMessage Error details if failed
 * @property localFilePath Path to exported file
 * @property fileSizeBytes Size of exported file
 * @property fileHash SHA-256 hash for integrity verification
 * @property driveFileId Google Drive file ID
 * @property includedMedia Whether media was included
 * @property durationMs Export duration in milliseconds
 * @property username User who initiated the export
 * @property createdAt Record creation timestamp
 */
data class ExportRunEntity(
    val id: Int,
    val chatId: Int,
    val exportTimestamp: Instant,
    val outcome: ExportOutcome,
    val errorMessage: String?,
    val localFilePath: String?,
    val fileSizeBytes: Long?,
    val fileHash: String?,
    val driveFileId: String?,
    val includedMedia: Boolean,
    val durationMs: Long?,
    val username: String?,
    val createdAt: Instant
)

/**
 * Represents a cached Google Contacts lookup.
 *
 * @property id Unique identifier
 * @property phoneNumber Phone number (primary key)
 * @property contactId Google Contacts ID
 * @property displayName Display name from Contacts
 * @property fetchedAt When contact was fetched from API
 * @property updatedAt Last update timestamp
 * @property createdAt Record creation timestamp
 */
data class ContactCacheEntity(
    val id: Int,
    val phoneNumber: String,
    val contactId: String,
    val displayName: String,
    val fetchedAt: Instant,
    val updatedAt: Instant,
    val createdAt: Instant
)

/**
 * Represents a database migration record.
 *
 * @property id Unique identifier
 * @property version Migration version number
 * @property description Human-readable description
 * @property appliedAt When migration was applied
 * @property checksum Migration integrity checksum
 * @property success Whether migration succeeded
 */
data class MigrationEntity(
    val id: Int,
    val version: Int,
    val description: String,
    val appliedAt: Instant,
    val checksum: String,
    val success: Boolean
)

/**
 * Input data for creating a new chat record.
 */
data class CreateChatInput(
    val chatName: String,
    val occurrenceIndex: Int = 0,
    val contactId: String? = null,
    val phoneNumber: String? = null,
    val channelPrefix: String? = null,
    val contentHash: String? = null
) {
    val normalizedName: String = chatName.lowercase().trim()
}

/**
 * Input data for creating a new export run record.
 */
data class CreateExportRunInput(
    val chatId: Int,
    val outcome: ExportOutcome,
    val errorMessage: String? = null,
    val localFilePath: String? = null,
    val fileSizeBytes: Long? = null,
    val fileHash: String? = null,
    val driveFileId: String? = null,
    val includedMedia: Boolean = false,
    val durationMs: Long? = null,
    val username: String? = System.getProperty("user.name")
)

/**
 * Input data for creating/updating a contact cache entry.
 */
data class CreateContactCacheInput(
    val phoneNumber: String,
    val contactId: String,
    val displayName: String
)

/**
 * Summary of last export for a chat.
 */
data class LastExportSummary(
    val chatId: Int,
    val chatName: String,
    val lastExportTimestamp: Instant?,
    val lastOutcome: ExportOutcome?,
    val daysSinceLastExport: Long?
)

/**
 * Chat with export statistics.
 */
data class ChatWithStats(
    val chat: ChatEntity,
    val totalExports: Long,
    val successfulExports: Long,
    val failedExports: Long,
    val lastExportTimestamp: Instant?,
    val lastOutcome: ExportOutcome?
)
