package com.libeyond.imandroid.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import com.libeyond.imandroid.R
import com.libeyond.imandroid.data.MuteState
import com.libeyond.imandroid.data.PinnedAt
import com.libeyond.imandroid.data.db.ConversationEntity
import com.libeyond.imandroid.ui.components.MuteDurationSheet
import com.libeyond.imandroid.ui.components.rememberMuteTick
import com.libeyond.imandroid.sdk.IMClient
import com.libeyond.imandroid.sdk.logging.IMLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 群资料页「设置区」（置顶聊天 / 消息免打扰 / 我在本群的昵称 / 群备注 / 群公告·简介全文）
 * 的状态持有者，含两个编辑弹窗与全文弹窗的开关态。
 *
 * 从 `GroupInfoHost` 拆出（CODING_STYLE §7②）：那个文件是接线层且贴着 600 行硬闸，
 * 这一整块设置区自成一体——进页现拉一次（本地 `ConversationEntity` 没落 `remark` 这个
 * 字段，群资料页与单聊「备注名」用的是两套接口，见 `ConversationsApi.setRemark` 的注释），
 * 置顶/免打扰是**整体替换**三开关，群备注是独立接口、不动那三个值。
 */
@Composable
fun rememberGroupInfoSettings(client: IMClient, convId: String, scope: CoroutineScope): GroupInfoSettingsState =
    remember(convId) { GroupInfoSettingsState(client, convId, scope) }

class GroupInfoSettingsState internal constructor(
    private val client: IMClient,
    private val convId: String,
    private val scope: CoroutineScope,
) {
    private val pinnedState = mutableStateOf(false)
    private val pinnedAtState = mutableStateOf(0L)
    private val mutedState = mutableStateOf(false)
    /** 定时免打扰到期毫秒（第二批 NOTIFICATIONS_P1_DESIGN §5）。「消息免打扰」行点开时长菜单，同聊天信息页。 */
    private val muteUntilState = mutableStateOf(0L)
    private val muteSheetOpenState = mutableStateOf(false)
    private val markedUnreadState = mutableStateOf(false)
    private val remarkState = mutableStateOf("")
    private val editingMyNicknameState = mutableStateOf(false)
    private val editingRemarkState = mutableStateOf(false)
    private val noticeState = mutableStateOf<Pair<String, String>?>(null)

    val pinned: Boolean get() = pinnedState.value
    val muted: Boolean get() = MuteState.isMutedNow(mutedState.value, muteUntilState.value)
    val muteSheetOpen: Boolean get() = muteSheetOpenState.value
    val remark: String get() = remarkState.value
    val editingMyNickname: Boolean get() = editingMyNicknameState.value
    val editingRemark: Boolean get() = editingRemarkState.value
    val notice: Pair<String, String>? get() = noticeState.value

    /** 调用方在进页的 `LaunchedEffect` 里调一次。 */
    suspend fun load() {
        runCatching { client.conversationsApi.settings(convId) }
            .onSuccess { s ->
                pinnedState.value = s.pinnedAt > 0
                pinnedAtState.value = s.pinnedAt
                mutedState.value = s.muted
                muteUntilState.value = s.muteUntil
                markedUnreadState.value = s.markedUnread
                remarkState.value = s.remark
            }
            .onFailure { IMLog.tag("IM.Group").w("group_settings_failed") }
    }

    /** 置顶/免打扰**整体替换**三项：改一项也要把 `markedUnread` 原样带回，否则会顺手清掉。 */
    /** @param muteUntil 只有时长菜单选中时才传；其余留 null（省略），服务端保留未到期的原到期时间（PROTOCOL §6.10）。 */
    private fun push(newPinned: Boolean, newMuted: Boolean, muteUntil: Long? = null) {
        val pinnedAt = PinnedAt.next(newPinned, pinnedAtState.value)
        pinnedAtState.value = pinnedAt
        scope.launch {
            runCatching {
                client.conversationsApi.updateSettings(
                    convId,
                    pinnedAt = pinnedAt,
                    muted = newMuted,
                    markedUnread = markedUnreadState.value,
                    muteUntil = muteUntil,
                )
            }.onFailure { IMLog.tag("IM.Group").w("group_conv_settings_failed") }
            client.messages.refreshConversations()
        }
    }

    fun togglePinned(v: Boolean) { pinnedState.value = v; push(v, muted) }
    fun openMuteSheet() { muteSheetOpenState.value = true }
    fun dismissMuteSheet() { muteSheetOpenState.value = false }

    /** 时长菜单的结果：`null` = 取消免打扰，否则为到期毫秒（0 = 永久）。 */
    fun setMute(muteUntil: Long?) {
        muteSheetOpenState.value = false
        mutedState.value = muteUntil != null
        muteUntilState.value = muteUntil ?: 0
        push(pinned, muteUntil != null, muteUntil)
    }

    /**
     * 「消息免打扰」行右值（至… / 永久；未免打扰返回 null）。与 [muted] 同取墙钟，两处不会各说各话；
     * 到点重组由调用方的到期定时器负责（见 [rememberGroupMuteValueText]）。
     */
    fun muteValueText(): String? {
        val now = System.currentTimeMillis()
        return if (MuteState.isMutedNow(mutedState.value, muteUntilState.value, now)) MuteState.untilText(muteUntilState.value, now) else null
    }

    /** 喂给 [com.libeyond.imandroid.ui.components.rememberMuteTick] 的单元素会话表（只关心到期时刻）。 */
    fun tickSource(): List<ConversationEntity> =
        listOf(ConversationEntity(ownerUid = "", convId = convId, muted = mutedState.value, muteUntil = muteUntilState.value))

    /** 群备注（G1）：与置顶/免打扰三开关解耦的独立接口，不动那三个值。 */
    fun setRemark(v: String) {
        remarkState.value = v
        scope.launch {
            runCatching { client.conversationsApi.setRemark(convId, v) }
                .onFailure { IMLog.tag("IM.Group").w("group_remark_failed") }
            client.messages.refreshConversations()
        }
    }

    fun openMyNicknameEditor() { editingMyNicknameState.value = true }
    fun dismissMyNicknameEditor() { editingMyNicknameState.value = false }
    fun openRemarkEditor() { editingRemarkState.value = true }
    fun dismissRemarkEditor() { editingRemarkState.value = false }
    fun openNotice(title: String, content: String) { noticeState.value = title to content }
    fun dismissNotice() { noticeState.value = null }
}

/** 群资料页「消息免打扰」行右值：到期定时器驱动，到点自动从「至…」变回「关」。 */
@Composable
fun rememberGroupMuteValueText(settings: GroupInfoSettingsState): String {
    rememberMuteTick(settings.tickSource()) // 只负责到期 / 回前台时触发重组，取值走 muteValueText 的墙钟
    return settings.muteValueText() ?: stringResource(R.string.common_off)
}

/** 群资料页的免打扰时长菜单（与聊天信息页同一个 [MuteDurationSheet]）；未打开时不画。 */
@Composable
fun GroupMuteSheet(settings: GroupInfoSettingsState, title: String) {
    if (!settings.muteSheetOpen) return
    MuteDurationSheet(
        convTitle = title,
        showUnmute = settings.muted,
        onUnmute = { settings.setMute(null) },
        onSelect = { d -> settings.setMute(d.muteUntil()) },
        onDismiss = settings::dismissMuteSheet,
    )
}
