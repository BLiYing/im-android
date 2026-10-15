package com.libeyond.imandroid.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Copy
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.PinOff
import com.composables.icons.lucide.X
import com.libeyond.imandroid.R
import com.libeyond.imandroid.data.PinnedBanner
import com.libeyond.imandroid.data.PinnedMenuAction
import com.libeyond.imandroid.data.PinnedPanel
import com.libeyond.imandroid.data.ReplyNames
import com.libeyond.imandroid.data.SenderRun
import com.libeyond.imandroid.data.SysEvents
import com.libeyond.imandroid.data.db.MessageEntity
import com.libeyond.imandroid.i18n.Str
import com.libeyond.imandroid.sdk.api.PinnedMessage
import com.libeyond.imandroid.ui.components.ChatWallpaperBackground
import com.libeyond.imandroid.ui.components.IMConfirmDialog
import com.libeyond.imandroid.ui.components.IMTopBar
import com.libeyond.imandroid.ui.components.LocateInChatIcon
import com.libeyond.imandroid.ui.components.MenuAnchor
import com.libeyond.imandroid.ui.components.SheetItem
import com.libeyond.imandroid.ui.components.WindowContextMenu
import com.libeyond.imandroid.ui.screens.Bubble
import com.libeyond.imandroid.ui.screens.ChatRowStyle
import com.libeyond.imandroid.ui.screens.DaySeparator
import com.libeyond.imandroid.ui.screens.senderNameOf
import com.libeyond.imandroid.ui.theme.IMTheme
import kotlinx.coroutines.launch

/**
 * 置顶消息底部面板（PINNED_PANEL_DESIGN §2，1:1 稿 `sketches/PINNED_PANEL_UX_SKETCH.html` ③）：
 * `ModalBottomSheet`（半屏起、可拖到全屏）里就是一段**只读的聊天**——壁纸底 + 与聊天页同源的真气泡（[Bubble] `readOnly`），
 * 气泡旁紧挨一枚「定位到聊天位置」图标（别人的在右、我发的在左，底边对齐，间距 8）。
 *
 * 点气泡 / 图标 = 先收起面板再 [onLocate]；长按 = 过滤后的消息菜单（取消置顶 / 复制 / 定位，[PinnedPanel.menuActions]）；
 * 底栏「取消所有置顶」在流内（不悬浮），二次确认后调批量接口。无权限：无取消项、无底栏、无副标题。
 * 置顶集合是宿主（`ChatBanners`）的实时状态，面板开着时别人置顶 / 取消 / 撤回会直接刷新；空集自动收起。
 *
 * @param localRows 按 conv_seq 取本地库里已有的行（兜底 ①，见 [PinnedPanel.resolve]）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PinnedBottomSheet(
    items: List<PinnedMessage>,
    convId: String,
    style: ChatRowStyle,
    canUnpin: Boolean,
    localRows: suspend (List<Long>) -> Map<Long, MessageEntity>,
    onLocate: (Long) -> Unit,
    onUnpin: (Long) -> Unit,
    onUnpinAll: () -> Unit,
    onToast: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = IMTheme.colors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true) // 只有全高一档：打开即最高点，下滑到一定距离即关闭
    val scope = rememberCoroutineScope()
    /** 先收起（动画走完）再做事：定位要等面板退场，否则跳转动画被 sheet 挡着。 */
    fun close(then: () -> Unit = {}) {
        scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss(); then() }
    }
    // 别人把最后一条也取消了 / 撤回了：别留一个空壳
    LaunchedEffect(items.isEmpty()) { if (items.isEmpty()) close() }

    val local by produceState(emptyMap<Long, MessageEntity>(), items) { value = localRows(items.map { it.convSeq }) }
    val msgs = remember(items, local) {
        items.map { PinnedPanel.resolve(it, local[it.convSeq], style.myUid, convId, PinnedBanner::preview) }
    }
    var menu by remember { mutableStateOf<Pair<MessageEntity, MenuAnchor>?>(null) }
    var confirmAll by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = c.surface) {
        Column(Modifier.fillMaxWidth().sheetBody()) {
            IMTopBar(
                title = stringResource(R.string.pinned_title, items.size),
                subtitle = stringResource(if (PinnedPanel.showsLongPressHint(canUnpin)) R.string.pinned_long_press_hint else R.string.pinned_long_press_hint_readonly),
                leftIcon = Lucide.X, leftDescription = stringResource(R.string.common_close),
                onLeft = { close() }, containerColor = c.surface,
            )
            Box(Modifier.weight(1f).fillMaxWidth()) {
                ChatWallpaperBackground(Modifier.matchParentSize())
                val listState = rememberLazyListState()
                val keepInList = remember(listState) { KeepScrollInList(listState) }
                LazyColumn(Modifier.fillMaxWidth().nestedScroll(keepInList), state = listState, contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 6.dp, bottom = 12.dp)) {
                    itemsIndexed(msgs, key = { _, m -> m.convSeq }) { i, m ->
                        if (PinnedPanel.showsDayPill(msgs.getOrNull(i - 1)?.timestamp, m.timestamp)) DaySeparator(m.timestamp)
                        PinnedRow(m, style, onLocate = { close { onLocate(m.convSeq) } }, onLongPress = { menu = m to it })
                    }
                }
            }
            if (PinnedPanel.showsUnpinAll(canUnpin, items.size)) UnpinAllBar { confirmAll = true }
        }
        menu?.let { (m, anchor) ->
            PinnedRowMenu(
                m, anchor, style, canUnpin,
                onUnpin = { onUnpin(m.convSeq) }, onLocate = { close { onLocate(m.convSeq) } },
                onToast = onToast, onDismiss = { menu = null },
            )
        }
        if (confirmAll) {
            IMConfirmDialog(
                title = stringResource(R.string.pinned_unpin_all_title),
                message = pluralStringResource(R.plurals.pinned_unpin_all_message, items.size, items.size),
                confirmText = stringResource(R.string.pinned_unpin_all_confirm),
                onConfirm = onUnpinAll,
                onDismiss = { confirmAll = false },
            )
        }
    }
}

