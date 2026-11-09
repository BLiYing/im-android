package com.libeyond.imandroid.data

import com.libeyond.imandroid.data.db.MessageEntity
import com.libeyond.imandroid.sdk.api.ServerConfig
import com.libeyond.imandroid.sdk.logging.IMLog
import com.libeyond.imandroid.sdk.protocol.ContentType
import com.libeyond.imandroid.sdk.protocol.ProtocolJson
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * 后台「系统设置」S2 六项（PROTOCOL `GET /server-config`）：
 * 归一（**缺失 → 默认、时间窗 0 → 不限、有值 → 用值**）、撤回 / 编辑菜单可见性、拉取节流与失败保留。
 */
class ServerConfigStoreTest {

    @Before
    fun reset() {
        IMLog.useSinksForTest()
        ServerConfigStore.beforeAdoptForTest = null
        ServerConfigStore.forget()
    }

    @After
    fun clearHook() {
        ServerConfigStore.beforeAdoptForTest = null
    }

    private fun decode(json: String) = ProtocolJson.decodeFromString(ServerConfig.serializer(), json)

    // ——— 归一 ———

    @Test
    fun `字段缺失（老服务端）用内置默认，而不是 0`() {
        val l = ServerLimits.of(decode("""{"max_group_members":500,"fcm_enabled":true}"""))
        assertEquals(ServerLimits(120, 0, 9, 100, 30, 500), l)
        // 撤回窗口缺失若读成 0 = 永远可撤回——这是这一组字段最要命的那一条
        assertEquals(120L, l.recallWindowSec)
    }

    @Test
    fun `上传策略缺失（老服务端）：开、2GiB、无禁用类型`() {
        val l = ServerLimits.of(decode("""{"max_group_members":500}"""))
        assertTrue(l.uploadEnabled)
        assertEquals(2147483648L, l.uploadMaxFileBytes)
        assertEquals(emptyList<String>(), l.uploadBlockedExtensions)
        assertEquals(l.uploadMaxFileBytes, ServerLimits.of(null).uploadMaxFileBytes)
        assertTrue(ServerLimits.of(null).uploadEnabled)
    }

    @Test
    fun `上传策略有值就用值；上限 0 或负数按缺失；扩展名归一小写去重、丢掉不合法的`() {
        val l = ServerLimits.of(
            decode("""{"upload_enabled":false,"upload_max_file_bytes":10485760,"upload_blocked_extensions":[".EXE"," .apk ",".exe","bat","."]}"""),
        )
        assertFalse(l.uploadEnabled)
        assertEquals(10485760L, l.uploadMaxFileBytes)
        assertEquals(listOf(".exe", ".apk"), l.uploadBlockedExtensions)
        assertEquals(2147483648L, ServerLimits.of(decode("""{"upload_max_file_bytes":0}""")).uploadMaxFileBytes)
        assertEquals(2147483648L, ServerLimits.of(decode("""{"upload_max_file_bytes":-1}""")).uploadMaxFileBytes)
        assertTrue(ServerLimits.of(decode("""{"upload_enabled":true}""")).uploadEnabled)
    }

    @Test
    fun `没拉到配置也是内置默认`() = assertEquals(ServerLimits(120, 0, 9, 100, 30, 500), ServerLimits.of(null))

    @Test
    fun `时间窗下发 0 是不限，不回落到默认`() {
        val l = ServerLimits.of(decode("""{"recall_window_sec":0,"edit_window_sec":0}"""))
        assertEquals(0L, l.recallWindowSec)
        assertEquals(0L, l.editWindowSec)
    }

    @Test
    fun `有值就用值`() {
        val l = ServerLimits.of(
            decode(
                """{"recall_window_sec":300,"edit_window_sec":3600,"forward_max_targets":20,""" +
                    """"merge_forward_max":50,"group_name_max_len":40,"group_announcement_max_len":800}""",
            ),
        )
        assertEquals(ServerLimits(300, 3600, 20, 50, 40, 800), l)
    }

