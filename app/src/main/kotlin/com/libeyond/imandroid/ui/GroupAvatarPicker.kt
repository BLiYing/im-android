package com.libeyond.imandroid.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.libeyond.imandroid.R
import com.libeyond.imandroid.i18n.Str
import com.libeyond.imandroid.sdk.IMClient
import com.libeyond.imandroid.sdk.api.GroupInfo
import com.libeyond.imandroid.sdk.logging.IMLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 换群头像：**选图 → 压成 JPEG → POST /avatar → PUT /groups/{id}**。
 *
 * 从 `GroupInfoHost` 拆出（2026-09-16，那个文件到 585/600 行），**行为未改**。
 *
 * 两条不能丢的纪律：
 * ① `PUT /groups/{id}` 是**整体替换**（PROTOCOL §11）——名字与简介必须原样带回去，否则被清空；
 * ② **这里立刻提交**，不像「我的资料」那侧攒到点保存。群管理页没有保存按钮，每一项都即时生效，
 *    头像若只更新预览就成了唯一一个"改了但没生效"的项。
 *
 * @param info 取**当前**群资料的入口（写成 lambda 而不是值：上传要几秒，期间群资料可能已被别处刷新）。
 * @param runManage 执行一次管理动作并把成败说给用户听（宿主提供：它持有 toast 与刷新）。
 * @return 点「换头像」时调的那个函数。
 */
@Composable
internal fun rememberGroupAvatarPicker(
    client: IMClient,
    convId: String,
    info: () -> GroupInfo?,
    scope: CoroutineScope,
    onToast: (String) -> Unit,
    runManage: (String, suspend () -> Unit) -> Unit,
): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        val g0 = info()
        if (uri == null || g0 == null) return@rememberLauncherForActivityResult
        scope.launch {
            onToast(Str.s(R.string.common_uploading))
            val bytes = withContext(Dispatchers.IO) { AvatarPrepare.fromUri(context, uri) }
            if (bytes == null) {
                onToast(Str.s(R.string.common_image_process_failed))
                return@launch
            }
            val up = runCatching { client.upload.uploadAvatar(bytes) }
            val url = up.getOrNull()?.url
            if (url == null) {
                onToast(Str.s(R.string.net_error_avatar_upload_failed))
                IMLog.tag("IM.Group").w("group_avatar_upload_failed")
                return@launch
            }
            runManage(Str.s(R.string.group_avatar_change)) { client.groups.updateInfo(convId, g0.name, url, g0.intro) }
        }
    }
    return { launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
}
