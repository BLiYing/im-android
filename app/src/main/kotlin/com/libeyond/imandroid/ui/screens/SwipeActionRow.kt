package com.libeyond.imandroid.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** 一格左滑动作的外观（文案 + 底色）。 */
internal data class SwipeAction(val label: String, val background: Color, val onClick: () -> Unit)

/** 每格的宽度。与 iOS `UISwipeActionsConfiguration` 的默认格宽同量级。 */
private val ACTION_WIDTH = 88.dp

/**
 * 左滑露出若干动作的行（对齐 iOS 的 `trailingSwipeActionsConfiguration`）。
 *
 * Compose 没有等价控件（`SwipeToDismissBox` 是"滑掉一整行"，不是"露出几个按钮"），所以自绘：
 * 内容层跟手左移，底下露出等宽的动作格。**动作格从右往左排**，即 [actions] 的第一个最靠近屏幕边缘
 * ——与 iOS 数组第一个显示在最外侧一致。
 *
 * ### 这是个**受控**组件
 * 「当前敞着的是哪一行」由调用方持有（[opened] / [onOpenedChange]），不是每行各存各的。
 * iOS 的 `UITableView` 内建两条行为，各自都要一个全局视角才做得到：
 * ① 滑开第二行时第一行自动收起；② 敞着的行点内容区只**收起**、不触发选中。
 * 每行自己存 offset 时这两条都写不出来——滑开张三再点他的名字会直接进资料页，行还敞着
 * （2026-09-09 `/code-review` 抓出）。
 *
 * ②**由调用方在 onClick 里判**，不在这里拦指针事件：内容自带的 `clickable` 是子节点，
 * Main 传递是自下而上的，父节点这一层根本轮不到；改用 Initial 传递能拦住，但同时会
 * 把这一行的拖动也一并吃掉（松手只能靠点，不能再滑回去）。真机实测过才发现
 * ——拦指针那版点下去照样进了资料页。
 *
 * 松手时的归位判据在 [settleOffset]（纯函数，有单测）：过半吸开、否则弹回。
 */
@Composable
internal fun SwipeActionRow(
    actions: List<SwipeAction>,
    opened: Boolean,
    onOpenedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    if (actions.isEmpty()) {
        Column(modifier) { content() }
        return
    }
    val density = LocalDensity.current
    val maxOffset = with(density) { (ACTION_WIDTH * actions.size).toPx() }
    val scope = rememberCoroutineScope()
    var rowHeight by remember { mutableStateOf(0) }
    // 用 Animatable 而不是 animateFloatAsState：拖动中要 snapTo（跟手，不能有动画迟滞），
    // 松手才 animateTo。两者混用会在松手那一瞬看见一次**回缩**——动画值还停在落后于手指的位置
    var dragging by remember { mutableStateOf(false) }
    val offset = remember { Animatable(0f) }

    // 别人被滑开时把自己收起来 / 调用方要求敞开时敞开
    LaunchedEffect(opened, maxOffset) {
        if (!dragging) offset.animateTo(if (opened) -maxOffset else 0f)
    }

    Box(modifier.onGloballyPositioned { rowHeight = it.size.height }) {
        // 动作层在底下；只在真的滑开时才画，免得每一行都白白多一层
        if (offset.value < -1f) {
            Row(
                Modifier.align(Alignment.CenterEnd).height(with(density) { rowHeight.toDp() }),
            ) {
                // reversed：actions[0] 要贴屏幕边缘，而 Row 是从左往右排
                actions.asReversed().forEach { a ->
                    Box(
                        Modifier.width(ACTION_WIDTH).fillMaxHeight().background(a.background)
                            .clickable {
                                onOpenedChange(false) // 先收起再执行：动作可能弹确认框，留着敞开的行很难看
                                a.onClick()
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(a.label, color = Color.White, fontSize = 14.sp)
                    }
                }
            }
        }
        Column(
            Modifier
                .fillMaxWidth()
                .offset { IntOffset(offset.value.roundToInt(), 0) }
                .draggable(
                    orientation = Orientation.Horizontal,
                    state = rememberDraggableState { delta ->
                        scope.launch { offset.snapTo((offset.value + delta).coerceIn(-maxOffset, 0f)) }
                    },
                    onDragStarted = { dragging = true },
                    onDragStopped = {
                        dragging = false
                        val target = settleOffset(offset.value, maxOffset)
                        onOpenedChange(target < 0f)
                        offset.animateTo(target)
                    },
                ),
        ) { content() }
    }
}

/**
 * 松手后归到哪：**滑过一半吸开，否则弹回**。
 *
 * 抽成纯函数是因为这类"阈值判据"最容易写反，而写反的表现是"轻轻一碰整排按钮就弹出来"
 * 或"用力滑到底松手又缩回去"——两种都只能靠手感发现。
 *
 * @param offset 当前偏移（≤ 0，向左为负）
 * @param maxOffset 全部动作格的总宽（正数）
 */
internal fun settleOffset(offset: Float, maxOffset: Float): Float =
    if (offset <= -maxOffset / 2f) -maxOffset else 0f
