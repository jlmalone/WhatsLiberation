package vision.salient.adb

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import vision.salient.config.AppConfig
import vision.salient.config.ContactsConfig
import vision.salient.config.DriveConfig
import java.nio.file.Files
import java.nio.file.Path

class RealAdbClientTest {

    @Test
    fun `run builds command including device id when provided`() {
        val processRunner = CapturingProcessRunner()
        val config = appConfig(deviceId = "device123")
        val client = RealAdbClient(config, processRunner)

        val result = client.run(listOf("devices"))

        assertEquals(listOf(config.adbPath.toString(), "-s", "device123", "devices"), result.command)
        assertEquals(1, processRunner.commands.size)
        assertEquals(result.command, processRunner.commands.first())
    }

    @Test
    fun `run omits device flag when not specified`() {
        val processRunner = CapturingProcessRunner()
        val config = appConfig(deviceId = null)
        val client = RealAdbClient(config, processRunner)

        client.run(listOf("version"))

        assertEquals(listOf(config.adbPath.toString(), "version"), processRunner.commands.first())
    }

    @Test
    fun `shell adds shell prefix`() {
        val processRunner = CapturingProcessRunner()
        val config = appConfig(deviceId = null)
        val client = RealAdbClient(config, processRunner)

        client.shell("input", "tap", "10", "20")

        assertEquals(
            listOf(config.adbPath.toString(), "shell", "input", "tap", "10", "20"),
            processRunner.commands.first()
        )
    }

    private fun appConfig(deviceId: String?): AppConfig {
        val base = Files.createTempDirectory("adb")
        val adb = Files.createFile(base.resolve("adb"))
        return AppConfig(
            deviceId = deviceId,
            username = "tester",
            basePath = base,
            deviceSnapshotDir = "/sdcard/whats",
            localSnapshotDir = base,
            adbPath = adb,
            drive = DriveConfig(
                credentialsPath = null,
                folderName = "Conversations",
                folderId = null,
                pollTimeout = java.time.Duration.ofSeconds(10),
                pollInterval = java.time.Duration.ofMillis(500)
            ),
            contacts = vision.salient.config.ContactsConfig(
                clientSecretPath = null,
                tokenPath = null
            ),
        )
    }

    private class CapturingProcessRunner : ProcessRunner {
        val commands = mutableListOf<List<String>>()

        override fun run(command: List<String>, timeoutMillis: Long?): ProcessOutput {
            commands += command
            return ProcessOutput(0, stdout = "ok", stderr = "")
        }
    }
}
