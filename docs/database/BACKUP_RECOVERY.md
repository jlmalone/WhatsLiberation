# Backup and Recovery Procedures

**Database Backup Strategies, Restoration, and Disaster Recovery**

## Overview

This guide covers comprehensive backup and recovery procedures for the WhatsLiberation database, including automated backups, manual procedures, disaster recovery, and data integrity verification.

## Backup Strategies

### 1. Automated Backups (Recommended)

#### Programmatic Backup via DatabaseService

```kotlin
val dbService = DatabaseService(Config.appConfig.database)
dbService.initialize()

// Create backup with timestamp
val backupPath = dbService.createBackup()
println("Backup created: $backupPath")
// Output: Backup created: /home/user/.whatsliberation/registry-backup-2025-11-15T14:32:10Z.db
```

#### Custom Backup Location

```kotlin
val customPath = Paths.get("/backups/whatsliberation/registry-20251115.db")
val backupPath = dbService.createBackup(customPath)
```

#### Automated Daily Backups (Cron Job)

Create backup script `/usr/local/bin/backup-whatsliberation.sh`:

```bash
#!/bin/bash

# WhatsLiberation Database Backup Script
# Usage: Add to cron for daily execution

BACKUP_DIR="/backups/whatsliberation"
DB_PATH="$HOME/.whatsliberation/registry.db"
TIMESTAMP=$(date +"%Y%m%d_%H%M%S")
BACKUP_FILE="$BACKUP_DIR/registry_backup_$TIMESTAMP.db"
RETENTION_DAYS=30

# Create backup directory
mkdir -p "$BACKUP_DIR"

# Copy database file
cp "$DB_PATH" "$BACKUP_FILE"

# Verify backup integrity
sqlite3 "$BACKUP_FILE" "PRAGMA integrity_check;" | grep -q "ok"
if [ $? -eq 0 ]; then
    echo "[$(date)] Backup successful: $BACKUP_FILE" >> "$BACKUP_DIR/backup.log"

    # Compress backup
    gzip "$BACKUP_FILE"

    # Delete backups older than retention period
    find "$BACKUP_DIR" -name "registry_backup_*.db.gz" -mtime +$RETENTION_DAYS -delete

    echo "[$(date)] Old backups cleaned (retention: $RETENTION_DAYS days)" >> "$BACKUP_DIR/backup.log"
else
    echo "[$(date)] ERROR: Backup integrity check failed!" >> "$BACKUP_DIR/backup.log"
    exit 1
fi
```

**Make script executable**:
```bash
chmod +x /usr/local/bin/backup-whatsliberation.sh
```

**Add to crontab** (daily at 2 AM):
```bash
crontab -e

# Add this line:
0 2 * * * /usr/local/bin/backup-whatsliberation.sh
```

---

### 2. Manual Backups

#### Simple File Copy

**While application is stopped**:
```bash
cp ~/.whatsliberation/registry.db ~/.whatsliberation/registry-backup-$(date +%Y%m%d).db
```

**While application is running** (requires WAL checkpoint):
```bash
# Checkpoint WAL to ensure consistency
sqlite3 ~/.whatsliberation/registry.db "PRAGMA wal_checkpoint(FULL);"

# Copy database
cp ~/.whatsliberation/registry.db ~/.whatsliberation/registry-backup-$(date +%Y%m%d).db

# Also copy WAL and SHM files if using WAL mode
cp ~/.whatsliberation/registry.db-wal ~/.whatsliberation/registry-backup-$(date +%Y%m%d).db-wal
cp ~/.whatsliberation/registry.db-shm ~/.whatsliberation/registry-backup-$(date +%Y%m%d).db-shm
```

#### SQLite Backup API (Hot Backup)

```bash
sqlite3 ~/.whatsliberation/registry.db ".backup '/backups/registry-20251115.db'"
```

**Advantages**:
- Works while database is active
- Handles WAL mode correctly
- Ensures consistent snapshot

#### Export to SQL

```bash
sqlite3 ~/.whatsliberation/registry.db .dump > /backups/registry-20251115.sql
```

**Advantages**:
- Human-readable
- Portable across SQLite versions
- Can selectively restore tables

**Disadvantages**:
- Larger file size
- Slower to restore
- Loses binary data integrity

---

### 3. Cloud Backups

#### Google Drive Sync

```bash
# Install rclone
curl https://rclone.org/install.sh | sudo bash

# Configure Google Drive
rclone config

# Sync backups to Google Drive
rclone copy ~/.whatsliberation/ gdrive:WhatsLiberation/backups/ --include "registry*.db"
```

#### AWS S3 Backup

