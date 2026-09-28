package com.libeyond.imandroid.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Search
import com.libeyond.imandroid.R
import com.libeyond.imandroid.sdk.api.GroupInfo
import com.libeyond.imandroid.sdk.api.GroupMember
import com.libeyond.imandroid.ui.components.IMAvatar
import androidx.compose.ui.geometry.Rect
import com.libeyond.imandroid.data.ArchiveTarget
import com.libeyond.imandroid.data.DetailAction
import com.libeyond.imandroid.data.DetailTab
import com.libeyond.imandroid.data.DetailTabs
import com.libeyond.imandroid.data.GroupMemberSearch
import com.libeyond.imandroid.data.db.MessageEntity
import com.libeyond.imandroid.sdk.api.ConvMediaItem
import com.libeyond.imandroid.data.DetailMoreAction
import com.libeyond.imandroid.data.GroupPermissions
import com.libeyond.imandroid.data.GroupSettings
import com.libeyond.imandroid.ui.components.DetailActionBar
import com.libeyond.imandroid.ui.components.IMTopBar
import com.libeyond.imandroid.ui.theme.IMTheme

/**
 * 群聊详情页。**只负责"看"**：群头像/名称/人数、公告与简介、成员列表，
 * 外加一个通往「群管理」的入口行。退群/解散只在头部「更多」里。
 *
 * **管理项不摊在这一页上**（2026-09-08 拆分）：此前开关组、群名编辑、待审申请全挤在这里，
 * 一页上十几个可点的东西，其中一半普通成员根本看不见——页面在两种身份下长得完全不同。
 * 对齐 iOS：`IMChatDetailViewController` 的「群管理」行 push 出 `IMGroupManageViewController`；
 * im-web 的 `GroupManagePanel` 同样是详情抽屉的二级视图。
 *
 * **超级群不物化成员表**：`GET /groups/{id}` 对超级群只回我自己，
 * 成员必须走分页接口。故这里的成员区数据由调用方决定从哪来，本组件只管渲染。
 *
 * **归档是内联页签，成员也是其中一格**（2026-09-09 改过来的，对齐 iOS
 * `IMChatDetailViewController`：单聊与群聊共用同一套页签，成员只是群聊多出来的那一格）。
 * 此前这里是「一长串成员 + 一行『聊天媒体』跳出去」，与单聊那一侧长得完全不一样。
 */

