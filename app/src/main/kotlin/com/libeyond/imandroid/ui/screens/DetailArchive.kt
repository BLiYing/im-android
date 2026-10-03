package com.libeyond.imandroid.ui.screens

import com.libeyond.imandroid.voice.VoiceRules
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import com.libeyond.imandroid.R
import com.libeyond.imandroid.data.ArchiveTarget
import com.libeyond.imandroid.data.DetailTab
import com.libeyond.imandroid.data.toArchiveTarget
import com.libeyond.imandroid.data.db.MessageEntity
import com.libeyond.imandroid.data.DetailTabs
import com.libeyond.imandroid.data.LinkScan
import com.libeyond.imandroid.data.MediaGrid
import com.libeyond.imandroid.sdk.api.ConvMediaItem
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import com.libeyond.imandroid.ui.theme.IMTheme

/**
 * 详情页的内联页签条（对齐 iOS 的 `pillsView` / `IMLiquidSegmentedControl`）。
 *
 * **归档在详情页内切 tab，不跳出去**——这是 iOS 的形态，也是本端 2026-09-08 之前
 * 与 iOS 差得最远的一处（当时是一行「聊天媒体」push 出去一整页）。
 */
@Composable
internal fun DetailTabBar(tabs: List<DetailTab>, current: DetailTab, onSelect: (DetailTab) -> Unit) {
    SegTabBar(tabs.map { DetailTabs.title(it) }, tabs.indexOf(current)) { i -> onSelect(tabs[i]) }
}

/**
 * 页签条本体（底轨 + 药丸）。详情页、群资料、**收藏页**共用——三页的页签必须长得一样，
 * 最可靠的保证是同一段代码画的（iOS 三处同为 `IMLiquidSegmentedControl`）。
 */
@Composable
internal fun SegTabBar(titles: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val c = IMTheme.colors
    // **底轨 + 药丸**（对齐 iOS `IMLiquidSegmentedControl`：track 玻璃、pill 浮在上面）。
    // 起初这里只有一排裸按钮、选中态是 12% 绿底 + 绿字：深色模式下几乎看不出选了哪个
    // （2026-09-08 用户报的「选中态颜色太暗」）。iOS 的做法是**选中与未选中同为主文字色，
    // 只靠字重与药丸底色区分**——照抄这一条，别再用低透明度主色去表达"选中"。
    // **整条居中、底轨贴合内容宽度**（对齐 iOS `IMLiquidSegmentedControl`：药丸条居中、不拉满）。
    // 此前底轨 fillMaxWidth，页签靠左、最右一个页签（链接）后面是一大片空底。
    // 放不下（页签多 / 大字号）时底轨顶满可用宽度并横向滚动，不截断。
    Box(
        Modifier.fillMaxWidth().padding(horizontal = IMTheme.dimens.space4, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier
                .clip(RoundedCornerShape(18.dp))
                .background(c.subtleFill)
                .horizontalScroll(rememberScrollState())
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            titles.forEachIndexed { i, t -> MediaSeg(t, i == selected) { onSelect(i) } }
        }
    }
}

/**
 * 「链接」页签的脚注。**必须说清楚它只覆盖本地已加载的消息**——
 * 服务端没有可索引的链接列（`internal/conversation/media.go`），这一格只能扫本地文本，
 * 与其他几格的"全量"语义不同。不说的话用户会以为链接丢了。
 */
internal val LINK_TAB_NOTE: String
    // **不能是顶层 const val**（禁止在顶层初始化时求值文案，切语言不会变）：
    // 改成 @Composable getter，两处调用点（`item { Footnote(LINK_TAB_NOTE) }`）本就在组合期，
    // 读法不用变——只是从编译期常量变成运行时按当前语言取。
    @Composable get() = stringResource(R.string.detail_tab_link_note)


/**
 * 会话媒体库页的标题。**逐字取自 iOS** `IMConversationMediaViewController.viewDidLoad`
 * 的 `self.title = @"图片与视频"`——本端此前显的是会话名（2026-09-17 用户报）。
 * 单聊详情与群资料共用这一个属性，别在两处各写一遍字面量。
 */
internal val GALLERY_TITLE: String
    @Composable get() = stringResource(R.string.gallery_title)

/** 判定一条本地消息是不是链接（薄封装，方便调用点读起来短）。 */
internal fun linkUrlOf(contentType: String, content: String, convSeq: Long): String? =
    if (LinkScan.isLinkMessage(contentType, content, convSeq)) LinkScan.firstUrl(content) else null

