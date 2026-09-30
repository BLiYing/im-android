package com.libeyond.imandroid.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AccountNotifySettingsSync] 的纯决策：GET 应答该迁移/覆盖/丢弃，冷启动/重连该补 PUT 还是该 GET。
 * 这三条决策是账号级通知设置多端同步正确性的全部逻辑，[AccountNotifySettingsStoreTest] 只负责
 * 验证编排（谁在什么时机调用了它们），判据本身钉在这里。
 */
class AccountNotifySettingsSyncTest {

    private val serverPrivate = NotifTypeSettings(enabled = false, preview = false, sound = NotifSound.CHIME)
    private val serverFields = AccountNotifyFields(private = serverPrivate)

    @Test
    fun `exists=false 时决定迁移——不管版本号多少`() {
        val resp = NotifySettingsResponse(version = 0, exists = false, fields = serverFields)
        assertEquals(NotifySyncDecision.Migrate, AccountNotifySettingsSync.decide(AccountNotifySettingsSync.UNKNOWN, resp))
        // 即使服务端版本看起来比本地"新"，exists=false 仍然是迁移分支，不是采纳分支
        assertEquals(NotifySyncDecision.Migrate, AccountNotifySettingsSync.decide(applied = 3, resp.copy(version = 9)))
    }

    @Test
    fun `exists=true 且版本可采纳——覆盖本地`() {
        val resp = NotifySettingsResponse(version = 4, exists = true, fields = serverFields)
        val decision = AccountNotifySettingsSync.decide(AccountNotifySettingsSync.UNKNOWN, resp)
        assertEquals(NotifySyncDecision.Adopt(4, serverFields), decision)
    }

    @Test
    fun `exists=true 但版本比已采纳的旧——丢弃`() {
        val resp = NotifySettingsResponse(version = 2, exists = true, fields = serverFields)
        assertEquals(NotifySyncDecision.Stale, AccountNotifySettingsSync.decide(applied = 3, resp))
    }

    @Test
    fun `exists=true 且版本相等——仍然采纳`() {
        val resp = NotifySettingsResponse(version = 3, exists = true, fields = serverFields)
        assertEquals(NotifySyncDecision.Adopt(3, serverFields), AccountNotifySettingsSync.decide(applied = 3, resp))
    }

    @Test
    fun `版本号：等于也采纳，小于才丢`() {
        assertTrue(AccountNotifySettingsSync.shouldApply(applied = 5, incoming = 5))
        assertTrue(AccountNotifySettingsSync.shouldApply(applied = AccountNotifySettingsSync.UNKNOWN, incoming = 0))
        assertFalse(AccountNotifySettingsSync.shouldApply(applied = 6, incoming = 5))
    }

    @Test
    fun `推送去重：采纳过的版本不重拉，严格更新才重拉`() {
        assertFalse(AccountNotifySettingsSync.shouldRefetch(applied = 7, pushed = 7))
        assertTrue(AccountNotifySettingsSync.shouldRefetch(applied = 7, pushed = 8))
        assertTrue(AccountNotifySettingsSync.shouldRefetch(applied = AccountNotifySettingsSync.UNKNOWN, pushed = 0))
    }

    @Test
    fun `脏了就补 PUT，不脏就正常 GET`() {
        assertEquals(NotifyTrigger.RetryPut, AccountNotifySettingsSync.onTrigger(dirty = true))
        assertEquals(NotifyTrigger.Refresh, AccountNotifySettingsSync.onTrigger(dirty = false))
    }
}
