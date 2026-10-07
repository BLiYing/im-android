package com.libeyond.imandroid.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.libeyond.imandroid.ui.theme.IMTheme

/**
 * 列表行的「长按原位浮起」（资料页文件 / 链接 / 语音 / 媒体格、群成员行；对齐 iOS 表格行的 `UIContextMenu`）。
 *
 * **与聊天气泡的做法不同**：气泡的预览是「按行数据再画一遍」（`ChatMessageMenu`）；这些行种类多、
 * 各自带播放器 / 下载态，逐种重绘必然漏。这里照 iOS `UITargetedPreview` 的思路——**行把自己的绘制录进一个
 * [GraphicsLayer]**，菜单里直接把这个层画出来：长什么样就浮起什么样，行里的进度 / 播放态也照常在动。
 *
 * 用法：页面提供 [LocalMenuLift]；行用 [rememberLiftHandle] + [liftSource]，长按时 [LiftHandle.lift]；
 * 菜单的 `preview` 传 [LiftPreview]。**原位只在预览真的出现时才隐藏**（[LiftPreview] 进组合时登记），
 * 长按后没弹出菜单（比如对方是群主、我能做的一项都没有）的那一行不会凭空消失。
 */
@Stable
class MenuLiftState {
    /** 当前被长按的那一行（[LiftHandle] 自己的身份）。 */
    internal var token by mutableStateOf<Any?>(null)
    internal var layer: GraphicsLayer? = null
    /** 预览正显示着（原位据此隐藏）。 */
    internal var shown by mutableStateOf(false)
    /** 被长按那一行在窗口里的矩形：菜单的锚点。 */
    var area: Rect = Rect.Zero
        private set

    internal fun lift(token: Any, layer: GraphicsLayer, area: Rect) {
        this.token = token
        this.layer = layer
        this.area = area
    }
}

/** null = 本页不做原位浮起（行照常可长按，只是不录层）。 */
val LocalMenuLift = staticCompositionLocalOf<MenuLiftState?> { null }

@Composable
fun rememberMenuLift(): MenuLiftState = remember { MenuLiftState() }

/** 一行的浮起句柄。页面没提供 [LocalMenuLift] 时是空壳（不建层、[lift] 不做事）。 */
class LiftHandle internal constructor(
    internal val state: MenuLiftState?,
    internal val layer: GraphicsLayer?,
) {
    /** 长按时调：登记「浮起的是我」。 */
    fun lift(area: Rect) {
        if (state != null && layer != null) state.lift(this, layer, area)
    }
}

@Composable
fun rememberLiftHandle(): LiftHandle {
    val state = LocalMenuLift.current
    val layer = if (state != null) rememberGraphicsLayer() else null
    val h = remember(state, layer) { LiftHandle(state, layer) }
    // 被浮起的那一行离开组合（菜单收起动画期间列表刷新 / 删掉了它）时层会被回收：
    // 把引用摘掉，预览就画空而不是去画一个已释放的层
    DisposableEffect(h) {
        onDispose { if (state != null && state.token === h) state.layer = null }
    }
    return h
}

/**
 * 把这一行的绘制录进句柄的层。放在行的修饰链**最外层**附近：在它之前的背景不会被录进去
 * （[LiftPreview] 会补一层卡片底色）。
 *
 * **只有被长按的那一行才录**：其余行照常直接画，滚动时不为每一格多走一层 RenderNode
 * （宫格 / 大群成员表一屏几十格，`/code-review` 2026-10-07 提）。长按那一刻 [LiftHandle.lift] 改了 token，
 * 这一行随即重画并开始录；被浮起时原位只录不画——位置留着，列表不跳。
 */
fun Modifier.liftSource(h: LiftHandle): Modifier {
    val state = h.state ?: return this
    val layer = h.layer ?: return this
    return this.drawWithContent {
        if (state.token !== h) {
            drawContent()
        } else {
            layer.record { this@drawWithContent.drawContent() }
            if (!state.shown) drawLayer(layer)
        }
    }
}

/** 菜单里的那份预览：画被长按那一行录下来的层，卡片底色 + 圆角 + 阴影（iOS 浮起的行就是一张小卡片）。 */
@Composable
fun LiftPreview(state: MenuLiftState) {
    if (state.layer == null) return
    DisposableEffect(state) {
        state.shown = true
        onDispose {
            state.shown = false
            state.token = null
        }
    }
    val h = with(LocalDensity.current) { state.area.height.toDp() }
    val shape = RoundedCornerShape(IMTheme.dimens.radiusCard)
    Box(
        Modifier.fillMaxWidth().height(h)
            .shadow(12.dp, shape)
            .clip(shape)
            .background(IMTheme.colors.cardBackground)
            // 每帧现取：源行离开组合时引用会被摘掉（见 rememberLiftHandle）
            .drawBehind { state.layer?.takeIf { !it.isReleased }?.let { drawLayer(it) } },
    )
}

/** 一个页面的长按菜单两件套：背景模糊（[MenuBackdropState]）+ 原位浮起（[MenuLiftState]）。 */
@Stable
class MenuSurface internal constructor(val backdrop: MenuBackdropState, val lift: MenuLiftState)

@Composable
fun rememberMenuSurface(): MenuSurface = remember { MenuSurface(MenuBackdropState(), MenuLiftState()) }

/** 把两件套交给下面的行与菜单。被模糊的内容根另挂 `Modifier.menuBackdrop(surface.backdrop)`。 */
@Composable
fun ProvideMenuSurface(surface: MenuSurface, content: @Composable () -> Unit) {
    androidx.compose.runtime.CompositionLocalProvider(
        LocalMenuBackdrop provides surface.backdrop,
        LocalMenuLift provides surface.lift,
        content = content,
    )
}
