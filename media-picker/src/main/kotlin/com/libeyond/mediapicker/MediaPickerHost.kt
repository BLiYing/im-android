package com.libeyond.mediapicker

import androidx.compose.ui.res.stringResource
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 选图入口（模块的**唯一**公开入口）：权限 → 自建宫格页。
 *
 * **权限被拒不降级**，而是在同一页里显示空状态（说明 + 「去设置」，见 [MediaPickerDenied]），
 * 从系统设置回来时自动重查权限、直接进宫格。
 * 此前这里会降级回系统选择器（`PickMultipleVisualMedia`）——那条路没有原图勾选、没有编号，
 * 在 Android 11 上还是 DocumentsUI 文件浏览器，两套交互并存只会让人分不清；
 * 且被拒的用户仍有拍摄/文件两个入口可发图，不是被堵死。
 * 将来若上架审核（Play 的相册权限政策）要求改用系统 Photo Picker，接回思路见
 * IMServer `docs/UI_SPEC.md` §6.4（`PickMultipleVisualMedia` + 自己查 URI 元数据补出 [PickedMedia]）。
 *
 * @param skin 颜色与间距（模块不认识业务主题，见 [MediaPickerSkin]）
 * @param log  日志转发到调用方的统一入口（模块不直接打日志，见 [MediaPickerLog]）
 * @param includeVideo 是否收视频。**false 时连视频权限都不申请**（避免权限过度索取）。
 */