```bash
# Install AWS CLI
pip install awscli

# Configure credentials
aws configure

# Upload backup
aws s3 cp ~/.whatsliberation/registry-backup-20251115.db \
    s3://my-bucket/whatsliberation/backups/registry-20251115.db \
    --storage-class STANDARD_IA
```

#### Dropbox Backup

```bash
# Install Dropbox CLI
cd ~ && wget -O - "https://www.dropbox.com/download?plat=lnx.x86_64" | tar xzf -

# Copy to Dropbox folder
cp ~/.whatsliberation/registry.db ~/Dropbox/WhatsLiberation/registry-backup-$(date +%Y%m%d).db
```

---

## Restoration Procedures

### 1. Simple Restoration

**Stop application**, then restore from backup:

```bash
# Backup current database (just in case)
mv ~/.whatsliberation/registry.db ~/.whatsliberation/registry-corrupted.db

# Restore from backup
cp ~/.whatsliberation/registry-backup-20251115.db ~/.whatsliberation/registry.db

# Restart application
```

### 2. Restore Specific Tables

Extract specific tables from backup:

```bash
# Dump only chats table from backup
sqlite3 ~/.whatsliberation/registry-backup-20251115.db <<EOF
.mode insert chats
SELECT * FROM chats;
EOF > /tmp/chats_backup.sql

# Import into current database
sqlite3 ~/.whatsliberation/registry.db < /tmp/chats_backup.sql
```

### 3. Restore from SQL Dump

```bash
# Create fresh database from SQL dump
sqlite3 ~/.whatsliberation/registry-restored.db < /backups/registry-20251115.sql

# Verify integrity
sqlite3 ~/.whatsliberation/registry-restored.db "PRAGMA integrity_check;"

# If OK, replace current database
mv ~/.whatsliberation/registry.db ~/.whatsliberation/registry-old.db
mv ~/.whatsliberation/registry-restored.db ~/.whatsliberation/registry.db
```

### 4. Partial Data Recovery

**Scenario**: Export runs table corrupted, but chats table is OK

```bash
# Dump good tables from current database
sqlite3 ~/.whatsliberation/registry.db <<EOF
.mode insert
.output /tmp/chats.sql
SELECT * FROM chats;
.output /tmp/contacts_cache.sql
SELECT * FROM contacts_cache;
EOF

# Create new database from schema
# (Application will auto-create schema on startup)

# Import good data
sqlite3 ~/.whatsliberation/registry.db < /tmp/chats.sql
sqlite3 ~/.whatsliberation/registry.db < /tmp/contacts_cache.sql
```

---

## Disaster Recovery

### Scenario 1: Database Corruption

**Symptoms**:
- `PRAGMA integrity_check` returns errors
- Application crashes on startup
- SQLite errors: "database disk image is malformed"

**Recovery Steps**:

1. **Attempt automatic recovery**:
   ```bash
   sqlite3 ~/.whatsliberation/registry.db ".recover" | sqlite3 ~/.whatsliberation/registry-recovered.db
   ```

2. **If recovery fails, restore from backup**:
   ```bash
   cp ~/.whatsliberation/registry-backup-LATEST.db ~/.whatsliberation/registry.db
   ```

3. **Verify integrity**:
   ```sql
   PRAGMA integrity_check;
   PRAGMA foreign_key_check;
   ```

4. **Restart application**

---

### Scenario 2: Accidental Data Deletion

**Symptoms**:
- User accidentally deleted chats or export history
- `DELETE FROM chats WHERE ...` ran with wrong WHERE clause

**Recovery Steps**:

1. **Immediately stop application** to prevent further changes

2. **Restore from most recent backup**:
   ```bash
   cp ~/.whatsliberation/registry-backup-LATEST.db ~/.whatsliberation/registry.db
   ```

3. **If partial recovery needed**:
   ```bash
   # Extract deleted data from backup
   sqlite3 ~/.whatsliberation/registry-backup-LATEST.db <<EOF
   .mode insert chats
   SELECT * FROM chats WHERE id IN (1, 2, 3);  -- IDs of deleted chats
   EOF > /tmp/deleted_chats.sql

   # Insert back into current database
   sqlite3 ~/.whatsliberation/registry.db < /tmp/deleted_chats.sql
   ```

---

### Scenario 3: Disk Full During Write

**Symptoms**:
- Application crashes during export
- SQLite error: "disk I/O error"
- Database may be inconsistent

**Recovery Steps**:

1. **Free up disk space immediately**

2. **Check database integrity**:
   ```bash
   sqlite3 ~/.whatsliberation/registry.db "PRAGMA integrity_check;"
   ```

3. **If corrupted, rollback to last good state**:
   ```bash
   # SQLite's WAL mode auto-recovers from incomplete writes
   # If using journal mode, manual recovery needed:
   mv ~/.whatsliberation/registry.db ~/.whatsliberation/registry-corrupted.db
   cp ~/.whatsliberation/registry-backup-LATEST.db ~/.whatsliberation/registry.db
   ```

