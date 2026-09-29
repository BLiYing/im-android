package com.libeyond.imandroid.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import com.libeyond.imandroid.data.MuteState
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
    private val mutedState = mutableStateOf(false)
    /** 定时免打扰到期毫秒（第二批 NOTIFICATIONS_P1_DESIGN §5）。群资料页暂未接时长菜单
     *  （本轮范围只做会话列表/聊天信息页/添加例外三个入口，见任务清单），这里只保证 [muted]
     *  这个读点走 [MuteState.isMutedNow]，不直接暴露原始 `muted`。 */
    private val muteUntilState = mutableStateOf(0L)
    private val markedUnreadState = mutableStateOf(false)
    private val remarkState = mutableStateOf("")
    private val editingMyNicknameState = mutableStateOf(false)
    private val editingRemarkState = mutableStateOf(false)
    private val noticeState = mutableStateOf<Pair<String, String>?>(null)

    val pinned: Boolean get() = pinnedState.value
    val muted: Boolean get() = MuteState.isMutedNow(mutedState.value, muteUntilState.value)
    val remark: String get() = remarkState.value
    val editingMyNickname: Boolean get() = editingMyNicknameState.value
    val editingRemark: Boolean get() = editingRemarkState.value
    val notice: Pair<String, String>? get() = noticeState.value

    /** 调用方在进页的 `LaunchedEffect` 里调一次。 */
    suspend fun load() {
        runCatching { client.conversationsApi.settings(convId) }
            .onSuccess { s ->
                pinnedState.value = s.pinnedAt > 0
                mutedState.value = s.muted
                muteUntilState.value = s.muteUntil
                markedUnreadState.value = s.markedUnread
                remarkState.value = s.remark
            }
            .onFailure { IMLog.tag("IM.Group").w("group_settings_failed") }
    }

    /** 置顶/免打扰**整体替换**三项：改一项也要把 `markedUnread` 原样带回，否则会顺手清掉。 */
    private fun push(newPinned: Boolean, newMuted: Boolean) {
        scope.launch {
            runCatching {
                client.conversationsApi.updateSettings(
                    convId,
                    pinnedAt = if (newPinned) System.currentTimeMillis() else 0,
                    muted = newMuted,
                    markedUnread = markedUnreadState.value,
                )
            }.onFailure { IMLog.tag("IM.Group").w("group_conv_settings_failed") }
            client.messages.refreshConversations()
        }
    }

    fun togglePinned(v: Boolean) { pinnedState.value = v; push(v, muted) }
    fun toggleMuted(v: Boolean) {
        mutedState.value = v
        if (!v) muteUntilState.value = 0
        push(pinned, v)
    }

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
