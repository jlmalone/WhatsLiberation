# Database Integration Guide

**How Applications Use the Database, Query Patterns, Transactions, and Concurrency**

## Overview

This guide explains how to integrate the WhatsLiberation database into the export workflow, covering common usage patterns, transaction boundaries, concurrency handling, and best practices.

## Architecture Overview

```
┌──────────────────────────────────────────────────────────────┐
│                    Application Layer                          │
│  (SingleChatExportRunner, MultiChatBackupRunner, CLI)        │
└────────────────────────┬─────────────────────────────────────┘
                         │
                         ▼
┌──────────────────────────────────────────────────────────────┐
│                   DatabaseService                             │
│  • High-level export tracking API                            │
│  • Automatic transaction management                          │
│  • Connection pooling                                         │
└────────────────────────┬─────────────────────────────────────┘
                         │
         ┌───────────────┼───────────────┐
         ▼               ▼               ▼
┌────────────────┬───────────────┬──────────────────┐
│ ChatRepository │ ExportRunRepo │ ContactsCacheRepo │
│  (CRUD & Query)│ (CRUD & Stats)│  (Cache Mgmt)     │
└────────┬───────┴──────┬────────┴────────┬─────────┘
         │              │                  │
         └──────────────┼──────────────────┘
                        ▼
         ┌──────────────────────────────┐
         │      DatabaseManager          │
         │  • Connection lifecycle       │
         │  • Transaction boundaries     │
         │  • Migration execution        │
         └──────────────┬────────────────┘
                        ▼
         ┌──────────────────────────────┐
         │   Exposed ORM + SQLite JDBC  │
         └──────────────────────────────┘
```

## Integration Points

### 1. Application Initialization

**At startup**, initialize the database service:

```kotlin
// src/main/kotlin/vision/salient/cli/WhatsLiberationCli.kt

fun main(args: Array<String>) {
    // Load configuration
    val config = Config.appConfig

    // Validate configuration
    val validation = Config.validation()
    if (!validation.isValid) {
        logger.error("Configuration errors: ${validation.errors}")
        exitProcess(1)
    }

    // Initialize database (if enabled)
    val dbService = if (config.database.enabled) {
        DatabaseService(config.database).also {
            try {
                it.initialize()
                logger.info("Database service initialized successfully")
            } catch (e: DatabaseInitializationException) {
                logger.error("Database initialization failed", e)
                exitProcess(1)
            }
        }
    } else {
        logger.warn("Database is disabled - running in stateless mode")
        null
    }

    // Run export workflow
    val runner = MultiChatBackupRunner(dbServiceProvider = { dbService })
    val result = runner.run(request)

    // Cleanup
    dbService?.close()
}
```

---

### 2. Single Chat Export Integration

**During export**, track each attempt in the database:

```kotlin
// src/main/kotlin/vision/salient/export/SingleChatExportRunner.kt

class SingleChatExportRunner(
    private val dbService: DatabaseService? = null  // Optional, can be null if disabled
) {

    fun run(request: SingleChatExportRequest): SingleChatExportResult {
        val startTime = System.currentTimeMillis()

        // Step 1: Record export start (find or create chat)
        val chat = dbService?.recordExportStart(
            chatName = request.targetChat,
            occurrenceIndex = request.matchIndex ?: 0,
            channelPrefix = request.channelPrefix
        )

        try {
            // Step 2: Check if incremental backup should skip this chat
            if (request.incremental && dbService != null && chat != null) {
                if (!dbService.shouldBackupChat(
                    chatName = chat.chatName,
                    occurrenceIndex = chat.occurrenceIndex,
                    channelPrefix = chat.channelPrefix,
                    olderThanHours = 24
                )) {
                    logger.info("Skipping ${chat.chatName} - already exported recently")

                    dbService.recordExportSkipped(
                        chatId = chat.id,
                        reason = "Already exported successfully within last 24 hours"
                    )

                    return SingleChatExportResult.Success(
                        chatName = chat.chatName,
                        runDirectory = runDirectory,
                        exportedFiles = emptyList()
                    )
                }
            }

            // Step 3: Perform export (existing automation logic)
            val workflow = Workflow(config, repository, adbClient, request, ...)
            val exportFile = workflow.execute()

            // Step 4: Enrich with Google Contacts (if available)
            val contactsClient = GoogleContactsClient(config.contacts)
            val phoneNumber = extractPhoneNumber(request.targetChat)  // Your logic
            val contact = if (phoneNumber != null) {
                // Check cache first
                dbService?.getCachedContact(phoneNumber) ?: run {
                    // Call API and cache result
                    val apiContact = contactsClient.lookup(phoneNumber)
                    dbService?.cacheContact(phoneNumber, apiContact.id, apiContact.name)
                    apiContact
                }
            } else null

            // Update chat with contact info
            if (chat != null && contact != null) {
                dbService?.updateChatContactInfo(chat.id, contact.id, phoneNumber)
            }

            // Step 5: Record success
            val duration = System.currentTimeMillis() - startTime
            chat?.let {
                dbService?.recordExportSuccess(
                    chatId = it.id,
                    filePath = exportFile.toString(),
                    includedMedia = request.includeMedia,
                    durationMs = duration,
                    driveFileId = driveFileId  // If uploaded to Drive
                )
            }

            return SingleChatExportResult.Success(
                chatName = request.targetChat,
                runDirectory = runDirectory,
                exportedFiles = listOf(exportFile)
            )

        } catch (e: Exception) {
            // Step 6: Record failure
            val duration = System.currentTimeMillis() - startTime
            chat?.let {
                dbService?.recordExportFailure(
                    chatId = it.id,
                    errorMessage = e.message ?: "Unknown error",
                    durationMs = duration
                )
            }

            logger.error("Export failed for ${request.targetChat}", e)
            return SingleChatExportResult.Failure(e.message ?: "Export failed", e)
        }
    }
}
```

