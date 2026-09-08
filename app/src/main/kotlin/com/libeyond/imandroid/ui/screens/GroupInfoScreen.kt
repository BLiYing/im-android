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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Lucide
import com.libeyond.imandroid.sdk.api.GroupInfo
import com.libeyond.imandroid.sdk.api.GroupMember
import com.libeyond.imandroid.ui.components.IMAvatar
import com.libeyond.imandroid.data.GroupPermissions
import com.libeyond.imandroid.data.GroupSettings
import com.libeyond.imandroid.ui.components.IMTopBar
import com.libeyond.imandroid.ui.theme.IMTheme

/**
 * 群聊详情页。**只负责"看"**：群头像/名称/人数、公告与简介、成员列表、退群，
 * 外加一个通往「群管理」的入口行。
 *
 * **管理项不摊在这一页上**（2026-09-08 拆分）：此前开关组、群名编辑、待审申请全挤在这里，
 * 一页上十几个可点的东西，其中一半普通成员根本看不见——页面在两种身份下长得完全不同。
 * 对齐 iOS：`IMChatDetailViewController` 的「群管理」行 push 出 `IMGroupManageViewController`；
 * im-web 的 `GroupManagePanel` 同样是详情抽屉的二级视图。
 *
 * **超级群不物化成员表**：`GET /groups/{id}` 对超级群只回我自己，
 * 成员必须走分页接口。故这里的成员区数据由调用方决定从哪来，本组件只管渲染。
 */

@Composable
fun GroupInfoScreen(
    info: GroupInfo,
    members: List<GroupMember>,
    hasMoreMembers: Boolean,
    onLoadMoreMembers: () -> Unit,
    onOpenMember: (GroupMember) -> Unit,
    onLeave: () -> Unit,
    onBack: () -> Unit,
    /** 我的 uid——权限判定要用（不能踢自己、不能给自己设管理员）。 */
    myUid: String,
    /** 长按成员。菜单项由 [GroupPermissions] 决定，Host 负责执行。 */
    onMemberLongPress: (GroupMember) -> Unit,
    /** 进「群管理」二级页（仅群主/管理员看得到这个入口）。 */
    onOpenManage: () -> Unit,
) {
    val c = IMTheme.colors
    val d = IMTheme.dimens

    Column(Modifier.fillMaxSize().background(c.groupedBackground).systemBarsPadding()) {
        IMTopBar(title = "群聊信息", onLeft = onBack)

        LazyColumn(Modifier.fillMaxSize()) {
            item {
                // —— 群头部 ——
                Column(
                    modifier = Modifier.fillMaxWidth().background(c.pageBackground)
                        .padding(vertical = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    IMAvatar(info.name, seed = info.convId, avatarUrl = info.avatarUrl, size = 72.dp)
                    Spacer(Modifier.height(10.dp))
                    Text(
                        info.name.ifBlank { "未命名群聊" },
                        style = MaterialTheme.typography.titleLarge,
                        color = c.textPrimary,
                    )
                    Spacer(Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${info.memberCount} 人", color = c.textSecondary,
                            style = MaterialTheme.typography.bodyMedium)
                        if (info.isSuper) {
                            Text(" · 大群", color = c.textSecondary,
                                style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }

                // —— 大群说明行 ——
                // **恒显**：既没公告也没简介的大群恰恰最需要这句解释。
                // 副标题的「· 大群」只让人察觉，这一行才解释。
                if (info.isSuper) {
                    Spacer(Modifier.height(d.cardGap))
                    Box(
                        Modifier.fillMaxWidth().padding(horizontal = d.space4)
                            .clip(RoundedCornerShape(d.radiusCard)).background(c.cardBackground)
                            .padding(d.space4),
                    ) {
                        Text(
                            "大群 · 已关闭 3 项能力：不显示「正在输入」、不显示已读双勾、" +
                                "不显示成员在线态。这些能力在两万人规模下会产生海量无效推送。",
                            color = c.textSecondary,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }

                if (info.announcement.isNotBlank() || info.intro.isNotBlank()) {
                    Spacer(Modifier.height(d.cardGap))
                    Column(
                        Modifier.fillMaxWidth().padding(horizontal = d.space4)
                            .clip(RoundedCornerShape(d.radiusCard)).background(c.cardBackground)
                            .padding(d.space4),
                    ) {
                        if (info.announcement.isNotBlank()) {
                            Text("群公告", color = c.textTertiary, fontSize = 11.sp)
                            Text(info.announcement, color = c.textPrimary,
                                style = MaterialTheme.typography.bodyLarge)
                        }
                        if (info.intro.isNotBlank()) {
                            if (info.announcement.isNotBlank()) Spacer(Modifier.height(8.dp))
                            Text("群简介", color = c.textTertiary, fontSize = 11.sp)
                            Text(info.intro, color = c.textPrimary,
                                style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }

                // —— 群管理入口（仅群主/管理员）——
                // **管理项不再摊在这一页上**：详情页是"看"的（群资料、公告、成员），
                // 管理页是"改"的。摊在一起时这一页有 12 个可点的东西，
                // 而其中一半是普通成员根本看不到的——对齐 iOS：
                // `IMChatDetailViewController` 的「群管理」行 push 出
                // `IMGroupManageViewController`，im-web 的 `GroupManagePanel` 亦为二级视图。
                if (GroupPermissions.canEditSettings(info)) {
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
                            Text("群管理", color = c.textPrimary,
                                style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                            // 有人在等审批就把数字摆到入口上——否则要点进两层才知道
                            if (info.pendingCount > 0) {
                                Text("${info.pendingCount} 待处理", color = c.accent,
                                    style = MaterialTheme.typography.bodyMedium)
                            }
                            Text("  ›", color = c.textTertiary)
                        }
                    }
                }

                Text(
                    "成员（${info.memberCount}）",
                    color = c.textTertiary,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(start = d.space4, top = 16.dp, bottom = 6.dp),
                )
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
                        Text("加载更多", color = c.accent,
                            modifier = Modifier.clickable { onLoadMoreMembers() })
                    }
                }
            }

            item {
                Spacer(Modifier.height(24.dp))
                Box(
                    Modifier.fillMaxWidth().padding(horizontal = d.space4)
                        .clip(RoundedCornerShape(d.radiusCard)).background(c.cardBackground)
                        .clickable(enabled = GroupPermissions.canLeave(info)) { onLeave() }
                        .padding(vertical = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    // 群主退群会被服务端拒——**在这里就说清楚要先转让**，
                    // 而不是让用户点一下拿个错误码。
                    if (GroupPermissions.canLeave(info)) {
                        Text("退出群聊", color = c.danger)
                    } else {
                        Text("群主需先转让群聊才能退出", color = c.textTertiary)
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun MemberRow(m: GroupMember, onClick: () -> Unit, onLongClick: () -> Unit) {
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
                    RoleBadge(if (m.isOwner) "群主" else "管理员", m.isOwner)
                }
                if (m.muteUntil != 0L) {
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


