package com.libeyond.imandroid.ui.screens

import android.os.SystemClock
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.Lucide
import com.libeyond.imandroid.R
import com.libeyond.imandroid.data.ChatEntry
import com.libeyond.imandroid.data.ChatScroll
import com.libeyond.imandroid.ui.theme.IMTheme
import kotlinx.coroutines.launch

/**
 * ↓ 悬浮跳转（从 `ChatScreen` 抽出，只为控体量）：离底较远、**或窗口停在历史**时出现；角标是 ↓N（`UnreadBelow`）。
 */
@Composable
internal fun BoxScope.JumpToLatestButton(
    listState: LazyListState,
    marks: ChatScrollMarks,
    rows: List<ChatRow>,
    pendingReadSeq: Long,
    myUid: String,
    showsJumpToLatest: (awayFromBottom: Boolean) -> Boolean,
    unreadBelowOf: (pendingRead: Long, loadedBelow: Int) -> Int,
    onJumpToLatest: () -> Unit,
) {
    val c = IMTheme.colors
    val d = IMTheme.dimens
    // ↓ 悬浮跳转：离底较远、**或窗口停在历史**时出现
    val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
    val awayFromBottom = rows.isNotEmpty() && lastVisible in 0 until (rows.size - 1 - ChatEntry.NEAR_BOTTOM_SLACK)
    if (showsJumpToLatest(awayFromBottom)) {
        val scope = rememberCoroutineScope()
        // 已加载窗口内、已滚入位点之下的对端消息数（见上面 pendingReadSeq 的注释）
        val loadedBelow = remember(rows, pendingReadSeq, myUid) {
            rows.count { r -> (r as? ChatRow.Confirmed)?.msg?.let { it.convSeq > pendingReadSeq && it.sender != myUid } == true }
        }
        val unreadBelow = unreadBelowOf(pendingReadSeq, loadedBelow)
        Box(modifier = Modifier.align(Alignment.BottomEnd).padding(end = d.space4, bottom = d.space3)) {
            Box(
                modifier = Modifier
                    .size(d.jumpButton)
                    .clip(CircleShape)
                    .background(c.surfaceElevated)
                    .clickable {
                        // 先请宿主换回尾窗（历史窗里没有"最新那条"可滚）。换窗是异步的，此刻 rows 还是
                        // 旧那一窗（真机撞见：从会话开头点↓，落在半空中），所以记一个**带保质期**的贴底，
                        // 新的一窗在保质期内到了，上面那个 effect 会再贴一次。
                        // **保质期不能省**：已经在尾窗时换窗不产生新的 rows，不失效的待办会一直挂着，
                        // 等用户滚上去读历史时来一条新消息，被当成"刚点过 ↓"一把甩到底。
                        onJumpToLatest()
                        marks.stickUntil = SystemClock.uptimeMillis() + ChatScroll.STICK_BOTTOM_ARM_MS
                        // 本来就在尾窗里（只是离底远）时不会有新数据到达，直接贴
                        scope.launch { stickToBottom(listState) }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    imageVector = Lucide.ChevronDown,
                    contentDescription = stringResource(R.string.chat_jump_to_latest),
                    modifier = Modifier.size(20.dp),
                    colorFilter = ColorFilter.tint(c.accent),
                )
            }
            if (unreadBelow > 0) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 4.dp, y = (-4).dp)
                        .height(d.unreadBadgeHeight)
                        .widthIn(min = d.unreadBadgeHeight)
                        .clip(CircleShape)
                        .background(c.unreadBadge)
                        .padding(horizontal = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (unreadBelow > 99) "99+" else unreadBelow.toString(),
                        color = c.onAccent,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
    }
}
