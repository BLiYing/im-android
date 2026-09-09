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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.SendHorizontal
import com.libeyond.imandroid.ui.theme.IMTheme

// 聊天页底部输入栏。从 ChatScreen 拆出（CODING_STYLE §7②）：那个文件贴着 600 行硬闸，
// 而"怎么打字、怎么发"与"消息列表怎么滚"是两件事。逐行平移，无逻辑改动。

@Composable
internal fun Composer(
    /**
     * 输入框的**值 + 光标**。用 [TextFieldValue] 而不是裸 String 是 @提及要求的：
     * 「正在输入的 @查询词」是"光标前最近一个 @ 到光标之间"，没有光标就算不出来
     * （iOS 从 `selectedTextRange` 取、Web 从 `selectionStart` 取，本端同源）。
     */
    input: TextFieldValue,
    onInputChange: (TextFieldValue) -> Unit,
    onSend: () -> Unit,
    onPlus: () -> Unit,
    onInputFocus: () -> Unit,
    /** 输入栏**上方**的内联层（@成员面板）。没有就不画。 */
    above: (@Composable () -> Unit)? = null,
) {
    Column(Modifier.fillMaxWidth()) {
        above?.invoke()
        ComposerBar(input, onInputChange, onSend, onPlus, onInputFocus)
    }
}

@Composable
private fun ComposerBar(
    input: TextFieldValue,
    onInputChange: (TextFieldValue) -> Unit,
    onSend: () -> Unit,
    onPlus: () -> Unit,
    onInputFocus: () -> Unit,
) {
    val c = IMTheme.colors
    val d = IMTheme.dimens
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 单行态总高 56（UI_SPEC §4，iOS inputBar.heightAnchor 同值）；
            // 多行时允许长高，故用 heightIn(min) 而非 height。
            .heightIn(min = d.inputBarHeight)
            .background(c.surface)
            // 按钮距栏边 8（UI_SPEC §4，iOS plusButton leading）——移动端要给拇指留满宽，
            // 不走 Web 的 --space-4 页面节奏。
            .padding(horizontal = d.inputBarEdge, vertical = d.space2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 点击区 36（与 iOS plusButton 同）；图标本身 24。
        Box(
            modifier = Modifier.size(d.inputControl).clickable { onPlus() },
            contentAlignment = Alignment.Center,
        ) {
            Image(
                imageVector = Lucide.Plus,
                contentDescription = "更多",
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
                Text("发送消息…", color = c.textTertiary, fontSize = 15.sp)
            }
            BasicTextField(
                value = input,
                onValueChange = onInputChange,
                textStyle = TextStyle(color = c.textPrimary, fontSize = 15.sp),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(c.accent),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.width(d.space2))
        Box(
            modifier = Modifier
                .size(d.inputControl)
                .clip(CircleShape)
                .background(if (input.text.isNotBlank()) c.accent else c.neutralControl)
                .clickable(enabled = input.text.isNotBlank()) { onSend() },
            contentAlignment = Alignment.Center,
        ) {
            Image(
                imageVector = Lucide.SendHorizontal,
                contentDescription = "发送",
                modifier = Modifier.size(20.dp),
                colorFilter = ColorFilter.tint(c.onAccent),
            )
        }
    }
}
