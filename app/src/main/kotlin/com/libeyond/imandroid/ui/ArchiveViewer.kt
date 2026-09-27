package com.libeyond.imandroid.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.libeyond.imandroid.data.ArchiveActions
import com.libeyond.imandroid.data.ArchiveTarget
import com.libeyond.imandroid.data.MediaTimeline
import com.libeyond.imandroid.data.ViewerMedia
import androidx.compose.ui.res.stringResource
import com.libeyond.imandroid.data.toViewerMedia
import com.libeyond.imandroid.R
import com.libeyond.imandroid.sdk.IMClient
import com.libeyond.imandroid.sdk.api.ConvMediaItem
import com.libeyond.imandroid.sdk.protocol.ContentType
import com.libeyond.imandroid.ui.components.SheetItem
import com.libeyond.imandroid.ui.screens.ForwardPickerScreen
import com.libeyond.imandroid.ui.screens.MediaViewerScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 归档（详情页 / 群资料的「媒体」页签）里打开的查看器——翻页序列、「更多」、转发选择页。
 *
 * 单聊详情与群资料**共用这一份**，理由同 [ArchiveActionsHost]：归档在两种会话里完全一样，
 * 分两份的代价是判据会分叉。
 *
 * ⚠️ **本文件里的 composable 一律不自建 `rememberCoroutineScope`**，一律由宿主传 [CoroutineScope] 进来：
 * 这几层都是"点一下就把自己关掉"的浮层，协程挂在自己身上的话，
 * 「点转发/删除紧接着关掉」那一次请求会随组合一起被取消（本仓 `IMCardSheet` 那条坑记的是同一件事）。
 */

/**
 * 归档查看器的翻页序列：**就用页签已经拉到的那几页**（[ConvArchive] 的 items，服务端倒序），
 * 转成升序。翻到最旧一端时续拉——与这一页的"滚到底续拉"是同一份分页状态，不另开一条请求线。
 *
 * [current] 不在序列里时只放它一条（「语音」页签点开的那种，或列表刚被刷过）——**不假装能翻**。
 */
internal fun archiveViewerPages(items: List<ConvMediaItem>, current: ConvMediaItem): List<ViewerMedia> {
    val seq = items.mapNotNull { it.toViewerMedia() }.sortedBy { it.convSeq }
    return if (seq.any { it.convSeq == current.convSeq }) {
        seq
    } else {
        listOf(
            ViewerMedia(
                convSeq = current.convSeq,
                contentType = current.contentType,
                content = current.content,
                poster = current.poster,
                sender = current.sender,
                timestamp = current.timestamp,
            ),
        )
    }
}

/**
 * 归档里打开的媒体查看器。
 *
 * **不给「媒体」钮**：它就是回到这一页，从这里点进来再给是来回套娃（iOS 同）。
 * 「更多」给（2026-09-16 补）：iOS 的媒体库查看器一直有，本端此前缺，
 * 于是从归档点开的图只能退回去再长按才能转发/删除。
 */
