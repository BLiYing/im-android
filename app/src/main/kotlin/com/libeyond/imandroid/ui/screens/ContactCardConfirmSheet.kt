package com.libeyond.imandroid.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.libeyond.imandroid.R
import com.libeyond.imandroid.data.CardContent
import com.libeyond.imandroid.data.ContactCardSend
import com.libeyond.imandroid.data.DisplayName
import com.libeyond.imandroid.sdk.api.FriendEntry
import com.libeyond.imandroid.ui.components.blockPointerInput
import com.libeyond.imandroid.ui.theme.IMTheme

/**
 * 发名片的二次确认（iOS `presentContactSendConfirmForCards:`）：把**即将发出的那张卡先给用户看**。
 *
 * 版式同 iOS：标题（17sp 半粗，最多 2 行）→ 首张名片的预览卡（**与收方气泡里的同一个 [ContactCardContent]**，
 * 底色 `bubbleThem`、气泡圆角）→ 其余折叠成一行摘要（13sp 次要色）→ 「发送」主按钮 + 「取消」。
 * 底部卡片、半高；点遮罩 / 返回键 = 取消，回到聊天页。
 *
 * 预览卡写的是**公开昵称**（[DisplayName.publicNameOfFriend]），与最终发出的 JSON 同一份——
 * 预览里露备注、发出去却是昵称，用户会以为发错了。
 */
@Composable
internal fun ContactCardConfirmSheet(
    friends: List<FriendEntry>,
    /** 目标会话显示名（标题里的「X」）。 */
    to: String,
    onSend: () -> Unit,
    onCancel: () -> Unit,
) {
    val c = IMTheme.colors
    val first = friends.firstOrNull() ?: return
    val previewJson = remember(first) {
        CardContent.encodeContact(
            uid = first.userId, username = first.username,
            nickname = DisplayName.publicNameOfFriend(first), avatarUrl = first.avatarUrl,
        )
    }
    val summary = remember(friends) { ContactCardSend.restSummary(friends.map(DisplayName::publicNameOfFriend)) }
    BackHandler(onBack = onCancel)
    Box(Modifier.fillMaxSize().blockPointerInput()) {
        // 遮罩：点它 = 取消
        Box(
            Modifier.fillMaxSize().background(c.overlay)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onCancel),
        )
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .clip(RoundedCornerShape(topStart = IMTheme.dimens.radiusCard, topEnd = IMTheme.dimens.radiusCard))
                .background(c.pageBackground)
                // 吞掉点击：卡片空白处不能漏给遮罩把自己关了
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .navigationBarsPadding()
                .padding(horizontal = SHEET_H_PADDING, vertical = SHEET_V_PADDING),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                ContactCardSend.title(friends.size, to),
                color = c.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(20.dp))
            Box(
                Modifier.clip(RoundedCornerShape(IMTheme.appearance.bubbleRadius))
                    .background(c.bubbleThem).padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                ContactCardContent(previewJson)
            }
            if (summary.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    summary, color = c.textSecondary, fontSize = 13.sp,
                    textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(24.dp))
            Box(
                Modifier.fillMaxWidth().height(SEND_BUTTON_HEIGHT).clip(RoundedCornerShape(12.dp))
                    .background(c.accent).clickable(onClick = onSend),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    stringResource(R.string.common_send), color = c.onAccent,
                    fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.height(8.dp))
            Box(
                Modifier.fillMaxWidth().height(CANCEL_BUTTON_HEIGHT).clickable(onClick = onCancel),
                contentAlignment = Alignment.Center,
            ) {
                Text(stringResource(R.string.common_cancel), color = c.accent, fontSize = 17.sp)
            }
        }
    }
}

/** 取自 iOS：左右 24、顶 24；发送钮高 50 圆角 12、取消高 44、与底边留 12。 */
private val SHEET_H_PADDING = 24.dp
private val SHEET_V_PADDING = 24.dp
private val SEND_BUTTON_HEIGHT = 50.dp
private val CANCEL_BUTTON_HEIGHT = 44.dp