---

### 3. Multi-Chat Backup Integration

**For batch exports**, track all chats systematically:

```kotlin
// src/main/kotlin/vision/salient/export/MultiChatBackupRunner.kt

class MultiChatBackupRunner(
    private val dbServiceProvider: () -> DatabaseService? = { null }
) {

    fun run(request: MultiChatBackupRequest): MultiChatBackupResult {
        val dbService = dbServiceProvider()

        // Discover chats to export
        val chatsToExport = if (request.incremental && dbService != null) {
            // Incremental mode: Only export chats needing backup
            dbService.getChatsNeedingBackup(olderThanHours = request.incrementalHours ?: 24)
                .map { ChatSelection(it.chatName, it.occurrenceIndex) }
                .take(request.maxChats ?: Int.MAX_VALUE)
        } else {
            // Full mode: Discover from device
            val collector = ChatCollector(config, adbClient)
            collector.collectChats(request.maxChats ?: Int.MAX_VALUE)
        }

        logger.info("Discovered ${chatsToExport.size} chats for backup")

        // Export each chat
        val results = chatsToExport.map { selection ->
            val singleRequest = SingleChatExportRequest(
                targetChat = selection.name,
                matchIndex = selection.occurrence,
                includeMedia = request.includeMedia,
                shareTarget = request.shareTarget,
                driveFolder = request.driveFolder,
                channelPrefix = request.channelPrefix,
                incremental = request.incremental
            )

            singleRunner.run(singleRequest)
        }

        // Generate summary with database statistics
        val stats = dbService?.getExportStatistics()

        return MultiChatBackupResult(
            successfulChats = results.filterIsInstance<Success>().map { it.chatName },
            downloadedFiles = results.flatMap { it.exportedFiles },
            failedChats = results.filterIsInstance<Failure>().map { it.chatName to it.reason },
            skippedChats = results.filterIsInstance<Skipped>().map { it.chatName },
            statistics = stats
        )
    }
}
```

---

## Common Query Patterns

### Pattern 1: Find or Create

**Use Case**: Ensure chat exists before recording export

```kotlin
fun findOrCreateChat(chatName: String, occurrence: Int = 0): ChatEntity {
    return dbService.executeTransaction {
        chatRepo.findOrCreate(chatName, occurrence)
    }
}
```

**SQL Equivalent**:
```sql
-- Check existence
SELECT * FROM chats WHERE chat_name = ? AND occurrence_index = ?;

-- If not exists, insert
INSERT INTO chats (chat_name, normalized_name, occurrence_index) VALUES (?, ?, ?);
```

---

### Pattern 2: Incremental Backup Query

**Use Case**: Get list of chats needing export

```kotlin
fun getChatsNeedingBackup(olderThanHours: Int): List<ChatEntity> {
    return dbService.executeTransaction {
        chatRepo.getChatsNeedingBackup(olderThanHours)
    }
}
```

