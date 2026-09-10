package com.libeyond.imandroid.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.X
import com.libeyond.imandroid.data.ReplyNames
import com.libeyond.imandroid.data.db.MessageEntity
import com.libeyond.imandroid.ui.components.FileTypeIcon
import com.libeyond.imandroid.ui.rememberFrostedPainter
import com.libeyond.imandroid.ui.theme.IMTheme

/** 回复条出入场时长（iOS `IMReplyBar` 的 0.2s）。 */
private const val REPLY_BAR_ANIM_MS = 200

/**
 * 输入栏上方的回复条（十七条对齐 #14），照 iOS `IMReplyBar`：
 * **3dp 强调色竖条 + 36dp 缩略/类型图 + 两行（「回复 X」/ 摘要）+ ✕**。
 *
 * 点条本身跳到被引用的那条（此前点了没反应）；下滑或点 ✕ 取消。
 * 此前是气泡引用块直接搬过来用，竖条画了两遍、标题写的是公开昵称不是「回复 X」。
 */
@Composable
internal fun ReplyBar(
    replyTo: MessageEntity?,
    myUid: String,
    isGroup: Boolean,
    convTitle: String,
    localNameOf: (String) -> String?,
    memberNameOf: (String) -> String?,
    onJump: (Long) -> Unit,
    onCancel: () -> Unit,
) {
    // 退场动画期间 replyTo 已经是 null，内容得用最后一次的值画完，不然条子先变空再收起
    var held by remember { mutableStateOf(replyTo) }
    LaunchedEffect(replyTo) { if (replyTo != null) held = replyTo }
    AnimatedVisibility(
        visible = replyTo != null,
        enter = expandVertically(tween(REPLY_BAR_ANIM_MS)) + fadeIn(tween(REPLY_BAR_ANIM_MS)),
        exit = shrinkVertically(tween(REPLY_BAR_ANIM_MS)) + fadeOut(tween(REPLY_BAR_ANIM_MS)),
    ) {
        val m = replyTo ?: held ?: return@AnimatedVisibility
        ReplyBarContent(
            m = m,
            title = ReplyNames.replyBarTitle(
                sender = m.sender,
                myUid = myUid,
                isGroup = isGroup,
                localName = localNameOf(m.sender),
                memberName = memberNameOf(m.sender),
                fromNickname = m.fromNickname,
                convTitle = convTitle,
            ),
            onJump = onJump,
            onCancel = onCancel,
        )
    }
}

@Composable
private fun ReplyBarContent(m: MessageEntity, title: String, onJump: (Long) -> Unit, onCancel: () -> Unit) {
    val c = IMTheme.colors
    val cancel by rememberUpdatedState(onCancel)
    // 与气泡里的引用块**同一句摘要**：引用时看到的，发出去以后在气泡里显示的必须是同一句
    val preview = replyPreviewOf(m.contentType, m.content, m.fileName, m.caption)
    val frosted = rememberFrostedPainter(m.thumb)
    val fileName = quoteFileNameOf(preview)
    val glyph = quoteGlyphFor(preview)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(c.surface)
            // 下滑取消（iOS 在条上挂了下滑手势）。累计过阈值只触发一次
            .pointerInput(Unit) {
                val threshold = 24.dp.toPx()
                var dragged = 0f
                detectVerticalDragGestures(onDragStart = { dragged = 0f }) { _, dy ->
                    dragged += dy
                    if (dragged > threshold) {
                        dragged = Float.NEGATIVE_INFINITY
                        cancel()
                    }
                }
            }
            .clickable { onJump(m.convSeq) }
            .padding(start = 12.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(3.dp).height(36.dp).clip(RoundedCornerShape(1.5.dp)).background(c.accent))
        Spacer(Modifier.width(8.dp))
        // 缩略槽：真缩略 > 文件类型图 > 类型字形；纯文本不占这一格
        if (frosted != null || fileName != null || glyph != null) {
            Box(
                Modifier.size(36.dp).clip(RoundedCornerShape(6.dp)).background(c.subtleFill),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    frosted != null -> Image(
                        painter = frosted,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                    fileName != null -> FileTypeIcon(fileName, size = 36.dp)
                    glyph != null -> Image(
                        glyph, null, Modifier.size(18.dp),
                        colorFilter = ColorFilter.tint(c.textSecondary),
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                color = c.accent,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(preview, color = c.textSecondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(
            Modifier.padding(start = 8.dp, end = 12.dp).size(24.dp).clip(CircleShape).clickable(onClick = onCancel),
            contentAlignment = Alignment.Center,
        ) {
            Image(Lucide.X, "取消回复", Modifier.size(16.dp), colorFilter = ColorFilter.tint(c.textTertiary))
        }
    }
}
