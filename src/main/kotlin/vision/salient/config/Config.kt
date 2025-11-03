package vision.salient.config

import io.github.cdimascio.dotenv.Dotenv
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Duration

/**
 * Represents the application configuration resolved from environment variables or defaults.
 */
data class AppConfig(
    val deviceId: String?,
    val username: String?,
    val basePath: Path,
    val deviceSnapshotDir: String,
    val localSnapshotDir: Path,
    val adbPath: Path,
    val drive: DriveConfig,
    val contacts: ContactsConfig,
)

data class DriveConfig(
    val credentialsPath: Path?,
    val folderName: String?,
    val folderId: String?,
    val pollTimeout: Duration,
    val pollInterval: Duration,
)

data class ContactsConfig(
    val clientSecretPath: Path?,
    val tokenPath: Path?,
)

/**
 * Validation result for configuration state. Errors should fail fast; warnings are informative.
 */
data class ConfigValidationResult(
    val errors: List<String> = emptyList(),
    val warnings: List<String> = emptyList()
) {
    val isValid: Boolean get() = errors.isEmpty()
}

/** Simple abstraction around configuration sources to facilitate testing. */
fun interface EnvSource {
    operator fun get(key: String): String?
}

class DotenvEnvSource(delegate: Dotenv? = null) : EnvSource {
    private val dotenv: Dotenv? = delegate ?: runCatching {
        Dotenv.configure()
            .ignoreIfMissing()
            .ignoreIfMalformed()
            .load()
    }.getOrNull()

    override fun get(key: String): String? = dotenv?.get(key) ?: System.getenv(key)
}

class MapEnvSource(private val values: Map<String, String?>) : EnvSource {
    override fun get(key: String): String? = values[key]
}

class ConfigLoader(private val env: EnvSource = DotenvEnvSource()) {

    fun load(): AppConfig {
        val username = envValue("USERNAME")
        val basePath = resolveBasePath(envValue("BASE_PATH"), username)
        val deviceSnapshotDir = envValue("DEVICE_SNAPSHOT_DIR") ?: "/sdcard/whats"
        val localSnapshotDir = resolvePath(envValue("LOCAL_SNAPSHOT_DIR"), basePath)
            ?: basePath.resolve("Downloads/whatsliberation").normalize()
        val adbPath = resolvePath(envValue("ADB_PATH"), basePath)
            ?: basePath.resolve("Library/Android/sdk/platform-tools/adb").normalize()

        val driveCredentials = resolvePath(envValue("GOOGLE_DRIVE_CREDENTIALS_PATH"), basePath)
        val driveFolderName = envValue("GOOGLE_DRIVE_FOLDER_NAME") ?: "Conversations"
        val driveFolderId = envValue("GOOGLE_DRIVE_FOLDER_ID")
        val drivePollTimeoutSeconds = envValue("GOOGLE_DRIVE_POLL_TIMEOUT_SECONDS")?.toLongOrNull() ?: 90L
        val drivePollIntervalMillis = envValue("GOOGLE_DRIVE_POLL_INTERVAL_MILLIS")?.toLongOrNull() ?: 2000L
        val driveConfig = DriveConfig(
            credentialsPath = driveCredentials,
            folderName = driveFolderName,
            folderId = driveFolderId,
            pollTimeout = Duration.ofSeconds(drivePollTimeoutSeconds.coerceAtLeast(5L)),
            pollInterval = Duration.ofMillis(drivePollIntervalMillis.coerceAtLeast(500L)),
        )

        val contactsConfig = ContactsConfig(
            clientSecretPath = resolvePath(envValue("GOOGLE_CONTACTS_CLIENT_SECRET_PATH"), basePath),
            tokenPath = resolvePath(envValue("GOOGLE_CONTACTS_TOKEN_PATH"), basePath)
        )

        return AppConfig(
            deviceId = envValue("DEVICE_ID"),
            username = username,
            basePath = basePath,
            deviceSnapshotDir = deviceSnapshotDir,
            localSnapshotDir = localSnapshotDir,
            adbPath = adbPath,
            drive = driveConfig,
            contacts = contactsConfig,
        )
    }

