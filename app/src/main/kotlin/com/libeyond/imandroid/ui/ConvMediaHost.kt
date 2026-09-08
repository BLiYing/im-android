package com.libeyond.imandroid.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.libeyond.imandroid.sdk.IMClient
import com.libeyond.imandroid.sdk.api.ConvMediaItem
import com.libeyond.imandroid.sdk.api.MediaKind
import com.libeyond.imandroid.sdk.logging.IMLog
import com.libeyond.imandroid.ui.components.IMToast
import com.libeyond.imandroid.ui.screens.ConvMediaScreen
import com.libeyond.imandroid.ui.screens.MediaViewerScreen
import kotlinx.coroutines.launch

/**
 * 会话媒体归档接线层（M4.5-3）。
 *
 * **群聊与单聊共用这一份**：归档在两种会话里完全一样（同一个接口、同一套分页、同一个查看器），
 * 没有分两份的理由。分两份的代价不是重复代码，是分页语义会分叉——
 * 一边守了在途、一边没守，同一页被追加两次。
 */
@Composable
internal fun ConvMediaHost(
    client: IMClient,
    convId: String,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var kind by remember(convId) { mutableStateOf(MediaKind.MEDIA) }
    var items by remember(convId) { mutableStateOf<List<ConvMediaItem>>(emptyList()) }
    var cursor by remember(convId) { mutableStateOf(0L) }
    var hasMore by remember(convId) { mutableStateOf(false) }
    var loading by remember(convId) { mutableStateOf(false) }
    var viewing by remember(convId) { mutableStateOf<ConvMediaItem?>(null) }
    var toast by remember(convId) { mutableStateOf<String?>(null) }
    val saveMedia = rememberMediaSaver { toast = it }

    // 只有两层，直接判；层多了再上枚举（见 ChatDetailPage）
    BackHandler { if (viewing != null) viewing = null else onBack() }

    /** 换分类 = 从头拉；续页 = 带游标追加。**两条路共用一个出口**，免得分页语义分叉。 */
    fun load(reset: Boolean) {
        if (loading) return          // 在途守卫：滚到底会连续触发，不守就把同一页追加两次
        loading = true
        scope.launch {
            runCatching { client.conversationsApi.media(convId, kind, if (reset) 0L else cursor) }
                .onSuccess { p ->
                    items = if (reset) {
                        p.items
                    } else {
                        // 按 conv_seq 去重再追加——即便守卫被绕过也不会出现重复行
                        val seen = items.mapTo(HashSet()) { it.convSeq }
                        items + p.items.filter { it.convSeq !in seen }
                    }
                    cursor = p.nextCursor
                    hasMore = p.hasMore
                }
                .onFailure { IMLog.tag("IM.Detail").w("conv_media_failed", "kind" to kind) }
            loading = false
        }
    }

    LaunchedEffect(convId, kind) { load(reset = true) }

    val v = viewing
    if (v != null) {
        MediaViewerScreen(
            contentType = v.contentType,
            content = v.content,
            poster = v.poster,
            host = client.host,
            useTls = com.libeyond.imandroid.BuildConfig.USE_TLS,
            onSave = saveMedia,
            // 归档里**没有转发**：转发选择页与发送上下文都在聊天页那一侧。
            // 按钮不画，不做成点了没反应的。
            onForward = null,
            onClose = { viewing = null },
        )
    } else {
        ConvMediaScreen(
            kind = kind,
            onKindChange = { k -> if (k != kind) { kind = k; items = emptyList(); cursor = 0 } },
            items = items,
            loading = loading,
            hasMore = hasMore,
            onLoadMore = { if (hasMore) load(reset = false) },
            onOpen = { viewing = it },
            host = client.host,
            useTls = com.libeyond.imandroid.BuildConfig.USE_TLS,
            onBack = onBack,
        )
    }

    // toast 放最后：它是一层 fillMaxSize 的浮层，画在页面之前会被盖住
    toast?.let { t -> IMToast(t) { toast = null } }
}