4. **Re-run failed exports**

---

### Scenario 4: Migration Failure

**Symptoms**:
- Migration fails halfway through
- Database in inconsistent state
- Application won't start

**Recovery Steps**:

1. **Check migration history**:
   ```sql
   SELECT * FROM migration_history ORDER BY version DESC LIMIT 5;
   ```

2. **If last migration failed** (`success = 0`):
   ```bash
   # Restore from pre-migration backup
   cp ~/.whatsliberation/registry-pre-migration-v2.db ~/.whatsliberation/registry.db
   ```

3. **If no pre-migration backup exists**:
   ```bash
   # Manual rollback (dangerous, use with caution)
   sqlite3 ~/.whatsliberation/registry.db <<EOF
   -- Reverse migration changes manually
   -- Example for failed Migration002:
   DROP TABLE IF EXISTS new_table;
   ALTER TABLE chats DROP COLUMN new_column;  -- If supported
   EOF
   ```

4. **Fix migration code and retry**

---

## Data Integrity Verification

### 1. Integrity Checks

```sql
-- Full integrity check (slow for large DBs)
PRAGMA integrity_check;

-- Quick check (faster, less thorough)
PRAGMA quick_check;

-- Foreign key constraint check
PRAGMA foreign_key_check;
```

**Expected output**: `"ok"` for all checks

### 2. Verify Backup Before Disaster

```bash
# Create verification script
sqlite3 ~/.whatsliberation/registry-backup-20251115.db <<EOF
PRAGMA integrity_check;
SELECT COUNT(*) FROM chats;
SELECT COUNT(*) FROM export_runs;
SELECT COUNT(*) FROM contacts_cache;
EOF
```

**Expected output**:
```
ok
2543
51234
1847
```

### 3. Checksum Verification

```bash
# Create checksum of database
sha256sum ~/.whatsliberation/registry.db > ~/.whatsliberation/registry.db.sha256

# Verify later
sha256sum -c ~/.whatsliberation/registry.db.sha256
```

**Output**: `registry.db: OK`

---

## Backup Best Practices

### ✅ DO

1. **Backup before major operations**:
   - Before running migrations
   - Before bulk deletions
   - Before schema changes
   - Before upgrading application

2. **Automate daily backups** with cron

3. **Test restore procedures** monthly

4. **Store backups in multiple locations**:
   - Local disk
   - Cloud storage (S3, Drive, Dropbox)
   - External hard drive

5. **Verify backup integrity** after creation

6. **Retain backups** using a rotation policy:
   - Daily backups: Keep 7 days
   - Weekly backups: Keep 4 weeks
   - Monthly backups: Keep 12 months
   - Yearly backups: Keep indefinitely

7. **Document recovery procedures** and test them

8. **Monitor backup success/failure** with logging and alerts

### ❌ DON'T

1. **Don't backup while application is writing** (unless using `.backup` API or WAL mode)

2. **Don't assume backups are valid** - Always verify integrity

3. **Don't store backups only locally** - Use cloud storage for disaster recovery

4. **Don't forget WAL/SHM files** if using WAL mode

5. **Don't delete old backups** without verifying new ones first

6. **Don't backup to same disk** as primary database (disk failure = data loss)

---

## Backup Retention Policy

### Recommended Rotation Schedule

```
Daily Backups:
  - Retention: 7 days
  - Frequency: Every day at 2 AM
  - Location: /backups/whatsliberation/daily/
  - Naming: registry_daily_YYYYMMDD.db.gz

Weekly Backups:
  - Retention: 4 weeks (28 days)
  - Frequency: Every Sunday at 3 AM
  - Location: /backups/whatsliberation/weekly/
  - Naming: registry_weekly_YYYYMMDD.db.gz

Monthly Backups:
  - Retention: 12 months (1 year)
  - Frequency: First day of month at 4 AM
  - Location: /backups/whatsliberation/monthly/
  - Naming: registry_monthly_YYYYMM.db.gz

Yearly Backups:
  - Retention: Indefinite
  - Frequency: January 1st at 5 AM
  - Location: /backups/whatsliberation/yearly/
  - Naming: registry_yearly_YYYY.db.gz
```

### Automated Rotation Script

