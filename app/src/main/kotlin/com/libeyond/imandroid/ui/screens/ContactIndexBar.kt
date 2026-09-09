package com.libeyond.imandroid.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.libeyond.imandroid.ui.theme.IMTheme

/**
 * 右侧 A–Z 索引尺。
 *
 * iOS 那侧是系统的 `sectionIndexTitlesForTableView:`，Compose 没有对应控件，只能自绘
 * ——**要对齐的是"有一条能一按就跳组的尺"**，不是同一个控件（`docs/UI_PARITY_IOS.md` §4.5.1）。
 *
 * 两条与 iOS 一致的判据：
 * - [titles] 为空（没有好友）时**整条不画**（iOS 是 `sectionIndexTitles` 回 nil）。
 * - 顶部那四个入口**不参与**——本组件只认字母组，"第几组对应列表第几项"由调用方换算。
 *
 * 按下与拖动都实时回调 [onPick]，松手不回调：手指一路划过连续跳组是这条尺的全部意义。
 */
@Composable
internal fun ContactIndexBar(
    titles: List<String>,
    onPick: (index: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (titles.isEmpty()) return
    val c = IMTheme.colors
    val view = LocalView.current
    var barHeight by remember { mutableStateOf(0) }
    var active by remember { mutableStateOf(-1) }

    /** 触点 Y → 第几个字母。**夹在两端**：滑出上下边界仍停在首/末组，不至于划着划着没反应。 */
    fun pickAt(y: Float) {
        if (barHeight <= 0) return
        val idx = (y / barHeight * titles.size).toInt().coerceIn(0, titles.lastIndex)
        if (idx == active) return
        active = idx
        // 跳组给一次轻触感。iOS 的系统索引尺没有这一下，但自绘的尺缺了它会让人觉得"没按到"
        view.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
        onPick(idx)
    }

    Column(
        modifier = modifier
            .width(28.dp)
            .fillMaxHeight()
            .padding(vertical = 8.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (active >= 0) c.subtleFill else Color.Transparent)
            .onGloballyPositioned { barHeight = it.size.height }
            .pointerInput(titles) {
                detectVerticalDragGestures(
                    onDragStart = { pickAt(it.y) },
                    onDragEnd = { active = -1 },
                    onDragCancel = { active = -1 },
                ) { change, _ -> pickAt(change.position.y) }
            }
            .pointerInput(titles) {
                detectTapGestures(onPress = {
                    pickAt(it.y)
                    tryAwaitRelease()
                    active = -1
                })
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceEvenly,
    ) {
        titles.forEachIndexed { i, t ->
            // 高度由 SpaceEvenly 均分，不写死行高——字母数从 1 到 27 都要铺满整条，
            // 写死的话只有两三组时会全挤在顶上，而那正是新账号的样子
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = t,
                    color = if (i == active) c.accent else c.textSecondary,
                    fontSize = 10.sp,
                    fontWeight = if (i == active) FontWeight.Bold else FontWeight.Medium,
                )
            }
        }
    }
}
