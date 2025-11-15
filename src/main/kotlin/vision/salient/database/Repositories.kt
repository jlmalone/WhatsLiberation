package vision.salient.database

import org.jetbrains.exposed.sql.*
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant

/**
 * Repository for chat-related database operations.
 *
 * Provides high-level API for:
 * - Finding/creating chats
 * - Querying export history
 * - Identifying chats needing incremental backup
 */
class ChatRepository {
    private val logger = LoggerFactory.getLogger(ChatRepository::class.java)

    /**
     * Find or create a chat by name and occurrence index.
     *
     * This is the primary method for chat lookup during exports.
     *
     * @param chatName Display name from WhatsApp
     * @param occurrenceIndex Index for duplicate names (default 0)
     * @param channelPrefix Channel prefix (e.g., "HK")
     * @return Existing or newly created chat entity
     */
    fun findOrCreate(chatName: String, occurrenceIndex: Int = 0, channelPrefix: String? = null): ChatEntity {
        val normalizedName = chatName.lowercase().trim()

        // Try to find existing chat
        val existing = Chats
            .selectAll()
            .where {
                (Chats.chatName eq chatName) and
                (Chats.occurrenceIndex eq occurrenceIndex) and
                (Chats.channelPrefix eq channelPrefix)
            }
            .singleOrNull()

        if (existing != null) {
            return existing.toChatEntity()
        }

        // Create new chat
        val id = Chats.insert {
            it[Chats.chatName] = chatName
            it[Chats.normalizedName] = normalizedName
            it[Chats.occurrenceIndex] = occurrenceIndex
            it[Chats.channelPrefix] = channelPrefix
        } get Chats.id

        logger.info("Created new chat: $chatName (occurrence: $occurrenceIndex, channel: $channelPrefix)")

        return findById(id.value)!!
    }

    /**
     * Find chat by ID.
     */
    fun findById(id: Int): ChatEntity? {
        return Chats
            .selectAll()
            .where { Chats.id eq id }
            .singleOrNull()
            ?.toChatEntity()
    }

    /**
     * Find chats by name (exact match).
     */
    fun findByName(chatName: String): List<ChatEntity> {
        return Chats
            .selectAll()
            .where { Chats.chatName eq chatName }
            .map { it.toChatEntity() }
    }

    /**
     * Find chats by fuzzy name match.
     *
     * Uses normalized name with LIKE pattern.
     */
    fun findByNameFuzzy(pattern: String): List<ChatEntity> {
        val normalizedPattern = "%${pattern.lowercase().trim()}%"
        return Chats
            .selectAll()
            .where { Chats.normalizedName like normalizedPattern }
            .map { it.toChatEntity() }
    }

    /**
     * Update chat contact information.
     *
     * Called after Google Contacts lookup.
     */
    fun updateContactInfo(chatId: Int, contactId: String?, phoneNumber: String?) {
        Chats.update({ Chats.id eq chatId }) {
            it[Chats.contactId] = contactId
            it[Chats.phoneNumber] = phoneNumber
            it[Chats.updatedAt] = Instant.now()
        }

        logger.debug("Updated contact info for chat $chatId: contactId=$contactId, phone=$phoneNumber")
    }

    /**
     * Update chat content hash.
     *
     * Used for deduplication detection.
     */
    fun updateContentHash(chatId: Int, hash: String) {
        Chats.update({ Chats.id eq chatId }) {
            it[contentHash] = hash
            it[updatedAt] = Instant.now()
        }
    }

