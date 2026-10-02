package com.libeyond.imandroid.ui.screens

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import android.os.SystemClock
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import com.libeyond.imandroid.data.ChatEntry
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.libeyond.imandroid.data.ChatScroll
import com.libeyond.imandroid.sdk.logging.IMLog
import com.libeyond.imandroid.sdk.logging.PerfMarks
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

/** 距顶多少行以内就去加载更早的一页。 */
private const val LOAD_OLDER_THRESHOLD = 3

/**
 * 聊天列表的**滚动时序**一整组：首屏定位 → 贴底收敛 → 行变后的跟底 → 翻页保位 → 可见即读。
 *
 * 2026-09-16 从 `ChatScreen.kt` 整段平移过来（那份文件到 598/600 行，而"消息列表什么时候该滚到哪"
 * 与"这一页长什么样"是两件事）。**逐字平移，无逻辑改动**——这几条 effect 之间的先后与互斥
 * 是反复调出来的，见各自的注释。
 *
 * 它们必须待在一起：四条都读写同一份 [ChatScrollMarks]，拆散了就会出现
 * 「一条 effect 把另一条刚设的待办覆盖掉」。
 */
@Composable
@Suppress("LongParameterList")
internal fun ChatListSync(
    convId: String,
    listState: LazyListState,
    marks: ChatScrollMarks,
    rows: List<ChatRow>,
    rowsReady: Boolean,
    readSeq: Long,
    unread: Int,
    /** 用户正拖着列表——拖动期间不跟底（让用户赢）。 */
    listDragged: Boolean,
    /** 被详情页盖住：不报已读（用户看的不是这一页）。 */
    covered: Boolean,
    onLoadOlder: () -> Unit,
    onOutgoingEcho: () -> Unit,
    onVisibleSeq: (Long) -> Unit,
) {
    // —— 首屏定位（CHAT_UX §3）：有未读锚到首条未读，无未读贴底 ——
    // **在组合期下达**（requestScrollToItem），列表第一次测量就落在目标上。此前是 effect 里
    // scrollToItem：先按第 0 行画出一帧再跳过去，进会话肉眼可见地闪一下（设计稿 #2）。
    // 只做一次（iOS didInitialPosition），**不能与下面的自动贴底合并**：合并会让「停在首条未读」被贴底当场覆盖掉。
    val entryReady = rowsReady && rows.isNotEmpty()
    if (entryReady && !marks.didEntry) {
        val idx = ChatEntry.entryScrollIndex(rows.map { it.seqOrZero() }, readSeq, unread)
        marks.didEntry = true
        marks.entryAtBottom = idx == rows.lastIndex
        marks.rowsSize = rows.size
        marks.outgoing = outgoingKeysOf(rows) // 进来时就躺在出箱里的（失败待重发）不算"刚发的"
        listState.requestScrollToItem(idx)
    }
    // 压测埋点：首屏定位指令发出后的下一帧（口径见 PerfMarks）
    LaunchedEffect(entryReady) {
        if (!entryReady) return@LaunchedEffect
        withFrameNanos { }
        PerfMarks.chatInitialPosition(convId)
    }
    // 贴底那一支还欠一次收敛：对齐的是最后一行的**顶**，最后一行比屏高（长文/竖图）时停在它开头
    LaunchedEffect(entryReady) {
        if (!entryReady || !marks.entryAtBottom) return@LaunchedEffect
        marks.entryAtBottom = false
        withFrameNanos { } // 等第一次测量
        stickToBottom(listState)
    }

    // —— 行变了之后的贴底 ——
    // ① 刚点过 ↓ / 刚发出一条（保质期内）→ 精确贴底；② 行数变多且用户本来就贴着底 → 跟到底。
    val outgoingKeys = remember(rows) { outgoingKeysOf(rows) }
    LaunchedEffect(rows.size, outgoingKeys) {
        if (!marks.didEntry || rows.isEmpty()) return@LaunchedEffect
        // 自己发的 = 出箱里新冒出一条。口径收在出箱回显这一个入口（CHAT_UX §9），不在每条发送路径上各挂一次
        if (ChatScroll.hasNewOutgoing(marks.outgoing, outgoingKeys)) {
            onOutgoingEcho() // 停在历史时 Host 换回尾窗；新那一窗到了本 effect 再跑一次，仍在保质期内
            marks.stickUntil = SystemClock.uptimeMillis() + ChatScroll.STICK_BOTTOM_ARM_MS
            marks.locatingUntil = 0L // 自己发了东西就该回到最新，刚才那次跳转作废
        }
        marks.outgoing = outgoingKeys
        val previousRowsSize = marks.rowsSize
        val grew = rows.size > previousRowsSize
        marks.rowsSize = rows.size
        // 刚跳到某条：换锚点窗让行数变了，这时跟底会把刚居中的目标甩走
        if (SystemClock.uptimeMillis() < marks.locatingUntil) return@LaunchedEffect
        if (SystemClock.uptimeMillis() < marks.stickUntil && !listDragged) {
            stickToBottom(listState)
            PerfMarks.jumpBottomDone(convId)
            return@LaunchedEffect
        }
        // 只在**变多**时跟：ack 把待发换成已确认、行数不变，那时 animateScrollToItem 会把比屏高的最后一行滚回开头
        if (!grew) return@LaunchedEffect
        val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
        // 「贴不贴底」要拿长出来**之前**的总行数去比，不能拿长出来之后的新总数——
        // new_msg 一条条来时新增 1 行，2 行的容差（NEAR_BOTTOM_SLACK）盖得住这点测量延迟，看着像没事；
        // 断线重连补收 / 连发好几条一次性长出 ≥3 行时，拿新总数一比，明明贴着旧的底也会跌出容差，
        // 判成「在翻历史」不跟，表现为「聊天页收到新消息，列表没滚到最新那条」（2026-09-28 用户报）。
        if (ChatEntry.shouldAutoScroll(last, previousRowsSize)) {
            listState.animateScrollToItem(rows.size - 1)
        }
    }

    // —— 滚到顶部附近就加载更早的一页 ——
    //
    // 两件事一起做，缺一条都会出问题：
    // ① **在途守卫**：不守的话 rows.size 一变 effect 就再触发，一路把整个会话
    //    （可能十几万条）全加载进来，等于没做窗口。
    // ② **翻页保位**：在顶部插入 N 条后，firstVisibleItemIndex 仍指向同一个**下标**，
    //    而那个下标现在对应的是更早的消息——用户会看到列表凭空跳走。
    //    补偿一律**按同一条消息**（下标 + 新增条数），不按 contentSize 差值。
    var pendingOlder by remember(convId) { mutableStateOf(false) }
    var rowsBeforeLoad by remember(convId) { mutableStateOf(0) }

    LaunchedEffect(rows.size, listState.firstVisibleItemIndex) {
        if (!marks.didEntry || rows.isEmpty()) return@LaunchedEffect
        // 刚跳到某条：这时行数变是换锚点窗换的，不是上一页回来了——按新增条数补偿会把目标推走
        if (SystemClock.uptimeMillis() < marks.locatingUntil) {
            pendingOlder = false
            return@LaunchedEffect
        }

        // 上一页加载回来了 → 把视口按同一条消息补偿回去
        if (pendingOlder && rows.size > rowsBeforeLoad) {
            val added = rows.size - rowsBeforeLoad
            pendingOlder = false
            listState.scrollToItem(
                (listState.firstVisibleItemIndex + added).coerceAtMost(rows.lastIndex),
                listState.firstVisibleItemScrollOffset,
            )
            return@LaunchedEffect
        }

        if (!pendingOlder && listState.firstVisibleItemIndex <= LOAD_OLDER_THRESHOLD) {
            pendingOlder = true
            rowsBeforeLoad = rows.size
            onLoadOlder()
        }
    }

    // —— 可见即读 ——
    // **注意**：程序化滚到底之后紧跟"可见即读"上报，等于替用户把消息读完
    // （十万条未读进一次会话清零就是这么来的，CHAT_UX §3）。
    // 故只在**首屏定位完成后**才上报，且只报真正可见的行。
    // 被详情页盖住期间也不报（用户看的不是这一页）；露出来时 covered 一变，这里补报一次。
    LaunchedEffect(rows.size, listState.layoutInfo.visibleItemsInfo.size, covered) {
        if (!marks.didEntry || covered) return@LaunchedEffect
        val maxSeq = listState.layoutInfo.visibleItemsInfo
            .mapNotNull { (rows.getOrNull(it.index) as? ChatRow.Confirmed)?.msg?.convSeq }
            .maxOrNull() ?: return@LaunchedEffect
        onVisibleSeq(maxSeq)
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

private const val SCROLLBAR_WIDTH_DP = 3
private const val SCROLLBAR_TRAILING_MARGIN_DP = 1

/**
 * 聊天列表右侧的细滚动条。判据在 [ChatScroll.scrollbarThumb]，这里只画——
 * Compose Foundation 在 Android 上没有现成的滚动条（Web/iOS 都是系统白送的，
 * 本端此前一直没有，聊天页翻长会话时完全没有"大概翻到哪了"的提示）。
 */
internal fun Modifier.chatScrollbar(listState: LazyListState, color: Color): Modifier =
    drawWithContent {
        drawContent()
        val layout = listState.layoutInfo
        val visible = layout.visibleItemsInfo
        if (visible.isEmpty()) return@drawWithContent
        val averageRowHeightPx = visible.sumOf { it.size } / visible.size.toFloat()
        val first = visible.first()
        val thumb = ChatScroll.scrollbarThumb(
            totalRows = layout.totalItemsCount,
            firstVisibleIndex = first.index,
            firstVisibleOffset = first.offset,
            averageRowHeightPx = averageRowHeightPx,
            viewportHeightPx = layout.viewportSize.height.toFloat(),
        ) ?: return@drawWithContent
        val widthPx = SCROLLBAR_WIDTH_DP.dp.toPx()
        drawRoundRect(
            color = color,
            topLeft = Offset(size.width - widthPx - SCROLLBAR_TRAILING_MARGIN_DP.dp.toPx(), thumb.topPx),
            size = Size(widthPx, thumb.heightPx),
            cornerRadius = CornerRadius(widthPx / 2f, widthPx / 2f),
        )
    }
