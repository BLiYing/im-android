package com.libeyond.imandroid.ui.screens

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.libeyond.imandroid.data.ChatScroll
import com.libeyond.imandroid.sdk.logging.IMLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

// 聊天页列表的滚动动作（贴底 / 居中 / 键盘跟随 / 点空白收键盘）。
// 从 ChatScreen.kt 拆出（2026-09-10，那份文件到 595/600 行）。
// 判据（该不该滚、算不算点击）是纯函数，在 `data/ChatScroll.kt`；这里只放**动手**的那一半。

private val log = IMLog.tag("IM.Chat")

/**
 * 聊天列表上**只记、不驱动重组**的簿记。
 *
 * 用普通字段不用 State：它们只在 effect / 手势回调里读，而首屏定位要在**组合期**写 [didEntry]
 * ——写一个本次组合读过的 State 会触发回写重组。按会话 `remember(convId)` 建，换会话即重置。
 */
internal class ChatScrollMarks {
    /** 首屏定位做过了（iOS `didInitialPosition`，一次性）。 */
    var didEntry = false

    /** 首屏落点是底部，还欠一次精确贴底收敛。 */
    var entryAtBottom = false

    /** 上一次看到的行数——自动跟底只在**变多**时做。 */
    var rowsSize = 0

    /** 在这个时刻（uptimeMillis）之前，行一变就重新贴底。见 [ChatScroll.STICK_BOTTOM_ARM_MS]。 */
    var stickUntil = 0L

    /** 上一次看到的出箱身份；null = 基线未建。见 [ChatScroll.hasNewOutgoing]。 */
    var outgoing: Set<String>? = null

    /**
     * 在这个时刻之前刚跳到过某条消息（引用块 / 回复条 / 搜索命中）。
     *
     * 跳转会换锚点窗、行数跟着变，这段时间里**跟底与翻页补偿都要让路**——
     * 否则刚居中的目标被「行变多了、跟到底」或「按新增条数补偿」一把甩走。
     */
    var locatingUntil = 0L
}

/** 列表底边还差多少像素到内容底；最后一行不可见时 null。坐标口径见 [ChatScroll.distanceToBottomPx]。 */
private fun LazyListLayoutInfo.distanceToBottomPx(): Int? {
    val last = visibleItemsInfo.lastOrNull()
    return ChatScroll.distanceToBottomPx(
        totalRows = totalItemsCount,
        lastVisibleIndex = last?.index ?: -1,
        lastVisibleEnd = last?.let { it.offset + it.size } ?: 0,
        viewportEnd = viewportEndOffset,
        afterContentPadding = afterContentPadding,
    )
}

/**
 * 精确贴到内容底（iOS `scrollToAbsoluteBottom`）。收敛返回 true。
 *
 * **不能只 `scrollToItem(last)`**：那是把最后一行的**顶**对到视口顶，最后一行比视口高时
 * （长文、竖图）停在它的开头。也不能只滚一次：图片/链接卡的高度是异步定下来的，一轮常常差一截，
 * 所以量一次滚一次，最多 [ChatScroll.STICK_BOTTOM_MAX_ROUNDS] 轮，没到底就让出一帧再量。
 * 没收敛记一条 warn（与 iOS 同名事件），不抛。
 *
 * 用户正拖着列表时，滚动互斥锁会拒掉这次滚动（抛 CancellationException）——那是让用户赢，
 * 吞掉返回 false；**调用方协程自己被取消时照常抛出**，否则 effect 换 key 时停不下来。
 */
internal suspend fun stickToBottom(listState: LazyListState): Boolean {
    try {
        repeat(ChatScroll.STICK_BOTTOM_MAX_ROUNDS) {
            val info = listState.layoutInfo
            when (val dist = info.distanceToBottomPx()) {
                0 -> return true
                null -> listState.scrollToItem(info.totalItemsCount - 1)
                else -> listState.scrollBy(dist.toFloat())
            }
            if (listState.layoutInfo.distanceToBottomPx() == 0) return true
            withFrameNanos { }
        }
    } catch (e: CancellationException) {
        currentCoroutineContext().ensureActive()
        return false
    }
    log.w(
        "chat_stick_bottom_not_converged",
        "rows" to listState.layoutInfo.totalItemsCount,
        "dist" to listState.layoutInfo.distanceToBottomPx(),
    )
    return false
}