@Composable
fun GroupInfoScreen(
    info: GroupInfo,
    members: List<GroupMember>,
    hasMoreMembers: Boolean,
    onLoadMoreMembers: () -> Unit,
    /** 大群「搜索成员」入口（`GroupMemberSearch.shouldOffer` 门控，超过阈值才显示）。 */
    onOpenMemberSearch: () -> Unit,
    onOpenMember: (GroupMember) -> Unit,
    onBack: () -> Unit,
    /** 我的 uid——权限判定要用（不能踢自己、不能给自己设管理员）。 */
    myUid: String,
    /** 长按成员。菜单项由 [GroupPermissions] 决定，Host 负责执行。 */
    onMemberLongPress: (GroupMember) -> Unit,
    /** 进「群管理」二级页（仅群主/管理员看得到这个入口）。 */
    onOpenManage: () -> Unit,
    // —— 设置区（对齐 iOS `IMChatDetailViewController` 的 Settings 分区，2026-09-22 补）——
    /** 置顶聊天 / 消息免打扰：与单聊那侧同一套会话设置接口，全体成员可自己拨。 */
    pinned: Boolean,
    muted: Boolean,
    onTogglePinned: (Boolean) -> Unit,
    onToggleMuted: (Boolean) -> Unit,
    /** 我在本群的昵称（仅本人可见，覆盖全局昵称）。 */
    onEditMyNickname: () -> Unit,
    /** 群备注（G1，仅本人可见，与单聊「备注名」同一套接口、多端同步）。 */
    remark: String,
    onEditRemark: () -> Unit,
    /** 点开「群公告」/「群简介」看全文（各自独立、可展开，对齐 iOS 两个独立行各自 push 只读页）。 */
    onOpenNotice: (title: String, content: String) -> Unit,
    /**
     * 群二维码 / 群邀请链接。行本身按 [GroupPermissions.canInvite] 门控——与「邀请好友入群」
     * 卡片同一份判据（`perm_invite=1` 时对非管理员隐藏，对齐 iOS `inviteEntriesVisible`：
     * 无邀请权者不给死胡同入口，点了也只会被服务端拒）。
     */
    onOpenGroupQR: () -> Unit,
    onOpenGroupInviteLink: () -> Unit,
    // —— 内联页签（成员 / 媒体 / 文件 / 语音 / 链接）——
    tab: DetailTab,
    onTabChange: (DetailTab) -> Unit,
    /** 当前页签的归档数据（成员/链接页签走各自的来源，这里为空）。 */
    archive: List<ConvMediaItem>,
    /** 链接页签：本地已加载的消息，由调用方扫出 URL。 */
    linkMessages: List<Pair<MessageEntity, String>>?,
    archiveLoading: Boolean,
    archiveHasMore: Boolean,
    onLoadMoreArchive: () -> Unit,
    onOpenArchive: (ConvMediaItem) -> Unit,
    onLongPressArchive: (ArchiveTarget, Rect) -> Unit,
    onOpenLink: (String) -> Unit,
    host: String,
    useTls: Boolean,
    /** 见 [com.libeyond.imandroid.ui.screens.archiveTab] 同名参数：语音行要显发送者名。 */
    senderNameOf: (String) -> String = { "" },
    /** 见 [com.libeyond.imandroid.ui.screens.archiveTab] 同名参数：波形由本地消息表兜底。 */
    waveformOf: (Long) -> String? = { null },
    /** 邀请好友入群。**入口按 [GroupPermissions.canInvite] 显隐**——
     *  开了「仅管理员可邀请」还给普通成员留入口，点进去只会拿到 300212。 */
    onInvite: () -> Unit,
    /** 头部操作排（搜索 / 更多）。由 [com.libeyond.imandroid.data.DetailActions] 算可见项。 */
    actions: List<DetailAction>,
    moreItems: List<DetailMoreAction>,
    onAction: (DetailAction) -> Unit,
    onMore: (DetailMoreAction) -> Unit,
    /** 只当**会话媒体库**用：去掉头部与页签条，整页就是媒体宫格。理由见 `ChatDetailScreen` 同名参数。 */
    galleryOnly: Boolean = false,
) {
    val c = IMTheme.colors
    com.libeyond.imandroid.ui.voice.PauseVoiceOnLeave() // 离开本页暂停语音（保留位点）
    val d = IMTheme.dimens

    Column(Modifier.fillMaxSize().background(c.groupedBackground).systemBarsPadding()) {
        // 媒体库标题逐字对齐 iOS（理由见 `ChatDetailScreen` 同一行）
        IMTopBar(title = if (galleryOnly) GALLERY_TITLE else stringResource(R.string.group_info_title), onLeft = onBack)

        // 头部拆成几个独立 item，别再合回一个大 item（理由见 `ChatDetailScreen` 同一处）
        LazyColumn(Modifier.fillMaxSize()) {
            if (!galleryOnly) item(key = "header") {
                // —— 群头部 ——
                // 左右留页边距 + 群名单行居中、放不下尾部省略（对齐 iOS `makeNameLabel` 的 center + 单行）：
                // 此前只有上下 padding、Text 也没居中，长群名折成两行后左对齐、贴着屏幕两边（2026-09-27 真机发现）
                Column(
                    modifier = Modifier.fillMaxWidth().background(c.pageBackground)
                        .padding(vertical = 20.dp, horizontal = IMTheme.dimens.space4),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    IMAvatar(info.name, seed = info.convId, avatarUrl = info.avatarUrl, size = 72.dp)
                    Spacer(Modifier.height(10.dp))
                    Text(
                        info.name.ifBlank { stringResource(R.string.group_text_unnamed) },
                        style = MaterialTheme.typography.titleLarge,
                        color = c.textPrimary,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            pluralStringResource(R.plurals.common_people_count, info.memberCount, info.memberCount),
                            color = c.textSecondary,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (info.isSuper) {
                            Text(" · " + stringResource(R.string.group_text_super), color = c.textSecondary,
                                style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
            if (!galleryOnly) item(key = "actions") {
                // —— 操作排（对齐 iOS 头部的 pills：群聊是「搜索 / 更多」）——
                Spacer(Modifier.height(d.cardGap))
                DetailActionBar(actions, moreItems, onAction, onMore)
            }

            // —— 大群说明行 ——
            // **恒显**：既没公告也没简介的大群恰恰最需要这句解释。
            // 副标题的「· 大群」只让人察觉，这一行才解释。
            if (!galleryOnly && info.isSuper) item(key = "super-note") {
                Spacer(Modifier.height(d.cardGap))
                Box(
                    Modifier.fillMaxWidth().padding(horizontal = d.space4)
                        .clip(RoundedCornerShape(d.radiusCard)).background(c.cardBackground)
                        .padding(d.space4),
                ) {
                    Text(
                        stringResource(R.string.group_info_super_note),
                        color = c.textSecondary,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            // **两个独立行**，不再合成一张卡（2026-09-22 对齐 iOS：群公告/群简介各自
            // 独立展示、各自可点开看全文）。公告最长 500 字，卡片里只露 3 行摘要，
            // 点开走 [onOpenNotice] 弹只读全文（`GroupTextViewDialog`，对齐 iOS
            // `IMGroupTextViewController` 的只读全屏页，本端用弹窗而非整页）。
            if (!galleryOnly && info.announcement.isNotBlank()) item(key = "announcement") {
                Spacer(Modifier.height(d.cardGap))
                val label = stringResource(R.string.group_text_announcement)
                NoticeCard(label, info.announcement) { onOpenNotice(label, info.announcement) }
            }
            if (!galleryOnly && info.intro.isNotBlank()) item(key = "intro") {
                Spacer(Modifier.height(d.cardGap))
                val label = stringResource(R.string.group_text_intro)
                NoticeCard(label, info.intro) { onOpenNotice(label, info.intro) }
            }

            // —— 设置区（对齐 iOS Settings 分区）——
            if (!galleryOnly) item(key = "settings") {
                Spacer(Modifier.height(d.cardGap))
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = d.space4)
                        .clip(RoundedCornerShape(d.radiusCard)).background(c.cardBackground),
                ) {
                    val notSet = stringResource(R.string.settings_info_not_set)
                    SettingsSwitchRow(stringResource(R.string.chat_detail_pinned), pinned, onTogglePinned)
                    SettingsDivider()
                    SettingsSwitchRow(stringResource(R.string.chat_detail_muted), muted, onToggleMuted)
                    SettingsDivider()
                    SettingsChevronRow(
                        stringResource(R.string.chat_detail_my_group_nickname),
                        info.myNickname.ifBlank { notSet },
                        onEditMyNickname,
                    )
                    SettingsDivider()
                    SettingsChevronRow(stringResource(R.string.chat_detail_group_remark), remark.ifBlank { notSet }, onEditRemark)
                    if (GroupPermissions.canInvite(info)) {
                        SettingsDivider()
                        SettingsChevronRow(stringResource(R.string.qr_card_group_title_code), "", onOpenGroupQR)
                        SettingsDivider()
                        SettingsChevronRow(stringResource(R.string.qr_card_group_title_link), "", onOpenGroupInviteLink)
                    }
                }
            }

            // 「聊天媒体」那一行没有了——归档已经是下面的内联页签（对齐 iOS）。
            // 同一件事留两个入口，其中一个还要跳出去，是本端此前与 iOS 差得最远的一处。
            if (!galleryOnly && GroupPermissions.canInvite(info)) item(key = "invite") {
                Spacer(Modifier.height(d.cardGap))
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = d.space4)
                        .clip(RoundedCornerShape(d.radiusCard)).background(c.cardBackground),
                ) {
                    Row(
                        Modifier.fillMaxWidth().clickable { onInvite() }
                            .padding(horizontal = d.space4, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(stringResource(R.string.group_info_invite_friends), color = c.textPrimary,
                            style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        Text("›", color = c.textTertiary)
                    }
                }
            }

            if (!galleryOnly && GroupPermissions.canEditSettings(info)) item(key = "manage") {
                // —— 群管理入口（仅群主/管理员）——
                // **管理项不再摊在这一页上**：详情页是"看"的（群资料、公告、成员），
                // 管理页是"改"的。摊在一起时这一页有 12 个可点的东西，
                // 而其中一半是普通成员根本看不到的——对齐 iOS：
                // `IMChatDetailViewController` 的「群管理」行 push 出
                // `IMGroupManageViewController`，im-web 的 `GroupManagePanel` 亦为二级视图。
                Spacer(Modifier.height(d.cardGap))
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = d.space4)
                        .clip(RoundedCornerShape(d.radiusCard)).background(c.cardBackground),
                ) {
                    Row(
                        Modifier.fillMaxWidth().clickable { onOpenManage() }
                            .padding(horizontal = d.space4, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(stringResource(R.string.group_manage_title), color = c.textPrimary,
                            style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        // 有人在等审批就把数字摆到入口上——否则要点进两层才知道
                        if (info.pendingCount > 0) {
                            Text(
                                stringResource(R.string.chat_detail_manage_pending_badge, info.pendingCount),
                                color = c.accent,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        Text("  ›", color = c.textTertiary)
                    }
                }
            }
            if (!galleryOnly) item(key = "tabs") {
                Spacer(Modifier.height(d.cardGap))
                DetailTabBar(DetailTabs.visible(isGroup = true), tab) { onTabChange(it) }
            }

            // —— 页签内容 ——
            when (tab) {
                DetailTab.Members -> {
                    if (GroupMemberSearch.shouldOffer(info.memberCount, members.size)) {
                        item(key = "member_search_entry") { MemberSearchEntryRow(onClick = onOpenMemberSearch) }
                    }
                    items(members, key = { it.userId }) { m ->
                        MemberRow(m, onClick = { onOpenMember(m) }, onLongClick = { onMemberLongPress(m) })
                    }
                    if (hasMoreMembers) {
                        item {
                            // 滚到底自动续拉；手点入口保留作失败重试
                            // （2 万人群要点 400 次「加载更多」是不可接受的）
                            androidx.compose.runtime.LaunchedEffect(members.size) { onLoadMoreMembers() }
                            Box(Modifier.fillMaxWidth().padding(14.dp), contentAlignment = Alignment.Center) {
                                Text(stringResource(R.string.group_member_load_more), color = c.accent,
                                    modifier = Modifier.clickable { onLoadMoreMembers() })
                            }
                        }
                    }
                }
                else -> archiveTab(
                    tab = tab,
                    convId = info.convId,
                    archive = archive,
                    linkMessages = linkMessages,
                    loading = archiveLoading,
                    hasMore = archiveHasMore,
                    onLoadMore = onLoadMoreArchive,
                    onOpenArchive = onOpenArchive,
                    onLongPressArchive = onLongPressArchive,
                    onOpenLink = onOpenLink,
                    host = host,
                    useTls = useTls,
                    isGroup = true,
                    senderNameOf = senderNameOf,
                    waveformOf = waveformOf,
                )
            }

            // 底部不再放「退出群聊」卡：头部「更多」里已有退出/解散（带二次确认，同 iOS），
            // 两个入口并存只会让人以为是两件不同的事
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
internal fun MemberRow(m: GroupMember, onClick: () -> Unit, onLongClick: () -> Unit) {
    val c = IMTheme.colors
    val d = IMTheme.dimens
    Row(
        modifier = Modifier.fillMaxWidth().background(c.pageBackground).combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = d.space4, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IMAvatar(m.displayName, seed = m.userId, avatarUrl = m.avatarUrl, size = 40.dp)
        Spacer(Modifier.width(d.space3))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(m.displayName, color = c.textPrimary, style = MaterialTheme.typography.titleMedium)
                if (m.isManager) {
                    Spacer(Modifier.width(6.dp))
                    RoleBadge(
                        if (m.isOwner) stringResource(R.string.group_role_owner) else stringResource(R.string.group_role_admin),
                        m.isOwner,
                    )
                }
                // 判据须与 GroupPermissions.isMuteActive 同口径：0=没禁/到期的历史时间戳不算禁言中
                // （此前用 != 0L 判定，过期的禁言时间戳会被误显示成"仍在禁言"）。
                if (GroupPermissions.isMuteActive(m.muteUntil)) {
                    Spacer(Modifier.width(4.dp))
                    Text("🔇", fontSize = 11.sp)
                }
            }
            if (m.handle.isNotEmpty()) {
                Text(m.handle, color = c.textSecondary, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
    Box(Modifier.fillMaxWidth().height(0.5.dp).padding(start = 68.dp).background(c.separator))
}

/** 「搜索成员」入口行——只在大群（`GroupMemberSearch.shouldOffer`）显示，摆在成员列表最上面。 */
@Composable
private fun MemberSearchEntryRow(onClick: () -> Unit) {
    val c = IMTheme.colors
    val d = IMTheme.dimens
    Row(
        modifier = Modifier.fillMaxWidth().background(c.pageBackground).clickable(onClick = onClick)
            .padding(horizontal = d.space4, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            imageVector = Lucide.Search,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(c.textSecondary),
        )
        Spacer(Modifier.width(d.space3))
        Text(stringResource(R.string.group_member_search), color = c.textSecondary, style = MaterialTheme.typography.bodyLarge)
    }
    Box(Modifier.fillMaxWidth().height(0.5.dp).padding(start = 68.dp).background(c.separator))
}

/** 群公告 / 群简介卡片：摘要最多 3 行，点开看全文（见 `onOpenNotice`）。 */
@Composable
private fun NoticeCard(label: String, content: String, onClick: () -> Unit) {
    val c = IMTheme.colors
    val d = IMTheme.dimens
    Column(
        Modifier.fillMaxWidth().padding(horizontal = d.space4)
            .clip(RoundedCornerShape(d.radiusCard)).background(c.cardBackground)
            .clickable(onClick = onClick).padding(d.space4),
    ) {
        Text(label, color = c.textTertiary, fontSize = 11.sp)
        Text(
            content, color = c.textPrimary, style = MaterialTheme.typography.bodyLarge,
            maxLines = 3, overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 设置区的开关行（置顶聊天 / 消息免打扰）。 */
@Composable
private fun SettingsSwitchRow(label: String, on: Boolean, onToggle: (Boolean) -> Unit) {
    val c = IMTheme.colors
    Row(
        Modifier.fillMaxWidth()
            .padding(start = IMTheme.dimens.space4, end = IMTheme.dimens.space3, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = c.textPrimary, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = on, onCheckedChange = onToggle)
    }
}

/** 设置区的可点行（我在本群的昵称 / 群备注），右侧带当前值预览。 */
@Composable
private fun SettingsChevronRow(label: String, value: String, onClick: () -> Unit) {
    val c = IMTheme.colors
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(horizontal = IMTheme.dimens.space4, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = c.textPrimary, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(value, color = c.textSecondary, style = MaterialTheme.typography.bodyMedium)
        Text("  ›", color = c.textTertiary)
    }
}

@Composable
private fun SettingsDivider() {
    Box(
        Modifier.fillMaxWidth().padding(start = IMTheme.dimens.space4)
            .height(0.5.dp).background(IMTheme.colors.separator),
    )
}

/** 群主/管理员胶囊徽标——群主主色实底、管理员次要灰（与 iOS/Web 同一表意）。 */
@Composable
private fun RoleBadge(text: String, owner: Boolean) {
    val c = IMTheme.colors
    Box(
        modifier = Modifier.clip(RoundedCornerShape(4.dp))
            .background(if (owner) c.accent else c.neutralControl)
            .padding(horizontal = 4.dp, vertical = 1.dp),
    ) {
        Text(
            text = text,
            color = if (owner) c.onAccent else c.textSecondary,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}


