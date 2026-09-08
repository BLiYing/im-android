package com.libeyond.imandroid.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.libeyond.imandroid.data.DetailAction
import com.libeyond.imandroid.data.DetailMoreAction
import com.libeyond.imandroid.data.DetailTab
import com.libeyond.imandroid.data.DetailTabs
import com.libeyond.imandroid.data.db.ConversationEntity
import com.libeyond.imandroid.data.db.MessageEntity
import com.libeyond.imandroid.sdk.api.ConvMediaItem
import com.libeyond.imandroid.ui.components.IMAvatar
import com.libeyond.imandroid.ui.components.DetailActionBar
import com.libeyond.imandroid.ui.components.IMTopBar
import com.libeyond.imandroid.ui.theme.IMTheme

/**
 * 单聊详情页（M4.5-3）。
 *
 * 结构对齐 iOS `IMChatDetailViewController`：**大头像头部 + 信息卡 + 设置卡 + 内联页签**。
 * 归档在本页内切 tab，不跳出去——这是与 iOS 差得最远的一处，2026-09-08 改过来的
 * （此前是一行「聊天媒体」push 出一整页）。差异登记见 `docs/UI_PARITY_IOS.md` §2。
 *
 * **与「用户资料页」是两件事**：后者回答"这个人是谁"（备注/加好友/删好友），
 * 本页回答"这段对话怎么设置"。iOS 同样是两个 VC，用户资料是本页 push 出去的。
 *
 * **不做水滴形变**：那需要把头像"吸进灵动岛"，而 Android 没有灵动岛
 * （差异档 §1 记着为什么不做，别再来一次）。
 */
