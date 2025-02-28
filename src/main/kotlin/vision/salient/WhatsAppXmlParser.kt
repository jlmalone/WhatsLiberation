package vision.salient

import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import vision.salient.model.WhatsAppChat
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

object WhatsAppXmlParser {

    /**
     * Finds the first node in the XML with the specified [targetResId] and whose text equals [targetText].
     * Returns the center coordinates of its bounds as Pair(x, y), or null if not found.
     */
    fun findNodeCenterWithText(xmlContent: String, targetResId: String, targetText: String): Pair<Int, Int>? {
        val docBuilder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        val doc = docBuilder.parse(InputSource(StringReader(xmlContent)))
        val root = doc.documentElement
        var result: Pair<Int, Int>? = null

        fun traverse(node: Node) {
            if (result != null) return
            if (node.nodeType == Node.ELEMENT_NODE) {
                val element = node as Element
                val resId = element.getAttribute("resource-id")
                val text = element.getAttribute("text").trim()
                // Debug print
                // println("Checking node: resId='$resId', text='$text'")
                if (resId == targetResId && text == targetText) {
                    result = parseBoundsCenter(element.getAttribute("bounds"))
                    return
                }
                val children = element.childNodes
                for (i in 0 until children.length) {
                    traverse(children.item(i))
                    if (result != null) break
                }
            }
        }
        traverse(root)
        return result
    }

//    /**
//     * Parses a bounds string of the form "[x1,y1][x2,y2]" and returns the center as Pair(x, y).
//     */
//    fun parseBoundsCenter(bounds: String): Pair<Int, Int>? {
//        val regex = "\\[(\\d+),(\\d+)]\\[(\\d+),(\\d+)]".toRegex()
//        val matchResult = regex.find(bounds) ?: return null
//        val (x1, y1, x2, y2) = matchResult.destructured
//        val centerX = (x1.toInt() + x2.toInt()) / 2
//        val centerY = (y1.toInt() + y2.toInt()) / 2
//        return centerX to centerY
//    }
    /**
     * Finds the first node with the given [targetResId] in the provided XML content.
     * Returns the center coordinates of its bounds as a Pair(x, y) if found.
     */
    fun findNodeCenter(xmlContent: String, targetResId: String): Pair<Int, Int>? {
        val docBuilder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        val doc = docBuilder.parse(InputSource(StringReader(xmlContent)))
        val root = doc.documentElement

        var result: Pair<Int, Int>? = null

        fun traverse(node: Node) {
            if (result != null) return  // already found
            if (node.nodeType == Node.ELEMENT_NODE) {
                val element = node as Element
                val resId = element.getAttribute("resource-id")
                if (resId == targetResId) {
                    val bounds = element.getAttribute("bounds")
                    result = parseBoundsCenter(bounds)
                    return
                }
                val children = element.childNodes
                for (i in 0 until children.length) {
                    traverse(children.item(i))
                    if (result != null) break
                }
            }
        }
        traverse(root)
        return result
    }

    /**
     * Parse the WhatsApp UI XML for the conversation list screen.
     * Logs every element encountered.
     * Return a list of WhatsAppChat objects with name and tap coordinates.
     */
    fun parseConversationList(xmlContent: String): List<WhatsAppChat> {
        val chats = mutableListOf<WhatsAppChat>()
        println("DEBUG: Starting XML parsing...")

        val docBuilder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        val doc = docBuilder.parse(InputSource(StringReader(xmlContent)))
        val root = doc.documentElement

        fun traverse(node: Node, depth: Int = 0) {
            if (node.nodeType == Node.ELEMENT_NODE) {
                val element = node as Element
                val indent = "  ".repeat(depth)
                val resId = element.getAttribute("resource-id")
                val text = element.getAttribute("text")
                println("DEBUG: $indent Node: class=${element.tagName}, resource-id='$resId', text='$text'")

                // Check if this element is the conversation container.
                // You might need to adjust this if the resource-id has changed.
                if (resId == "com.whatsapp:id/contact_row_container") {
                    val bounds = element.getAttribute("bounds")
                    val chatName = findContactName(element)
                        ?: run {
                            println("DEBUG: $indent No contact name found in container; defaulting to 'Unknown Chat'")
                            "Unknown Chat"
                        }
                    val center = parseBoundsCenter(bounds)
                    if (center != null) {
                        println("DEBUG: $indent Found conversation: '$chatName' with bounds $bounds -> center=(${center.first}, ${center.second})")
                        chats.add(WhatsAppChat(chatName, bounds, center.first, center.second))
                    } else {
                        println("DEBUG: $indent Could not parse bounds: $bounds")
                    }
                }

                // Recurse into children.
                val children = element.childNodes
                for (i in 0 until children.length) {
                    traverse(children.item(i), depth + 1)
                }
            }
        }

        traverse(root)
        println("DEBUG: Finished parsing XML. Total conversations found: ${chats.size}")
        return chats
    }

    /**
     * Try to find the contact name by searching child nodes
     * that have resource-id="com.whatsapp:id/conversations_row_contact_name".
     */
    private fun findContactName(container: Element): String? {
        val nodes = container.getElementsByTagName("node")
        for (i in 0 until nodes.length) {
            val child = nodes.item(i) as? Element ?: continue
            val childResId = child.getAttribute("resource-id")
            val childText = child.getAttribute("text")
            println("DEBUG:    Checking child node: resource-id='$childResId', text='$childText'")
            if (childResId == "com.whatsapp:id/conversations_row_contact_name" && childText.isNotBlank()) {
                println("DEBUG:    Found contact name: '$childText'")
                return childText
            }
        }
        return null
    }

    /**
     * Parse the [x1,y1][x2,y2] bounds attribute, return the center as (x,y).
     */
    private fun parseBoundsCenter(bounds: String): Pair<Int, Int>? {
        val regex = "\\[(\\d+),(\\d+)]\\[(\\d+),(\\d+)]".toRegex()
        val matchResult = regex.find(bounds) ?: return null
        val (x1, y1, x2, y2) = matchResult.destructured
        val centerX = (x1.toInt() + x2.toInt()) / 2
        val centerY = (y1.toInt() + y2.toInt()) / 2
        return centerX to centerY
    }
}
