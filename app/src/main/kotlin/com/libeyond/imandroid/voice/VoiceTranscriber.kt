package com.libeyond.imandroid.voice

import com.libeyond.imandroid.R
import com.libeyond.imandroid.i18n.Str
import com.libeyond.imandroid.sdk.protocol.VoiceTranscriptData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 一条语音此刻的转写展示态；不在这张表里 = 面板收起。 */
sealed class VoiceTranscript {
    /** 已发起识别，等结果（REST 直接回 pending，或缓存未命中的第一帧）。 */
    data object Loading : VoiceTranscript()
    data class Done(val text: String) : VoiceTranscript()
}

/**
 * 语音「转文字」（服务端识别），见 `../IMServer/docs/design/VOICE_TRANSCRIBE_DESIGN.md`，
 * 与 iOS `IMVoiceTranscriber`、Web `useVoiceTranscript` 同一份协议契约与展开/折叠语义
 * （**判据抄它们，不抄代码形状**）：
 *
 * - 面板展开态（[state]，哪些 mid 当前显示什么）是进程内存，随 App 重启清空——同 Web；
 * - 但「转写文本」与「折叠过」两个更底层的判据要落盘（[VoiceTranscriptStore]），
 *   同 iOS：杀进程重进会话，之前点过「取消转文字」的不该被缓存重新撑开；
 * - 缓存键是**音频内容**不是消息坐标：同一段语音转发/收藏多份共享一次识别结果；
 * - 「取消转文字」只收本地面板，不删服务端结果——服务端缓存按内容存、会话内共享，
 *   一个人取消不该让别人也看不到。
 *
 * `mid` 用 `"seq:<convSeq>"`（[VoiceRules.playableId] 在 `convSeq>0` 时的形态）：长按菜单
 * 「转文字」只对 `convSeq>0`（已确认）的消息出现，WS 回执按 `convSeq` 反查即可对上同一个 mid，
 * 不需要 clientMsgId。
 */
class VoiceTranscriber(
    /**
     * 发起一次识别请求（[com.libeyond.imandroid.sdk.api.VoiceApi.transcribe]）。**取函数不取那个类本身**——
     * 单测要能注入假实现，绕开真实 HTTP（本项目单测没有 mock 框架，只能靠手写 fake，
     * 与 [contentLookup]/[VoiceKv] 同一套取舍）。
     */
    private val transcribe: suspend (convId: String, convSeq: Long) -> VoiceTranscriptData,
    kv: VoiceKv,
    /**
     * 按会话坐标反查本地那条消息的 `content`（音频地址）——只有 UI/仓库层知道
     * `MessageRepository`，`voice/` 包不越层直连它，由 [com.libeyond.imandroid.sdk.IMClient] 注入。
     */
    private val contentLookup: suspend (convId: String, convSeq: Long) -> String?,
) {
    private val store = VoiceTranscriptStore(kv)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow<Map<String, VoiceTranscript>>(emptyMap())
    val state: StateFlow<Map<String, VoiceTranscript>> = _state

    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 4)
    /** 失败 toast——REST 失败与 WS `status=failed` 共用同一条文案（[R.string.chat_voice_transcribe_failed_retry]）。 */
    val errors: SharedFlow<String> = _errors.asSharedFlow()

    /** 长按菜单该显「转文字」还是「取消转文字」：这条的面板当前是不是展开着。 */
    fun isExpanded(convSeq: Long): Boolean = _state.value.containsKey(midOf(convSeq))

    /**
     * 长按菜单点「转文字」/「取消转文字」，同一个入口按当前展开态分派。
     * @param content 本地这条消息的 `content`（音频地址）——缓存键与去重靠它。
     */
    fun toggle(convId: String, convSeq: Long, content: String) {
        val mid = midOf(convSeq) ?: return
        if (_state.value.containsKey(mid)) {
            store.collapse(mid)
            _state.update { it - mid }
            return
        }
        store.expand(mid)
        val cached = store.cachedText(content)
        if (cached != null) {
            _state.update { it + (mid to VoiceTranscript.Done(cached)) }
            return
        }
        _state.update { it + (mid to VoiceTranscript.Loading) }
        scope.launch {
            try {
                val r = transcribe(convId, convSeq)
                applyResult(mid, content, r.status, r.text)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!store.isCollapsed(mid)) {
                    _state.update { it - mid }
                    _errors.tryEmit(Str.s(R.string.chat_voice_transcribe_failed_retry))
                }
            }
        }
    }

    /** WS `voice_transcript` 帧落地：按 convSeq 反查本地消息拿 content，再落缓存 + 广播。 */
    fun applyRemote(d: VoiceTranscriptData) {
        val mid = midOf(d.convSeq) ?: return
        if (store.isCollapsed(mid)) return // 等结果期间用户点了「取消转文字」
        scope.launch {
            val content = contentLookup(d.convId, d.convSeq) ?: return@launch
            applyResult(mid, content, d.status, d.text)
        }
    }

    private fun applyResult(mid: String, content: String, status: String, text: String) {
        if (status == "done" && text.isNotEmpty()) {
            store.putText(content, text)
            // 结果照落缓存（下次点开秒出），但**不广播**——否则识别中途取消的那条会被结果重新撑开。
            if (store.isCollapsed(mid)) return
            _state.update { it + (mid to VoiceTranscript.Done(text)) }
            return
        }
        if (store.isCollapsed(mid)) return
        // failed，或服务端标了 done 却没带文本（畸形响应）——两者都不该让面板永远停在「识别中…」，
        // 走同一条失败收尾（code review 抓出：done+空文本此前会落进下面的「维持」分支，永久卡住无法重试）。
        if (status == "failed" || status == "done") {
            _state.update { it - mid }
            _errors.tryEmit(Str.s(R.string.chat_voice_transcribe_failed_retry))
        }
        // pending 或空 status：维持「识别中…」（_state 已经是 Loading，不用动）
    }

    private fun midOf(convSeq: Long): String? = VoiceRules.playableId(convSeq, "")
}
