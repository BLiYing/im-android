package com.libeyond.imandroid.ui

import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.libeyond.imandroid.data.MediaAsset
import com.libeyond.imandroid.data.MediaPermission
import com.libeyond.imandroid.data.MediaPick
import com.libeyond.imandroid.data.MediaStoreSource
import com.libeyond.imandroid.sdk.logging.IMLog
import com.libeyond.imandroid.ui.screens.MediaPickerScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 选图入口：权限 → 自建宫格页；**权限被拒就降级回系统选择器**。
 *
 * 降级这条路是刻意保留的：读相册权限是敏感权限，用户完全可能拒绝，
 * 而「拒绝 = 发不了图」是不能接受的产品结果。降级后在 Android 11 上确实是 DocumentsUI
 * （体验差，正是自建这一页的原因），但**功能不消失**。
 *
 * 对调用方而言两条路是同一个出口：都回调一组 `content://` URI，**按发送顺序**。
 */
@Composable
internal fun MediaPickerHost(
    onPicked: (List<String>) -> Unit,
    onDismiss: () -> Unit,
    onToast: (String) -> Unit,
) {
    val context = LocalContext.current
    val sdk = android.os.Build.VERSION.SDK_INT
    val log = remember { IMLog.tag("IM.Media") }

    fun currentAccess(): MediaPermission.Access {
        val granted = MediaPermission.required(sdk)
            .filter { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
            .toSet()
        return MediaPermission.access(sdk, granted)
    }

    var access by remember { mutableStateOf(currentAccess()) }
    var assets by remember { mutableStateOf<List<MediaAsset>?>(null) }
    /** 已经把决定权交给系统选择器了：这一帧不要再渲染自建页，也不要再申请权限。 */
    var delegated by remember { mutableStateOf(false) }

    val systemPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MediaPick.LIMIT),
    ) { uris ->
        if (uris.isEmpty()) onDismiss() else onPicked(uris.map { it.toString() })
    }

    val askPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        val granted = result.filterValues { it }.keys
        access = MediaPermission.access(sdk, granted)
        if (!MediaPermission.canBrowse(access)) {
            // 被拒 → 不纠缠、不弹「去设置」说教，直接给系统选择器，用户照样能发图
            log.d("album_permission_denied_fallback")
            delegated = true
            systemPicker.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
            )
        }
    }

    // 权限就绪就读相册；access 变化（首次授权 / 部分授权改选）后要重读
    LaunchedEffect(access) {
        if (!MediaPermission.canBrowse(access)) {
            assets = null
            return@LaunchedEffect
        }
        assets = withContext(Dispatchers.IO) { MediaStoreSource.images(context) }
    }

    LaunchedEffect(Unit) {
        if (!MediaPermission.canBrowse(currentAccess())) {
            askPermission.launch(MediaPermission.required(sdk).toTypedArray())
        }
    }

    val list = assets
    if (delegated || !MediaPermission.canBrowse(access) || list == null) return

    MediaPickerScreen(
        assets = list,
        access = access,
        onSend = { picked -> onPicked(picked.map { it.uri }) },
        onCancel = onDismiss,
        onToast = onToast,
        // Android 14 的「管理选中的照片」= 再申请一次，系统会弹重新选择的界面。
        // 低版本没有这个概念，传 null 让入口整个不出现。
        onManagePhotos = if (sdk >= 34) {
            { askPermission.launch(MediaPermission.required(sdk).toTypedArray()) }
        } else {
            null
        },
    )
}
