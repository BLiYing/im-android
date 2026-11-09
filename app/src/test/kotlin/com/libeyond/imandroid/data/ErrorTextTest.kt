package com.libeyond.imandroid.data

import com.libeyond.imandroid.R
import com.libeyond.imandroid.sdk.protocol.ErrCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 业务码 → 本地化文案（对齐 iOS `IMFriendlyMessageForCode`）。 */
class ErrorTextTest {
    @Test fun `好友申请已在待处理走本地化而非服务端英文`() =
        assertEquals(R.string.err_200105, ErrorText.friendlyRes(ErrCode.FRIEND_REQUEST_PENDING))

    @Test fun `两个 token 码都归到登录失效`() {
        assertEquals(R.string.common_login_expired, ErrorText.friendlyRes(ErrCode.TOKEN_INVALID))
        assertEquals(R.string.common_login_expired, ErrorText.friendlyRes(ErrCode.TOKEN_EXPIRED))
    }

    @Test fun `被拉黑用模糊文案`() = assertEquals(R.string.err_200102, ErrorText.friendlyRes(ErrCode.FRIEND_BLOCKED))

    @Test fun `无权限与入群待审刻意不映射`() {
        assertNull(ErrorText.friendlyRes(ErrCode.NO_GROUP_PERMISSION)) // 服务端带具体原因，透传
        assertNull(ErrorText.friendlyRes(ErrCode.GROUP_JOIN_PENDING)) // UI 走待审批分支
    }

    @Test fun `语音转文字三个码走专用文案`() {
        assertEquals(R.string.err_500101, ErrorText.friendlyRes(ErrCode.TRANSCRIBE_DISABLED))
        assertEquals(R.string.err_500102, ErrorText.friendlyRes(ErrCode.TRANSCRIBE_FAILED))
        assertEquals(R.string.err_500103, ErrorText.friendlyRes(ErrCode.TRANSCRIBE_BUSY))
    }

    @Test fun `上传暂停走本地化；500001 500002 不映射（头像接口也回，文案不适用）`() {
        assertEquals(R.string.err_500003, ErrorText.friendlyRes(ErrCode.UPLOAD_DISABLED))
        assertNull(ErrorText.friendlyRes(ErrCode.FILE_TOO_LARGE))
        assertNull(ErrorText.friendlyRes(ErrCode.FILE_TYPE_INVALID))
    }

    @Test fun `未收录的码返回 null 回退服务端原文`() = assertNull(ErrorText.friendlyRes(999999))
}
