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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.libeyond.imandroid.data.db.MessageEntity
import com.libeyond.imandroid.ui.components.TimeFormat
import com.libeyond.imandroid.ui.components.IMAvatar
import com.libeyond.imandroid.ui.theme.IMTheme

// 气泡与分隔行。从 ChatScreen 拆出（CODING_STYLE §7②）：
// 那个文件 526 行触了体量 WARN，规矩是**接近上限就规划拆分**，不等触顶。

@Composable
internal fun DaySeparator(ts: Long) {
    val c = IMTheme.colors
    val d = IMTheme.dimens
    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
        Box(
            // 胶囊高 24、圆角 = 半高（UI_SPEC §3，iOS _datePillHeight/_datePill.cornerRadius 同值）
            modifier = Modifier
                .height(d.datePillHeight)
                .clip(RoundedCornerShape(d.datePillHeight / 2))
                .background(c.datePillBackground)
                .padding(horizontal = d.space3),
            contentAlignment = Alignment.Center,
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
    /** 媒体地址补全用的当前 host。 */
    host: String = "",
    useTls: Boolean = false,
    timestamp: Long,
    senderName: String?,
    sending: Boolean = false,
    failed: Boolean = false,
    delivered: Boolean = false,
    read: Boolean = false,
    onRetry: (() -> Unit)? = null,
    /** 群聊里对方的消息要占一条 30dp 头像列（即使本条不画头像，也得占位）。 */
    reserveAvatarColumn: Boolean = false,
    /** 本条是否真的画头像（连续段的最后一条才画，见 [showsSenderAvatar]）。 */
    showAvatar: Boolean = false,
    /** 头像取色种子——用 uid 不用昵称，改昵称不该换颜色。 */
    avatarSeed: String = "",
) {
    val c = IMTheme.colors
    val d = IMTheme.dimens
    val appearance = IMTheme.appearance
    // 气泡最大宽是**内容区的比例**不是固定 dp（UI_SPEC §3）：固定值在窄机上过宽、宽机上过窄。
    // BoxWithConstraints 才能拿到本行可用宽度并按比例折算。
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
    val bubbleMax = bubbleMaxWidth(maxWidth, d.bubbleMaxWidthFraction)
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
        // —— 头像列（UI_SPEC §3：12 + 30 + 6 = iOS 的 _leading.constant 48）——
        // **底对齐气泡底**，不是顶对齐：多行气泡时头像贴在最后一行旁边，与 iOS/Web 一致。
        if (reserveAvatarColumn) {
            // **这里不再加左边距**：消息列表本身的横向内边距就是 chatAvatarLeading（见 ChatScreen），
            // 在这儿再加一次会把整列右推 12dp（实测头像左边距 24 而非 12、气泡左缘 60 而非 48）。
            Box(
                modifier = Modifier.size(d.chatAvatar).align(Alignment.Bottom),
            ) {
                if (showAvatar) {
                    IMAvatar(
                        displayName = senderName.orEmpty().ifBlank { avatarSeed },
                        seed = avatarSeed,
                        avatarUrl = "",   // TODO 群成员头像 URL 尚无本地缓存，先走首字母色块
                        size = d.chatAvatar,
                    )
                }
            }
            Spacer(Modifier.width(d.chatAvatarGap))
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
                    .widthIn(max = bubbleMax)
                    .clip(RoundedCornerShape(appearance.bubbleRadius))
                    .background(if (mine) c.bubbleMe else c.bubbleThem)
                    .then(
                        if (onLongPress != null && !recalled) {
                            Modifier.combinedClickable(onClick = {}, onLongClick = onLongPress)
                        } else Modifier
                    )
                    .padding(horizontal = d.bubblePaddingH, vertical = d.bubblePaddingV),
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
                    val isMedia = msg != null && msg.contentType in MEDIA_TYPES
                    when {
                        recalled -> Text(
                            text = "你撤回了一条消息",
                            color = c.textTertiary,
                            fontSize = appearance.chatFontSize,
                        )
                        isMedia -> MediaContent(msg!!, host, useTls)
                        else -> Text(
                            text = text,
                            color = c.textPrimary,
                            fontSize = appearance.chatFontSize,
                        )
                    }
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
    } // BoxWithConstraints（量本行可用宽 → 气泡最大宽按比例）
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


/**
 * 群内发送者头像**只挂在连续段的最后一条**上（与 iOS `IMBubbleCell` 的 gutter、
 * Web `.avatar-col` 一致）；段内其余行用等宽占位撑住，保证同一段所有气泡左缘齐平。
 *
 * 抽成纯函数是为了能单测——「只在段末挂」这条错了肉眼很难发现：
 * 段里每条都挂头像看着也"正常"，只是啰嗦；而**忘了占位**才会让气泡左缘参差，
 * 那时人多半会去调 padding 而不是想到这里。
 */
internal fun showsSenderAvatar(rows: List<ChatRow>, index: Int, myUid: String, isGroup: Boolean): Boolean {
    if (!isGroup) return false
    val cur = rows.getOrNull(index) as? ChatRow.Confirmed ?: return false
    if (cur.msg.sender.isBlank() || cur.msg.sender == myUid) return false
    val next = rows.getOrNull(index + 1)
    return !(next is ChatRow.Confirmed && next.msg.sender == cur.msg.sender)
}

/**
 * 气泡最大宽 = 可用内容区宽 × 比例（UI_SPEC §3）。
 *
 * 抽成纯函数**只为一件事**：让「它是比例、不是固定 dp」这条不变式可以被单测钉住。
 * 本端一度写成 `widthIn(max = 280.dp)`，在 360dp 宽的机器上占 78%、411dp 上占 68%，
 * 两头都不对；而 iOS（multiplier 0.75）和 Web（`.row` 72%）从一开始就是比例。
 * 固定值的坏处在模拟器单一机型上**看不出来**，所以必须靠测试而不是靠肉眼。
 */
internal fun bubbleMaxWidth(available: Dp, fraction: Float): Dp = available * fraction

/** 走媒体渲染而不是纯文本的内容类型。 */
private val MEDIA_TYPES = setOf("image", "video", "voice", "file")