```bash
#!/bin/bash

# Backup Rotation Script
BACKUP_BASE="/backups/whatsliberation"
DB_PATH="$HOME/.whatsliberation/registry.db"

# Daily backup
DAILY_DIR="$BACKUP_BASE/daily"
mkdir -p "$DAILY_DIR"
cp "$DB_PATH" "$DAILY_DIR/registry_daily_$(date +%Y%m%d).db"
gzip "$DAILY_DIR/registry_daily_$(date +%Y%m%d).db"
find "$DAILY_DIR" -mtime +7 -delete

# Weekly backup (Sundays)
if [ $(date +%u) -eq 7 ]; then
    WEEKLY_DIR="$BACKUP_BASE/weekly"
    mkdir -p "$WEEKLY_DIR"
    cp "$DB_PATH" "$WEEKLY_DIR/registry_weekly_$(date +%Y%m%d).db"
    gzip "$WEEKLY_DIR/registry_weekly_$(date +%Y%m%d).db"
    find "$WEEKLY_DIR" -mtime +28 -delete
fi

# Monthly backup (1st of month)
if [ $(date +%d) -eq 01 ]; then
    MONTHLY_DIR="$BACKUP_BASE/monthly"
    mkdir -p "$MONTHLY_DIR"
    cp "$DB_PATH" "$MONTHLY_DIR/registry_monthly_$(date +%Y%m).db"
    gzip "$MONTHLY_DIR/registry_monthly_$(date +%Y%m).db"
    find "$MONTHLY_DIR" -mtime +365 -delete
fi

# Yearly backup (January 1st)
if [ "$(date +%m%d)" = "0101" ]; then
    YEARLY_DIR="$BACKUP_BASE/yearly"
    mkdir -p "$YEARLY_DIR"
    cp "$DB_PATH" "$YEARLY_DIR/registry_yearly_$(date +%Y).db"
    gzip "$YEARLY_DIR/registry_yearly_$(date +%Y).db"
fi
```

---

## Monitoring and Alerts

### Backup Success Monitoring

```bash
#!/bin/bash

# Check if today's backup exists
BACKUP_FILE="/backups/whatsliberation/daily/registry_daily_$(date +%Y%m%d).db.gz"

if [ ! -f "$BACKUP_FILE" ]; then
    echo "ERROR: Today's backup missing!" | mail -s "WhatsLiberation Backup Failed" admin@example.com
    exit 1
fi

# Verify backup is recent (< 24 hours old)
if [ $(find "$BACKUP_FILE" -mtime +1 | wc -l) -gt 0 ]; then
    echo "ERROR: Backup is older than 24 hours!" | mail -s "WhatsLiberation Backup Stale" admin@example.com
    exit 1
fi

echo "Backup OK: $BACKUP_FILE"
```

### Disk Space Monitoring

```bash
#!/bin/bash

# Alert if backup disk < 10% free
BACKUP_DISK="/backups"
USAGE=$(df "$BACKUP_DISK" | tail -1 | awk '{print $5}' | sed 's/%//')

if [ "$USAGE" -gt 90 ]; then
    echo "WARNING: Backup disk is $USAGE% full!" | mail -s "WhatsLiberation Backup Disk Low" admin@example.com
fi
```

---

## Recovery Testing

### Monthly Recovery Drill

Test restoration procedure monthly:

```bash
#!/bin/bash

# Recovery Test Script
TEST_DIR="/tmp/whatsliberation-recovery-test"
BACKUP_FILE="/backups/whatsliberation/daily/registry_daily_$(date +%Y%m%d).db.gz"

# Create test directory
mkdir -p "$TEST_DIR"
cd "$TEST_DIR"

# Extract backup
gunzip -c "$BACKUP_FILE" > registry-test.db

# Verify integrity
sqlite3 registry-test.db "PRAGMA integrity_check;" | grep -q "ok"
if [ $? -ne 0 ]; then
    echo "FAILED: Backup integrity check failed!" | mail -s "WhatsLiberation Recovery Test Failed" admin@example.com
    exit 1
fi

# Verify data
CHAT_COUNT=$(sqlite3 registry-test.db "SELECT COUNT(*) FROM chats;")
EXPORT_COUNT=$(sqlite3 registry-test.db "SELECT COUNT(*) FROM export_runs;")

if [ "$CHAT_COUNT" -lt 1 ] || [ "$EXPORT_COUNT" -lt 1 ]; then
    echo "FAILED: Backup contains no data!" | mail -s "WhatsLiberation Recovery Test Failed" admin@example.com
    exit 1
fi

echo "SUCCESS: Recovery test passed. Chats: $CHAT_COUNT, Exports: $EXPORT_COUNT" | mail -s "WhatsLiberation Recovery Test OK" admin@example.com

# Cleanup
rm -rf "$TEST_DIR"
```

---

## References

- [SQLite Backup API](https://www.sqlite.org/backup.html)
- [SQLite WAL Mode](https://www.sqlite.org/wal.html)
- [Schema Documentation](SCHEMA.md)
- [Performance Guide](PERFORMANCE.md)
- Source Code: `src/main/kotlin/vision/salient/database/DatabaseService.kt`
