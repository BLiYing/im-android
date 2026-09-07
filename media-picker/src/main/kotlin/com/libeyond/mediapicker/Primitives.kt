package com.libeyond.mediapicker

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit

/**
 * 模块内的最小文本原语。
 *
 * **刻意不依赖 material3**：本模块只吃 `compose.foundation` + `ui`，
 * 这样接入方用 material2、material3 还是完全自绘都行。为一个 `Text` 拖进整个 Material 主题体系，
 * 会把「模块不认识业务主题」这条设计当场作废——material3 的 `Text` 会去读
 * `LocalContentColor` / `MaterialTheme.typography`，那就是另一套主题了。
 */
@Composable
internal fun Text0(
    text: String,
    color: Color,
    size: TextUnit,
    modifier: Modifier = Modifier,
    weight: FontWeight = FontWeight.Normal,
    maxLines: Int = 1,
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = TextStyle(color = color, fontSize = size, fontWeight = weight),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

/** `combinedClickable` 目前仍标着实验性注解，集中在这里 opt-in，别撒到各个组件上。 */
@OptIn(ExperimentalFoundationApi::class)
internal fun Modifier.combinedClickableCompat(
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
): Modifier = composed {
    combinedClickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        onClick = onClick,
        onLongClick = onLongClick,
    )
}

/**
 * 选择器专用的 Coil `ImageLoader`。
 *
 * **为什么不用 Coil 的全局单例**：视频格子要出首帧，得注册 `VideoFrameDecoder`；
 * 往调用方的全局 loader 上塞 decoder 属于「模块偷偷改宿主全局状态」，
 * 换个 App 接进来就会莫名其妙。自己持一个，谁都不影响。
 */
internal val LocalPickerImageLoader = androidx.compose.runtime.staticCompositionLocalOf<coil.ImageLoader?> { null }

/**
 * 取当前该用的 loader：选择器内部用自己那一个；**在模块外单独使用
 * [ZoomableImage] 时回落到调用方的 Coil 单例**。
 *
 * 原先这个 CompositionLocal 的默认值是 `error(...)`，于是 `ZoomableImage` 虽然是 public、
 * 却只能在 `MediaPickerHost` 里用——聊天页要复用它就当场崩。导出一个组件却让它只能在
 * 自家内部跑，等于没导出。
 */
@Composable
internal fun currentImageLoader(): coil.ImageLoader =
    LocalPickerImageLoader.current
        ?: coil.Coil.imageLoader(androidx.compose.ui.platform.LocalContext.current)

@Composable
internal fun rememberPickerImageLoader(): coil.ImageLoader {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    return remember(ctx) {
        coil.ImageLoader.Builder(ctx)
            .components { add(coil.decode.VideoFrameDecoder.Factory()) }
            .build()
    }
}
