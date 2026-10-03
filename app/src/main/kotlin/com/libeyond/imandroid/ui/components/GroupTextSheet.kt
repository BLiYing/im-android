package com.libeyond.imandroid.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.libeyond.imandroid.R
import com.libeyond.imandroid.ui.theme.IMTheme

/**
 * 群公告 / 群简介 / 大群说明的**只读全文底部弹窗**，对齐 iOS `IMGroupTextViewController`
 * （`UIModalPresentationPageSheet`）：顶部「标题 + 关闭」，副标题（公告的发布时间，简介没有），
 * 可滚动、可选中复制的正文，底部「复制全文」。
 *
 * **群资料页与聊天页共用这一个**（iOS 两处也是同一个 VC）——聊天页点公告横幅、群资料页点公告/简介行，
 * 看到的必须是同一张弹窗，别各画一份。用 [Dialog] 而不是挂在调用方 Box 里：两处的父布局不一样，
 * 窗口级弹层不依赖宿主。
 */
@Composable
fun GroupTextSheet(
    title: String,
    body: String,
    onDismiss: () -> Unit,
    subtitle: String = "",
) {
    val c = IMTheme.colors
    val d = IMTheme.dimens
    val clipboard = LocalClipboardManager.current
    // 吐司画在弹窗窗口里：Dialog 是独立窗口，宿主页的吐司会被它盖住
    var copied by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier.fillMaxSize().clickable(
                interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss,
            ),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Column(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                    .background(c.groupedBackground)
                    // 吞掉点击，别让弹窗本体的点击穿透到遮罩把自己关了
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                    .navigationBarsPadding()
                    .padding(horizontal = d.space4),
            ) {
                Box(
                    Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp)
                        .size(width = 36.dp, height = 4.dp).clip(RoundedCornerShape(2.dp)).background(c.textTertiary),
                )
                Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        title, color = c.textPrimary, style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "✕", color = c.textTertiary, style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.clickable(onClick = onDismiss).padding(start = 12.dp, top = 2.dp, bottom = 2.dp),
                    )
                }
                if (subtitle.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(subtitle, color = c.textSecondary, style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(14.dp))
                Box(Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 420.dp).verticalScroll(rememberScrollState())) {
                    SelectionContainer {
                        Text(body, color = c.textPrimary, style = MaterialTheme.typography.bodyLarge)
                    }
                }
                Spacer(Modifier.height(12.dp))
                Box(Modifier.fillMaxWidth().height(0.5.dp).background(c.separator))
                Text(
                    stringResource(R.string.chat_reader_copy),
                    color = c.accent, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                        .clickable { clipboard.setText(AnnotatedString(body)); copied = true }
                        .padding(vertical = 12.dp),
                )
            }
            if (copied) IMToast(stringResource(R.string.common_copied)) { copied = false }
        }
    }
}
