package vision.salient.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

class ConfigLoaderTest {

    @Test
    fun `load uses sensible defaults when optional values missing`() {
        val baseDir = Files.createTempDirectory("wl-base").toAbsolutePath()
        val loader = ConfigLoader(
            MapEnvSource(
                mapOf(
                    "BASE_PATH" to baseDir.toString(),
                    "USERNAME" to "tester",
                )
            )
        )

        val config = loader.load()

        assertEquals(baseDir, config.basePath)
        assertEquals(baseDir.resolve("Downloads/whatsliberation"), config.localSnapshotDir)
        assertEquals(baseDir.resolve("Library/Android/sdk/platform-tools/adb"), config.adbPath)
        assertEquals("/sdcard/whats", config.deviceSnapshotDir)
    }

    @Test
    fun `validate flags missing adb binary`() {
        val baseDir = Files.createTempDirectory("wl-base").toAbsolutePath()
        val loader = ConfigLoader(
            MapEnvSource(
                mapOf(
                    "BASE_PATH" to baseDir.toString(),
                    "LOCAL_SNAPSHOT_DIR" to baseDir.resolve("exports").toString(),
                    "DEVICE_SNAPSHOT_DIR" to "/sdcard/custom",
                    "USERNAME" to "tester",
                )
            )
        )

        val config = loader.load()
        val result = loader.validate(config)

        assertTrue(result.errors.any { it.contains("ADB binary not found") })
        assertTrue(result.warnings.any { it.contains("Local snapshot directory") })
    }

    @Test
    fun `validate passes when directories and adb exist`() {
        val baseDir = Files.createTempDirectory("wl-base").toAbsolutePath()
        val exportsDir = Files.createDirectories(baseDir.resolve("exports"))
        val adbDir = Files.createDirectories(baseDir.resolve("tools"))
        val adbBinary = Files.createFile(adbDir.resolve("adb"))
        val creds = Files.createFile(baseDir.resolve("creds.json"))
        val contactsSecret = Files.writeString(baseDir.resolve("contacts_secret.json"), "{}")
        val contactsToken = Files.writeString(
            baseDir.resolve("contacts_token.json"),
            "{\"type\":\"authorized_user\",\"client_id\":\"id\",\"client_secret\":\"secret\",\"refresh_token\":\"token\"}"
        )

        val loader = ConfigLoader(
            MapEnvSource(
                mapOf(
                    "BASE_PATH" to baseDir.toString(),
                    "LOCAL_SNAPSHOT_DIR" to exportsDir.toString(),
                    "ADB_PATH" to adbBinary.toString(),
                    "DEVICE_SNAPSHOT_DIR" to "/sdcard/whats",
                    "USERNAME" to "tester",
                    "GOOGLE_DRIVE_CREDENTIALS_PATH" to creds.toString(),
                    "GOOGLE_CONTACTS_CLIENT_SECRET_PATH" to contactsSecret.toString(),
                    "GOOGLE_CONTACTS_TOKEN_PATH" to contactsToken.toString(),
                    )
            )
        )

        val config = loader.load()
        val result = loader.validate(config)

        assertTrue(result.isValid)
        assertTrue(result.warnings.isEmpty())
    }
}
