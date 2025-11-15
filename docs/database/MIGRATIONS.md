# Database Migration Guide

**Migration Strategy, Version History, and Rollback Procedures**

## Overview

WhatsLiberation uses a versioned migration system to evolve the database schema safely over time. This document describes the migration strategy, provides version history, and explains rollback procedures.

## Migration Strategy

### Version Control Approach

1. **Sequential Versioning** - Each migration has a unique integer version number (1, 2, 3, ...)
2. **Checksum Verification** - SHA-256 checksums ensure migration integrity
3. **Transaction Safety** - All migrations run within transactions (atomic)
4. **History Tracking** - `migration_history` table records all applied migrations
5. **Rollback Support** - Each migration provides both `up()` and `down()` methods

### Migration Lifecycle

```
┌─────────────────────────────────────────────────────────┐
│           Migration Execution Lifecycle                  │
└─────────────────────────────────────────────────────────┘

1. APPLICATION STARTUP
   └─ DatabaseManager.initialize()
      └─ MigrationRunner.runMigrations()

2. CHECK MIGRATION HISTORY TABLE
   ├─ If doesn't exist: CREATE TABLE migration_history
   └─ If exists: Read current version

3. IDENTIFY PENDING MIGRATIONS
   ├─ Current version: SELECT MAX(version) FROM migration_history WHERE success = true
   ├─ Available migrations: getAllMigrations()
   └─ Pending: migrations.filter { it.version > currentVersion }

4. EXECUTE PENDING MIGRATIONS (in order)
   For each migration:
   ├─ BEGIN TRANSACTION
   ├─ Execute migration.up()
   ├─ Record in migration_history (version, description, checksum, success)
   ├─ COMMIT TRANSACTION
   └─ Log success

5. ON ERROR
   ├─ ROLLBACK TRANSACTION
   ├─ Record failure in migration_history (success = false)
   └─ THROW MigrationException (halts application startup)
```

### Migration File Structure

Each migration is a Kotlin class extending `Migration`:

```kotlin
class Migration00XDescription : Migration() {
    override val version = X                // Unique version number
    override val description = "..."        // Human-readable description

    override fun up() {
        // Apply schema changes (CREATE, ALTER, etc.)
    }

    override fun down() {
        // Revert schema changes (DROP, ALTER, etc.)
    }
}
```

## Version History

### Version 1: Initial Schema (2025-11-15)

**Description**: Create chats, export_runs, contacts_cache tables

**Changes**:
- ✅ Created `chats` table with indexes
- ✅ Created `export_runs` table with foreign key to chats
- ✅ Created `contacts_cache` table with unique phone index
- ✅ Added composite unique index on chats (name, occurrence, channel)

**Migration Code** (`Migration001InitialSchema`):

```kotlin
class Migration001InitialSchema : Migration() {
    override val version = 1
    override val description = "Initial schema: create chats, export_runs, contacts_cache tables"

    override fun up() {
        SchemaUtils.create(Chats, ExportRuns, ContactsCache)
    }

    override fun down() {
        SchemaUtils.drop(ContactsCache, ExportRuns, Chats)
    }
}
```

**SQL Equivalent** (for reference):

```sql
-- UP Migration
CREATE TABLE chats (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    chat_name VARCHAR(255) NOT NULL,
    normalized_name VARCHAR(255) NOT NULL,
    occurrence_index INTEGER NOT NULL DEFAULT 0,
    contact_id VARCHAR(100),
    phone_number VARCHAR(50),
    channel_prefix VARCHAR(10),
    content_hash VARCHAR(64),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_chats_chat_name ON chats(chat_name);
CREATE INDEX idx_chats_normalized_name ON chats(normalized_name);
CREATE UNIQUE INDEX idx_chats_name_occurrence ON chats(chat_name, occurrence_index, channel_prefix);

CREATE TABLE export_runs (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    chat_id INTEGER NOT NULL,
    export_timestamp TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    outcome VARCHAR(20) NOT NULL,
    error_message TEXT,
    local_file_path VARCHAR(500),
    file_size_bytes BIGINT,
    file_hash VARCHAR(64),
    drive_file_id VARCHAR(100),
    included_media BOOLEAN NOT NULL DEFAULT 0,
    duration_ms BIGINT,
    username VARCHAR(100),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (chat_id) REFERENCES chats(id)
);

CREATE INDEX idx_export_runs_chat_id ON export_runs(chat_id);
CREATE INDEX idx_export_runs_timestamp ON export_runs(export_timestamp);
CREATE INDEX idx_export_runs_file_hash ON export_runs(file_hash);

CREATE TABLE contacts_cache (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    phone_number VARCHAR(50) NOT NULL UNIQUE,
    contact_id VARCHAR(100) NOT NULL,
    display_name VARCHAR(255) NOT NULL,
    fetched_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX idx_contacts_cache_phone ON contacts_cache(phone_number);

-- DOWN Migration
DROP TABLE contacts_cache;
DROP TABLE export_runs;
DROP TABLE chats;
```

**Impact**: Creates entire database schema from scratch

