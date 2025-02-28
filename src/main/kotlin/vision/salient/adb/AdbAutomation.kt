

package vision.salient.adb

import vision.salient.Config
import vision.salient.Config.buildAdbCommand
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.*

object AdbAutomation {

    fun pullFilesFromDevice(fileName: String, localPath: String) {
        val adbCommand = buildAdbCommand("pull /sdcard/hge/$fileName $localPath")
        runCommand(adbCommand)
    }

    fun capturePageSnapshot(pageName: String) {
        val timestamp = SimpleDateFormat("yyyyMMddHHmmss").format(Date())
//        val adbAutomation = vision.salient.adb.AdbAutomation(SnapshotConfig())

        val xmlFile = captureXmlDump(pageName, timestamp)
        val screenshotFile = captureScreenshot(pageName, timestamp)

        pullFilesFromDevice(xmlFile)
        pullFilesFromDevice(screenshotFile)

        cleanUpDeviceFiles()  // Optionally delete files from the device
    }

    fun captureXmlDump(pageName: String, timeStamp: String): String {
        val uiXmlName = "navigation_${pageName}_${timeStamp}_ui.xml"
        val adbCommand = buildAdbCommand("shell uiautomator dump ${Config.deviceSnapshotDir}/$uiXmlName")
        runCommand(adbCommand)
        return uiXmlName
    }

    fun captureScreenshot(pageName: String, timeStamp: String): String {
        val screenshotName = "navigation_${pageName}_${timeStamp}_screenshot.png"
        val adbCommand = buildAdbCommand("shell screencap -p ${Config.deviceSnapshotDir}/$screenshotName")
        runCommand(adbCommand)
        return screenshotName
    }

    fun pullFilesFromDevice(fileName: String) {
        val adbCommand = buildAdbCommand("pull ${Config.deviceSnapshotDir}/$fileName ${Config.localSnapshotDir}/$fileName")
        runCommand(adbCommand)
    }

    fun cleanUpDeviceFiles() {
        val adbCommand = buildAdbCommand("shell rm -f ${Config.deviceSnapshotDir}/*.xml ${Config.deviceSnapshotDir}/*.png")
        runCommand(adbCommand)
    }

    private fun runCommand(command: String) {
        val process = ProcessBuilder(*command.split(" ").toTypedArray()).start()
        process.waitFor()
    }


    fun runAdbCommandWithOutput(command: String): String? {
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
            e.printStackTrace()
            null
        }
    }

    fun dumpUi(filePath: String) {
        val adbCommand = buildAdbCommand("shell uiautomator dump $filePath")
        runCommand(adbCommand)
    }
}