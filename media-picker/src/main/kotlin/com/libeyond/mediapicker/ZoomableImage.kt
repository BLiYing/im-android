package com.libeyond.mediapicker

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import coil.compose.AsyncImage

/**
 * 可缩放图片（双指缩放 / 拖动 / 双击放大复位）。
 *
 * **这是本工程第一份可缩放查看器**，刻意放在模块里导出——聊天页将来要做大图查看器时
 * 直接复用它，而不是再搓第二份。两份缩放实现必然在「边界回弹」「双击倍率」这些细节上分叉。
 *
 * ### 边界钳制为什么必须有
 * 不钳制的话，放大后一甩就能把图拖出屏幕，剩一片黑，用户以为图没了。
 * 钳制口径：**平移量不超过「放大后超出容器的那一半」**；缩放回 1 倍时平移强制归零。
 */
@Composable
fun ZoomableImage(
    model: Any?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    maxScale: Float = 4f,
    doubleTapScale: Float = 2.5f,
    /**
     * 加载期 / 失败时画什么（通常是消息里内嵌缩略的磨砂版）。
     *
     * **没有它就是一屏纯黑**：原件动辄几 MB，未下载时查看器是现拉的，那几秒里用户看到的是
     * 黑屏，只能理解成"这张图没了"（2026-09-17 用户报）。iOS 同款：
     * `IMMediaViewerViewController.showThumbPlaceholder` 先画内嵌 thumb 的模糊版，原图到达后原地替换。
     */
    placeholder: androidx.compose.ui.graphics.painter.Painter? = null,
) {
    var scale by remember(model) { mutableFloatStateOf(1f) }
    var offsetX by remember(model) { mutableFloatStateOf(0f) }
    var offsetY by remember(model) { mutableFloatStateOf(0f) }
    var box by remember(model) { mutableStateOf(0f to 0f) }

    fun clamp() {
        val (w, h) = box
        // 放大后单边溢出量的一半 = 允许的最大平移
        val maxX = (w * (scale - 1f) / 2f).coerceAtLeast(0f)
        val maxY = (h * (scale - 1f) / 2f).coerceAtLeast(0f)
        offsetX = offsetX.coerceIn(-maxX, maxX)
        offsetY = offsetY.coerceIn(-maxY, maxY)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            // 双击那条**必须排在缩放那条前面**：后者会消费掉手势，排在它后面就永远收不到点击。
            .pointerInput(model) {
                detectTapGestures(
                    onDoubleTap = {
                        if (scale > 1f) {
                            scale = 1f; offsetX = 0f; offsetY = 0f
                        } else {
                            scale = doubleTapScale.coerceAtMost(maxScale)
                            clamp()
                        }
                    },
                )
            }
            // 自己写手势循环而不是用 detectTransformGestures：后者**无条件消费**事件，
            // 结果是 1 倍时的单指横滑也被吃掉，外层的 HorizontalPager 永远翻不了页
            // （2026-09-07 真机实测就是这个症状：双击不放大、左右滑不翻页）。
            // 规则：**双指任何时候都归我；单指只在放大状态下归我**，1 倍单指原样放行。
            .pointerInput(model) {
                box = size.width.toFloat() to size.height.toFloat()
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val canceled = event.changes.any { it.isConsumed }
                        if (!canceled) {
                            val multiTouch = event.changes.count { it.pressed } > 1
                            if (multiTouch || scale > 1f) {
                                val zoomChange = event.calculateZoom()
                                val panChange = event.calculatePan()
                                scale = (scale * zoomChange).coerceIn(1f, maxScale)
                                if (scale <= 1f) {
                                    // 回到 1 倍就强制归位，否则会停在一个偏移的 1 倍视图上，
                                    // 看着像「图跑偏了」
                                    offsetX = 0f; offsetY = 0f
                                } else {
                                    offsetX += panChange.x; offsetY += panChange.y
                                    clamp()
                                }
                                if (zoomChange != 1f || panChange != Offset.Zero) {
                                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                                }
                            }
                        }
                    } while (!canceled && event.changes.any { it.pressed })
                }
            },
    ) {
        AsyncImage(
            imageLoader = currentImageLoader(),
            model = model,
            contentDescription = contentDescription,
            contentScale = ContentScale.Fit,
            placeholder = placeholder,
            error = placeholder,
            fallback = placeholder,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offsetX,
                    translationY = offsetY,
                ),
        )
    }
}

/** 让 [ZoomableImage] 的钳制口径能被单测覆盖（Compose 手势本身进不了 JVM 单测）。 */
object ZoomBounds {
    /** 给定容器边长与缩放倍率，允许的最大平移量（单边）。 */
    fun maxTranslation(containerPx: Float, scale: Float): Float =
        (containerPx * (scale - 1f) / 2f).coerceAtLeast(0f)

    /** 钳制后的平移量；[scale] ≤ 1 时恒为 0。 */
    fun clamp(value: Float, containerPx: Float, scale: Float): Float {
        if (scale <= 1f) return 0f
        val max = maxTranslation(containerPx, scale)
        return value.coerceIn(-max, max)
    }
}

/** 供预览页取当前密度换算用（保留给调用方，避免各页各写一遍）。 */
@Composable
internal fun pxOf(dp: androidx.compose.ui.unit.Dp): Float = with(LocalDensity.current) { dp.toPx() }