    /**
     * Get all chats with their export statistics.
     */
    fun getAllChatsWithStats(): List<ChatWithStats> {
        return Chats
            .leftJoin(ExportRuns, { Chats.id }, { ExportRuns.chatId })
            .select(
                Chats.id,
                Chats.chatName,
                Chats.normalizedName,
                Chats.occurrenceIndex,
                Chats.contactId,
                Chats.phoneNumber,
                Chats.channelPrefix,
                Chats.contentHash,
                Chats.createdAt,
                Chats.updatedAt,
                ExportRuns.id.count(),
                ExportRuns.outcome,
                ExportRuns.exportTimestamp.max()
            )
            .groupBy(Chats.id)
            .map { row =>
                val chat = row.toChatEntity()
                val totalExports = row[ExportRuns.id.count()]
                val lastExportTime = row[ExportRuns.exportTimestamp.max()]

                // Count successful/failed exports
                val successCount = ExportRuns
                    .select(ExportRuns.id.count())
                    .where { (ExportRuns.chatId eq chat.id) and (ExportRuns.outcome eq ExportOutcome.SUCCESS.name) }
                    .single()[ExportRuns.id.count()]

                val failedCount = ExportRuns
                    .select(ExportRuns.id.count())
                    .where { (ExportRuns.chatId eq chat.id) and (ExportRuns.outcome eq ExportOutcome.FAILED.name) }
                    .single()[ExportRuns.id.count()]

                // Get last outcome
                val lastOutcome = if (lastExportTime != null) {
                    ExportRuns
                        .select(ExportRuns.outcome)
                        .where { (ExportRuns.chatId eq chat.id) and (ExportRuns.exportTimestamp eq lastExportTime) }
                        .singleOrNull()
                        ?.get(ExportRuns.outcome)
                        ?.let { ExportOutcome.valueOf(it) }
                } else null

                ChatWithStats(
                    chat = chat,
                    totalExports = totalExports,
                    successfulExports = successCount,
                    failedExports = failedCount,
                    lastExportTimestamp = lastExportTime,
                    lastOutcome = lastOutcome
                )
            }
    }

    /**
     * Get chats that need incremental backup.
     *
     * Returns chats that:
     * - Have never been exported, OR
     * - Last export was more than X hours ago, OR
     * - Last export failed
     *
     * @param olderThanHours Only include chats not exported in last X hours
     * @return List of chats needing export
     */
    fun getChatsNeedingBackup(olderThanHours: Int = 24): List<ChatEntity> {
        val cutoffTime = Instant.now().minusSeconds((olderThanHours * 3600).toLong())

        // Get all chats
        val allChats = Chats.selectAll().map { it.toChatEntity() }

        // Filter based on export history
        return allChats.filter { chat =>
            val lastSuccessfulExport = ExportRuns
                .select(ExportRuns.exportTimestamp.max())
                .where {
                    (ExportRuns.chatId eq chat.id) and
                    (ExportRuns.outcome eq ExportOutcome.SUCCESS.name)
                }
                .singleOrNull()
                ?.get(ExportRuns.exportTimestamp.max())

            // Include if: never exported OR last export before cutoff
            lastSuccessfulExport == null || lastSuccessfulExport < cutoffTime
        }
    }

    /**
     * Get total number of chats in registry.
     */
    fun count(): Long {
        return Chats.selectAll().count()
    }
}

/**
 * Repository for export run operations.
 */
class ExportRunRepository {
    private val logger = LoggerFactory.getLogger(ExportRunRepository::class.java)

    /**
     * Create a new export run record.
     *
     * Called at the end of each export attempt (success or failure).
     */
    fun create(input: CreateExportRunInput): ExportRunEntity {
        val id = ExportRuns.insert {
            it[chatId] = input.chatId
            it[exportTimestamp] = Instant.now()
            it[outcome] = input.outcome.name
            it[errorMessage] = input.errorMessage
            it[localFilePath] = input.localFilePath
            it[fileSizeBytes] = input.fileSizeBytes
            it[fileHash] = input.fileHash
            it[driveFileId] = input.driveFileId
            it[includedMedia] = input.includedMedia
            it[durationMs] = input.durationMs
            it[username] = input.username
        } get ExportRuns.id

        logger.info("Created export run: chatId=${input.chatId}, outcome=${input.outcome}, file=${input.localFilePath}")

        return findById(id.value)!!
    }

    /**
     * Find export run by ID.
     */
    fun findById(id: Int): ExportRunEntity? {
        return ExportRuns
            .selectAll()
            .where { ExportRuns.id eq id }
            .singleOrNull()
            ?.toExportRunEntity()
    }

    /**
     * Get all export runs for a chat.
     */
    fun findByChat(chatId: Int): List<ExportRunEntity> {
        return ExportRuns
            .selectAll()
            .where { ExportRuns.chatId eq chatId }
            .orderBy(ExportRuns.exportTimestamp to SortOrder.DESC)
            .map { it.toExportRunEntity() }
    }

