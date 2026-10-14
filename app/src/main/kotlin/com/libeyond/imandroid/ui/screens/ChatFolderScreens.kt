package com.libeyond.imandroid.ui.screens

import androidx.compose.foundation.gestures.detectDragGestures
import kotlin.math.roundToInt
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.zIndex
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Briefcase
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.Folder
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Smile
import com.composables.icons.lucide.Users
import com.libeyond.imandroid.R
import com.libeyond.imandroid.data.ChatFolder
import com.libeyond.imandroid.data.ListSearch
import com.libeyond.imandroid.data.db.ConversationEntity
import com.libeyond.imandroid.ui.components.IMAvatar
import com.libeyond.imandroid.ui.components.IMRowDivider
import com.libeyond.imandroid.ui.components.IMSearchField
import com.libeyond.imandroid.ui.components.IMSectionFooter
import com.libeyond.imandroid.ui.components.IMSectionHeader
import com.libeyond.imandroid.ui.components.IMSettingsGroup
import com.libeyond.imandroid.ui.components.IMSettingsRow
import com.libeyond.imandroid.ui.components.IMTopBar
import com.libeyond.imandroid.ui.theme.IMTheme

/** 空态推荐名称（与 iOS / Web 同三项；点一下 = 以该名称进创建页，不预填会话）。 */
internal enum class FolderRecommend(val nameRes: Int) {
    Work(R.string.folder_recommend_work),
    Personal(R.string.folder_recommend_personal),
    Groups(R.string.folder_recommend_groups),
}

/**
 * 「我」→ 聊天文件夹 管理页（设计 §4.5）：文件夹列表（左滑删除；排序用上下移）+「创建新文件夹」；
 * 空态给「推荐文件夹」。纯展示，状态与导航在 `ChatFoldersHost`。
 */
