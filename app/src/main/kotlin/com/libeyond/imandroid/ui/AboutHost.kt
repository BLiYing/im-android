package com.libeyond.imandroid.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import com.libeyond.imandroid.BuildConfig
import com.libeyond.imandroid.R
import com.libeyond.imandroid.data.AboutLinkValues
import com.libeyond.imandroid.data.AboutLinks
import com.libeyond.imandroid.data.AboutLinksStore
import com.libeyond.imandroid.data.LanguageStore
import com.libeyond.imandroid.sdk.IMClient
import com.libeyond.imandroid.ui.components.IMToast
import com.libeyond.imandroid.ui.components.LocalOpenLink
import com.libeyond.imandroid.ui.screens.AboutScreen

/**
 * 关于我们页的状态桥：四条链接（隐私/条款/技术支持/在线客服，后台可配置）走聊天链接同款的应用内浏览器（[LocalOpenLink] → `WebViewScreen`，
 * 对齐 iOS `SFSafariViewController`），打开时不带任何 token / Cookie（只有拉取链接那次请求带登录态，用来决定是否下发客服地址）。
 * 版本行点一下复制「Android 0.1.0 (1)」并弹轻提示。
 */
@Composable
fun AboutHost(client: IMClient, onBack: () -> Unit) {
    val openLink = LocalOpenLink.current
    val clipboard = LocalClipboardManager.current
    var toast by remember { mutableStateOf<String?>(null) }
    val openFailed = stringResource(R.string.about_open_failed)
    val copied = stringResource(R.string.about_version_copied)
    val version = AboutLinks.versionString(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE.toLong())

    // 链接由后台配置：先用上次缓存立刻渲染，进页后台刷新；拉不到退回内置路径（客服行隐藏）。
    val cacheKey = AboutLinksStore.key(client.host, authenticated = true)
    var links by remember(cacheKey) { mutableStateOf(AboutLinksStore.cached(cacheKey) ?: AboutLinkValues.FALLBACK) }
    LaunchedEffect(cacheKey) { links = AboutLinksStore.load(cacheKey) { client.aboutLinks.fetch() } }

    fun open(value: String) {
        val url = AboutLinks.resolve(value, client.host, BuildConfig.USE_TLS, LanguageStore.resolved)
        if (url == null || openLink == null) toast = openFailed else openLink(url)
    }

    BackHandler(onBack = onBack)
    AboutScreen(
        version = version,
        onPrivacy = { open(links.privacy) },
        onTerms = { open(links.terms) },
        onSupport = { open(links.support) },
        showCustomerService = AboutLinks.resolve(links.customerService, client.host, BuildConfig.USE_TLS, LanguageStore.resolved) != null,
        onCustomerService = { open(links.customerService) },
        onCopyVersion = {
            clipboard.setText(AnnotatedString(AboutLinks.clipboardText(version)))
            toast = copied
        },
        onBack = onBack,
    )
    toast?.let { IMToast(it) { toast = null } }
}