    /**
     * Get last successful export for a chat.
     */
    fun getLastSuccessfulExport(chatId: Int): ExportRunEntity? {
        return ExportRuns
            .selectAll()
            .where {
                (ExportRuns.chatId eq chatId) and
                (ExportRuns.outcome eq ExportOutcome.SUCCESS.name)
            }
            .orderBy(ExportRuns.exportTimestamp to SortOrder.DESC)
            .limit(1)
            .singleOrNull()
            ?.toExportRunEntity()
    }

    /**
     * Get last export (any outcome) for a chat.
     */
    fun getLastExport(chatId: Int): ExportRunEntity? {
        return ExportRuns
            .selectAll()
            .where { ExportRuns.chatId eq chatId }
            .orderBy(ExportRuns.exportTimestamp to SortOrder.DESC)
            .limit(1)
            .singleOrNull()
            ?.toExportRunEntity()
    }

    /**
     * Check if chat has been successfully exported recently.
     *
     * @param chatId Chat to check
     * @param withinHours Time window in hours
     * @return true if exported successfully within time window
     */
    fun hasRecentSuccessfulExport(chatId: Int, withinHours: Int): Boolean {
        val cutoffTime = Instant.now().minusSeconds((withinHours * 3600).toLong())

        return ExportRuns
            .selectAll()
            .where {
                (ExportRuns.chatId eq chatId) and
                (ExportRuns.outcome eq ExportOutcome.SUCCESS.name) and
                (ExportRuns.exportTimestamp greater cutoffTime)
            }
            .count() > 0
    }

    /**
     * Get export runs by outcome.
     */
    fun findByOutcome(outcome: ExportOutcome): List<ExportRunEntity> {
        return ExportRuns
            .selectAll()
            .where { ExportRuns.outcome eq outcome.name }
            .orderBy(ExportRuns.exportTimestamp to SortOrder.DESC)
            .map { it.toExportRunEntity() }
    }

    /**
     * Get export statistics.
     */
    fun getStatistics(since: Instant? = null): ExportStatistics {
        val query = if (since != null) {
            ExportRuns.selectAll().where { ExportRuns.exportTimestamp greater since }
        } else {
            ExportRuns.selectAll()
        }

        val runs = query.map { it.toExportRunEntity() }

        val totalRuns = runs.size.toLong()
        val successfulRuns = runs.count { it.outcome == ExportOutcome.SUCCESS }.toLong()
        val failedRuns = runs.count { it.outcome == ExportOutcome.FAILED }.toLong()
        val skippedRuns = runs.count { it.outcome == ExportOutcome.SKIPPED }.toLong()

        val avgDuration = runs.mapNotNull { it.durationMs }.average().let {
            if (it.isNaN()) null else it.toLong()
        }

        val totalSize = runs.mapNotNull { it.fileSizeBytes }.sum()

        return ExportStatistics(
            totalRuns = totalRuns,
            successfulRuns = successfulRuns,
            failedRuns = failedRuns,
            skippedRuns = skippedRuns,
            averageDurationMs = avgDuration,
            totalSizeBytes = totalSize
        )
    }

    /**
     * Get failed exports that could be retried.
     *
     * @param limit Maximum number of results
     * @return List of failed export runs
     */
    fun getFailedExportsForRetry(limit: Int = 50): List<ExportRunEntity> {
        return ExportRuns
            .selectAll()
            .where { ExportRuns.outcome eq ExportOutcome.FAILED.name }
            .orderBy(ExportRuns.exportTimestamp to SortOrder.DESC)
            .limit(limit)
            .map { it.toExportRunEntity() }
    }
}

/**
 * Repository for contacts cache operations.
 */
class ContactsCacheRepository {
    private val logger = LoggerFactory.getLogger(ContactsCacheRepository::class.java)

    /**
     * Find contact by phone number.
     *
     * @return Cached contact or null if not found
     */
    fun findByPhoneNumber(phoneNumber: String): ContactCacheEntity? {
        return ContactsCache
            .selectAll()
            .where { ContactsCache.phoneNumber eq phoneNumber }
            .singleOrNull()
            ?.toContactCacheEntity()
    }

