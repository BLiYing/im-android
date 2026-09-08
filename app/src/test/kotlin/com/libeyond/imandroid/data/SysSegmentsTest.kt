package com.libeyond.imandroid.data

import com.libeyond.imandroid.sdk.protocol.SysSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 系统消息分段。重点在「回退不能崩」与「备注只在本地出现」。 */
class SysSegmentsTest {

    private val json =
        """[{"uid":"1001","text":"张三"},{"text":" 邀请 "},{"uid":"1002","text":"李四"},{"text":" 加入群聊"}]"""

    @Test
    fun `解析出四段，名字段带 uid`() {
        val segs = SysSegments.parse(json)
        assertEquals(4, segs.size)
        assertTrue(SysSegments.isName(segs[0]))
        assertFalse(SysSegments.isName(segs[1]))
    }

    /** 历史系统消息没有分段（服务端当时没存这一列），必须回退整句，不能显示空白。 */
    @Test
    fun `没有分段时回退成整句一段`() {
        val segs = SysSegments.render(null, "管理员开启了全员禁言")
        assertEquals(1, segs.size)
        assertEquals("管理员开启了全员禁言", segs[0].text)
        assertFalse(SysSegments.isName(segs[0]))
    }

    /** 坏 JSON 不能让整条消息渲染不出来——宁可退回整句。 */
    @Test
    fun `坏 JSON 回退整句而不是崩`() {
        val segs = SysSegments.render("{不是数组}", "某某 加入群聊")
        assertEquals(listOf("某某 加入群聊"), segs.map { it.text })
    }

    /** 协议保证：各段拼起来恒等于 content。对不上说明数据串了。 */
    @Test
    fun `分段拼接等于整句`() {
        assertTrue(SysSegments.matchesContent(SysSegments.parse(json), "张三 邀请 李四 加入群聊"))
        assertFalse(SysSegments.matchesContent(SysSegments.parse(json), "别的句子"))
    }

    /**
     * **备注 → 群昵称 → 公开昵称**。末级用服务端给的公开昵称而**不是 uid**——
     * uid 是 10 位内部 ID，露在界面上对用户毫无意义。
     */
    @Test
    fun `显示名回退链`() {
        val seg = SysSegment(uid = "1002", text = "用户1002")
        assertEquals("张曼玉", SysSegments.displayName(seg, remark = "张曼玉", groupNickname = "群里的小李"))
        assertEquals("群里的小李", SysSegments.displayName(seg, remark = "", groupNickname = "群里的小李"))
        assertEquals("用户1002", SysSegments.displayName(seg, remark = null, groupNickname = null))
    }

    /**
     * **隐私**：分段里的 `text` 恒为公开昵称，备注只在本地重解析时才出现。
     * 这条钉的是「渲染时才叠备注」，而不是把备注写回分段。
     */
    @Test
    fun `分段本身不含备注`() {
        val segs = SysSegments.parse(json)
        assertTrue(segs.none { it.text.contains("备注") })
        // 叠备注是渲染期的事，parse 出来的原始段不变
        SysSegments.displayName(segs[0], remark = "老张", groupNickname = null)
        assertEquals("张三", segs[0].text)
    }
}
