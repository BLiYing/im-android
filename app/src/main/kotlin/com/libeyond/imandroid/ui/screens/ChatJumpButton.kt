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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
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
import com.libeyond.imandroid.data.IncomingRule
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
    // 判据按**像素**（距底 ≥ 80dp，iOS `isNearBottom` 同口径），不按行数：图片 / 相册 / 卡片行动辄 300dp，
    // 按「最后 3 行都滑出」算要滑过一整屏才出现。贴底动作的保质期内不亮（iOS `scrollToBottomGuardUntil`），
    // 免得贴底动画途中闪一下。最后一行不在视口里时距离为 null，按「远」算。
    val thresholdPx = with(androidx.compose.ui.platform.LocalDensity.current) { ChatScroll.NEAR_BOTTOM_DP.dp.toPx() }
    // **先读布局再判保质期**：短路在前的话，保质期内这次组合没读过 layoutInfo，就没订阅滚动，
    // 保质期过后滚多远按钮都不亮（2026-10-13 OPPO 实测：点 ↓ 回底后再上滑，按钮不出现）。
    val far = !ChatScroll.isNearBottom(listState.layoutInfo.distanceToBottomPx(), thresholdPx)
    // 保质期到点补一次重组（它是普通 var，不会自己触发）
    var guardTick by remember { mutableIntStateOf(0) }
    val guardLeft = marks.stickUntil - SystemClock.uptimeMillis()
    if (guardLeft > 0) LaunchedEffect(marks.stickUntil) { delay(guardLeft); guardTick++ }
    val awayFromBottom = guardTick >= 0 && rows.isNotEmpty() && guardLeft <= 0 && far
    if (showsJumpToLatest(awayFromBottom)) {
        val scope = rememberCoroutineScope()
        // 已加载窗口内、已滚入位点之下的对端消息数（见上面 pendingReadSeq 的注释）
        val loadedBelow = remember(rows, pendingReadSeq, myUid) {
            rows.count { r -> (r as? ChatRow.Confirmed)?.msg?.let { it.convSeq > pendingReadSeq && IncomingRule.countsAsUnread(it.sender, it.contentType, myUid) } == true }
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
