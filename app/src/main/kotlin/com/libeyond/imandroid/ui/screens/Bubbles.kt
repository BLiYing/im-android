package com.libeyond.imandroid.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.libeyond.imandroid.data.db.MessageEntity
import com.libeyond.imandroid.ui.components.TimeFormat
import com.libeyond.imandroid.ui.theme.IMTheme

// 气泡与分隔行。从 ChatScreen 拆出（CODING_STYLE §7②）：
// 那个文件 526 行触了体量 WARN，规矩是**接近上限就规划拆分**，不等触顶。

@Composable
internal fun DaySeparator(ts: Long) {
    val c = IMTheme.colors
    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(c.datePillBackground)
                .padding(horizontal = 10.dp, vertical = 3.dp),
        ) {
            Text(TimeFormat.dayLabel(ts), color = c.onMedia, fontSize = 11.sp)
        }
    }
}

@Composable
internal fun UnreadDividerRow() {
    val c = IMTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f).height(0.5.dp).background(c.separator))
        Text(
            text = "以下为新消息",
            color = c.textTertiary,
            fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        Box(Modifier.weight(1f).height(0.5.dp).background(c.separator))
    }
}


@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun Bubble(
    text: String,
    mine: Boolean,
    msg: MessageEntity? = null,
    onLongPress: (() -> Unit)? = null,
    timestamp: Long,
    senderName: String?,
    sending: Boolean = false,
    failed: Boolean = false,
    delivered: Boolean = false,
    read: Boolean = false,
    onRetry: (() -> Unit)? = null,
) {
    val c = IMTheme.colors
    val appearance = IMTheme.appearance
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
    ) {
        if (failed && onRetry != null) {
            // 红❗点击重发。放气泡外侧，不遮正文。
            Text(
                text = "❗",
                fontSize = 16.sp,
                modifier = Modifier.clickable { onRetry() }.padding(end = 4.dp),
            )
        }
        Column(horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
            if (!senderName.isNullOrBlank()) {
                Text(
                    text = senderName,
                    color = c.textSecondary,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(start = 4.dp, bottom = 2.dp),
                )
            }
            val recalled = (msg?.recalledAt ?: 0) > 0
            Box(
                modifier = Modifier
                    .widthIn(max = 280.dp)
                    .clip(RoundedCornerShape(appearance.bubbleRadius))
                    .background(if (mine) c.bubbleMe else c.bubbleThem)
                    .then(
                        if (onLongPress != null && !recalled) {
                            Modifier.combinedClickable(onClick = {}, onLongClick = onLongPress)
                        } else Modifier
                    )
                    .padding(horizontal = 10.dp, vertical = 7.dp),
            ) {
                Column(horizontalAlignment = Alignment.End) {
                    // 引用条：被引用消息的降级快照（发送时冻结，原消息删了仍可展示）
                    val snap = msg?.replySnapshot
                    if (!snap.isNullOrBlank() && !recalled) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .background(c.subtleFill)
                                .padding(horizontal = 6.dp, vertical = 4.dp),
                        ) {
                            Text(
                                text = localizeReplySnapshot(snap),
                                color = c.textSecondary,
                                fontSize = 11.sp,
                                maxLines = 2,
                            )
                        }
                        Spacer(Modifier.height(3.dp))
                    }
                    Text(
                        text = if (recalled) "你撤回了一条消息" else text,
                        color = if (recalled) c.textTertiary else c.textPrimary,
                        fontSize = appearance.chatFontSize,
                    )
                    Spacer(Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = TimeFormat.bubbleTime(timestamp),
                            color = if (mine) c.metaTime else c.textTertiary,
                            fontSize = 10.sp,
                        )
                        if (sending) {
                            Spacer(Modifier.width(3.dp))
                            Text("🕐", fontSize = 9.sp)
                        } else if (delivered) {
                            Spacer(Modifier.width(3.dp))
                            // 已读=绿双勾 / 未读=灰单勾，与 iOS/Web 同一表意
                            Text(
                                text = if (read) "✓✓" else "✓",
                                color = if (read) c.checkRead else c.textTertiary,
                                fontSize = 10.sp,
                            )
                        }
                    }
                }
            }
        }
    }
}


/**
 * 引用快照的**前缀 token 本地化**（PROTOCOL §4.3）。
 *
 * 服务端下发的是 `[image]` / `[file] 报表.xlsx` / `[voice] 0:12` 这种带前缀 token 的形式，
 * **只替换前缀，保留尾随的文件名/时长**。
 * `chat_record` / `contact` 由服务端预本地化下发，客户端只对存量裸 token 精确匹配兜底。
 */
internal fun localizeReplySnapshot(raw: String): String = when {
    raw == "[chat_record]" -> "[聊天记录]"
    raw == "[contact]" -> "[个人名片]"
    raw.startsWith("[image]") -> raw.replaceFirst("[image]", "[图片]")
    raw.startsWith("[video]") -> raw.replaceFirst("[video]", "[视频]")
    raw.startsWith("[file]") -> raw.replaceFirst("[file]", "[文件]")
    raw.startsWith("[voice]") -> raw.replaceFirst("[voice]", "[语音]")
    else -> raw
}
