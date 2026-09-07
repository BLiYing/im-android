package com.libeyond.imandroid.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.libeyond.imandroid.data.ProfileEdit
import com.libeyond.imandroid.sdk.IMClient
import com.libeyond.imandroid.sdk.api.UserCard
import com.libeyond.imandroid.ui.components.IMToast
import com.libeyond.imandroid.ui.screens.MyProfileScreen
import com.libeyond.imandroid.ui.screens.ProfileForm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 我的资料：只读 ↔ 编辑双态、改头像、保存。
 *
 * 保存的**两段式**与 iOS 一致：先 `PUT /users/me` 存昵称/头像/手机/标签，
 * 再（仅当真改了）`POST /users/me/username` 改公开句柄。
 * 后者失败**不回滚前者**——资料确实已经存上了，谎称"保存失败"更糟；
 * 只提示原因、留在页内让用户改个名再试。
 */
@Composable
fun MyProfileHost(
    client: IMClient,
    card: UserCard?,
    /** 资料变了通知外层重拉（「我」页头部要立刻显示新昵称/新头像）。 */
    onChanged: (UserCard) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var current by remember { mutableStateOf(card) }
    var form by remember { mutableStateOf(ProfileForm()) }
    /** 载入时的原始句柄。判「改没改」只跟它比，不跟当前输入比。 */
    var loadedUsername by remember { mutableStateOf("") }
    /**
     * 当前展示的头像 URL（选图上传后先落这里，随「保存」一起提交）。
     * **初值取自传入的 card**——写死空串的话，进页那几帧会先回退成首字母色块、
     * 等 `/users/me` 回来才跳成真头像，正是三端 2026-08-30 收口要消除的那种闪动。
     */
    var avatarUrl by remember { mutableStateOf(card?.avatarUrl.orEmpty()) }
    var editing by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var toast by remember { mutableStateOf<String?>(null) }

    /** 把一份权威资料同时铺进只读态与表单——两处各填一遍迟早漂移（iOS `applyProfile:`）。 */
    fun apply(c: UserCard) {
        current = c
        avatarUrl = c.avatarUrl
        loadedUsername = c.username
        form = ProfileForm(
            nickname = c.nickname,
            username = c.username,
            phone = c.phone,
            tags = ProfileEdit.tagsText(c.tags),
        )
    }

    LaunchedEffect(Unit) {
        // 进页重拉一次权威资料：外层传进来的那份可能是几分钟前的
        runCatchingCancellable { client.contacts.me() }
            .onSuccess { apply(it) }
            // 拉不到就用外层传进来的那份定型，别把页面停在空表单上
            .onFailure { card?.let { seed -> apply(seed) } }
    }

    val pickAvatar = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            toast = "上传中…"
            val bytes = withContext(Dispatchers.IO) { AvatarPrepare.fromUri(context, uri) }
            if (bytes == null) {
                toast = "图片处理失败，换一张试试"
                return@launch
            }
            runCatchingCancellable { client.upload.uploadAvatar(bytes) }
                .onSuccess {
                    avatarUrl = it.url
                    // 只更新预览，**不立刻提交**——与 iOS 同：头像跟着「保存」一起生效，
                    // 用户选错了还能点取消退回去
                    toast = "头像已更新，记得保存"
                }
                .onFailure { toast = it.userMessage("头像上传失败") }
        }
    }

    fun save() {
        if (saving) return
        val nickErr = ProfileEdit.nicknameError(form.nickname)
        if (nickErr != null) { error = nickErr; return }
        val renaming = ProfileEdit.shouldChangeUsername(form.username, loadedUsername)
        if (renaming) {
            val nameErr = ProfileEdit.usernameError(form.username)
            if (nameErr != null) { error = nameErr; return }
        }
        error = ""
        saving = true
        scope.launch {
            val updated = runCatchingCancellable {
                client.profile.update(
                    nickname = form.nickname.trim(),
                    avatarUrl = avatarUrl,
                    phone = form.phone.trim(),
                    tags = ProfileEdit.tagsFrom(form.tags),
                )
            }.onFailure { error = it.userMessage("保存失败") }
                .getOrNull()
            if (updated == null) { saving = false; return@launch }

            if (renaming) {
                runCatchingCancellable { client.changeUsername(form.username.trim()) }
                    .onFailure {
                        // 资料已存上，别谎称全盘失败；留在编辑态让用户换个名重试
                        error = it.userMessage("用户名未能修改")
                        saving = false
                        return@launch
                    }
            }
            // **重拉而不是就地拼**：改名走的是另一个接口，`update` 回的那份 username
            // 还是旧值，两处各拼一份迟早漂移。多一次 GET 换「显示的一定是服务端认的」。
            val fresh = runCatchingCancellable { client.contacts.me() }.getOrNull() ?: updated
            apply(fresh)
            onChanged(fresh)
            saving = false
            editing = false
            toast = "已保存"
        }
    }

    BackHandler { if (editing) { current?.let { apply(it) }; editing = false; error = "" } else onBack() }

    val c = current
    MyProfileScreen(
        form = form,
        onFormChange = { form = it },
        displayName = c?.displayName.orEmpty().ifBlank { client.myPublicName() },
        handle = c?.handle.orEmpty(),
        phone = c?.phone.orEmpty(),
        avatarUrl = avatarUrl,
        seed = client.uid.orEmpty(),
        editing = editing,
        saving = saving,
        error = error,
        onEnterEdit = { editing = true; error = "" },
        onCancelEdit = {
            // 丢弃未保存的输入（含刚传上去的头像预览），用最后一次权威数据回填
            current?.let { apply(it) }
            editing = false
            error = ""
        },
        onSave = { save() },
        onPickAvatar = {
            pickAvatar.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        },
        onBack = onBack,
    )

    toast?.let { IMToast(it) { toast = null } }
}
