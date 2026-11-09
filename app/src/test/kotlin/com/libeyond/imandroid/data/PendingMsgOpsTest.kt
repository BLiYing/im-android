package com.libeyond.imandroid.data

import com.libeyond.imandroid.sdk.protocol.ErrCode
import com.libeyond.imandroid.sdk.protocol.ErrorData
import com.libeyond.imandroid.sdk.protocol.MsgOp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** error 帧带 client_msg_id：是我发起的 msg_op 被拒（编辑命中敏感词等），还是某条 send_msg 被拒。 */
class PendingMsgOpsTest {
    @Test fun `命中登记过的操作 cid 才算操作被拒，且只认一次`() {
        val p = PendingMsgOps()
        p.register("op-1", MsgOp.EDIT, "c1", 42)
        val r = p.take(ErrorData(ErrCode.CONTENT_BLOCKED, "blocked", "op-1"))
        assertNotNull(r)
        assertEquals(MsgOpRejection("op-1", MsgOp.EDIT, "c1", 42, ErrCode.CONTENT_BLOCKED, "blocked"), r)
        assertNull(p.take(ErrorData(ErrCode.CONTENT_BLOCKED, "blocked", "op-1")))
    }

    @Test fun `send_msg 的拒绝与无 cid 的 error 不被当成操作吞掉`() {
        val p = PendingMsgOps()
        p.register("op-1", MsgOp.EDIT, "c1", 42)
        assertFalse(p.reject(ErrorData(ErrCode.CONTENT_BLOCKED, "blocked", "send-9")))
        assertFalse(p.reject(ErrorData(ErrCode.CONTENT_BLOCKED, "blocked", null)))
        assertTrue(p.reject(ErrorData(ErrCode.CONTENT_BLOCKED, "blocked", "op-1")))
    }

    @Test fun `登记表封顶，最旧的被挤出`() {
        val p = PendingMsgOps()
        repeat(PendingMsgOps.CAP + 1) { p.register("op-$it", MsgOp.PIN, "c", it.toLong()) }
        assertNull(p.take(ErrorData(1, "", "op-0")))
        assertNotNull(p.take(ErrorData(1, "", "op-${PendingMsgOps.CAP}")))
    }

    @Test fun `提示文案：100004 本地化，未收录码回退服务端原文，再回退操作失败`() {
        fun r(code: Int, msg: String) = MsgOpRejection("x", MsgOp.EDIT, "c", 1, code, msg)
        assertEquals("内容包含不允许发送的词语", r(ErrCode.CONTENT_BLOCKED, "content blocked").userText())
        assertEquals("server says no", r(999999, "server says no").userText())
        assertEquals(com.libeyond.imandroid.i18n.Str.s(com.libeyond.imandroid.R.string.common_action_failed), r(999999, "").userText())
    }
}
