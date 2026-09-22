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
import androidx.compose.ui.text.AnnotatedString
import androidx.core.content.ContextCompat
import com.libeyond.imandroid.data.QrEncode
import com.libeyond.imandroid.sdk.IMClient
import com.libeyond.imandroid.sdk.api.QrCard
import com.libeyond.imandroid.ui.components.IMConfirmDialog
import com.libeyond.imandroid.ui.components.IMToast
import com.libeyond.imandroid.ui.components.qrToBitmap
import com.libeyond.imandroid.ui.screens.QrCardScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 群二维码 / 群邀请链接（对齐 iOS `pushGroupCardAsLink:`：同一份数据、同一套动作，
 * [asLink] 只切标题文案——两个入口点开的是同一枚 `/q/g/<token>`）。
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
            .onFailure { error = it.userMessage("获取二维码失败") }
    }

    // 展示页要给别人扫：临时拉满屏幕亮度，离开还原（同 QrCardHost）。
    val activity = context as? Activity
    DisposableEffect(activity) {
        val window = activity?.window
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
            toast = "二维码还没准备好"
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
        if (granted) saveNow() else toast = "没有存储权限，无法保存到相册"
    }

    QrCardScreen(
        card = card,
        title = if (asLink) "群邀请链接" else "群二维码",
        displayName = groupName,
        subtitle = "$memberCount 人", // 与详情页头部人数文案一致（GroupInfoScreen.kt）
        avatarUrl = avatarUrl,
        seed = convId,
        error = error,
        hint = if (asLink) "复制链接分享给好友，扫描/点击即可加入本群\n该链接 7 天内有效，重置后旧链接立即失效"
        else "邀请好友扫描二维码加入本群\n该码 7 天内有效，重置后旧码立即失效",
        onCopyLink = {
            val code = card?.codeString.orEmpty()
            if (code.isEmpty()) {
                toast = "链接还没准备好"
            } else {
                clipboard.setText(AnnotatedString(code))
                toast = "已复制链接"
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
        onShare = {
            val code = card?.codeString.orEmpty()
            val matrix = QrEncode.encode(code)
            if (matrix == null) {
                toast = "二维码还没准备好"
            } else {
                scope.launch {
                    val err = withContext(Dispatchers.IO) {
                        ImageExport.shareImage(context, qrToBitmap(matrix), code, "im_group_qr.png")
                    }
                    err?.let { toast = it }
                }
            }
        },
        onReset = if (canReset) { { confirmReset = true } } else null,
        onBack = onBack,
    )

    if (confirmReset) {
        IMConfirmDialog(
            title = "重置二维码？",
            message = "重置后旧二维码/旧链接立即失效，已经把它发出去的人将无法通过它加群。",
            confirmText = "确认重置",
            onConfirm = {
                scope.launch {
                    runCatchingCancellable { client.qr.resetGroupQR(convId) }
                        .onSuccess { card = it; error = ""; toast = "已重置，旧码已失效" }
                        .onFailure { toast = it.userMessage("重置失败") }
                }
            },
            onDismiss = { confirmReset = false },
        )
    }

    toast?.let { IMToast(it) { toast = null } }
}
