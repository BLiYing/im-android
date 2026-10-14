package com.libeyond.imandroid.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.libeyond.imandroid.R
import com.libeyond.imandroid.data.db.ConversationEntity
import com.libeyond.imandroid.i18n.Str
import com.libeyond.imandroid.sdk.IMClient
import com.libeyond.imandroid.ui.components.IMConfirmDialog
import com.libeyond.imandroid.ui.components.IMToast
import com.libeyond.imandroid.ui.screens.ChatFolderEditScreen
import com.libeyond.imandroid.ui.screens.ChatFolderPickerScreen
import kotlinx.coroutines.launch

/**
 * 聊天文件夹创建 / 编辑页 + 其「添加聊天」选择页（设计 §4.6）。**自管两层**（编辑 ↔ 选择），
 * 「我」的管理页与会话列表的 Tab 长按都直接用它。
 *
 * - 创建（[folderId] = null）：名称 + 待添加聊天先留在本地，右上「创建」一次提交。
 * - 编辑：名称失焦 / 返回即存；增删成员立即提交；服务端权威（成功后整表替换，页面随快照刷新）。
 *   别的设备把它删了 → 自动退出本页。
 *
 * @param conversations 选择页的数据源（全部会话）；行显示信息也靠它解析（解析不到的成员仍占数、不列出）。
 */
@Composable
fun ChatFolderEditHost(
    client: IMClient,
    folderId: String?,
    initialName: String?,
    conversations: List<ConversationEntity>,
    onBack: () -> Unit,
) {
    val snap by client.chatFolderStore.state.collectAsState()
    val creating = folderId == null
    val folder = snap.folder(folderId)
    val scope = rememberCoroutineScope()
    var toast by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    var name by remember(folderId) { mutableStateOf(folder?.name ?: initialName.orEmpty()) }
    // 创建模式的待提交成员；编辑模式直接读快照
    var draftConvIds by remember { mutableStateOf<List<String>>(emptyList()) }
    val memberIds = if (creating) draftConvIds else folder?.convIds.orEmpty()

    // 别的设备把它删了：退出本页
    LaunchedEffect(folder == null && !creating) { if (!creating && folder == null) onBack() }

    val byId = remember(conversations) { conversations.associateBy { it.convId } }
    val chats = memberIds.mapNotNull { byId[it] }
    val failedText = Str.s(R.string.folder_toast_failed)

    /** 提交名称；返回「可以离开」。失败时回滚显示并提示（留在本页，别把失败的改名随页面一起丢掉）。 */
    suspend fun commitName(): Boolean {
        val f = folder ?: return true
        val n = name.trim()
        if (n.isEmpty()) { name = f.name; return true }
        if (n == f.name) return true
        return runCatchingCancellable { client.chatFolderStore.rename(f.id, n) }
            .onFailure { toast = it.userMessage(failedText); name = f.name }
            .isSuccess
    }

    // 返回要**先等改名落定再离开**：scope 随本页离开组合而取消，先 onBack() 会把在途的改名请求一起取消。
    val back: () -> Unit = {
        if (creating) onBack()
        else scope.launch { if (commitName()) onBack() }
    }
    BackHandler(enabled = !picking) { back() }

    if (picking) {
        BackHandler { picking = false }
        var selected by remember { mutableStateOf(memberIds.toSet()) }
        ChatFolderPickerScreen(
            conversations = conversations,
            selected = selected,
            onToggle = { c ->
                selected = if (c.convId in selected) selected - c.convId
                else if (selected.size >= snap.limits.maxConvs) { toast = Str.s(R.string.err_300302); selected }
                else selected + c.convId
            },
            onDone = {
                val before = memberIds
                // 按会话列表顺序回写，并保留解析不到的既有成员（别让「完成」把它们误移出）
                val ordered = conversations.map { it.convId }.filter { it in selected } +
                    before.filter { it in selected && it !in byId }
                picking = false
                if (creating) draftConvIds = ordered
                else {
                    val add = ordered.filter { it !in before }
                    val remove = before.filter { it !in selected }
                    if (add.isNotEmpty() || remove.isNotEmpty()) {
                        scope.launch {
                            runCatchingCancellable { client.chatFolderStore.changeConvs(folderId!!, add, remove) }
                                .onFailure { toast = it.userMessage(failedText) }
                        }
                    }
                }
            },
            onBack = { picking = false },
        )
    } else {
        ChatFolderEditScreen(
            creating = creating,
            name = name,
            onNameChange = { name = it },
            maxName = snap.limits.maxName,
            onNameDone = { if (!creating) scope.launch { commitName() } },
            chats = chats,
            onAddChats = { picking = true },
            onRemoveChat = { c ->
                if (creating) draftConvIds = draftConvIds - c.convId
                else scope.launch {
                    runCatchingCancellable { client.chatFolderStore.changeConvs(folderId!!, emptyList(), listOf(c.convId)) }
                        .onFailure { toast = it.userMessage(failedText) }
                }
            },
            onDelete = if (creating) null else ({ confirmDelete = true }),
            onCreate = {
                val n = name.trim()
                if (n.isEmpty()) { toast = Str.s(R.string.err_300303); return@ChatFolderEditScreen }
                busy = true
                scope.launch {
                    runCatchingCancellable { client.chatFolderStore.create(n, draftConvIds) }
                        .onSuccess { onBack() }
                        .onFailure { busy = false; toast = it.userMessage(failedText) }
                }
            },
            createEnabled = name.trim().isNotEmpty() && !busy,
            onBack = back,
        )
    }

    if (confirmDelete) {
        IMConfirmDialog(
            title = name,
            message = stringResource(R.string.folder_edit_delete_confirm, name),
            confirmText = stringResource(R.string.common_delete),
            onConfirm = {
                confirmDelete = false
                scope.launch {
                    runCatchingCancellable { client.chatFolderStore.delete(folderId!!) }
                        .onSuccess { onBack() }
                        .onFailure { toast = it.userMessage(failedText) }
                }
            },
            onDismiss = { confirmDelete = false },
        )
    }
    toast?.let { IMToast(it) { toast = null } }
}
