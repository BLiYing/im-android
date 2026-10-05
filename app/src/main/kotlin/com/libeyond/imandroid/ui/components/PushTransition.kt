package com.libeyond.imandroid.ui.components

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.lifecycle.Lifecycle
import com.libeyond.imandroid.data.PushDirection
import com.libeyond.imandroid.data.PushNav

/** 转场时长。iOS 的 push 约 350ms、弹簧曲线；取 300ms + 先快后慢，观感接近又不拖。 */
private const val PUSH_DURATION_MS = 300

/** 被盖住的那一页只让开 30%（iOS 的视差），不是整页推走。 */
private const val PARALLAX = 0.3f

private val PushEasing = CubicBezierEasing(0.2f, 0.9f, 0.3f, 1f)

private fun <T> pushSpec(): FiniteAnimationSpec<T> = tween(PUSH_DURATION_MS, easing = PushEasing)

/**
 * iOS 式 push / pop 转场：**前进**新页从右边盖进来、旧页左移让开；**后退**当前页向右滑走、下层页从左边回位。
 *
 * 页面仍由调用方的状态驱动（`when (page)` 原样搬进 [content]），本组件只管「怎么换」。
 * 方向与层叠顺序都从 [depthOf] 推（判据在 [PushNav]），调用方不用自己记"这次是进还是退"。
 *
 * 容器恒铺满：它装的是整页，不是控件。
 *
 * **退场中的那一页是惰性的**（见 [Inert]）：动画那 300ms 里新旧两页同时在组合里，
 * 不隔离的话，旧页的返回键回调还挂在分发器上（连按两下返回会被它吞掉一下），
 * 手指也还能点到正在滑走的列表（连点两下打开两个会话）。
 *
 * 转场进行中进场页会吞掉一次返回键（见 content 内的 BackHandler），防止连按返回把应用退出。
 *
 * @param contentKey 同一个 key 视为同一页，状态变了也不转场（比如会话实体被刷新）。
 */
@Composable
fun <S> PushTransition(
    targetState: S,
    depthOf: (S) -> Int,
    contentKey: (S) -> Any? = { it },
    content: @Composable (S) -> Unit,
) {
    AnimatedContent(
        targetState = targetState,
        modifier = Modifier.fillMaxSize(),
        transitionSpec = { pushTransform(depthOf(initialState), depthOf(targetState)) },
        label = "push",
        contentKey = contentKey,
    ) { state ->
        Inert(inert = transition.targetState == EnterExitState.PostExit) {
            // 转场进行中（进场页）：先吞一次返回键。连按两下返回时第二下落在刚显露的根页上，
            // 那里没人接返回键，会直接退出应用；页内自己的 BackHandler 注册得更晚、优先级更高，不受影响
            BackHandler(enabled = transition.targetState == EnterExitState.Visible && transition.currentState != EnterExitState.Visible) {}
            content(state)
        }
    }
}

/**
 * 被上层**盖住而不离开组合**的那一页（聊天页被详情页盖住：回来时列表原位不动）。
 * 盖上时左移让开 30%、揭开时回位，与 [PushTransition] 同一条时间曲线，两层才同步。
 */
@Composable
fun PushBase(covered: Boolean, content: @Composable () -> Unit) {
    val aside by animateFloatAsState(if (covered) 1f else 0f, pushSpec(), label = "push-base")
    Box(Modifier.fillMaxSize().graphicsLayer { translationX = -size.width * PARALLAX * aside }) {
        content()
    }
}

private fun <S> AnimatedContentTransitionScope<S>.pushTransform(fromDepth: Int, toDepth: Int): ContentTransform {
    val t = when (PushNav.directionOf(fromDepth, toDepth)) {
        PushDirection.Forward ->
            slideInHorizontally(pushSpec()) { it } togetherWith
                slideOutHorizontally(pushSpec()) { -(it * PARALLAX).toInt() }
        PushDirection.Back ->
            slideInHorizontally(pushSpec()) { -(it * PARALLAX).toInt() } togetherWith
                slideOutHorizontally(pushSpec()) { it }
    }
    // 谁画在上面按深度定：前进时新页盖住旧页，后退时滑走的那页（更深）仍在上面。
    // AnimatedContent 在**进场那一刻**记下 zIndex 并一直带到退场（字节码里取的是 specOnEnter），
    // 所以深度可以直接当 zIndex；同深度（换会话）按插入顺序，新页在上，正好是前进的样子。
    t.targetContentZIndex = toDepth.toFloat()
    // 不做尺寸动画：页面恒铺满。默认的 SizeTransform 会在「空内容 ↔ 整页」之间缩放容器，把滑动中的页裁掉
    return t using null
}

/**
 * 让一页「看得见、但不接收输入」。结构恒定（只换 CompositionLocal 的值与修饰符），
 * 切换惰性**不会**重建子树——滑走一半被按返回打断、又滑回来的那一页，状态原样还在。
 */
@Composable
private fun Inert(inert: Boolean, content: @Composable () -> Unit) {
    val real = LocalOnBackPressedDispatcherOwner.current
    val detached = remember(real) { real?.let(::DetachedBackOwner) }
    Box(Modifier.fillMaxSize().then(if (inert) BlockTouches else Modifier)) {
        if (real == null || detached == null) {
            // 只有预览里会走到：没有 Activity 就没有返回键，无从隔离
            content()
        } else {
            // BackHandler 按分发器做 key 重新登记（DisposableEffect(lifecycleOwner, backDispatcher)），
            // 换成一个不挂在 Activity 上的分发器，退场页里的全部返回键回调就当场失效
            CompositionLocalProvider(
                LocalOnBackPressedDispatcherOwner provides if (inert) detached else real,
            ) { content() }
        }
    }
}

/** 退场页用的返回键分发器：不挂在 Activity 上，按返回永远到不了它。 */
private class DetachedBackOwner(private val real: OnBackPressedDispatcherOwner) : OnBackPressedDispatcherOwner {
    override val lifecycle: Lifecycle get() = real.lifecycle
    override val onBackPressedDispatcher = OnBackPressedDispatcher()
}

/**
 * 在 **Initial** 阶段吞掉触摸：Initial 是自上而下派发的，父级先拿到、消费掉，
 * 子级的点击/滑动手势看到的就是已消费事件。挂在 Main 阶段就晚了——子级已经处理完。
 */
private val BlockTouches = Modifier.pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
    }
}