    @Test
    fun `计数类没有「不限」：0 或负数按缺失；合并转发封顶多选上限 100`() {
        val l = ServerLimits.of(
            decode("""{"forward_max_targets":0,"merge_forward_max":500,"group_name_max_len":-1,"group_announcement_max_len":0,"recall_window_sec":-5}"""),
        )
        assertEquals(9, l.forwardMaxTargets)
        assertEquals(100, l.mergeForwardMax)
        assertEquals(30, l.groupNameMaxLen)
        assertEquals(500, l.groupAnnouncementMaxLen)
        assertEquals(120L, l.recallWindowSec)
    }

    // ——— 时间窗判定 ———

    @Test
    fun `时间窗：0 不限，边界含等于`() {
        val now = 1_000_000_000L
        assertTrue(ServerLimits.withinWindow(now - 10_000_000, now, 0))
        assertTrue(ServerLimits.withinWindow(now - 120_000, now, 120))
        assertFalse(ServerLimits.withinWindow(now - 120_001, now, 120))
    }

    private val now = 1_700_000_000_000L
    private fun mine(ago: Long, type: String = ContentType.TEXT) = MessageEntity(
        ownerUid = "me", convId = "u_me_u_x", convSeq = 5, sender = "me",
        contentType = type, content = "hi", timestamp = now - ago,
    )
    private fun acts(m: MessageEntity, recall: Long, edit: Long) =
        MessageActions.availableFor(m, "me", false, false, now, recallWindowSec = recall, editWindowSec = edit)

    @Test
    fun `撤回按配置的窗口；0 = 不限；不传 = 默认 2 分钟`() {
        assertTrue(MessageAction.Recall in acts(mine(299_000), recall = 300, edit = 0))
        assertFalse(MessageAction.Recall in acts(mine(301_000), recall = 300, edit = 0))
        assertTrue("0 = 不限", MessageAction.Recall in acts(mine(30L * 24 * 3600_000), recall = 0, edit = 0))
        assertFalse(MessageAction.Recall in MessageActions.availableFor(mine(121_000), "me", false, false, now))
        assertTrue(MessageAction.Recall in MessageActions.availableFor(mine(119_000), "me", false, false, now))
    }

    @Test
    fun `编辑超窗隐藏；0 = 不限（默认，保持现状）`() {
        assertTrue(MessageAction.Edit in acts(mine(3_599_000), recall = 120, edit = 3600))
        assertFalse(MessageAction.Edit in acts(mine(3_601_000), recall = 120, edit = 3600))
        assertTrue(MessageAction.Edit in acts(mine(400L * 24 * 3600_000), recall = 120, edit = 0))
        assertTrue(MessageAction.Edit in MessageActions.availableFor(mine(400L * 24 * 3600_000), "me", false, false, now))
    }

    // ——— 节流 / 失败保留 ———

    @Test
    fun `节流：在途不发；成功后 5 秒内不发；从没成功过随时可发`() {
        assertTrue(ServerConfigRefresh.shouldFetch(false, ServerConfigRefresh.NEVER, 0))
        assertFalse(ServerConfigRefresh.shouldFetch(true, ServerConfigRefresh.NEVER, 0))
        assertFalse(ServerConfigRefresh.shouldFetch(false, 1_000, 5_999))
        assertTrue(ServerConfigRefresh.shouldFetch(false, 1_000, 6_000))
    }

    @Test
    fun `拉取失败保留上一次的值，且不计入节流`() = runTest {
        var calls = 0
        val ok = ServerConfig(recallWindowSec = 600)
        assertEquals(ok, ServerConfigStore.refresh("t", now = 0) { calls++; ok })
        assertEquals(600L, ServerConfigStore.current.recallWindowSec)

        // 5 秒内再喊：不发请求
        assertNull(ServerConfigStore.refresh("t", now = 4_000) { calls++; ok })
        assertEquals(1, calls)

        // 过了节流期但失败：保留 600
        assertNull(ServerConfigStore.refresh("t", now = 40_000) { calls++; throw IOException("down") })
        assertEquals(600L, ServerConfigStore.current.recallWindowSec)
        // 失败不计入节流：紧接着下一次照样发
        assertEquals(ok, ServerConfigStore.refresh("t", now = 41_000) { calls++; ok })
        assertEquals(3, calls)
    }

