package com.libeyond.imandroid.data

import com.libeyond.imandroid.R
import com.libeyond.imandroid.data.db.ConversationEntity
import com.libeyond.imandroid.i18n.Str
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 应用内横幅的展示内容（`../../IMServer/docs/design/NOTIFICATIONS_P1_DESIGN.md` §1.2）。
 *
 * 头像种子/标题/正文全部**预先算好**存进来——UI 层（`ui/components/InAppBanner.kt`）只管画和计时，
 * 不反过来引用 repo/名字解析这些数据层的东西（CLAUDE.md「SDK 与 UI 分层」）。
 *
 * @param token 自增序号。同一个会话连续来两条不同内容的消息也要"原地换内容、重新计时"，
 *   仅靠 [convId] 当 key 在两条都属于同一会话时不会触发 UI 层重开计时器，必须另有一个每次都变的值。
 */
data class BannerContent(
    val convId: String,
    /** 头像种子：群聊=convId、私聊=peerUid，与会话列表/转发选择页同一口径。 */
    val avatarSeed: String,
    val avatarUrl: String,
    val title: String,
    val body: String,
    val token: Long = 0,
)

/**
 * 应用内横幅标题/正文的格式化——纯函数，独立可测（NOTIFICATIONS_P1_DESIGN §1.2 表格）。
 *
 * - **标题**：与会话列表行同一来源（[ConversationEntity.title]，取不到回 convId；
 *   `ConversationRow` 里就是这个口径，不是另起一份）。
 * - **正文**：该类型「消息预览」关 → 固定文案 [R.string.notif_preview_hidden]（第一期已有）；
 *   开 → 复用会话列表最后一条预览的格式化函数 [ConversationPreview.of]——群聊那边已经是
 *   "发送者：摘要"的形状（[ConversationPreview] 类注释），横幅不用再另拼一次。
 * - **头像**：系统通知会话（[DetailActions.SYSTEM_UID]）会被 `IMAvatar` 按种子自动识别显示应用图标，
 *   这里不用特殊处理，跟其它调用方一样把 uid/convId 当种子传过去就行。
 */
object BannerFormat {
    fun of(
        conv: ConversationEntity,
        previewOn: Boolean,
        myUid: String,
        /** 本机对某 uid 的显示名解析；喂给 [ConversationPreview.of] 拼群聊"昵称: "前缀。
         *  这条路径不在 Compose 里、够不到好友表，默认 `{ null }`——[ConversationPreview.of]
         *  对此有现成的退化路径（回退服务端快照昵称），与 `ChatRowView` 等处的默认值同一口径。 */
        nameOf: (String) -> String? = { null },
    ): BannerContent {
        val title = Forward.titleOf(conv, nameOf)
        val body = if (previewOn) ConversationPreview.of(conv, myUid, nameOf) else Str.s(R.string.notif_preview_hidden)
        val seed = if (conv.isGroup) conv.convId else conv.peerUid.ifBlank { conv.convId }
        return BannerContent(convId = conv.convId, avatarSeed = seed, avatarUrl = conv.avatarUrl, title = title, body = body)
    }
}

/**
 * 应用内横幅当前展示内容的唯一持有者。数据层（[IncomingAlert]）只管调 [show]，
 * UI 层（`ui/components/InAppBanner.kt`）订阅 [current] 画横幅、算计时器、处理点击/上滑——
 * 数据层不能直接调 UI（CODING_STYLE 分层），这是两者之间唯一的挂钩。
 */
object InAppBannerStore {
    private val _current = MutableStateFlow<BannerContent?>(null)
    val current: StateFlow<BannerContent?> = _current.asStateFlow()
    private var seq = 0L

    /** 新横幅到达：不管当前有没有正显示的，一律原地替换并重新计时（§1.2「不叠第二条、不排队」）。 */
    fun show(content: BannerContent) {
        seq += 1
        _current.value = content.copy(token = seq)
    }

    fun dismiss() {
        _current.value = null
    }

    /** 若横幅正显示的恰是这个会话，收起它（§1.2「进入该会话」）——[ui.MainScreen] 在 `openConv` 变化时调。 */
    fun dismissIfShowing(convId: String) {
        if (_current.value?.convId == convId) _current.value = null
    }
}
