package com.libeyond.imandroid.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Plus
import com.libeyond.imandroid.R
import com.libeyond.imandroid.ui.theme.IMTheme
import com.libeyond.imandroid.ui.voice.LocalVoiceRecorder
import com.libeyond.imandroid.ui.voice.VoiceHoldOverlay
import com.libeyond.imandroid.ui.voice.VoiceLockedRow
import com.libeyond.imandroid.ui.voice.VoiceMicOrSendButton
import com.libeyond.imandroid.ui.voice.VoiceRecordEvents
import com.libeyond.imandroid.ui.voice.VoiceRecordingRowContent
import com.libeyond.imandroid.voice.VoiceRecorder
import kotlinx.coroutines.flow.MutableStateFlow

// 聊天页底部输入栏。从 ChatScreen 拆出（CODING_STYLE §7②）：那个文件贴着 600 行硬闸，
// 而"怎么打字、怎么发"与"消息列表怎么滚"是两件事。逐行平移，无逻辑改动。
//
// 语音录制（VOICE_MESSAGE_DESIGN §5）接进来之后，右缘键从"静态发送键"变成
// "麦克风/发送键，按住进录制"；录制中 / 锁定中整条栏 morph，具体手势与三段 UI
// 都在 ui/voice/VoiceRecordUi.kt 里（同一份要跨很多状态判断，抽出去才不把这个文件顶穿 600 行）。

@Composable
internal fun Composer(
    convId: String,
    /**
     * 输入框的**值 + 光标**。用 [TextFieldValue] 而不是裸 String 是 @提及要求的：
     * 「正在输入的 @查询词」是"光标前最近一个 @ 到光标之间"，没有光标就算不出来
     * （iOS 从 `selectedTextRange` 取、Web 从 `selectionStart` 取，本端同源）。
     */
    input: TextFieldValue,
    onInputChange: (TextFieldValue) -> Unit,
    onSend: () -> Unit,
    /** 一段语音录完（松手发送 / 锁定态自动发送）。 */
    onSendVoice: (file: java.io.File, durationMs: Int, waveform: String?) -> Unit,
    /** 语音录制的各种边界提示（太短/权限/达上限……）。 */
    onToast: (String) -> Unit,
    onPlus: () -> Unit,
    onInputFocus: () -> Unit,
    /** 输入栏**上方**的内联层（@成员面板 / 粘贴图预览条）。没有就不画。 */
    above: (@Composable () -> Unit)? = null,
    /**
     * 正文之外还有东西可发（目前＝粘贴条上挂着待发图）。
     *
     * **发送键的可用态不能只看正文**：粘了一张图但一个字没打时，光看 `input.text` 会把
     * 发送键判成灰的，那张图就发不出去（对齐 iOS：粘贴图挂在 pasteBar 上，由输入栏那颗发送键统一发出）。
     */
    extraSendable: Boolean = false,
) {
    Column(Modifier.fillMaxWidth()) {
        above?.invoke()
        ComposerBar(convId, input, onInputChange, onSend, onSendVoice, onToast, onPlus, onInputFocus, extraSendable)
    }
}

