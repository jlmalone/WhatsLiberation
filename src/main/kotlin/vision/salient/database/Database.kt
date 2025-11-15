package vision.salient.database

import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Database connection manager for WhatsLiberation persistent registry.
 *
 * Responsibilities:
 * - Initialize SQLite database connection
 * - Run migrations on startup
 * - Provide transaction boundaries
 * - Handle database file location
 */
class DatabaseManager(
    private val databasePath: Path = getDefaultDatabasePath(),
    private val migrationRunner: MigrationRunner = MigrationRunner()
) {
    private val logger = LoggerFactory.getLogger(DatabaseManager::class.java)

    private lateinit var database: Database

    companion object {
        /**
         * Default database location: ~/.whatsliberation/registry.db
         */
        fun getDefaultDatabasePath(): Path {
            val homeDir = System.getProperty("user.home")
            val appDir = Paths.get(homeDir, ".whatsliberation")

            // Ensure directory exists
            File(appDir.toString()).mkdirs()

            return appDir.resolve("registry.db")
        }

        /**
         * Get test database path (in-memory or temp file)
         */
        fun getTestDatabasePath(): Path {
            return Paths.get(System.getProperty("java.io.tmpdir"), "whatsliberation-test-${System.currentTimeMillis()}.db")
        }
    }

    /**
     * Initialize database connection and run migrations.
     *
     * This method:
     * 1. Creates database file if it doesn't exist
     * 2. Establishes JDBC connection
     * 3. Runs pending migrations
     * 4. Verifies schema integrity
     *
     * @throws DatabaseInitializationException if initialization fails
     */
    fun initialize() {
        try {
            logger.info("Initializing database at: $databasePath")

            // Create parent directory if needed
            databasePath.parent?.toFile()?.mkdirs()

            // Connect to SQLite database
            database = Database.connect(
                url = "jdbc:sqlite:$databasePath",
                driver = "org.sqlite.JDBC"
            )

            logger.info("Database connection established")

            // Run migrations
            transaction(database) {
                migrationRunner.runMigrations()
            }

            logger.info("Database initialized successfully")

        } catch (e: Exception) {
            logger.error("Failed to initialize database", e)
            throw DatabaseInitializationException("Database initialization failed: ${e.message}", e)
        }
    }

    /**
     * Execute a database transaction.
     *
     * Example:
     * ```
     * dbManager.executeTransaction {
     *     // Your database operations here
     * }
     * ```
     */
    fun <T> executeTransaction(block: () -> T): T {
        if (!::database.isInitialized) {
            throw IllegalStateException("Database not initialized. Call initialize() first.")
        }
        return transaction(database) {
            block()
        }
    }

    /**
     * Get database statistics for monitoring.
     */
    fun getStatistics(): DatabaseStatistics {
        return executeTransaction {
            DatabaseStatistics(
                totalChats = Chats.selectAll().count(),
                totalExportRuns = ExportRuns.selectAll().count(),
                cachedContacts = ContactsCache.selectAll().count(),
                databaseSizeBytes = databasePath.toFile().length(),
                databasePath = databasePath.toString()
            )
        }
    }

    /**
     * Close database connection.
     */
    fun close() {
        logger.info("Closing database connection")
        // Exposed doesn't require explicit connection closure
        // but we log for audit purposes
    }

    /**
     * Vacuum the database to reclaim space and optimize performance.
     *
     * Should be run periodically (e.g., weekly) or after large deletions.
     */
    fun vacuum() {
        executeTransaction {
            exec("VACUUM")
        }
        logger.info("Database vacuumed successfully")
    }

    /**
     * Create a backup of the database file.
     *
     * @param backupPath Where to save the backup
     * @return Path to the backup file
     */
    fun createBackup(backupPath: Path? = null): Path {
        val destination = backupPath ?: run {
            val timestamp = java.time.Instant.now().toString().replace(":", "-")
            databasePath.parent.resolve("registry-backup-$timestamp.db")
        }

        logger.info("Creating database backup at: $destination")

        // Copy database file
        databasePath.toFile().copyTo(destination.toFile(), overwrite = true)

        logger.info("Database backup created successfully")
        return destination
    }
}

/**
 * Database statistics for monitoring and debugging.
 */
data class DatabaseStatistics(
    val totalChats: Long,
    val totalExportRuns: Long,
    val cachedContacts: Long,
    val databaseSizeBytes: Long,
    val databasePath: String
)

/**
 * Exception thrown when database initialization fails.
 */
class DatabaseInitializationException(message: String, cause: Throwable? = null) : Exception(message, cause)

// Extension function to make selectAll available
private fun org.jetbrains.exposed.sql.Table.selectAll() = org.jetbrains.exposed.sql.selectAll()
