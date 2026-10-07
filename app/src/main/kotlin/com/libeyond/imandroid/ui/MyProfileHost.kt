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
import com.libeyond.imandroid.R
import com.libeyond.imandroid.data.ProfileEdit
import com.libeyond.imandroid.i18n.Str
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
    /**
     * 进页即编辑态（「我」页右上「编辑」）。此时编辑就是这一趟的目的：取消 / 返回 / 保存成功都直接 [onBack]，
     * 不落回只读态——否则用户要多点一次返回（2026-10-07 用户报）。点头部进来的仍是只读 ↔ 编辑双态。
     */
    startEditing: Boolean = false,
    /** 资料变了通知外层重拉（「我」页头部要立刻显示新昵称/新头像）。 */
    onChanged: (UserCard) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var current by remember { mutableStateOf(card) }
    // 表单**先用传入的那份**铺好：直进编辑态时首帧就是表单，空着等 /users/me 回来，用户会对着一屏空白输入框
    var form by remember { mutableStateOf(card?.let(::formOf) ?: ProfileForm()) }
    /** 载入时的原始句柄。判「改没改」只跟它比，不跟当前输入比。 */
    var loadedUsername by remember { mutableStateOf(card?.username.orEmpty()) }
    /** 进页时铺进表单的那份（传入的本机副本）：迟到的 /users/me 只覆盖用户还没动过的字段，见 merge。 */
    val seedForm = remember { form }
    /**
     * /users/me 拉到了没有。**没拉到不许保存**：本机副本只存昵称 / 句柄 / 头像（MyProfileCodec），
     * 手机号与标签是空的——这时保存会把服务端的手机号和标签整个清掉（PUT 是整体替换）。
     */
    var loaded by remember { mutableStateOf(false) }
    /**
     * 当前展示的头像 URL（选图上传后先落这里，随「保存」一起提交）。
     * **初值取自传入的 card**——写死空串的话，进页那几帧会先回退成首字母色块、
     * 等 `/users/me` 回来才跳成真头像，正是三端 2026-08-30 收口要消除的那种闪动。
     */
    var avatarUrl by remember { mutableStateOf(card?.avatarUrl.orEmpty()) }
    var editing by remember { mutableStateOf(startEditing) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var toast by remember { mutableStateOf<String?>(null) }

    /** 把一份权威资料同时铺进只读态与表单——两处各填一遍迟早漂移（iOS `applyProfile:`）。 */
    fun apply(c: UserCard) {
        current = c
        avatarUrl = c.avatarUrl
        loadedUsername = c.username
        form = formOf(c)
    }

    /** 迟到的权威资料：用户动过的字段保留他的输入，没动过的（含本机副本里本就空着的手机号 / 标签）取服务端值。 */
    fun merge(c: UserCard) {
        val f = formOf(c)
        current = c
        loadedUsername = c.username
        form = ProfileForm(
            nickname = if (form.nickname == seedForm.nickname) f.nickname else form.nickname,
            username = if (form.username == seedForm.username) f.username else form.username,
            phone = if (form.phone == seedForm.phone) f.phone else form.phone,
            tags = if (form.tags == seedForm.tags) f.tags else form.tags,
        )
        if (avatarUrl == card?.avatarUrl.orEmpty()) avatarUrl = c.avatarUrl
    }

    LaunchedEffect(Unit) {
        // 进页重拉一次权威资料：外层传进来的那份可能是几分钟前的
        runCatchingCancellable { client.contacts.me() }
            .onSuccess { fresh -> if (editing) merge(fresh) else apply(fresh); loaded = true }
            // 拉不到：页面沿用外层传进来的那份（不停在空白上），但保存仍被拦着（见 loaded）
            .onFailure { error = it.userMessage(Str.s(R.string.common_load_failed)) }
    }

    val pickAvatar = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            toast = Str.s(R.string.common_uploading)
            val bytes = withContext(Dispatchers.IO) { AvatarPrepare.fromUri(context, uri) }
            if (bytes == null) {
                toast = Str.s(R.string.common_image_process_failed)
                return@launch
            }
            runCatchingCancellable { client.upload.uploadAvatar(bytes) }
                .onSuccess {
                    avatarUrl = it.url
                    // 只更新预览，**不立刻提交**——与 iOS 同：头像跟着「保存」一起生效，
                    // 用户选错了还能点取消退回去
                    toast = Str.s(R.string.profile_avatar_saved_hint)
                }
                .onFailure { toast = it.userMessage(Str.s(R.string.net_error_avatar_upload_failed)) }
        }
    }

    fun save() {
        if (saving) return
        // 见 loaded：没拉到权威资料前保存会清掉手机号与标签。拉失败了就把失败原因再说一遍，而不是一直「加载中」
        if (!loaded) { toast = error.ifEmpty { Str.s(R.string.common_loading) }; return }
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
            }.onFailure { error = it.userMessage(Str.s(R.string.common_save_failed)) }
                .getOrNull()
            if (updated == null) { saving = false; return@launch }

            if (renaming) {
                runCatchingCancellable { client.changeUsername(form.username.trim()) }
                    .onFailure {
                        // 资料已存上，别谎称全盘失败；留在编辑态让用户换个名重试
                        error = it.userMessage(Str.s(R.string.net_fallback_username_change))
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
            if (startEditing) { onBack(); return@launch }
            editing = false
            toast = Str.s(R.string.profile_saved_toast)
        }
    }

    /** 放弃编辑：直进编辑态的一趟直接退出；否则丢弃未保存输入（含刚传上去的头像预览）、用最后一次权威数据回填。 */
    fun cancelEdit() {
        if (startEditing) { onBack(); return }
        current?.let { apply(it) }
        editing = false
        error = ""
    }

    BackHandler { if (editing) cancelEdit() else onBack() }

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
        onCancelEdit = ::cancelEdit,
        onSave = { save() },
        onPickAvatar = {
            pickAvatar.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        },
        onBack = onBack,
    )

    toast?.let { IMToast(it) { toast = null } }
}

/** 一份资料 → 编辑表单（只读态与表单共用这一份映射，见 apply）。 */
private fun formOf(c: UserCard) = ProfileForm(
    nickname = c.nickname,
    username = c.username,
    phone = c.phone,
    tags = ProfileEdit.tagsText(c.tags),
)
