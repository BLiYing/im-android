package com.libeyond.imandroid.ui.screens

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
import androidx.compose.foundation.layout.systemBarsPadding
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
import androidx.compose.ui.unit.dp
import com.libeyond.imandroid.data.GroupPermissions
import com.libeyond.imandroid.data.GroupSettings
import com.libeyond.imandroid.sdk.api.GroupInfo
import com.libeyond.imandroid.ui.components.IMTopBar
import com.libeyond.imandroid.ui.theme.IMTheme

/** 群管理页上的编辑项。由 Host 决定弹什么框、调什么接口。 */
enum class GroupManageAction { EditName, EditIntro, EditAnnouncement, ToggleMuteAll }

/**
 * 群管理（二级页，仅群主/管理员）。**只负责"改"**：群资料编辑、治理开关组、待审入群申请。
 *
 * 从群详情页拆出来（2026-09-08）。此前两者混在一页，结果是同一个页面在群主和普通成员
 * 眼里长得完全不同——普通成员看到的是"看"的那一半，群主看到的是十几个可点的东西堆在一起。
 * 对齐 iOS `IMGroupManageViewController`（由详情页的「群管理」行 push 出来）与
 * im-web 的 `GroupManagePanel`（详情抽屉的二级视图）。
 *
 * 分组与文案与 im-web 逐字一致（见 [GroupSettings.label]）——同一个开关两端叫法不同，
 * 用户看到的就是两套规则。
 */
@Composable
internal fun GroupManageScreen(
    info: GroupInfo,
    onManage: (GroupManageAction) -> Unit,
    /** 拨一个治理开关。载荷由 [GroupSettings.toggled] 算好，Host 只管发。 */
    onToggleSetting: (GroupSettings.Key) -> Unit,
    onOpenJoinRequests: () -> Unit,
    onBack: () -> Unit,
) {
    val c = IMTheme.colors
    val d = IMTheme.dimens

    Column(Modifier.fillMaxSize().background(c.groupedBackground).systemBarsPadding()) {
        IMTopBar(title = "群管理", subtitle = info.name, onLeft = onBack)

        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(d.cardGap))

            // —— 群资料（G1）——
            // 每一项的显隐都走 GroupPermissions，**不在这里各判各的**：与成员长按菜单、
            // 成员详情页同一份判据，分叉了会出现「按钮亮着但点了报 300204」或反过来。
            val editItems = buildList {
                if (GroupPermissions.canEditInfo(info)) {
                    add(GroupManageAction.EditName to "群名称")
                    add(GroupManageAction.EditIntro to "群简介")
                }
                if (GroupPermissions.canEditAnnouncement(info)) {
                    add(GroupManageAction.EditAnnouncement to "群公告")
                }
            }
            if (editItems.isNotEmpty()) {
                RowCard {
                    editItems.forEachIndexed { i, (action, label) ->
                        if (i > 0) CardDivider()
                        ChevronRow(label) { onManage(action) }
                    }
                }
            }

            // —— 加入与发言 ——
            // 全员禁言与「进群确认」同组：都是"这个群现在准不准进/准不准说话"。
            CardTitle("加入与发言")
            RowCard {
                SwitchRow(
                    label = GroupSettings.label(GroupSettings.Key.JoinApproval),
                    on = GroupSettings.isOn(info, GroupSettings.Key.JoinApproval),
                ) { onToggleSetting(GroupSettings.Key.JoinApproval) }
                if (GroupPermissions.canMuteAll(info)) {
                    CardDivider()
                    // 全员禁言**不是**治理开关组的一员（它走 /mute 接口、值是到期时间不是布尔），
                    // 但在用户眼里就是同一类东西，所以摆在一起。
                    SwitchRow(
                        label = "全员禁言",
                        on = GroupPermissions.isMuteActive(info.muteUntil),
                    ) { onManage(GroupManageAction.ToggleMuteAll) }
                }
            }

            // —— 成员权限 ——
            CardTitle("成员权限")
            RowCard {
                GroupSettings.PERM_GROUP.forEachIndexed { i, k ->
                    if (i > 0) CardDivider()
                    SwitchRow(GroupSettings.label(k), GroupSettings.isOn(info, k)) { onToggleSetting(k) }
                }
            }
            Text(
                "「新成员仅可见入群后历史」开启后，新成员看不到加入前的聊天记录。",
                color = c.textTertiary,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = d.space4, vertical = 8.dp),
            )

            // —— 治理 ——
            CardTitle("治理")
            RowCard {
                ChevronRow(
                    label = "待审入群申请",
                    // pending_count 只对群主/管理员下发（PROTOCOL §11），普通成员恒 0
                    value = if (info.pendingCount > 0) "${info.pendingCount} 待处理" else "无",
                    valueAccent = info.pendingCount > 0,
                    onClick = onOpenJoinRequests,
                )
            }
            Text(
                "黑名单、邀请入群、管理员管理还没做（im-web 已有）。",
                color = c.textTertiary,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = d.space4, vertical = 8.dp),
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** 分组小标题（对齐 im-web 的 `detail-card-title`）。 */
@Composable
internal fun CardTitle(text: String) {
    Text(
        text,
        color = IMTheme.colors.textTertiary,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(start = IMTheme.dimens.space4, top = 16.dp, bottom = 6.dp),
    )
}

/** 一张圆角卡，里面按行排。 */
@Composable
private fun RowCard(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = IMTheme.dimens.space4)
            .clip(RoundedCornerShape(IMTheme.dimens.radiusCard))
            .background(IMTheme.colors.cardBackground),
    ) { content() }
}

@Composable
private fun CardDivider() {
    Box(
        Modifier.fillMaxWidth().padding(start = IMTheme.dimens.space4)
            .height(0.5.dp).background(IMTheme.colors.separator),
    )
}

/** 带 `›` 的可点行。 */
@Composable
private fun ChevronRow(
    label: String,
    value: String = "",
    valueAccent: Boolean = false,
    onClick: () -> Unit,
) {
    val c = IMTheme.colors
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(horizontal = IMTheme.dimens.space4, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = c.textPrimary, style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f))
        if (value.isNotEmpty()) {
            Text(value, color = if (valueAccent) c.accent else c.textTertiary,
                style = MaterialTheme.typography.bodyMedium)
        }
        Text("  ›", color = c.textTertiary)
    }
}

/** 开关行。**开=收紧**（"仅管理员可…"），别把它读成"允许成员…"。 */
@Composable
private fun SwitchRow(label: String, on: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .padding(start = IMTheme.dimens.space4, end = IMTheme.dimens.space3, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = IMTheme.colors.textPrimary,
            style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = on, onCheckedChange = { onToggle() })
    }
}
