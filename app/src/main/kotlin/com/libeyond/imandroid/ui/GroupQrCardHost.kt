package com.libeyond.imandroid.ui

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.core.content.ContextCompat
import com.libeyond.imandroid.R
import com.libeyond.imandroid.data.QrEncode
import com.libeyond.imandroid.i18n.Str
import com.libeyond.imandroid.sdk.IMClient
import com.libeyond.imandroid.sdk.api.QrCard
import com.libeyond.imandroid.ui.components.IMConfirmDialog
import com.libeyond.imandroid.ui.components.IMToast
import com.libeyond.imandroid.ui.components.TimeFormat
import com.libeyond.imandroid.ui.components.qrToBitmap
import com.libeyond.imandroid.ui.screens.QrCardScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 群二维码 / 群邀请链接（对齐 iOS `pushGroupCardAsLink:`：同一份数据、同一套动作，
 * [asLink] 切标题文案与版式（链接页不画码、只分享文字链接，可经 [onViewCode] 切到码页）——两个入口点开的是同一枚 `/q/g/<token>`）。
 *
 * 与 [QrCardHost]（名片码）几乎同构，唯独：取码/重置码走群接口、[canReset] 由调用方按
 * 群主/管理员判定传入（服务端仍会二次校验，这里只决定按钮显不显）。**只接「出示」这一半**——
 * 扫码识别 / 点链接跳转解析加群的接收方流程未接，见 `QrApi.kt` 顶部注释。
 */
@Composable
fun GroupQrCardHost(
    client: IMClient,
    convId: String,
    groupName: String,
    avatarUrl: String,
    memberCount: Int,
    asLink: Boolean,
    canReset: Boolean,
    onBack: () -> Unit,
    /** 链接页底部「查看二维码」：由调用方切到同一枚 token 的二维码页。 */
    onViewCode: (() -> Unit)? = null,
) {
    BackHandler(onBack = onBack)

    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    var card by remember(convId) { mutableStateOf<QrCard?>(null) }
    var error by remember(convId) { mutableStateOf("") }
    var toast by remember(convId) { mutableStateOf<String?>(null) }
    var confirmReset by remember(convId) { mutableStateOf(false) }
    var pendingSave by remember(convId) { mutableStateOf(false) }

    LaunchedEffect(convId) {
        runCatchingCancellable { client.qr.groupQR(convId) }
            .onSuccess { card = it; error = "" }
            .onFailure { error = it.userMessage(Str.s(R.string.qr_card_fetch_failed)) }
    }

    // 展示页要给别人扫：临时拉满屏幕亮度，离开还原（同 QrCardHost）。
    val activity = context as? Activity
    DisposableEffect(activity, asLink) {
        // 链接页不出示二维码，不需要拉亮度。
        val window = if (asLink) null else activity?.window
        val previous = window?.attributes?.screenBrightness
        window?.attributes = window?.attributes?.apply { screenBrightness = 1.0f }
        onDispose {
            if (window != null && previous != null) {
                window.attributes = window.attributes.apply { screenBrightness = previous }
            }
        }
    }

    fun saveNow() {
        val code = card?.codeString.orEmpty()
        val matrix = QrEncode.encode(code)
        if (matrix == null) {
            toast = Str.s(R.string.qr_card_not_ready)
            return
        }
        scope.launch {
            toast = withContext(Dispatchers.IO) {
                ImageExport.saveToGallery(context, qrToBitmap(matrix), "im_group_qr_${System.currentTimeMillis()}.png")
            }
        }
    }

    val requestWrite = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!pendingSave) return@rememberLauncherForActivityResult
        pendingSave = false
        if (granted) saveNow() else toast = Str.s(R.string.media_storage_permission_denied_save)
    }

    QrCardScreen(
        card = card,
        title = if (asLink) stringResource(R.string.qr_card_group_title_link) else stringResource(R.string.qr_card_group_title_code),
        displayName = groupName,
        subtitle = pluralStringResource(R.plurals.qr_branch_group_meta, memberCount, memberCount), // 「N 名成员」，与 iOS 群码卡片 / 扫码预览一致
        avatarUrl = avatarUrl,
        seed = convId,
        error = error,
        hint = groupQrHint(asLink, card?.expiresAt ?: 0L),
        onCopyLink = {
            val code = card?.codeString.orEmpty()
            if (code.isEmpty()) {
                toast = Str.s(R.string.qr_card_link_not_ready)
            } else {
                clipboard.setText(AnnotatedString(code))
                toast = Str.s(R.string.common_copied_link)
            }
        },
        onSave = {
            val needsPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
                PackageManager.PERMISSION_GRANTED
            if (needsPermission) {
                pendingSave = true
                requestWrite.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            } else {
                saveNow()
            }
        },
        linkMode = asLink,
        resetText = stringResource(R.string.qr_card_reset_group),
        onViewCode = onViewCode,
        onShare = {
            val code = card?.codeString.orEmpty()
            if (asLink) {
                // 链接页只分享文字链接，不带图。
                if (code.isEmpty()) toast = Str.s(R.string.qr_card_link_not_ready)
                else ImageExport.shareText(context, code)?.let { toast = it }
                return@QrCardScreen
            }
            val matrix = QrEncode.encode(code)
            if (matrix == null) {
                toast = Str.s(R.string.qr_card_not_ready)
            } else {
                scope.launch {
                    val err = withContext(Dispatchers.IO) {
                        ImageExport.shareImage(context, qrToBitmap(matrix), code, "im_group_qr.png")
                    }
                    err?.let { toast = it }
                }
            }
        },
        onReset = if (canReset && !asLink) { { confirmReset = true } } else null, // 链接页不放重置（重置只在二维码页）
        onBack = onBack,
    )

    if (confirmReset) {
        IMConfirmDialog(
            title = stringResource(R.string.qr_card_reset_confirm_title),
            message = stringResource(R.string.qr_card_reset_group_message),
            confirmText = stringResource(R.string.qr_confirm_reset),
            onConfirm = {
                scope.launch {
                    runCatchingCancellable { client.qr.resetGroupQR(convId) }
                        .onSuccess { card = it; error = ""; toast = Str.s(R.string.qr_card_reset_done) }
                        .onFailure { toast = it.userMessage(Str.s(R.string.qr_card_reset_failed)) }
                }
            },
            onDismiss = { confirmReset = false },
        )
    }

    toast?.let { IMToast(it) { toast = null } }
}

/** 码页/链接页底部提示：有到期时间就写具体日期（与 iOS 同一套文案），没有则退回固定 7 天口径。 */
@Composable
private fun groupQrHint(asLink: Boolean, expiresAt: Long): String = when {
    expiresAt > 0 -> stringResource(
        if (asLink) R.string.qr_card_group_link_hint_expiry else R.string.qr_card_group_hint_expiry,
        TimeFormat.dateLabel(expiresAt),
    )
    asLink -> stringResource(R.string.group_qr_hint_link)
    else -> stringResource(R.string.group_qr_hint_code)
}
