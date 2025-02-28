package vision.salient
import io.github.cdimascio.dotenv.Dotenv


object Config {

    // Load environment variables
    private val dotenv = Dotenv.load()
    val deviceId = dotenv["DEVICE_ID"]
    val username: String? = dotenv["USERNAME"]

    // Paths
    val basePath: String = dotenv["BASE_PATH"] ?: "/Users/${username}"

    // Snapshot paths
    val deviceSnapshotDir: String = dotenv["DEVICE_SNAPSHOT_DIR"] ?: "/sdcard/whats"
    val localSnapshotDir: String = dotenv["LOCAL_SNAPSHOT_DIR"] ?: "${basePath}/Downloads/whatsliberation"
    // ADB paths
    val adbPath: String = dotenv["ADB_PATH"] ?: "${basePath}/Library/Android/sdk/platform-tools/adb"

    // Function to build ADB command
    fun buildAdbCommand(baseCommand: String): String {
        return if (deviceId.isNullOrEmpty()) {
            // No specific device ID, use base ADB command
            "$adbPath $baseCommand"
        } else {
            // Specific device ID is provided
            "$adbPath -s $deviceId $baseCommand"
        }
    }
}