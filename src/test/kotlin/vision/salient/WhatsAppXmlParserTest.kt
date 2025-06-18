import kotlin.test.Test
import kotlin.test.assertEquals

class WhatsAppXmlParserTest {
    @Test
    fun testParseBoundsCenter() {
        val clazz = vision.salient.WhatsAppXmlParser::class.java
        val method = clazz.getDeclaredMethod("parseBoundsCenter", String::class.java)
        method.isAccessible = true
        val parserInstance = vision.salient.WhatsAppXmlParser
        val result = method.invoke(parserInstance, "[0,100][200,300]") as? Pair<*, *>
        requireNotNull(result)
        assertEquals(100, result.first)
        assertEquals(200, result.second)
    }
}
