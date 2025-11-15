# WhatsLiberation Database Documentation

**Phase C - Persistent Registry Implementation**

This directory contains comprehensive documentation for the WhatsLiberation persistent registry database.

## Quick Links

- **[Schema Documentation](SCHEMA.md)** - Complete database schema with ER diagrams, tables, indexes, and constraints
- **[Query Examples](QUERIES.md)** - 50+ sample queries for common operations
- **[Migration Guide](MIGRATIONS.md)** - Migration strategy, version history, and rollback procedures
- **[Performance Guide](PERFORMANCE.md)** - Index usage, query optimization, and scaling considerations
- **[Backup & Recovery](BACKUP_RECOVERY.md)** - Backup procedures and disaster recovery
- **[Integration Guide](INTEGRATION.md)** - How applications use the database, transaction boundaries, concurrency

## Overview

The WhatsLiberation database is a SQLite-based persistent registry that enables:

### ✅ Incremental Backups
- Track when each chat was last exported
- Only export chats that have changed or are new
- Skip chats exported within a configurable time window

### ✅ Export History & Audit Trail
- Complete history of all export attempts (success and failures)
- Duration tracking for performance analysis
- Error messages for troubleshooting
- File integrity verification via SHA-256 hashing

### ✅ Contact Lookup Caching
- Cache Google Contacts API lookups to minimize API calls
- Support offline filename generation from cached data
- Automatic cache expiration and refresh

### ✅ Deduplication
- Detect duplicate exports via content hashing
- Handle duplicate chat names via occurrence index
- Prevent redundant backups

### ✅ Performance Monitoring
- Track export success rates over time
- Analyze average export duration
- Monitor file sizes and storage usage
- Identify problematic chats for retry

## Database Location

Default: `~/.whatsliberation/registry.db`

Custom location via environment variable:
```bash
DATABASE_PATH=/path/to/custom/registry.db
```

Disable database (fallback to stateless mode):
```bash
DATABASE_ENABLED=false
```

## Quick Start

### 1. Initialize Database

The database is automatically initialized on first run:

```kotlin
val config = Config.appConfig.database
val dbService = DatabaseService(config)
dbService.initialize()
```

### 2. Record an Export

```kotlin
// Start export
val chat = dbService.recordExportStart("Chuck Malone", occurrenceIndex = 0)

// On success
dbService.recordExportSuccess(
    chatId = chat.id,
    filePath = "/path/to/export.txt",
    includedMedia = false,
    durationMs = 29000,
    driveFileId = "abc123"
)

// On failure
dbService.recordExportFailure(
    chatId = chat.id,
    errorMessage = "Chat not found",
    durationMs = 5000
)
```

### 3. Incremental Backup

```kotlin
// Check if chat needs backup (not exported in last 24 hours)
if (dbService.shouldBackupChat("Chuck Malone", olderThanHours = 24)) {
    // Perform export
}

// Get all chats needing backup
val chatsToBackup = dbService.getChatsNeedingBackup(olderThanHours = 24)
```

### 4. Contact Caching

```kotlin
// Check cache before API call
val cached = dbService.getCachedContact("+13239746605")
if (cached != null) {
    // Use cached contact
} else {
    // Call API and cache result
    val contact = googleContactsClient.lookup(phoneNumber)
    dbService.cacheContact(phoneNumber, contact.id, contact.name)
}
```

## Architecture

```
┌─────────────────────────────────────────────────────────┐
│                   Export Workflow                        │
│  (SingleChatExportRunner, MultiChatBackupRunner)        │
└────────────────────┬────────────────────────────────────┘
                     │
                     ▼
┌─────────────────────────────────────────────────────────┐
│                 DatabaseService                          │
│  (High-level API for export tracking)                   │
└────────────────────┬────────────────────────────────────┘
                     │
         ┌───────────┼───────────┐
         ▼           ▼           ▼
┌───────────────┬────────────┬──────────────────┐
│ ChatRepository│ExportRunRepo│ContactsCacheRepo │
│  (Chats CRUD) │(Export CRUD)│(Contact CRUD)    │
└───────┬───────┴─────┬──────┴──────────┬───────┘
        │             │                  │
        └─────────────┼──────────────────┘
                      ▼
        ┌──────────────────────────────┐
        │      DatabaseManager         │
        │  (Connection, Migrations)    │
        └──────────────┬───────────────┘
                       ▼
        ┌──────────────────────────────┐
        │   SQLite Database (Exposed)  │
        │   ~/.whatsliberation/        │
        │   registry.db                │
        └──────────────────────────────┘
```

