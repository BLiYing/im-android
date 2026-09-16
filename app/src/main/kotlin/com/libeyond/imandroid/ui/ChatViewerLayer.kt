package com.libeyond.imandroid.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import com.composables.icons.lucide.Bookmark
import com.composables.icons.lucide.Copy
import com.composables.icons.lucide.Forward
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.MessageSquareText
import com.composables.icons.lucide.Trash2
import com.libeyond.imandroid.data.MediaUrl
import com.libeyond.imandroid.data.MessageAction
import com.libeyond.imandroid.data.MessageActions
import com.libeyond.imandroid.data.ViewerAction
import com.libeyond.imandroid.data.ViewerActions
import com.libeyond.imandroid.data.ViewerMedia
import com.libeyond.imandroid.data.db.ConversationEntity
import com.libeyond.imandroid.data.db.MessageEntity
import com.libeyond.imandroid.sdk.IMClient
import com.libeyond.imandroid.sdk.api.FavoriteDraft
import com.libeyond.imandroid.sdk.protocol.ContentType
import com.libeyond.imandroid.ui.components.ActionSheet
import com.libeyond.imandroid.ui.components.SheetItem
import com.libeyond.imandroid.ui.screens.MediaViewerScreen
import kotlinx.coroutines.launch

/**
 * 聊天页的媒体查看器层：查看器本体 + 翻页序列 + 「更多」的外部动作 + 删除两档选择单。
 * 从 ChatHost 拆出（那个文件贴着 600 行红线；套路同 `ChatRecordLayer`）。
 * 动作清单的判据在 `data/ViewerActions.kt`（对齐 iOS `mediaViewerMoreActionsForMessage:`），
 * 翻页序列与判据在 `ui/ChatMediaTimelineState.kt` + `data/MediaTimeline.kt`。
 *
 * **本层恒在组合里**（不随 [viewing] 进出）：「更多 → 删除」要先关查看器再弹两档选择单，
 * 选择单的状态若挂在查看器那一支上，关查看器的同时它也跟着没了。
 */
