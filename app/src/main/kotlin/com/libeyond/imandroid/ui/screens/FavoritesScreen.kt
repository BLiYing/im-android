package com.libeyond.imandroid.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Bookmark
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Quote
import com.libeyond.imandroid.R
import com.libeyond.imandroid.data.FavoriteCategory
import com.libeyond.imandroid.data.FavoritePick
import com.libeyond.imandroid.data.Favorites
import com.libeyond.imandroid.sdk.api.Favorite
import com.libeyond.imandroid.ui.components.IMSearchField
import com.libeyond.imandroid.ui.components.IMTopBar
import com.libeyond.imandroid.ui.components.TimeFormat
import com.libeyond.imandroid.ui.theme.IMTheme

/**
 * 「我 ▸ 收藏消息」（M4-4，对齐 iOS `IMFavoritesViewController` 的 B 方案「以消息模式查看」）。
 *
 * ### 这一页几乎不自己画东西（用户 2026-09-17 的要求，也是 iOS 的做法）
 * iOS 收藏页的媒体宫格直接是详情页的 `IMDetailMediaContainerCell`、文件行直接是 `IMDetailFileCell`，
 * 下载态经合成的消息模型喂给与聊天页共用的编排器。本端对应：
 * - 媒体 / 文件 / 语音 / 链接 → 详情页归档那几种行（`ArchiveRows.kt`），它们的门控外观
 *   就是聊天页的那几个组件（`GateOverlays.kt`），下载状态按 URL 全局共享；
 * - 名片 / 聊天记录 → 聊天页气泡里的卡片内容（`CardBubbles.kt`），不另画一版；
 * - 文本 → 引号图标行（iOS `IMFavoriteRowCell`，聊天页没有对应物）。
 *
 * ### 选择模式（[pick] 非空，聊天页附件面板 ▸ 收藏）
 * 同一页、同一套行，只多三样：行尾 / 格右上角的勾选框、底部「发送 (N)」栏、左上角「取消」；
 * 长按菜单不出（iOS pick 模式同样禁掉上下文菜单与左滑删除）。**点行 / 点格仍是打开**，
 * 选中只走勾选框——判据在 [com.libeyond.imandroid.data.FavoritePick]。
 * */
