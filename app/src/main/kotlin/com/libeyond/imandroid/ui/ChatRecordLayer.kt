package com.libeyond.imandroid.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.libeyond.imandroid.ui.screens.ChatRecordScreen
import com.libeyond.imandroid.ui.screens.MediaViewerScreen
import com.libeyond.imandroid.ui.screens.RecordMedia

/**
 * 聊天记录详情的导航态：一个页栈（每层是一条 chat_record 的 content）+ 页内点开的媒体。
 * 从 ChatHost 拆出——那份文件已经逼近 600 行。
 */
@Stable
internal class ChatRecordNav {
    var stack by mutableStateOf<List<String>>(emptyList())
        private set

    /** 详情页里点开的图/视频（null = 没在看）。 */
    var media by mutableStateOf<RecordMedia?>(null)

    val isOpen: Boolean get() = stack.isNotEmpty()

    fun push(content: String) {
        stack = stack + content
    }

    fun pop() {
        stack = stack.dropLast(1)
    }

    fun closeViewer() {
        media = null
    }
}

@Composable
internal fun rememberChatRecordNav(convId: String): ChatRecordNav = remember(convId) { ChatRecordNav() }

/**
 * 画栈顶那一页，以及它点开的查看器。
 *
 * 查看器**不带转发**（iOS 同）：记录里的一条没有 conv_seq，走不了转发那条路。
 */
@Composable
internal fun ChatRecordLayer(
    nav: ChatRecordNav,
    host: String,
    useTls: Boolean,
    onOpenUser: (String) -> Unit,
    onSave: (url: String, isVideo: Boolean) -> Unit,
) {
    val top = nav.stack.lastOrNull() ?: return
    // 按层数换 key：往里压一层是一页新列表，不能沿用上一层的滚动位置
    key(nav.stack.size) {
        ChatRecordScreen(
            content = top,
            host = host,
            useTls = useTls,
            onBack = { nav.pop() },
            onOpenRecord = { nav.push(it) },
            onOpenUser = onOpenUser,
            onOpenMedia = { nav.media = it },
        )
    }
    nav.media?.let { m ->
        MediaViewerScreen(
            contentType = m.contentType,
            content = m.content,
            poster = "",
            host = host,
            useTls = useTls,
            onSave = onSave,
            onClose = { nav.closeViewer() },
        )
    }
}
