package com.libeyond.imandroid.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 本机已播集合与会话级倍速（VOICE_MESSAGE_DESIGN §6.2/§7）。 */
class VoiceLocalStoreTest {

    private class MemKv : VoiceKv {
        val map = HashMap<String, String>()
        override fun get(key: String) = map[key]
        override fun put(key: String, value: String) { map[key] = value }
    }

    @Test
    fun `已播按账号与会话隔离，并能从落盘恢复`() {
        val kv = MemKv()
        val s = VoicePlayedStore(kv)
        s.markPlayed("u1", "c1", "seq:3")
        assertTrue(s.hasPlayed("u1", "c1", "seq:3"))
        assertFalse(s.hasPlayed("u2", "c1", "seq:3"))
        assertFalse(s.hasPlayed("u1", "c2", "seq:3"))
        // 新实例（冷启动）从 kv 读回
        assertTrue(VoicePlayedStore(kv).hasPlayed("u1", "c1", "seq:3"))
    }

    @Test
    fun `超过封顶按 FIFO 剔除最早的`() {
        val kv = MemKv()
        val s = VoicePlayedStore(kv)
        repeat(VoicePlayedStore.CAP + 2) { s.markPlayed("u", "c", "seq:$it") }
        assertFalse(s.hasPlayed("u", "c", "seq:0"))
        assertFalse(s.hasPlayed("u", "c", "seq:1"))
        assertTrue(s.hasPlayed("u", "c", "seq:2"))
        assertEquals(VoicePlayedStore.CAP, kv.map.values.single().split('\n').size)
    }

    @Test
    fun `重复标记不改版本号，新标记才通知刷新`() {
        val s = VoicePlayedStore(MemKv())
        s.markPlayed("u", "c", "seq:1")
        val v = s.version.value
        s.markPlayed("u", "c", "seq:1")
        assertEquals(v, s.version.value)
        s.markPlayed("u", "c", "seq:2")
        assertEquals(v + 1, s.version.value)
    }

    @Test
    fun `倍速按会话记忆，默认 1x，写入规范化`() {
        val r = VoiceRateStore(MemKv())
        assertEquals(1f, r.rateFor("c1"))
        r.setRate("c1", 2f)
        r.setRate("c2", 1.49f)
        assertEquals(2f, r.rateFor("c1"))
        assertEquals(1.5f, r.rateFor("c2"))
        r.setRate("c1", 7f)
        assertEquals(1f, r.rateFor("c1"))
    }

    @Test
    fun `转写文本按内容缓存，未转过或已被挤掉都是 null`() {
        val kv = MemKv()
        val s = VoiceTranscriptStore(kv)
        assertEquals(null, s.cachedText("/uploads/a.m4a"))
        s.putText("/uploads/a.m4a", "你好")
        assertEquals("你好", s.cachedText("/uploads/a.m4a"))
        // 新实例（冷启动）从 kv 读回——同一段音频转发/收藏出多份也该秒出
        assertEquals("你好", VoiceTranscriptStore(kv).cachedText("/uploads/a.m4a"))
        assertEquals(null, s.cachedText(""))
    }

    @Test
    fun `转写文本超过封顶按 FIFO 挤掉最早的`() {
        val s = VoiceTranscriptStore(MemKv())
        repeat(VoiceTranscriptStore.CACHE_MAX + 2) { s.putText("/uploads/$it.m4a", "text-$it") }
        assertEquals(null, s.cachedText("/uploads/0.m4a"))
        assertEquals(null, s.cachedText("/uploads/1.m4a"))
        assertEquals("text-2", s.cachedText("/uploads/2.m4a"))
    }

    @Test
    fun `折叠态跨实例落盘，取消折叠后消失`() {
        val kv = MemKv()
        val s = VoiceTranscriptStore(kv)
        assertFalse(s.isCollapsed("seq:1"))
        s.collapse("seq:1")
        assertTrue(s.isCollapsed("seq:1"))
        // 新实例（冷启动重进会话）折叠态还在——不能让「取消转文字」在重启后失效
        assertTrue(VoiceTranscriptStore(kv).isCollapsed("seq:1"))
        s.expand("seq:1")
        assertFalse(s.isCollapsed("seq:1"))
        assertFalse(VoiceTranscriptStore(kv).isCollapsed("seq:1"))
    }

    @Test
    fun `折叠名单超过封顶按 FIFO 挤掉最早的`() {
        val s = VoiceTranscriptStore(MemKv())
        repeat(VoiceTranscriptStore.COLLAPSED_MAX + 2) { s.collapse("seq:$it") }
        assertFalse(s.isCollapsed("seq:0"))
        assertFalse(s.isCollapsed("seq:1"))
        assertTrue(s.isCollapsed("seq:2"))
    }
}
