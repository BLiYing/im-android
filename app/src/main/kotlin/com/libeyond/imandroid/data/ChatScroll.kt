package com.libeyond.imandroid.data

/**
 * 聊天列表的滚动判据（贴底 / 键盘跟随 / 发送回底 / 点空白收键盘）。
 *
 * 口径照 iOS `IMChatViewController+Scroll.m` 与 `IMChatViewController.m`（源码核实，2026-09-10）：
 * `isNearBottom`（距底 < 80）、`scrollToAbsoluteBottom`（最多 6 轮收敛）、
 * `keyboardWillChange`（变化**前**贴着底才在变化后重贴）、`handleReplyJumpTap`（面板开着只收面板）。
 * 设计稿 `../IMServer/docs/design/sketches/CHAT_UI_SKETCH.html` §9。
 *
 * 抽成纯函数的理由同 [ChatEntry]：**这些判据错了不会报错**，只是列表停在了不该停的地方。
 * 动手的那一半（真去滚）在 `ui/screens/ChatScroll.kt`。
 */
object ChatScroll {

    /** 距底多少 dp 以内算「贴着底」。iOS `isNearBottom` 80pt。 */
    const val NEAR_BOTTOM_DP = 80f

    /** 贴底最多几轮。iOS `scrollToAbsoluteBottom` 6 轮——行高是异步定下来的（图片/链接卡），一轮常常差一截。 */
    const val STICK_BOTTOM_MAX_ROUNDS = 6

    /**
     * 「刚点过 ↓ / 刚发出一条」之后多久内，列表一有变化就重新贴底。
     *
     * iOS 是 `selfSendScrollGuardUntil = now + 0.5` 且换尾窗是同步的；本端换窗要等一次查库，
     * 0.5 秒常常等不到新那一窗，所以放宽到 1 秒（与 ↓ 按钮原先那道「待办保质期」同一个数）。
     */
    const val STICK_BOTTOM_ARM_MS = 1_000L

    /**
     * 列表底边还差多少像素到内容底；**最后一行不在视口里时返回 null**（= 离底很远，具体多远不可知）。
     *
     * 坐标系是 `LazyListLayoutInfo` 的：行偏移从内容区顶（不含上内边距）算起，
     * 视口终点 `viewportEndOffset` 已含下内边距，所以到底时 `行尾 + 下内边距 == 视口终点`。
     * 内容不满一屏时差值为负，按 0 算（本来就在底）。
     */
    fun distanceToBottomPx(
        totalRows: Int,
        lastVisibleIndex: Int,
        lastVisibleEnd: Int,
        viewportEnd: Int,
        afterContentPadding: Int,
    ): Int? {
        if (totalRows <= 0) return 0
        if (lastVisibleIndex != totalRows - 1) return null
        return (lastVisibleEnd + afterContentPadding - viewportEnd).coerceAtLeast(0)
    }

    fun isNearBottom(distancePx: Int?, thresholdPx: Float): Boolean =
        distancePx != null && distancePx < thresholdPx

    /**
     * 视口高度变了（键盘弹收 / 引用条 / @面板 / ➕面板）之后要不要重新贴底。
     *
     * 判据是**变化前**贴不贴底——变化后再量就晚了：键盘顶起 300px，量出来必然"离底 300"。
     * 用户手指按在列表上时不动（iOS `!isTracking`），否则会和他的拖动抢。
     * [oldViewport] < 0 表示还没量过（首帧），不算变化。
     */
    fun shouldRestickOnResize(
        oldViewport: Int,
        newViewport: Int,
        wasNearBottom: Boolean,
        userDragging: Boolean,
    ): Boolean = oldViewport >= 0 && oldViewport != newViewport && wasNearBottom && !userDragging

    /**
     * 出箱里有没有**新出现**的一条（= 自己刚发了东西，要回到最新）。
     *
     * 口径收在「出箱回显」这一个入口上（`CHAT_UX.md` §9，im-web 2026-09-05 同一条）：
     * 文本 / 图片 / 文件 / 名片 / 转发都先写一行待发，所以不必在每条发送路径上各挂一次。
     * **重发不算新的**——它复用原来那一行的 clientMsgId。
     * [previous] 为 null 表示基线还没建（刚进会话），那时已在出箱里的失败消息不是"刚发的"。
     */
    fun hasNewOutgoing(previous: Set<String>?, current: Set<String>): Boolean =
        previous != null && current.any { it !in previous }

    /** 一次按下算不算「轻点」：没滑出 touchSlop，且没按到长按的时长。 */
    fun isTap(movedBeyondSlop: Boolean, pressedMs: Long, longPressTimeoutMs: Long): Boolean =
        !movedBeyondSlop && pressedMs < longPressTimeoutMs

    /** 在列表上轻点一下该做什么。 */
    enum class TapAction {
        /** ➕面板开着：**只收面板**，这一下不再传给气泡（iOS `handleReplyJumpTap` 第二步直接 return）。 */
        ClosePanelOnly,

        /** 收键盘，但**不吃掉这一下**——点在气泡上时气泡自己的点击照常生效（点引用块照样跳）。 */
        DismissKeyboard,
    }

    fun tapActionOf(panelOpen: Boolean): TapAction =
        if (panelOpen) TapAction.ClosePanelOnly else TapAction.DismissKeyboard
}