    @Test
    fun `forget 回内置默认并清节流`() = runTest {
        ServerConfigStore.refresh("t", now = 0) { ServerConfig(recallWindowSec = 0) }
        assertEquals(0L, ServerConfigStore.current.recallWindowSec)
        ServerConfigStore.forget()
        assertEquals(ServerLimits(), ServerConfigStore.current)
        assertNull(ServerConfigStore.config.value)
        // 节流已清：立刻可以再拉
        assertEquals(60L, ServerConfigStore.refresh("t", now = 1) { ServerConfig(recallWindowSec = 60) }?.recallWindowSec)
    }

    // ——— forget 与在途请求的时序 ———

    /**
     * forget 落在「核对代次」与「写状态」之间（另一线程，用测试钩子把它插进这个窗口）：
     * 有锁时 forget 要等这次写完再执行，最终是默认值 + 节流已清；没锁时旧值会在 forget 之后写回。
     */
    @Test
    fun `forget 插在核对代次与写状态之间：旧应答绝不在 forget 之后留下状态`() = runTest {
        var forgetter: Thread? = null
        ServerConfigStore.beforeAdoptForTest = {
            ServerConfigStore.beforeAdoptForTest = null
            forgetter = Thread { ServerConfigStore.forget() }.also { it.start(); it.join(200) }
        }
        ServerConfigStore.refresh("old", now = 0) { ServerConfig(recallWindowSec = 999) }
        forgetter?.join(2_000)
        assertEquals(ServerLimits(), ServerConfigStore.current)
        assertNull(ServerConfigStore.config.value)
        // lastSuccessAt 也没被写回：新一代 1 毫秒后就能拉（否则会被节流着一直用默认值）
        var called = false
        ServerConfigStore.refresh("new", now = 1) { called = true; ServerConfig(recallWindowSec = 60) }
        assertTrue("forget 之后新一代被节流挡住了", called)
        assertEquals(60L, ServerConfigStore.current.recallWindowSec)
    }

    @Test
    fun `fetch 期间 forget：旧应答丢弃，lastSuccessAt 不写`() = runTest {
        assertNull(ServerConfigStore.refresh("old", now = 0) { ServerConfigStore.forget(); ServerConfig(recallWindowSec = 999) })
        assertEquals(ServerLimits(), ServerConfigStore.current)
        assertEquals(60L, ServerConfigStore.refresh("new", now = 1) { ServerConfig(recallWindowSec = 60) }?.recallWindowSec)
    }

    /** 登出后立刻登录：旧请求还在飞，新账号的重拉不能被它的「在途」挡住；旧请求回来也不能复位新一代的在途标志。 */
    @Test
    fun `在途按代：forget 后新一代可立即发请求，旧请求完成不影响新一代`() = runTest {
        val oldGate = CompletableDeferred<ServerConfig>()
        val newGate = CompletableDeferred<ServerConfig>()
        val old = async { ServerConfigStore.refresh("old", now = 0) { oldGate.await() } }
        yield()
        ServerConfigStore.forget()

        var newCalls = 0
        val fresh = async { ServerConfigStore.refresh("new", now = 1) { newCalls++; newGate.await() } }
        yield()
        assertEquals("forget 后新一代被旧在途挡住了", 1, newCalls)

        // 旧请求先回来：丢弃，且不复位新一代的在途标志
        oldGate.complete(ServerConfig(recallWindowSec = 999))
        assertNull(old.await())
        var dupCalls = 0
        assertNull(ServerConfigStore.refresh("dup", now = 2) { dupCalls++; ServerConfig() })
        assertEquals("新一代仍在途，不该再发", 0, dupCalls)

        newGate.complete(ServerConfig(recallWindowSec = 60))
        assertEquals(60L, fresh.await()?.recallWindowSec)
        assertEquals(60L, ServerConfigStore.current.recallWindowSec)
    }
}
