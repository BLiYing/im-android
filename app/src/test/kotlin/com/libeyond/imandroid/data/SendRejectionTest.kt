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
        assertEquals(R.string.chat_reject_not_friend, SendRejection.noteRes(ErrCode.NOT_FRIEND))
        assertEquals(R.string.chat_reject_too_large, SendRejection.noteRes(ErrCode.MSG_TOO_LONG))
        assertEquals(R.string.err_300004, SendRejection.noteRes(ErrCode.ACCOUNT_MUTED))
        assertEquals(R.string.chat_input_disabled_mute_all, SendRejection.noteRes(ErrCode.GROUP_MUTED))
        assertEquals(R.string.chat_input_disabled_muted, SendRejection.noteRes(ErrCode.GROUP_MEMBER_MUTED))
        assertEquals(R.string.err_300203, SendRejection.noteRes(ErrCode.NOT_GROUP_MEMBER))
        assertEquals(R.string.chat_reject_blocked, SendRejection.noteRes(ErrCode.FRIEND_BLOCKED))
    }

    @Test fun `上传策略拒绝给说明（重发必再被拒），不给自助动作`() {
        assertEquals(R.string.err_500003, SendRejection.noteRes(ErrCode.UPLOAD_DISABLED))
        assertEquals(R.string.upload_precheck_blocked_type, SendRejection.noteRes(ErrCode.FILE_TYPE_INVALID))
        assertEquals(R.string.upload_precheck_too_large, SendRejection.noteRes(ErrCode.FILE_TOO_LARGE))
        assertFalse(SendRejection.isActionable(ErrCode.UPLOAD_DISABLED))
    }

    @Test fun `文件超限说明行写当前上限（不是「消息内容过大」）`() {
        val mb = 1L shl 20
        assertEquals("文件超过 100 MB，无法发送", SendRejection.noteText(ErrCode.FILE_TOO_LARGE, ServerLimits(uploadMaxFileBytes = 100 * mb)))
        assertEquals("文件超过 2 GB，无法发送", SendRejection.noteText(ErrCode.FILE_TOO_LARGE, ServerLimits()))
        assertEquals("消息内容过大，无法发送", SendRejection.noteText(ErrCode.MSG_TOO_LONG))
        assertEquals("管理员已暂停发送文件", SendRejection.noteText(ErrCode.UPLOAD_DISABLED))
        assertNull(SendRejection.noteText(0))
    }

    @Test fun `敏感词拦截（100004）给专用说明且不可重发、无自助动作`() {
        assertEquals(R.string.chat_reject_sensitive, SendRejection.noteRes(ErrCode.CONTENT_BLOCKED))
        assertEquals("内容包含不允许发送的词语", SendRejection.noteText(ErrCode.CONTENT_BLOCKED))
        assertFalse(SendRejection.isActionable(ErrCode.CONTENT_BLOCKED))
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
