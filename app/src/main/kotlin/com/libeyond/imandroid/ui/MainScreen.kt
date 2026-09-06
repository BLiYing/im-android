package com.libeyond.imandroid.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.Image
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.MessageCircle
import com.composables.icons.lucide.User
import com.composables.icons.lucide.Users
import com.libeyond.imandroid.sdk.IMClient
import com.libeyond.imandroid.sdk.ws.ConnState
import com.libeyond.imandroid.data.db.ConversationEntity
import com.libeyond.imandroid.ui.screens.ConversationListScreen
import com.libeyond.imandroid.ui.theme.IMTheme
import kotlinx.coroutines.flow.emptyFlow

/** 底部三个 Tab，与 iOS 的 TabBar / Web 的左栏切换同构。 */
private enum class Tab(val label: String) { Chats("消息"), Contacts("通讯录"), Me("我") }

/**
 * 主界面外壳：底部 Tab + 各 Tab 内容 + 二级页（聊天/找人/新的朋友/资料）。
 *
 * 从 AppRoot 拆出来（CODING_STYLE §7②）：AppRoot 只管「恢复会话 → 登录页 / 主界面」
 * 这一层阶段路由，主界面自己的导航不该混在里面。
 */
@Composable
fun MainScreen(client: IMClient, onLogout: () -> Unit) {
    val owner = client.uid.orEmpty()
    var tab by remember { mutableStateOf(Tab.Chats) }
    var openConv by remember { mutableStateOf<ConversationEntity?>(null) }

    val conversations by remember(owner) {
        if (owner.isEmpty()) emptyFlow() else client.repo.observeConversations(owner)
    }.collectAsState(initial = emptyList())

    val connState by client.socket.state.collectAsState()
    val totalUnread by remember(owner) {
        if (owner.isEmpty()) emptyFlow() else client.repo.observeTotalUnread(owner)
    }.collectAsState(initial = 0)

    // 进主界面就拉一次会话列表——WS 的 onConnected 也会拉，但那条路只在
    // 「本次冷启动真的新建了连接」时触发；会话已存活时进来不会有 onConnected。
    LaunchedEffect(owner) {
        if (owner.isNotEmpty()) client.messages.refreshConversations()
    }

    // —— 二级页：聊天（占满全屏，不显 Tab 栏）——
    val conv = openConv
    if (conv != null) {
        ChatHost(client = client, conv = conv, onBack = { openConv = null })
        return
    }

    Column(Modifier.fillMaxSize().background(IMTheme.colors.groupedBackground)) {
        Box(Modifier.weight(1f)) {
            when (tab) {
                Tab.Chats -> ConversationListScreen(
                    conversations = conversations,
                    onOpen = { openConv = it },
                    onSettings = { tab = Tab.Me },
                    connected = connState == ConnState.Connected,
                )
                Tab.Contacts -> ContactsHost(client = client, onOpenChat = { openConv = it })
                Tab.Me -> MeHost(client = client, onLogout = onLogout)
            }
        }
        BottomBar(current = tab, unread = totalUnread, onSelect = { tab = it })
    }
}

@Composable
private fun BottomBar(current: Tab, unread: Int, onSelect: (Tab) -> Unit) {
    val c = IMTheme.colors
    Column {
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(c.separator))
        Row(
            modifier = Modifier.fillMaxWidth().background(c.surface).navigationBarsPadding(),
        ) {
            Tab.entries.forEach { t ->
                val selected = t == current
                Column(
                    modifier = Modifier.weight(1f).clickable { onSelect(t) }.padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box {
                        Image(
                            imageVector = when (t) {
                                Tab.Chats -> Lucide.MessageCircle
                                Tab.Contacts -> Lucide.Users
                                Tab.Me -> Lucide.User
                            },
                            contentDescription = t.label,
                            modifier = Modifier.size(22.dp),
                            colorFilter = ColorFilter.tint(if (selected) c.accent else c.textTertiary),
                        )
                        if (t == Tab.Chats && unread > 0) {
                            Box(
                                Modifier.align(Alignment.TopEnd)
                                    .size(8.dp)
                                    .background(c.unreadBadge, androidx.compose.foundation.shape.CircleShape),
                            )
                        }
                    }
                    Text(
                        text = t.label,
                        color = if (selected) c.accent else c.textTertiary,
                        fontSize = 10.sp,
                    )
                }
            }
        }
    }
}
