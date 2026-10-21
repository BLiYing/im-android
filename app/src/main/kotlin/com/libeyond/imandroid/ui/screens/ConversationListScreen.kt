package com.libeyond.imandroid.ui.screens

import com.libeyond.imandroid.ui.components.unclippedBoundsInWindow
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.libeyond.imandroid.ui.components.liftSource
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.BellOff
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Pin
import androidx.compose.ui.graphics.compositeOver
import com.libeyond.imandroid.data.ConversationRowStyle
import com.composables.icons.lucide.Plus
import com.libeyond.imandroid.R
import com.libeyond.imandroid.data.ChatFolder
import com.libeyond.imandroid.data.ChatFolderLogic
import com.libeyond.imandroid.data.ChatSubtitle
import com.libeyond.imandroid.data.ChatSubtitleSpec
import com.libeyond.imandroid.sdk.ws.ConnState
import com.libeyond.imandroid.data.Forward
import com.libeyond.imandroid.data.CallRecord
import com.libeyond.imandroid.data.ConversationListPhase
import com.libeyond.imandroid.data.DisplayName
import com.libeyond.imandroid.data.ConversationPreview
import com.libeyond.imandroid.data.MuteState
import com.libeyond.imandroid.data.db.ConversationEntity
import com.libeyond.imandroid.sdk.protocol.ContentType
import com.libeyond.imandroid.ui.components.IMAvatar
import com.libeyond.imandroid.ui.components.IMSearchEntry
import com.libeyond.imandroid.ui.components.IMTopBar
import com.libeyond.imandroid.ui.components.ReadTickIcon
import com.libeyond.imandroid.ui.components.TimeFormat
import com.libeyond.imandroid.ui.components.TopBarCircleButton
import com.libeyond.imandroid.ui.theme.IMTheme

