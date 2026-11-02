package com.libeyond.imandroid.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import com.libeyond.imandroid.BuildConfig
import com.libeyond.imandroid.R
import com.libeyond.imandroid.data.AboutLinks
import com.libeyond.imandroid.data.LanguageStore
import com.libeyond.imandroid.sdk.IMClient
import com.libeyond.imandroid.ui.components.IMToast
import com.libeyond.imandroid.ui.components.LocalOpenLink
import com.libeyond.imandroid.ui.screens.AboutScreen

/**
 * 关于我们页的状态桥：三条链接走聊天链接同款的应用内浏览器（[LocalOpenLink] → `WebViewScreen`，
 * 对齐 iOS `SFSafariViewController`），法务页是公开静态页，不带任何 token / Cookie。
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

    fun open(path: String) {
        val url = AboutLinks.legalUrl(client.host, BuildConfig.USE_TLS, path, LanguageStore.resolved)
        if (url == null || openLink == null) toast = openFailed else openLink(url)
    }

    BackHandler(onBack = onBack)
    AboutScreen(
        version = version,
        onPrivacy = { open(AboutLinks.PATH_PRIVACY) },
        onTerms = { open(AboutLinks.PATH_TERMS) },
        onSupport = { open(AboutLinks.PATH_SUPPORT) },
        onCopyVersion = {
            clipboard.setText(AnnotatedString(AboutLinks.clipboardText(version)))
            toast = copied
        },
        onBack = onBack,
    )
    toast?.let { IMToast(it) { toast = null } }
}