/**
 * 面板主体的高度：① 全屏展开时停在状态栏下方（sheet 本身不避让状态栏）；② **半屏时把底栏留在可见区底边**——
 * M3 的半屏态是整张 sheet 往下推一半，流内底栏会被推出屏幕；这里量出被推出去的那截，把内容区缩回可见区
 * （只在 layout 阶段读，不触发重组；拖动时晚一帧跟上）。外框高度恒定，不会反过来改动 sheet 的锚点。
 */
@Composable
private fun Modifier.sheetBody(): Modifier {
    val density = LocalDensity.current
    val topInset = WindowInsets.statusBars.getTop(density)
    val minVisible = with(density) { MIN_VISIBLE.roundToPx() }
    var hidden by remember { mutableFloatStateOf(0f) }
    return this
        .layout { m, cs ->
            if (!cs.hasBoundedHeight) return@layout m.measure(cs).let { p -> layout(p.width, p.height) { p.place(0, 0) } }
            val h = (cs.maxHeight - topInset).coerceAtLeast(0)
            val p = m.measure(cs.copy(minHeight = h, maxHeight = h))
            layout(p.width, h) { p.place(0, 0) }
        }
        .onGloballyPositioned { co ->
            val rootH = co.findRootCoordinates().size.height
            hidden = (co.positionInRoot().y + co.size.height - rootH).coerceAtLeast(0f)
        }
        .layout { m, cs ->
            if (!cs.hasBoundedHeight) return@layout m.measure(cs).let { p -> layout(p.width, p.height) { p.place(0, 0) } }
            val full = cs.maxHeight
            val cut = hidden.toInt().coerceIn(0, (full - minVisible).coerceAtLeast(0))
            val p = m.measure(cs.copy(minHeight = full - cut, maxHeight = full - cut))
            layout(p.width, full) { p.place(0, 0) }
        }
}

