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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.core.content.ContextCompat
import com.libeyond.imandroid.R
import com.libeyond.imandroid.data.QrEncode
import com.libeyond.imandroid.i18n.Str
import com.libeyond.imandroid.sdk.IMClient
import com.libeyond.imandroid.sdk.api.QrCard
import com.libeyond.imandroid.sdk.api.UserCard
import com.libeyond.imandroid.ui.components.IMConfirmDialog
import com.libeyond.imandroid.ui.components.IMToast
import com.libeyond.imandroid.ui.components.qrToBitmap
import com.libeyond.imandroid.ui.screens.QrCardScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 我的二维码（名片码）。取码 / 重置 / 复制链接 / 存相册 / 分享。
 *
 * 服务端**懒生成**：没有码就建一枚长期有效的，有就复用——所以反复进页拿到的是同一张，
 * 不必在本地缓存它。
 */
@Composable
fun QrCardHost(
    client: IMClient,
    me: UserCard?,
    /** true = 嵌在扫一扫页的「我的二维码」页签里：不画自己的顶栏，也不接返回键（由扫一扫页管）。 */
    embedded: Boolean = false,
    onBack: () -> Unit,
) {
    BackHandler(enabled = !embedded, onBack = onBack)

    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    var card by remember { mutableStateOf<QrCard?>(null) }
    var error by remember { mutableStateOf("") }
    var toast by remember { mutableStateOf<String?>(null) }
    var confirmReset by remember { mutableStateOf(false) }
    /** true = 权限拿到后立刻继续保存（Android 9 及更早才会走到）。 */
    var pendingSave by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        runCatchingCancellable { client.qr.myCard() }
            .onSuccess { card = it; error = "" }
            .onFailure { error = it.userMessage(Str.s(R.string.qr_card_fetch_failed)) }
    }

    // 展示页要给别人扫：临时拉满屏幕亮度，离开页面还原（对齐 iOS boostBrightness）。
    // 用 DisposableEffect 而不是在回调里还原——用户按返回、被来电打断、进程切后台，
    // 任何一条退出路径都必须还原，否则手机会一直停在满亮度。
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
            toast = Str.s(R.string.qr_card_not_ready)
            return
        }
        scope.launch {
            toast = withContext(Dispatchers.IO) {
                ImageExport.saveToGallery(context, qrToBitmap(matrix), "im_qr_${System.currentTimeMillis()}.png")
            }
        }
    }

    // Android 9 及更早保存到相册要 WRITE_EXTERNAL_STORAGE；Q 起不需要，压根不会走这条。
    val requestWrite = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!pendingSave) return@rememberLauncherForActivityResult
        pendingSave = false
        if (granted) saveNow() else toast = Str.s(R.string.media_storage_permission_denied_save)
    }

    QrCardScreen(
        showTopBar = !embedded,
        card = card,
        title = stringResource(R.string.settings_info_my_qr),
        displayName = me?.displayName.orEmpty().ifBlank { client.myPublicName() },
        subtitle = me?.handle.orEmpty(),
        avatarUrl = me?.avatarUrl.orEmpty(),
        seed = client.uid.orEmpty(),
        error = error,
        hint = stringResource(R.string.qr_card_my_hint),
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
        onShare = {
            val code = card?.codeString.orEmpty()
            val matrix = QrEncode.encode(code)
            if (matrix == null) {
                toast = Str.s(R.string.qr_card_not_ready)
            } else {
                scope.launch {
                    // 出图 **和写文件** 都在 IO 上：shareImage 会压一张 PNG 落到 cacheDir，
                    // 留在主线程是实打实的磁盘写（存相册那条路本来就在 IO，两条要一致）。
                    // startActivity 本身不限线程。
                    val err = withContext(Dispatchers.IO) {
                        ImageExport.shareImage(context, qrToBitmap(matrix), code, "im_qr.png")
                    }
                    err?.let { toast = it }
                }
            }
        },
        onReset = { confirmReset = true },
        onBack = onBack,
    )

    if (confirmReset) {
        IMConfirmDialog(
            title = stringResource(R.string.qr_card_reset_confirm_title),
            // 重置不可撤销且**影响外部世界**（旧码可能已经发出去了），所以强制二次确认
            message = stringResource(R.string.qr_card_reset_user_message),
            confirmText = stringResource(R.string.qr_confirm_reset),
            onConfirm = {
                scope.launch {
                    runCatchingCancellable { client.qr.resetMyCard() }
                        .onSuccess { card = it; error = ""; toast = Str.s(R.string.qr_card_reset_done) }
                        .onFailure { toast = it.userMessage(Str.s(R.string.qr_card_reset_failed)) }
                }
            },
            onDismiss = { confirmReset = false },
        )
    }

    toast?.let { IMToast(it) { toast = null } }
}
