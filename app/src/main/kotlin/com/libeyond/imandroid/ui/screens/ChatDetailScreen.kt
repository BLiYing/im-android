package com.libeyond.imandroid.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.Rect
import com.libeyond.imandroid.R
import com.libeyond.imandroid.data.ArchiveTarget
import com.libeyond.imandroid.data.DetailAction
import com.libeyond.imandroid.data.DetailMoreAction
import com.libeyond.imandroid.data.DetailTab
import com.libeyond.imandroid.data.DetailTabs
import com.libeyond.imandroid.data.db.ConversationEntity
import com.libeyond.imandroid.data.db.MessageEntity
import com.libeyond.imandroid.data.toArchiveTarget
import com.libeyond.imandroid.sdk.api.ConvMediaItem
import com.libeyond.imandroid.ui.components.IMAvatar
import com.libeyond.imandroid.ui.components.DetailActionBar
import com.libeyond.imandroid.ui.components.IMSectionFooter
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
    /** 头部副标题 = 在线态文案（空串不显）。**不能再重复 @句柄**——下面「用户名」行已经有（iOS `displaySubtitle`）。 */
    subtitle: String = "",
    /** 长按「用户名」行：复制裸句柄（不带 @）并说一声（宿主做剪贴板与吐司）。 */
    onCopyUsername: () -> Unit = {},
    remark: String,
    pinned: Boolean,
    /** 「消息免打扰」行的右值：`common_off` / `notif_mute_until_*` / `common_permanent`
     *  （NOTIFICATIONS_P1_DESIGN §4.2，本页不持免打扰判定逻辑，调用方算好文本传进来——纯展示）。 */
    muteValueText: String,
    tab: DetailTab,
    onTabChange: (DetailTab) -> Unit,
    /** 当前页签的归档数据（链接页签走 [linkMessages]，这里为空）。 */
    archive: List<ConvMediaItem>,
    /** 链接页签：本地已加载的消息，由调用方扫出 URL。 */
    linkMessages: List<Pair<MessageEntity, String>>?,
    loading: Boolean,
    hasMore: Boolean,
    onLoadMore: () -> Unit,
    onOpenArchive: (ConvMediaItem) -> Unit,
    /** 长按归档里的一项 → 归档菜单。链接页签传的是那条本地消息。 */
    onLongPressArchive: (ArchiveTarget, Rect) -> Unit,
    onOpenLink: (String) -> Unit,
    onTogglePinned: (Boolean) -> Unit,
    /** 点「消息免打扰」行：打开时长菜单（NOTIFICATIONS_P1_DESIGN §4.1/§4.2，草图 B）。 */
    onOpenMuteSheet: () -> Unit,
    onSetRemark: () -> Unit,
    onOpenProfile: () -> Unit,
    /** 头部操作排（消息/呼叫/视频/搜索/更多，或非好友时只有「加好友」）。 */
    actions: List<DetailAction>,
    moreItems: List<DetailMoreAction>,
    onAction: (DetailAction) -> Unit,
    onMore: (DetailMoreAction) -> Unit,
    host: String,
    useTls: Boolean,
    /** 见 [archiveTab] 同名参数：语音行要显发送者名。 */
    senderNameOf: (String) -> String = { "" },
    /** 见 [archiveTab] 同名参数：归档接口不回带波形，由本地消息表兜底。 */
    waveformOf: (Long) -> String? = { null },
    /**
     * 只当**会话媒体库**用：去掉头像头部/信息卡/设置卡/页签条，整页就是媒体宫格，
     * 标题换成会话名。查看器右下角的「媒体」钮进的是这一页。
     *
     * **为什么不是直接跳详情页**：iOS 那边 `galleryTapped` 打开的是独立的
     * `IMConversationMediaViewController`（标题＝会话名的全屏媒体库），而不是
     * 「聊天信息」页。本端此前跳的是详情页并落在媒体页签上——用户点「媒体」却进了设置页
     * （2026-09-16 用户报）。**复用本页而不是另起一页**：归档取数、长按菜单、查看器、
     * 转发选择页那整套接线都在宿主里，另写一页必然分叉（`ConvMediaScreen` 已经因此被并掉过一次）。
     */
    galleryOnly: Boolean = false,
    /**
     * 对端是系统通知账号（uid `777000`，[com.libeyond.imandroid.data.DetailActions.SYSTEM_UID]）。
     * 对齐 Web `DetailPanel.tsx` 的 `isSystemPeer`/`showDetailBody`：备注名/设置卡/页签整段隐藏，
     * 换成一段说明卡——系统通知会话没有"备注"这回事，也没有媒体/文件/链接可归档。
     * `actions`/`moreItems` 已经在调用方经 `DetailActions.pillsFor/moreFor` 收窄，这里只补齐正文。
     */
    isSystemPeer: Boolean = false,
    onBack: () -> Unit,
) {
    val c = IMTheme.colors
    com.libeyond.imandroid.ui.voice.PauseVoiceOnLeave() // 离开本页暂停语音（保留位点）
    val d = IMTheme.dimens
    val tabs = DetailTabs.visible(isGroup = false)
    val showBody = !galleryOnly && !isSystemPeer

    Column(Modifier.fillMaxSize().background(c.groupedBackground).systemBarsPadding()) {
        // 会话媒体库的标题是**「图片与视频」**，不是会话名——逐字对齐 iOS
        // `IMConversationMediaViewController.viewDidLoad` 的 `self.title = @"图片与视频"`
        // （2026-09-17 用户报：本端显的是会话名）。
        IMTopBar(
            title = if (galleryOnly) GALLERY_TITLE else stringResource(R.string.chat_detail_title_user),
            onLeft = onBack,
        )

        // 页签内容是可滚动的长列表，头部/卡片作为它的头几项 —— 整页一条滚动轴，
        // 与 iOS 的 tableHeaderView + sections 同构（不是"上面固定、下面单独滚"）。
        //
        // **头部拆成几个独立 item，别再合回一个大 item**（2026-09-17「详情页很卡」一并收的）：
        // 合在一起时头像 + 操作排 + 两张卡 + 页签条是同一个 item，往回滚到它露出一个像素，
        // 整块就得在同一帧里重新组合、测量——那一帧正好卡在手指下。拆开后每次只进来一小块。
        LazyColumn(Modifier.fillMaxSize()) {
            if (!galleryOnly) item(key = "header") {
                // —— 大头像头部（对齐 iOS 的 300pt tableHeaderView）——
                Column(
                    Modifier.fillMaxWidth().background(c.pageBackground)
                        .clickable(onClick = onOpenProfile).padding(vertical = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    IMAvatar(title, seed = conv.peerUid, avatarUrl = conv.avatarUrl, size = 100.dp)
                    Spacer(Modifier.height(12.dp))
                    Text(title, style = MaterialTheme.typography.headlineSmall, color = c.textPrimary)
                    // 副标题 = 在线态，空串整行隐藏；句柄在下面「用户名」行，这里不重复
                    if (subtitle.isNotEmpty()) {
                        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
                    }
                }
            }
            if (!galleryOnly) item(key = "actions") {
                // —— 操作排（对齐 iOS 头部的 pills）——
                Spacer(Modifier.height(d.cardGap))
                DetailActionBar(actions, moreItems, onAction, onMore)
            }
            if (!galleryOnly && isSystemPeer) item(key = "system_notice") {
                // —— 系统通知会话说明卡（对齐 Web `isSystemPeer` 分支的 system_notice_* 文案）——
                Spacer(Modifier.height(d.cardGap))
                Card {
                    Text(
                        stringResource(R.string.chat_detail_system_notice_prefix) +
                            stringResource(R.string.chat_detail_system_notice_bold) +
                            stringResource(R.string.chat_detail_system_notice_suffix),
                        color = c.textSecondary,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = IMTheme.dimens.space4, vertical = 14.dp),
                    )
                }
            }
            if (showBody) item(key = "info") {
                // —— 信息（对齐 iOS 的 IMDetailSectionInfo：备注名 + 用户名）——
                Spacer(Modifier.height(d.cardGap))
                Card {
                    Row2(
                        stringResource(R.string.chat_detail_remark_name),
                        remark.ifBlank { stringResource(R.string.settings_info_not_set) },
                        onClick = onSetRemark,
                    )
                    if (handle.isNotEmpty()) {
                        Divider()
                        Row2(stringResource(R.string.settings_info_username), handle, onLongClick = onCopyUsername)
                    }
                }
            }
            if (showBody) item(key = "settings") {
                // —— 设置 ——
                Spacer(Modifier.height(d.cardGap))
                Card {
                    // 两项都走 PUT /conversations/{id}/settings，而那是**整体替换**三项，
                    // 所以改一项也要把另外两项原样带回（Host 里做）。
                    SwitchRow(stringResource(R.string.chat_detail_pinned), pinned, onTogglePinned)
                    Divider()
                    // 「免打扰」从开关变成右值行（第二批 NOTIFICATIONS_P1_DESIGN §4.2）：点了弹时长菜单，
                    // 右值 = 关 / 至……/ 永久。
                    Row2(stringResource(R.string.chat_detail_muted), muteValueText, onClick = onOpenMuteSheet)
                    // 「查找聊天记录 / 清空聊天记录」**不在这张卡上**：iOS 把它们放在头部
                    // 操作排的「搜索」与「更多 → 清空聊天记录」里。摆两处等于同一件事有两个入口，
                    // 而其中一个还写着"还没做"。
                }
            }
            if (showBody) item(key = "settings_footer") {
                IMSectionFooter(stringResource(R.string.chat_detail_mute_footer))
            }
            if (showBody) item(key = "tabs") {
                Spacer(Modifier.height(d.cardGap))
                DetailTabBar(tabs, tab) { onTabChange(it) }
            }

            // —— 页签内容（与群资料共用同一段渲染，见 DetailArchive.archiveTab）——
            // 系统通知会话没有可归档的媒体/文件/链接，整段不渲染（对齐 Web `showDetailBody`）。
            if (!isSystemPeer) archiveTab(
                tab = tab,
                convId = conv.convId,
                archive = archive,
                linkMessages = linkMessages,
                loading = loading,
                hasMore = hasMore,
                onLoadMore = onLoadMore,
                onOpenArchive = onOpenArchive,
                onLongPressArchive = onLongPressArchive,
                onOpenLink = onOpenLink,
                host = host,
                useTls = useTls,
                isGroup = false,
                senderNameOf = senderNameOf,
                waveformOf = waveformOf,
            )
            item { Spacer(Modifier.height(24.dp)) }
        }
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
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun Row2(
    label: String, value: String = "", danger: Boolean = false, onClick: (() -> Unit)? = null,
    /** 长按（「用户名」行复制句柄）。带触觉反馈，与 iOS 轻震一致。 */
    onLongClick: (() -> Unit)? = null,
) {
    val c = IMTheme.colors
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    Row(
        Modifier.fillMaxWidth()
            .let {
                when {
                    onLongClick != null -> it.combinedClickable(
                        onClick = { onClick?.invoke() },
                        onLongClick = {
                            haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                            onLongClick()
                        },
                    )
                    onClick != null -> it.clickable(onClick = onClick)
                    else -> it
                }
            }
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
