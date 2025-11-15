package vision.salient.database

import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import java.time.Instant

/**
 * Phase C - Persistent Registry Schema
 *
 * This schema enables:
 * - Incremental backups (track last export per chat)
 * - Chat deduplication (identify duplicate exports)
 * - Export history and audit trail
 * - Contact lookup caching (reduce API calls)
 * - Migration version tracking
 */

/**
 * Stores chat metadata for tracking exports over time.
 *
 * Primary use cases:
 * - Identify which chats have been exported and when
 * - Support incremental backups by querying last export timestamp
 * - Handle duplicate chat names via occurrence_index
 * - Link to Google Contacts data for enriched filenames
 */
object Chats : IntIdTable("chats") {
    /** Display name as it appears in WhatsApp UI */
    val chatName = varchar("chat_name", 255).index()

    /** Normalized name for fuzzy matching (lowercase, trimmed) */
    val normalizedName = varchar("normalized_name", 255).index()

    /**
     * Occurrence index for duplicate names (0-based)
     * Example: Three "John" chats would have indices 0, 1, 2
     */
    val occurrenceIndex = integer("occurrence_index").default(0)

    /** Google Contacts ID (nullable, may not be available for groups) */
    val contactId = varchar("contact_id", 100).nullable()

    /** Phone number associated with chat (nullable, not available for groups) */
    val phoneNumber = varchar("phone_number", 50).nullable()

    /** Channel prefix (e.g., "HK" for WhatsApp Business, null for personal) */
    val channelPrefix = varchar("channel_prefix", 10).nullable()

    /** Hash of chat content for deduplication (optional, computed from export file) */
    val contentHash = varchar("content_hash", 64).nullable()

    /** When this chat was first discovered */
    val createdAt = timestamp("created_at").clientDefault { Instant.now() }

    /** Last time this chat record was updated */
    val updatedAt = timestamp("updated_at").clientDefault { Instant.now() }

    /** Composite index for fast lookup by name + occurrence */
    init {
        uniqueIndex("idx_chat_name_occurrence", chatName, occurrenceIndex, channelPrefix)
    }
}

/**
 * Tracks individual export attempts for each chat.
 *
 * Primary use cases:
 * - Query last successful export to enable incremental backups
 * - Audit trail of all export attempts (success and failures)
 * - Performance analysis (duration, file size trends)
 * - Retry failed exports
 */
object ExportRuns : IntIdTable("export_runs") {
    /** Reference to the chat being exported */
    val chatId = reference("chat_id", Chats).index()

    /** When the export was initiated */
    val exportTimestamp = timestamp("export_timestamp").clientDefault { Instant.now() }.index()

    /** Outcome of the export attempt */
    val outcome = varchar("outcome", 20) // SUCCESS, FAILED, SKIPPED

    /** Error message if outcome = FAILED */
    val errorMessage = text("error_message").nullable()

    /** Local file path where export was saved */
    val localFilePath = varchar("local_file_path", 500).nullable()

    /** Size of exported file in bytes */
    val fileSizeBytes = long("file_size_bytes").nullable()

    /** SHA-256 hash of file content for integrity verification */
    val fileHash = varchar("file_hash", 64).nullable().index()

    /** Google Drive file ID if uploaded to Drive */
    val driveFileId = varchar("drive_file_id", 100).nullable()

    /** Whether media was included in this export */
    val includedMedia = bool("included_media").default(false)

    /** Export duration in milliseconds */
    val durationMs = long("duration_ms").nullable()

    /** User who initiated the export */
    val username = varchar("username", 100).nullable()

    /** Record creation timestamp */
    val createdAt = timestamp("created_at").clientDefault { Instant.now() }
}

/**
 * Caches Google Contacts API lookups to minimize API calls.
 *
 * Primary use cases:
 * - Reduce redundant API calls for same phone number
 * - Enable offline filename generation from cached data
 * - Track when cache entries need refreshing
 */
object ContactsCache : IntIdTable("contacts_cache") {
    /** Phone number (unique, primary lookup key) */
    val phoneNumber = varchar("phone_number", 50).uniqueIndex()

    /** Google Contacts ID */
    val contactId = varchar("contact_id", 100)

    /** Display name from Google Contacts */
    val displayName = varchar("display_name", 255)

    /** When this contact was fetched from API */
    val fetchedAt = timestamp("fetched_at").clientDefault { Instant.now() }

    /** Last time this cache entry was updated */
    val updatedAt = timestamp("updated_at").clientDefault { Instant.now() }

    /** Cache entry created timestamp */
    val createdAt = timestamp("created_at").clientDefault { Instant.now() }
}

/**
 * Tracks database schema migrations.
 *
 * Primary use cases:
 * - Version control for database schema
 * - Enable safe migrations and rollbacks
 * - Audit trail of schema changes
 */
object MigrationHistory : IntIdTable("migration_history") {
    /** Migration version number (unique, sequential) */
    val version = integer("version").uniqueIndex()

    /** Human-readable description of migration */
    val description = text("description")

    /** When this migration was applied */
    val appliedAt = timestamp("applied_at").clientDefault { Instant.now() }

    /** Checksum of migration script for integrity verification */
    val checksum = varchar("checksum", 64)

    /** Success status of migration */
    val success = bool("success").default(true)
}

/**
 * Enum for export run outcomes
 */
enum class ExportOutcome {
    SUCCESS,  // Export completed and file saved
    FAILED,   // Export failed with error
    SKIPPED   // Export skipped (e.g., already exported recently)
}