/**
 * 会话列表（CLIENT_PARITY M1「会话列表」行）。
 *
 * 纯展示：数据与动作全经参数注入，不持业务状态（CODING_STYLE §7②）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ConversationListScreen(
    conversations: List<ConversationEntity>,
    /** 画列表、空态还是什么都不画。判据在 [ConversationListPhase]——**别在这里拿 isEmpty 自己判**。 */
    phase: ConversationListPhase,
    /** 我的 uid——副标题"我: "前缀、已读双勾都要判"是不是我发的"。 */
    myUid: String,
    /** 本机对某 uid 的显示名（备注 > 昵称）；取不到回 null，见 [ConversationPreview.of]。 */
    localNameOf: (String) -> String?,
    /** 对端在线态（仅单聊；群聊调用方不必关心）——同 iOS `peerPresence.isOnline`，快照来自
     * 会话列表接口，此后靠 presence 帧增量更新（`data/Presence.kt`）。 */
    onlineOf: (String) -> Boolean,
    /** 群待审入群申请数（仅群主/管理员有，其余 0）：有待审时整行只显红字「[入群申请 N]」、不接上一条预览（iOS `pendingCount`）。 */
    pendingOf: (String) -> Int = { 0 },
    onOpen: (ConversationEntity) -> Unit,
    /** 长按一行，带上它在窗口坐标系里的矩形——菜单要贴着这一行弹（对齐 iOS UIContextMenu）。 */
    onLongPress: (ConversationEntity, Rect) -> Unit,
    /** 右上角 ＋，带上按钮在窗口坐标系里的矩形——菜单贴着它弹（对齐 iOS `plusTapped:` 的 IMPopoverCard）。 */
    onPlus: (Rect) -> Unit,
    /** 点顶部搜索胶囊 → 进全局搜索页（入口不承载输入，同 iOS `searchEntryTapped`）。 */
    onSearch: () -> Unit = {},
    /** 连接态：标题栏副标题显示「连接中…」/「未连接」，已连接不显示（对齐 iOS `im_navigationSubtitle`）。 */
    conn: ConnState,
    /** 判「是否免打扰」的当前时刻（定时免打扰到期刷新，NOTIFICATIONS_P1_DESIGN §4.4）：
     *  纯展示不持业务状态（CODING_STYLE §7②），由调用方喂 `ui/components/MuteTick.kt` 的 tick。 */
    nowMs: Long = System.currentTimeMillis(),
    /** 滚动位置由调用方持有：点进会话时本页整个离开组合，页内 remember 的状态会丢，返回就被甩回顶部。 */
    listState: LazyListState,
    /** 聊天文件夹（设计 CHAT_FOLDERS_DESIGN §4.1）：为空 = 不显示 Tab 条，列表与此前完全一致。 */
    folders: List<ChatFolder> = emptyList(),
    /** 当前选中的文件夹 id；null = 「全部」。状态由调用方持有（进会话时本页离开组合）。 */
    selectedFolderId: String? = null,
    onSelectFolder: (String?) -> Unit = {},
    /** 长按文件夹 Tab（「全部」不触发）。 */
    onFolderLongPress: (ChatFolder, Rect) -> Unit = { _, _ -> },
    /** 每个 Tab 记住自己的滚动位置；由外壳持有。 */
    scrollMemory: FolderScrollMemory = remember { FolderScrollMemory() },
    /** 编辑模式（批量已读 / 删除，设计 CONV_LIST_EDIT_MODE_DESIGN）：状态由外壳持有（底栏在 TabRoot 的底栏插槽里）。 */
    editing: Boolean = false,
    selectedIds: Set<String> = emptySet(),
    onToggleSelect: (String) -> Unit = {},
    onEnterEdit: () -> Unit = {},
    onExitEdit: () -> Unit = {},
) {
    val c = IMTheme.colors
    var plusRect by remember { mutableStateOf(Rect.Zero) }
    // 选中的文件夹被删（别的设备删了）→ 回落「全部」；表格数据源 = 当前范围内的会话
    val selectedFolder = folders.firstOrNull { it.id == selectedFolderId }
    val shown = remember(conversations, selectedFolder) { ChatFolderLogic.filter(conversations, selectedFolder) }
    val tabIndex = ChatFolderLogic.tabIndex(selectedFolderId, folders)
    // 切 Tab：先存当前位置，再把目标 Tab 上次的位置还回来（首次进入 = 顶部）
    val selectFolder: (String?) -> Unit = { id ->
        if (id != selectedFolderId) {
            scrollMemory.save(selectedFolderId, listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
            onSelectFolder(id)
        }
    }
    // 只在「Tab 真的变了」时还原：本页进会话时整层离开组合、返回时重新进入，LaunchedEffect 会再跑一次——
    // 那一次 listState 本来就由外壳保住了，再 scrollToItem(0,0) 会把用户甩回顶部。
    val shownFolderId = selectedFolder?.id
    var restoredFor by remember { mutableStateOf(shownFolderId) }
    LaunchedEffect(shownFolderId) {
        if (restoredFor == shownFolderId) return@LaunchedEffect
        restoredFor = shownFolderId
        val (i, o) = scrollMemory.restore(shownFolderId)
        listState.scrollToItem(i, o)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(c.groupedBackground)
            .systemBarsPadding(),
    ) {
        // 标题**居中**、连接态走副标题，对齐 iOS 注入栏（标题恒为「消息」，连接中放在「在线」那个位置）。
        // 此前是左对齐的大标题 + 右上角一个文字「我」——底栏已有「我」，那个入口是多余的（2026-09-15 用户报）
        // 编辑模式：标题 =「已选择 N 项」（未选时仍是「消息」）、右上「＋」隐藏、左槽「编辑 / 完成」
        // （聊天多选「取消」同一个 leftLabel 槽）；空列表不给入口。
        IMTopBar(
            title = if (editing && selectedIds.isNotEmpty()) {
                androidx.compose.ui.res.pluralStringResource(R.plurals.conv_edit_selected_count, selectedIds.size, selectedIds.size)
            } else {
                stringResource(R.string.ios_tab_messages)
            },
            leftLabel = if (editing) stringResource(R.string.common_done) else if (shown.isNotEmpty()) stringResource(R.string.common_edit) else "",
            onLeft = if (editing) onExitEdit else if (shown.isNotEmpty()) onEnterEdit else null,
            subtitle = when (ChatSubtitle.connection(conn)) {
                ChatSubtitleSpec.Connecting -> stringResource(R.string.conn_state_connecting)
                ChatSubtitleSpec.Disconnected -> stringResource(R.string.conn_state_disconnected)
                else -> ""
            },
            right = if (editing) null else {
                {
                    TopBarCircleButton(
                        icon = Lucide.Plus,
                        description = stringResource(R.string.common_add),
                        onClick = { onPlus(plusRect) },
                        modifier = Modifier.onGloballyPositioned { plusRect = it.boundsInWindow() },
                    )
                }
            },
        )
        IMSearchEntry(
            placeholder = stringResource(R.string.search_global_placeholder),
            onClick = if (editing) ({}) else onSearch, // 编辑模式：搜索入口置灰、点击无效
            modifier = Modifier.fillMaxWidth().padding(horizontal = IMTheme.dimens.space4, vertical = IMTheme.dimens.space2)
                .alpha(if (editing) 0.5f else 1f),
        )
        // 文件夹 Tab 条：紧贴固定的搜索框下方；没有任何文件夹时不显示（设计 §4.1）
        if (folders.isNotEmpty()) {
            ChatFolderTabStrip(
                folders = folders, conversations = conversations, nowMs = nowMs,
                selectedId = selectedFolder?.id, onSelect = selectFolder,
                onLongPress = if (editing) ({ _, _ -> }) else onFolderLongPress, // 编辑模式：Tab 只用来切换范围，长按菜单关闭
            )
        }

        // 内容区横滑切 Tab：只吃横向拖动，竖向滚动不受影响
        Box(
            // 编辑模式：左右滑切 Tab 关闭（只剩点 Tab 条切换），避免勾选时误切
            if (editing) Modifier.fillMaxSize() else Modifier.fillMaxSize().swipeToSwitchFolder(tabIndex, folders.size + 1) { i ->
                selectFolder(if (i <= 0) null else folders.getOrNull(i - 1)?.id)
            },
        ) {
        if (selectedFolder != null && shown.isEmpty()) {
            FolderEmptyState()
        } else when (phase) {
            // 还不知道有没有：什么都不画。画空态 = 先宣布「还没有会话」再改口
            ConversationListPhase.Loading -> Unit
            ConversationListPhase.Empty -> EmptyState()
            ConversationListPhase.List -> {
                // 列表按 convId 做 key：新消息把某会话顶到第一行时，LazyColumn 会**锚住原来的第一行**，
                // 新顶上来的会话被挤到屏幕上方、看起来像「消失了」（2026-09-29 真机）。
                // 用户本来就停在顶部时跟着回顶；往下翻着看时不动，不打断阅读。
                // 「顶部」要在**新列表布局前**判：remember(topKey) 在组合期算，此时 listState 还是旧位置
                // （等 LaunchedEffect 里再读，锚定已经把 index 推到了 1+）。
                val topKey = shown.firstOrNull()?.convId
                val wasAtTop = remember(topKey) {
                    Snapshot.withoutReadObservation { listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0 }
                }
                LaunchedEffect(topKey) { if (wasAtTop) listState.scrollToItem(0) }
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                items(shown, key = { it.convId }) { conv ->
                    ConversationRow(
                        conv, myUid, localNameOf, onlineOf, pendingOf(conv.convId), nowMs,
                        onClick = { if (editing) onToggleSelect(conv.convId) else onOpen(conv) },
                        onLongClick = { r -> onLongPress(conv, r) },
                        editing = editing, selected = conv.convId in selectedIds,
                        // 置顶 / 取消置顶：行平滑上移 / 下移（key = convId，重排时按位移补间，不再瞬间跳位）
                        modifier = Modifier.animateItem(),
                    )
                }
            }
            }
        }
        } // 横滑 Box
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationRow(
    conv: ConversationEntity,
    myUid: String,
    localNameOf: (String) -> String?,
    onlineOf: (String) -> Boolean,
    pendingCount: Int,
    nowMs: Long,
    onClick: () -> Unit,
    onLongClick: (Rect) -> Unit,
    modifier: Modifier = Modifier,
    /** 编辑模式：行首出现勾选圈（圈 22 + 间距 8 = [SELECTION_GUTTER]），点整行 = 勾选，长按菜单关闭。 */
    editing: Boolean = false,
    selected: Boolean = false,
) {
    var rect by remember { mutableStateOf(Rect.Zero) }
    // 长按原位浮起（iOS 会话列表的 UIContextMenu）：本行录进层，菜单里画它（MenuLift.kt）
    val lift = com.libeyond.imandroid.ui.components.rememberLiftHandle()
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val c = IMTheme.colors
    val d = IMTheme.dimens
    // 标题为空（刚收到陌生人首条消息、列表还没拉回来）：先用本机好友表的名字，再退「未命名」，**绝不露 convId**
    val title = Forward.titleOf(conv, localNameOf)
    // 不用 remember：算的是字符串拼接，比记忆化本身还便宜；
    // 记了反而会在 localNameOf 解析结果变化（改备注）时读到 (conv, myUid) 没变的旧值。
    val preview = ConversationPreview.of(conv, myUid, localNameOf)

    Row(
        modifier = modifier
            .fillMaxWidth()
            // 行高 76 = 12 + 52 + 12（UI_SPEC §2，iOS 写死 rowHeight 76）。
            // 用 heightIn(min) 不用 height：长昵称换行时允许长高，不裁内容。
            .heightIn(min = d.convRowHeight)
            // 放在底色之前：置顶行的强调色底也要录进浮起的那张卡片
            .liftSource(lift)
            // 置顶行底色：强调色 α0.10 叠在页面底色上（iOS `contentView.backgroundColor`，深浅色皆适配）
            .background(
                when {
                    selected -> c.accent.copy(alpha = 0.16f).compositeOver(c.pageBackground) // 选中行：主色 16%（盖过置顶底色；比置顶的 10% 深，两者才分得清）
                    conv.pinnedAt > 0 -> c.accent.copy(alpha = 0.10f).compositeOver(c.pageBackground)
                    else -> c.pageBackground
                },
            )
            .onGloballyPositioned { rect = it.unclippedBoundsInWindow() }
            .combinedClickable(onClick = onClick, onLongClick = if (editing) null else ({
                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                lift.lift(rect)
                onLongClick(rect)
            }))
            .padding(horizontal = d.space4, vertical = d.space3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.animation.AnimatedVisibility(
            visible = editing,
            enter = androidx.compose.animation.expandHorizontally() + androidx.compose.animation.fadeIn(),
            exit = androidx.compose.animation.shrinkHorizontally() + androidx.compose.animation.fadeOut(),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SelectionCheck(selected)
                SelectionGutterSpacer()
            }
        }
        Box {
            IMAvatar(
                displayName = title,
                // 种子用 uid 不用显示名——改昵称不该换颜色
                seed = if (conv.isGroup) conv.convId else conv.peerUid.ifBlank { conv.convId },
                avatarUrl = conv.avatarUrl,
                size = d.convAvatar,
            )
            // 在线态绿点：仅单聊且对端在线时显示（对齐 iOS `_onlineDot`）。群聊不显示。
            // 12dp 圆 + 2dp 边框（描边色=行背景，抠出与底色的间隙），贴头像右下角。
            if (!conv.isGroup && onlineOf(conv.peerUid)) {
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .size(12.dp)
                        .background(c.pageBackground, CircleShape)
                        .padding(2.dp)
                        .background(c.online, CircleShape),
                )
            }
        }
        Spacer(Modifier.width(d.space3))

        // 右侧「时间」与「未读角标」**各自独立锚定**（iOS `_deliveryTimeStack.centerY = _nameStateStack.centerY`、
        // `_badge/_dot.centerY = _last.centerY`）：时间落在名称行右端、角标落在预览行右端。
        // 此前两者叠成一个整体垂直居中的 Column，有角标时整体比无角标高 20+6dp，居中后时间 y 漂 ~10dp。
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        color = c.textPrimary,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    // 置顶图标与免打扰铃铛**同款**：14dp 次要色矢量（iOS `pin.fill` / `bell.slash.fill` 同 tintColor）
                    if (conv.pinnedAt > 0) {
                        Spacer(Modifier.width(4.dp))
                        androidx.compose.foundation.Image(
                            Lucide.Pin, null, Modifier.size(14.dp),
                            colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(c.textSecondary),
                        )
                    }
                    // 免打扰铃铛：名称 → 置顶 → 免打扰，状态图标紧跟名称（对齐 iOS `_nameStateStack`，
                    // 14dp 次要色 bell.slash.fill）；定时免打扰到期后跟着消失，判据同未读徽标。
                    if (MuteState.isMutedNow(conv.muted, conv.muteUntil, nowMs)) {
                        Spacer(Modifier.width(4.dp))
                        androidx.compose.foundation.Image(
                            Lucide.BellOff, null, Modifier.size(14.dp),
                            colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(c.textSecondary),
                        )
                    }
                }
                Spacer(Modifier.width(d.space2))
                Text(
                    text = TimeFormat.conversationTime(conv.lastTimestamp),
                    color = c.textTertiary,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    // 群里有人 @我：红字前缀，且**穿透免打扰**
                    if (conv.mentionUnread) {
                        Text(
                            text = stringResource(R.string.conv_list_mention_tag) + " ",
                            color = c.danger,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    // 群「待审入群申请」（G3，仅群主/管理员）：有待审时整行只显红字提示（可带 [有人@我]），
                    // 不再接上一条消息——待审是「当前待办」，上一条是「历史」，拼在一起读不通。
                    // 审批清零后 pendingCount=0，自然回到最后一条消息预览（对齐 iOS cell / Web convlast）。
                    val pendingOnly = conv.isGroup && pendingCount > 0
                    if (pendingOnly) {
                        Text(
                            text = stringResource(R.string.conv_list_pending_tag, pendingCount),
                            color = c.danger,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                        )
                    }
                    // 单聊已读双勾：只有「我发的、对方已读」才画蓝双勾图标，未读灰单勾图标——与聊天页气泡同一表意
                    // （对齐 iOS `showCheck`）。群聊没有对端已读位点这个概念，恒不画。
                    if (!conv.isGroup && conv.lastFrom == myUid && !conv.lastRecalled && conv.lastContent.isNotBlank()) {
                        val read = conv.peerReadSeq >= conv.lastConvSeq
                        ReadTickIcon(
                            read, if (read) c.checkRead else c.textSecondary,
                            MaterialTheme.typography.bodyMedium.fontSize,
                        )
                        Spacer(Modifier.width(3.dp))
                    }
                    if (!pendingOnly) {
                        Text(
                            text = preview,
                            // 被叫侧「未接来电」整行预览标红（danger，不随主题变）；撤回态不再是通话消息，不标红
                            color = if (!conv.lastRecalled && conv.lastContentType == ContentType.CALL && CallRecord.isMissedPreview(conv.lastContent)) {
                                c.danger
                            } else {
                                c.textSecondary
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (UnreadMark.shown(conv)) {
                    Spacer(Modifier.width(d.space2))
                    UnreadBadge(conv, nowMs)
                }
            }
        }
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(0.5.dp)
            .padding(start = d.convSeparatorInset)
            .background(c.separator),
    )
}

/**
 * 未读徽标。
 * - 免打扰会话显示**灰点**不显示数字（弱提示），但 **@我 仍要红底数字**——穿透免打扰。
 * - 手动标未读显示小红点、不计数。
 */
@Composable
private fun UnreadBadge(conv: ConversationEntity, nowMs: Long) {
    val c = IMTheme.colors
    // 判「是否免打扰」一律走 MuteState.isMutedNow，不直接读 conv.muted（NOTIFICATIONS_P1_DESIGN §4.3）。
    val mutedNow = MuteState.isMutedNow(conv.muted, conv.muteUntil, nowMs)
    when {
        conv.unread > 0 -> {
            val strongAlert = ConversationRowStyle.strongAlert(mutedNow, conv.mentionUnread)
            val d = IMTheme.dimens
            Box(
                // 高 20、最小宽 20（UI_SPEC §2，与 iOS _badge.heightAnchor 同值）：
                // 个位数是正圆，两位数才拉成胶囊。
                modifier = Modifier
                    .height(d.unreadBadgeHeight)
                    .widthIn(min = d.unreadBadgeHeight)
                    .clip(CircleShape)
                    .background(if (strongAlert) c.unreadBadge else c.unreadBadgeMuted)
                    .padding(horizontal = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = ConversationRowStyle.compactCount(conv.unread),
                    color = c.onAccent,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                )
            }
        }
        conv.markedUnread -> Box(
            // 标未读圆点同徽标：免打扰且没被 @ → 灰（iOS `_dot.backgroundColor = unreadColor`）
            modifier = Modifier.size(10.dp).clip(CircleShape)
                .background(if (ConversationRowStyle.strongAlert(mutedNow, conv.mentionUnread)) c.unreadBadge else c.unreadBadgeMuted),
        )
        // 免打扰的铃铛不在这里：iOS 把它放在**名称行**、紧跟置顶图标（`_nameStateStack`），
        // 这一格只给未读徽标（灰色表示免打扰）。此前把 🔕 画在时间下方，位置与 iOS 不符。
        else -> Unit
    }
}

/** 预览行右端有没有未读标记（数字徽标 / 标未读圆点）；没有就连间距都不占，预览得到整行宽。 */
internal object UnreadMark {
    fun shown(conv: ConversationEntity): Boolean = conv.unread > 0 || conv.markedUnread
}

/** 选中了某个文件夹但其中没有会话（设计 §4.1）：提示怎么放进来。 */
@Composable
private fun FolderEmptyState() {
    val c = IMTheme.colors
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = IMTheme.dimens.space4 * 2),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            stringResource(R.string.folder_list_empty),
            color = c.textSecondary,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun EmptyState() {
    val c = IMTheme.colors
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 与 iOS/Web 共用同一句完整文案（conv.list.empty），不再手工拆成两行——
        // 拆开在中文里正好卡在逗号上，英文原文是句号+另起一句，硬切位置对不上
        Text(
            stringResource(R.string.conv_list_empty),
            color = c.textSecondary,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
    }
}
