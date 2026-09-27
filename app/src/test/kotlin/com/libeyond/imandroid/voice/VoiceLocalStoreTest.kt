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
}
