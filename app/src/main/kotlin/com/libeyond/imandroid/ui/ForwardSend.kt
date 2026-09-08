package com.libeyond.imandroid.ui

import com.libeyond.imandroid.data.Forward
import com.libeyond.imandroid.data.db.ConversationEntity
import com.libeyond.imandroid.data.db.MessageEntity
import com.libeyond.imandroid.sdk.IMClient

/**
 * 把若干条消息转发到若干个会话，回一句给用户看的话。
 *
 * **逐条、逐会话串行发**：服务端对 `send_msg` 有限流，9 个会话 × 100 条并发打过去必然撞墙。
 *
 * 抽出来是因为它有两个调用方（聊天页长按转发 / 详情页归档长按转发）。各写一遍的代价不是
 * 重复代码，是**限流纪律与 `forwardFrom` 口径会分叉**——而分叉的表现是"从某个入口转发出去的
 * 消息少了『转发自 X』"，编译与测试都看不出来。
 *
 * 合并转发（`chat_record` 一张卡片）本端还没做，这里恒为逐条转发。
 */
internal suspend fun forwardMessages(
    client: IMClient,
    msgs: List<MessageEntity>,
    targets: List<ConversationEntity>,
): String {
    if (msgs.isEmpty() || targets.isEmpty()) return ""
    val owner = client.uid.orEmpty()
    val myName = client.myPublicName()
    for (t in targets) {
        val to = if (t.isGroup) "" else t.peerUid
        for (m in msgs) {
            client.messages.forward(
                msg = m, toConvId = t.convId, to = to,
                origin = Forward.originOf(m, owner, myName),
            )
        }
    }
    return if (targets.size > 1) "已转发到 ${targets.size} 个会话" else "已转发"
}
