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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.AlignLeft
import com.composables.icons.lucide.Ban
import com.composables.icons.lucide.Camera
import com.composables.icons.lucide.Crown
import com.composables.icons.lucide.History
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Megaphone
import com.composables.icons.lucide.MicOff
import com.composables.icons.lucide.Pin
import com.composables.icons.lucide.ShieldCheck
import com.composables.icons.lucide.SquarePen
import com.composables.icons.lucide.Tag
import com.composables.icons.lucide.UserCheck
import com.composables.icons.lucide.UserPlus
import com.libeyond.imandroid.data.GroupPermissions
import com.libeyond.imandroid.data.GroupSettings
import com.libeyond.imandroid.sdk.api.GroupInfo
import com.libeyond.imandroid.sdk.api.GroupMember
import com.libeyond.imandroid.ui.components.IMAvatar
import com.libeyond.imandroid.ui.components.IMTopBar
import com.libeyond.imandroid.ui.theme.IMTheme

/** 群管理页上的编辑项。由 Host 决定弹什么框、调什么接口。 */
enum class GroupManageAction { EditName, EditIntro, EditAnnouncement, ToggleMuteAll }

/**
 * 群管理（二级页，仅群主/管理员）。
 *
 * **分区、顺序、文案、footer 逐条对齐 iOS `IMGroupManageViewController`**
 * （`IMManageSection`：资料 / 加入与发言 / 成员权限 / 治理 / 管理员 / 群主）。
 * 差异只有图标——SF Symbol 在 Android 上不存在，用 Lucide 里语义最近的一枚，
 * 要对齐的是「每行都有一个能一眼认出的图标」，不是同一张图（见 `docs/UI_PARITY_IOS.md` §3）。
 *
 * 从群详情页拆出来（2026-09-08）：详情页负责"看"，本页负责"改"。
 */
