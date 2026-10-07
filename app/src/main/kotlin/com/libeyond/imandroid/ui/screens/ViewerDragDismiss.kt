package com.libeyond.imandroid.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import com.libeyond.imandroid.data.ViewerDismiss
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** 查看器下拉关闭的状态：拖动偏移 + 由它算出的缩放 / 背景透明度。判据在 [ViewerDismiss]。 */
@Stable
internal class DragDismissState(
    private val scope: CoroutineScope,
    private val density: Float,
    private val close: () -> Unit,
) {
    private val offset = Animatable(0f)
    var dragging by mutableStateOf(false)
        private set

    val offsetY: Float get() = offset.value
    private val offsetDp: Float get() = offset.value / density
    val contentScale: Float get() = ViewerDismiss.scaleFor(offsetDp)
    val backdropAlpha: Float get() = ViewerDismiss.backdropAlphaFor(offsetDp)

    fun drag(dy: Float) {
        dragging = true
        scope.launch { offset.snapTo((offset.value + dy).coerceAtLeast(0f)) }
    }

    fun release(velocityY: Float) {
        if (ViewerDismiss.shouldClose(offsetDp, velocityY / density)) {
            close()
        } else {
            scope.launch {
                offset.animateTo(0f)
                dragging = false
            }
        }
    }
}

@Composable
internal fun rememberDragDismiss(onClose: () -> Unit): DragDismissState {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current.density
    val latestClose by rememberUpdatedState(onClose)
    return remember(density) { DragDismissState(scope, density) { latestClose() } }
}

/**
 * 竖向拖动交给 [state]。**挂在翻页容器外层**：横滑归翻页、放大后的单指拖动归 `ZoomableImage`、
 * 进度条拖动归 Slider——它们都会消费事件，本检测器见到已消费的就让路，只接没人要的竖向拖。
 */
internal fun Modifier.dragToDismiss(state: DragDismissState): Modifier = pointerInput(state) {
    val tracker = VelocityTracker()
    detectVerticalDragGestures(
        onDragStart = { tracker.resetTracking() },
        onDragEnd = { state.release(tracker.calculateVelocity().y) },
        onDragCancel = { state.release(0f) },
        onVerticalDrag = { change, dy ->
            tracker.addPosition(change.uptimeMillis, change.position)
            change.consume()
            state.drag(dy)
        },
    )
}
