package com.libeyond.imandroid.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import com.libeyond.imandroid.sdk.logging.IMLog
import com.libeyond.imandroid.sdk.protocol.ContentType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import com.libeyond.imandroid.data.MessageAction
import com.libeyond.imandroid.data.MessageActions
import com.libeyond.imandroid.data.Presence
import com.libeyond.imandroid.data.db.MessageEntity
import com.libeyond.imandroid.sdk.protocol.MsgOp
import com.libeyond.imandroid.ui.components.ActionSheet
import com.libeyond.imandroid.ui.components.SheetItem
import com.libeyond.imandroid.data.db.ConversationEntity
import com.libeyond.imandroid.sdk.IMClient
import com.libeyond.imandroid.ui.screens.ChatScreen
import com.libeyond.imandroid.ui.screens.buildChatRows
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** typing 上报节流：每次按键都发是错的，服务端要给全体成员中继。 */
private const val TYPING_THROTTLE_MS = 3_000L

/** 进会话先渲染多少条。 */
private const val INITIAL_WINDOW = 200

/** 滚到顶再加一页的条数。 */
private const val WINDOW_PAGE = 200

/**
 * 聊天页的接线层：读库、算副标题、发消息、管 watch。
 * [ChatScreen] 保持纯展示（CODING_STYLE §7②）。
 */
@Composable
fun ChatHost(client: IMClient, conv: ConversationEntity, onBack: () -> Unit) {
    // 系统返回键要回会话列表，不是退出 App。
    // 不拦的话「进会话 → 按返回 → App 没了」，这是 Android 用户最直觉的一个动作。
    BackHandler(onBack = onBack)

    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

    // 系统相册选择器。**用 PickVisualMedia 而不是 GetContent**：
    // 前者是 Android 13+ 的 Photo Picker，**不需要读取全部相册的权限**——
    // 用户只把选中的那张授权给你。声明 READ_MEDIA_IMAGES 去换一个选图功能
    // 是典型的权限过度索取，商店审核也会问。
    val pickMedia = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val picked = withContext(Dispatchers.IO) { readPickedImage(context, uri) }
            if (picked == null) {
                IMLog.tag("IM.Media").w("pick_read_failed")
                return@launch
            }
            client.messages.sendMedia(
                convId = conv.convId,
                to = if (conv.isGroup) conv.convId else conv.peerUid,
                bytes = picked.bytes,
                fileName = picked.name,
                mimeType = picked.mime,
                contentType = ContentType.IMAGE,
                localPreviewUri = uri.toString(),
            )
        }
    }
    val owner = client.uid.orEmpty()
    var input by remember(conv.convId) { mutableStateOf("") }

    // 渲染窗口大小。**不能无界**——13 万条的会话会把聊天页渲染成空白（实测）。
    // 滚到顶时加一页；不做减半回收：Compose 的 LazyColumn 本就只组合可见项，
    // 内存压力来自这个 List 本身，而用户主动翻上去的部分他还想看得到。
    var windowLimit by remember(conv.convId) { mutableStateOf(INITIAL_WINDOW) }

    val messages by remember(owner, conv.convId, windowLimit) {
        client.repo.observeMessages(owner, conv.convId, windowLimit)
    }.collectAsState(initial = emptyList())

    val pending by remember(owner, conv.convId) {
        client.repo.observePending(owner, conv.convId)
    }.collectAsState(initial = emptyList())

    // **进会话那一刻的快照，之后不再跟随**。
    //
    // 直接用实时的 conv.readSeq / conv.unread 会让未读分割线在进会话后当场消失：
    // 「可见即读」一上报就把 unread 清零，重组时 buildChatRows 拿到 unread=0，
    // 分割线随之不见——用户根本来不及看到自己从哪里开始没读。
    // iOS/Web 同样是冻结入会话快照，不是实时值。
    val entry = remember(conv.convId) { conv.readSeq to conv.unread }

    val rows = remember(messages, pending, entry) {
        buildChatRows(messages, pending, entry.first, entry.second)
    }

    // —— 在线态：租约模型要求客户端自己敲心跳重算 ——
    // 「租约到期」是纯粹的时间流逝，不触发任何回调；不主动重算的话，
    // 用户静止不动时副标题会**永远**停在「在线」（PROTOCOL §5.5）。
    val presenceMap by client.presence.presence.collectAsState()
    val typingMap by client.presence.typing.collectAsState()
    var tick by remember { mutableStateOf(System.currentTimeMillis()) }

    LaunchedEffect(conv.convId) {
        // 进会话即上报 watch。force=true：服务端对每次 watch（含集合不变的重发）都回快照，
        // 返回聊天页正靠它刷新，别因为集合相同就跳过。
        if (!conv.isGroup && conv.peerUid.isNotEmpty()) {
            client.messages.sendWatch(setOf(conv.peerUid), force = true)
        }
        var sinceSnapshot = 0L
        while (true) {
            delay(Presence.TICK_MS)
            tick = System.currentTimeMillis()
            sinceSnapshot += Presence.TICK_MS
            // 对端不在线时定期重拉快照：单聊 topic 随首条消息才建，
            // 「刚加好友但从未聊过」的对端收不到上线帧，只靠帧永远升不回「在线」。
            if (sinceSnapshot >= Presence.SNAPSHOT_REFRESH_MS && !conv.isGroup) {
                sinceSnapshot = 0
                val p = client.presence.snapshotOf(conv.peerUid)
                val d = Presence.display(p.status, p.onlineUntil, p.lastSeen, tick)
                if (Presence.needsSnapshotRefresh(d)) client.messages.refreshConversations()
            }
        }
    }

    // 退出聊天页清空 watch 集（全量替换语义）
    DisposableEffect(conv.convId) {
        onDispose { client.messages.sendWatch(emptySet(), force = true) }
    }

    val subtitle = remember(conv.convId, presenceMap, typingMap, tick) {
        val who = client.presence.typingIn(conv.convId, tick)
        when {
            who != null -> "正在输入…"
            conv.isGroup -> ""
            else -> {
                val p = client.presence.snapshotOf(conv.peerUid)
                Presence.label(Presence.display(p.status, p.onlineUntil, p.lastSeen, tick), tick)
            }
        }
    }

    var lastTypingSent by remember(conv.convId) { mutableStateOf(0L) }
    var menuFor by remember { mutableStateOf<MessageEntity?>(null) }
    var replyTo by remember(conv.convId) { mutableStateOf<MessageEntity?>(null) }

    // 群里我是不是管理员——决定能否删他人的消息。
    // TODO(P10 群聊)：接 GET /groups/{id} 的 my_role 后换成真值；
    //   现在恒 false，最坏结果是**少给**一个菜单项，不会越权（服务端也会拦）。
    val iAmManager = false

    Box(Modifier.fillMaxSize()) {
    ChatScreen(
        convId = conv.convId,
        title = conv.title.ifBlank { conv.convId },
        myUid = owner,
        rows = rows,
        readSeq = entry.first,
        unread = entry.second,
        subtitle = subtitle,
        peerReadSeq = if (conv.isGroup) 0 else conv.peerReadSeq,
        input = input,
        onInputChange = { input = it },
        onTyping = {
            val now = System.currentTimeMillis()
            if (now - lastTypingSent >= TYPING_THROTTLE_MS) {
                lastTypingSent = now
                client.messages.sendTyping(conv.convId)
            }
        },
        onSend = {
            val text = input.trim()
            if (text.isNotEmpty()) {
                val quoted = replyTo
                input = ""
                replyTo = null
                scope.launch {
                    client.messages.sendText(
                        convId = conv.convId,
                        to = if (conv.isGroup) conv.convId else conv.peerUid,
                        text = text,
                        replyToConvSeq = quoted?.convSeq,
                    )
                }
            }
        },
        onBack = onBack,
        onRetry = { cid -> scope.launch { client.messages.resend(cid) } },
        onVisibleSeq = { seq -> scope.launch { client.messages.markRead(conv.convId, seq) } },
        onPickMedia = { pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
        onLoadOlder = {
            // 只有窗口已经装满时才继续加——没装满说明本地就这么多，
            // 再加只会让同一批数据反复重查
            if (messages.size >= windowLimit) windowLimit += WINDOW_PAGE
        },
        onLongPress = { menuFor = it },
        replyTo = replyTo,
        onCancelReply = { replyTo = null },
        host = client.host,
        useTls = com.libeyond.imandroid.BuildConfig.USE_TLS,
    )

    // —— 消息长按菜单 ——
    val target = menuFor
    if (target != null) {
        val actions = MessageActions.availableFor(target, owner, conv.isGroup, iAmManager)
        ActionSheet(
            title = target.content.take(30),
            items = actions.map { a ->
                SheetItem(a.label, a.destructive) {
                    when (a) {
                        MessageAction.Copy -> clipboard.setText(AnnotatedString(target.content))
                        MessageAction.Reply -> replyTo = target
                        MessageAction.Recall ->
                            client.messages.sendMsgOp(conv.convId, MsgOp.RECALL, target.convSeq)
                        MessageAction.DeleteForEveryone ->
                            client.messages.sendMsgOp(conv.convId, MsgOp.DELETE, target.convSeq)
                        MessageAction.HideForMe -> scope.launch {
                            // 走 REST，不是 msg_op——「仅为我删除」是每用户私有偏好，
                            // 不进会话事件流、不占 conv_seq、不广播给其他成员（§6.7.1）
                            runCatching { client.conversationsApi.hideMessage(conv.convId, target.convSeq) }
                            client.repo.applyMsgHidden(owner, conv.convId, target.convSeq)
                        }
                    }
                }
            },
            onDismiss = { menuFor = null },
        )
    }
    }
}


