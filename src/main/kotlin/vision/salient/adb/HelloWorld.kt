package vision.salient.adb

import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import vision.salient.Config
import vision.salient.WhatsAppXmlParser
import vision.salient.model.WhatsAppChat
import java.io.File
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory


object HelloWorld {
    @JvmStatic
    fun main(args: Array<String>) {
        println("Starting WhatsLiberation...")

        // Simple argument parsing for --device-id <id>
        var cliDeviceId: String? = null
        var i = 0
        while (i < args.size) {
            when (args[i]) {
                "--device-id" -> {
                    if (i + 1 < args.size) {
                        cliDeviceId = args[i + 1]
                        i += 2
                    } else {
                        i++
                    }
                }
                else -> i++
            }
        }
        Config.overrideDeviceId(cliDeviceId)

        println("Using device ID: ${Config.deviceId ?: "(default)"}")

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

//        // Use the AdbAutomation object to capture the page snapshot
//        AdbAutomation.capturePageSnapshot("initial_screen")
//
//        println("Initial snapshot captured. Check ${Config.localSnapshotDir} for output.")
//


        // Capture the conversation list snapshot.
        AdbAutomation.capturePageSnapshot("initial_screen")
        println("Initial snapshot captured. Check ${Config.localSnapshotDir} for output.")

        // Locate the most recent XML dump for "initial_screen".
        val localDir = Config.localSnapshotDir.replace("~", System.getProperty("user.home"))

        val snapshotXmlFile = findMostRecentXml(localDir, prefix = "navigation_initial_screen_")

//        val snapshotXmlFile = findMostRecentXml(localDir, prefix = "initial_screen_")
            ?: run {
                println("No initial_screen XML found in $localDir!")
                return
            }
        println("Found UI dump: $snapshotXmlFile")

        // Read and parse the XML dump.
        val xmlContent = File(snapshotXmlFile).readText()
        val chats: List<WhatsAppChat> = WhatsAppXmlParser.parseConversationList(xmlContent)

        if (chats.isEmpty()) {
            println("No conversations found in the UI dump.")
        } else {
            // Tap on the first conversation.
            val firstChat = chats[0]
            println("Tapping on first chat: '${firstChat.name}' at x=${firstChat.centerX}, y=${firstChat.centerY}")
            val tapCommand = Config.buildAdbCommand("shell input tap ${firstChat.centerX} ${firstChat.centerY}")
            runCommand(tapCommand)
            Thread.sleep(3000) // Wait for the chat to open.

            // Capture a snapshot of the opened chat.
            AdbAutomation.capturePageSnapshot("first_chat_opened")
            println("Captured snapshot of first chat. Check ${Config.localSnapshotDir} for output.")

//
//            // Now, locate and tap the overflow (three dots) button.
//            tapOverflowMenu("first_chat_opened")
//

//            runCommand(tapCommand)
//            Thread.sleep(3000) // Wait for the chat to open.
//
//            // Capture a snapshot of the opened chat.
//            AdbAutomation.capturePageSnapshot("first_chat_opened")
//            println("Captured snapshot of first chat. Check ${Config.localSnapshotDir} for output.")

            // Now, locate and tap the overflow (three dots) button.
            tapOverflowMenu("navigation_first_chat_opened")
            Thread.sleep(3000)
            AdbAutomation.capturePageSnapshot("opened_three_dot")
            Thread.sleep(3000)
            tapMoreMenu()
            Thread.sleep(3000)
            AdbAutomation.capturePageSnapshot("opened_more")
            Thread.sleep(3000)
            tapExportChat()
            Thread.sleep(3000)
            AdbAutomation.capturePageSnapshot("opened_export")
            Thread.sleep(3000)
            tapWithoutMediaOption()

            //todo
//            tapMore("navigation_opened_three_dot")

        }

    }

    private fun findContactName(container: Element): String? {
        val children = container.getElementsByTagName("node")
        for (i in 0 until children.length) {
            val child = children.item(i) as? Element ?: continue
            val childResId = child.getAttribute("resource-id")
            if (childResId == "com.whatsapp:id/conversations_row_contact_name") {
                val name = child.getAttribute("text")
                if (name.isNotBlank()) return name
            }
        }
        return null
    }

