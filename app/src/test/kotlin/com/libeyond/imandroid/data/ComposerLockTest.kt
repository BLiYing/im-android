package com.libeyond.imandroid.data

import com.libeyond.imandroid.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 输入栏锁判据（对齐 iOS `refreshComposerMuteState`）。 */
class ComposerLockTest {
    private val now = 1_000_000L
    private fun lock(
        isGroup: Boolean = true, peer: String = "", role: String? = "member", my: Long = 0, all: Long = 0,
    ) = ComposerLock.reasonRes(isGroup, peer, role, my, all, now)

    @Test fun `系统通知会话锁`() = assertEquals(R.string.chat_input_disabled_system, lock(false, DetailActions.SYSTEM_UID))
    @Test fun `普通单聊不锁`() = assertNull(lock(false, "1002"))
    @Test fun `普通群不锁`() = assertNull(lock())
    @Test fun `成员级禁言锁且压过全员禁言`() =
        assertEquals(R.string.chat_input_disabled_muted, lock(my = now + 5, all = -1))
    @Test fun `永久禁言锁`() = assertEquals(R.string.chat_input_disabled_muted, lock(my = -1))
    @Test fun `已过期的禁言不锁`() = assertNull(lock(my = now - 1, all = now - 1))
    @Test fun `全员禁言锁普通成员`() = assertEquals(R.string.chat_input_disabled_mute_all, lock(all = now + 5))
    @Test fun `全员禁言不锁群主和管理员`() {
        assertNull(lock(role = "owner", all = -1))
        assertNull(lock(role = "admin", all = -1))
    }
    @Test fun `管理员被单独禁言照样锁`() = assertEquals(R.string.chat_input_disabled_muted, lock(role = "admin", my = -1))
}
