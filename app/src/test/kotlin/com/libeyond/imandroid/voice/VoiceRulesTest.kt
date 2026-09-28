package com.libeyond.imandroid.voice

import com.libeyond.imandroid.data.db.MessageEntity
import com.libeyond.imandroid.sdk.protocol.ContentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    // ————————————————— 录制（§5）—————————————————

    @Test
    fun `振幅换算 0 到 1，越界钳位`() {
        assertEquals(0f, VoiceRules.amplitudeOf(0))
        assertEquals(1f, VoiceRules.amplitudeOf(32767))
        assertEquals(1f, VoiceRules.amplitudeOf(40000)) // 越界钳位
        assertEquals(0f, VoiceRules.amplitudeOf(-5))
    }

    @Test
    fun `振幅转协议字节，百分比钳位到 0 到 100`() {
        assertEquals(0, VoiceRules.amplitudeByte(0f).toInt())
        assertEquals(100, VoiceRules.amplitudeByte(1f).toInt())
        assertEquals(50, VoiceRules.amplitudeByte(0.5f).toInt())
        assertEquals(100, VoiceRules.amplitudeByte(2f).toInt()) // 越界钳位
    }

    @Test
    fun `波形编码——空数组返回 null，不超上限直传，超限按桶取最大值下采`() {
        assertNull(VoiceRules.encodeWaveform(ByteArray(0)))
        val short = byteArrayOf(10, 20, 30)
        val decodedShort = java.util.Base64.getDecoder().decode(VoiceRules.encodeWaveform(short))
        assertEquals(3, decodedShort.size)
        // 120 帧下采到 60：偶数下标那半桶取最大值应为该桶内的较大值
        val long = ByteArray(120) { (it % 10).toByte() }
        val decodedLong = java.util.Base64.getDecoder().decode(VoiceRules.encodeWaveform(long))
        assertEquals(VoiceRules.WAVEFORM_SAMPLES, decodedLong.size)
    }

    @Test
    fun `锁定判定——距离内锁定、走廊内近距高亮、越过锁钮也算锁定`() {
        // 手指原地未动：远离锁钮
        assertEquals(VoiceRules.LockPhase.None, VoiceRules.lockPhase(0f, 0f, 0f, -200f, 70f, 34f))
        // 进入高亮走廊但未到位
        assertEquals(VoiceRules.LockPhase.Near, VoiceRules.lockPhase(0f, -150f, 0f, -200f, 70f, 34f))
        // 到位即锁（无需松手）
        assertEquals(VoiceRules.LockPhase.Locked, VoiceRules.lockPhase(0f, -190f, 0f, -200f, 70f, 34f))
        // 快速上滑跳过锁钮：手指已高于锁中心、横向仍在走廊内 → 兜底判锁
        assertEquals(VoiceRules.LockPhase.Locked, VoiceRules.lockPhase(10f, -260f, 0f, -200f, 70f, 34f))
    }

    @Test
    fun `取消阈值——达到行宽 40 百分比才算过阈值`() {
        assertFalse(VoiceRules.cancelReady(-39f, 100f))
        assertTrue(VoiceRules.cancelReady(-40f, 100f))
        assertFalse(VoiceRules.cancelReady(0f, 0f)) // 行宽未量到时不误判
    }

    @Test
    fun `滑动提示——位移 0点4 阻尼、线性渐隐到 0点2 兜底，过阈值强制居中不透明`() {
        val (offset0, alpha0) = VoiceRules.slideHint(0f, cancelReady = false)
        assertEquals(0f, offset0)
        assertEquals(1f, alpha0)
        val (offset70, alpha70) = VoiceRules.slideHint(-70f, cancelReady = false)
        assertEquals(-28f, offset70, 0.001f) // -70 * 0.4
        assertEquals(0.5f, alpha70, 0.001f) // 1 + (-70/140)
        val (offsetFar, alphaFar) = VoiceRules.slideHint(-500f, cancelReady = false) // 越过 -140 钳位
        assertEquals(-56f, offsetFar, 0.001f) // clamp 到 -140 再 * 0.4
        assertEquals(0.2f, alphaFar, 0.001f)
        val (offsetReady, alphaReady) = VoiceRules.slideHint(-90f, cancelReady = true)
        assertEquals(0f, offsetReady)
        assertEquals(1f, alphaReady)
    }

    @Test
    fun `倒数——4分50秒前不显示，之后向上取整显剩余秒数`() {
        assertNull(VoiceRules.countdownSeconds(0))
        assertNull(VoiceRules.countdownSeconds(VoiceRules.COUNTDOWN_FROM_MS - 1))
        assertEquals(10, VoiceRules.countdownSeconds(VoiceRules.COUNTDOWN_FROM_MS))
        assertEquals(1, VoiceRules.countdownSeconds(VoiceRules.MAX_MS - 1))
        assertEquals(0, VoiceRules.countdownSeconds(VoiceRules.MAX_MS))
    }
}
