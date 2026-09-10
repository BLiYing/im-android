package com.libeyond.imandroid.ui.screens

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState

// 聊天页列表的滚动动作（贴底 / 居中 / 键盘跟随 / 点空白收键盘）。
// 从 ChatScreen.kt 拆出（2026-09-10，那份文件到 595/600 行）。
// 判据（该不该滚、算不算点击）是纯函数，在 `data/ChatScroll.kt`；这里只放**动手**的那一半。

/**
 * 把第 [index] 行滚到视口**中间**。
 *
 * `scrollToItem` 是把目标顶到视口**顶端**，而 `CHAT_UX.md §3.1` 的三端契约是**居中**
 * ——顶端对齐时目标上方的上下文一行都看不到，"跳到了但不知道跳到哪"。
 * iOS 用 `UITableViewScrollPositionMiddle`，Web 用 `scrollIntoView({block:"center"})`，
 * Compose 没有对应参数，只能先顶上去再补一段偏移。
 *
 * 目标比视口还高时不补（`delta <= 0`），补了反而把它的开头推出屏幕。
 * 靠边的行由 `scrollBy` 自己夹住，不必特判。
 */
internal suspend fun centerItem(listState: LazyListState, index: Int) {
    val info = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index } ?: return
    val viewport = listState.layoutInfo.viewportEndOffset - listState.layoutInfo.viewportStartOffset
    val delta = (viewport - info.size) / 2f
    if (delta > 0f) listState.scrollBy(-delta)
}
