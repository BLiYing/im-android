package com.libeyond.imandroid.voice

import com.libeyond.imandroid.data.db.MessageEntity
import com.libeyond.imandroid.sdk.protocol.ContentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 语音播放纯规则（VOICE_MESSAGE_DESIGN §6/§7），对齐 iOS `IMVoicePlayer` / `IMChatViewController+Voice`。 */
class VoiceRulesTest {

    @Test
    fun `倍速三档循环，脏值兜底 1x`() {
        assertEquals(1.5f, VoiceRules.nextRate(1f))
        assertEquals(2f, VoiceRules.nextRate(1.5f))
        assertEquals(1f, VoiceRules.nextRate(2f))
        assertEquals(1f, VoiceRules.normalizeRate(0.7f))
        assertEquals(1f, VoiceRules.normalizeRate(3f))
        assertEquals(1.5f, VoiceRules.normalizeRate(1.52f))
        assertEquals("1.5x", VoiceRules.rateLabel(1.5f))
        assertEquals("1x", VoiceRules.rateLabel(9f))
    }

    @Test
    fun `气泡宽度按时长线性，封顶 240、下限 160`() {
        assertEquals(160f, VoiceRules.bubbleWidthDp(0))
        assertEquals(160f, VoiceRules.bubbleWidthDp(5_000))
        assertEquals(96f + 30 * 3.6f, VoiceRules.bubbleWidthDp(30_000), 0.001f)
        assertEquals(240f, VoiceRules.bubbleWidthDp(300_000))
    }

    @Test
    fun `播放中显剩余，空闲显总时长`() {
        assertEquals(12_000L, VoiceRules.shownMillis(12_000, 0.5f, active = false))
        assertEquals(6_000L, VoiceRules.shownMillis(12_000, 0.5f, active = true))
        assertEquals(0L, VoiceRules.shownMillis(12_000, 1.5f, active = true))
    }

    @Test
    fun `播放器时长不可信时用消息自带时长`() {
        // Web 录的分片 MP4：播放器读出 11ms，消息说 6714ms
        assertEquals(6714L, VoiceRules.effectiveDurationMs(11, 6714))
        assertEquals(6714L, VoiceRules.effectiveDurationMs(0, 6714))
        assertEquals(6720L, VoiceRules.effectiveDurationMs(6720, 6714))
        assertEquals(5000L, VoiceRules.effectiveDurationMs(5000, 0))
    }

    @Test
    fun `播放标识：已确认用 conv_seq，未确认用 clientMsgId`() {
        assertEquals("seq:42", VoiceRules.playableId(42, "c1"))
        assertEquals("cid:c1", VoiceRules.playableId(0, "c1"))
        assertNull(VoiceRules.playableId(0, ""))
    }

    private fun m(seq: Long, type: String = ContentType.VOICE, from: String = "peer", recalled: Long? = null) =
        MessageEntity(ownerUid = "me", convId = "c", convSeq = seq, sender = from, contentType = type, recalledAt = recalled)

    @Test
    fun `接力：跳过自己发的、撤回的、已播的，找到下一条对方语音`() {
        val list = listOf(m(1), m(2, from = "me"), m(3, recalled = 9), m(4), m(5))
        val next = VoiceRules.nextRelay(list, "seq:1", myUid = "me") { it == "seq:4" }
        assertEquals(5L, next?.convSeq)
    }

    @Test
    fun `接力：遇到非语音消息即停`() {
        val list = listOf(m(1), m(2, type = ContentType.TEXT), m(3))
        assertNull(VoiceRules.nextRelay(list, "seq:1", myUid = "me") { false })
    }

    @Test
    fun `接力：找不到刚播完那条或已到末尾返回空`() {
        val list = listOf(m(1), m(2))
        assertNull(VoiceRules.nextRelay(list, "seq:9", myUid = "me") { false })
        assertNull(VoiceRules.nextRelay(list, "seq:2", myUid = "me") { false })
    }
}