    /**
     * Create or update contact cache entry.
     *
     * Uses upsert logic: update if exists, insert if not.
     */
    fun upsert(input: CreateContactCacheInput): ContactCacheEntity {
        val existing = findByPhoneNumber(input.phoneNumber)

        if (existing != null) {
            // Update existing
            ContactsCache.update({ ContactsCache.phoneNumber eq input.phoneNumber }) {
                it[contactId] = input.contactId
                it[displayName] = input.displayName
                it[fetchedAt] = Instant.now()
                it[updatedAt] = Instant.now()
            }

            logger.debug("Updated contact cache: ${input.phoneNumber} -> ${input.displayName}")

        } else {
            // Insert new
            ContactsCache.insert {
                it[phoneNumber] = input.phoneNumber
                it[contactId] = input.contactId
                it[displayName] = input.displayName
            }

            logger.info("Created contact cache entry: ${input.phoneNumber} -> ${input.displayName}")
        }

        return findByPhoneNumber(input.phoneNumber)!!
    }

    /**
     * Check if contact cache entry is stale.
     *
     * @param phoneNumber Phone to check
     * @param maxAgeHours Maximum age in hours (default 7 days)
     * @return true if cache is stale or missing
     */
    fun isStale(phoneNumber: String, maxAgeHours: Int = 168): Boolean {
        val cached = findByPhoneNumber(phoneNumber) ?: return true

        val age = Duration.between(cached.fetchedAt, Instant.now())
        return age.toHours() > maxAgeHours
    }

    /**
     * Get total number of cached contacts.
     */
    fun count(): Long {
        return ContactsCache.selectAll().count()
    }

    /**
     * Clear old cache entries.
     *
     * @param olderThanDays Delete entries older than X days
     * @return Number of deleted entries
     */
    fun clearOldEntries(olderThanDays: Int = 90): Int {
        val cutoffTime = Instant.now().minusSeconds((olderThanDays * 24 * 3600).toLong())

        val deleted = ContactsCache.deleteWhere {
            fetchedAt less cutoffTime
        }

        logger.info("Cleared $deleted old contact cache entries (older than $olderThanDays days)")

        return deleted
    }
}

/**
 * Extension functions to convert database rows to entities.
 */
private fun ResultRow.toChatEntity() = ChatEntity(
    id = this[Chats.id].value,
    chatName = this[Chats.chatName],
    normalizedName = this[Chats.normalizedName],
    occurrenceIndex = this[Chats.occurrenceIndex],
    contactId = this[Chats.contactId],
    phoneNumber = this[Chats.phoneNumber],
    channelPrefix = this[Chats.channelPrefix],
    contentHash = this[Chats.contentHash],
    createdAt = this[Chats.createdAt],
    updatedAt = this[Chats.updatedAt]
)

private fun ResultRow.toExportRunEntity() = ExportRunEntity(
    id = this[ExportRuns.id].value,
    chatId = this[ExportRuns.chatId].value,
    exportTimestamp = this[ExportRuns.exportTimestamp],
    outcome = ExportOutcome.valueOf(this[ExportRuns.outcome]),
    errorMessage = this[ExportRuns.errorMessage],
    localFilePath = this[ExportRuns.localFilePath],
    fileSizeBytes = this[ExportRuns.fileSizeBytes],
    fileHash = this[ExportRuns.fileHash],
    driveFileId = this[ExportRuns.driveFileId],
    includedMedia = this[ExportRuns.includedMedia],
    durationMs = this[ExportRuns.durationMs],
    username = this[ExportRuns.username],
    createdAt = this[ExportRuns.createdAt]
)

private fun ResultRow.toContactCacheEntity() = ContactCacheEntity(
    id = this[ContactsCache.id].value,
    phoneNumber = this[ContactsCache.phoneNumber],
    contactId = this[ContactsCache.contactId],
    displayName = this[ContactsCache.displayName],
    fetchedAt = this[ContactsCache.fetchedAt],
    updatedAt = this[ContactsCache.updatedAt],
    createdAt = this[ContactsCache.createdAt]
)

/**
 * Statistics about export runs.
 */
data class ExportStatistics(
    val totalRuns: Long,
    val successfulRuns: Long,
    val failedRuns: Long,
    val skippedRuns: Long,
    val averageDurationMs: Long?,
    val totalSizeBytes: Long
) {
    val successRate: Double
        get() = if (totalRuns > 0) successfulRuns.toDouble() / totalRuns else 0.0
}
