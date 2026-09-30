package com.libeyond.imandroid.data

import com.libeyond.imandroid.sdk.logging.IMLog
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * [AccountNotifySettingsStore] 的编排：迁移（exists=false）、覆盖（exists=true）、推送去重、
 * 本地编辑失败不回滚只标脏、脏了下次启动补 PUT、切账号作废在途应答。
 */
class AccountNotifySettingsStoreTest {

    private val localDefault = AccountNotifyFields()
    private val localCustom = AccountNotifyFields(private = NotifTypeSettings(enabled = false, sound = NotifSound.DROP))
    private val serverFields = AccountNotifyFields(group = NotifTypeSettings(preview = false, sound = NotifSound.CHIME))

    @Before
    fun quietLogs() = IMLog.useSinksForTest()

    /** 按调用顺序吐预设结果的假服务端 + 一份充当 [NotificationSettingsStore] 的本地镜像。 */
    private class Fake(initialLocal: AccountNotifyFields) {
        val fetches = ArrayDeque<suspend () -> NotifySettingsResponse>()
        val puts = ArrayDeque<suspend (AccountNotifyFields) -> NotifySettingsResponse>()
        var fetchCount = 0
        var putCount = 0
        var local = initialLocal

        fun store() = AccountNotifySettingsStore(
            fetch = { fetchCount++; fetches.removeFirst()() },
            put = { putCount++; puts.removeFirst()(it) },
            localFields = { local },
            applyLocal = { local = it },
        )
    }

    @Test
    fun `exists=false 时把本地现值迁移上去`() = runTest {
        val f = Fake(localCustom)
        f.fetches += { NotifySettingsResponse(0, exists = false, fields = localCustom) }
        // 服务端在存的这一刻规整了 sound（这里假装原样接受，验证迁移确实发了 PUT）
        f.puts += { sent -> NotifySettingsResponse(1, exists = true, fields = sent) }
        val store = f.store()
        store.start("t")
        assertEquals(1, f.putCount)
        assertEquals(AccountNotifyState(1, localCustom, dirty = false), store.state.value)
        assertEquals(localCustom, f.local)
    }

    @Test
    fun `exists=true 时覆盖本地为服务端值`() = runTest {
        val f = Fake(localCustom)
        f.fetches += { NotifySettingsResponse(5, exists = true, fields = serverFields) }
        val store = f.store()
        store.start("t")
        assertEquals(0, f.putCount)
        assertEquals(AccountNotifyState(5, serverFields, dirty = false), store.state.value)
        assertEquals("覆盖本地", serverFields, f.local)
    }

    @Test
    fun `推送帧版本没变就不发请求，版本更新才重拉`() = runTest {
        val f = Fake(localDefault)
        f.fetches += { NotifySettingsResponse(3, exists = true, fields = serverFields) }
        val store = f.store()
        store.start("t")
        store.onPushed(3)
        assertEquals(1, f.fetchCount)
        f.fetches += { NotifySettingsResponse(4, exists = true, fields = localCustom) }
        store.onPushed(4)
        assertEquals(2, f.fetchCount)
        assertEquals(localCustom, f.local)
    }

    @Test
    fun `本地编辑立刻生效并 PUT，采纳服务端规整后的值`() = runTest {
        val f = Fake(localDefault)
        f.fetches += { NotifySettingsResponse(1, exists = true, fields = localDefault) }
        f.puts += { NotifySettingsResponse(2, exists = true, fields = serverFields) }
        val store = f.store()
        store.start("t")
        assertTrue(store.save(localCustom))
        // 本地立刻生效用的是调用方传入的值；PUT 应答回来后再被服务端规整值覆盖
        assertEquals(AccountNotifyState(2, serverFields, dirty = false), store.state.value)
        assertEquals(serverFields, f.local)
    }

    @Test
    fun `没改就不发 PUT`() = runTest {
        val f = Fake(localDefault)
        f.fetches += { NotifySettingsResponse(1, exists = true, fields = localDefault) }
        val store = f.store()
        store.start("t")
        assertTrue(store.save(localDefault))
        assertEquals(0, f.putCount)
    }

    @Test
    fun `本地编辑失败——不回滚，标脏，下次启动补 PUT`() = runTest {
        val f = Fake(localDefault)
        f.fetches += { NotifySettingsResponse(1, exists = true, fields = localDefault) }
        f.puts += { throw IOException("offline") }
        val store = f.store()
        store.start("t")

        assertFalse(store.save(localCustom))
        // 失败不回滚：本地已经是用户想要的新值，且状态标脏
        assertEquals(localCustom, f.local)
        assertTrue(store.state.value.dirty)
        assertEquals(localCustom, store.state.value.fields)

        // 下次冷启动/重连：脏了直接补 PUT，不发 GET
        f.puts += { sent -> NotifySettingsResponse(2, exists = true, fields = sent) }
        store.start("reconnect")
        assertEquals(1, f.fetchCount) // 仍是第一次 start 里那一次，重试没有再 GET
        assertEquals(2, f.putCount)
        assertFalse(store.state.value.dirty)
        assertEquals(AccountNotifyState(2, localCustom, dirty = false), store.state.value)
    }

    @Test
    fun `脏了再次重试仍失败——继续标脏`() = runTest {
        val f = Fake(localDefault)
        f.fetches += { NotifySettingsResponse(1, exists = true, fields = localDefault) }
        f.puts += { throw IOException("offline") }
        val store = f.store()
        store.start("t")
        store.save(localCustom)

        f.puts += { throw IOException("still offline") }
        store.start("reconnect")
        assertTrue(store.state.value.dirty)
        assertEquals(1, f.fetchCount)
    }

    @Test
    fun `退出登录——回到未同步态，本地三项退回默认，在途应答作废`() = runTest {
        val f = Fake(localCustom)
        f.fetches += { NotifySettingsResponse(1, exists = true, fields = serverFields) }
        val store = f.store()
        store.start("t")
        assertEquals(serverFields, f.local)

        store.forget()
        assertEquals(AccountNotifyState(AccountNotifySettingsSync.UNKNOWN, AccountNotifyFields(), dirty = false), store.state.value)
        assertEquals("forget 要把本地也退回默认，否则下一个账号先看见这个账号的设置", AccountNotifyFields(), f.local)
    }
}
