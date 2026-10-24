package com.duobao.proxy.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class LogZhTest {
    /** Golden pairs produced by the desktop `app/qml/LogZh.js` (see src/test/tools/logzh-golden.js). */
    @Test
    fun matchesDesktopTranslator() {
        val text = javaClass.getResource("/logzh-golden.json")!!.readText()
        val pairs = (Json.parseToJsonElement(text) as JsonArray).map { p ->
            val a = p as JsonArray
            (a[0] as JsonPrimitive).content to (a[1] as JsonPrimitive).content
        }
        assertEquals(53, pairs.size)
        for ((input, expected) in pairs) {
            assertEquals("translate($input)", expected, LogZh.translate(LogZh.stripAnsi(input)))
        }
    }

    @Test
    fun durationReplacesFirstMatchOnly() {
        assertEquals("49毫秒", LogZh.duration("49ms"))
        assertEquals("1分5.2秒", LogZh.duration("1m5.2s"))
        assertEquals("1时2分3秒", LogZh.duration("1h2m3s"))
    }

    @Test
    fun unknownLinesPassThrough() {
        assertEquals("hello", LogZh.translate("hello"))
        assertEquals("信息 hello", LogZh.translate("INFO[0000] hello"))
    }
}