@Composable
internal fun ChatFoldersScreen(
    folders: List<ChatFolder>,
    maxFolders: Int,
    onOpen: (ChatFolder) -> Unit,
    onCreate: () -> Unit,
    onRecommend: (String) -> Unit,
    onDelete: (ChatFolder) -> Unit,
    /** 拖动排序结束：按新顺序给出全部文件夹 id。 */
    onReorder: (List<String>) -> Unit,
    onBack: () -> Unit,
) {
    val c = IMTheme.colors
    val d = IMTheme.dimens
    val ic = IMTheme.settingsIcons
    var openedId by remember { mutableStateOf<String?>(null) }
    val maxed = folders.size >= maxFolders
    // 排序（对齐 iOS：右上「编辑」进入，长按拖动）：编辑态下用本地顺序实时重排，松手一次提交；快照一变就重置本地顺序
    var editing by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var order by remember(folders) { mutableStateOf(folders) }
    var dragId by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableStateOf(0f) }
    var rowH by remember { mutableStateOf(0f) }
    LaunchedEffect(folders.isEmpty()) { if (folders.isEmpty()) editing = false }
    Column(Modifier.fillMaxSize().background(c.groupedBackground).statusBarsPadding()) {
        IMTopBar(
            title = stringResource(R.string.settings_row_folders),
            onLeft = onBack,
            actionText = if (folders.isEmpty()) "" else stringResource(if (editing) R.string.common_done else R.string.common_edit),
            onAction = if (folders.isEmpty()) null else ({ editing = !editing; openedId = null }),
        )
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            if (folders.isEmpty()) {
                FolderEmptyHero()
                IMSettingsGroup(Modifier.padding(top = d.space4)) {
                    IMSettingsRow(title = stringResource(R.string.folder_manage_create), onClick = onCreate)
                }
                IMSectionHeader(stringResource(R.string.folder_manage_recommended))
                IMSettingsGroup {
                    FolderRecommend.values().forEachIndexed { i, r ->
                        if (i > 0) IMRowDivider(insetStart = d.space4 + 30.dp + d.space3)
                        val name = stringResource(r.nameRes)
                        IMSettingsRow(
                            title = name,
                            onClick = { onRecommend(name) },
                            icon = when (r) { FolderRecommend.Work -> Lucide.Briefcase; FolderRecommend.Personal -> Lucide.Smile; else -> Lucide.Users },
                            iconBackground = when (r) { FolderRecommend.Work -> ic.green; FolderRecommend.Personal -> ic.orange; else -> ic.purple },
                            rightValue = stringResource(R.string.folder_manage_recommended_add),
                        )
                    }
                }
            } else {
                IMSectionHeader(stringResource(R.string.folder_manage_section_mine, folders.size, maxFolders))
                IMSettingsGroup {
                    val shown = if (editing) order else folders
                    shown.forEachIndexed { i, f ->
                        androidx.compose.runtime.key(f.id) {
                            if (i > 0) IMRowDivider(insetStart = d.space4 + 30.dp + d.space3)
                            if (editing) {
                                val dragging = dragId == f.id
                                // 拖动期间**不改列表顺序**（在 Column 里挪节点会让进行中的手势被取消）：被拖行跟手位移，
                                // 其余行按「被拖行当前落点」让出一格；松手才一次性换序并提交。
                                val n = order.size
                                val from = if (dragId != null) order.indexOfFirst { it.id == dragId } else -1
                                val target = if (from >= 0 && rowH > 0f)
                                    (from + (dragOffset / rowH).roundToInt()).coerceIn(0, n - 1) else from
                                val shiftY = when {
                                    dragging -> dragOffset
                                    from < 0 -> 0f
                                    from < target && i in (from + 1)..target -> -rowH
                                    target < from && i in target until from -> rowH
                                    else -> 0f
                                }
                                val start = { dragId = f.id; dragOffset = 0f }
                                val move = { dy: Float ->
                                    val idx = order.indexOfFirst { it.id == f.id }
                                    val lo = -idx * rowH
                                    val hi = (order.lastIndex - idx) * rowH
                                    dragOffset = (dragOffset + dy).coerceIn(lo, hi)
                                }
                                val end = {
                                    val idx = order.indexOfFirst { it.id == f.id }
                                    val t = if (rowH > 0f) (idx + (dragOffset / rowH).roundToInt()).coerceIn(0, order.lastIndex) else idx
                                    dragId = null; dragOffset = 0f
                                    if (t != idx) {
                                        val moved = order.toMutableList().also { val x = it.removeAt(idx); it.add(t, x) }
                                        order = moved // 先本地换序，服务端回包后快照一变自然重置
                                        onReorder(moved.map { it.id })
                                    }
                                }
                                val cancel = { dragId = null; dragOffset = 0f }
                                FolderRow(
                                    f, iconColor = ic.blue, editing = true, onClick = {},
                                    modifier = Modifier
                                        .onSizeChanged { rowH = it.height.toFloat() }
                                        .zIndex(if (dragging) 1f else 0f)
                                        .offset { androidx.compose.ui.unit.IntOffset(0, shiftY.roundToInt()) }
                                        // 整行长按后拖动（对齐 iOS 编辑态长按排序）
                                        .pointerInput(f.id) {
                                            detectDragGesturesAfterLongPress(
                                                onDragStart = { start() },
                                                onDrag = { change, amount -> change.consume(); move(amount.y) },
                                                onDragEnd = { end() },
                                                onDragCancel = { cancel() },
                                            )
                                        },
                                    // 右侧 ≡ 手柄：按下即拖（iOS reorder control 同款，不必先长按）
                                    handleModifier = Modifier.pointerInput(f.id) {
                                        detectDragGestures(
                                            onDragStart = { start() },
                                            onDrag = { change, amount -> change.consume(); move(amount.y) },
                                            onDragEnd = { end() },
                                            onDragCancel = { cancel() },
                                        )
                                    },
                                )
                            } else {
                                val opened = openedId == f.id
                                SwipeActionRow(
                                    actions = listOf(
                                        SwipeAction(stringResource(R.string.folder_tab_menu_delete), c.danger) { openedId = null; onDelete(f) },
                                    ),
                                    opened = opened,
                                    onOpenedChange = { open -> openedId = if (open) f.id else null },
                                ) {
                                    FolderRow(f, iconColor = ic.blue, editing = false, onClick = { if (opened) openedId = null else onOpen(f) })
                                }
                            }
                        }
                    }
                }
                IMSettingsGroup(Modifier.padding(top = d.space4)) {
                    IMSettingsRow(
                        title = stringResource(R.string.folder_manage_create),
                        onClick = if (maxed) null else onCreate,
                        muted = maxed,
                    )
                }
                IMSectionFooter(
                    if (maxed) stringResource(R.string.folder_manage_max_reached, maxFolders)
                    else stringResource(R.string.folder_manage_footer),
                )
            }
            Spacer(Modifier.height(d.sectionGap))
        }
    }
}

