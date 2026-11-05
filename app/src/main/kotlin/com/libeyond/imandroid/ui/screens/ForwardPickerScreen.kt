package com.libeyond.imandroid.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.User
import com.composables.icons.lucide.Users
import com.libeyond.imandroid.R
import com.libeyond.imandroid.data.Forward
import com.libeyond.imandroid.data.SharePick
import com.libeyond.imandroid.data.SharePickSelection
import com.libeyond.imandroid.data.SharePickSources
import com.libeyond.imandroid.i18n.Str
import com.libeyond.imandroid.data.db.ConversationEntity
import com.libeyond.imandroid.ui.components.IMCardSheet
import com.libeyond.imandroid.ui.components.IMConfirmDialog
import com.libeyond.imandroid.ui.components.IMSearchField
import com.libeyond.imandroid.ui.components.IMSectionFooter
import com.libeyond.imandroid.ui.components.IMTopBar
import com.libeyond.imandroid.ui.theme.IMTheme

/** 选择页内的「导航栈」：主页 / 我的好友 / 我的群聊（同一张卡片里切，返回回主页、不清已选）。 */
private enum class SharePage { Main, Friends, Groups }

/**
 * 转发 / 分享目标选择页（M4-3）。**逐项对齐 iOS `IMForwardPickerViewController`**（2026-09-16 用户报：
 * 本端是一整屏平铺、不是 iOS 那种卡片，且不能搜索）：
 *
 * - **卡片式弹层**（[IMCardSheet]，iOS pageSheet）：可下拉关闭，标题「转发到」居中；
 * - **默认单选**：点一行弹「发送给「X」？」确认后发送；右上「多选」进多选，行首出勾选圈，
 *   上限 [Forward.MAX_TARGETS]，超限吐司而不是静默丢弃（未勾的行**不置灰**）；
 * - **搜索框**：按显示名 + 单聊对端 uid 过滤，只影响看得见哪些行、已选不丢（[Forward.pickable]）；
 * - 剔除系统通知单聊。
 *
 * **好友 / 群聊入口（2026-11-05，IMServer `docs/design/sketches/SHARE_PICKER_UX_SKETCH.html`）**：
 * 传了 [sources] 就在「最近聊天」上方出「我的好友」「我的群聊」两行（点了进子页 [SharePickSubPage]）；
 * 三处列表共用一份 [SharePickSelection]（按 convId，合计最多 9 个）；多选态「已选 N/9」挂在标题下，
 * 「发送(N)」挪到常驻底栏 [SharePickBottomBar]，点了先弹确认面板 [SharePickConfirmSheet]（带 [preview]）；
 * 主页搜索同时搜三处、分组显示、按 convId 去重。交回调用方的仍是 `List<ConversationEntity>`——
 * 没聊过的好友 / 会话被删的群是本地拼的同形目标（[SharePick]），调用方一行不用改。
 *
 * [onConfirm] / [onCancel] 都在卡片**滑出屏幕之后**才回调。
 *
 * **NOTIFICATIONS_P1_DESIGN §2「添加例外」复用这同一个组件**（不是另写一份选择页）：
 * 那一路要求纯单选（没有右上「多选」入口）、点一行不经二次确认直接生效、标题/脚注/空态文案
 * 也不一样，且**不传 [sources]**（没有入口行、没有底栏）——不传就是原来那一套行为，逐字不变。
 */