@Composable
@Suppress("LongParameterList")
internal fun ArchiveMediaViewer(
    client: IMClient,
    convId: String,
    isGroup: Boolean,
    iAmManager: Boolean,
    archive: ConvArchive,
    current: ConvMediaItem,
    /** 查看器顶部标题＝会话名（iOS `IMMediaPagerViewController.conversationTitle`）。 */
    title: String = "",
    /** 宿主的作用域（见文件头的 ⚠️）。 */
    scope: CoroutineScope,
    onSave: (url: String, isVideo: Boolean) -> Unit,
    onForwardPicker: (ArchiveTarget) -> Unit,
    onLocateInChat: (Long) -> Unit,
    onChanged: () -> Unit,
    onToast: (String) -> Unit,
    onClose: () -> Unit,
) {
    // **必须记住**：`archiveViewerPages` 每次都要 mapNotNull + sortedBy 整份归档（可达几百条）。
    // 摆在参数位上就是每一次重组都重排一遍——翻页时肉眼可见地发涩
    // （2026-09-16 用户报：详情页点开的查看器比聊天页点开的卡）。聊天页那条路一直是 remember 的。
    val pages = remember(archive.items, current.convSeq) { archiveViewerPages(archive.items, current) }
    MediaViewerScreen(
        pages = pages,
        startSeq = current.convSeq,
        title = title,
        host = client.host,
        useTls = com.libeyond.imandroid.BuildConfig.USE_TLS,
        localFileOf = { vm -> client.downloads.localFile(vm.content, vm.contentType == ContentType.VIDEO) },
        downloads = client.downloads,
        onSave = onSave,
        // 归档这一页的取数失败了：翻页只能停在已拉到的那几页，说一句（§4.9「少了要说出来」）
        notice = if (archive.failed) stringResource(R.string.media_viewer_offline_partial_notice) else null,
        moreActionsFor = { vm ->
            archiveViewerMoreItems(
                client = client, convId = convId, isGroup = isGroup, iAmManager = iAmManager,
                item = vm, scope = scope, onClose = onClose,
                onForwardPicker = onForwardPicker, onLocateInChat = onLocateInChat,
                onChanged = onChanged, onToast = onToast,
            )
        },
        onNearOldest = { idx ->
            if (MediaTimeline.wantsOlder(idx, archive.items.size, archive.hasMore, archive.loading)) {
                archive.loadMore()
            }
        },
        onClose = onClose,
    )
}

/**
 * 归档查看器「更多」里的动作。
 *
 * **判据与执行都复用归档长按菜单那一份**（[ArchiveActions] + [runArchiveAction]）——iOS 那侧同样是
 * 媒体库查看器与逐格长按共用一个 `contentMenuConfigForMessage:`。
 *
 * 动作**先关查看器再执行**（iOS `showMoreSheet` 同）：转发选择页、两档删除要盖在这一页上下文里。
 * 转发**不带 caption**：相册视角看不到图说，与 iOS `forwardMediaFromViewerMessage:` 同一语义。
 */
@Suppress("LongParameterList")
internal fun archiveViewerMoreItems(
    client: IMClient,
    convId: String,
    isGroup: Boolean,
    iAmManager: Boolean,
    item: ViewerMedia,
    scope: CoroutineScope,
    onClose: () -> Unit,
    onForwardPicker: (ArchiveTarget) -> Unit,
    onLocateInChat: (Long) -> Unit,
    onChanged: () -> Unit,
    onToast: (String) -> Unit,
): List<SheetItem> {
    val owner = client.uid.orEmpty()
    val target = ArchiveTarget(
        convSeq = item.convSeq,
        sender = item.sender,
        contentType = item.contentType,
        content = item.content,
        timestamp = item.timestamp,
    )
    val actions = ArchiveActions.availableFor(
        convSeq = target.convSeq,
        downloading = archiveItemDownloading(client, target),
        mine = target.sender == owner,
        isGroup = isGroup,
        iAmManager = iAmManager,
    )
    return buildArchiveMenu(actions) { a ->
        onClose()
        runArchiveAction(
            action = a, client = client, convId = convId, owner = owner, target = target,
            scope = scope, onForwardPicker = onForwardPicker,
            onLocateInChat = onLocateInChat, onChanged = onChanged, onToast = onToast,
        )
    }
}

/**
 * 归档的转发选择页：长按菜单与查看器「更多」共用。
 *
 * [scope] 由宿主传（见文件头）：**发送不能挂在本层**——`onConfirm` 里先关掉自己再发，
 * 挂本层的协程会当场被取消，表现是"点了发送但对方没收到"。
 */
@Composable
internal fun ArchiveForwardPicker(
    client: IMClient,
    convId: String,
    target: ArchiveTarget,
    scope: CoroutineScope,
    onDismiss: () -> Unit,
    onToast: (String) -> Unit,
) {
    val owner = client.uid.orEmpty()
    val convs by client.repo.observeConversations(owner).collectAsState(initial = emptyList())
    ForwardPickerScreen(
        conversations = convs,
        onCancel = onDismiss,
        onToast = onToast,
        onConfirm = { targets ->
            onDismiss()
            scope.launch {
                onToast(forwardMessages(client, listOf(target.toMessageEntity(owner, convId)), targets))
            }
        },
    )
}