**Rollback**: Drops all tables (WARNING: data loss!)

---

### Version 2: Add Retry Tracking (Planned)

**Description**: Add retry_count and last_retry_at columns to export_runs

**Planned Changes**:
- Add `retry_count INTEGER DEFAULT 0` to `export_runs`
- Add `last_retry_at TIMESTAMP` to `export_runs`
- Add index on `retry_count` for filtering retryable exports

**Migration Code** (planned):

```kotlin
class Migration002RetryTracking : Migration() {
    override val version = 2
    override val description = "Add retry tracking to export_runs"

    override fun up() {
        transaction {
            exec("""
                ALTER TABLE export_runs
                ADD COLUMN retry_count INTEGER NOT NULL DEFAULT 0
            """)

            exec("""
                ALTER TABLE export_runs
                ADD COLUMN last_retry_at TIMESTAMP
            """)

            exec("""
                CREATE INDEX idx_export_runs_retry_count
                ON export_runs(retry_count)
            """)
        }
    }

    override fun down() {
        transaction {
            exec("DROP INDEX idx_export_runs_retry_count")

            // SQLite doesn't support DROP COLUMN directly
            // Need to recreate table without these columns
            exec("""
                CREATE TABLE export_runs_backup AS
                SELECT id, chat_id, export_timestamp, outcome, error_message,
                       local_file_path, file_size_bytes, file_hash, drive_file_id,
                       included_media, duration_ms, username, created_at
                FROM export_runs
            """)

            exec("DROP TABLE export_runs")
            exec("ALTER TABLE export_runs_backup RENAME TO export_runs")

            // Recreate indexes
            exec("CREATE INDEX idx_export_runs_chat_id ON export_runs(chat_id)")
            exec("CREATE INDEX idx_export_runs_timestamp ON export_runs(export_timestamp)")
            exec("CREATE INDEX idx_export_runs_file_hash ON export_runs(file_hash)")
        }
    }
}
```

**Impact**: Enables retry logic for failed exports

**Rollback**: Removes retry columns (data in those columns lost)

---

### Version 3: Add Chat Type Enum (Planned)

**Description**: Add chat_type column to distinguish individuals, groups, broadcasts

**Planned Changes**:
- Add `chat_type VARCHAR(20) DEFAULT 'INDIVIDUAL'` to `chats`
- Possible values: `INDIVIDUAL`, `GROUP`, `BROADCAST`, `CHANNEL`

**Use Case**: Enable filtering by chat type (e.g., "export all groups")

---

### Version 4: Add Last Message Timestamp (Planned)

**Description**: Track last message timestamp for smarter incremental logic

**Planned Changes**:
- Add `last_message_at TIMESTAMP` to `chats`
- Add index on `last_message_at`

**Use Case**: Export chats only if new messages since last export

---

## Migration Execution

### Automatic Migrations (Recommended)

Migrations run automatically on application startup:

```kotlin
val config = Config.appConfig.database
val dbManager = DatabaseManager(config.path)
dbManager.initialize()  // Runs pending migrations
```

**Output**:
```
[INFO] Initializing database at: /home/user/.whatsliberation/registry.db
[INFO] Database connection established
[INFO] Running database migrations...
[INFO] Current database version: 0
[INFO] Found 1 pending migrations
[INFO] Applying migration 1: Initial schema: create chats, export_runs, contacts_cache tables
[INFO] Migration 1 applied successfully in 152ms
[INFO] All migrations applied successfully
[INFO] Database initialized successfully
```

### Manual Migration Execution

For testing or controlled environments:

```kotlin
val migrationRunner = MigrationRunner()

// Run all pending migrations
migrationRunner.runMigrations()

// Get current version
val currentVersion = MigrationHistory
    .select(MigrationHistory.version)
    .where { MigrationHistory.success eq true }
    .maxByOrNull { it[MigrationHistory.version] }
    ?.get(MigrationHistory.version) ?: 0

println("Current database version: $currentVersion")
```

### Check Migration Status

```sql
SELECT * FROM migration_history ORDER BY version;
```

**Output**:
```
id  version  description                              applied_at               checksum                                                          success
1   1        Initial schema: create chats, ...        2025-11-15T14:32:10Z     a1b2c3d4e5f6789012345678901234567890abcdef1234567890abcdef123456  1
```

## Rollback Procedures

### ⚠️ WARNING: Rollback Risks

**Rollbacks are destructive and may cause data loss!**

- Rolling back drops tables or columns → **data in those structures is lost**
- Only rollback in emergency scenarios or during development
- **ALWAYS create a backup before rollback**

### Rollback to Specific Version

```kotlin
val migrationRunner = MigrationRunner()

// Rollback to version 0 (empty database)
migrationRunner.rollback(targetVersion = 0)

// Rollback to version 1 (undo version 2 and 3 changes)
migrationRunner.rollback(targetVersion = 1)
```

