package com.libeyond.imandroid.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import com.libeyond.imandroid.R
import com.libeyond.imandroid.i18n.Str
import com.composables.icons.lucide.Copy
import com.composables.icons.lucide.Forward
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Trash2
import com.composables.icons.lucide.X
import com.libeyond.imandroid.data.CardContent
import com.libeyond.imandroid.data.DownloadPhase
import com.libeyond.imandroid.data.FavoriteAction
import com.libeyond.imandroid.data.FavoriteCategory
import com.libeyond.imandroid.data.FavoritePick
import com.libeyond.imandroid.data.Favorites
import com.libeyond.imandroid.data.SelectionActions
import com.libeyond.imandroid.data.db.ConversationEntity
import com.libeyond.imandroid.data.db.MessageEntity
import com.libeyond.imandroid.data.toViewerMedia
import com.libeyond.imandroid.sdk.IMClient
import com.libeyond.imandroid.sdk.api.Favorite
import com.libeyond.imandroid.sdk.api.FriendEntry
import com.libeyond.imandroid.sdk.api.UserCard
import com.libeyond.imandroid.sdk.logging.IMLog
import com.libeyond.imandroid.sdk.protocol.ContentType
import com.libeyond.imandroid.ui.components.IMToast
import com.libeyond.imandroid.ui.components.LocalOpenLink
import com.libeyond.imandroid.ui.components.MessageContextMenu
import com.libeyond.imandroid.ui.components.SheetItem
import com.libeyond.imandroid.ui.components.blockPointerInput
import com.libeyond.imandroid.ui.screens.FavoritePickUi
import com.libeyond.imandroid.ui.screens.FavoriteReaderScreen
import com.libeyond.imandroid.ui.screens.FavoritesScreen
import com.libeyond.imandroid.ui.screens.ForwardPickerScreen
import com.libeyond.imandroid.ui.screens.MediaViewerScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private val log = IMLog.tag("IM.Favorites")

/**
 * 已加载的收藏（分页累积，不一定是全量——见 [total]）。
 * 与详情页 [ConvArchive] 同一个形状：状态在这里，取数的在途守卫也在这里。
 */
@Stable
internal class FavoriteList {
    var items by mutableStateOf<List<Favorite>>(emptyList())
    var total by mutableStateOf(0)
    var loading by mutableStateOf(true)
    var failed by mutableStateOf(false)
    var loadingMore = false

    val hasMore: Boolean get() = Favorites.hasMore(items.size, total)

    /** 拉第一页。**失败保留旧数据**（FAVORITES_DESIGN §7：不要把已显示的收藏抹掉）。 */
    fun reload(client: IMClient, scope: CoroutineScope) {
        loading = true
        scope.launch {
            runCatchingCancellable { client.favorites.list(0) }
                .onSuccess { p -> items = p.favorites; total = p.page.total; failed = false }
                .onFailure { failed = true; log.w("favorites_load_failed", "err" to (it.message ?: "?")) }
            loading = false
        }
    }

    /** 滚到底续拉：按**已加载条数**作 offset，按 id 去重（理由见 [Favorites.merge]）。失败静默，下次滚到底再试。 */
    fun loadMore(client: IMClient, scope: CoroutineScope) {
        if (loadingMore || loading || !hasMore) return
        loadingMore = true
        scope.launch {
            runCatchingCancellable { client.favorites.list(items.size) }
                .onSuccess { p -> items = Favorites.merge(items, p.favorites); total = p.page.total }
                .onFailure { log.w("favorites_load_more_failed", "offset" to items.size) }
            loadingMore = false
        }
    }
}

/**
 * 「我 ▸ 收藏消息」的接线层：取数、来源名、打开、长按菜单、转发、删除。
 *
 * 画面在 [FavoritesScreen]；打开之后的几页**全部复用现成的**——查看器 [MediaViewerScreen]、
 * 聊天记录 [ChatRecordLayer]、资料页 [UserProfileHost]、转发选择页 [ForwardPickerScreen]，
 * 文件打开走详情页那一条 [openArchiveItem]。
 *
 * @param onOpenChat 名片进的资料页里点「发消息」：关掉「我」这一栈、进与他的单聊。
 * @param onPicked 非 null = **选择模式**（聊天页附件面板 ▸ 收藏，iOS `initInPickModeWithDone:`）：
 *   勾选框 + 底部「发送 (N)」，没有长按菜单；点「发送」交回**已换成消息**的选中项（按列表顺序），
 *   发到哪个会话由调用方定。换成消息放在这里做，是因为「转发自」要的公开名只有这一层有（好友表 + 补拉的名片）。
 */
