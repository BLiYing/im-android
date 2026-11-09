package com.libeyond.imandroid.ui

import com.libeyond.imandroid.R
import com.libeyond.imandroid.i18n.Str
import com.libeyond.imandroid.sdk.http.ApiException
import com.libeyond.imandroid.sdk.protocol.ErrCode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** 编辑框被敏感词拦下（100004）时留住用户写的字；群管理失败提示直接说原因。 */
class BlockedDraftsTest {
    private fun blocked(): Nothing = throw ApiException(ErrCode.CONTENT_BLOCKED, "content blocked")

    @Test fun `100004 留住草稿并原样抛出，下次打开用草稿`() {
        val d = BlockedDrafts()
        val e = assertThrows(ApiException::class.java) { runBlocking { d.keepIfBlocked("EditName", "新群名") { blocked() } } }
        assertEquals(ErrCode.CONTENT_BLOCKED, e.code)
        assertEquals("新群名", d.initial("EditName", "旧群名"))
        assertEquals("旧简介", d.initial("EditIntro", "旧简介"))
    }

    @Test fun `其它失败不留草稿，成功清掉草稿`() {
        val d = BlockedDrafts()
        assertThrows(ApiException::class.java) {
            runBlocking { d.keepIfBlocked<Unit>("k", "x") { throw ApiException(ErrCode.NO_GROUP_PERMISSION, "no") } }
        }
        assertEquals("旧", d.initial("k", "旧"))
        assertThrows(ApiException::class.java) { runBlocking { d.keepIfBlocked("k", "x") { blocked() } } }
        runBlocking { d.keepIfBlocked("k", "y") { } }
        assertEquals("旧", d.initial("k", "旧"))
    }

    @Test fun `群管理失败：100004 直接说原因，其它码照旧带码`() {
        assertEquals(Str.s(R.string.err_100004), groupManageFailureText("修改群名", ErrCode.CONTENT_BLOCKED))
        assertEquals(Str.s(R.string.group_manage_op_failed_with_code, "修改群名", ErrCode.NO_GROUP_PERMISSION),
            groupManageFailureText("修改群名", ErrCode.NO_GROUP_PERMISSION))
        assertEquals(Str.s(R.string.group_manage_op_failed, "修改群名"), groupManageFailureText("修改群名", null))
    }
}
