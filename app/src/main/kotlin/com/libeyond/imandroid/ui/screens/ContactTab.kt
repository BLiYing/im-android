package com.libeyond.imandroid.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.libeyond.imandroid.R
import com.libeyond.imandroid.data.CardContent
import com.libeyond.imandroid.data.DetailTab
import com.libeyond.imandroid.data.DetailTabs
import com.libeyond.imandroid.data.db.MessageEntity
import com.libeyond.imandroid.ui.components.IMAvatar
import com.libeyond.imandroid.ui.components.TimeFormat
import com.libeyond.imandroid.ui.theme.IMTheme

/**
 * 详情页「名片」页签的数据（对齐 iOS `IMDetailTabKindContacts`）：本地消息里 `content_type=contact` 且解析出非空 uid 的，
 * **解析失败的整条排除**（不显示一行点不动的空壳）。最新在前。`null` = 还没扫完。
 */
class ContactTabData(
    val messages: List<Pair<MessageEntity, CardContent.Contact>>?,
    /** 名片上显示的名字：本机备注 > 快照昵称（备注不进名片内容，收方本地覆盖）。 */
    val nameOf: (CardContent.Contact) -> String,
    /** 群聊第三行「由 X 分享」里的 X：我自己是「你」，别人取群昵称（带备注覆盖）；单聊返回 null 不画这一行。 */
    val sharedByOf: (MessageEntity) -> String?,
    /** 点一行 → 进该用户资料页（自己进个人资料页）。 */
    val onOpen: (CardContent.Contact) -> Unit,
)

internal fun LazyListScope.contactTab(data: ContactTabData?) {
    val list = data?.messages
    when {
        list == null -> item { Hint(stringResource(R.string.common_loading)) }
        list.isEmpty() -> item { Hint(DetailTabs.emptyText(DetailTab.Contacts)) }
        else -> items(list, key = { it.first.convSeq }) { (m, card) ->
            ContactRow(card, data.nameOf(card), m.timestamp, data.sharedByOf(m)) { data.onOpen(card) }
        }
    }
}

/** 一行名片：44dp 头像 / 名字 / `@用户名`（没有就不画，**绝不退到 uid**）/ 右上时间 /（群聊）「由 X 分享」。 */
@Composable
private fun ContactRow(card: CardContent.Contact, name: String, timestamp: Long, sharedBy: String?, onClick: () -> Unit) {
    val c = IMTheme.colors
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = IMTheme.dimens.space4, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IMAvatar(displayName = name, seed = card.uid, size = 44.dp, avatarUrl = card.avatarUrl)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name, color = c.textPrimary, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (card.username.isNotBlank()) {
                Text("@" + card.username, color = c.textSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 1)
            }
            if (!sharedBy.isNullOrBlank()) {
                Text(
                    stringResource(R.string.contact_card_row_shared_by, sharedBy), color = c.accent,
                    style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Text(TimeFormat.conversationTime(timestamp), color = c.textTertiary, style = MaterialTheme.typography.labelSmall)
    }
}

/**
 * 「名片」页签的数据装配（两个宿主共用，免得各抄一遍）。名字取「本机备注 > 快照昵称 > 句柄」；
 * 群聊「由 X 分享」的 X：我自己 =「你」，别人取群里显示名（带备注覆盖），拿不到退「未命名」，**绝不露 uid**。
 */
@Composable
fun rememberContactTab(
    client: com.libeyond.imandroid.sdk.IMClient,
    convId: String,
    isGroup: Boolean,
    remarkOf: (String) -> String?,
    groupNameOf: (String) -> String?,
    onOpen: (CardContent.Contact) -> Unit,
): ContactTabData {
    val messages = com.libeyond.imandroid.ui.rememberContactMessages(client, convId)
    val me = client.uid.orEmpty()
    val you = stringResource(R.string.chat_detail_you)
    val unnamed = stringResource(R.string.common_unnamed_user)
    return ContactTabData(
        messages = messages,
        nameOf = { c -> remarkOf(c.uid)?.takeIf { it.isNotBlank() } ?: c.nickname.ifBlank { if (c.username.isNotBlank()) "@" + c.username else unnamed } },
        sharedByOf = { m ->
            if (!isGroup) null
            else if (m.sender == me) you
            else remarkOf(m.sender)?.takeIf { it.isNotBlank() } ?: groupNameOf(m.sender) ?: unnamed
        },
        onOpen = onOpen,
    )
}