@Composable
internal fun FavoritesHost(
    client: IMClient,
    onOpenChat: (ConversationEntity) -> Unit,
    onBack: () -> Unit,
    onPicked: ((List<MessageEntity>) -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val openLink = LocalOpenLink.current
    val owner = client.uid.orEmpty()
    val useTls = com.libeyond.imandroid.BuildConfig.USE_TLS

    val list = remember { FavoriteList() }
    var tab by remember { mutableStateOf<FavoriteCategory?>(null) }
    var query by remember { mutableStateOf("") }
    var toast by remember { mutableStateOf<String?>(null) }
    var viewing by remember { mutableStateOf<Favorite?>(null) }
    var reading by remember { mutableStateOf<String?>(null) }
    var profileUid by remember { mutableStateOf<String?>(null) }
    var menuFor by remember { mutableStateOf<Favorite?>(null) }
    var menuAnchor by remember { mutableStateOf(Rect.Zero) }
    var forwarding by remember { mutableStateOf<Favorite?>(null) }
    /** 选择模式下勾中的收藏 id（判据 [FavoritePick]）。 */
    var picked by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var sendingPicked by remember { mutableStateOf(false) }
    val recordNav = rememberChatRecordNav("favorites")
    val saveMedia = rememberMediaSaver { toast = it }

    // —— 来源名：会话表 + 好友表（本地/一次请求）+ 解析不出的再逐个补拉名片 ——
    var friends by remember { mutableStateOf<Map<String, FriendEntry>?>(null) }
    val cards = remember { mutableStateMapOf<String, UserCard>() }
    val triedCards = remember { HashSet<String>() }
    val convs by client.repo.observeConversations(owner).collectAsState(initial = emptyList())
    val convById = remember(convs) { convs.associateBy { it.convId } }
    fun sourceOf(f: Favorite) =
        Favorites.sourceName(f, owner, convById[f.sourceConvId], friends?.get(f.sourceFrom), cards[f.sourceFrom])

    LaunchedEffect(Unit) {
        list.reload(client, scope)
        friends = runCatchingCancellable { client.contacts.friends() }.getOrNull()
            ?.associateBy { it.userId }.orEmpty()
    }
    // 好友表回来之前不补拉：否则好友也会各发一次名片请求。每个 uid 只试一次，失败静默（来源名不值得打断浏览）。
    // ⚠️ 请求挂**宿主作用域**、不挂本 effect：key 里有会话表，任何会话来一条消息它就重启一次，
    // 挂在 effect 上的在途请求会被当场取消——而 uid 已记进 triedCards，名字就永远补不上了
    LaunchedEffect(list.items, friends, convById) {
        if (friends == null) return@LaunchedEffect
        list.items.asSequence()
            .filter { it.sourceFrom.isNotBlank() && sourceOf(it).isEmpty() }
            .map { it.sourceFrom }.distinct()
            .filter { triedCards.add(it) }
            .forEach { uid ->
                scope.launch { runCatchingCancellable { client.contacts.card(uid) }.onSuccess { cards[uid] = it } }
            }
    }

    val categories = remember(list.items) { Favorites.categoriesOf(list.items) }
    val current = Favorites.settle(categories, tab)
    val shown = remember(list.items, current, query) {
        current?.let { Favorites.filter(list.items, it, query) }.orEmpty()
    }

    /**
     * 发出去要的消息形态：与长按「转发」同一个换法（元数据全带、「转发自」只写公开名）。
     *
     * **先把还没解析出来的名字补齐再换**：进页那两跳（好友表 → 逐个补拉名片）是异步的，
     * 用户可能在它们回来之前就点了发送 / 转发。这里等一次（好友表没到就现拉，名片缺的现补），
     * 还是拿不到才由 [Favorites.toMessageEntity] 写「未命名用户」。失败都静默：名字不值得拦住发送。
     */
    suspend fun toMessages(favs: List<Favorite>): List<MessageEntity> {
        if (friends == null) {
            friends = runCatchingCancellable { client.contacts.friends() }.getOrNull()?.associateBy { it.userId }
        }
        favs.map { it.sourceFrom }.distinct()
            .filter { it.isNotBlank() && it != owner && friends?.containsKey(it) != true && it !in cards }
            .forEach { uid -> runCatchingCancellable { client.contacts.card(uid) }.onSuccess { cards[uid] = it } }
        return favs.map { f ->
            Favorites.toMessageEntity(f, owner, Favorites.originName(f, friends?.get(f.sourceFrom), cards[f.sourceFrom]))
        }
    }

    val pickUi = onPicked?.let { done ->
        FavoritePickUi(
            picked = picked,
            onToggle = { f -> FavoritePick.toggle(picked, f.id)?.let { picked = it } ?: run { toast = FavoritePick.limitText() } },
            onSend = {
                // 补名字要等网络：这期间再点一次不能发两遍
                val favs = FavoritePick.picked(list.items, picked)
                if (favs.isNotEmpty() && !sendingPicked) {
                    sendingPicked = true
                    scope.launch {
                        try { done(toMessages(favs)) } finally { sendingPicked = false }
                    }
                }
            },
        )
    }

    fun open(f: Favorite) {
        when {
            Favorites.matches(f, FavoriteCategory.Media) -> viewing = f
            Favorites.matches(f, FavoriteCategory.Files) ->
                openArchiveItem(client, context, Favorites.toConvMediaItem(f), onToast = { toast = it }) {}
            Favorites.matches(f, FavoriteCategory.Links) -> {
                val url = Favorites.linkUrl(f)
                openLink?.invoke(url) ?: openInBrowser(context, url) { toast = it }
            }
            Favorites.matches(f, FavoriteCategory.Record) -> recordNav.push(f.content)
            Favorites.matches(f, FavoriteCategory.Contact) -> CardContent.parseContact(f.content)?.let { card ->
                // 名片里是我自己：本端「我」页就是资料入口，没有另一页可去，说一句（iOS 进编辑资料）
                if (card.uid == owner) toast = Str.s(R.string.qr_result_own_card) else profileUid = card.uid
            }
            Favorites.matches(f, FavoriteCategory.Text) -> reading = f.content
            // 语音：本端整个 App 还没有播放器（同详情页语音签），点了不做事
            else -> Unit
        }
    }

    BackHandler {
        when {
            menuFor != null -> menuFor = null
            viewing != null -> viewing = null
            profileUid != null -> profileUid = null
            recordNav.media != null -> recordNav.closeViewer()
            recordNav.isOpen -> recordNav.pop()
            reading != null -> reading = null
            else -> onBack()
        }
    }

    FavoritesScreen(
        categories = categories,
        current = current,
        onSelect = { tab = it },
        query = query,
        onQueryChange = { query = it },
        shown = shown,
        noneAtAll = list.items.isEmpty(),
        loadedCount = list.items.size,
        loading = list.loading,
        failed = list.failed,
        hasMore = list.hasMore,
        onLoadMore = { list.loadMore(client, scope) },
        onRetry = { list.reload(client, scope) },
        host = client.host,
        useTls = useTls,
        sourceNameOf = ::sourceOf,
        onOpen = ::open,
        onLongPress = { f, r -> menuFor = f; menuAnchor = r },
        onBack = onBack,
        pick = pickUi,
    )

    // —— 打开之后的几页，逐层盖上去（顺序 = 返回键关闭的逆序）——
    reading?.let { text ->
        Overlay { FavoriteReaderScreen(text, onBack = { reading = null }) }
    }
    if (recordNav.isOpen) {
        Overlay {
            ChatRecordLayer(
                nav = recordNav, host = client.host, useTls = useTls,
                onOpenUser = { uid -> if (uid != owner) profileUid = uid },
                onSave = saveMedia,
            )
        }
    }
    profileUid?.let { uid ->
        val fr = friends?.get(uid)
        val card = list.items.firstNotNullOfOrNull { f ->
            CardContent.parseContact(f.content)?.takeIf { f.contentType == ContentType.CONTACT && it.uid == uid }
        }
        Overlay {
            UserProfileHost(
                client = client,
                userId = uid,
                knownRelation = fr?.status.orEmpty(),
                seed = UserCard(
                    userId = uid,
                    username = fr?.username ?: card?.username.orEmpty(),
                    nickname = fr?.nickname ?: card?.nickname.orEmpty(),
                    avatarUrl = fr?.avatarUrl ?: card?.avatarUrl.orEmpty(),
                    remark = fr?.remark.orEmpty(),
                ),
                onSendMessage = { u -> onOpenChat(client.conversationStubFor(u.userId, u.displayName, u.avatarUrl)) },
                onBack = { profileUid = null },
            )
        }
    }
    viewing?.let { f ->
        // 翻页序列 = 本签当前显示的全部媒体（iOS `openMediaItem:` 同：翻页范围 = 本签，无「媒体」钮、无「更多」）
        val pages = remember(shown, f.id) {
            shown.mapNotNull { Favorites.toConvMediaItem(it).toViewerMedia() }.sortedBy { it.convSeq }
                .ifEmpty { listOfNotNull(Favorites.toConvMediaItem(f).toViewerMedia()) }
        }
        if (pages.isNotEmpty()) {
            MediaViewerScreen(
                pages = pages,
                startSeq = f.id,
                title = stringResource(R.string.common_saved_messages),
                host = client.host,
                useTls = useTls,
                localFileOf = { vm -> client.downloads.localFile(vm.content, vm.isVideo) },
                downloads = client.downloads,
                onSave = saveMedia,
                onClose = { viewing = null },
            )
        }
    }

    menuFor?.let { f ->
        FavoriteMenu(
            client = client, target = f, anchor = menuAnchor, scope = scope,
            onCopy = { text -> clipboard.setText(AnnotatedString(text)); toast = Str.s(R.string.common_copied) },
            onForward = { forwarding = it },
            onDeleted = { id ->
                val (left, total) = Favorites.afterDelete(list.items, list.total, id)
                list.items = left
                list.total = total
            },
            onToast = { toast = it },
            onDismiss = { menuFor = null },
        )
    }

    forwarding?.let { f ->
        ForwardPickerScreen(
            conversations = convs,
            onCancel = { forwarding = null },
            onToast = { toast = it },
            onConfirm = { targets ->
                forwarding = null
                // 挂宿主作用域：选择页先关掉自己再回调，挂它自己身上的协程会当场被取消
                scope.launch { toast = forwardMessages(client, toMessages(listOf(f)), targets) }
            },
        )
    }

    toast?.let { t -> IMToast(t) { toast = null } }
}

/** 盖在收藏列表之上的一页：占住命中测试，下层列表点不到（同 `MainScreen` 覆盖页的 [blockPointerInput]）。 */
@Composable
private fun Overlay(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().blockPointerInput()) { content() }
}