/** 从相册 uri 读出的图片。 */
private class PickedImage(val bytes: ByteArray, val name: String, val mime: String)

/**
 * 把选中的图读进内存。
 *
 * **有大小闸门**：服务端图片上限 20MB，超了会回 500001。在端上先挡一道，
 * 免得用户等上传等半天才被拒——而且真读进内存也可能 OOM。
 * 压缩待做（TODO），当前只挡不压。
 */
private fun readPickedImage(context: android.content.Context, uri: android.net.Uri): PickedImage? {
    val cr = context.contentResolver
    val mime = cr.getType(uri) ?: "image/jpeg"
    val name = cr.query(uri, null, null, null, null)?.use { cursor ->
        val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
        if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
    } ?: ("image_" + System.currentTimeMillis() + "." + mime.substringAfterLast('/'))

    return try {
        val bytes = cr.openInputStream(uri)?.use { it.readBytes() } ?: return null
        if (bytes.size > MAX_IMAGE_BYTES) {
            IMLog.tag("IM.Media").w("pick_too_large", "size" to bytes.size)
            return null
        }
        PickedImage(bytes, name, mime)
    } catch (e: Exception) {
        IMLog.tag("IM.Media").w("pick_read_error", "err" to e.javaClass.simpleName)
        null
    }
}

/** 服务端图片上限 20MB（uploadLimitByKind）。 */
private const val MAX_IMAGE_BYTES = 20 * 1024 * 1024
