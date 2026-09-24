package com.libeyond.imandroid.ui

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
import com.libeyond.imandroid.data.DisplayName
import com.libeyond.imandroid.data.GroupNameDefault
import com.libeyond.imandroid.sdk.IMClient
import com.libeyond.imandroid.sdk.api.FriendEntry
import com.libeyond.imandroid.sdk.api.GroupInfo
import com.libeyond.imandroid.sdk.http.ApiException
import com.libeyond.imandroid.ui.screens.CreateGroupScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 建群页的状态与动作。**两个入口共用**：通讯录「群聊」列表右上角、消息页 ＋ 菜单
 * （iOS 两处走的也是同一份 `IMGroupCreateViewController`，其注释记着此前两处各有一份实现的教训）。
 *
 * 布局/交互对齐 iOS `IMGroupCreateViewController`（2026-09-23 用户报「和 iOS 拉齐」）：
 * 头像可选、群名有 rune 计数与上限、按已选成员自动预填群名（手改过就不再覆盖）。
 * **未对齐的一处是刻意的**：iOS 建群分两步（先选人的独立页，再是头像/群名页），本端保留单页——
 * 拆成两页要么新写一套联系人搜索+索引页、要么把 `FriendPickerScreen`（单选专用、无搜索）
 * 硬改成多选，两条路都比"单页里加搜索+索引"改动面大、回归风险高，产出的体验差异对用户不明显。
 *
 * @param seedFriends 调用方手上已有的好友表，先用它画，进页再拉一次新的——不然从消息页进来
 *                    （主界面那份好友表只在登录时拉过一次）会漏掉之后才加的好友。
 * @param onCreated 建成之后调用方决定去哪：通讯录回列表，消息页直接进新群（同 iOS `startNewGroup`）。
 */
@Composable
fun CreateGroupHost(
    client: IMClient,
    seedFriends: List<FriendEntry>,
    onCreated: (GroupInfo) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var friends by remember { mutableStateOf(seedFriends.filter { it.status == FriendEntry.ACCEPTED }) }
    var name by remember { mutableStateOf("") }
    var nameEdited by remember { mutableStateOf(false) } // 手改过就不再被自动预填覆盖，同 iOS nameEdited
    var picks by remember { mutableStateOf<Set<String>>(emptySet()) }
    var creating by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var myPublicName by remember { mutableStateOf("") } // 预填群名要带上"我"，与 iOS 起手一致
    // **群上限读服务端配置，不硬编码**（要装更多人走大群，不是调大这个数）
    var maxMembers by remember { mutableStateOf(0) }

    var avatarUrl by remember { mutableStateOf("") }
    var avatarUploading by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        runCatchingCancellable { client.conversationsApi.serverConfig().maxGroupMembers }
            .onSuccess { maxMembers = it }
        runCatchingCancellable { client.contacts.friends() }
            .onSuccess { list -> friends = list.filter { it.status == FriendEntry.ACCEPTED } }
        runCatchingCancellable { client.contacts.me() }
            .onSuccess { me -> myPublicName = GroupNameDefault.publicUserName(me.nickname, me.username, me.userId) }
    }

    // 选人变化时重算预填群名——手改过就不再覆盖（同 iOS applySuggestedNameIfNeeded）
    LaunchedEffect(picks, myPublicName) {
        if (nameEdited) return@LaunchedEffect
        val names = buildList {
            add(myPublicName)
            picks.forEach { uid ->
                val f = friends.firstOrNull { it.userId == uid } ?: return@forEach
                add(DisplayName.publicNameOfFriend(f))
            }
        }
        name = GroupNameDefault.defaultName(names)
    }

    val avatarPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            avatarUploading = true
            val bytes = withContext(Dispatchers.IO) { AvatarPrepare.fromUri(context, uri) }
            if (bytes == null) {
                avatarUploading = false
                error = "图片处理失败，换一张试试"
                return@launch
            }
            val up = runCatching { client.upload.uploadAvatar(bytes) }
            avatarUploading = false
            val url = up.getOrNull()?.url
            if (url == null) {
                error = "头像上传失败"
                return@launch
            }
            avatarUrl = url
        }
    }

    CreateGroupScreen(
        name = name,
        onNameChange = { input ->
            nameEdited = true
            name = GroupNameDefault.truncateToRunes(input, GroupNameDefault.MAX_LENGTH)
        },
        friends = friends,
        selected = picks,
        onToggle = { id -> picks = if (id in picks) picks - id else picks + id },
        maxMembers = maxMembers,
        busy = creating,
        error = error,
        avatarUrl = avatarUrl,
        avatarUploading = avatarUploading,
        onPickAvatar = { avatarPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
        onCreate = {
            scope.launch {
                creating = true; error = ""
                try {
                    val group = client.groups.create(name.trim(), picks.toList(), avatarUrl)
                    client.messages.refreshConversations()
                    onCreated(group)
                } catch (e: ApiException) {
                    error = if (e.isTransport) "网络请求失败" else e.message
                } finally { creating = false }
            }
        },
        onBack = onBack,
    )
}