private val MIN_VISIBLE = 200.dp

/** 一条置顶 = 一个只读聊天行：行外距上下 4、左右 16；图标列 40（32 + 间距 8）先让出去，气泡在余下宽里包裹内容。 */
@Composable
private fun PinnedRow(m: MessageEntity, style: ChatRowStyle, onLocate: () -> Unit, onLongPress: (MenuAnchor) -> Unit) {
    val mine = m.sender == style.myUid
    val leading = PinnedPanel.locateIconLeading(mine)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = IMTheme.dimens.space4, vertical = 4.dp),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom,
    ) {
        if (leading) { LocateButton(onLocate); Spacer(Modifier.width(ICON_GAP)) }
        Box(Modifier.weight(1f, fill = false)) { PinnedBubble(m, style, onLocate, onLongPress) }
        if (!leading) { Spacer(Modifier.width(ICON_GAP)); LocateButton(onLocate) }
    }
}

private val ICON_GAP = 8.dp

/** 与聊天页同一个 [Bubble]（只读）：群聊别人的消息每条都显头像 + 昵称（面板里没有连续段合并）；不画已读勾 / 发送态。 */
@Composable
private fun PinnedBubble(m: MessageEntity, style: ChatRowStyle, onTap: () -> Unit, onLongPress: ((MenuAnchor) -> Unit)?) {
    val mine = m.sender == style.myUid
    val them = style.isGroup && !mine
    val replyFrom = m.replyToFrom
    Bubble(
        text = SysEvents.noticeTextOf(m) ?: m.content,
        msg = m,
        mentionNames = style.mentionNames,
        onLongPress = onLongPress,
        host = style.host, useTls = style.useTls,
        mine = mine,
        timestamp = m.timestamp,
        senderName = if (mine) null else style.senderNameOf(m),
        showSenderName = them,
        senderBadge = if (them) SenderRun.badgeOf(style.roleOf(m.sender), m.fromRole) else null,
        quoteSnapshot = com.libeyond.imandroid.ui.screens.quoteSnapshotFor(emptyList(), m),
        replyFromName = if (style.isGroup) {
            ReplyNames.quoteFrom(replyFrom, style.myUid, replyFrom?.let(style.localNameOf), replyFrom?.let(style.memberNameOf), null)
        } else null,
        reserveAvatarColumn = them,
        showAvatar = them,
        avatarSeed = m.sender,
        senderAvatarUrl = if (them) style.avatarOf(m.sender) else "",
        isGroup = style.isGroup,
        loadLinkPreview = style.loadLinkPreview,
        readOnly = true,
        onTap = onTap,
    )
}

