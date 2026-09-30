package com.libeyond.imandroid.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 点系统推送通知后"待跳转到哪个会话"（M5 批次 2，FCM）。 */
data class PendingNotificationTarget(val convId: String, val title: String, val token: Long)

/**
 * 点系统推送通知后的待跳转状态——**设计意图参照 iOS `IMPendingNotificationRoute`**
 * （`../../IMProgram/IMProgram/Common/IMPendingNotificationRoute.h/.m`），但不照抄实现：本地记一个
 * "待跳转 convId"，App 变为可交互（这里是 `ui/MainScreen.kt` 的主界面真正组合出来）时消费；会话对象
 * 本地还没同步到时，用推送 payload 里带的 `title` 现造一个占位会话（[resolveKind] 判私聊/群聊，调用方
 * 用 [com.libeyond.imandroid.sdk.IMClient.conversationStubFor]/`groupConversationStubFor` 现造，
 * 与扫码加群、资料页点「发消息」等入口同一手法——`ui/QrRouteHost.kt`/`ui/GroupInfoHost.kt` 等处已经
 * 在用），等真实数据从 sync 落库后自然替换，不需要这里另做"占位替换"逻辑。
 *
 * **只有真正确认跳转成功才能 [consume]**——iOS 那边踩过的教训：冷启动时若在导航栈建好之前就把
 * "待处理"标记清掉，这次请求就再也没有重试机会，表现成"点了通知只是打开了 App，没进会话"。
 * 本类不做"允许消费但可能失败"这种中间态：`ui/MainScreen.kt` 的消费逻辑
 * （`conversationsById[convId] ?: 用 [resolveKind] 现造占位会话`）对任意合法输入必然成功，"确认成功"
 * 因此退化成"MainScreen 这次重组真的跑到了 consume 那一行"——`owner`（当前账号）还没就绪时那侧的
 * 效果直接 `return`、不调 [consume]，账号就绪后 Compose 因为 `owner` 变化会自然重跑这段
 * `LaunchedEffect`，不需要本类自己起定时器重试。
 */
object NotificationRoute {
    private val _pending = MutableStateFlow<PendingNotificationTarget?>(null)
    val pending: StateFlow<PendingNotificationTarget?> = _pending.asStateFlow()
    private var seq = 0L

    /** [com.libeyond.imandroid.MainActivity] 收到通知点击的 intent（冷启动 extras / `onNewIntent`）时调。 */
    fun request(convId: String, title: String) {
        if (convId.isBlank()) return
        seq += 1
        _pending.value = PendingNotificationTarget(convId, title, seq)
    }

    /** 见类注释：只有 `ui/MainScreen.kt` 真的把这个会话摆上屏幕才调用。 */
    fun consume(token: Long) {
        if (_pending.value?.token == token) _pending.value = null
    }

    /** `conv_id` 判是私聊还是群聊、私聊时对端是谁——纯函数，独立可测。 */
    sealed interface Kind {
        data class Private(val peerUid: String) : Kind
        data object Group : Kind
    }

    /**
     * `conv_id` 形状：群聊 `g_...`（服务端直接给的群 ID，原样当占位会话 ID 用）；
     * 私聊 `u_<a>_u_<b>`（字典序，与 [com.libeyond.imandroid.sdk.IMClient.conversationStubFor] 的
     * 构造同构，这里反过来从 convId + 自己的 uid 解出对端）。
     * 解析不出来的（未知形状/两段都不是自己）一律当群聊处理——好歹能落地一个占位会话，不是死路。
     */
    fun resolveKind(convId: String, myUid: String): Kind {
        if (convId.startsWith(PRIVATE_PREFIX)) {
            val rest = convId.removePrefix(PRIVATE_PREFIX)
            val idx = rest.indexOf(PRIVATE_SEP)
            if (idx >= 0) {
                val a = rest.substring(0, idx)
                val b = rest.substring(idx + PRIVATE_SEP.length)
                val peer = when (myUid) {
                    a -> b
                    b -> a
                    else -> null
                }
                if (!peer.isNullOrEmpty()) return Kind.Private(peer)
            }
        }
        return Kind.Group
    }

    private const val PRIVATE_PREFIX = "u_"
    private const val PRIVATE_SEP = "_u_"
}