@Composable
internal fun ChatViewerLayer(
    client: IMClient,
    conv: ConversationEntity,
    viewing: MessageEntity?,
    iAmManager: Boolean,
    onSave: (url: String, isVideo: Boolean) -> Unit,
    onLocate: (Long) -> Unit,
    onForward: (MessageEntity) -> Unit,
    /** 「媒体」钮：打开本会话详情的媒体页签。本层负责先关查看器。 */
    onOpenGallery: () -> Unit,
    onClose: () -> Unit,
    onToast: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val owner = client.uid.orEmpty()
    /** 等用户在「仅删除自己 / 为所有人删除」里挑一档的那条及可选的档（null = 没在挑）。 */
    var deleting by remember(conv.convId) { mutableStateOf<Pair<MessageEntity, List<MessageAction>>?>(null) }
    // 会话媒体时间线（本地打底 + 翻到最旧时向服务端续拉）。**本层恒在组合里，所以它只建一次**
    val timeline = rememberChatMediaTimeline(client, conv.convId)

    viewing?.let { m ->
        // 定位不到就只看这一条（未确认的、或超出本地取数上限的那些）——不假装能翻
        val pages = remember(timeline.items, m.convSeq) {
            if (timeline.items.any { it.convSeq == m.convSeq }) {
                timeline.items
            } else {
                listOf(
                    ViewerMedia(
                        convSeq = m.convSeq, contentType = m.contentType, content = m.content,
                        poster = m.poster.orEmpty(), sender = m.sender, timestamp = m.timestamp,
                    ),
                )
            }
        }

        /**
         * 翻到的那一条对应的消息实体——「更多」的判据与执行都按它算。
         *
         * 点进来的那条直接用原件（它带着全部字段）；翻过去的那些只有序列项上的最小集，
         * 按它现搭一个：**`sender` / `timestamp` 必须带上**，否则「为所有人删除」与「撤回」
         * 的判据会静默降级成"这条不是我发的"（见 [ViewerMedia] 的注释）。
         */
        fun entityOf(vm: ViewerMedia): MessageEntity =
            if (vm.convSeq == m.convSeq) {
                m
            } else {
                MessageEntity(
                    ownerUid = owner, convId = conv.convId, convSeq = vm.convSeq,
                    sender = vm.sender, contentType = vm.contentType, content = vm.content,
                    poster = vm.poster, timestamp = vm.timestamp,
                )
            }

        MediaViewerScreen(
            pages = pages,
            startSeq = m.convSeq,
            // 标题＝会话名（iOS `IMMediaPagerViewController.conversationTitle`）
            title = conv.title,
            host = client.host,
            useTls = com.libeyond.imandroid.BuildConfig.USE_TLS,
            // 门控已经把它下到本地了，查看器就该放本地那份（断网也看得了）
            localFileOf = { vm -> client.downloads.localFile(vm.content, vm.contentType == ContentType.VIDEO) },
            onSave = onSave,
            downloads = client.downloads,
            // 续拉失败/离线时说一句，别让用户以为"前面真的没有图了"（§4.9「少了要说出来」）
            notice = timeline.notice,
            moreActionsFor = { vm ->
                val cur = entityOf(vm)
                ViewerActions.chatMoreActions(cur.convSeq, (cur.recalledAt ?: 0) > 0, vm.isVideo).map { a ->
                    SheetItem(a.label, a.destructive, icon = viewerActionIcon(a)) {
                        // 外部动作**先关查看器再执行**（iOS `showMoreSheet`）：回到聊天页上下文
                        onClose()
                        when (a) {
                            ViewerAction.Locate -> onLocate(cur.convSeq)
                            ViewerAction.Favorite -> scope.launch {
                                runCatchingCancellable { client.favorites.add(FavoriteDraft.of(cur)) }
                                    .onSuccess { onToast("已收藏") }
                                    .onFailure { onToast(it.userMessage("收藏失败")) }
                            }
                            // 复制图片：**本地原件优先**，与渲染/存相册同一个地址（见 CopyImage 的注释）
                            ViewerAction.Copy -> scope.launch {
                                val local = client.downloads.localFile(vm.content, vm.isVideo)
                                val url = local?.let { android.net.Uri.fromFile(it).toString() }
                                    ?: MediaUrl.absolute(
                                        vm.content, client.host, com.libeyond.imandroid.BuildConfig.USE_TLS,
                                    )
                                onToast(CopyImage.copy(context, url))
                            }
                            // 失效媒体的拦截在 forwardOne 里（长按菜单、查看器同一个入口）
                            ViewerAction.Forward -> onForward(cur)
                            ViewerAction.Delete -> {
                                // 能删哪几档与长按菜单同一份判据（MessageActions）：
                                // 两档都能用才让人挑，只剩一档直接执行（iOS 同）
                                val tiers = MessageActions
                                    .availableFor(cur, owner, conv.isGroup, iAmManager)
                                    .filter(::isDeleteTier)
                                when (tiers.size) {
                                    0 -> onToast("这条消息不能删除")
                                    1 -> runMessageDelete(client, conv.convId, tiers.first(), cur.convSeq, scope)
                                    else -> deleting = cur to tiers
                                }
                            }
                        }
                    }
                }
            },
            onOpenGallery = {
                onClose()
                onOpenGallery()
            },
            onNearOldest = { idx -> timeline.loadOlderIfNeeded(idx) },
            onClose = onClose,
        )
    }

    deleting?.let { (m, tiers) ->
        // 选择单开着时返回键只关它：不拦的话落到聊天页的返回键上，直接退出会话
        BackHandler { deleting = null }
        ActionSheet(
            title = "",
            items = tiers.map { t ->
                SheetItem(t.label, destructive = true) { runMessageDelete(client, conv.convId, t, m.convSeq, scope) }
            },
            onDismiss = { deleting = null },
        )
    }
}

private fun isDeleteTier(a: MessageAction) = a == MessageAction.DeleteForEveryone || a == MessageAction.HideForMe

/** 图标逐项对齐 iOS 的 SF Symbol（text.bubble / bookmark / arrowshape.turn.up.right / trash），用 Lucide 近义。 */
private fun viewerActionIcon(a: ViewerAction): ImageVector = when (a) {
    ViewerAction.Locate -> Lucide.MessageSquareText
    ViewerAction.Favorite -> Lucide.Bookmark
    ViewerAction.Copy -> Lucide.Copy
    ViewerAction.Forward -> Lucide.Forward
    ViewerAction.Delete -> Lucide.Trash2
}
