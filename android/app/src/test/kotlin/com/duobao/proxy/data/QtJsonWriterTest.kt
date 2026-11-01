package com.duobao.proxy.data

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class QtJsonWriterTest {
    private fun parse(s: String) = CompactJson.parseToJsonElement(s)

    @Test
    fun indentedMatchesQtLayout() {
        val text = QtJsonWriter.indented(parse("""{"b":[1,{"y":[],"x":{}}],"a":"多宝"}"""))
        val expected = """
            {
                "a": "多宝",
                "b": [
                    1,
                    {
                        "x": {
                        },
                        "y": [
                        ]
                    }
                ]
            }

        """.trimIndent()
        assertEquals(expected, text)
    }

    @Test
    fun compactSortsKeys() {
        assertEquals("""{"a":[true,null,1.5],"b":"/"}""", QtJsonWriter.compact(parse("""{"b":"/","a":[true,null,1.5]}""")))
    }

    @Test
    fun escapesLikeQt() {
        assertEquals("\"q\\\"b\\\\n\\nt\\t\\u0001\\u001f/é\"", QtJsonWriter.compact(JsonPrimitive("q\"b\\n\nt\t\u0001\u001f/é")))
    }

    @Test
    fun numberLiteralsAreKeptVerbatim() {
        assertEquals("""[12345678.5,1e12,-0.25,9876543210]""", QtJsonWriter.compact(parse("""[12345678.5,1e12,-0.25,9876543210]""")))
    }

    @Test
    fun kotlinNumbersFollowQVariantRules() {
        assertEquals("[2080,12345678.5,0.1,3]", QtJsonWriter.compact(listOf(2080.0, 12345678.5, 0.1, 3L).toJson()))
    }
}