@Composable
internal fun MediaSeg(label: String, on: Boolean, onClick: () -> Unit) {
    val c = IMTheme.colors
    // **选中/未选中同为主文字色，只以字重 + 药丸底色区分**（逐条对齐 iOS
    // `IMLiquidSegmentedControl.applyFonts`：semibold / medium，都是 label 色）。
    // 别再用「12% 主色底 + 主色字」表示选中——那在深色模式下几乎看不出来。
    Box(
        Modifier.clip(RoundedCornerShape(14.dp))
            .background(if (on) c.surfaceElevated else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        Text(
            label,
            color = c.textPrimary,
            fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
internal fun Hint(text: String) {
    Box(Modifier.fillMaxWidth().padding(top = 48.dp), contentAlignment = Alignment.Center) {
        Text(text, color = IMTheme.colors.textTertiary, style = MaterialTheme.typography.bodyMedium)
    }
}

/** 滚到底自动续拉；手点入口保留作失败重试（同群成员列表那条）。 */
@Composable
internal fun LoadMore(onLoadMore: () -> Unit) {
    LaunchedEffect(Unit) { onLoadMore() }
    Box(Modifier.fillMaxWidth().padding(14.dp), contentAlignment = Alignment.Center) {
        Text(
            stringResource(R.string.common_load_more),
            color = IMTheme.colors.accent,
            modifier = Modifier.clickable { onLoadMore() },
        )
    }
}

/**
 * 归档页签的**内容**（媒体 / 文件 / 语音 / 链接），单聊详情与群资料共用。
 *
 * 共用这一段是本轮 (2026-09-09) 的重点：两页"长得一样"最可靠的保证不是各写一遍对着改，
 * 而是**同一段代码画的**。此前群那一侧是另一整页（`ConvMediaScreen`），
 * 只有两个分类、没有语音与链接、空态文案也另起一套。
 *
 * 「成员」不在这里——它不是消息，数据来自群成员接口，由群资料那一页自己渲染。
 */
internal fun LazyListScope.archiveTab(
    tab: DetailTab,
    /** 所属会话：语音行的播放标识与已播红点按会话分（见 [VoiceRules.playableId]）。 */
    convId: String,
    archive: List<ConvMediaItem>,
    /** `null` = 本地还没扫完。 */
    linkMessages: List<Pair<MessageEntity, String>>?,
    loading: Boolean,
    hasMore: Boolean,
    onLoadMore: () -> Unit,
    onOpenArchive: (ConvMediaItem) -> Unit,
    onLongPressArchive: (ArchiveTarget, Rect) -> Unit,
    onOpenLink: (String) -> Unit,
    host: String,
    useTls: Boolean,
    isGroup: Boolean,
    /**
     * uid → 显示名。**语音行要显发送者**（iOS 语音 cell 第一行就是它）；拿不到时给空串，
     * 那一行整行不画，而不是显一串内部 uid。
     */
    senderNameOf: (String) -> String = { "" },
    /**
     * conv_seq → 波形。服务端归档接口**不回带** `waveform`（`internal/conversation/media.go` 没有这一列），
     * 所以由调用方从**本地消息表**按 conv_seq 兜底取；取不到就是等高条纹（协议允许的合法状态）。
     * 这同时修掉了「从语音页签转发出去的语音在收端只有等高条纹」那条老限制——
     * 下面把它一并写进 [ArchiveTarget]。
     */
    waveformOf: (Long) -> String? = { null },
    /** 「名片」页签的数据与点击（没有名片时页签不出现，此参数不会被用到）。 */
    contacts: ContactTabData? = null,
) {
    when (tab) {
        DetailTab.Contacts -> contactTab(contacts)
        DetailTab.Members -> Unit // 不是消息，见 KDoc
        DetailTab.Links -> {
            item { Footnote(LINK_TAB_NOTE) }
            if (linkMessages == null) {
                item { Hint(stringResource(R.string.common_loading)) }
            } else if (linkMessages.isEmpty()) {
                item { Hint(DetailTabs.emptyText(tab)) }
            } else {
                items(linkMessages, key = { it.first.convSeq }) { (m, url) ->
                    LinkRow(
                        m.content, m.timestamp, url,
                        onLongPress = { r -> onLongPressArchive(m.toArchiveTarget(), r) },
                    ) { onOpenLink(url) }
                }
            }
        }
        DetailTab.Voice -> {
            archiveList(archive, loading, hasMore, tab, onLoadMore) { item ->
                val wave = waveformOf(item.convSeq)
                VoiceRow(
                    item, playId = VoiceRules.playableId(item.convSeq, "").orEmpty(), convId = convId,
                    senderName = senderNameOf(item.sender), waveform = wave,
                    // 波形一并带进菜单目标：从这一格转发出去的语音才不会丢波形
                    onLongPress = { r -> onLongPressArchive(item.toArchiveTarget().copy(waveform = wave), r) },
                )
            }
        }
        DetailTab.Files -> archiveList(archive, loading, hasMore, tab, onLoadMore) { item ->
            FileRow(
                item, isGroup = isGroup, onOpen = onOpenArchive,
                onLongPress = { r -> onLongPressArchive(item.toArchiveTarget(), r) },
            )
        }
        DetailTab.Media -> {
            if (loading && archive.isEmpty()) {
                item { Hint(stringResource(R.string.common_loading)) }
            } else if (archive.isEmpty()) {
                item { Hint(DetailTabs.emptyText(tab)) }
            } else {
                mediaGrid(
                    archive, host, useTls, isGroupOf = { isGroup }, onOpenArchive = onOpenArchive,
                    onLongPressItem = { item, r -> onLongPressArchive(item.toArchiveTarget(), r) },
                )
                if (hasMore) item { LoadMore(onLoadMore) }
            }
        }
    }
}

/**
 * 媒体宫格：**由外层列表逐行渲染**，本身不是一个会滚动的容器。
 * 详情页「媒体」页签、会话媒体库页、**收藏页「媒体」签**共用这一份（iOS 收藏页同样直接复用
 * 详情页的 `IMDetailMediaContainerCell`）。
 *
 * ### 为什么不能用 LazyVerticalGrid（2026-09-16 用户报了两条，都是它）
 * 此前是把 `LazyVerticalGrid` 塞进 `LazyColumn` 的一个 `item {}` 里，
 * 高度按条数算成 `rows * 92dp` 再 `coerceAtMost(1200dp)`：
 *
 * 1. **纵向嵌套同向滚动**——手指落在宫格上时竖向拖动被内层吃掉，整个详情页滚不动。
 *    用户报的「这个页面很卡、划不动」就是它，不是性能问题，是手势被抢了。
 * 2. **1200dp 封顶把内容裁掉**——媒体超过约 13 行之后，多出来的格子被切在容器外，
 *    滚都滚不到，即「看不到媒体里全部照片」。
 *
 * 一行一个 `item` 之后：没有内层滚动容器、没有高度要算、没有上限要夹，
 * 而且天然惰性（只组合可见的那几行）。分行与补位的判据在 [MediaGrid]。
 */
internal fun LazyListScope.mediaGrid(
    archive: List<ConvMediaItem>,
    host: String,
    useTls: Boolean,
    /** 这一格按单聊还是群聊的自动下载策略判（详情页整页同一个值；收藏页按每条的来源会话）。 */
    isGroupOf: (ConvMediaItem) -> Boolean,
    onOpenArchive: (ConvMediaItem) -> Unit,
    /** 长按一格。交回条目本身而不是 [ArchiveTarget]：收藏页复用这份宫格，它的菜单作用在收藏上。null = 不响应长按。 */
    onLongPressItem: ((ConvMediaItem, Rect) -> Unit)?,
    /** 「从收藏发送」：这一格勾没勾；null = 不在选择模式（详情页、媒体库、收藏浏览），不画勾选框。 */
    pickedOf: ((ConvMediaItem) -> Boolean)? = null,
    onTogglePick: (ConvMediaItem) -> Unit = {},
) {
    val rows = MediaGrid.rows(archive)
    itemsIndexed(rows, key = { _, row -> row.first().convSeq }) { idx, row ->
        Row(
            Modifier.fillMaxWidth().padding(top = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            row.forEach { item ->
                Box(Modifier.weight(1f)) {
                    ArchiveTile(
                        item, host, useTls, isGroup = isGroupOf(item), onOpen = onOpenArchive,
                        onLongPress = onLongPressItem?.let { cb -> { r -> cb(item, r) } },
                        picked = pickedOf?.invoke(item),
                        onTogglePick = { onTogglePick(item) },
                    )
                }
            }
            // 末行用等宽空位补齐：不补的话只有两张图的那一行会各占半屏，
            // 同一个宫格里格子大小不一，看着像排版坏了
            if (idx == rows.lastIndex) {
                repeat(MediaGrid.blanksInLastRow(archive.size)) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** 文件/语音这类纵向列表页签共用的一段（空态、分页、行渲染交给调用方）。 */
private fun LazyListScope.archiveList(
    items: List<ConvMediaItem>,
    loading: Boolean,
    hasMore: Boolean,
    tab: DetailTab,
    onLoadMore: () -> Unit,
    row: @Composable (ConvMediaItem) -> Unit,
) {
    if (loading && items.isEmpty()) {
        item { Hint(stringResource(R.string.common_loading)) }
        return
    }
    if (items.isEmpty()) {
        item { Hint(DetailTabs.emptyText(tab)) }
        return
    }
    items(items, key = { it.convSeq }) { row(it) }
    if (hasMore) item { LoadMore(onLoadMore) }
}
