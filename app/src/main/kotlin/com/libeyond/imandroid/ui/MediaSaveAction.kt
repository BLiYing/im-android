package com.libeyond.imandroid.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.libeyond.imandroid.R
import com.libeyond.imandroid.i18n.Str
import kotlinx.coroutines.launch

/**
 * 「保存到相册」的接线：权限分支 + 起协程 + 三段文案。
 *
 * 单独一个文件而不是塞进 `ChatHost`：`ChatHost` 已经 439/600 行，
 * 而这里的权限分支只跟「存相册」有关、跟聊天页没关系。
 *
 * **Android 10（Q）起走分区存储，一个权限都不要**；Q 以前才需要
 * `WRITE_EXTERNAL_STORAGE`（manifest 里那条带 `maxSdkVersion="28"`）。
 * 被拒时要说人话——静默失败会让用户以为是网络问题，反复点。
 */
@Composable
internal fun rememberMediaSaver(onToast: (String) -> Unit): (url: String, isVideo: Boolean) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 权限对话框弹出期间要记住用户点的是哪一条，授权回来才好接着存
    var pending by remember { mutableStateOf<Pair<String, Boolean>?>(null) }

    fun start(url: String, isVideo: Boolean) {
        // 视频要整段下载，几秒到几十秒都有可能——先给一句「正在保存…」，
        // 否则点了没反应，用户会连点好几次（每次都是一次完整下载）
        onToast(Str.s(R.string.chat_media_saving))
        scope.launch { onToast(MediaSaver.save(context, url, isVideo)) }
    }

    val askPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val p = pending
        pending = null
        when {
            !granted -> onToast(Str.s(R.string.media_storage_permission_denied_save))
            p != null -> start(p.first, p.second)
        }
    }

    return { url, isVideo ->
        val needsPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED
        if (needsPermission) {
            pending = url to isVideo
            askPermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            start(url, isVideo)
        }
    }
}