    fun validate(config: AppConfig): ConfigValidationResult {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        if (config.username.isNullOrBlank()) {
            warnings += "USERNAME is not set; defaults rely on the current system user"
        }

        if (!Files.exists(config.localSnapshotDir)) {
            warnings += "Local snapshot directory '${config.localSnapshotDir}' does not exist yet"
        }

        if (!Files.exists(config.adbPath)) {
            errors += "ADB binary not found at '${config.adbPath}'"
        } else if (!Files.isRegularFile(config.adbPath)) {
            errors += "ADB path '${config.adbPath}' is not a file"
        }

        if (config.deviceSnapshotDir.isBlank()) {
            errors += "DEVICE_SNAPSHOT_DIR must not be blank"
        }

        config.drive.credentialsPath?.let {
            if (!Files.exists(it)) {
                warnings += "Google Drive credentials file not found at '$it'; Drive downloads will be disabled"
            } else if (!Files.isRegularFile(it)) {
                warnings += "Google Drive credentials path '$it' is not a file"
            }
        } ?: run {
            warnings += "GOOGLE_DRIVE_CREDENTIALS_PATH is not set; Drive downloads will be disabled"
        }

        config.contacts.let { contactsConfig ->
            if (contactsConfig.clientSecretPath == null || contactsConfig.tokenPath == null) {
                warnings += "Google Contacts credentials not configured; contact enrichment will be skipped"
            } else {
                if (!Files.exists(contactsConfig.clientSecretPath)) {
                    errors += "Google Contacts client secret not found at '${contactsConfig.clientSecretPath}'"
                }
                if (!Files.exists(contactsConfig.tokenPath)) {
                    errors += "Google Contacts token not found at '${contactsConfig.tokenPath}'"
                }
            }
        }

        return ConfigValidationResult(errors = errors, warnings = warnings)
    }

    private fun envValue(key: String): String? = env[key]?.trim()?.takeIf { it.isNotEmpty() }

    private fun resolveBasePath(rawValue: String?, username: String?): Path {
        rawValue?.let { resolvePath(it, null)?.let { return it } }
        val home = System.getProperty("user.home")?.let { Paths.get(it) }
        if (username.isNullOrBlank()) {
            return home?.normalize() ?: Paths.get(".").toAbsolutePath().normalize()
        }
        val userHomeGuess = Paths.get("/Users", username)
        return if (Files.exists(userHomeGuess)) {
            userHomeGuess.normalize()
        } else {
            home?.normalize() ?: userHomeGuess.normalize()
        }
    }

    private fun resolvePath(rawValue: String?, basePath: Path?): Path? {
        rawValue ?: return null
        return try {
            val expanded = expandUser(rawValue)
            val candidate = Paths.get(expanded)
            if (candidate.isAbsolute) candidate.normalize()
            else (basePath ?: Paths.get(".").toAbsolutePath()).resolve(candidate).normalize()
        } catch (ex: InvalidPathException) {
            null
        }
    }

    private fun expandUser(path: String): String {
        if (!path.startsWith("~")) return path
        val home = System.getProperty("user.home") ?: return path.removePrefix("~")
        val suffix = path.removePrefix("~")
        return home + suffix
    }
}

/**
 * Global access point mirroring the legacy Config singleton while deferring actual loading
 * until accessed. This eases the ongoing refactor of callers.
 */
object Config {
    private val loader = ConfigLoader()
    val appConfig: AppConfig by lazy { loader.load() }

    val deviceId: String? get() = appConfig.deviceId
    val username: String? get() = appConfig.username
    val basePath: Path get() = appConfig.basePath
    val deviceSnapshotDir: String get() = appConfig.deviceSnapshotDir
    val localSnapshotDir: Path get() = appConfig.localSnapshotDir
    val adbPath: Path get() = appConfig.adbPath
    val drive: DriveConfig get() = appConfig.drive
    val contacts: ContactsConfig get() = appConfig.contacts

    fun validation(): ConfigValidationResult = loader.validate(appConfig)

    fun buildAdbCommand(baseCommand: String): String {
        val adbExecutable = adbPath.toString()
        return if (deviceId.isNullOrBlank()) {
            "$adbExecutable $baseCommand"
        } else {
            "$adbExecutable -s $deviceId $baseCommand"
        }
    }
}