@Composable
internal fun GroupManageScreen(
    info: GroupInfo,
    /** 黑名单人数；`null` = 还没拉到（不显数字，别显 0 骗人）。 */
    banCount: Int?,
    members: List<GroupMember>,
    onManage: (GroupManageAction) -> Unit,
    /** 拨一个治理开关。载荷由 [GroupSettings.toggled] 算好，Host 只管发。 */
    onToggleSetting: (GroupSettings.Key) -> Unit,
    onOpenJoinRequests: () -> Unit,
    onOpenBans: () -> Unit,
    onOpenAdmins: () -> Unit,
    onTransferOwner: () -> Unit,
    /** 换群头像。`null` = 我没有改群资料的权限（不显相机圈，也不显那行提示）。 */
    onPickAvatar: (() -> Unit)?,
    onBack: () -> Unit,
) {
    val c = IMTheme.colors
    val d = IMTheme.dimens

    Column(Modifier.fillMaxSize().background(c.groupedBackground).systemBarsPadding()) {
        IMTopBar(title = "群管理", subtitle = info.name, onLeft = onBack)

        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            // —— 群头像头部（对齐 iOS 的 IMGroupAvatarHeader：头像 + 相机圈 + 「设置新头像」）——
            // 头像**可点即可换**（同 iOS）。本端 2026-09-08 之前这里只是一张不能点的图，
            // 于是「群名称/群简介/群公告」都能改、唯独头像没有任何入口。
            Column(
                Modifier.fillMaxWidth().background(c.pageBackground).padding(vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier.let { if (onPickAvatar != null) it.clickable(onClick = onPickAvatar) else it },
                    contentAlignment = Alignment.Center,
                ) {
                    IMAvatar(info.name, seed = info.convId, avatarUrl = info.avatarUrl, size = 80.dp)
                    if (onPickAvatar != null) {
                        // 相机徽标压在右下角：没有它，一张圆头像看不出来是可点的
                        Box(
                            Modifier.align(Alignment.BottomEnd).size(26.dp)
                                .clip(androidx.compose.foundation.shape.CircleShape)
                                .background(c.accent),
                            contentAlignment = Alignment.Center,
                        ) {
                            Image(
                                Lucide.Camera, "更换群头像", Modifier.size(14.dp),
                                colorFilter = ColorFilter.tint(c.onAccent),
                            )
                        }
                    }
                }
                if (onPickAvatar != null) {
                    Spacer(Modifier.height(8.dp))
                    Text("设置新头像", color = c.accent,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.clickable(onClick = onPickAvatar))
                }
            }

            // —— 资料（无分区标题，同 iOS）——
            // 每一项的显隐都走 GroupPermissions，**不在这里各判各的**：与成员长按菜单、
            // 成员详情页同一份判据，分叉了会出现「按钮亮着但点了报 300204」或反过来。
            val editItems = buildList {
                if (GroupPermissions.canEditInfo(info)) {
                    add(Triple(GroupManageAction.EditName, "群名称", Lucide.Tag))
                    add(Triple(GroupManageAction.EditIntro, "群简介", Lucide.AlignLeft))
                }
                if (GroupPermissions.canEditAnnouncement(info)) {
                    add(Triple(GroupManageAction.EditAnnouncement, "群公告", Lucide.Megaphone))
                }
            }
            if (editItems.isNotEmpty()) {
                Spacer(Modifier.height(d.cardGap))
                Card {
                    editItems.forEachIndexed { i, (action, label, icon) ->
                        if (i > 0) CardDivider()
                        ChevronRow(label, icon) { onManage(action) }
                    }
                }
                Footnote("简介与公告展示给全体成员；公告发布后会通知所有人。")
            }

            // —— 加入与发言 ——
            CardTitle("加入与发言")
            Card {
                SwitchRow(
                    GroupSettings.label(GroupSettings.Key.JoinApproval),
                    Lucide.ShieldCheck,
                    GroupSettings.isOn(info, GroupSettings.Key.JoinApproval),
                ) { onToggleSetting(GroupSettings.Key.JoinApproval) }
                if (GroupPermissions.canMuteAll(info)) {
                    CardDivider()
                    // 全员禁言**不是**治理开关组的一员（走 /mute 接口、值是到期时间不是布尔），
                    // 但在用户眼里是同一类东西，iOS 也把它并在这一节。
                    SwitchRow("全员禁言", Lucide.MicOff, GroupPermissions.isMuteActive(info.muteUntil)) {
                        onManage(GroupManageAction.ToggleMuteAll)
                    }
                }
            }
            Footnote("进群确认：凭二维码加入需管理员审批。全员禁言：仅群主 / 管理员可发言。")

            // —— 成员权限 ——
            CardTitle("成员权限")
            Card {
                PERM_ICONS.entries.forEachIndexed { i, (k, icon) ->
                    if (i > 0) CardDivider()
                    SwitchRow(GroupSettings.label(k), icon, GroupSettings.isOn(info, k)) { onToggleSetting(k) }
                }
            }
            Footnote("「新成员仅可见入群后历史」开启后，新成员看不到加入前的聊天记录。")

            // —— 治理 ——
            CardTitle("治理")
            Card {
                ChevronRow(
                    "待审入群申请",
                    Lucide.UserCheck,
                    // pending_count 只对群主/管理员下发（PROTOCOL §11），普通成员恒 0
                    value = if (info.pendingCount > 0) "${info.pendingCount} 待处理" else "无",
                    valueAccent = info.pendingCount > 0,
                    onClick = onOpenJoinRequests,
                )
                CardDivider()
                ChevronRow("黑名单", Lucide.Ban, value = banCount?.let { "$it 人" }.orEmpty(), onClick = onOpenBans)
            }

            // —— 管理员 ——
            CardTitle("管理员")
            Card {
                ChevronRow("管理员", Lucide.ShieldCheck, value = adminCountText(info, members), onClick = onOpenAdmins)
            }
            Footnote("管理员可审批入群、禁言与移出普通成员，但不能设置管理员或转让群组。")

            // —— 群主：只有群主看得到 ——
            // 单开一张卡：不可逆的一次性操作不该和会反复进出的「治理」混在一张卡里（手指一滑就点到旁边）。
            if (info.myRole == GroupMember.ROLE_OWNER) {
                CardTitle("群主")
                Card {
                    // 本页唯一的红色行。有意为之，不是漏改主题色（iOS 同）。
                    ChevronRow("转让群组", Lucide.Crown, danger = true, onClick = onTransferOwner)
                }
                Footnote("转让后你将立即变为普通成员，且不可撤销。群主不能直接退群，须先转让。")
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** 成员权限四行的图标。顺序即 [GroupSettings.PERM_GROUP]，两者要一起改。 */
private val PERM_ICONS: Map<GroupSettings.Key, ImageVector> = linkedMapOf(
    GroupSettings.Key.PermInvite to Lucide.UserPlus,
    GroupSettings.Key.PermEditInfo to Lucide.SquarePen,
    GroupSettings.Key.PermPin to Lucide.Pin,
    GroupSettings.Key.HistoryVisible to Lucide.History,
)

/**
 * 「N 位管理员」。
 *
 * **超级群里成员是分页拉的**，手上这批不代表全部，此时说"3 位"是在骗人——
 * 拉不全就只说「查看」。iOS 那边 `IMGroupAdminLogic adminCountTextForMembers:`
 * 拿到的是全量成员，没有这个问题。
 */
private fun adminCountText(info: GroupInfo, members: List<GroupMember>): String {
    if (info.isSuper) return ""
    val n = members.count { it.role == GroupMember.ROLE_ADMIN }
    return if (n > 0) "$n 位" else "未设置"
}

/** 分组小标题（对齐 im-web 的 `detail-card-title` 与 iOS 的 section header）。 */
@Composable
internal fun CardTitle(text: String) {
    Text(
        text,
        color = IMTheme.colors.textTertiary,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(start = IMTheme.dimens.space4, top = 16.dp, bottom = 6.dp),
    )
}

/** 分区脚注（对齐 iOS 的 `titleForFooterInSection`）。 */
@Composable
internal fun Footnote(text: String) {
    Text(
        text,
        color = IMTheme.colors.textTertiary,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(horizontal = IMTheme.dimens.space4, vertical = 8.dp),
    )
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
private fun CardDivider() {
    Box(
        Modifier.fillMaxWidth().padding(start = 52.dp)   // 与图标右缘对齐（同 iOS separatorInset）
            .height(0.5.dp).background(IMTheme.colors.separator),
    )
}

/** 行图标。**Lucide 近义图标**，不是 SF Symbol（见 docs/UI_PARITY_IOS.md §3）。 */
@Composable
private fun RowIcon(icon: ImageVector, tint: Color) {
    Image(icon, null, Modifier.size(20.dp), colorFilter = ColorFilter.tint(tint))
}

@Composable
private fun ChevronRow(
    label: String,
    icon: ImageVector,
    value: String = "",
    valueAccent: Boolean = false,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    val c = IMTheme.colors
    val fg = if (danger) c.danger else c.textPrimary
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(horizontal = IMTheme.dimens.space4, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RowIcon(icon, if (danger) c.danger else c.accent)
        Spacer(Modifier.width(12.dp))
        Text(label, color = fg, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        if (value.isNotEmpty()) {
            Text(value, color = if (valueAccent) c.accent else c.textSecondary,
                style = MaterialTheme.typography.bodyMedium)
        }
        Text("  ›", color = c.textTertiary)
    }
}

/** 开关行。**开=收紧**（"仅管理员可…"），别把它读成"允许成员…"。 */
@Composable
private fun SwitchRow(label: String, icon: ImageVector, on: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .padding(start = IMTheme.dimens.space4, end = IMTheme.dimens.space3, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RowIcon(icon, IMTheme.colors.accent)
        Spacer(Modifier.width(12.dp))
        Text(label, color = IMTheme.colors.textPrimary,
            style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = on, onCheckedChange = { onToggle() })
    }
}
