package com.libeyond.mediapicker

import android.content.pm.PackageManager
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 选图入口（模块的**唯一**公开入口）：权限 → 自建宫格页；**权限被拒就降级回系统选择器**。
 *
 * 降级这条路是刻意保留的：读相册是敏感权限，用户完全可能拒绝，
 * 而「拒绝 = 发不了图」是不能接受的产品结果。降级后在 Android 11 上确实是 DocumentsUI
 * （体验差，正是自建这一页的原因），但**功能不消失**。
 *
 * 对调用方而言两条路是同一个出口：都回调一组 [PickedMedia]，**按发送顺序**。
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
    /** 已经把决定权交给系统选择器了：这一帧不要再渲染自建页，也不要再申请权限。 */
    var delegated by remember { mutableStateOf(false) }
    /** 首屏是否已加载过：避免 `LaunchedEffect(bucketId)` 在首次组合时和权限流程抢跑。 */
    var ready by remember { mutableStateOf(false) }

    val systemPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MediaPick.LIMIT),
    ) { uris ->
        if (uris.isEmpty()) {
            onDismiss()
        } else {
            // 系统选择器只给 URI，元数据要自己查一遍（名字/体积/类型都要用来发消息）
            onPicked(uris.map { describe(context, it) }, sendOriginal)
        }
    }

    val askPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        val granted = result.filterValues { it }.keys
        access = MediaPermission.access(sdk, granted)
        canVideo = includeVideo && MediaPermission.canReadVideo(sdk, granted)
        if (!MediaPermission.canBrowse(access)) {
            // 被拒 → 不纠缠、不弹「去设置」说教，直接给系统选择器，用户照样能发图
            log.d("album_permission_denied_fallback")
            delegated = true
            systemPicker.launch(
                PickVisualMediaRequest(
                    if (includeVideo) {
                        ActivityResultContracts.PickVisualMedia.ImageAndVideo
                    } else {
                        ActivityResultContracts.PickVisualMedia.ImageOnly
                    },
                ),
            )
        }
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

    if (delegated || !MediaPermission.canBrowse(access)) return

    androidx.compose.runtime.CompositionLocalProvider(
        LocalPickerImageLoader provides rememberPickerImageLoader(),
    ) {
    MediaPickerTheme(skin) {
        MediaPickerScreen(
            buckets = buckets,
            assets = assets,
            bucketId = bucketId,
            onBucketChange = { bucketId = it },
            selected = selected,
            onToggle = { a ->
                val next = MediaPick.toggle(selected, a.id)
                if (next == null) onToast("最多选 ${MediaPick.LIMIT} 个") else selected = next
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

/**
 * 系统选择器只回 URI，元数据得自己查。读不到就给一组能用的兜底值——
 * 这条路已经是降级路径了，再因为查不到名字而整个失败就太脆了。
 */
private fun describe(context: android.content.Context, uri: Uri): PickedMedia {
    val cr = context.contentResolver
    val mime = cr.getType(uri) ?: "image/jpeg"
    var name: String? = null
    var size = 0L
    try {
        cr.query(uri, null, null, null, null)?.use { c ->
            val nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIdx = c.getColumnIndex(OpenableColumns.SIZE)
            if (c.moveToFirst()) {
                if (nameIdx >= 0) name = c.getString(nameIdx)
                if (sizeIdx >= 0 && !c.isNull(sizeIdx)) size = c.getLong(sizeIdx)
            }
        }
    } catch (_: Exception) {
        // 兜底值已经够用，查不到就算了
    }
    val isVideo = mime.startsWith("video/")
    return PickedMedia(
        uri = uri.toString(),
        displayName = name ?: ((if (isVideo) "video_" else "image_") + System.currentTimeMillis() +
            "." + mime.substringAfterLast('/')),
        mime = mime,
        sizeBytes = size,
        isVideo = isVideo,
    )
}
