package com.libeyond.imandroid.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.libeyond.imandroid.data.SheetDrag
import com.libeyond.imandroid.ui.theme.IMTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** 卡片顶到状态栏下方留的这一截：iOS pageSheet 在 iPhone 上顶部会露出底下那一页的一条边。 */
private val SHEET_TOP_GAP = 10.dp

/** 滑上来 / 滑下去的时长。与 [PushTransition] 同一条曲线，两种转场观感才一致。 */
private const val SHEET_ANIM_MS = 300
private val SheetEasing = CubicBezierEasing(0.2f, 0.9f, 0.3f, 1f)

/** 松手速度超过它算「甩」（dp/秒）。 */
private const val FLING_DP_PER_SEC = 1000

/** 高度量不出来时（理论上不会，调用方都是整屏）给个足够大的起点，保证卡片从屏幕外进来。 */
private const val UNBOUNDED_FALLBACK_PX = 4000f

/**
 * 卡片内容拿到的句柄。**要关卡片走 [dismiss]**，别直接把调用方的状态置空——那样卡片当场消失、没有滑下去的动画。
 */
class CardSheetScope internal constructor(private val close: (after: () -> Unit) -> Unit) {
    /**
     * 先把卡片滑下去，**动画结束后**再执行 [after]。
     * 「选好目标再发送」要等卡片走了再发：否则发送回执的吐司弹在半截卡片底下，看不见。
     */
    fun dismiss(after: () -> Unit) = close(after)
}

/**
 * 卡片式弹层（对齐 iOS `UIModalPresentationPageSheet`）：从底部滑上来、顶上留一截露出底下那页、
 * 圆角顶、遮罩压暗；**下拉关闭**（在列表顶部继续往下拉也会跟手）、点遮罩关、返回键关。
 *
 * iOS 那边转发选择、@提及、文件选择、已读详情、日期跳转、选联系人发名片等都是这种卡片，
 * 所以抽成组件——本端第一个用它的是转发选择页（2026-09-16 用户报：转发页不是 iOS 那种卡片）。
 *
 * @param onDismissed 用户**从外面**关掉卡片（遮罩 / 返回键 / 下拉）时、滑出屏幕之后回调。
 */
@Composable
fun IMCardSheet(
    onDismissed: () -> Unit,
    content: @Composable ColumnScope.(CardSheetScope) -> Unit,
) {
    val c = IMTheme.colors
    val d = IMTheme.dimens
    val scope = rememberCoroutineScope()
    val flingPx = with(LocalDensity.current) { FLING_DP_PER_SEC.dp.toPx() }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val fullPx = if (constraints.hasBoundedHeight) constraints.maxHeight.toFloat() else UNBOUNDED_FALLBACK_PX
        val motion = remember { SheetMotion(scope, fullPx) }
        // 这三个是普通字段，不是状态：每次重组把最新值写进去，手势回调里读到的就是最新的
        motion.fullPx = fullPx
        motion.flingPx = flingPx
        motion.onDismissed = onDismissed
        val sheet = remember(motion) { CardSheetScope { after -> motion.slideAway(after) } }

        LaunchedEffect(motion) { motion.animateTo(0f) }
        // **关闭途中也拦着返回键（吞掉不做事）**：放行的话它落到外层宿主的返回键上，宿主直接把卡片状态置空，
        // 卡片离开组合、动画协程被取消，`dismiss(after)` 里的 after 再也不跑——「点了发送、紧接着按返回」
        // 那次转发就被静默丢掉（2026-09-16 code-reviewer 那条的延伸）
        BackHandler { if (!motion.closing) motion.slideAway() }

        val connection = remember(motion) { SheetScrollConnection(motion) }

        // 遮罩：随卡片位置渐隐；点它关（卡片上方露出的那一截）
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = 1f - (motion.offset / motion.fullPx).coerceIn(0f, 1f) }
                .background(c.overlay)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                    motion.slideAway()
                },
        )
        // 手势挂在**不跟着位移**的这一层上：挂在位移的卡片本身上，手指与卡片一起动，拖动增量会被吃掉一截
        Box(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(top = SHEET_TOP_GAP)
                .nestedScroll(connection)
                .draggable(
                    state = rememberDraggableState { delta -> motion.dragBy(delta) },
                    orientation = Orientation.Vertical,
                    onDragStopped = { v -> motion.settle(v) },
                ),
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { translationY = motion.offset }
                    .clip(RoundedCornerShape(topStart = d.radiusCard, topEnd = d.radiusCard))
                    .background(c.pageBackground)
                    // 吞掉点击：卡片空白处的轻点不能漏到遮罩上把自己关了，更不能漏到底下那一页
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                    .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime).only(WindowInsetsSides.Bottom)),
            ) {
                content(sheet)
            }
        }
    }
}

/** 卡片的位置与开合。offset = 卡片离「完全展开」往下偏了多少像素。 */
@Stable
private class SheetMotion(private val scope: CoroutineScope, initialOffset: Float) {
    var offset by mutableFloatStateOf(initialOffset)
        private set
    var closing by mutableStateOf(false)
        private set
    var fullPx = initialOffset
    var flingPx = 0f
    var onDismissed: () -> Unit = {}
    private var job: Job? = null

    fun animateTo(target: Float, then: () -> Unit = {}) {
        job?.cancel()
        job = scope.launch {
            animate(offset, target, animationSpec = tween(SHEET_ANIM_MS, easing = SheetEasing)) { v, _ -> offset = v }
            then()
        }
    }

    /** 手指拖动：打断正在进行的回弹，卡片跟手；不许拖到展开位置之上。关闭途中不再响应。 */
    fun dragBy(delta: Float) {
        if (closing) return
        job?.cancel()
        offset = (offset + delta).coerceAtLeast(0f)
    }

    /** 滑出屏幕后再回调。关闭途中再触发（连按返回、松手撞上点遮罩）只算第一次。 */
    fun slideAway(after: () -> Unit = onDismissed) {
        if (closing) return
        closing = true
        animateTo(fullPx, after)
    }

    fun settle(velocity: Float) {
        if (closing) return
        when (SheetDrag.settle(offset, fullPx, velocity, flingPx)) {
            SheetDrag.Settle.Dismiss -> slideAway()
            SheetDrag.Settle.Restore -> animateTo(0f)
        }
    }
}

/** 列表与卡片抢同一个下拉手势：列表在顶时往下拉的余量归卡片，卡片被拉下来时往上推先归卡片。 */
private class SheetScrollConnection(private val motion: SheetMotion) : NestedScrollConnection {

    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        if (available.y >= 0f || motion.offset <= 0f || motion.closing) return Offset.Zero
        val used = maxOf(available.y, -motion.offset)
        motion.dragBy(used)
        return Offset(0f, used)
    }

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        // 只接手指拖出来的余量：列表惯性滚到顶的余量不该把卡片甩下去
        if (available.y <= 0f || source != NestedScrollSource.UserInput || motion.closing) return Offset.Zero
        motion.dragBy(available.y)
        return Offset(0f, available.y)
    }

    override suspend fun onPreFling(available: Velocity): Velocity {
        if (motion.offset <= 0f || motion.closing) return Velocity.Zero
        motion.settle(available.y)
        return available
    }
}