@Composable
private fun ComposerBar(
    convId: String,
    input: TextFieldValue,
    onInputChange: (TextFieldValue) -> Unit,
    onSend: () -> Unit,
    onSendVoice: (java.io.File, Int, String?) -> Unit,
    onToast: (String) -> Unit,
    onPlus: () -> Unit,
    onInputFocus: () -> Unit,
    extraSendable: Boolean,
) {
    val c = IMTheme.colors
    val d = IMTheme.dimens
    // 正文有字 **或** 粘贴条上挂着图，就能发
    val canSend = input.text.isNotBlank() || extraSendable

    val recorder = LocalVoiceRecorder.current
    val recState by (recorder?.state ?: remember { MutableStateFlow(VoiceRecorder.State()) }).collectAsState()
    // 用户上滑锁定；或录音被打断/到点转锁定——统一用「有效锁定」判断该画哪种行（见 VoiceRecordUi.kt 里的讨论）
    var locked by remember(convId) { mutableStateOf(false) }
    var dragOffset by remember(convId) { mutableStateOf(Offset.Zero) }
    var rowWidthPx by remember { mutableStateOf(0f) }
    val effectiveLocked = locked || recState.phase == VoiceRecorder.Phase.Paused

    VoiceRecordEvents(
        convId = convId,
        locked = effectiveLocked,
        onToast = onToast,
        onSentVoice = onSendVoice,
        onReset = { locked = false; dragOffset = Offset.Zero },
        onLocked = { locked = true },
    )

    Box(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // 单行态总高 56（UI_SPEC §4，iOS inputBar.heightAnchor 同值）；
                // 多行时允许长高，故用 heightIn(min) 而非 height。
                .heightIn(min = d.inputBarHeight)
                .background(c.surface)
                // 取消阈值 = 这一整行宽度的 40%（VoiceRules.CANCEL_RATIO），量它才知道阈值在哪
                .onGloballyPositioned { rowWidthPx = it.size.width.toFloat() }
                // 按钮距栏边 8（UI_SPEC §4，iOS plusButton leading）——移动端要给拇指留满宽，
                // 不走 Web 的 --space-4 页面节奏。
                .padding(horizontal = d.inputBarEdge, vertical = d.space2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (effectiveLocked && recorder != null) {
                VoiceLockedRow(convId, recorder, recState, onToast, modifier = Modifier.weight(1f))
            } else {
                if (recState.phase == VoiceRecorder.Phase.Recording) {
                    VoiceRecordingRowContent(recState, dragOffset, rowWidthPx, modifier = Modifier.weight(1f))
                } else {
                    // 点击区 36（与 iOS plusButton 同）；图标本身 24。
                    Box(
                        modifier = Modifier.size(d.inputControl).clickable { onPlus() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Image(
                            imageVector = Lucide.Plus,
                            contentDescription = stringResource(R.string.common_more),
                            modifier = Modifier.size(24.dp),
                            colorFilter = ColorFilter.tint(c.textSecondary),
                        )
                    }
                    Spacer(Modifier.width(d.space2))
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onInputFocus() }
                            // 输入框圆角**跟随气泡圆角**（外观页可调）——iOS 就是这么做的，
                            // 之前写死 20 等于把用户的圆角设置在输入框上吞掉了（UI_SPEC §4）。
                            .clip(RoundedCornerShape(IMTheme.appearance.bubbleRadius))
                            .background(c.pageBackground)
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                    ) {
                        if (input.text.isEmpty()) {
                            Text(stringResource(R.string.chat_input_placeholder), color = c.textTertiary, fontSize = 15.sp)
                        }
                        BasicTextField(
                            value = input,
                            onValueChange = onInputChange,
                            textStyle = TextStyle(color = c.textPrimary, fontSize = 15.sp),
                            cursorBrush = SolidColor(c.accent),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                Spacer(Modifier.width(d.space2))
                // 空输入显 🎙（按住进录制），有输入/有粘贴图显 ➤——同一格原地换脸，绝不换边（草图 §02）。
                VoiceMicOrSendButton(
                    convId = convId,
                    showMic = input.text.isEmpty() && !extraSendable,
                    canSend = canSend,
                    onSend = onSend,
                    onToast = onToast,
                    onLocked = { locked = true },
                    onDrag = { dragOffset = it },
                    rowWidthPx = { rowWidthPx },
                )
            }
        }
        // 按住未锁定期间的悬浮层（大圆钮/呼吸环/锁钮），浮在输入栏之上、聊天内容零遮挡（草图 §03）。
        if (recState.phase == VoiceRecorder.Phase.Recording && !locked) {
            VoiceHoldOverlay(recState, dragOffset, modifier = Modifier.align(Alignment.BottomEnd))
        }
    }
}