**SQL Equivalent**:
```sql
SELECT c.*
FROM chats c
WHERE NOT EXISTS (
    SELECT 1 FROM export_runs e
    WHERE e.chat_id = c.id
      AND e.outcome = 'SUCCESS'
      AND e.export_timestamp >= datetime('now', '-24 hours')
)
ORDER BY c.chat_name;
```

---

### Pattern 3: Contact Cache Lookup

**Use Case**: Check cache before calling Google Contacts API

```kotlin
fun getContactInfo(phoneNumber: String): ContactInfo? {
    // Check database cache
    val cached = dbService?.getCachedContact(phoneNumber, maxAgeHours = 168)
    if (cached != null) {
        logger.debug("Contact cache HIT for $phoneNumber")
        return ContactInfo(cached.contactId, cached.displayName)
    }

    // Cache MISS - call API
    logger.debug("Contact cache MISS for $phoneNumber")
    val apiContact = contactsClient.lookup(phoneNumber) ?: return null

    // Cache result
    dbService?.cacheContact(phoneNumber, apiContact.id, apiContact.name)

    return apiContact
}
```

---

### Pattern 4: Export Statistics for Dashboard

**Use Case**: Display success rate, average duration, etc.

```kotlin
fun getExportDashboard(): ExportDashboard {
    val stats = dbService?.getExportStatistics() ?: return ExportDashboard.empty()

    val chatsWithStats = dbService?.getAllChatsWithStats() ?: emptyList()

    return ExportDashboard(
        totalChats = chatsWithStats.size,
        totalExports = stats.totalRuns,
        successRate = stats.successRate,
        avgDurationSeconds = (stats.averageDurationMs ?: 0) / 1000.0,
        totalSizeMB = stats.totalSizeBytes / 1024.0 / 1024.0,
        recentFailures = chatsWithStats
            .filter { it.lastOutcome == ExportOutcome.FAILED }
            .sortedByDescending { it.lastExportTimestamp }
            .take(10)
    )
}
```

---

## Transaction Boundaries

### Automatic Transactions (Recommended)

**Exposed handles transactions automatically** via `DatabaseService.executeTransaction()`:

```kotlin
// Single transaction wrapping multiple operations
dbService.executeTransaction {
    val chat = chatRepo.findOrCreate("Chuck Malone", 0)

    chatRepo.updateContactInfo(chat.id, "c123", "+13239746605")

    exportRepo.create(CreateExportRunInput(
        chatId = chat.id,
        outcome = ExportOutcome.SUCCESS,
        ...
    ))
}
```

**Benefits**:
- All-or-nothing guarantee (ACID)
- Automatic rollback on exception
- Better performance (single commit)

---

### Manual Transaction Control (Advanced)

**For fine-grained control**:

```kotlin
import org.jetbrains.exposed.sql.transactions.transaction

transaction {
    // Isolation level
    this.isolation = Connection.TRANSACTION_SERIALIZABLE

    // Your operations
    val chat = Chats.insert { ... } get Chats.id

    ExportRuns.insert {
        it[chatId] = chat.value
        ...
    }

    // Explicit rollback on condition
    if (someCondition) {
        rollback()
    }

    // Explicit commit (automatic if no exception)
    commit()
}
```

---

### Transaction Best Practices

**✅ DO**:
- Keep transactions short (< 100ms ideal)
- Group related operations in one transaction
- Use `executeTransaction()` wrapper for safety
- Handle exceptions and rollback appropriately