## Key Components

### Schema (`src/main/kotlin/vision/salient/database/Schema.kt`)
Defines all database tables using Exposed DSL:
- `Chats` - Chat metadata
- `ExportRuns` - Export history
- `ContactsCache` - Contact lookup cache
- `MigrationHistory` - Schema version tracking

### Entities (`src/main/kotlin/vision/salient/database/Entities.kt`)
Type-safe data classes for database records:
- `ChatEntity`
- `ExportRunEntity`
- `ContactCacheEntity`
- Plus input DTOs for create operations

### Repositories (`src/main/kotlin/vision/salient/database/Repositories.kt`)
High-level data access APIs:
- `ChatRepository` - Chat CRUD and queries
- `ExportRunRepository` - Export history and statistics
- `ContactsCacheRepository` - Contact caching

### Database Manager (`src/main/kotlin/vision/salient/database/Database.kt`)
Connection management, transaction boundaries, backup/restore

### Migrations (`src/main/kotlin/vision/salient/database/Migrations.kt`)
Version-controlled schema evolution with rollback support

### Database Service (`src/main/kotlin/vision/salient/database/DatabaseService.kt`)
Export workflow integration API

## Configuration

Add to `.env` file:

```bash
# Database Configuration (Phase C)
DATABASE_ENABLED=true                              # Enable persistent registry (default: true)
DATABASE_PATH=/custom/path/registry.db             # Custom database location (optional)
DATABASE_VACUUM_ON_STARTUP=false                   # Run VACUUM on startup (default: false)
```

## Statistics

Get database statistics:

```kotlin
val stats = dbService.getDatabaseStatistics()
println("Total chats: ${stats.totalChats}")
println("Total exports: ${stats.totalExportRuns}")
println("Cached contacts: ${stats.cachedContacts}")
println("Database size: ${stats.databaseSizeBytes} bytes")
```

Get export statistics:

```kotlin
val exportStats = dbService.getExportStatistics()
println("Success rate: ${exportStats.successRate * 100}%")
println("Average duration: ${exportStats.averageDurationMs}ms")
println("Total size: ${exportStats.totalSizeBytes} bytes")
```

## Maintenance

### Vacuum Database

Reclaim disk space and optimize performance:

```bash
# Via code
dbService.vacuum()

# Or set in .env for automatic vacuum on startup
DATABASE_VACUUM_ON_STARTUP=true
```

### Backup Database

```kotlin
// Create backup
val backupPath = dbService.createBackup()
println("Backup created: $backupPath")

// Custom backup location
val customBackup = dbService.createBackup(Paths.get("/backups/registry-2025-11-15.db"))
```

### Cleanup Old Cache

```kotlin
// Remove contact cache entries older than 90 days
val deleted = dbService.cleanupOldCacheEntries(olderThanDays = 90)
println("Deleted $deleted old cache entries")
```

## Success Criteria

✅ **ER diagrams for all databases** - See [SCHEMA.md](SCHEMA.md)
✅ **Complete schema documentation** - See [SCHEMA.md](SCHEMA.md)
✅ **50+ example queries documented** - See [QUERIES.md](QUERIES.md)
✅ **Migration strategy documented** - See [MIGRATIONS.md](MIGRATIONS.md)
✅ **Performance considerations documented** - See [PERFORMANCE.md](PERFORMANCE.md)
✅ **Backup and recovery procedures** - See [BACKUP_RECOVERY.md](BACKUP_RECOVERY.md)
✅ **Integration documentation** - See [INTEGRATION.md](INTEGRATION.md)

## Next Steps

1. **Enable Incremental Backups** - Add `--incremental` flag to CLI
2. **Retry Failed Exports** - Add `--retry-failed` command
3. **Export Statistics Dashboard** - Add `stats` command to view export metrics
4. **Database Migration Tools** - Add `db migrate`, `db rollback`, `db backup` commands

## Support

- **Issues**: [GitHub Issues](https://github.com/jlmalone/WhatsLiberation/issues)
- **Documentation**: `docs/database/`
- **Tests**: `src/test/kotlin/vision/salient/database/`