@Composable
private fun FolderEmptyHero() {
    val c = IMTheme.colors
    Column(
        Modifier.fillMaxWidth().padding(top = 28.dp, start = 32.dp, end = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        androidx.compose.foundation.Image(
            Lucide.Folder, null, Modifier.size(48.dp),
            colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(c.textSecondary),
        )
        Text(stringResource(R.string.folder_manage_empty_title), color = c.textPrimary, style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.folder_manage_empty_desc),
            color = c.textSecondary, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun FolderRow(
    f: ChatFolder, iconColor: androidx.compose.ui.graphics.Color, editing: Boolean,
    onClick: () -> Unit, modifier: Modifier = Modifier, handleModifier: Modifier = Modifier,
) {
    val c = IMTheme.colors
    val d = IMTheme.dimens
    Row(
        modifier.fillMaxWidth().background(c.cardBackground).clickable(enabled = !editing, onClick = onClick)
            .defaultMinSize(minHeight = d.settingsRowHeight).padding(horizontal = d.space4, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        com.libeyond.imandroid.ui.components.SettingsIconTile(Lucide.Folder, iconColor)
        Spacer(Modifier.width(d.space3))
        Column(Modifier.weight(1f)) {
            Text(f.name, color = c.textPrimary, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                pluralStringResource(R.plurals.folder_manage_chats_count, f.convIds.size, f.convIds.size),
                color = c.textSecondary, style = MaterialTheme.typography.bodySmall,
            )
        }
        // 编辑态右侧的拖动手柄（整行长按即可拖；手柄只是提示，同 iOS reorder control）
        if (editing) {
            Box(handleModifier.size(44.dp), contentAlignment = Alignment.Center) {
                Text("≡", color = c.textTertiary, style = MaterialTheme.typography.titleLarge)
            }
        }
    }
}

/**
 * 创建 / 编辑页（设计 §4.6）：名称 + 已添加的聊天（左滑移出）+「添加聊天」+ 删除。
 * 创建模式右上「创建」一次性提交；编辑模式名称失焦 / 返回即存、成员增删立即提交（由 Host 做）。
 */
@Composable
internal fun ChatFolderEditScreen(
    creating: Boolean,
    name: String,
    onNameChange: (String) -> Unit,
    maxName: Int,
    onNameDone: () -> Unit,
    chats: List<ConversationEntity>,
    onAddChats: () -> Unit,
    onRemoveChat: (ConversationEntity) -> Unit,
    onDelete: (() -> Unit)?,
    onCreate: () -> Unit,
    createEnabled: Boolean,
    onBack: () -> Unit,
) {
    val c = IMTheme.colors
    val d = IMTheme.dimens
    var openedId by remember { mutableStateOf<String?>(null) }
    fun runes(s: String) = s.codePointCount(0, s.length)
    Column(Modifier.fillMaxSize().background(c.groupedBackground).statusBarsPadding()) {
        IMTopBar(
            title = stringResource(if (creating) R.string.folder_edit_title_create else R.string.folder_edit_title_edit),
            onLeft = onBack,
            actionText = if (creating) stringResource(R.string.common_create) else "",
            actionEnabled = createEnabled,
            onAction = if (creating) onCreate else null,
        )
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            IMSectionHeader(stringResource(R.string.folder_edit_name))
            IMSettingsGroup {
                Row(
                    Modifier.fillMaxWidth().background(c.cardBackground)
                        .defaultMinSize(minHeight = d.settingsRowHeight).padding(horizontal = d.space4),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.weight(1f)) {
                        if (name.isEmpty()) {
                            Text(stringResource(R.string.folder_edit_name_placeholder), color = c.textTertiary, style = MaterialTheme.typography.bodyLarge)
                        }
                        BasicTextField(
                            value = name,
                            onValueChange = { if (runes(it) <= maxName) onNameChange(it) },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(color = c.textPrimary),
                            cursorBrush = SolidColor(c.accent),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { onNameDone() }),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Spacer(Modifier.width(d.space2))
                    Text("${runes(name)} / $maxName", color = c.textSecondary, style = MaterialTheme.typography.bodySmall)
                }
            }
            IMSectionHeader(stringResource(R.string.folder_edit_chats_section, chats.size))
            if (chats.isNotEmpty()) {
                IMSettingsGroup {
                    chats.forEachIndexed { i, conv ->
                        if (i > 0) IMRowDivider(insetStart = d.space4 + 40.dp + d.space3)
                        val opened = openedId == conv.convId
                        SwipeActionRow(
                            actions = listOf(
                                SwipeAction(stringResource(R.string.folder_edit_remove), c.danger) { openedId = null; onRemoveChat(conv) },
                            ),
                            opened = opened,
                            onOpenedChange = { open -> openedId = if (open) conv.convId else null },
                        ) { ConvPlainRow(conv, checked = null, onClick = { if (opened) openedId = null }) }
                    }
                }
            }
            IMSettingsGroup(Modifier.padding(top = d.space4)) {
                IMSettingsRow(title = stringResource(R.string.folder_edit_add_chats), onClick = onAddChats)
            }
            IMSectionFooter(stringResource(R.string.folder_edit_remove_hint))
            if (onDelete != null) {
                IMSettingsGroup(Modifier.padding(top = d.space4)) {
                    IMSettingsRow(title = stringResource(R.string.folder_edit_delete), onClick = onDelete, destructive = true)
                }
            }
            Spacer(Modifier.height(d.sectionGap))
        }
    }
}

/** 会话行（头像 + 显示名），checked != null 时右侧画勾选态（选择器用）。 */
@Composable
private fun ConvPlainRow(conv: ConversationEntity, checked: Boolean?, onClick: () -> Unit) {
    val c = IMTheme.colors
    val d = IMTheme.dimens
    val title = com.libeyond.imandroid.data.Forward.titleOf(conv)
    Row(
        Modifier.fillMaxWidth().background(c.cardBackground).clickable(onClick = onClick)
            .defaultMinSize(minHeight = 60.dp).padding(horizontal = d.space4, vertical = d.space2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IMAvatar(
            displayName = title,
            seed = if (conv.isGroup) conv.convId else conv.peerUid.ifBlank { conv.convId },
            avatarUrl = conv.avatarUrl, size = 40.dp,
        )
        Spacer(Modifier.width(d.space3))
        Text(title, color = c.textPrimary, style = MaterialTheme.typography.bodyLarge, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (checked == true) {
            androidx.compose.foundation.Image(
                Lucide.Check, null, Modifier.size(20.dp),
                colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(c.accent),
            )
        }
    }
}

/**
 * 聊天文件夹「添加聊天」的会话多选页：全部会话（含搜索），已在夹内的预勾选，右上「完成」交回最终选中集。
 * 不复用转发选择器（那是「转发」语义：≤9 个、无预选、选完即发）。
 */
@Composable
internal fun ChatFolderPickerScreen(
    conversations: List<ConversationEntity>,
    selected: Set<String>,
    onToggle: (ConversationEntity) -> Unit,
    onDone: () -> Unit,
    onBack: () -> Unit,
) {
    val c = IMTheme.colors
    val d = IMTheme.dimens
    var query by remember { mutableStateOf("") }
    val shown = remember(conversations, query) {
        conversations.filter { ListSearch.matches(query, listOf(com.libeyond.imandroid.data.Forward.titleOf(it), it.peerUid)) }
    }
    Column(Modifier.fillMaxSize().background(c.groupedBackground).statusBarsPadding()) {
        IMTopBar(
            title = stringResource(R.string.folder_picker_title),
            onLeft = onBack,
            actionText = stringResource(R.string.common_done),
            onAction = onDone,
        )
        IMSearchField(
            value = query, onValueChange = { query = it }, placeholder = stringResource(R.string.common_search),
            modifier = Modifier.fillMaxWidth().padding(horizontal = d.space4, vertical = d.space2),
        )
        LazyColumn(Modifier.fillMaxSize()) {
            items(shown, key = { it.convId }) { conv ->
                ConvPlainRow(conv, checked = conv.convId in selected, onClick = { onToggle(conv) })
                IMRowDivider(insetStart = d.space4 + 40.dp + d.space3)
            }
        }
    }
}
