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
import com.libeyond.imandroid.data.ChatFolder
import com.libeyond.imandroid.data.db.ConversationEntity
import com.libeyond.imandroid.i18n.Str
import com.libeyond.imandroid.sdk.IMClient
import com.libeyond.imandroid.ui.components.IMConfirmDialog
import com.libeyond.imandroid.ui.components.IMToast
import com.libeyond.imandroid.ui.screens.ChatFoldersScreen
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch

/**
 * 「我」→ 聊天文件夹（设计 §4.5）：管理页（列表 / 排序 / 删除 / 创建 / 推荐）+ 其下的创建 / 编辑页
 * （[ChatFolderEditHost]，内含选择聊天子页）。进页拉一次整表，写操作成功后整表由 [ChatFolderStore] 替换。
 */
@Composable
fun ChatFoldersHost(client: IMClient, onBack: () -> Unit) {
    val snap by client.chatFolderStore.state.collectAsState()
    val owner = client.uid.orEmpty()
    // 编辑页的会话数据源：本地库这一份（与会话列表同源）
    val conversations by remember(owner) {
        if (owner.isEmpty()) emptyFlow() else client.repo.observeConversations(owner)
    }.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var toast by remember { mutableStateOf<String?>(null) }
    // null = 管理页；否则编辑页：editId = null 即创建，recommendName 为推荐预填
    var editing by remember { mutableStateOf(false) }
    var editId by remember { mutableStateOf<String?>(null) }
    var recommendName by remember { mutableStateOf<String?>(null) }
    var deleteFor by remember { mutableStateOf<ChatFolder?>(null) }
    val failedText = Str.s(R.string.folder_toast_failed)

    LaunchedEffect(Unit) { client.chatFolderStore.refresh() }

    if (editing) {
        ChatFolderEditHost(
            client = client,
            folderId = editId,
            initialName = recommendName,
            conversations = conversations.orEmpty(),
            onBack = { editing = false },
        )
    } else {
        BackHandler { onBack() }
        ChatFoldersScreen(
            folders = snap.folders,
            maxFolders = snap.limits.maxFolders,
            onOpen = { f -> editId = f.id; recommendName = null; editing = true },
            onCreate = { editId = null; recommendName = null; editing = true },
            onRecommend = { n -> editId = null; recommendName = n; editing = true },
            onDelete = { deleteFor = it },
            onReorder = { ids ->
                scope.launch {
                    runCatchingCancellable { client.chatFolderStore.reorder(ids) }
                        .onFailure { toast = it.userMessage(failedText) }
                }
            },
            onBack = onBack,
        )
    }

    deleteFor?.let { f ->
        IMConfirmDialog(
            title = f.name,
            message = stringResource(R.string.folder_edit_delete_confirm, f.name),
            confirmText = stringResource(R.string.common_delete),
            onConfirm = {
                deleteFor = null
                scope.launch {
                    runCatchingCancellable { client.chatFolderStore.delete(f.id) }
                        .onFailure { toast = it.userMessage(failedText) }
                }
            },
            onDismiss = { deleteFor = null },
        )
    }
    toast?.let { IMToast(it) { toast = null } }
}
