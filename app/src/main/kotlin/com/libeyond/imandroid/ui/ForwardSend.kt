package com.libeyond.imandroid.ui

import com.libeyond.imandroid.data.Forward
import com.libeyond.imandroid.data.SelectionActions
import com.libeyond.imandroid.data.db.ConversationEntity
import com.libeyond.imandroid.data.db.MessageEntity
import com.libeyond.imandroid.sdk.IMClient
import java.util.UUID

/**
 * 把若干条消息**逐条**转发到若干个会话，回一句给用户看的话。
 *
 * **逐条、逐会话串行发**：服务端对 `send_msg` 有限流，9 个会话 × 100 条并发打过去必然撞墙。
 *
 * 抽出来是因为它有三个调用方（聊天页长按转发 / 多选「逐条转发」/ 详情页归档长按转发）。各写一遍的代价不是
 * 重复代码，是**限流纪律与 `forwardFrom` 口径会分叉**——而分叉的表现是"从某个入口转发出去的
 * 消息少了『转发自 X』"，编译与测试都看不出来。
 *
 * 合并转发（一张 `chat_record` 卡片）不走这里，见 `ChatSelectionActions.kt` 的 [ForwardPickerLayer]。
 *
 * @param expiredSkipped 调用方已经滤掉的失效媒体条数，拼进回执里如实告诉用户。
 */
internal suspend fun forwardMessages(
    client: IMClient,
    msgs: List<MessageEntity>,
    targets: List<ConversationEntity>,
    expiredSkipped: Int = 0,
): String {
    if (msgs.isEmpty() || targets.isEmpty()) return ""
    val owner = client.uid.orEmpty()
    val myName = client.myPublicName()
    for (t in targets) {
        val to = if (t.isGroup) "" else t.peerUid
        // 同一原相册选了 ≥2 张 → 每个目标会话各发一个新 group_id，收端重新聚成宫格（iOS/Web 同）
        val albums = SelectionActions.regroupAlbums(msgs) { "alb-${UUID.randomUUID()}" }
        for (m in msgs) {
            client.messages.forward(
                msg = m, toConvId = t.convId, to = to,
                origin = Forward.originOf(m, owner, myName),
                groupId = albums[m.convSeq],
            )
        }
    }
    return SelectionActions.forwardDoneText(targets.size, expiredSkipped)
}
