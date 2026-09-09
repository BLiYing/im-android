package com.libeyond.imandroid.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.TextFieldValue
import com.libeyond.imandroid.data.Mention
import com.libeyond.imandroid.sdk.api.GroupMember
import com.libeyond.imandroid.sdk.IMClient
import com.libeyond.imandroid.sdk.logging.IMLog
import com.libeyond.imandroid.sdk.protocol.MentionSpan

/**
 * 聊天输入栏的 @提及态（M4-8，**仅群聊**）。
 *
 * 对端 iOS `IMChatViewController+Mention.m` 的 `mentionCandidates` / `mentionAllPending`，
 * im-web `useMentions.ts`。判据全部在纯函数 [Mention] 里，这里只管"记住点过谁"和面板开关。
 *
 * ### 为什么记了候选还要在发送时复核
 * 用户点过「小明」→ 文本里插进 `@小明 `，但他随后可能把这个 token 删掉。
 * 发送时以**文本里还留着什么**为准（[resolve]），不以点过谁为准——
 * 否则删掉 token 的人照样收到一条穿透免打扰的强提醒。
 */
internal class MentionComposer {

    /** uid → 插进文本时用的**公开显示名**。绝不能放我的私有备注：那会随消息发给全群。 */
    private val candidates = LinkedHashMap<String, String>()

    /** 点过「@所有人」。是否真生效仍要看文本里还有没有那个 token。 */
    private var allPending = false

    /** 当前正在输入的 @查询词；null = 不在 @ 输入态（面板不该出现）。 */
    var query by mutableStateOf<String?>(null)
        private set

    /** 面板候选（服务端按 [query] 过滤后的一页）。 */
    var members by mutableStateOf<List<GroupMember>>(emptyList())
        internal set

    /** 我能不能 @所有人（仅群主/管理员，服务端另有 300204 校验）。 */
    var canMentionAll by mutableStateOf(false)

    val panelOpen: Boolean get() = query != null && (members.isNotEmpty() || canMentionAll)

    /** 输入变化时重算 @输入态。非群聊恒关。 */
    fun onInputChanged(value: TextFieldValue, isGroup: Boolean) {
        query = if (isGroup) Mention.activeQuery(value.text, value.selection.end) else null
    }

    /** 选中一位成员 / 「@所有人」：回填 token，记入候选，关面板。 */
    fun pick(value: TextFieldValue, displayName: String, uid: String?): TextFieldValue {
        val r = Mention.applyToken(value.text, value.selection.end, displayName)
        if (uid.isNullOrEmpty()) allPending = true else candidates[uid] = displayName
        query = null
        return TextFieldValue(r.text, androidx.compose.ui.text.TextRange(r.caret))
    }

    fun dismiss() {
        query = null
    }

    /**
     * 发送前按**文本现状**算出三件套。顺序与 iOS/Web 一致：
     * 先 mentions（每个候选独立判定，重名两人都算）、再 mentionAll、最后片段
     * （片段里「所有人」覆盖同名成员，与服务端校验一致）。
     */
    fun resolve(text: String): Resolved {
        val all = Mention.resolveMentionAll(text, allPending)
        return Resolved(
            mentions = Mention.resolveMentions(text, candidates),
            mentionAll = all,
            spans = Mention.resolveSpans(text, candidates, all),
        )
    }

    /** 发出去之后复位（输入框已清空，候选与 @所有人 标记一并清掉）。 */
    fun clear() {
        candidates.clear()
        allPending = false
        query = null
    }

    data class Resolved(
        val mentions: List<String>,
        val mentionAll: Boolean,
        val spans: List<MentionSpan>,
    )
}

/**
 * 建一个 [MentionComposer] 并在 @查询词变化时拉候选成员。
 *
 * **候选走服务端 `GET /groups/{id}/members?q=`**，不是在本地成员表里过滤：
 * 超级群（2 万人）根本不下发成员表（`GroupInfo.members` 只回我自己），
 * 本地过滤在那里恒空——而那正是最需要 @ 的场景。
 */
@Composable
internal fun rememberMentionComposer(
    client: IMClient,
    convId: String,
    isGroup: Boolean,
    myRole: String?,
): MentionComposer {
    val state = remember(convId) { MentionComposer() }
    state.canMentionAll = isGroup && Mention.canMentionAll(myRole)

    LaunchedEffect(convId, state.query, isGroup) {
        val q = state.query
        if (!isGroup || q == null) {
            state.members = emptyList()
            return@LaunchedEffect
        }
        runCatchingCancellable { client.groups.members(convId, q = q, limit = MENTION_PAGE) }
            .onSuccess { state.members = it.items }
            .onFailure {
                // 拉不到就给空列表——面板要么显 @所有人 一行、要么整个不出现，
                // 不能把上一次的候选留在那儿（那会让人 @ 到一个不在这个群里的人）
                state.members = emptyList()
                IMLog.tag("IM.Mention").w("mention_members_failed", "convId" to convId)
            }
    }
    return state
}

/** 面板一次显示多少人。iOS 那侧的内联卡也是"够高时约 5 行、可滚"，这里取一页 20 够滚。 */
private const val MENTION_PAGE = 20