@Composable
internal fun FavoritesScreen(
    categories: List<FavoriteCategory>,
    current: FavoriteCategory?,
    onSelect: (FavoriteCategory) -> Unit,
    query: String,
    onQueryChange: (String) -> Unit,
    /** 当前签 ∩ 关键词。 */
    shown: List<Favorite>,
    /** 一条收藏都没有（区分「还没有收藏」与「这一签/这次搜索是空的」）。 */
    noneAtAll: Boolean,
    /** 已加载条数：「加载更多」那一项按它换 key，翻完一页还在屏上时才会再触发一次。 */
    loadedCount: Int,
    /** 首屏在拉。 */
    loading: Boolean,
    /** 首屏拉失败且手里没有任何旧数据。 */
    failed: Boolean,
    hasMore: Boolean,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    host: String,
    useTls: Boolean,
    sourceNameOf: (Favorite) -> String,
    onOpen: (Favorite) -> Unit,
    onLongPress: (Favorite, Rect) -> Unit,
    onBack: () -> Unit,
    /** 选择模式（「从收藏发送」）；null = 浏览（「我 ▸ 收藏消息」）。 */
    pick: FavoritePickUi? = null,
    /** 下钻某来源会话时的标题（null = 「收藏消息」）与右上角槽（⋯ 模式菜单）。 */
    title: String? = null,
    topRight: (@Composable () -> Unit)? = null,
) {
    val c = IMTheme.colors
    Column(Modifier.fillMaxSize().background(c.groupedBackground).systemBarsPadding()) {
        if (pick != null) {
            IMTopBar(
                title = stringResource(R.string.favorites_pick_title),
                leftLabel = stringResource(R.string.common_cancel),
                onLeft = onBack,
            )
        } else {
            IMTopBar(title = title ?: stringResource(R.string.common_saved_messages), onLeft = onBack, right = topRight)
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            if (current != null) {
                item(key = "search") {
                    // 范围恒 = 当前签（iOS 的搜索 token 恒为当前签、不可删；本端用占位文案说清范围）
                    IMSearchField(
                        value = query,
                        onValueChange = onQueryChange,
                        placeholder = stringResource(R.string.favorites_search_placeholder_in_category, current.title),
                        modifier = Modifier.fillMaxWidth()
                            .padding(horizontal = IMTheme.dimens.space4)
                            .padding(top = 8.dp),
                    )
                }
                item(key = "tabs") {
                    SegTabBar(categories.map { it.title }, categories.indexOf(current)) { i -> onSelect(categories[i]) }
                }
            }
            when {
                loading && noneAtAll -> item { Hint(stringResource(R.string.common_loading)) }
                failed && noneAtAll -> item { RetryHint(onRetry) }
                noneAtAll || current == null -> item { EmptyFavorites() }
                shown.isEmpty() -> item {
                    Hint(
                        if (query.isNotBlank()) {
                            stringResource(R.string.favorites_empty_no_results)
                        } else {
                            Favorites.emptyText(current)
                        },
                    )
                }
                else -> favoriteItems(
                    current, shown, host, useTls, sourceNameOf, onOpen,
                    onLongPress = onLongPress.takeIf { pick == null }, pick = pick,
                )
            }
            // 滚到底续拉。**搜索时不续拉**（同 iOS）：过滤后列表变短、一进来就在底部，
            // 会立刻把剩余所有页拉光，而用户并没有要求
            // key 带已加载条数：同一个 key 的项留在屏上时 LoadMore 里的 effect 不会重跑，翻完一页就停住了
            if (hasMore && query.isBlank() && !noneAtAll) item(key = "more-$loadedCount") { LoadMore(onLoadMore) }
            item { Spacer(Modifier.height(24.dp)) }
        }
        if (pick != null) PickSendBar(pick.picked.size, pick.onSend)
    }
}

/** 选择模式的状态与回调（宿主 `FavoritesHost` 持有选中集）。 */
internal class FavoritePickUi(
    /** 选中的收藏 id。 */
    val picked: Set<Long>,
    val onToggle: (Favorite) -> Unit,
    val onSend: () -> Unit,
)

/**
 * 底部发送栏（iOS `buildPickBar`：52pt 内容区、右侧 36pt 高圆角强调色按钮、最窄 88pt）。
 * 没选时按钮灰着不可点，文案「发送」；选了是「发送 (N)」。
 */
@Composable
private fun PickSendBar(count: Int, onSend: () -> Unit) {
    val c = IMTheme.colors
    Column(Modifier.fillMaxWidth().background(c.surface)) {
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(c.separator))
        Row(
            Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(Modifier.weight(1f))
            val enabled = count > 0
            Box(
                Modifier.height(36.dp).widthIn(min = 88.dp).clip(RoundedCornerShape(18.dp))
                    .background(c.accent).alpha(if (enabled) 1f else 0.5f)
                    .clickable(enabled = enabled, onClick = onSend)
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    FavoritePick.sendLabel(count), color = c.onAccent,
                    style = MaterialTheme.typography.titleSmall,
                )
            }
        }
    }
}