    // ----------------------------
    // Tapping the Overflow Menu
    // ----------------------------
    private fun tapOverflowMenu(prefix: String) {
        // Find the most recent UI dump for the opened chat.
        val localDir = Config.localSnapshotDir.replace("~", System.getProperty("user.home"))
        val uiDumpFile = findMostRecentXml(localDir, prefix = prefix)
            ?: run {
                println("No UI dump found with prefix $prefix in $localDir!")
                return
            }
        println("Found chat UI dump: $uiDumpFile")
        val xmlContent = File(uiDumpFile).readText()

        // Use our parser to find the overflow menu (resource-id "com.whatsapp:id/menuitem_overflow")
        val overflowCenter = WhatsAppXmlParser.findNodeCenter(xmlContent, "com.whatsapp:id/menuitem_overflow")
        if (overflowCenter == null) {
            println("Could not find the overflow menu in the UI dump.")
        } else {
            println("Tapping overflow menu at x=${overflowCenter.first}, y=${overflowCenter.second}")
            val tapCommand = Config.buildAdbCommand("shell input tap ${overflowCenter.first} ${overflowCenter.second}")
            runCommand(tapCommand)
        }
    }

    fun parseConversationList(xmlContent: String): List<WhatsAppChat> {
        val chats = mutableListOf<WhatsAppChat>()

        val docBuilder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        val doc = docBuilder.parse(InputSource(StringReader(xmlContent)))
        val root = doc.documentElement

        fun traverse(node: Node) {
            if (node.nodeType == Node.ELEMENT_NODE) {
                val element = node as Element
                val resourceId = element.getAttribute("resource-id")
                // Identify chat row containers
                if (resourceId == "com.whatsapp:id/contact_row_container") {
                    val bounds = element.getAttribute("bounds")
                    val chatName = findContactName(element) ?: "Unknown Chat"
                    val (cx, cy) = parseBoundsCenter(bounds) ?: (0 to 0)
                    if (cx != 0 && cy != 0) {
                        chats.add(WhatsAppChat(chatName, bounds, cx, cy))
                    }
                }
                // Recursively check child nodes
                val children = element.childNodes
                for (i in 0 until children.length) {
                    traverse(children.item(i))
                }
            }
        }

        traverse(root)
        return chats
    }

    private fun parseBoundsCenter(bounds: String): Pair<Int, Int>? {
        // e.g. [0,172][1080,372]
        val regex = "\\[(\\d+),(\\d+)]\\[(\\d+),(\\d+)]".toRegex()
        val match = regex.find(bounds) ?: return null
        val (x1, y1, x2, y2) = match.destructured
        val centerX = (x1.toInt() + x2.toInt()) / 2
        val centerY = (y1.toInt() + y2.toInt()) / 2
        return centerX to centerY
    }

    // Check if an ADB device is connected
    fun isDeviceConnected(): Boolean {
        val command = "${Config.adbPath} devices"
        val output = runAdbCommandWithOutput(command) ?: return false
        return output.trim().split("\n").size > 1 // More than just the header line
    }

    // Check if WhatsApp is installed on the device
    fun isWhatsAppInstalled(): Boolean {
        val command = Config.buildAdbCommand("shell pm list packages com.whatsapp")
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
        val adbCommand = Config.buildAdbCommand("shell am start -n $packageName/.Main")
        runCommand(adbCommand)
        Thread.sleep(2000) // Wait for app to launch
        println("WhatsApp launched.")
    }


    private fun findMostRecentXml(localDir: String, prefix: String): String? {
        val dirFile = File(localDir)
        if (!dirFile.exists()) return null
        val xmlFiles = dirFile.listFiles { f ->
            f.isFile && (f.name.startsWith(prefix) || f.name.startsWith("navigation_$prefix")) && f.name.endsWith("_ui.xml")
        } ?: return null

        println("DEBUG: Found XML files:")
        xmlFiles.forEach { println(" - ${it.name}") }

        return xmlFiles.maxByOrNull { it.lastModified() }?.absolutePath
    }



