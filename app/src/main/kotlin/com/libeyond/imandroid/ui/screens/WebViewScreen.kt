package com.libeyond.imandroid.ui.screens

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.view.ViewGroup
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.composables.icons.lucide.Copy
import com.composables.icons.lucide.Ellipsis
import com.composables.icons.lucide.Globe
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.RotateCw
import com.composables.icons.lucide.X
import com.libeyond.imandroid.R
import com.libeyond.imandroid.data.LinkDetect
import com.libeyond.imandroid.data.WebLinks
import com.libeyond.imandroid.i18n.Str
import com.libeyond.imandroid.sdk.logging.IMLog
import com.libeyond.imandroid.ui.components.IMToast
import com.libeyond.imandroid.ui.components.IMTopBar
import com.libeyond.imandroid.ui.openInBrowser
import com.libeyond.imandroid.ui.theme.IMTheme

/**
 * 应用内浏览器（对齐 iOS `openLink:` 用的 `SFSafariViewController`）。可复用：谁要在 App 内打开网页，
 * 调 `LocalOpenLink.current` 即可，本页由 `WebLinkHost` 统一盖在最上层。
 *
 * 此前本端**没有这一页**：气泡里的链接既不高亮也点不动，详情页「链接」页签与聊天记录里的文件
 * 是甩给系统浏览器（2026-09-16 用户报，根因分析见 im-android `current_task.md`）。
 *
 * ### 安全边界（WebView 的几个经典坑，一条都不能松）
 * - 不开 `addJavascriptInterface`，不开文件 / content 访问：网页拿不到本机任何东西；
 * - 页内跳转只放行 http/https；拉起应用的 scheme 只在**用户点出来**时交给系统，`intent://` 清掉显式组件
 *   （判据在 [WebLinks.navigationFor]，有单测）；
 * - 证书错误一律 `cancel()`，不给「继续访问」；
 * - 明文 http：release 的网络安全配置不放行（PROTOCOL §0.1），加载失败时如实说，并给「用浏览器打开」。
 *
 * 日志**只记错误码不记地址**：链接里常带着对方的私人参数（LOGGING 脱敏约定）。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun WebViewScreen(url: String, onClose: () -> Unit) {
    val c = IMTheme.colors
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val log = remember { IMLog.tag("IM.Web") }
    var title by remember(url) { mutableStateOf("") }
    var current by remember(url) { mutableStateOf(url) }
    var progress by remember(url) { mutableIntStateOf(0) }
    var failure by remember(url) { mutableStateOf<String?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }

    val webView = remember(url) {
        WebView(context).apply {
            settings.javaScriptEnabled = true   // 绝大多数网页离了 JS 就是白屏；SFSafari 同样执行 JS
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            // 不支持多窗口：target=_blank 就在本页打开，不会凭空冒出一个接不住的新窗口
            settings.setSupportMultipleWindows(false)
            settings.mediaPlaybackRequiresUserGesture = true
            // 下载链接交给系统浏览器：本页没有下载管理，吞掉的话表现是「点了没反应」
            setDownloadListener { dl, _, _, _, _ -> openInBrowser(context, dl) { toast = it } }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    if (!request.isForMainFrame) return false
                    val target = request.url.toString()
                    return when (WebLinks.navigationFor(target, request.hasGesture())) {
                        WebLinks.Nav.InPage -> false
                        WebLinks.Nav.External -> {
                            openExternalApp(context, target) { toast = it }
                            true
                        }
                        WebLinks.Nav.Block -> true
                    }
                }

                override fun onPageStarted(view: WebView, pageUrl: String?, favicon: Bitmap?) {
                    failure = null
                    title = ""
                    if (!pageUrl.isNullOrBlank()) current = pageUrl
                }

                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    // 子资源（图片、统计脚本）失败不算页面打不开
                    if (!request.isForMainFrame) return
                    log.w("web_load_failed", "code" to error.errorCode)
                    failure = WebLinks.failureText(request.url.toString())
                }

                override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                    handler.cancel()
                    log.w("web_ssl_error", "primary" to error.primaryError)
                    if (error.url == current) failure = Str.s(R.string.webview_ssl_error)
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, newProgress: Int) {
                    progress = newProgress
                }

                override fun onReceivedTitle(view: WebView, received: String?) {
                    // 没有 <title> 的页面这里给的是地址本身，当标题显示就是一长串，不如退回站点名
                    title = received?.takeIf { it != view.url }.orEmpty()
                }
            }
            loadUrl(url)
        }
    }
    DisposableEffect(webView) {
        onDispose {
            webView.stopLoading()
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.destroy()
        }
    }
    // 切后台暂停网页里的音视频与定时器（iOS 的 SFSafari 由系统管，这里要自己跟生命周期）
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, webView) {
        val obs = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> webView.onPause()
                Lifecycle.Event.ON_RESUME -> webView.onResume()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    // 返回键先在网页里后退，退到头才关页
    BackHandler { if (webView.canGoBack()) webView.goBack() else onClose() }

    Column(
        Modifier
            .fillMaxSize()
            .background(c.pageBackground)
            // 盖在别的页之上：只有背景的地方也要拦住触摸，否则点穿到底下那页
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            .systemBarsPadding(),
    ) {
        val host = LinkDetect.hostOf(current)
        val loading = progress in 1..99
        val copiedLinkToast = stringResource(R.string.common_copied_link)
        IMTopBar(
            title = title.ifBlank { host },
            subtitle = if (title.isBlank()) "" else host,
            leftIcon = Lucide.X,
            leftDescription = stringResource(R.string.common_close),
            onLeft = onClose,
            right = {
                WebMenu(
                    open = menuOpen,
                    onOpen = { menuOpen = true },
                    onDismiss = { menuOpen = false },
                    onReload = { failure = null; webView.reload() },
                    onCopy = { clipboard.setText(AnnotatedString(current)); toast = copiedLinkToast },
                    onBrowser = { openInBrowser(context, current) { toast = it } },
                )
            },
            showDivider = !loading,
        )
        if (loading) {
            LinearProgressIndicator(
                progress = { progress / 100f },
                modifier = Modifier.fillMaxWidth().height(2.dp),
                color = c.accent,
                trackColor = c.separator,
            )
        }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            AndroidView(factory = { webView }, modifier = Modifier.fillMaxSize())
            failure?.let { msg ->
                WebFailure(
                    message = msg,
                    onRetry = { failure = null; webView.reload() },
                    onBrowser = { openInBrowser(context, current) { toast = it } },
                )
            }
        }
    }
    toast?.let { t -> IMToast(t) { toast = null } }
}

@Composable
private fun WebMenu(
    open: Boolean,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
    onReload: () -> Unit,
    onCopy: () -> Unit,
    onBrowser: () -> Unit,
) {
    val c = IMTheme.colors
    val d = IMTheme.dimens
    Box {
        Image(
            imageVector = Lucide.Ellipsis,
            contentDescription = stringResource(R.string.common_more),
            modifier = Modifier.size(d.topBarIcon).clickable(onClick = onOpen),
            colorFilter = ColorFilter.tint(c.accent),
        )
        DropdownMenu(expanded = open, onDismissRequest = onDismiss) {
            WebMenuItem(Lucide.RotateCw, stringResource(R.string.common_refresh)) { onDismiss(); onReload() }
            WebMenuItem(Lucide.Copy, stringResource(R.string.qr_copy_link)) { onDismiss(); onCopy() }
            WebMenuItem(Lucide.Globe, stringResource(R.string.common_open_in_browser)) { onDismiss(); onBrowser() }
        }
    }
}

@Composable
private fun WebMenuItem(icon: ImageVector, label: String, onClick: () -> Unit) {
    val c = IMTheme.colors
    DropdownMenuItem(
        text = { Text(label, color = c.textPrimary) },
        leadingIcon = { Image(icon, null, Modifier.size(18.dp), colorFilter = ColorFilter.tint(c.textPrimary)) },
        onClick = onClick,
    )
}

/** 主页面打不开时盖在网页上的说明：重试 / 用浏览器打开。 */
@Composable
private fun WebFailure(message: String, onRetry: () -> Unit, onBrowser: () -> Unit) {
    val c = IMTheme.colors
    Column(
        Modifier.fillMaxSize().background(c.pageBackground).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(message, color = c.textSecondary, fontSize = 15.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        Row {
            Text(stringResource(R.string.common_retry), color = c.accent, fontSize = 16.sp, modifier = Modifier.clickable(onClick = onRetry).padding(12.dp))
            Spacer(Modifier.width(16.dp))
            Text(stringResource(R.string.common_open_in_browser), color = c.accent, fontSize = 16.sp, modifier = Modifier.clickable(onClick = onBrowser).padding(12.dp))
        }
    }
}

/**
 * 把页内一次**用户点出来的**非网页跳转交给系统（`tel:`、`weixin://`、`intent://`…）。
 *
 * `intent://` 必须清掉显式组件与 selector、限定 BROWSABLE：不清的话网页能借它点名启动别家
 * （或本 App）**未导出**的页面——这是 WebView 里 intent scheme 的经典漏洞。
 */
private fun openExternalApp(context: Context, url: String, onToast: (String) -> Unit) {
    val intent = runCatching {
        if (url.startsWith("intent:", ignoreCase = true)) {
            Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
        } else {
            Intent(Intent.ACTION_VIEW, Uri.parse(url))
        }
    }.getOrNull()
    if (intent == null) {
        onToast(Str.s(R.string.webview_link_unopenable))
        return
    }
    intent.addCategory(Intent.CATEGORY_BROWSABLE)
    intent.component = null
    intent.selector = null
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }.onFailure { onToast(Str.s(R.string.webview_no_app_for_link)) }
}