/** 按页签把收藏交给对应的行。 */
private fun LazyListScope.favoriteItems(
    kind: FavoriteCategory,
    shown: List<Favorite>,
    host: String,
    useTls: Boolean,
    sourceNameOf: (Favorite) -> String,
    onOpen: (Favorite) -> Unit,
    /** null = 不响应长按（选择模式）。 */
    onLongPress: ((Favorite, Rect) -> Unit)?,
    pick: FavoritePickUi?,
) {
    // 行尾勾选框：选择模式才有
    val check: (Favorite) -> (@Composable () -> Unit)? = { f ->
        pick?.let { p -> { PickCheckButton(f.id in p.picked, onClick = { p.onToggle(f) }) } }
    }
    val longPressOf: (Favorite) -> ((Rect) -> Unit)? = { f -> onLongPress?.let { cb -> { r -> cb(f, r) } } }
    when (kind) {
        FavoriteCategory.Media -> {
            // 宫格的条目键是收藏 id（见 Favorites.toConvMediaItem），据此找回收藏本身
            // 策略档按来源会话分（Favorites.fromGroup）：一个宫格里单聊、群聊来的收藏混在一起，所以按格判
            val byId = shown.associateBy { it.id }
            mediaGrid(
                archive = shown.map(Favorites::toConvMediaItem),
                host = host,
                useTls = useTls,
                isGroupOf = { item -> byId[item.convSeq]?.let(Favorites::fromGroup) ?: false },
                onOpenArchive = { item -> byId[item.convSeq]?.let(onOpen) },
                onLongPressItem = onLongPress?.let { cb -> { item, r -> byId[item.convSeq]?.let { cb(it, r) } } },
                pickedOf = pick?.let { p -> { item -> item.convSeq in p.picked } },
                onTogglePick = { item -> byId[item.convSeq]?.let { pick?.onToggle?.invoke(it) } },
            )
        }
        FavoriteCategory.Files -> items(shown, key = { it.id }) { f ->
            FileRow(
                item = Favorites.toConvMediaItem(f), isGroup = Favorites.fromGroup(f),
                onOpen = { onOpen(f) }, source = sourceNameOf(f),
                onLongPress = longPressOf(f), trailing = check(f),
            )
        }
        FavoriteCategory.Voice -> items(shown, key = { it.id }) { f ->
            // 收藏快照自带波形（`im_favorite.waveform`），不用像详情页那样从本地消息表兜底
            VoiceRow(
                item = Favorites.toConvMediaItem(f), playId = "fav:${f.id}", convId = f.sourceConvId,
                waveform = f.waveform.ifBlank { null },
                source = sourceNameOf(f), onLongPress = longPressOf(f), trailing = check(f),
            )
        }
        FavoriteCategory.Links -> items(shown, key = { it.id }) { f ->
            LinkRow(
                text = f.content, timestamp = f.createdAt, url = Favorites.linkUrl(f),
                source = sourceNameOf(f), onLongPress = longPressOf(f), trailing = check(f),
            ) { onOpen(f) }
        }
        FavoriteCategory.Text -> items(shown, key = { it.id }) { f ->
            TextFavoriteRow(f, sourceNameOf(f), onClick = { onOpen(f) }, onLongPress = longPressOf(f), trailing = check(f))
        }
        FavoriteCategory.Record, FavoriteCategory.Contact -> items(shown, key = { it.id }) { f ->
            CardFavoriteRow(
                f, kind, sourceNameOf(f),
                onClick = { onOpen(f) }, onLongPress = longPressOf(f), trailing = check(f),
            )
        }
    }
}

/**
 * 文本收藏一行：左 36dp 淡主色底 + 引号图标、正文最多 3 行、时间、来自X（iOS `IMFavoriteRowCell` 文本类）。
 * 点开是全文阅读页（FAVORITES_DESIGN §5.6），复制走长按。
 */
@Composable
private fun TextFavoriteRow(
    f: Favorite,
    source: String,
    onClick: () -> Unit,
    onLongPress: ((Rect) -> Unit)?,
    trailing: (@Composable () -> Unit)?,
) {
    val c = IMTheme.colors
    val d = IMTheme.dimens
    Column {
        Row(
            Modifier.fillMaxWidth().background(c.surface)
                .archiveItemGestures(onClick = onClick, onLongPress = onLongPress)
                .padding(horizontal = d.space4, vertical = 12.dp),
        ) {
            Box(
                Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)).background(c.accentSoft),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    Lucide.Quote, stringResource(R.string.favorites_category_text),
                    Modifier.size(18.dp), colorFilter = ColorFilter.tint(c.accent),
                )
            }
            Spacer(Modifier.width(d.space3))
            Column(Modifier.weight(1f)) {
                Text(
                    f.content, color = c.textPrimary, style = MaterialTheme.typography.bodyLarge,
                    maxLines = 3, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    TimeFormat.fileDateTime(f.createdAt),
                    color = c.textTertiary, style = MaterialTheme.typography.bodySmall,
                )
                SourceLine(source)
            }
            trailing?.invoke()
        }
        Box(Modifier.fillMaxWidth().padding(start = 68.dp).height(0.5.dp).background(c.separator))
    }
}

