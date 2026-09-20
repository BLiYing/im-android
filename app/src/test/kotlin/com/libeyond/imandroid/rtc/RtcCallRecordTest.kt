package com.libeyond.imandroid.rtc

import com.imrtc.engine.IMCallEndReason
import com.imrtc.engine.IMCallSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** SDK 的 callSummary → 通话记录：只有主叫发，单聊发到 peer，群发到群，`*_elsewhere` / 缺目标不发。 */
class RtcCallRecordTest {

    private fun summary(
        role: String = "caller", reason: IMCallEndReason = IMCallEndReason.HANGUP, d: Long = 201,
        media: String = "video", group: Boolean = false, chat: String = "", peer: String = "1003", cid: String = "call-77a1",
    ) = IMCallSummary(cid, reason, d, "", media, group, chat, "1001", role, peer, "")

    @Test fun callerOneToOneGoesToPeerWithFixedBody() {
        val p = RtcCallRecords.planFor(summary())!!
        assertEquals("1003", p.peerUid)
        assertEquals("call-77a1", p.callId)
        assertEquals("""{"cid":"call-77a1","m":"video","r":"hangup","d":201}""", p.json)
    }

    @Test fun calleeNeverSends() {
        assertNull(RtcCallRecords.planFor(summary(role = "callee")))
    }

    @Test fun groupGoesToGroupWithGFlag() {
        val p = RtcCallRecords.planFor(summary(group = true, chat = "g_9", peer = "", reason = IMCallEndReason.NO_ANSWER, d = 0))!!
        assertEquals("g_9", p.chatGroupId)
        assertEquals("""{"cid":"call-77a1","m":"video","r":"no_answer","d":0,"g":1}""", p.json)
    }

    @Test fun missingTargetsAndElsewhereAreSkipped() {
        assertNull(RtcCallRecords.planFor(summary(peer = "")))
        assertNull(RtcCallRecords.planFor(summary(group = true, chat = "")))
        assertNull(RtcCallRecords.planFor(summary(cid = "")))
        assertNull(RtcCallRecords.planFor(summary(reason = IMCallEndReason.ANSWERED_ELSEWHERE)))
    }
}
