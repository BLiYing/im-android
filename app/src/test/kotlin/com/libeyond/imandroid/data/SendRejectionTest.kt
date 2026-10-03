package com.libeyond.imandroid.data

import com.libeyond.imandroid.R
import com.libeyond.imandroid.sdk.protocol.ErrCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 拒收说明行：哪些码给说明（不给重发），哪个码给「发送好友申请」。 */
class SendRejectionTest {
    @Test fun `明确拒收的码都有文案`() {
        assertEquals(R.string.err_200103, SendRejection.noteRes(ErrCode.NOT_FRIEND))
        assertEquals(R.string.err_300004, SendRejection.noteRes(ErrCode.ACCOUNT_MUTED))
        assertEquals(R.string.chat_input_disabled_mute_all, SendRejection.noteRes(ErrCode.GROUP_MUTED))
        assertEquals(R.string.chat_input_disabled_muted, SendRejection.noteRes(ErrCode.GROUP_MEMBER_MUTED))
        assertEquals(R.string.err_300203, SendRejection.noteRes(ErrCode.NOT_GROUP_MEMBER))
        assertEquals(R.string.err_200102, SendRejection.noteRes(ErrCode.FRIEND_BLOCKED))
    }

    @Test fun `超时网络等未知码不给说明，仍走重发`() {
        assertNull(SendRejection.noteRes(0))
        assertNull(SendRejection.noteRes(100003))
    }

    @Test fun `只有非好友可自助恢复，拉黑绝不给入口`() {
        assertTrue(SendRejection.isActionable(ErrCode.NOT_FRIEND))
        assertFalse(SendRejection.isActionable(ErrCode.FRIEND_BLOCKED))
        assertFalse(SendRejection.isActionable(ErrCode.ACCOUNT_MUTED))
    }
}
