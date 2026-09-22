package com.libeyond.imandroid.ui.components

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 打开一个网页链接：应用内浏览器 `WebViewScreen`（对齐 iOS `openLink:` → SFSafariViewController）。
 *
 * 由 `MainScreen` 提供，浏览器页盖在一切页面之上。**走 CompositionLocal 而不是逐层传参**：点链接的地方
 * 散在气泡正文、链接预览卡、聊天记录、单聊详情与群资料的「链接」页签……逐层传要穿过 `ChatScreen`
 * （贴着 600 行红线）与好几层纯展示组件；而「在哪儿打开网页」与所在页面无关——与 [LocalMediaGate] 同一个理由。
 *
 * null = 这棵树里没有宿主（预览 / 登录页），调用方就只高亮、不可点。
 */
val LocalOpenLink = staticCompositionLocalOf<((String) -> Unit)?> { null }

/**
 * 打开扫一扫取景页（QRCODE P0 接收方半）。由 [com.libeyond.imandroid.ui.QrRouteHost] 提供。
 *
 * 目前只有会话列表 ＋ 菜单一处调用点，本可以直接传参；仍走 CompositionLocal 是为了与
 * [LocalOpenLink] 同一套路由宿主共用同一份状态机——两者命中同一个「解析→路由」出口
 * （见 `QrRouteHost.kt`），分开管反而要维护两份同构状态。
 */
val LocalOpenQrScan = staticCompositionLocalOf<(() -> Unit)?> { null }
