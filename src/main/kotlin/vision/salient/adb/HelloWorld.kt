package vision.salient.adb

import vision.salient.Config
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.*

object HelloWorld {
    @JvmStatic
    fun main(args: Array<String>) {

        println("Starting WhatsLiberation...")

        if (!isDeviceConnected()) {
            println("No device connected. Please connect an Android device with USB debugging enabled.")
            return
        }
        if (!isWhatsAppInstalled()) {
            println("WhatsApp is not installed on this device. Please install WhatsApp and try again.")
            return
        }
        ensureDeviceDirectory()
        Thread.sleep(2000)
        setupLocalDirectory()
        Thread.sleep(2000)
        launchWhatsApp()
        captureAndPullSnapshot("initial_screen")
        println("Initial snapshot captured. Check ${Config.localSnapshotDir} for output.")
    }

    // Check if an ADB device is connected
    fun isDeviceConnected(): Boolean {
        val command = "${Config.adbPath} devices"
        val output = runAdbCommandWithOutput(command) ?: return false
        return output.trim().split("\n").size > 1 // More than just the header line
    }

    // Check if WhatsApp is installed on the device
    fun isWhatsAppInstalled(): Boolean {
        // This command will list packages matching 'com.whatsapp'
        val command = buildAdbCommand("shell pm list packages com.whatsapp")
        val output = runAdbCommandWithOutput(command)
        return output?.contains("com.whatsapp") ?: false
    }


    // Set up the local directory for storing exports
    private fun setupLocalDirectory() {
        val dir = File(Config.localSnapshotDir.replace("~", System.getProperty("user.home")))
        if (!dir.exists()) {
            dir.mkdirs()
            println("Created local directory: ${dir.absolutePath}")
        }
    }

    // Launch WhatsApp on the device
    private fun launchWhatsApp() {
        val packageName = "com.whatsapp"
        val adbCommand = buildAdbCommand("shell am start -n $packageName/.Main")
        runCommand(adbCommand)
        Thread.sleep(2000) // Wait for app to launch
        println("WhatsApp launched.")
    }

    // Capture UI dump and screenshot, then pull to local folder
    fun captureAndPullSnapshot(pageName: String) {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss").format(Date())
        val xmlFile = captureXmlDump(pageName, timestamp)
        val screenshotFile = captureScreenshot(pageName, timestamp)
        pullFileFromDevice(xmlFile)
        pullFileFromDevice(screenshotFile)
        cleanUpDeviceFiles()
    }

    // Capture UI XML dump
    private fun captureXmlDump(pageName: String, timestamp: String): String {
        val uiXmlName = "${pageName}_${timestamp}_ui.xml"
        val adbCommand = buildAdbCommand("shell uiautomator dump ${Config.deviceSnapshotDir}/$uiXmlName")
        runCommand(adbCommand)
        Thread.sleep(500) // Small delay to ensure file is written
        return uiXmlName
    }

    // Capture screenshot
    private fun captureScreenshot(pageName: String, timestamp: String): String {
        val screenshotName = "${pageName}_${timestamp}_screenshot.png"
        val adbCommand = buildAdbCommand("shell screencap -p ${Config.deviceSnapshotDir}/$screenshotName")
        runCommand(adbCommand)
        Thread.sleep(500) // Small delay to ensure file is written
        return screenshotName
    }

    // Pull file from device to local directory
    private fun pullFileFromDevice(fileName: String) {
        val localDir = Config.localSnapshotDir.replace("~", System.getProperty("user.home"))
        val adbCommand = buildAdbCommand("pull ${Config.deviceSnapshotDir}/$fileName $localDir/$fileName")
        runCommand(adbCommand)
        println("Pulled $fileName to $localDir")
    }

    // Clean up temporary files on the device
    private fun cleanUpDeviceFiles() {
        val adbCommand = buildAdbCommand("shell rm -f ${Config.deviceSnapshotDir}/*")
        runCommand(adbCommand)
        println("Cleaned up device files.")
    }

    private fun ensureDeviceDirectory() {
        val adbCommand = buildAdbCommand("shell mkdir -p ${Config.deviceSnapshotDir}")
        runCommand(adbCommand)
        println("Ensured device directory exists: ${Config.deviceSnapshotDir}")
    }

    // Build ADB command with proper path
    private fun buildAdbCommand(subCommand: String): String {
        return "${Config.adbPath} $subCommand"
    }

    // Run an ADB command without capturing output
    private fun runCommand(command: String) {
        try {
            val process = ProcessBuilder(*command.split(" ").toTypedArray()).start()
            process.waitFor()
        } catch (e: Exception) {
            println("Error executing command '$command': ${e.message}")
        }
    }

    // Run an ADB command and return output
    private fun runAdbCommandWithOutput(command: String): String? {
        return try {
            val process = Runtime.getRuntime().exec(command)
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val output = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                output.append(line).append("\n")
            }
            reader.close()
            process.waitFor()
            output.toString()
        } catch (e: Exception) {
            println("Error executing command with output '$command': ${e.message}")
            null
        }
    }



}
//
//fun main() {
//    println("Hello main ${HelloWorld.isDeviceConnected()}")
//
//}