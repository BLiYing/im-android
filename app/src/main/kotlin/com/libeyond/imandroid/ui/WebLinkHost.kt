package com.libeyond.imandroid.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.libeyond.imandroid.data.WebLinks
import com.libeyond.imandroid.ui.components.LocalOpenLink
import com.libeyond.imandroid.ui.screens.WebViewScreen

/** 浏览器页推上来 / 滑下去的时长（与卡片弹层、push 转场同为 300ms）。 */
private const val WEB_ANIM_MS = 300

/**
 * 应用内浏览器的宿主：提供 [LocalOpenLink]，并把浏览器页盖在 [content] 之上。
 *
 * 包在整个主界面外面（AppRoot）：聊天页、单聊详情、群资料、聊天记录里点开的链接都走这一个，
 * 浏览器页恒在最上层——iOS 的 SFSafariViewController 也是从当前最上层 present 的。
 * 返回键：浏览器页后进组合，它的 BackHandler 后注册、先吃到，底下各页的返回判定不必知道它。
 */
@Composable
internal fun WebLinkHost(content: @Composable () -> Unit) {
    var url by remember { mutableStateOf<String?>(null) }
    // 非 http/https 的地址不在 App 内打开（iOS `openLink:` 同样直接 return）
    val open: (String) -> Unit = remember { { raw -> WebLinks.entryUrl(raw)?.let { url = it } } }
    CompositionLocalProvider(LocalOpenLink provides open) {
        Box(Modifier.fillMaxSize()) {
            content()
            WebPageLayer(url = url, onClose = { url = null })
        }
    }
}

/**
 * 用 AnimatedContent 而不是 `if (url != null)`：关掉时地址已经置空，要靠它把旧页留到滑下去为止。
 * 目标是 null 时这一层是个空盒子，不接触摸，底下的页照常可点。
 */
@Composable
private fun WebPageLayer(url: String?, onClose: () -> Unit) {
    AnimatedContent(
        targetState = url,
        modifier = Modifier.fillMaxSize(),
        transitionSpec = {
            val t = slideInVertically(tween(WEB_ANIM_MS)) { it } togetherWith
                slideOutVertically(tween(WEB_ANIM_MS)) { it }
            // 进场时浏览器页在上；退场时它是进场那一刻记下的 zIndex=1，仍在「空」之上滑走
            t.targetContentZIndex = if (targetState == null) 0f else 1f
            t using null
        },
        label = "web",
    ) { u ->
        if (u != null) WebViewScreen(url = u, onClose = onClose)
    }
}
