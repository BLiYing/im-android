package com.libeyond.imandroid.ui.components

import android.os.Build
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.libeyond.imandroid.ui.theme.IMTheme

/**
 * 长按菜单的「背景模糊」（对齐 iOS `UIContextMenu`：菜单一开，底下整页高斯模糊）。
 *
 * **模糊必须挂在被盖住的那一层内容上，不能挂在菜单浮层自己身上**——浮层是内容的兄弟，
 * 模糊一个全屏透明浮层什么也模糊不到。所以拆成两半：
 * - 页面（聊天页、会话列表、收藏、资料页）在内容根上挂 [menuBackdrop]，并用 [LocalMenuBackdrop] 把状态交下去；
 * - [MessageContextMenu] 打开时自己来这里登记、开始收起时注销（收起动画与去模糊同时进行）。
 *
 * 用计数而不是布尔：待发气泡的菜单开在**它自己那一行**（全屏 Dialog），与聊天页的菜单是两个调用点，
 * 谁开谁登记，互不知道对方。
 *
 * **Android 11 及以下没有 RenderEffect**，`Modifier.blur` 在那里是空操作——不分支，
 * 由菜单改用更不透明的 [com.libeyond.imandroid.ui.theme.IMColors.menuScrimFlat] 兜住（2026-10-07 用户接受此降级）。
 */
@Stable
class MenuBackdropState {
    var openCount by mutableIntStateOf(0)
        internal set
    val active: Boolean get() = openCount > 0
}

/** null = 本页没挂模糊（菜单只铺材质色）。 */
val LocalMenuBackdrop = staticCompositionLocalOf<MenuBackdropState?> { null }

@Composable
fun rememberMenuBackdrop(): MenuBackdropState = remember { MenuBackdropState() }

/** 本机能不能做实时模糊（RenderEffect，API 31+）。 */
val menuBlurSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/** 模糊半径。24dp 时文字已糊成色块，与 iOS「几乎看不到底下内容」一致；再大只是多耗 GPU。 */
private val MENU_BLUR = 24.dp

/** 挂在**被菜单盖住的内容根**上：菜单开着时模糊（带过渡），关着时不建离屏层（零开销）。 */
@Composable
fun Modifier.menuBackdrop(state: MenuBackdropState): Modifier {
    if (!menuBlurSupported) return this
    val animate = IMTheme.appearance.animationsEnabled
    val t by animateFloatAsState(
        if (state.active) 1f else 0f,
        if (animate) tween(MENU_FADE_MS) else tween(0),
        label = "menuBackdrop",
    )
    return if (t <= 0f) this else this.blur(MENU_BLUR * t)
}

/** 菜单背景淡入 / 淡出时长（与模糊过渡同一个，两者同步）。 */
internal const val MENU_FADE_MS = 200

/**
 * 按住时的「先缩一下」（iOS 长按 `UIContextMenuInteraction` 在出菜单前的那段按压反馈）。
 * 缩到 [MENU_PRESS_SCALE]，菜单弹出时预览从同一个比例起跳，两段衔接没有跳变。
 *
 * 按压态来自 clickable 自己的 [InteractionSource]：在可滚动列表里它会**延后**约 100ms 才报按下，
 * 划过去的手指不会让气泡抖一下。
 *
 * @param origin 缩放中心。整行宽的点击区（待发气泡外包的那层）要给贴着气泡那一侧的边，否则缩的是整行、气泡会横移。
 */
@Composable
fun Modifier.pressShrink(
    source: InteractionSource,
    enabled: Boolean = true,
    origin: TransformOrigin = TransformOrigin.Center,
): Modifier {
    val pressed by source.collectIsPressedAsState()
    val animate = IMTheme.appearance.animationsEnabled
    val down = pressed && enabled && animate
    val s by animateFloatAsState(
        if (down) MENU_PRESS_SCALE else 1f,
        // 按下：用长按判定剩余的那段时间缓缓缩到位；松手：弹簧回弹
        if (down) tween(MENU_PRESS_MS) else spring(dampingRatio = 0.6f, stiffness = 600f),
        label = "pressShrink",
    )
    return if (s == 1f) this else this.graphicsLayer {
        scaleX = s
        scaleY = s
        transformOrigin = origin
    }
}

/** 按压缩放比例：iOS 长按时气泡缩到约 0.96。 */
internal const val MENU_PRESS_SCALE = 0.96f

/** 按下到长按触发（系统 400ms，扣掉列表约 100ms 的按下延迟）。 */
private const val MENU_PRESS_MS = 300
