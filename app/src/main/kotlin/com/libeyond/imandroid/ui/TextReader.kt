package com.libeyond.imandroid.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.composables.icons.lucide.Copy
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.X
import com.libeyond.imandroid.R
import com.libeyond.imandroid.data.LongText
import com.libeyond.imandroid.data.Mention
import com.libeyond.imandroid.data.db.MessageEntity
import com.libeyond.imandroid.ui.screens.chatBodyText
import com.libeyond.imandroid.ui.theme.IMTheme

/**
 * 全文阅读页（对齐 iOS `IMTextReaderViewController`）：全屏；顶栏 ✕ / 「全文 · 约 N 字」/ A− A＋ / 复制全文。
 * 正文可选中，@ 高亮（走落库的片段）；**不识别链接、不做会话内搜索高亮**（阅读页拿不到关键词，iOS 同）。
 * 字号 = 聊天字号 + 档位，档位 −1…+3 共 5 档、起点 0，到头对应钮禁用，不持久化。
 */
@Composable
fun TextReader(msg: MessageEntity, chatFontSize: Float, onToast: (String) -> Unit, onDismiss: () -> Unit) {
    val c = IMTheme.colors
    val clipboard = LocalClipboardManager.current
    var step by remember { mutableStateOf(0) }
    val spans = remember(msg.mentionSpans) { Mention.parseSpans(msg.mentionSpans) }
    val label = stringResource(R.string.chat_text_approx_chars, LongText.countLabel(LongText.charCount(msg.content)))
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(c.pageBackground).statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.height(48.dp).clickable(onClick = onDismiss).padding(horizontal = 16.dp), Alignment.Center) {
                    Icon(Lucide.X, stringResource(R.string.common_close), tint = c.textPrimary)
                }
                Text(
                    stringResource(R.string.chat_reader_title, label), color = c.textPrimary, fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, modifier = Modifier.weight(1f),
                )
                StepButton("A－", stringResource(R.string.chat_reader_font_smaller), step > -1) { step-- }
                StepButton("A＋", stringResource(R.string.chat_reader_font_larger), step < 3) { step++ }
                Box(
                    Modifier.height(48.dp).clickable {
                        clipboard.setText(AnnotatedString(msg.content))
                        onToast(com.libeyond.imandroid.i18n.Str.s(R.string.common_copied_full_text))
                    }.padding(horizontal = 14.dp),
                    Alignment.Center,
                ) { Icon(Lucide.Copy, stringResource(R.string.chat_reader_copy), tint = c.textPrimary) }
            }
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(c.separator))
            SelectionContainer(Modifier.weight(1f)) {
                Text(
                    text = chatBodyText(
                        text = msg.content, spans = spans, memberNames = emptyMap(), searchNeedle = "",
                        highlightBackground = c.accentSoft, mentionColor = c.link, onTapMention = null,
                    ),
                    color = c.textPrimary, fontSize = (chatFontSize + step).sp,
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp, 16.dp, 14.dp, 24.dp),
                )
            }
        }
    }
}

@Composable
private fun StepButton(glyph: String, desc: String, enabled: Boolean, onClick: () -> Unit) {
    val c = IMTheme.colors
    Box(
        Modifier.height(48.dp).then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier).padding(horizontal = 10.dp),
        Alignment.Center,
    ) {
        Text(glyph, color = if (enabled) c.textPrimary else c.textTertiary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }
}
