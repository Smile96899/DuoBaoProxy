package com.duobao.proxy.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class I18nTest {
    @After
    fun reset() = I18n.apply("zh")

    @Test
    fun chineseIsUnchanged() {
        I18n.apply("zh")
        assertEquals("设置", tr("设置"))
    }

    @Test
    fun exactAndTemplates() {
        I18n.apply("en")
        assertEquals("Settings", tr("设置"))
        assertEquals("Imported 3 nodes into \"My nodes\"", tr("导入 3 个节点到「自建节点」"))
        assertEquals("Updated 2 h ago", tr("2 小时前更新"))
        assertEquals("Offline · Japan", tr("无网络 · 日本"))
        assertEquals("Not supported by xray", tr("xray 不支持"))
        assertEquals("node-01", tr("node-01"))
        assertEquals("东京 01", tr("东京 01"))
    }

    @Test
    fun everyTemplateKeyIsDistinctFromTheExactTable() {
        for (k in I18n.TEMPLATES.keys) assert(k !in I18n.EN) { k }
    }
}