/**
 * 收藏长按菜单（判据 [Favorites.actionsFor]，iOS `contextMenuForFavorite:`）。
 *
 * [scope] 由宿主传：菜单点完就关，删除请求挂在菜单自己身上会随组合一起被取消
 * （`ChatMessageMenu` 2026-09-17 真机抓到的 `LeftCompositionCancellationException` 那条）。
 */
@Composable
private fun FavoriteMenu(
    client: IMClient,
    target: Favorite,
    anchor: Rect,
    scope: CoroutineScope,
    onCopy: (String) -> Unit,
    onForward: (Favorite) -> Unit,
    onDeleted: (Long) -> Unit,
    onToast: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val isVideo = target.contentType == ContentType.VIDEO
    val phase = client.downloads.stateOf(target.content, isVideo).phase
    val downloading = phase == DownloadPhase.Downloading || phase == DownloadPhase.Paused
    MessageContextMenu(
        anchor = anchor,
        mine = false,
        items = Favorites.actionsFor(target, downloading).map { a ->
            // 点完菜单自己会关（MessageContextMenu 在 onClick 之后调 onDismiss）
            SheetItem(a.label, a.destructive, icon = favoriteActionIcon(a)) {
                when (a) {
                    FavoriteAction.Forward -> {
                        // 失效媒体拦下不转，与聊天页 / 详情页同一判据（转出去对端必 404）
                        val gone = SelectionActions.isExpiredMedia(Favorites.toMessageEntity(target, "", "")) { url, v ->
                            client.downloads.stateOf(url, v).phase == DownloadPhase.Expired
                        }
                        if (gone) onToast(SelectionActions.expiredForwardText(target.contentType)) else onForward(target)
                    }
                    FavoriteAction.Copy -> Favorites.copyText(target)?.let(onCopy)
                    FavoriteAction.CancelDownload -> client.downloads.pause(target.content, isVideo)
                    FavoriteAction.Delete -> scope.launch {
                        runCatchingCancellable { client.favorites.delete(target.id) }
                            .onSuccess { onDeleted(target.id) }
                            .onFailure {
                                onToast(Str.s(R.string.net_fallback_delete_failed))
                                log.w("favorite_delete_failed", "id" to target.id)
                            }
                    }
                }
            }
        },
        onDismiss = onDismiss,
    )
}

/** 菜单图标，逐项对齐 iOS 的 SF Symbol（arrowshape.turn.up.right / doc.on.doc / xmark.circle / trash）。 */
private fun favoriteActionIcon(a: FavoriteAction) = when (a) {
    FavoriteAction.Forward -> Lucide.Forward
    FavoriteAction.Copy -> Lucide.Copy
    FavoriteAction.CancelDownload -> Lucide.X
    FavoriteAction.Delete -> Lucide.Trash2
}