/**
 * 名片 / 聊天记录收藏一行：**卡片本体就是聊天页气泡里那一张**（[ContactCardContent] /
 * [ChatRecordCardContent]），下面加时间与来自X。各画一版的话，名片卡在两处长得不一样是迟早的事。
 */
@Composable
private fun CardFavoriteRow(
    f: Favorite,
    kind: FavoriteCategory,
    source: String,
    onClick: () -> Unit,
    onLongPress: ((Rect) -> Unit)?,
    trailing: (@Composable () -> Unit)?,
) {
    val c = IMTheme.colors
    val d = IMTheme.dimens
    val screenW = LocalConfiguration.current.screenWidthDp
    // 有勾选框时卡片让出 36dp，不然卡片把勾选框挤出屏
    val slot = if (trailing != null) 36 else 0
    val cardW = remember(screenW, slot) { (screenW - 2 * 16 - 2 * 12 - slot).coerceAtLeast(200).dp }
    Column {
        Row(
            Modifier.fillMaxWidth().background(c.surface)
                .archiveItemGestures(onClick = onClick, onLongPress = onLongPress)
                .padding(horizontal = d.space4, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Box(
                    Modifier.clip(RoundedCornerShape(IMTheme.appearance.bubbleRadius))
                        .background(c.bubbleThem).padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    if (kind == FavoriteCategory.Contact) {
                        ContactCardContent(f.content, width = cardW)
                    } else {
                        ChatRecordCardContent(f.content, width = cardW)
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    TimeFormat.fileDateTime(f.createdAt),
                    color = c.textTertiary, style = MaterialTheme.typography.bodySmall,
                )
                SourceLine(source)
            }
            trailing?.invoke()
        }
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(c.separator))
    }
}

/** 一条收藏都没有（iOS `updateEmptyState` 那一档：大书签 + 两行字）。 */
@Composable
private fun EmptyFavorites() {
    val c = IMTheme.colors
    Column(
        Modifier.fillMaxWidth().padding(top = 96.dp, start = 32.dp, end = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(Lucide.Bookmark, null, Modifier.size(44.dp), colorFilter = ColorFilter.tint(c.textTertiary))
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.favorites_empty_title), color = c.textPrimary, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.favorites_empty_hint),
            color = c.textTertiary, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
        )
    }
}

/** 首屏拉失败且手里没旧数据：说清楚、给重试（iOS 是「加载失败 / 下拉重试」，本端没有下拉刷新，给一个点）。 */
@Composable
private fun RetryHint(onRetry: () -> Unit) {
    val c = IMTheme.colors
    Column(Modifier.fillMaxWidth().padding(top = 96.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(R.string.common_load_failed), color = c.textPrimary, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.common_tap_to_retry), color = c.accent, style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.archiveItemGestures(onClick = onRetry, onLongPress = null),
        )
    }
}

/**
 * 文本收藏的全文阅读页（FAVORITES_DESIGN §5.6，iOS `IMFavoriteReaderViewController`）：
 * 只读、可滚动、**可选中复制**，字号跟随聊天字号。点文本 = 看全貌，不做"点即复制"这种隐形副作用。
 */
@Composable
internal fun FavoriteReaderScreen(text: String, onBack: () -> Unit) {
    val c = IMTheme.colors
    Column(Modifier.fillMaxSize().background(c.pageBackground).systemBarsPadding()) {
        IMTopBar(title = stringResource(R.string.favorites_reader_title), onLeft = onBack, containerColor = c.pageBackground)
        androidx.compose.foundation.text.selection.SelectionContainer(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        ) {
            Text(
                text,
                color = c.textPrimary,
                fontSize = IMTheme.appearance.chatFontSize,
                modifier = Modifier.padding(IMTheme.dimens.space4),
            )
        }
    }
}