/**
 * 视口高度一变（键盘弹收 / 引用条 / @面板 / ➕面板），按「**变化前**贴不贴底」决定要不要重贴（#4）。
 *
 * 所有改视口高度的来源统一在这一处，不在各个开关回调里各滚一次——键盘高度是 `imePadding`
 * 逐帧动画出来的，只有跟着布局走才跟得上。判据见 [ChatScroll.shouldRestickOnResize]。
 */
@Composable
internal fun KeepBottomOnResize(listState: LazyListState) {
    val thresholdPx = with(LocalDensity.current) { ChatScroll.NEAR_BOTTOM_DP.dp.toPx() }
    val dragged by listState.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(listState, thresholdPx) {
        var prevViewport = -1
        var wasNear = false
        snapshotFlow { listState.layoutInfo }.collect { info ->
            val h = info.viewportSize.height
            wasNear = if (ChatScroll.shouldRestickOnResize(prevViewport, h, wasNear, dragged)) {
                stickToBottom(listState) ||
                    ChatScroll.isNearBottom(listState.layoutInfo.distanceToBottomPx(), thresholdPx)
            } else {
                ChatScroll.isNearBottom(info.distanceToBottomPx(), thresholdPx)
            }
            prevViewport = h
        }
    }
}

/**
 * 列表上的轻点（#6）：➕面板开着就**只收面板**、这一下不落到气泡上；否则收键盘、这一下照常落到气泡上。
 * 与 iOS `handleReplyJumpTap` 同序，判据见 [ChatScroll.tapActionOf]。
 *
 * 挂 Initial 阶段（外层先于气泡看到事件）：要拦住气泡的点击，只能赶在它处理抬起之前把抬起吃掉。
 * **按下不吃**——吃了列表就拖不动；滑出 slop、多指、按到长按时长的一律放行。
 * 要挂在横向内边距**之外**，头像列左边那 12dp 空白也算点空白。
 */
internal fun Modifier.chatListTaps(
    panelOpen: Boolean,
    onClosePanel: () -> Unit,
    onDismissKeyboard: () -> Unit,
): Modifier = pointerInput(panelOpen) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        var moved = false
        var up: PointerInputChange? = null
        while (up == null) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.changes.size > 1) moved = true
            val change = event.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
            if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) moved = true
            if (!change.pressed) up = change
        }
        val released = up ?: return@awaitEachGesture
        val pressedMs = released.uptimeMillis - down.uptimeMillis
        if (!ChatScroll.isTap(moved, pressedMs, viewConfiguration.longPressTimeoutMillis)) return@awaitEachGesture
        when (ChatScroll.tapActionOf(panelOpen)) {
            ChatScroll.TapAction.ClosePanelOnly -> {
                released.consume()
                onClosePanel()
            }
            ChatScroll.TapAction.DismissKeyboard -> onDismissKeyboard()
        }
    }
}

/**
 * 把第 [index] 行滚到视口**中间**。
 *
 * `scrollToItem` 是把目标顶到视口**顶端**，而 `CHAT_UX.md §3.1` 的三端契约是**居中**
 * ——顶端对齐时目标上方的上下文一行都看不到，"跳到了但不知道跳到哪"。
 * iOS 用 `UITableViewScrollPositionMiddle`，Web 用 `scrollIntoView({block:"center"})`，
 * Compose 没有对应参数，只能先把它弄进视口、再按它**实际所在的位置**补一段偏移
 * ——补多少见 [ChatScroll.centerDeltaPx]（旧写法假设它已在顶端，列表尾部会被夹住，补反了）。
 * 靠边的行由 `scrollBy` 自己夹住，不必特判。
 */
internal suspend fun centerItem(listState: LazyListState, index: Int) {
    val layout = listState.layoutInfo
    val info = layout.visibleItemsInfo.firstOrNull { it.index == index } ?: return
    val delta = ChatScroll.centerDeltaPx(info.offset, info.size, layout.viewportStartOffset, layout.viewportEndOffset)
    if (delta != 0) listState.scrollBy(delta.toFloat())
}