**Output**:
```
[WARN] Rolling back database to version 1
[INFO] Rolling back migration 3: Add last message timestamp
[INFO] Migration 3 rolled back successfully
[INFO] Rolling back migration 2: Add retry tracking
[INFO] Migration 2 rolled back successfully
[INFO] Rollback to version 1 completed
```

### Rollback Verification

After rollback, verify schema:

```sql
-- Check current version
SELECT MAX(version) FROM migration_history WHERE success = 1;

-- List all tables
SELECT name FROM sqlite_master WHERE type = 'table';

-- Check table structure
PRAGMA table_info(chats);
PRAGMA table_info(export_runs);
```

### Emergency Rollback (Database Corruption)

If migrations fail catastrophically:

1. **Stop the application**
2. **Restore from backup**:
   ```bash
   cp ~/.whatsliberation/registry-backup-2025-11-14.db ~/.whatsliberation/registry.db
   ```
3. **Restart application** (will re-attempt migrations from backup version)

## Migration Best Practices

### 1. Test Migrations Thoroughly

**Test on Copy of Production Database**:

```bash
# Create test copy
cp ~/.whatsliberation/registry.db ~/.whatsliberation/registry-test.db

# Run migration on test DB
DATABASE_PATH=~/.whatsliberation/registry-test.db ./gradlew run

# Verify migration success
sqlite3 ~/.whatsliberation/registry-test.db "PRAGMA integrity_check; SELECT * FROM migration_history;"

# If successful, run on production
```

### 2. Always Provide Rollback (`down()` method)

```kotlin
override fun down() {
    // REQUIRED: Implement reverse of up() migration
    // Even if you think rollback will never be needed
}
```

### 3. Use Transactions

Exposed automatically wraps migrations in transactions, but for raw SQL:

```kotlin
override fun up() {
    transaction {
        exec("ALTER TABLE ...")
        exec("CREATE INDEX ...")
    }
}
```

### 4. Avoid Breaking Changes

**DON'T**:
- Drop columns with existing data (use nullable columns instead)
- Change column types incompatibly (INT → VARCHAR is safe, VARCHAR → INT is not)
- Remove required indexes (performance regression)

**DO**:
- Add new nullable columns
- Create new tables
- Add indexes (improves performance)
- Use `ALTER TABLE ... ADD COLUMN ... DEFAULT ...`

### 5. Document Data Migrations

If migration moves/transforms data:

```kotlin
override fun up() {
    transaction {
        // Add new column
        exec("ALTER TABLE chats ADD COLUMN chat_type VARCHAR(20) DEFAULT 'INDIVIDUAL'")

        // Migrate existing data
        exec("""
            UPDATE chats
            SET chat_type = 'GROUP'
            WHERE chat_name LIKE '%Group%' OR chat_name LIKE '%Family%'
        """)

        exec("""
            UPDATE chats
            SET chat_type = 'BROADCAST'
            WHERE chat_name LIKE '%Broadcast%'
        """)
    }
}
```

### 6. Version Sequentially

- Never skip version numbers (1, 2, 3, ... NOT 1, 3, 5)
- Never reuse version numbers
- Never modify already-applied migrations (create new migration instead)

## Migration Checklist

Before deploying a new migration to production:

- [ ] Migration code written with `up()` and `down()`
- [ ] Migration tested on copy of production database
- [ ] Database backup created
- [ ] Migration description is clear and accurate
- [ ] Rollback procedure tested
- [ ] Data migration logic verified (if applicable)
- [ ] Indexes added for new columns (if needed)
- [ ] Documentation updated
- [ ] Team notified of schema change

## Troubleshooting

### Migration Fails with "Table Already Exists"

**Cause**: Migration already partially applied

**Fix**:
```sql
-- Check migration_history
SELECT * FROM migration_history WHERE version = X;

-- If success = 0, migration failed midway
-- Manual cleanup required:
DROP TABLE IF EXISTS problematic_table;

-- Then retry migration
```

### Migration Fails with "Disk Full"

**Cause**: Insufficient disk space for schema changes

**Fix**:
1. Free up disk space
2. Rollback to previous version
3. Vacuum database to reclaim space: `VACUUM;`
4. Retry migration

### Checksum Mismatch

**Cause**: Migration code changed after being applied

**Fix**:
```
ERROR: Migration 2 checksum mismatch!
  Expected: a1b2c3...
  Actual:   x9y8z7...
```

**DO NOT** modify already-applied migrations. Create a new migration instead.

## Future Migration Plans

### Planned Migrations (2025 Roadmap)

| Version | Description | Priority | ETA |
|---------|-------------|----------|-----|
| 2 | Add retry tracking | High | Q1 2025 |
| 3 | Add chat type enum | Medium | Q2 2025 |
| 4 | Add last message timestamp | High | Q2 2025 |
| 5 | Add export quality metrics | Low | Q3 2025 |
| 6 | Add user preferences table | Low | Q4 2025 |

## References

- [Schema Documentation](SCHEMA.md) - Table structure details
- [Backup & Recovery](BACKUP_RECOVERY.md) - Creating backups before migrations
- Source Code: `src/main/kotlin/vision/salient/database/Migrations.kt`
