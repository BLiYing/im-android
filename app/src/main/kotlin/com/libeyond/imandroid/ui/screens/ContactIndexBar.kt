package com.libeyond.imandroid.ui.screens

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
 * iOS 那侧是系统的 `sectionIndexTitlesForTableView:`，Compose 没有对应控件，只能自绘，
 * **观感照系统那条抄**（2026-09-15 用户报「索引样式太丑、和 iOS 不一致」）：
 * - 字母用**主色**、11sp 半粗、**不铺底色**——iOS 没设 `sectionIndexColor`，取的是窗口 tintColor，
 *   而本工程窗口 tint 就是主色（`SceneDelegate` 里 `window.tintColor = IMAppearance.accentColor`）；
 * - **固定行高、整条竖直居中**。此前按 SpaceEvenly 均分整列高度，只有几组时字母稀稀拉拉散满右边，
 *   按下时还铺一条灰色圆角底，和 iOS 两样；
 * - 字母多到放不下时（横屏 / 小屏）行高按可用高度收缩，不裁掉末尾那几个；
 * - **按住时不高亮某个字母**（iOS 系统那条整条同色、不分主次），停在哪一组靠列表跳过去 + 触感表达；
 *   下面的 `active` 只用来去重，不驱动观感——别当成漏写的高亮补回去。
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

    BoxWithConstraints(modifier.width(INDEX_WIDTH).fillMaxHeight(), contentAlignment = Alignment.Center) {
        val rowHeight = minOf(INDEX_ROW_HEIGHT, maxHeight / titles.size)
        Column(
            modifier = Modifier
                .width(INDEX_WIDTH)
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
        ) {
            titles.forEach { t ->
                Box(Modifier.height(rowHeight), contentAlignment = Alignment.Center) {
                    Text(text = t, color = c.accent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
            }
        }
    }
}

/** 触摸条宽。 */
private val INDEX_WIDTH = 24.dp

/** 每个字母的行高上限（iOS 系统索引尺约 16pt 一格）。 */
private val INDEX_ROW_HEIGHT = 16.dp