@Composable
internal fun ChatDetailScreen(
    conv: ConversationEntity,
    title: String,
    handle: String,
    remark: String,
    pinned: Boolean,
    muted: Boolean,
    tab: DetailTab,
    onTabChange: (DetailTab) -> Unit,
    /** 当前页签的归档数据（链接页签走 [linkMessages]，这里为空）。 */
    archive: List<ConvMediaItem>,
    /** 链接页签：本地已加载的消息，由调用方扫出 URL。 */
    linkMessages: List<Pair<MessageEntity, String>>,
    loading: Boolean,
    hasMore: Boolean,
    onLoadMore: () -> Unit,
    onOpenArchive: (ConvMediaItem) -> Unit,
    onOpenLink: (String) -> Unit,
    onTogglePinned: (Boolean) -> Unit,
    onToggleMuted: (Boolean) -> Unit,
    onSetRemark: () -> Unit,
    onOpenProfile: () -> Unit,
    /** 头部操作排（消息/呼叫/视频/搜索/更多，或非好友时只有「加好友」）。 */
    actions: List<DetailAction>,
    moreItems: List<DetailMoreAction>,
    onAction: (DetailAction) -> Unit,
    onMore: (DetailMoreAction) -> Unit,
    host: String,
    useTls: Boolean,
    onBack: () -> Unit,
) {
    val c = IMTheme.colors
    val d = IMTheme.dimens
    val tabs = DetailTabs.visible(isGroup = false)

    Column(Modifier.fillMaxSize().background(c.groupedBackground).systemBarsPadding()) {
        IMTopBar(title = "聊天信息", onLeft = onBack)

        // 页签内容是可滚动的长列表，头部/卡片作为它的头几项 —— 整页一条滚动轴，
        // 与 iOS 的 tableHeaderView + sections 同构（不是"上面固定、下面单独滚"）。
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                // —— 大头像头部（对齐 iOS 的 300pt tableHeaderView）——
                Column(
                    Modifier.fillMaxWidth().background(c.pageBackground)
                        .clickable(onClick = onOpenProfile).padding(vertical = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    IMAvatar(title, seed = conv.peerUid, avatarUrl = conv.avatarUrl, size = 100.dp)
                    Spacer(Modifier.height(12.dp))
                    Text(title, style = MaterialTheme.typography.headlineSmall, color = c.textPrimary)
                    // 标识行为空时**整行隐藏**——不显示「用户名：未设置」，更不回退内部 ID
                    if (handle.isNotEmpty()) {
                        Text(handle, style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
                    }
                }

                // —— 操作排（对齐 iOS 头部的 pills）——
                Spacer(Modifier.height(d.cardGap))
                DetailActionBar(actions, moreItems, onAction, onMore)

                // —— 信息（对齐 iOS 的 IMDetailSectionInfo：备注名 + 用户名）——
                Spacer(Modifier.height(d.cardGap))
                Card {
                    Row2("备注名", remark.ifBlank { "未设置" }, onClick = onSetRemark)
                    if (handle.isNotEmpty()) {
                        Divider()
                        Row2("用户名", handle)
                    }
                }

                // —— 设置 ——
                Spacer(Modifier.height(d.cardGap))
                Card {
                    // 两项都走 PUT /conversations/{id}/settings，而那是**整体替换**三项，
                    // 所以改一项也要把另外两项原样带回（Host 里做）。
                    SwitchRow("置顶聊天", pinned, onTogglePinned)
                    Divider()
                    SwitchRow("消息免打扰", muted, onToggleMuted)
                    // 「查找聊天记录 / 清空聊天记录」**不在这张卡上**：iOS 把它们放在头部
                    // 操作排的「搜索」与「更多 → 清空聊天记录」里。摆两处等于同一件事有两个入口，
                    // 而其中一个还写着"还没做"。
                }

                Spacer(Modifier.height(d.cardGap))
                DetailTabBar(tabs, tab) { onTabChange(it) }
            }

            // —— 页签内容 ——
            when (tab) {
                DetailTab.Links -> {
                    item { Footnote(LINK_TAB_NOTE) }
                    if (linkMessages.isEmpty()) {
                        item { Hint(DetailTabs.emptyText(tab)) }
                    } else {
                        items(linkMessages, key = { it.first.convSeq }) { (m, url) ->
                            LinkRow(m.content, m.timestamp, url) { onOpenLink(url) }
                        }
                    }
                }
                DetailTab.Voice -> {
                    item { Footnote(VOICE_TAB_NOTE) }
                    archiveList(archive, loading, hasMore, tab, onLoadMore) { item -> VoiceRow(item) }
                }
                DetailTab.Files -> archiveList(archive, loading, hasMore, tab, onLoadMore) { item ->
                    FileRow(item, onOpenArchive)
                }
                DetailTab.Media -> {
                    if (loading && archive.isEmpty()) {
                        item { Hint("加载中…") }
                    } else if (archive.isEmpty()) {
                        item { Hint(DetailTabs.emptyText(tab)) }
                    } else {
                        item {
                            // 宫格嵌在纵向列表里：**给定高度不能无界**，否则 LazyVerticalGrid
                            // 在 LazyColumn 里会崩（无限高约束）。按行数算高度。
                            val rows = (archive.size + 3) / 4
                            LazyVerticalGrid(
                                columns = GridCells.Fixed(4),
                                modifier = Modifier.fillMaxWidth()
                                    .height((rows * 92).dp.coerceAtMost(1200.dp)),
                                horizontalArrangement = Arrangement.spacedBy(2.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                items(archive, key = { it.convSeq }) { ArchiveTile(it, host, useTls, onOpen = onOpenArchive) }
                            }
                        }
                        if (hasMore) item { LoadMoreRow(onLoadMore) }
                    }
                }
                DetailTab.Members -> Unit   // 单聊没有这一格（DetailTabs.visible 已经不给）
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

/** 文件/语音这类纵向列表页签共用的一段（空态、分页、行渲染交给调用方）。 */
private fun androidx.compose.foundation.lazy.LazyListScope.archiveList(
    items: List<ConvMediaItem>,
    loading: Boolean,
    hasMore: Boolean,
    tab: DetailTab,
    onLoadMore: () -> Unit,
    row: @Composable (ConvMediaItem) -> Unit,
) {
    if (loading && items.isEmpty()) {
        item { Hint("加载中…") }
        return
    }
    if (items.isEmpty()) {
        item { Hint(DetailTabs.emptyText(tab)) }
        return
    }
    items(items, key = { it.convSeq }) { row(it) }
    if (hasMore) item { LoadMoreRow(onLoadMore) }
}

@Composable
private fun LoadMoreRow(onLoadMore: () -> Unit) {
    // 滚到底自动续拉；手点入口保留作失败重试
    LaunchedEffect(Unit) { onLoadMore() }
    Box(Modifier.fillMaxWidth().padding(14.dp), contentAlignment = Alignment.Center) {
        Text("加载更多", color = IMTheme.colors.accent, modifier = Modifier.clickable { onLoadMore() })
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = IMTheme.dimens.space4)
            .clip(RoundedCornerShape(IMTheme.dimens.radiusCard))
            .background(IMTheme.colors.cardBackground),
    ) { content() }
}

@Composable
private fun Divider() {
    Box(
        Modifier.fillMaxWidth().padding(start = IMTheme.dimens.space4)
            .height(0.5.dp).background(IMTheme.colors.separator),
    )
}

/** 左标题 + 右值（+ 可点时带 `›`）。对齐 iOS `UITableViewCellStyleValue1`。 */
@Composable
private fun Row2(label: String, value: String = "", danger: Boolean = false, onClick: (() -> Unit)? = null) {
    val c = IMTheme.colors
    Row(
        Modifier.fillMaxWidth()
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = IMTheme.dimens.space4, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = if (danger) c.danger else c.textPrimary,
            style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        if (value.isNotEmpty()) {
            Text(value, color = c.textSecondary, style = MaterialTheme.typography.bodyMedium)
        }
        if (onClick != null) Text("  ›", color = c.textTertiary)
    }
}

@Composable
private fun SwitchRow(label: String, on: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .padding(start = IMTheme.dimens.space4, end = IMTheme.dimens.space3, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = IMTheme.colors.textPrimary,
            style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = on, onCheckedChange = onToggle)
    }
}
