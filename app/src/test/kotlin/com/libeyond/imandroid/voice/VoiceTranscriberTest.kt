package com.libeyond.imandroid.voice

import com.libeyond.imandroid.sdk.protocol.VoiceTranscriptData
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 语音转文字的展开/折叠/缓存编排（VOICE_TRANSCRIBE_DESIGN.md），对齐 iOS `IMVoiceTranscriber`、
 * Web `useVoiceTranscript` 的判据（见 [VoiceTranscriber] 类注释）。
 *
 * `Dispatchers.setMain(UnconfinedTestDispatcher())`：[VoiceTranscriber] 内部用
 * `Dispatchers.Main.immediate` 起协程（与 [VoicePlayer] 同一套写法），换成 Unconfined
 * 才能在纯 JVM 单测里立即跑到第一个挂起点，断言不用等真实调度。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VoiceTranscriberTest {

    private class MemKv : VoiceKv {
        val map = HashMap<String, String>()
        override fun get(key: String) = map[key]
        override fun put(key: String, value: String) { map[key] = value }
    }

    @Before
    fun setupMain() { Dispatchers.setMain(UnconfinedTestDispatcher()) }

    @After
    fun tearDownMain() { Dispatchers.resetMain() }

    /** 按调用顺序吐预设结果的假识别接口；[calls] 记调用次数供去重断言。 */
    private class FakeTranscribe {
        var calls = 0
        var result: VoiceTranscriptData = VoiceTranscriptData(status = "pending")
        var throwing: Exception? = null
        val invoke: suspend (String, Long) -> VoiceTranscriptData = { _, _ ->
            calls++
            throwing?.let { throw it } ?: result
        }
    }

    private fun transcriber(
        api: FakeTranscribe = FakeTranscribe(),
        content: String = "/uploads/a.m4a",
    ) = Triple(
        VoiceTranscriber(api.invoke, MemKv()) { _, _ -> content },
        api,
        content,
    )

    @Test
    fun `点转文字先显识别中，REST 直接回 done 就落定并缓存`() {
        val (t, api, _) = transcriber()
        api.result = VoiceTranscriptData(status = "done", text = "你好世界")
        assertFalse(t.isExpanded(5))
        t.toggle("c1", 5, "/uploads/a.m4a")
        assertTrue(t.isExpanded(5))
        assertEquals(VoiceTranscript.Done("你好世界"), t.state.value["seq:5"])
        assertEquals(1, api.calls)
    }

    @Test
    fun `命中本地缓存秒出，不发请求`() {
        val (t, api, content) = transcriber()
        api.result = VoiceTranscriptData(status = "done", text = "你好")
        t.toggle("c1", 5, content) // 第一次：走网络，落缓存
        assertEquals(1, api.calls)
        t.toggle("c1", 5, content) // 收起
        assertFalse(t.isExpanded(5))
        t.toggle("c1", 5, content) // 再点：缓存命中，不该再发一遍
        assertTrue(t.isExpanded(5))
        assertEquals(1, api.calls)
    }

    @Test
    fun `取消转文字只收面板，缓存与请求计数不受影响`() {
        val (t, api, content) = transcriber()
        api.result = VoiceTranscriptData(status = "done", text = "文本")
        t.toggle("c1", 5, content)
        assertTrue(t.isExpanded(5))
        t.toggle("c1", 5, content)
        assertFalse(t.isExpanded(5))
        assertNull(t.state.value["seq:5"])
        assertEquals(1, api.calls)
    }

    @Test
    fun `识别中途取消，随后到达的结果不重新撑开面板`() {
        val (t, api, content) = transcriber()
        api.result = VoiceTranscriptData(status = "pending")
        t.toggle("c1", 5, content) // 发起，状态 pending，本地先显 Loading
        assertTrue(t.isExpanded(5))
        t.toggle("c1", 5, content) // 等结果期间取消
        assertFalse(t.isExpanded(5))
        // WS 帧姗姗来迟：done 但用户已经折叠过，不该被重新撑开
        t.applyRemote(VoiceTranscriptData(convId = "c1", convSeq = 5, status = "done", text = "迟到的文本"))
        assertFalse(t.isExpanded(5))
        assertNull(t.state.value["seq:5"])
    }

    @Test
    fun `请求真正在途时取消，结果回来不重新撑开面板`() {
        val content = "/uploads/a.m4a"
        val deferred = CompletableDeferred<VoiceTranscriptData>()
        var calls = 0
        val t = VoiceTranscriber(
            transcribe = { _, _ -> calls++; deferred.await() },
            kv = MemKv(),
        ) { _, _ -> content }

        t.toggle("c1", 5, content) // 挂起在 deferred.await()，_state 已经是 Loading
        assertTrue(t.isExpanded(5))
        assertEquals(1, calls)

        t.toggle("c1", 5, content) // 真·等结果期间取消
        assertFalse(t.isExpanded(5))

        deferred.complete(VoiceTranscriptData(status = "done", text = "迟到的文本")) // 结果这才到
        assertFalse("不该被迟到的结果重新撑开", t.isExpanded(5))
        assertNull(t.state.value["seq:5"])
    }

    @Test
    fun `WS 帧落地按 convSeq 反查 content 落缓存，同一内容换个坐标也秒出`() {
        val (t, api, content) = transcriber(content = "/uploads/b.m4a")
        t.applyRemote(VoiceTranscriptData(convId = "c1", convSeq = 9, status = "done", text = "服务端识别结果"))
        assertEquals(VoiceTranscript.Done("服务端识别结果"), t.state.value["seq:9"])
        // 缓存按内容去重：同一段音频（content 相同）换个会话坐标点「转文字」，命中缓存直接出文本
        t.toggle("c2", 1, content)
        assertEquals(VoiceTranscript.Done("服务端识别结果"), t.state.value["seq:1"])
        assertEquals(0, api.calls)
    }

    @Test
    fun `识别失败收起面板并报错`() {
        val (t, api, content) = transcriber()
        api.result = VoiceTranscriptData(status = "failed")
        t.toggle("c1", 5, content)
        // errors 是 SharedFlow，这里只断言状态被收起；toast 文案本身由 Str.s 给，不在这条断言范围
        assertFalse(t.isExpanded(5))
        assertNull(t.state.value["seq:5"])
    }

    @Test
    fun `请求异常收起面板`() {
        val (t, api, content) = transcriber()
        api.throwing = RuntimeException("network down")
        t.toggle("c1", 5, content)
        assertFalse(t.isExpanded(5))
    }

    @Test
    fun `convSeq 小于等于 0 不做任何事`() {
        val (t, api, content) = transcriber()
        t.toggle("c1", 0, content)
        assertFalse(t.isExpanded(0))
        assertEquals(0, api.calls)
    }
}
