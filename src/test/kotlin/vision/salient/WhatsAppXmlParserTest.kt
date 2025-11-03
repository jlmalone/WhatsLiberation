package vision.salient

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WhatsAppXmlParserTest {

    @Test
    fun `parseConversationList extracts chat names and coordinates`() {
        val xml = loadFixture("chat_list_sample.xml")

        val chats = WhatsAppXmlParser.parseConversationList(xml)

        assertEquals(2, chats.size)
        assertEquals("Alice", chats[0].name)
        assertEquals("Bob", chats[1].name)
        assertEquals(540, chats[0].centerX)
        assertEquals(272, chats[0].centerY)
        assertEquals(540, chats[1].centerX)
        assertEquals(472, chats[1].centerY)
    }

    @Test
    fun `findNodeCenterWithText locates Export chat option`() {
        val xml = loadFixture("overflow_menu_export.xml")

        val center = WhatsAppXmlParser.findNodeCenterWithText(
            xml,
            targetResId = "com.whatsapp:id/title",
            targetText = "Export chat"
        )

        assertNotNull(center)
        assertEquals(540, center!!.first)
        assertEquals(950, center.second)
    }

    @Test
    fun `findNodeCenter locates nodes by resource id only`() {
        val xml = loadFixture("overflow_menu_export.xml")

        val center = WhatsAppXmlParser.findNodeCenter(xml, "com.whatsapp:id/title")

        assertNotNull(center)
        assertTrue(center!!.first > 0)
        assertTrue(center.second > 0)
    }

    @Test
    fun `findNodeCenterWithText respects ignore case option`() {
        val xml = """
            <hierarchy>
              <node resource-id='android:id/button1' text='Include Media' bounds='[600,1200][950,1340]' />
            </hierarchy>
        """.trimIndent()

        val center = WhatsAppXmlParser.findNodeCenterWithText(
            xml,
            targetResId = "android:id/button1",
            targetText = "include media",
            ignoreCase = true
        )

        assertNotNull(center)
        assertEquals(775, center!!.first)
        assertEquals(1270, center.second)
    }

    @Test
    fun `findNodeCenterContainingText matches substrings`() {
        val xml = """
            <hierarchy>
              <node resource-id='android:id/text1' text='My Drive' bounds='[0,0][200,200]' />
            </hierarchy>
        """.trimIndent()

        val center = WhatsAppXmlParser.findNodeCenterContainingText(
            xml,
            targetResId = "android:id/text1",
            fragment = "drive",
            ignoreCase = true
        )

        assertNotNull(center)
        assertEquals(100, center!!.first)
        assertEquals(100, center.second)
    }

    private fun loadFixture(name: String): String {
        val resource = javaClass.classLoader?.getResource("fixtures/ui/$name")
            ?: error("Fixture $name not found")
        return resource.openStream().bufferedReader().use { it.readText() }
    }
}