**❌ DON'T**:
- Call external APIs inside transactions (network latency = long transaction)
- Perform file I/O inside transactions
- Nest transactions (Exposed doesn't support it)
- Hold transactions during user input

**Good Example** (Fast transaction):
```kotlin
// Fast DB operations only
val chatId = dbService.executeTransaction {
    chatRepo.findOrCreate("Chuck", 0).id
}

// Slow operations OUTSIDE transaction
val exportFile = performExport()  // Takes 30 seconds

// Fast DB operation
dbService.executeTransaction {
    exportRepo.create(CreateExportRunInput(chatId, ...))
}
```

**Bad Example** (Slow transaction):
```kotlin
dbService.executeTransaction {
    val chat = chatRepo.findOrCreate("Chuck", 0)

    // BAD: Network call inside transaction
    val contact = googleContactsClient.lookup("+1234567890")

    // BAD: File I/O inside transaction
    val exportFile = performExport()  // Holds DB lock for 30 seconds!

    exportRepo.create(CreateExportRunInput(chat.id, ...))
}
```

---

## Concurrency Handling

### SQLite Concurrency Model

**Single-Writer, Multiple-Readers**:
- One process can write at a time
- Multiple processes can read simultaneously
- Writers block readers (in rollback journal mode)
- Writers DON'T block readers (in WAL mode) ✅

### Enable WAL Mode (Recommended)

```kotlin
class DatabaseManager {
    fun initialize() {
        database = Database.connect(url, driver)

        transaction {
            exec("PRAGMA journal_mode = WAL")
            exec("PRAGMA synchronous = NORMAL")
        }

        logger.info("WAL mode enabled for concurrent access")
    }
}
```

**Benefits**:
- Readers don't block writers
- Writers don't block readers
- Better performance under concurrent load

---

### Handling Write Conflicts

**Problem**: Two processes try to write simultaneously → `SQLITE_BUSY`

**Solution**: Retry with exponential backoff

```kotlin
fun <T> retryOnBusy(maxRetries: Int = 5, block: () -> T): T {
    var attempt = 0
    var delay = 100L  // Start with 100ms

    while (true) {
        try {
            return block()
        } catch (e: SQLException) {
            if (e.message?.contains("database is locked") == true && attempt < maxRetries) {
                logger.warn("Database locked, retrying in ${delay}ms (attempt ${attempt + 1}/$maxRetries)")
                Thread.sleep(delay)
                delay *= 2  // Exponential backoff: 100ms, 200ms, 400ms, 800ms, 1600ms
                attempt++
            } else {
                throw e
            }
        }
    }
}

// Usage
val chat = retryOnBusy {
    dbService.executeTransaction {
        chatRepo.findOrCreate("Chuck", 0)
    }
}
```

---

### Connection Pooling

**SQLite doesn't support connection pooling** (single-file database), but Exposed manages connections internally.

**Best practice**: Reuse single `DatabaseManager` instance across application:

```kotlin
// Application-wide singleton
object AppDatabase {
    private val dbService: DatabaseService by lazy {
        DatabaseService(Config.appConfig.database).also {
            it.initialize()
        }
    }

    fun getService(): DatabaseService = dbService
}

// Usage in export runners
class SingleChatExportRunner {
    private val dbService = AppDatabase.getService()
    ...
}
```

---

## Error Handling

### Database Initialization Failures

```kotlin
try {
    dbService.initialize()
} catch (e: DatabaseInitializationException) {
    logger.error("Database initialization failed: ${e.message}", e)

    // Fallback: Restore from backup
    val backup = Paths.get("~/.whatsliberation/registry-backup-latest.db")
    if (Files.exists(backup)) {
        logger.info("Restoring from backup: $backup")
        Files.copy(backup, config.database.path, StandardCopyOption.REPLACE_EXISTING)

        // Retry initialization
        dbService.initialize()
    } else {
        logger.error("No backup available, cannot recover")
        exitProcess(1)
    }
}
```

---

### Query Execution Failures

```kotlin
try {
    val chat = dbService.executeTransaction {
        chatRepo.findOrCreate(chatName, 0)
    }
} catch (e: SQLException) {
    logger.error("Database query failed", e)

    // Check if database is corrupted
    if (e.message?.contains("malformed") == true) {
        logger.error("Database corruption detected - restore from backup required")
        // Alert admin, restore from backup, etc.
    }

    // Continue without database (degraded mode)
    // Export will work, but no history tracking
}
```

---

### Graceful Degradation

**If database is unavailable, continue export without tracking**:

```kotlin
class SingleChatExportRunner(
    private val dbService: DatabaseService? = null  // Nullable
) {

    fun run(request: SingleChatExportRequest): SingleChatExportResult {
        // Attempt database operations, but don't fail export if DB unavailable
        val chat = try {
            dbService?.recordExportStart(request.targetChat, 0)
        } catch (e: Exception) {
            logger.warn("Database unavailable, continuing without tracking", e)
            null
        }

        // Perform export (always works, even if DB unavailable)
        val exportFile = performExport()

        // Attempt to record success
        try {
            chat?.let { dbService?.recordExportSuccess(it.id, ...) }
        } catch (e: Exception) {
            logger.warn("Failed to record export success in database", e)
        }

        return SingleChatExportResult.Success(...)
    }
}
```

---

## Testing Integration

### Unit Tests with In-Memory Database

```kotlin
// src/test/kotlin/vision/salient/database/DatabaseServiceTest.kt

class DatabaseServiceTest {

    private lateinit var dbService: DatabaseService

    @BeforeEach
    fun setup() {
        val testConfig = DatabaseConfig(
            enabled = true,
            path = Paths.get(":memory:"),  // In-memory SQLite for tests
            vacuumOnStartup = false
        )

        dbService = DatabaseService(testConfig)
        dbService.initialize()
    }

    @Test
    fun `test find or create chat`() {
        // Create chat
        val chat1 = dbService.recordExportStart("Chuck Malone", 0)
        assertNotNull(chat1)
        assertEquals("Chuck Malone", chat1.chatName)

        // Find existing chat (should not create duplicate)
        val chat2 = dbService.recordExportStart("Chuck Malone", 0)
        assertEquals(chat1.id, chat2.id)
    }

    @Test
    fun `test incremental backup logic`() {
        val chat = dbService.recordExportStart("Test Chat", 0)

        // First export - should backup
        assertTrue(dbService.shouldBackupChat("Test Chat", 0, null, olderThanHours = 24))

        // Record successful export
        dbService.recordExportSuccess(chat.id, "/tmp/export.txt", false, 1000)

        // Second export within 24 hours - should skip
        assertFalse(dbService.shouldBackupChat("Test Chat", 0, null, olderThanHours = 24))
    }

    @AfterEach
    fun cleanup() {
        dbService.close()
    }
}
```

---

### Integration Tests with Real Database

```kotlin
class ExportIntegrationTest {

    @Test
    fun `test full export workflow with database`() {
        val tempDb = Files.createTempFile("test-registry", ".db")

        try {
            val config = DatabaseConfig(enabled = true, path = tempDb)
            val dbService = DatabaseService(config)
            dbService.initialize()

            val runner = SingleChatExportRunner(dbService = dbService)

            // Run export
            val result = runner.run(SingleChatExportRequest(
                targetChat = "Test Chat",
                dryRun = false,
                ...
            ))

            // Verify database recorded export
            val stats = dbService.getDatabaseStatistics()
            assertEquals(1, stats.totalChats)
            assertEquals(1, stats.totalExportRuns)

            dbService.close()

        } finally {
            Files.deleteIfExists(tempDb)
        }
    }
}
```

---

## CLI Commands for Database Inspection

### View Database Statistics

```bash
# Add to CLI
./gradlew run --args="db stats"
```

**Output**:
```
Database Statistics:
  Location: /home/user/.whatsliberation/registry.db
  Size: 45.3 MB
  Total Chats: 2,543
  Total Exports: 51,234
  Successful Exports: 48,621 (94.9%)
  Failed Exports: 2,613 (5.1%)
  Cached Contacts: 1,847
  Average Export Duration: 29.2 seconds
  Total Exported Size: 5.2 GB
```

### List Chats Needing Backup

```bash
./gradlew run --args="db list-pending --hours 24"
```

**Output**:
```
Chats needing backup (not exported in last 24 hours):
  1. Chuck Malone (last exported: 3 days ago)
  2. Heidi London (last exported: 5 days ago)
  3. Ana Belgium (never exported)
  ...
Total: 127 chats
```

### Retry Failed Exports

```bash
./gradlew run --args="db retry-failed --limit 10"
```

**Output**:
```
Retrying 10 failed exports...
  ✓ Chuck Malone - SUCCESS
  ✓ Heidi London - SUCCESS
  ✗ Unknown Chat - FAILED (chat not found)
  ...
Success: 8/10 (80%)
```

---

## References

- [Schema Documentation](SCHEMA.md) - Table structures and relationships
- [Query Examples](QUERIES.md) - 50+ sample queries
- [Performance Guide](PERFORMANCE.md) - Optimization techniques
- [Backup & Recovery](BACKUP_RECOVERY.md) - Disaster recovery procedures
- Source Code:
  - `src/main/kotlin/vision/salient/database/DatabaseService.kt`
  - `src/main/kotlin/vision/salient/database/Repositories.kt`
  - `src/main/kotlin/vision/salient/export/SingleChatExportRunner.kt`
