package com.libeyond.imandroid.ui.voice

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import com.libeyond.imandroid.data.db.MessageEntity
import com.libeyond.imandroid.sdk.logging.IMLog
import com.libeyond.imandroid.voice.VoiceRules

/**
 * 离开会播语音的页面时**就地暂停**（保留位点，回来接着听），iOS `pauseOnLeavingScreen`。
 *
 * 粒度只能落在「页面」：播放器是单例、不知道那条属于哪个页面；而气泡级的「离屏即停」是错的——
 * 同一页里把气泡滚出视野不该中断播放（微信也不中断）。
 * 当前调用点（新增会播语音的页面时**必须**跟着加一处）：聊天页、单聊详情、群资料、收藏页、合并转发记录页。
 * App 切后台不在此列——那个由 MainActivity 的生命周期观察者统一处理。
 */
@Composable
fun PauseVoiceOnLeave() {
    val player = LocalVoicePlayer.current ?: return
    DisposableEffect(player) { onDispose { player.pause() } }
}

/**
 * 接力连播（设计 §6.4）：本会话一条语音**自然播完**，自动接着播后面第一条未播放的对方语音；
 * 遇到非语音消息即停。只有聊天气泡参与（迷你播放器与试听 `relayable=false`）。判据在 [VoiceRules.nextRelay]。
 *
 * @param ordered 当前会话已确认消息（当前窗口）
 */
@Composable
fun VoiceRelayEffect(convId: String, ordered: List<MessageEntity>, myUid: String) {
    val player = LocalVoicePlayer.current ?: return
    val latest by rememberUpdatedState(ordered)
    LaunchedEffect(player, convId, myUid) {
        player.finished.collect { f ->
            if (!f.relayable || f.convId != convId) return@collect
            // 窗口流的顺序不作保证，按 conv_seq 排一次（只在播完那一刻排，不在重组热路径上）
            val next = VoiceRules.nextRelay(latest.sortedBy { it.convSeq }, f.id, myUid) { player.hasPlayed(convId, it) } ?: return@collect
            val id = VoiceRules.playableId(next) ?: return@collect
            player.toggle(id, convId, next.content, relayable = true, durationHintMs = (next.duration ?: 0).toLong()) { err ->
                // 接力是自动行为，失败不打断用户（不吐司），只留痕
                IMLog.tag("IM.Voice").w("voice_relay_failed", "err" to err)
            }
        }
    }
}
