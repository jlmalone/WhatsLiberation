package vision.salient

import vision.salient.model.WhatsAppChat
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.StringReader
import org.xml.sax.InputSource


object WhatsAppXmlParser {

    /**
     * Parse the WhatsApp UI XML for the conversation list screen.
     * Return a list of WhatsAppChat objects with name and tap coordinates.
     */
    fun parseConversationList(xmlContent: String): List<WhatsAppChat> {
        val chats = mutableListOf<WhatsAppChat>()

        // Parse the XML into a DOM
        val docBuilder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        val doc = docBuilder.parse(InputSource(StringReader(xmlContent)))
        val root = doc.documentElement

        // Recursively traverse the DOM
        fun traverse(node: Node) {
            if (node.nodeType == Node.ELEMENT_NODE) {
                val element = node as Element
                val resourceId = element.getAttribute("resource-id")

                // We look for the container that encloses each conversation row
                // e.g., resource-id="com.whatsapp:id/contact_row_container"
                if (resourceId == "com.whatsapp:id/contact_row_container") {
                    val bounds = element.getAttribute("bounds") // e.g. "[0,172][1080,372]"
                    // The child that has the contact name might be:
                    // resource-id="com.whatsapp:id/conversations_row_contact_name"
                    // But sometimes we have to look deeper.

                    val chatName = findContactName(element) ?: "Unknown Chat"
                    val (cx, cy) = parseBoundsCenter(bounds) ?: (0 to 0)

                    if (cx != 0 && cy != 0) {
                        chats.add(
                            WhatsAppChat(
                                name = chatName,
                                bounds = bounds,
                                centerX = cx,
                                centerY = cy
                            )
                        )
                    }
                }

                // Traverse children
                val children = element.childNodes
                for (i in 0 until children.length) {
                    traverse(children.item(i))
                }
            }
        }

        traverse(root)
        return chats
    }

    /**
     * Try to find the contact name by searching child nodes
     * that have resource-id="com.whatsapp:id/conversations_row_contact_name".
     */
    private fun findContactName(container: Element): String? {
        val children = container.getElementsByTagName("node")
        for (i in 0 until children.length) {
            val child = children.item(i) as? Element ?: continue
            val childResId = child.getAttribute("resource-id")
            if (childResId == "com.whatsapp:id/conversations_row_contact_name") {
                // The "text" attribute typically holds the contact/group name
                val name = child.getAttribute("text")
                if (name.isNotBlank()) return name
            }
        }
        return null
    }

    /**
     * Parse the [x1,y1][x2,y2] bounds attribute, return the center as (x,y).
     */
    private fun parseBoundsCenter(bounds: String): Pair<Int, Int>? {
        // Example bounds: [0,172][1080,372]
        val regex = "\\[(\\d+),(\\d+)]\\[(\\d+),(\\d+)]".toRegex()
        val matchResult = regex.find(bounds) ?: return null
        val (x1, y1, x2, y2) = matchResult.destructured
        val centerX = (x1.toInt() + x2.toInt()) / 2
        val centerY = (y1.toInt() + y2.toInt()) / 2
        return centerX to centerY
    }
}