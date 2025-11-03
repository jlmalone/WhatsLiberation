package vision.salient.drive

import java.nio.file.Path

interface DriveDownloader {
    fun downloadExport(
        chatName: String,
        includeMedia: Boolean,
        runDirectory: Path,
        desiredFileName: String?
    ): List<Path>
}
