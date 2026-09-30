package com.libeyond.imandroid.data

import com.libeyond.imandroid.data.NotificationPermission.TapAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationPermissionTest {
    @Test
    fun `已开启时点击直接去系统设置，不论系统版本`() {
        assertEquals(TapAction.OpenSettings, NotificationPermission.onTap(enabled = true, sdkInt = 26))
        assertEquals(TapAction.OpenSettings, NotificationPermission.onTap(enabled = true, sdkInt = 36))
    }

    @Test
    fun `未开启且 Android 13 起先申请运行时权限`() {
        assertEquals(TapAction.Request, NotificationPermission.onTap(enabled = false, sdkInt = 33))
        assertEquals(TapAction.Request, NotificationPermission.onTap(enabled = false, sdkInt = 36))
    }

    @Test
    fun `未开启且 Android 12 及以下没有运行时权限，只能引导去系统设置`() {
        assertEquals(TapAction.Explain, NotificationPermission.onTap(enabled = false, sdkInt = 32))
        assertEquals(TapAction.Explain, NotificationPermission.onTap(enabled = false, sdkInt = 26))
    }

    @Test
    fun `系统没弹框就回了拒绝，要补引导`() {
        assertTrue(NotificationPermission.shouldExplainAfterRequest(granted = false, rationaleBefore = false, rationaleAfter = false))
    }

    @Test
    fun `用户刚在系统框里点了拒绝，不再追着弹提示`() {
        // 第一次拒绝：申请后 rationale 变 true
        assertFalse(NotificationPermission.shouldExplainAfterRequest(granted = false, rationaleBefore = false, rationaleAfter = true))
        // 第二次拒绝：申请前 rationale 是 true
        assertFalse(NotificationPermission.shouldExplainAfterRequest(granted = false, rationaleBefore = true, rationaleAfter = false))
    }

    @Test
    fun `授权成功不提示`() {
        assertFalse(NotificationPermission.shouldExplainAfterRequest(granted = true, rationaleBefore = false, rationaleAfter = false))
    }
}