/** 定位到聊天位置：32 圆、`surfaceElevated` 底 + 轻阴影（压在壁纸上要有底）、图标 18 `accent`；长按出 tooltip。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LocateButton(onClick: () -> Unit) {
    val c = IMTheme.colors
    val label = stringResource(R.string.pinned_locate)
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
    ) {
        Box(
            Modifier.size(32.dp).shadow(1.dp, CircleShape, ambientColor = c.shadowSoft, spotColor = c.shadowSoft)
                .clip(CircleShape).background(c.surfaceElevated)
                .clickable(onClick = onClick).semantics { contentDescription = label },
            contentAlignment = Alignment.Center,
        ) {
            Icon(LocateInChatIcon, null, tint = c.accent, modifier = Modifier.size(18.dp))
        }
    }
}

/** 底栏（流内，不悬浮）：高 64、顶部 1px 分隔线、`surface` 底；居中 FilledTonal 40 高「取消所有置顶」（danger 字）。 */
@Composable
private fun UnpinAllBar(onClick: () -> Unit) {
    val c = IMTheme.colors
    val hairline = with(LocalDensity.current) { 1f.toDp() }
    Column(Modifier.fillMaxWidth().background(c.surface)) {
        Box(Modifier.fillMaxWidth().height(hairline).background(c.separator))
        Box(Modifier.fillMaxWidth().height(64.dp - hairline), contentAlignment = Alignment.Center) {
            FilledTonalButton(
                onClick = onClick,
                modifier = Modifier.height(40.dp),
                colors = ButtonDefaults.filledTonalButtonColors(containerColor = c.surfaceElevated, contentColor = c.danger),
            ) {
                Text(stringResource(R.string.pinned_unpin_all), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/**
 * 长按菜单：复用聊天页的 [com.libeyond.imandroid.ui.components.MessageContextMenu]（经 [WindowContextMenu] 抬到窗口级——
 * sheet 里没有全屏的树内空间），项由 [PinnedPanel.menuActions] 过滤。「取消置顶」只作用于所长按那条、不确认（可逆）。
 */
@Composable
private fun PinnedRowMenu(
    m: MessageEntity, anchor: MenuAnchor, style: ChatRowStyle, canUnpin: Boolean,
    onUnpin: () -> Unit, onLocate: () -> Unit, onToast: (String) -> Unit, onDismiss: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val items = PinnedPanel.menuActions(m, canUnpin).map { a ->
        when (a) {
            PinnedMenuAction.Unpin -> SheetItem(stringResource(R.string.pinned_unpin), destructive = true, icon = Lucide.PinOff, onClick = onUnpin)
            PinnedMenuAction.Copy -> SheetItem(stringResource(R.string.common_copy), icon = Lucide.Copy) {
                clipboard.setText(AnnotatedString(m.content))
                onToast(Str.s(R.string.common_copied))
            }
            PinnedMenuAction.Locate -> SheetItem(stringResource(R.string.chat_menu_locate), icon = LocateInChatIcon, onClick = onLocate)
        }
    }
    WindowContextMenu(
        anchor = anchor.area, focus = anchor.focus, mine = m.sender == style.myUid, items = items, onDismiss = onDismiss,
        // 原位重绘同一个只读气泡（预览层本身拦掉所有指针事件）
        preview = { PinnedBubble(m, style, onTap = {}, onLongPress = {}) },
    )
}

/**
 * 列表滑动 vs 下滑关闭：手指落下时列表若**不在顶部**，这一次手势里多出来的下拉（滑回顶部后继续往下的余量与惯性）
 * 一律在列表层吃掉，不再传给外层 sheet——否则快速回顶极易误关。手指落下时已在顶部的下拉照旧交给 sheet 关闭。
 */
private class KeepScrollInList(private val state: androidx.compose.foundation.lazy.LazyListState) :
    androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
    private var inGesture = false
    private var startedAtTop = true

    override fun onPreScroll(available: androidx.compose.ui.geometry.Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): androidx.compose.ui.geometry.Offset {
        if (!inGesture && source == androidx.compose.ui.input.nestedscroll.NestedScrollSource.UserInput) {
            inGesture = true
            startedAtTop = !state.canScrollBackward
        }
        return androidx.compose.ui.geometry.Offset.Zero
    }

    override fun onPostScroll(
        consumed: androidx.compose.ui.geometry.Offset,
        available: androidx.compose.ui.geometry.Offset,
        source: androidx.compose.ui.input.nestedscroll.NestedScrollSource,
    ): androidx.compose.ui.geometry.Offset =
        if (!startedAtTop && available.y > 0f) androidx.compose.ui.geometry.Offset(0f, available.y) else androidx.compose.ui.geometry.Offset.Zero

    override suspend fun onPostFling(consumed: androidx.compose.ui.unit.Velocity, available: androidx.compose.ui.unit.Velocity): androidx.compose.ui.unit.Velocity {
        val eat = if (!startedAtTop) androidx.compose.ui.unit.Velocity(0f, available.y.coerceAtLeast(0f)) else androidx.compose.ui.unit.Velocity.Zero
        inGesture = false
        return eat
    }
}
