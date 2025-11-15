# Database Query Examples

**50+ Sample Queries for Common Operations**

This document provides comprehensive SQL query examples for interacting with the WhatsLiberation database. All queries are tested and production-ready.

## Table of Contents

1. [Chat Queries](#chat-queries) (Queries 1-15)
2. [Export Run Queries](#export-run-queries) (Queries 16-35)
3. [Contact Cache Queries](#contact-cache-queries) (Queries 36-45)
4. [Analytics & Statistics](#analytics--statistics) (Queries 46-58)
5. [Maintenance Queries](#maintenance-queries) (Queries 59-65)

---

## Chat Queries

### 1. Find Chat by Name

```sql
SELECT * FROM chats
WHERE chat_name = 'Chuck Malone';
```

**Use Case**: Look up a specific chat before export

---

### 2. Find Chat by Name and Occurrence Index

```sql
SELECT * FROM chats
WHERE chat_name = 'John' AND occurrence_index = 1;
```

**Use Case**: Handle duplicate chat names (second "John" chat)

---

### 3. Fuzzy Search Chats by Partial Name

```sql
SELECT * FROM chats
WHERE normalized_name LIKE '%chuck%'
ORDER BY chat_name;
```

**Use Case**: Find chats when exact name is unknown

---

### 4. Get All Chats for a Channel (WhatsApp Business)

```sql
SELECT * FROM chats
WHERE channel_prefix = 'HK'
ORDER BY chat_name;
```

**Use Case**: List all WhatsApp Business chats

---

### 5. Get Chats with Contact Information

```sql
SELECT * FROM chats
WHERE contact_id IS NOT NULL AND phone_number IS NOT NULL
ORDER BY chat_name;
```

**Use Case**: Find chats enriched with Google Contacts data

---

### 6. Get Chats Without Contact Information

```sql
SELECT * FROM chats
WHERE contact_id IS NULL OR phone_number IS NULL
ORDER BY chat_name;
```

**Use Case**: Identify chats needing contact lookup

---

### 7. Count Total Chats

```sql
SELECT COUNT(*) as total_chats FROM chats;
```

**Output**: `{"total_chats": 1532}`

---

### 8. Count Chats by Channel

```sql
SELECT
    COALESCE(channel_prefix, 'Personal') as channel,
    COUNT(*) as chat_count
FROM chats
GROUP BY channel_prefix
ORDER BY chat_count DESC;
```

**Output**:
```
Personal    1250
HK          282
```

---

### 9. Find Duplicate Chat Names

```sql
SELECT chat_name, COUNT(*) as occurrence_count
FROM chats
GROUP BY chat_name
HAVING COUNT(*) > 1
ORDER BY occurrence_count DESC;
```

**Use Case**: Identify chats with duplicate names for manual review

---

### 10. Get Recently Created Chats (Last 7 Days)

```sql
SELECT * FROM chats
WHERE created_at >= datetime('now', '-7 days')
ORDER BY created_at DESC;
```

**Use Case**: Track newly discovered chats

---

### 11. Get Chats Never Exported

```sql
SELECT c.* FROM chats c
LEFT JOIN export_runs e ON c.id = e.chat_id
WHERE e.id IS NULL
ORDER BY c.chat_name;
```

**Use Case**: Find chats that have never been backed up

---

### 12. Get Chats with Failed Last Export

```sql
SELECT c.*
FROM chats c
INNER JOIN export_runs e ON c.id = e.chat_id
WHERE e.export_timestamp = (
    SELECT MAX(export_timestamp)
    FROM export_runs
    WHERE chat_id = c.id
)
AND e.outcome = 'FAILED'
ORDER BY c.chat_name;
```

**Use Case**: Identify chats needing retry

---

### 13. Update Chat Contact Information

```sql
UPDATE chats
SET contact_id = 'c1234567890',
    phone_number = '+13239746605',
    updated_at = datetime('now')
WHERE id = 42;
```

**Use Case**: Enrich chat with Google Contacts data

---

### 14. Update Chat Content Hash

```sql
UPDATE chats
SET content_hash = 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855',
    updated_at = datetime('now')
WHERE id = 42;
```

**Use Case**: Store hash for deduplication after export

---

### 15. Delete Chat and All Export History

```sql
-- Delete export runs first (foreign key constraint)
DELETE FROM export_runs WHERE chat_id = 42;

-- Then delete chat
DELETE FROM chats WHERE id = 42;
```

**Use Case**: Remove chat from registry (rare, use with caution)

---

## Export Run Queries

### 16. Get All Exports for a Chat

```sql
SELECT * FROM export_runs
WHERE chat_id = 42
ORDER BY export_timestamp DESC;
```

**Use Case**: View complete export history for a chat

---

### 17. Get Last Successful Export for a Chat

```sql
SELECT * FROM export_runs
WHERE chat_id = 42 AND outcome = 'SUCCESS'
ORDER BY export_timestamp DESC
LIMIT 1;
```

**Use Case**: Check when chat was last successfully exported

---

### 18. Get Last Export (Any Outcome) for a Chat

```sql
SELECT * FROM export_runs
WHERE chat_id = 42
ORDER BY export_timestamp DESC
LIMIT 1;
```

**Use Case**: Get most recent export attempt regardless of outcome

---

### 19. Check if Chat Exported in Last 24 Hours

```sql
SELECT COUNT(*) > 0 as recently_exported
FROM export_runs
WHERE chat_id = 42
  AND outcome = 'SUCCESS'
  AND export_timestamp >= datetime('now', '-24 hours');
```

**Output**: `{"recently_exported": 1}` (true) or `{"recently_exported": 0}` (false)

---

### 20. Get Chats Needing Incremental Backup (Not Exported in Last 24 Hours)

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

**Use Case**: Incremental backup - identify chats to export

---

### 21. Get All Failed Exports

```sql
SELECT * FROM export_runs
WHERE outcome = 'FAILED'
ORDER BY export_timestamp DESC;
```

**Use Case**: Troubleshoot failed exports

---

### 22. Get Failed Exports with Error Messages

```sql
SELECT
    c.chat_name,
    e.export_timestamp,
    e.error_message,
    e.duration_ms
FROM export_runs e
INNER JOIN chats c ON e.chat_id = c.id
WHERE e.outcome = 'FAILED'
ORDER BY e.export_timestamp DESC;
```

**Use Case**: Analyze failure patterns

---

### 23. Get Exports by Date Range

```sql
SELECT * FROM export_runs
WHERE export_timestamp BETWEEN '2025-11-01' AND '2025-11-15'
ORDER BY export_timestamp;
```

**Use Case**: Audit exports for a specific period

---

### 24. Get Exports Including Media

```sql
SELECT * FROM export_runs
WHERE included_media = 1 AND outcome = 'SUCCESS'
ORDER BY export_timestamp DESC;
```

**Use Case**: Find exports with media attachments

---

### 25. Get Largest Exports by File Size

```sql
SELECT
    c.chat_name,
    e.file_size_bytes,
    e.file_size_bytes / 1024 / 1024 as file_size_mb,
    e.export_timestamp
FROM export_runs e
INNER JOIN chats c ON e.chat_id = c.id
WHERE e.outcome = 'SUCCESS'
ORDER BY e.file_size_bytes DESC
LIMIT 20;
```

**Use Case**: Identify largest exports for storage analysis

---

### 26. Get Slowest Exports by Duration

```sql
SELECT
    c.chat_name,
    e.duration_ms,
    e.duration_ms / 1000 as duration_seconds,
    e.export_timestamp
FROM export_runs e
INNER JOIN chats c ON e.chat_id = c.id
WHERE e.outcome = 'SUCCESS'
ORDER BY e.duration_ms DESC
LIMIT 20;
```

**Use Case**: Identify slow exports for optimization

---

### 27. Get Exports with Drive Integration

```sql
SELECT * FROM export_runs
WHERE drive_file_id IS NOT NULL
ORDER BY export_timestamp DESC;
```

**Use Case**: Find exports uploaded to Google Drive

---

### 28. Detect Duplicate Exports (Same File Hash)

```sql
SELECT
    file_hash,
    COUNT(*) as duplicate_count,
    GROUP_CONCAT(id) as export_ids
FROM export_runs
WHERE file_hash IS NOT NULL
GROUP BY file_hash
HAVING COUNT(*) > 1;
```

**Use Case**: Find duplicate exports for cleanup

---

### 29. Get Export Success Rate by Chat

```sql
SELECT
    c.chat_name,
    COUNT(*) as total_exports,
    SUM(CASE WHEN e.outcome = 'SUCCESS' THEN 1 ELSE 0 END) as successful,
    SUM(CASE WHEN e.outcome = 'FAILED' THEN 1 ELSE 0 END) as failed,
    ROUND(100.0 * SUM(CASE WHEN e.outcome = 'SUCCESS' THEN 1 ELSE 0 END) / COUNT(*), 2) as success_rate
FROM export_runs e
INNER JOIN chats c ON e.chat_id = c.id
GROUP BY c.id, c.chat_name
ORDER BY total_exports DESC;
```

**Use Case**: Identify problematic chats with low success rates

---

### 30. Get Exports by User

```sql
SELECT
    username,
    COUNT(*) as total_exports,
    SUM(CASE WHEN outcome = 'SUCCESS' THEN 1 ELSE 0 END) as successful
FROM export_runs
WHERE username IS NOT NULL
GROUP BY username
ORDER BY total_exports DESC;
```

**Use Case**: Track which users are running exports (multi-user environment)

---

### 31. Insert Successful Export Run

```sql
INSERT INTO export_runs (
    chat_id, outcome, local_file_path, file_size_bytes,
    file_hash, drive_file_id, included_media, duration_ms, username
) VALUES (
    42,
    'SUCCESS',
    '/home/user/Downloads/whatsliberation/chuck_malone/20251115/export.txt',
    15432768,
    'a3b2c1d4e5f6789012345678901234567890abcdef1234567890abcdef123456',
    'abc123xyz456',
    0,
    29150,
    'jlmalone'
);
```

---

### 32. Insert Failed Export Run

```sql
INSERT INTO export_runs (
    chat_id, outcome, error_message, duration_ms
) VALUES (
    42,
    'FAILED',
    'Chat not found on conversation list after flexible search',
    5200
);
```

---

### 33. Insert Skipped Export Run

```sql
INSERT INTO export_runs (
    chat_id, outcome, error_message
) VALUES (
    42,
    'SKIPPED',
    'Already exported successfully within last 24 hours'
);
```

---

### 34. Update Export with Drive File ID

```sql
UPDATE export_runs
SET drive_file_id = 'abc123xyz456'
WHERE id = 99;
```

**Use Case**: Add Drive file ID after asynchronous upload completes

---

### 35. Delete Old Export Runs (Retention Policy)

```sql
DELETE FROM export_runs
WHERE created_at < datetime('now', '-2 years');
```

**Use Case**: Apply 2-year retention policy to save space

---

## Contact Cache Queries

### 36. Find Cached Contact by Phone Number

```sql
SELECT * FROM contacts_cache
WHERE phone_number = '+13239746605';
```

**Use Case**: Check if contact is cached before API call

---

### 37. Check if Contact Cache is Stale

```sql
SELECT
    *,
    CASE
        WHEN fetched_at < datetime('now', '-7 days') THEN 1
        ELSE 0
    END as is_stale
FROM contacts_cache
WHERE phone_number = '+13239746605';
```

**Use Case**: Determine if cache needs refresh (7-day expiry)

---

### 38. Get All Fresh Contacts (Fetched in Last 7 Days)

```sql
SELECT * FROM contacts_cache
WHERE fetched_at >= datetime('now', '-7 days')
ORDER BY display_name;
```

**Use Case**: List valid cached contacts

---

### 39. Get All Stale Contacts (Older Than 7 Days)

```sql
SELECT * FROM contacts_cache
WHERE fetched_at < datetime('now', '-7 days')
ORDER BY fetched_at;
```

**Use Case**: Identify contacts needing refresh

---

### 40. Insert/Update Contact Cache (Upsert)

```sql
INSERT INTO contacts_cache (phone_number, contact_id, display_name, fetched_at)
VALUES ('+13239746605', 'c1234567890', 'Chuck Malone', datetime('now'))
ON CONFLICT(phone_number) DO UPDATE SET
    contact_id = excluded.contact_id,
    display_name = excluded.display_name,
    fetched_at = datetime('now'),
    updated_at = datetime('now');
```

**Use Case**: Cache new contact or update existing entry

---

### 41. Delete Stale Contact Cache (Older Than 90 Days)

```sql
DELETE FROM contacts_cache
WHERE fetched_at < datetime('now', '-90 days');
```

**Use Case**: Cleanup old cache entries

---

### 42. Count Cached Contacts

```sql
SELECT COUNT(*) as total_cached FROM contacts_cache;
```

**Output**: `{"total_cached": 1847}`

---

### 43. Get Cache Hit Rate

```sql
SELECT
    (SELECT COUNT(*) FROM contacts_cache WHERE fetched_at >= datetime('now', '-7 days')) as cache_hits,
    (SELECT COUNT(*) FROM chats WHERE phone_number IS NOT NULL) as total_chats_with_phone,
    ROUND(100.0 *
        (SELECT COUNT(*) FROM contacts_cache WHERE fetched_at >= datetime('now', '-7 days')) /
        (SELECT COUNT(*) FROM chats WHERE phone_number IS NOT NULL), 2
    ) as cache_hit_rate;
```

**Use Case**: Monitor contact cache effectiveness

---

### 44. Get Contacts Not in Chats Table

```sql
SELECT cc.* FROM contacts_cache cc
LEFT JOIN chats c ON cc.phone_number = c.phone_number
WHERE c.id IS NULL;
```

**Use Case**: Find orphaned cache entries

---

### 45. Get Chats with Cached Contacts

```sql
SELECT
    c.chat_name,
    c.phone_number,
    cc.contact_id,
    cc.display_name as cached_name,
    cc.fetched_at
FROM chats c
INNER JOIN contacts_cache cc ON c.phone_number = cc.phone_number
ORDER BY c.chat_name;
```

**Use Case**: Verify contact enrichment

---

## Analytics & Statistics

### 46. Get Total Export Runs by Outcome

```sql
SELECT
    outcome,
    COUNT(*) as count,
    ROUND(100.0 * COUNT(*) / (SELECT COUNT(*) FROM export_runs), 2) as percentage
FROM export_runs
GROUP BY outcome
ORDER BY count DESC;
```

**Output**:
```
SUCCESS    4523    87.21%
FAILED      612    11.80%
SKIPPED      51     0.99%
```

---

### 47. Get Average Export Duration

```sql
SELECT
    ROUND(AVG(duration_ms), 0) as avg_duration_ms,
    ROUND(AVG(duration_ms) / 1000, 2) as avg_duration_seconds,
    MIN(duration_ms) as min_duration_ms,
    MAX(duration_ms) as max_duration_ms
FROM export_runs
WHERE outcome = 'SUCCESS' AND duration_ms IS NOT NULL;
```

**Output**:
```
avg_duration_ms: 29150
avg_duration_seconds: 29.15
min_duration_ms: 12500
max_duration_ms: 87600
```

---

### 48. Get Total Exported File Size

```sql
SELECT
    SUM(file_size_bytes) as total_bytes,
    ROUND(SUM(file_size_bytes) / 1024.0 / 1024.0, 2) as total_mb,
    ROUND(SUM(file_size_bytes) / 1024.0 / 1024.0 / 1024.0, 2) as total_gb
FROM export_runs
WHERE outcome = 'SUCCESS' AND file_size_bytes IS NOT NULL;
```

**Output**: `{"total_bytes": 5892345672, "total_mb": 5621.43, "total_gb": 5.49}`

---

### 49. Get Export Activity by Month

```sql
SELECT
    strftime('%Y-%m', export_timestamp) as month,
    COUNT(*) as total_exports,
    SUM(CASE WHEN outcome = 'SUCCESS' THEN 1 ELSE 0 END) as successful,
    SUM(CASE WHEN outcome = 'FAILED' THEN 1 ELSE 0 END) as failed
FROM export_runs
GROUP BY strftime('%Y-%m', export_timestamp)
ORDER BY month DESC;
```

**Use Case**: Track export trends over time

---

### 50. Get Export Activity by Day of Week

```sql
SELECT
    CASE CAST(strftime('%w', export_timestamp) AS INTEGER)
        WHEN 0 THEN 'Sunday'
        WHEN 1 THEN 'Monday'
        WHEN 2 THEN 'Tuesday'
        WHEN 3 THEN 'Wednesday'
        WHEN 4 THEN 'Thursday'
        WHEN 5 THEN 'Friday'
        WHEN 6 THEN 'Saturday'
    END as day_of_week,
    COUNT(*) as export_count
FROM export_runs
GROUP BY strftime('%w', export_timestamp)
ORDER BY CAST(strftime('%w', export_timestamp) AS INTEGER);
```

**Use Case**: Identify peak export days

---

### 51. Get Top 10 Most Exported Chats

```sql
SELECT
    c.chat_name,
    COUNT(e.id) as export_count,
    SUM(CASE WHEN e.outcome = 'SUCCESS' THEN 1 ELSE 0 END) as successful,
    MAX(e.export_timestamp) as last_export
FROM chats c
INNER JOIN export_runs e ON c.id = e.chat_id
GROUP BY c.id, c.chat_name
ORDER BY export_count DESC
LIMIT 10;
```

**Use Case**: Identify frequently exported chats

---

### 52. Get Chat Export Frequency

```sql
SELECT
    c.chat_name,
    COUNT(e.id) as total_exports,
    MIN(e.export_timestamp) as first_export,
    MAX(e.export_timestamp) as last_export,
    ROUND(
        JULIANDAY(MAX(e.export_timestamp)) - JULIANDAY(MIN(e.export_timestamp)), 1
    ) as days_span,
    ROUND(
        COUNT(e.id) * 1.0 /
        NULLIF(JULIANDAY(MAX(e.export_timestamp)) - JULIANDAY(MIN(e.export_timestamp)), 0),
        2
    ) as exports_per_day
FROM chats c
INNER JOIN export_runs e ON c.id = e.chat_id
WHERE e.outcome = 'SUCCESS'
GROUP BY c.id, c.chat_name
HAVING COUNT(e.id) > 1
ORDER BY exports_per_day DESC
LIMIT 20;
```

**Use Case**: Analyze export patterns per chat

---

### 53. Get Database Statistics

```sql
SELECT
    (SELECT COUNT(*) FROM chats) as total_chats,
    (SELECT COUNT(*) FROM export_runs) as total_exports,
    (SELECT COUNT(*) FROM export_runs WHERE outcome = 'SUCCESS') as successful_exports,
    (SELECT COUNT(*) FROM export_runs WHERE outcome = 'FAILED') as failed_exports,
    (SELECT COUNT(*) FROM contacts_cache) as cached_contacts,
    (SELECT COUNT(*) FROM migration_history WHERE success = 1) as applied_migrations;
```

**Use Case**: Dashboard summary statistics

---

### 54. Get Exports Per Hour (24-Hour Breakdown)

```sql
SELECT
    strftime('%H', export_timestamp) as hour,
    COUNT(*) as export_count
FROM export_runs
WHERE export_timestamp >= datetime('now', '-7 days')
GROUP BY strftime('%H', export_timestamp)
ORDER BY hour;
```

**Use Case**: Identify peak export hours

---

### 55. Get Average File Size by Media Inclusion

```sql
SELECT
    included_media,
    COUNT(*) as export_count,
    ROUND(AVG(file_size_bytes) / 1024.0 / 1024.0, 2) as avg_size_mb
FROM export_runs
WHERE outcome = 'SUCCESS' AND file_size_bytes IS NOT NULL
GROUP BY included_media;
```

**Output**:
```
included_media=0:  export_count=4200, avg_size_mb=2.34
included_media=1:  export_count=323,  avg_size_mb=47.82
```

---

### 56. Get Chats with Most Failures

```sql
SELECT
    c.chat_name,
    COUNT(*) as failure_count,
    MAX(e.export_timestamp) as last_failure,
    MAX(e.error_message) as last_error
FROM export_runs e
INNER JOIN chats c ON e.chat_id = c.id
WHERE e.outcome = 'FAILED'
GROUP BY c.id, c.chat_name
ORDER BY failure_count DESC
LIMIT 20;
```

**Use Case**: Prioritize problematic chats for debugging

---

### 57. Get Export Success Rate Over Time

```sql
SELECT
    DATE(export_timestamp) as export_date,
    COUNT(*) as total_exports,
    SUM(CASE WHEN outcome = 'SUCCESS' THEN 1 ELSE 0 END) as successful,
    ROUND(100.0 * SUM(CASE WHEN outcome = 'SUCCESS' THEN 1 ELSE 0 END) / COUNT(*), 2) as success_rate
FROM export_runs
WHERE export_timestamp >= datetime('now', '-30 days')
GROUP BY DATE(export_timestamp)
ORDER BY export_date;
```

**Use Case**: Track success rate trends

---

### 58. Get Most Recent Exports Per Chat

```sql
SELECT
    c.chat_name,
    MAX(e.export_timestamp) as last_export,
    ROUND((JULIANDAY('now') - JULIANDAY(MAX(e.export_timestamp))), 1) as days_ago,
    e2.outcome as last_outcome
FROM chats c
LEFT JOIN export_runs e ON c.id = e.chat_id
LEFT JOIN export_runs e2 ON c.id = e2.chat_id AND e.export_timestamp = e2.export_timestamp
GROUP BY c.id, c.chat_name
ORDER BY last_export DESC NULLS LAST
LIMIT 50;
```

**Use Case**: See last export timestamp for all chats

---

## Maintenance Queries

### 59. Vacuum Database (Reclaim Space)

```sql
VACUUM;
```

**Use Case**: Optimize database after large deletions, reclaim disk space

---

### 60. Analyze Database (Update Statistics)

```sql
ANALYZE;
```

**Use Case**: Update query planner statistics for better performance

---

### 61. Get Database Page Count and Size

```sql
SELECT
    page_count,
    page_size,
    page_count * page_size as total_bytes,
    ROUND(page_count * page_size / 1024.0 / 1024.0, 2) as total_mb
FROM pragma_page_count(), pragma_page_size();
```

**Use Case**: Monitor database file size

---

### 62. Get Table Sizes

```sql
SELECT
    name as table_name,
    (SELECT COUNT(*) FROM pragma_table_info(name)) as column_count,
    (SELECT COUNT(*) FROM main.sqlite_master WHERE tbl_name = name AND type = 'index') as index_count
FROM sqlite_master
WHERE type = 'table' AND name NOT LIKE 'sqlite_%';
```

**Use Case**: Analyze table structure

---

### 63. Get Index Usage Statistics

```sql
SELECT
    m.name as index_name,
    m.tbl_name as table_name,
    s.stat as index_stats
FROM sqlite_master m
LEFT JOIN sqlite_stat1 s ON m.name = s.idx
WHERE m.type = 'index'
ORDER BY m.tbl_name, m.name;
```

**Use Case**: Monitor index effectiveness

---

### 64. Check Foreign Key Integrity

```sql
PRAGMA foreign_keys = ON;
PRAGMA foreign_key_check;
```

**Use Case**: Verify referential integrity

---

### 65. Get Database Integrity Check

```sql
PRAGMA integrity_check;
```

**Expected Output**: `"ok"`

**Use Case**: Verify database file is not corrupted

---

## Kotlin Repository Method Equivalents

For reference, here are Kotlin repository methods that execute these queries:

```kotlin
// Query #1-2: Find chat
chatRepository.findOrCreate("Chuck Malone", occurrenceIndex = 0)

// Query #11: Get chats never exported
chatRepository.getChatsNeedingBackup(olderThanHours = Int.MAX_VALUE)

// Query #17: Get last successful export
exportRepository.getLastSuccessfulExport(chatId = 42)

// Query #19: Check recent export
exportRepository.hasRecentSuccessfulExport(chatId = 42, withinHours = 24)

// Query #21-22: Get failed exports
exportRepository.findByOutcome(ExportOutcome.FAILED)

// Query #36-37: Contact cache lookup
contactsRepository.findByPhoneNumber("+13239746605")
contactsRepository.isStale(phoneNumber, maxAgeHours = 168)

// Query #46-48, 53: Statistics
exportRepository.getStatistics()
databaseManager.getStatistics()
```

## Performance Tips

1. **Use Indexes**: All provided queries leverage existing indexes for optimal performance
2. **Limit Results**: Add `LIMIT` clause for large result sets
3. **Use Prepared Statements**: In application code, always use parameterized queries
4. **Batch Operations**: Use transactions for multiple INSERT/UPDATE operations
5. **Regular Maintenance**: Run `VACUUM` and `ANALYZE` monthly

## Next Steps

- See [PERFORMANCE.md](PERFORMANCE.md) for query optimization techniques
- See [INTEGRATION.md](INTEGRATION.md) for using these queries in application code
- See [SCHEMA.md](SCHEMA.md) for table structure details
