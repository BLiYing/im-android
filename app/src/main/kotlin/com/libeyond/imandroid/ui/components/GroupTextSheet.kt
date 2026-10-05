package com.libeyond.imandroid.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Copy
import com.composables.icons.lucide.Lucide
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
    // 「已复制」吐司画在本浮层内（盖在遮罩之上，宿主页的吐司会被浮层挡住）
    var copied by remember { mutableStateOf(false) }
    // **不用 Dialog 窗口**：Android 15 的 Dialog 窗口内容区被状态栏高度下推、Compose 却按整屏量，
    // 底部「复制全文」被挤出屏幕（2026-10-05 PKD130 / Android 15 复现，Pixel 2 XL 无此问题）。
    // 与 IMCardSheet 一样画在组合树内、自己处理 insets。
    BackHandler(onBack = onDismiss)
    run {
        Box(
            Modifier.fillMaxSize().background(c.overlay).clickable(
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
                    .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime).only(WindowInsetsSides.Bottom))
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
                Box(Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(min = 120.dp, max = 420.dp).verticalScroll(rememberScrollState())) {
                    SelectionContainer {
                        Text(body, color = c.textPrimary, style = MaterialTheme.typography.bodyLarge)
                    }
                }
                Spacer(Modifier.height(12.dp))
                Box(Modifier.fillMaxWidth().height(0.5.dp).background(c.separator))
                Spacer(Modifier.height(10.dp))
                // 做成有底色的实心按钮：此前只是一行主色字，真机上被当成「没有复制按钮」
                Row(
                    Modifier.fillMaxWidth().height(44.dp)
                        .clip(RoundedCornerShape(d.radiusCard)).background(c.accent)
                        .clickable { clipboard.setText(AnnotatedString(body)); copied = true },
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Image(
                        imageVector = Lucide.Copy, contentDescription = null, modifier = Modifier.size(18.dp),
                        colorFilter = ColorFilter.tint(c.onAccent),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.chat_reader_copy),
                        color = c.onAccent, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center,
                    )
                }
                Spacer(Modifier.height(10.dp))
            }
            if (copied) IMToast(stringResource(R.string.common_copied)) { copied = false }
        }
    }
}
