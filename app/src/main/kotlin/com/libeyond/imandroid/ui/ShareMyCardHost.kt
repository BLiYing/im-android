package com.libeyond.imandroid.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.runtime.collectAsState
import com.libeyond.imandroid.R
import com.libeyond.imandroid.data.CardContent
import com.libeyond.imandroid.data.sendCard
import com.libeyond.imandroid.i18n.Str
import com.libeyond.imandroid.sdk.IMClient
import com.libeyond.imandroid.sdk.api.UserCard
import com.libeyond.imandroid.sdk.protocol.ContentType
import com.libeyond.imandroid.ui.components.IMToast
import com.libeyond.imandroid.ui.screens.ForwardPickerScreen
import kotlinx.coroutines.launch

/**
 * 「我 → 分享我的名片」（对齐 iOS `IMContactShare presentPickerFrom:…`）：开会话选择页（与转发同一页，可多选），
 * 对每个选中的会话发一条 `content_type=contact`，内容 `{"u","un","n","a"}`。选择页本身就是确认，不再二次确认。
 *
 * **用真实昵称，绝不用备注 / 本机显示名**——这句话会发给别人（隐私红线，IMServer `docs/UI.md`）。
 * 走 `sendCard`（带本地乐观行：服务端不回显自己的消息，不插的话目标会话要等下次同步才有这一条）。
 */
@Composable
fun ShareMyCardHost(client: IMClient, me: UserCard?, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val owner = client.uid.orEmpty()
    val conversations by remember(owner) { client.repo.observeConversations(owner) }.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var toast by remember { mutableStateOf<String?>(null) }
    Box(Modifier.fillMaxSize()) {
        ForwardPickerScreen(
            conversations = conversations,
            onCancel = onBack,
            onToast = { toast = it },
            onConfirm = { picked ->
                val card = me ?: client.cachedMyProfile()
                if (card == null || card.userId.isBlank()) {
                    toast = Str.s(R.string.chat_media_contact_card_incomplete)
                    return@ForwardPickerScreen
                }
                val json = CardContent.encodeContact(card.userId, card.username, card.nickname, card.avatarUrl)
                // 先发完、给「已发送」提示一个露脸的时间，**最后**才 onBack：onBack 会让本宿主离开组合，
                // 它的作用域随之取消——此前先 onBack，选了几个会话就只有前一两个收到、提示也永远不显示
                scope.launch {
                    picked.forEach { c ->
                        client.messages.sendCard(c.convId, if (c.isGroup) c.convId else c.peerUid, ContentType.CONTACT, json)
                    }
                    toast = if (picked.size == 1) Str.s(R.string.common_sent) else Str.p(R.plurals.common_sent_to_chats, picked.size, picked.size)
                    kotlinx.coroutines.delay(900)
                    onBack()
                }
            },
        )
        toast?.let { IMToast(it) { toast = null } }
    }
}
