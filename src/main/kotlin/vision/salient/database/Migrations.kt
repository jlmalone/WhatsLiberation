package vision.salient.database

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.time.Instant

/**
 * Database migration runner.
 *
 * Handles:
 * - Schema versioning
 * - Migration execution
 * - Rollback support
 * - Migration history tracking
 */
class MigrationRunner {
    private val logger = LoggerFactory.getLogger(MigrationRunner::class.java)

    /**
     * Run all pending migrations.
     *
     * This method:
     * 1. Creates migration_history table if it doesn't exist
     * 2. Identifies pending migrations
     * 3. Executes them in order
     * 4. Records results in migration_history
     */
    fun runMigrations() {
        logger.info("Running database migrations...")

        // Ensure migration_history table exists
        SchemaUtils.create(MigrationHistory)

        // Get current version
        val currentVersion = getCurrentVersion()
        logger.info("Current database version: $currentVersion")

        // Get all migrations
        val migrations = getAllMigrations()

        // Run pending migrations
        val pendingMigrations = migrations.filter { it.version > currentVersion }

        if (pendingMigrations.isEmpty()) {
            logger.info("No pending migrations")
            return
        }

        logger.info("Found ${pendingMigrations.size} pending migrations")

        pendingMigrations.forEach { migration =>
            try {
                logger.info("Applying migration ${migration.version}: ${migration.description}")

                val startTime = System.currentTimeMillis()

                // Execute migration
                migration.up()

                val duration = System.currentTimeMillis() - startTime

                // Record in history
                MigrationHistory.insert {
                    it[version] = migration.version
                    it[description] = migration.description
                    it[appliedAt] = Instant.now()
                    it[checksum] = migration.checksum
                    it[success] = true
                }

                logger.info("Migration ${migration.version} applied successfully in ${duration}ms")

            } catch (e: Exception) {
                logger.error("Migration ${migration.version} failed", e)

                // Record failure
                MigrationHistory.insert {
                    it[version] = migration.version
                    it[description] = migration.description
                    it[appliedAt] = Instant.now()
                    it[checksum] = migration.checksum
                    it[success] = false
                }

                throw MigrationException("Migration ${migration.version} failed: ${e.message}", e)
            }
        }

        logger.info("All migrations applied successfully")
    }

    /**
     * Get current database version (highest applied migration).
     */
    private fun getCurrentVersion(): Int {
        return try {
            MigrationHistory
                .select(MigrationHistory.version)
                .where { MigrationHistory.success eq true }
                .maxByOrNull { it[MigrationHistory.version] }
                ?.get(MigrationHistory.version)
                ?: 0
        } catch (e: Exception) {
            // Table doesn't exist yet
            0
        }
    }

    /**
     * Get all defined migrations.
     */
    private fun getAllMigrations(): List<Migration> {
        return listOf(
            Migration001InitialSchema(),
            // Future migrations will be added here
            // Migration002AddIndexes(),
            // Migration003AddContactMetadata(),
        )
    }

    /**
     * Rollback to a specific version.
     *
     * WARNING: This is destructive and may lose data!
     *
     * @param targetVersion Version to rollback to
     */
    fun rollback(targetVersion: Int) {
        logger.warn("Rolling back database to version $targetVersion")

        val currentVersion = getCurrentVersion()

        if (targetVersion >= currentVersion) {
            logger.info("Already at or below target version")
            return
        }

        val migrations = getAllMigrations()
            .filter { it.version > targetVersion && it.version <= currentVersion }
            .sortedByDescending { it.version }

        migrations.forEach { migration =>
            try {
                logger.info("Rolling back migration ${migration.version}: ${migration.description}")

                migration.down()

                // Mark as rolled back
                MigrationHistory.update({ MigrationHistory.version eq migration.version }) {
                    it[success] = false
                }

                logger.info("Migration ${migration.version} rolled back successfully")

            } catch (e: Exception) {
                logger.error("Rollback of migration ${migration.version} failed", e)
                throw MigrationException("Rollback failed: ${e.message}", e)
            }
        }

        logger.info("Rollback to version $targetVersion completed")
    }
}

/**
 * Base class for migrations.
 */
abstract class Migration {
    abstract val version: Int
    abstract val description: String

    /**
     * Apply the migration (upgrade).
     */
    abstract fun up()

    /**
     * Revert the migration (downgrade).
     */
    abstract fun down()

    /**
     * Calculate checksum for migration integrity verification.
     */
    val checksum: String
        get() {
            val content = "$version:$description"
            val md = MessageDigest.getInstance("SHA-256")
            val digest = md.digest(content.toByteArray())
            return digest.joinToString("") { "%02x".format(it) }
        }
}

/**
 * Migration 001: Initial schema creation.
 *
 * Creates all base tables:
 * - chats
 * - export_runs
 * - contacts_cache
 * - migration_history
 */
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

/**
 * Exception thrown when migration fails.
 */
class MigrationException(message: String, cause: Throwable? = null) : Exception(message, cause)