@Composable
fun MediaPickerHost(
    skin: MediaPickerSkin,
    onPicked: (List<PickedMedia>, sendOriginal: Boolean) -> Unit,
    onDismiss: () -> Unit,
    onToast: (String) -> Unit,
    log: MediaPickerLog = MediaPickerLog.None,
    includeVideo: Boolean = true,
) {
    val context = LocalContext.current
    val sdk = android.os.Build.VERSION.SDK_INT
    // 续页协程挂在组合的生命周期上：用 MainScope() 会在页面关掉后继续跑，且每次都新建一个
    val scope = rememberCoroutineScope()

    fun grantedSet(): Set<String> = MediaPermission.required(sdk, includeVideo)
        .filter { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
        .toSet()

    var access by remember { mutableStateOf(MediaPermission.access(sdk, grantedSet())) }
    var canVideo by remember { mutableStateOf(includeVideo && MediaPermission.canReadVideo(sdk, grantedSet())) }
    var buckets by remember { mutableStateOf<List<MediaBucket>>(emptyList()) }
    var bucketId by remember { mutableStateOf(MediaPick.ALL_BUCKET) }
    var assets by remember { mutableStateOf<List<MediaAsset>>(emptyList()) }
    var selected by remember { mutableStateOf<List<Long>>(emptyList()) }
    var sendOriginal by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    /** 已经没有下一页了：不置这个标志，滑到底会无限重查最后一页。 */
    var exhausted by remember { mutableStateOf(false) }
    /** 首次权限请求还没回来：这段时间不要闪「被拒」空状态（系统权限弹窗正压在上面）。 */
    var asking by remember { mutableStateOf(!MediaPermission.canBrowse(access)) }
    /** 首屏是否已加载过：避免 `LaunchedEffect(bucketId)` 在首次组合时和权限流程抢跑。 */
    var ready by remember { mutableStateOf(false) }

    val askPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        val granted = result.filterValues { it }.keys
        access = MediaPermission.access(sdk, granted)
        canVideo = includeVideo && MediaPermission.canReadVideo(sdk, granted)
        asking = false
    }

    // 从系统设置（或「管理授权的照片」）回来：权限可能已变，重查一次，不要让用户再点一遍
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val granted = grantedSet()
                access = MediaPermission.access(sdk, granted)
                canVideo = includeVideo && MediaPermission.canReadVideo(sdk, granted)
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    LaunchedEffect(Unit) {
        if (!MediaPermission.canBrowse(MediaPermission.access(sdk, grantedSet()))) {
            askPermission.launch(MediaPermission.required(sdk, includeVideo).toTypedArray())
        }
    }

    // 相册列表：全量归纳一次（几个轻列的一次游标扫描），不受分页影响——
    // 「只从最新 N 条归纳相册」会让只装旧照片的相册整个消失。
    LaunchedEffect(access, canVideo) {
        if (!MediaPermission.canBrowse(access)) return@LaunchedEffect
        buckets = withContext(Dispatchers.IO) { MediaStoreSource.buckets(context, canVideo, log) }
    }

    // 切相册 / 拿到权限 → 重取第一页
    LaunchedEffect(access, canVideo, bucketId) {
        if (!MediaPermission.canBrowse(access)) {
            assets = emptyList(); ready = false
            return@LaunchedEffect
        }
        loading = true
        val first = withContext(Dispatchers.IO) {
            MediaStoreSource.page(context, bucketId, 0, MediaStoreSource.PAGE, canVideo, log)
        }
        assets = first
        exhausted = first.size < MediaStoreSource.PAGE
        loading = false
        ready = true
    }

    if (!MediaPermission.canBrowse(access)) {
        if (!asking) {
            MediaPickerTheme(skin) {
                MediaPickerDenied(onCancel = onDismiss, onOpenSettings = { openAppSettings(context, log) })
            }
        }
        return
    }

    androidx.compose.runtime.CompositionLocalProvider(
        LocalPickerImageLoader provides rememberPickerImageLoader(),
    ) {
    val limitMsg = stringResource(R.string.mp_limit, MediaPick.LIMIT)
    MediaPickerTheme(skin) {
        MediaPickerScreen(
            buckets = buckets,
            assets = assets,
            bucketId = bucketId,
            onBucketChange = { bucketId = it },
            selected = selected,
            onToggle = { a ->
                val next = MediaPick.toggle(selected, a.id)
                if (next == null) onToast(limitMsg) else selected = next
            },
            sendOriginal = sendOriginal,
            onOriginalChange = { sendOriginal = it },
            access = access,
            loadingMore = loading,
            onLoadMore = {
                if (!loading && !exhausted && ready) {
                    loading = true
                    // 续页在 IO 线程；游标用「已加载条数」而不是页码——
                    // 页码在中途插入新照片时会错位，条数偏移最多重复一条。
                    val offset = assets.size
                    scope.launch {
                        val more = withContext(Dispatchers.IO) {
                            MediaStoreSource.page(context, bucketId, offset, MediaStoreSource.PAGE, canVideo, log)
                        }
                        val merged = PageMerge.merge(assets, more, MediaStoreSource.PAGE)
                        assets = merged.assets
                        exhausted = merged.exhausted
                        loading = false
                    }
                }
            },
            onSend = { onPicked(MediaPick.ordered(assets, selected).map { it.toPicked() }, sendOriginal) },
            onCancel = onDismiss,
            onToast = onToast,
            // Android 14 的「管理选中的照片」= 再申请一次，系统会弹重新选择的界面。
            // 低版本没有这个概念，传 null 让入口整个不出现。
            onManagePhotos = if (sdk >= 34) {
                { askPermission.launch(MediaPermission.required(sdk, includeVideo).toTypedArray()) }
            } else {
                null
            },
        )
    }
    }
}

/** 选好的一项，交给调用方去读字节/上传。模块**不碰上传**。 */
data class PickedMedia(
    val uri: String,
    val displayName: String,
    val mime: String,
    val sizeBytes: Long,
    val isVideo: Boolean,
)

internal fun MediaAsset.toPicked() = PickedMedia(uri, displayName, mime, sizeBytes, isVideo)

private fun openAppSettings(context: android.content.Context, log: MediaPickerLog) {
    try {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    } catch (e: Exception) {
        // 极个别精简 ROM 没有应用详情页：吞掉，用户仍可手动去设置（页面上的文案已说明）
        log.w("open_app_settings_failed", "err" to e.javaClass.simpleName)
    }
}
