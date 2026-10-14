package com.libeyond.imandroid.data

import com.libeyond.imandroid.data.db.ConversationEntity
import com.libeyond.imandroid.sdk.protocol.FolderUpdateData
import com.libeyond.imandroid.sdk.protocol.FolderWire
import kotlin.math.abs
import kotlin.math.min

/**
 * 会话文件夹（聊天文件夹）：用户自建的手动分类，一个会话可在多个文件夹内、仍留在「全部」。
 * 服务端口径见 `IMServer/docs/PROTOCOL.md` §6.15、设计见 `IMServer/docs/design/CHAT_FOLDERS_DESIGN.md`。
 * 三端同口径：iOS `IMChatFolderLogic`、Web `chatFolders.ts`（共用向量 `docs/conformance/chat_folder_logic.json`）。
 */
data class ChatFolder(
    val id: String,
    val name: String,
    val position: Int,
    /** 成员会话 id（按加入先后）。 */
    val convIds: List<String>,
) {
    private val set: Set<String> by lazy(LazyThreadSafetyMode.NONE) { convIds.toHashSet() }

    fun contains(convId: String): Boolean = convId.isNotEmpty() && convId in set
}

/** 服务端下发的上限（随 GET/写响应的 `limits`）；未拿到时用设计默认值。 */
data class FolderLimits(
    val maxFolders: Int = 10,
    val maxConvs: Int = 100,
    val maxName: Int = 12,
)

/** 某用户全部文件夹的快照 + 单调版本。 */
data class FolderSnapshot(
    val version: Long = 0,
    val folders: List<ChatFolder> = emptyList(),
    val limits: FolderLimits = FolderLimits(),
) {
    fun folder(id: String?): ChatFolder? = if (id == null) null else folders.firstOrNull { it.id == id }

    /** 含该会话的全部文件夹（按 position 升序）。 */
    fun containing(convId: String): List<ChatFolder> = folders.filter { it.contains(convId) }
}

/** 文件夹 Tab 的未读角标：count=0 不显示；muted=true 表示主数值为 0、仅静音会话有未读（灰色）。 */
data class FolderBadge(val count: Int, val muted: Boolean)

/** 横滑判定结果（设计 §4.3）。 */
enum class FolderSwipe { None, NextTab, PrevTab, RowSwipe }

fun FolderWire.toChatFolder() = ChatFolder(id = id, name = name, position = position, convIds = convIds)

fun FolderUpdateData.toSnapshot(limits: FolderLimits = FolderLimits()) = FolderSnapshot(
    version = version,
    folders = folders.filter { it.id.isNotEmpty() && it.name.isNotEmpty() }
        .map { it.toChatFolder() }.sortedBy { it.position },
    limits = limits,
)

/** 纯逻辑（无 UI / 无 IO，单测直接钉）。 */
object ChatFolderLogic {
    /** 位移小于它不判定（避免点按抖动误切）。 */
    const val MIN_TRANSLATION = 8f

    /** 尾部区域占列表宽度的上限（按钮最多 4 个，展开几乎占满整行，须保留可用于切 Tab 的区域）。 */
    const val TAIL_MAX_RATIO = 0.4f

    /** 按会话顺序保序过滤出 folder 内的会话（folder=null 即「全部」，原样返回）。 */
    fun filter(convs: List<ConversationEntity>, folder: ChatFolder?): List<ConversationEntity> =
        if (folder == null) convs else convs.filter { folder.contains(it.convId) }

    /**
     * 某范围（folder=null = 全部）的 Tab 角标。口径（设计 §3.3）：未读**消息条数**之和；
     * 静音会话不计入主数值（@我 仍计 1，同 [TabUnread]）；手动「标为未读」计 1；只有静音会话有未读时灰显。
     */
    fun badge(convs: List<ConversationEntity>, folder: ChatFolder?, nowMs: Long): FolderBadge {
        var main = 0
        var mutedSum = 0
        for (c in convs) {
            if (folder != null && !folder.contains(c.convId)) continue
            val n = if (c.unread > 0) c.unread else if (c.markedUnread) 1 else 0
            if (n <= 0) continue
            if (MuteState.isMutedNow(c.muted, c.muteUntil, nowMs)) {
                if (c.mentionUnread) main += 1 else mutedSum += n
            } else {
                main += n
            }
        }
        return if (main > 0) FolderBadge(main, false) else FolderBadge(mutedSum, mutedSum > 0)
    }

    /** 尾部区域宽度 = min(行按钮组展开宽度, 列表宽 × 40%)。 */
    fun tailZoneWidth(listWidth: Float, actionsWidth: Float): Float =
        min(actionsWidth.coerceAtLeast(0f), listWidth * TAIL_MAX_RATIO)

    /**
     * 横滑判定（设计 §4.3 全表）。[translationX]<0 为向左滑；[startX] 为起手点 x；[rowOpen] 为已有行按钮展开。
     * Android 会话行**没有**左滑按钮（长按菜单承载全部行操作），调用方传 `actionsWidth = 0`，
     * 尾部区域恒为 0，向左滑只在「没有下一个 Tab」时才落到 [FolderSwipe.RowSwipe]（= 不处理）。
     */
    fun swipeOutcome(
        translationX: Float,
        startX: Float,
        listWidth: Float,
        actionsWidth: Float,
        hasNext: Boolean,
        hasPrev: Boolean,
        rowOpen: Boolean,
    ): FolderSwipe {
        if (abs(translationX) < MIN_TRANSLATION) return FolderSwipe.None
        if (translationX < 0) {
            if (rowOpen) return FolderSwipe.RowSwipe
            if (!hasNext) return FolderSwipe.RowSwipe
            val tail = tailZoneWidth(listWidth, actionsWidth)
            return if (tail > 0f && startX >= listWidth - tail) FolderSwipe.RowSwipe else FolderSwipe.NextTab
        }
        if (rowOpen) return FolderSwipe.None
        return if (hasPrev) FolderSwipe.PrevTab else FolderSwipe.None
    }

    /** Tab 下标：0 = 「全部」，i = 第 i 个文件夹；选中的文件夹已被删则回落 0。 */
    fun tabIndex(selectedId: String?, folders: List<ChatFolder>): Int {
        if (selectedId == null) return 0
        val i = folders.indexOfFirst { it.id == selectedId }
        return if (i < 0) 0 else i + 1
    }
}
