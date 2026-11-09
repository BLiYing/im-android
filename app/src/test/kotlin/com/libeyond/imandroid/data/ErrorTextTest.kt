package com.libeyond.imandroid.data

import com.libeyond.imandroid.R
import com.libeyond.imandroid.sdk.protocol.ErrCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import com.libeyond.imandroid.i18n.Str
import com.libeyond.imandroid.sdk.http.ApiException
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
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

    private fun lockData(v: JsonPrimitive) = buildJsonObject { put("retry_after_sec", v) }

    @Test fun `锁定分钟数 = max(1, ceil(秒÷60))；接受小数与 0；缺失或非数字为 null`() {
        assertEquals(15L, ErrorText.loginLockMinutes(lockData(JsonPrimitive(900))))
        assertEquals(16L, ErrorText.loginLockMinutes(lockData(JsonPrimitive(901))))
        assertEquals(1L, ErrorText.loginLockMinutes(lockData(JsonPrimitive(59))))
        assertEquals(1L, ErrorText.loginLockMinutes(lockData(JsonPrimitive(1))))
        assertEquals(1L, ErrorText.loginLockMinutes(lockData(JsonPrimitive(0))))
        assertEquals(1L, ErrorText.loginLockMinutes(lockData(JsonPrimitive(0.5))))
        assertEquals(2L, ErrorText.loginLockMinutes(lockData(JsonPrimitive(60.5))))
        assertEquals(1L, ErrorText.loginLockMinutes(lockData(JsonPrimitive(60.0))))
        assertNull(ErrorText.loginLockMinutes(lockData(JsonPrimitive(-1))))
        assertNull(ErrorText.loginLockMinutes(lockData(JsonPrimitive("x"))))
        assertNull("字符串形式的数字不算数字", ErrorText.loginLockMinutes(lockData(JsonPrimitive("120"))))
        assertNull(ErrorText.loginLockMinutes(lockData(JsonPrimitive(true))))
        assertNull(ErrorText.loginLockMinutes(buildJsonObject {}))
        assertNull(ErrorText.loginLockMinutes(null))
    }

    @Test fun `注册暂停走本地化`() = assertEquals(R.string.err_200007, ErrorText.friendlyRes(ErrCode.REGISTER_CLOSED))

    @Test fun `friendlyText：200006 带分钟数，无 data 回通用 100002 文案而非服务端原文`() {
        val e = ApiException(ErrCode.LOGIN_LOCKED, "too many failed logins", data = lockData(JsonPrimitive(120)))
        assertEquals(Str.s(R.string.err_200006, 2L), ErrorText.friendlyText(e))
        assertEquals(Str.s(R.string.err_100002), ErrorText.friendlyText(ApiException(ErrCode.LOGIN_LOCKED, "too many failed logins")))
        assertEquals(Str.s(R.string.err_100002), ErrorText.friendlyText(ApiException(ErrCode.LOGIN_LOCKED, "x", data = lockData(JsonPrimitive("x")))))
        assertEquals(Str.s(R.string.err_200007), ErrorText.friendlyText(ApiException(ErrCode.REGISTER_CLOSED, "x")))
        assertNull(ErrorText.friendlyText(ApiException(999999, "x")))
    }
}