    // Ensure the device snapshot directory exists
    private fun ensureDeviceDirectory() {
        val adbCommand = Config.buildAdbCommand("shell mkdir -p ${Config.deviceSnapshotDir}")
        runCommand(adbCommand)
        println("Ensured device directory exists: ${Config.deviceSnapshotDir}")
    }

    // Helper: Run an ADB command and return its output
    private fun runAdbCommandWithOutput(command: String): String? {
        return try {
            val process = Runtime.getRuntime().exec(command)
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()
            output
        } catch (e: Exception) {
            println("Error executing command with output '$command': ${e.message}")
            null
        }
    }

    // Helper: Run an ADB command without capturing output
    private fun runCommand(command: String) {
        try {
            ProcessBuilder(*command.split(" ").toTypedArray()).start().waitFor()
        } catch (e: Exception) {
            println("Error executing command '$command': ${e.message}")
        }
    }

    private fun tapExportChat() {
        // Assume that after tapping the overflow button, you capture a new UI dump.
        // Here we look for a dump with prefix "first_chat_opened" (or whatever you use for the overflow screen)
        val localDir = Config.localSnapshotDir.replace("~", System.getProperty("user.home"))
        val uiDumpFile = findMostRecentXml(localDir, prefix = "navigation_opened_more")
            ?: run {
                println("No UI dump found for overflow screen in $localDir!")
                return
            }
        println("Found overflow UI dump: $uiDumpFile")
        val xmlContent = File(uiDumpFile).readText()

        // Look for the node with resource-id "com.whatsapp:id/title" and text "More"
        val targetResId = "com.whatsapp:id/title"
        val targetText = "Export chat"
        val exportChatCentre = WhatsAppXmlParser.findNodeCenterWithText(xmlContent, targetResId, targetText)
        if (exportChatCentre == null) {
            println("Could not locate the 'Export chat' menu item in the UI dump.")
        } else {
            println("Tapping 'Export chat' menu at x=${exportChatCentre.first}, y=${exportChatCentre.second}")
            val tapCommand = Config.buildAdbCommand("shell input tap ${exportChatCentre.first} ${exportChatCentre.second}")
            runCommand(tapCommand)
        }
    }

    private fun tapMoreMenu() {
        // Assume that after tapping the overflow button, you capture a new UI dump.
        // Here we look for a dump with prefix "first_chat_opened" (or whatever you use for the overflow screen)
        val localDir = Config.localSnapshotDir.replace("~", System.getProperty("user.home"))
        val uiDumpFile = findMostRecentXml(localDir, prefix = "navigation_first_chat_opened")
            ?: run {
                println("No UI dump found for overflow screen in $localDir!")
                return
            }
        println("Found overflow UI dump: $uiDumpFile")
        val xmlContent = File(uiDumpFile).readText()

        // Look for the node with resource-id "com.whatsapp:id/title" and text "More"
        val targetResId = "com.whatsapp:id/title"
        val targetText = "More"
        val moreCenter = WhatsAppXmlParser.findNodeCenterWithText(xmlContent, targetResId, targetText)
        if (moreCenter == null) {
            println("Could not locate the 'More' menu item in the UI dump.")
        } else {
            println("Tapping 'More' menu at x=${moreCenter.first}, y=${moreCenter.second}")
            val tapCommand = Config.buildAdbCommand("shell input tap ${moreCenter.first} ${moreCenter.second}")
            runCommand(tapCommand)
        }
    }

    private fun tapWithoutMediaOption() {
        val localDir = Config.localSnapshotDir.replace("~", System.getProperty("user.home"))
        val uiDumpFile = findMostRecentXml(localDir, prefix = "navigation_opened_export")
            ?: run {
                println("No UI dump found for export options in $localDir!")
                return
            }
        println("Found export options dump: $uiDumpFile")
        val xmlContent = File(uiDumpFile).readText()
        val center = WhatsAppXmlParser.findNodeCenterWithText(xmlContent, "com.whatsapp:id/title", "Without media")
        if (center == null) {
            println("Could not locate the 'Without media' option in the UI dump.")
        } else {
            println("Tapping 'Without media' at x=${center.first}, y=${center.second}")
            val tapCommand = Config.buildAdbCommand("shell input tap ${center.first} ${center.second}")
            runCommand(tapCommand)
        }
    }
}