@Composable
fun ForwardPickerScreen(
    conversations: List<ConversationEntity>,
    onConfirm: (List<ConversationEntity>) -> Unit,
    onCancel: () -> Unit,
    onToast: (String) -> Unit,
    /** 数据过滤——默认转发那一套（[Forward.pickable]），「添加例外」传 [Forward.exceptionPickable] 的绑定版。 */
    filter: (List<ConversationEntity>, String) -> List<ConversationEntity> = Forward::pickable,
    title: String = stringResource(R.string.forward_picker_destination_title),
    /** 列表下方的说明文字，空串不画（转发不需要，「添加例外」传 `notif_exceptions_pick_footer_*`）。 */
    footer: String = "",
    /** 数据源本就是空的（不是搜索没搜到）时显示的文案，空串不画——转发场景保留原状（不说话）。 */
    emptyText: String = "",
    /** 允许切到多选（右上「多选」按钮）。「添加例外」传 false：纯单选，不出这颗按钮。 */
    allowMulti: Boolean = true,
    /** 单选态点一行是否先弹「发送给「X」？」确认。「添加例外」传 false：点了立即生效并关闭选择页。 */
    confirmSingleTap: Boolean = true,
    /** 好友 / 群聊两份额外数据源（见类注释）。`null` = 不出入口行与底栏（「添加例外」）。 */
    sources: SharePickSources? = null,
    /** 多选确认面板里的内容预览（消息摘要 / 「[名片] X」/ 群邀请文字），空串不画。 */
    preview: String = "",
    /** 最近聊天 / 好友 / 群聊三处都不列的会话（分享 A 的名片时不列 A 自己；发群邀请时不列这个群）。 */
    exclude: Set<String> = emptySet(),
) {
    val c = IMTheme.colors
    val d = IMTheme.dimens
    val focus = LocalFocusManager.current
    val shareMode = sources != null
    var query by remember { mutableStateOf("") }
    var multi by remember { mutableStateOf(false) }
    // 勾选顺序 = 已选栏顺序 = 发送顺序（iOS `_selected` 是有序数组）
    var selection by remember { mutableStateOf(SharePickSelection()) }
    var confirming by remember { mutableStateOf<ConversationEntity?>(null) }
    var page by remember { mutableStateOf(SharePage.Main) }
    var reviewing by remember { mutableStateOf(false) }
    val rows = remember(conversations, query, filter, exclude) {
        filter(conversations, query).let { list -> if (exclude.isEmpty()) list else list.filter { it.convId !in exclude } }
    }
    val sections = remember(rows, sources, query, exclude, shareMode) {
        if (shareMode && query.isNotBlank()) SharePick.searchSections(rows, sources ?: SharePickSources(), query, exclude) else null
    }
    val targets = remember(selection, conversations) { selection.targets(conversations) }
    val subtitle = if (multi && shareMode) stringResource(R.string.share_picker_selected_of_max, selection.size, Forward.MAX_TARGETS) else ""

    IMCardSheet(onDismissed = onCancel) { sheet ->
        val onTap: (ConversationEntity) -> Unit = { conv ->
            focus.clearFocus()
            if (!multi) {
                if (confirmSingleTap) confirming = conv else sheet.dismiss { onConfirm(listOf(conv)) }
            } else {
                val next = selection.toggle(conv)
                if (next == null) {
                    onToast(Str.p(R.plurals.forward_picker_max_selection, Forward.MAX_TARGETS, Forward.MAX_TARGETS))
                } else {
                    selection = next
                }
            }
        }
        // 子页的返回只回主页（比卡片自己的返回键后注册，优先）
        BackHandler(enabled = page != SharePage.Main) { page = SharePage.Main }

        when (page) {
            SharePage.Friends, SharePage.Groups -> {
                val friendsPage = page == SharePage.Friends
                SharePickSubPage(
                    title = stringResource(if (friendsPage) R.string.share_picker_my_friends else R.string.share_picker_my_groups),
                    subtitle = subtitle,
                    searchPlaceholder = stringResource(if (friendsPage) R.string.share_picker_search_friends else R.string.share_picker_search_groups),
                    emptyText = stringResource(if (friendsPage) R.string.share_picker_no_friends else R.string.share_picker_no_groups),
                    entries = if (friendsPage) sources?.friends else sources?.groups,
                    failed = if (friendsPage) sources?.friendsFailed == true else sources?.groupsFailed == true,
                    onRetry = { if (friendsPage) sources?.retryFriends?.invoke() else sources?.retryGroups?.invoke() },
                    exclude = exclude,
                    alphabetical = friendsPage,
                    multi = multi,
                    isChecked = { it in selection },
                    onTap = onTap,
                    onBack = { page = SharePage.Main },
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
            }
            SharePage.Main -> Column(Modifier.fillMaxWidth().weight(1f)) {
                IMTopBar(
                    title = title,
                    subtitle = subtitle,
                    leftLabel = stringResource(R.string.common_cancel),
                    onLeft = { sheet.dismiss(onCancel) },
                    actionText = when {
                        !allowMulti -> ""
                        !multi -> stringResource(R.string.forward_picker_multi)
                        shareMode -> "" // 「发送(N)」在底栏
                        selection.size == 0 -> stringResource(R.string.common_send)
                        else -> pluralStringResource(R.plurals.forward_picker_send_count, selection.size, selection.size)
                    },
                    actionEnabled = !multi || selection.size > 0,
                    onAction = {
                        if (!multi) multi = true else sheet.dismiss { onConfirm(targets) }
                    },
                    containerColor = c.pageBackground, // IMCardSheet 底色是 pageBackground，栏跟它同色
                )
                IMSearchField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = stringResource(R.string.forward_picker_search_placeholder),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = d.space4, vertical = d.space2),
                )
                // 「搜了但没搜到」恒说「没匹配」；数据源本就是空的才轮到调用方给的 emptyText——
                // 只在 query 为空时判"数据源本就空"：还没读到库里的数据时（初值空表）不能抢答，闪一下空态
                val emptyMessage = when {
                    sections != null -> if (sections.isEmpty) stringResource(R.string.forward_picker_no_match) else null
                    rows.isNotEmpty() -> null
                    query.isNotBlank() -> stringResource(R.string.forward_picker_no_match)
                    shareMode -> null // 入口行还在，最近聊天空着不用说话
                    emptyText.isNotEmpty() -> emptyText
                    else -> null
                }
                emptyMessage?.let {
                    Text(
                        it,
                        color = c.textTertiary,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
                    )
                }
                val friendsLabel = stringResource(R.string.share_picker_my_friends)
                val groupsLabel = stringResource(R.string.share_picker_my_groups)
                val recentLabel = stringResource(R.string.share_picker_recent)
                val friendsHeader = stringResource(R.string.share_picker_section_friends)
                val groupsHeader = stringResource(R.string.share_picker_section_groups)
                LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                    val rowOf: LazyListScope.(List<ConversationEntity>, String) -> Unit = { list, keyPrefix ->
                        items(list, key = { keyPrefix + it.convId }) { conv ->
                            ForwardRow(conv = conv, multi = multi, checked = conv.convId in selection) { onTap(conv) }
                        }
                    }
                    when {
                        sections != null -> {
                            if (sections.recent.isNotEmpty()) item(key = "h-recent") { SharePickHeader(recentLabel) }
                            rowOf(sections.recent, "r-")
                            if (sections.friends.isNotEmpty()) item(key = "h-friends") { SharePickHeader(friendsHeader) }
                            rowOf(sections.friends, "f-")
                            if (sections.groups.isNotEmpty()) item(key = "h-groups") { SharePickHeader(groupsHeader) }
                            rowOf(sections.groups, "g-")
                        }
                        else -> {
                            if (shareMode && query.isBlank()) {
                                item(key = "e-friends") { SharePickEntryRow(Lucide.User, friendsLabel) { focus.clearFocus(); page = SharePage.Friends } }
                                item(key = "e-groups") { SharePickEntryRow(Lucide.Users, groupsLabel) { focus.clearFocus(); page = SharePage.Groups } }
                                if (rows.isNotEmpty()) item(key = "h-recent") { SharePickHeader(recentLabel) }
                            }
                            rowOf(rows, "")
                        }
                    }
                    if (footer.isNotEmpty()) item { IMSectionFooter(footer) }
                }
            }
        }
        if (shareMode && multi) {
            SharePickBottomBar(
                selected = targets,
                onRemove = { selection = selection.remove(it) },
                onSend = { focus.clearFocus(); reviewing = true },
            )
        }
        if (reviewing && targets.isNotEmpty()) {
            SharePickConfirmSheet(
                targets = targets,
                preview = preview,
                onRemove = { id ->
                    selection = selection.remove(id)
                    if (selection.size == 0) reviewing = false // 删到 0 个就关面板，回到列表
                },
                onSend = {
                    reviewing = false
                    val out = targets
                    sheet.dismiss { onConfirm(out) }
                },
                onDismiss = { reviewing = false },
            )
        }
        confirming?.let { target ->
            IMConfirmDialog(
                title = stringResource(R.string.common_forward),
                message = stringResource(R.string.forward_picker_confirm_single, Forward.titleOf(target)),
                confirmText = stringResource(R.string.common_send),
                destructive = false,
                onConfirm = { sheet.dismiss { onConfirm(listOf(target)) } },
                onDismiss = { confirming = null },
            )
        }
    }
}
