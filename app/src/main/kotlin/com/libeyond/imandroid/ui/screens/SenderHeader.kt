package com.libeyond.imandroid.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.libeyond.imandroid.data.SenderRun
import com.libeyond.imandroid.sdk.protocol.ContentType
import com.libeyond.imandroid.ui.theme.IMTheme

// 群聊气泡的「发送者头」：气泡外上方的昵称 + 角色徽标，以及决定它和头像挂在哪一条的连续段判据。
// 判据本体在 data/SenderRun（纯函数）；这里只负责把列表行映射过去、找邻行。

/**
 * 昵称 + 徽标（CHAT_UI_SKETCH §1.1）：昵称 13 半粗强调色、12 字截断；
 * 徽标 11 中粗、高 16、圆角 4、左右 6、距昵称 6。群主强调色，管理员次要色。
 */
@Composable
internal fun SenderHeader(name: String, badge: SenderRun.Badge?) {
    val c = IMTheme.colors
    Row(
        // 起点比气泡左缘多 2、与气泡间距 2
        modifier = Modifier.padding(start = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = SenderRun.clampName(name),
            color = c.accent,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (badge == null) return@Row
        Spacer(Modifier.width(6.dp))
        val (fg, bg) = when (badge) {
            SenderRun.Badge.Owner -> c.accent to c.accent.copy(alpha = 0.14f)
            SenderRun.Badge.Admin -> c.textSecondary to c.separator.copy(alpha = 0.5f)
        }
        Box(
            modifier = Modifier.height(16.dp).clip(RoundedCornerShape(4.dp)).background(bg)
                .padding(horizontal = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(badge.label, color = fg, fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        }
    }
}

/**
 * 群里对方的消息**只在连续段首条显示昵称**（iOS `isFirstInSenderRun:`）。
 *
 * 本端此前每条都显——连发五条就是五行一样的名字（十七条对齐 #15）。
 */
internal fun showsSenderName(rows: List<ChatRow>, index: Int, myUid: String, isGroup: Boolean): Boolean {
    val cur = othersGroupItem(rows, index, myUid, isGroup) ?: return false
    val prev = runItemOf(neighborRow(rows, index, -1), myUid) ?: return true
    return !SenderRun.sameRun(prev, cur)
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
    val cur = othersGroupItem(rows, index, myUid, isGroup) ?: return false
    val next = runItemOf(neighborRow(rows, index, +1), myUid) ?: return true
    return !SenderRun.sameRun(next, cur)
}

/**
 * 群里对方发的普通消息**与一组图**才有发送者头；其余（单聊 / 自己 / 系统 / 发送者缺失）返回 null。
 *
 * 宫格行此前不在这里：接收端的九宫格既不占头像列也不画名字，左缘比别的气泡少 36dp，
 * 发送者是谁一眼看不出来（iOS `IMAlbumCell` 继承 `IMMessageCell` 的 gutter，天然有）。
 */
private fun othersGroupItem(rows: List<ChatRow>, index: Int, myUid: String, isGroup: Boolean): SenderRun.Item? {
    if (!isGroup) return null
    val item = when (val cur = rows.getOrNull(index)) {
        is ChatRow.Confirmed -> if (cur.msg.contentType == ContentType.SYSTEM) null else runItemOf(cur, myUid)
        is ChatRow.Album -> runItemOf(cur, myUid)
        else -> null
    } ?: return null
    if (item.sender.isBlank() || item.sender == myUid) return null
    return item
}

/**
 * 相邻的那一行，**跳过「以下为新消息」分割线**：它只是插进来的一条线，不是一条消息，
 * 不该把同一个人的连发拆成两段（iOS 的分割线画在 cell 里，根本不占行）。
 * 日期行**不跳**——跨天本来就该断段（iOS `sameSenderRunAs:` 判了同一天）。
 */
private fun neighborRow(rows: List<ChatRow>, index: Int, step: Int): ChatRow? {
    var j = index + step
    while (rows.getOrNull(j) is ChatRow.UnreadDivider) j += step
    return rows.getOrNull(j)
}

/** 行 → 连续段判据的输入。日期行等非消息行返回 null（= 断段）。 */
private fun runItemOf(row: ChatRow?, myUid: String): SenderRun.Item? = when (row) {
    is ChatRow.Confirmed -> SenderRun.Item(
        sender = row.msg.sender,
        system = row.msg.contentType == ContentType.SYSTEM,
        recalled = (row.msg.recalledAt ?: 0) > 0,
    )
    // 待发行必然是我发的
    is ChatRow.Pending -> SenderRun.Item(myUid)
    // 一组图必然同一个人发的，取首格；首格还在传就是我
    is ChatRow.Album -> when (val f = row.members.first()) {
        is AlbumMember.Sent -> SenderRun.Item(f.msg.sender)
        is AlbumMember.Sending -> SenderRun.Item(myUid)
    }
    else -> null
}
