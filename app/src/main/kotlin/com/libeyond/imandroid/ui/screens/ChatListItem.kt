package com.libeyond.imandroid.ui.screens

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.dp
import com.libeyond.imandroid.data.ChatScroll
import com.libeyond.imandroid.data.ChatSelection
import com.libeyond.imandroid.data.db.MessageEntity
import com.libeyond.imandroid.ui.theme.IMTheme

// 从 ChatScreen 拆出（那边贴 600 行硬闸）：列表里「一行」的外壳（多选圈 / 高亮 / 长按隐形）与「定位到某条」的滚动 + 高亮副作用。

/**
 * 定位到某条消息（引用块跳转 / 搜索命中），返回**正在高亮**的 conv_seq（0 = 没有）。
 */
@Composable
internal fun rememberLocateHighlight(
    convId: String,
    locateSeq: Long,
    rows: List<ChatRow>,
    marks: ChatScrollMarks,
    listState: LazyListState,
    onLocateConsumed: () -> Unit,
): Long {
    var highlightSeq by remember(convId) { mutableStateOf(0L) }
    // key 用 rows 本身而非 rows.size：换到一个**条数相同**的锚点窗时 size 不变，目标进来了也不会再跑
    LaunchedEffect(locateSeq, rows) {
        if (locateSeq <= 0) return@LaunchedEffect
        val idx = rowIndexOfSeq(rows, locateSeq)
        if (idx < 0) return@LaunchedEffect
        // 跳转压过「刚点过 ↓」的贴底待办，并让跟底 / 翻页补偿让路一小段
        marks.stickUntil = 0L
        marks.locatingUntil = SystemClock.uptimeMillis() + ChatScroll.STICK_BOTTOM_ARM_MS
        listState.scrollToItem(idx)
        centerItem(listState, idx)
        // 先点亮再归零：归零会换掉本 effect 的 key 把它取消，
        // 高亮的熄灭因此**不能**写在这里（写这儿就会一直亮着）。
        highlightSeq = locateSeq
        onLocateConsumed()
    }
    LaunchedEffect(highlightSeq) {
        if (highlightSeq <= 0) return@LaunchedEffect
        kotlinx.coroutines.delay(1200)
        highlightSeq = 0
    }
    return highlightSeq
}

/** 列表里的一行：多选态左侧画圈、整行点一下勾选；菜单开着的那一行隐形占位；定位命中的那一行高亮。 */
@Composable
internal fun ChatListItem(
    rows: List<ChatRow>,
    i: Int,
    style: ChatRowStyle,
    selection: Map<Long, MessageEntity>?,
    menuForSeq: Long,
    highlightSeq: Long,
    onToggleSelect: (MessageEntity) -> Unit,
    onLongPress: (MessageEntity, com.libeyond.imandroid.ui.components.MenuAnchor) -> Unit,
    onOpenMedia: (MessageEntity) -> Unit,
    onOpenUser: (String) -> Unit,
    onRetry: (String) -> Unit,
    onToggleUpload: (String) -> Unit,
    onCancelPending: (String) -> Unit,
    onTapLongText: (MessageEntity) -> Boolean,
    onAddFriend: () -> Unit,
    onReedit: (String) -> Unit,
    onJumpToSeq: (Long) -> Unit,
    onOpenRecord: (String) -> Unit,
    onCallBack: (Boolean) -> Unit,
) {
    val r0 = rows[i]
    // 菜单开着的那一行整行隐形（保留占位，列表不跳）。
    // **宫格例外**：只隐被长按的那一格（浮起来的也只有那一格），
    // 整行隐会让旁边几张跟着消失——那不是 iOS 的样子。
    val hidden = menuForSeq > 0 &&
        (r0 as? ChatRow.Confirmed)?.msg?.convSeq == menuForSeq
    val highlighted = highlightSeq > 0 && when (r0) {
        is ChatRow.Confirmed -> r0.msg.convSeq == highlightSeq
        is ChatRow.Album -> r0.sent.any { it.convSeq == highlightSeq }
        else -> false
    }
    // 多选态：可勾的行左侧画圈、整行改成"点一下勾选"。
    // 不可勾的行（系统提示/撤回墓碑/未确认本地件）**不画圈也不响应**，
    // 与 iOS `canEditRowAtIndexPath` 对 NO 的行不画圈同口径。
    val selMsg = (r0 as? ChatRow.Confirmed)?.msg?.takeIf { ChatSelection.selectable(it) }
    val selecting = selection != null
    Row(
        Modifier
            .alpha(if (hidden) 0f else 1f)
            .then(
                if (selecting && selMsg != null) {
                    Modifier.clickable { onToggleSelect(selMsg) }
                } else {
                    Modifier
                },
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
    if (selecting) {
        if (selMsg != null) {
            SelectionCheck(selected = selection.containsKey(selMsg.convSeq))
        } else {
            Spacer(Modifier.width(22.dp)) // 占位，让可勾与不可勾的行左缘对齐
        }
        SelectionGutterSpacer()
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalLocateFlash provides highlighted) {
    ChatRowView(
        rows = rows,
        i = i,
        style = style,
        onLongPress = onLongPress,
        onOpenMedia = onOpenMedia,
        onOpenUser = onOpenUser,
        onRetry = onRetry,
        onToggleUpload = onToggleUpload, onCancelPending = onCancelPending,
        onTapLongText = onTapLongText,
        onAddFriend = onAddFriend,
        onReedit = onReedit,
        onJumpToSeq = onJumpToSeq,
        onOpenRecord = onOpenRecord,
        onCallBack = onCallBack,
        // 宫格：长按的那一格自己隐形（整行不隐，其余格仍在原位）
        hiddenTile = if (r0 is ChatRow.Album) menuForSeq else 0L,
        selection = if (r0 is ChatRow.Album) selection else null, onToggleSelect = onToggleSelect, // 只宫格要逐格圈：别的行不吃 selection，免得每勾一下全体行重组
    )
    }
    }
}
