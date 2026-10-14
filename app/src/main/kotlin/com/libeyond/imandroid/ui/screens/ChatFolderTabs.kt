package com.libeyond.imandroid.ui.screens

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.libeyond.imandroid.R
import com.libeyond.imandroid.data.ChatFolder
import com.libeyond.imandroid.data.ChatFolderLogic
import com.libeyond.imandroid.data.ConversationRowStyle
import com.libeyond.imandroid.data.FolderSwipe
import com.libeyond.imandroid.data.db.ConversationEntity
import kotlin.math.abs

/**
 * 会话列表顶部的文件夹 Tab 条（设计 CHAT_FOLDERS_DESIGN §4.1–§4.2）：首位恒为「全部」，其后是各文件夹；
 * 每个 Tab 带未读消息总数角标。直接复用 [SegTabBar]（药丸选中、居中贴合内容、放不下横向滚动），
 * 本函数只管数据映射——与 iOS `IMChatFolderTabsView` 同职责。
 *
 * @param selectedId 当前文件夹 id；null = 「全部」。
 * @param onLongPress 长按某个**文件夹** Tab（「全部」不触发）。
 */
@Composable
internal fun ChatFolderTabStrip(
    folders: List<ChatFolder>,
    conversations: List<ConversationEntity>,
    nowMs: Long,
    selectedId: String?,
    onSelect: (String?) -> Unit,
    onLongPress: (ChatFolder) -> Unit,
) {
    val all = stringResource(R.string.folder_tab_all)
    val titles = listOf(all) + folders.map { it.name }
    val badges = (listOf<ChatFolder?>(null) + folders).map { f ->
        val b = ChatFolderLogic.badge(conversations, f, nowMs)
        if (b.count > 0) SegBadge(ConversationRowStyle.compactCount(b.count), b.muted) else null
    }
    androidx.compose.foundation.layout.Box(Modifier.padding(bottom = com.libeyond.imandroid.ui.theme.IMTheme.dimens.space2)) {
    SegTabBar(
        titles = titles,
        selected = ChatFolderLogic.tabIndex(selectedId, folders),
        badges = badges,
        onLongPress = { i -> if (i > 0) folders.getOrNull(i - 1)?.let(onLongPress) },
        // 上下留白由外层定：搜索框底部已有 space2，条下方补同样的 space2 → 搜索框↔Tab 与 Tab↔首行间距相等
        verticalPadding = 0.dp,
        onSelect = { i -> onSelect(if (i <= 0) null else folders.getOrNull(i - 1)?.id) },
    )
    }
}

/** 每个 Tab 记住自己的滚动位置（设计 §4.3）：id（「全部」= ""）→ (首个可见项, 偏移)。由外壳持有，进聊天页回来不丢。 */
class FolderScrollMemory {
    private val map = HashMap<String, Pair<Int, Int>>()
    fun save(id: String?, index: Int, offset: Int) { map[id.orEmpty()] = index to offset }
    fun restore(id: String?): Pair<Int, Int> = map[id.orEmpty()] ?: (0 to 0)
}

/**
 * 内容区**左右横滑切文件夹 Tab**（设计 §4.3；仿 [swipeToSwitchTab]）：左滑 = 下一个、右滑 = 上一个。
 * 只吃横向拖动，竖向滚动不受影响。Android 会话行没有左滑按钮（行操作全在长按菜单里），
 * 所以不存在「起手在尾部区域让给行滑动」的冲突——判定走同一个 [ChatFolderLogic.swipeOutcome]（`actionsWidth = 0`），
 * 与 iOS 共用向量验证。
 *
 * @param count Tab 总数（含「全部」）；< 2 时不装手势。
 */
@Composable
internal fun Modifier.swipeToSwitchFolder(currentIndex: Int, count: Int, onSelectIndex: (Int) -> Unit): Modifier {
    if (count < 2) return this
    val threshold = with(LocalDensity.current) { 72.dp.toPx() }
    // 回调每次重组都是新 lambda、且闭包着当时的文件夹列表：用 rememberUpdatedState 取最新，
    // 别让 pointerInput 的 key 里漏掉它（别的设备改了顺序但 count / index 没变时，会切到旧位置的文件夹）。
    val latestOnSelect by rememberUpdatedState(onSelectIndex)
    return this.pointerInput(currentIndex, count) {
        var total = 0f
        var startX = 0f
        detectHorizontalDragGestures(
            onDragStart = { total = 0f; startX = it.x },
            onDragEnd = {
                val tx = if (abs(total) >= threshold) total else 0f
                when (
                    ChatFolderLogic.swipeOutcome(
                        translationX = tx, startX = startX, listWidth = size.width.toFloat(), actionsWidth = 0f,
                        hasNext = currentIndex < count - 1, hasPrev = currentIndex > 0, rowOpen = false,
                    )
                ) {
                    FolderSwipe.NextTab -> latestOnSelect(currentIndex + 1)
                    FolderSwipe.PrevTab -> latestOnSelect(currentIndex - 1)
                    else -> Unit
                }
            },
            onDragCancel = { total = 0f },
        ) { _, dx -> total += dx }
    }
}
