package com.libeyond.imandroid.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.libeyond.mediapicker.PickedMedia
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** 附件面板里两个**要出 App 才回得来**的入口。 */
internal class ChatMediaLaunchers(
    val openCamera: () -> Unit,
    val openFilePicker: () -> Unit,
)

/**
 * 相机与系统文件选择器的接线（从 `ChatHost` 拆出，2026-09-16，那个文件到 587/600 行）。
 *
 * 拆的边界是「**出 App 再回来**」这件事：这两条路都要先起一个 `ActivityResultLauncher`、
 * 等系统那一头回调，而相机还多一份**跨进程存活的落点** [Uri]——拍照结果不经内存回传，
 * 是写进我们事先给的那个 uri 里的，所以那个 uri 必须记在组合里、不能是调用时现算的局部变量。
 * 与 `MediaSendFlow`（真正的压缩/上传/发帧）分开：这里只管"拿到 uri"。
 */
@Composable
internal fun rememberChatMediaLaunchers(
    convId: String,
    scope: CoroutineScope,
    mediaSend: MediaSendFlow,
    onToast: (String) -> Unit,
): ChatMediaLaunchers {
    val context = LocalContext.current
    /** 相机产物的落点；拍完从这里读字节。 */
    var cameraUri by remember(convId) { mutableStateOf<Uri?>(null) }

    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = cameraUri
        cameraUri = null
        // ok=false 就是用户在相机里按了取消——**不提示**，那不是错误
        if (ok && uri != null) {
            scope.launch {
                mediaSend.send(
                    listOf(
                        PickedMedia(
                            uri = uri.toString(),
                            displayName = "camera_${System.currentTimeMillis()}.jpg",
                            mime = "image/jpeg",
                            // 相机产物走压缩路径，字节数由压缩后的结果决定，这里给 1 只为过
                            // 「0 = MediaStore 坏行」那道判断
                            sizeBytes = 1,
                            isVideo = false,
                        ),
                    ),
                    // 相机原片动辄 10MB+，默认压（与相册同口径）
                    sendOriginal = false,
                ) { onToast(it) }
            }
        }
    }

    val pickFile = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) scope.launch { mediaSend.sendFile(uri) { onToast(it) } }
    }

    return ChatMediaLaunchers(
        openCamera = {
            val uri = MediaSendFlow.newCameraUri(context)
            if (uri == null) {
                onToast("打不开相机")
            } else {
                cameraUri = uri
                takePhoto.launch(uri)
            }
        },
        // 任意类型：服务端按扩展名走白名单，端上不预筛——预筛只会让用户
        // 「明明有这个文件却选不中」，而真正的规则在服务端
        openFilePicker = { pickFile.launch(arrayOf("*/*")) },
    )
}
