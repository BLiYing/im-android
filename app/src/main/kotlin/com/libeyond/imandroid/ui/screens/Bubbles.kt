package com.libeyond.imandroid.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.libeyond.imandroid.R
import com.libeyond.imandroid.i18n.Str
import com.libeyond.imandroid.data.LongText
import com.libeyond.imandroid.data.TextTier
import com.libeyond.imandroid.data.BubbleCaption
import com.libeyond.imandroid.data.CaptionPlacement
import com.libeyond.imandroid.data.CallRecord
import com.libeyond.imandroid.data.CardContent
import com.libeyond.imandroid.data.LinkDetect
import com.libeyond.imandroid.data.ReplySnapshots
import com.libeyond.imandroid.data.Mention
import com.libeyond.imandroid.data.SenderRun
import com.libeyond.imandroid.data.db.MessageEntity
import com.libeyond.imandroid.sdk.protocol.ContentType
import com.libeyond.imandroid.ui.components.IMAvatar
import com.libeyond.imandroid.ui.components.TimeFormat
import com.libeyond.imandroid.ui.theme.IMTheme

// 气泡本体。从 ChatScreen 拆出（CODING_STYLE §7②）：那个文件 526 行触了体量 WARN，
// 规矩是**接近上限就规划拆分**，不等触顶。日期/系统消息/未读线这些非气泡行在 ChatNotes.kt。

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun Bubble(
    text: String,
    mine: Boolean,
    msg: MessageEntity? = null,
    /** 长按回调，带上**气泡在窗口里的矩形**——菜单要浮在气泡旁边（对齐 iOS UIContextMenu）。 */
    onLongPress: ((Rect) -> Unit)? = null,
    /** 点开媒体查看器（只对 image/video 生效）。 */
    onOpenMedia: ((MessageEntity) -> Unit)? = null,
    /** 点合并转发卡 → 聊天记录详情页，参数是这条的 content（十七条对齐 #17）。 */
    onOpenRecord: ((String) -> Unit)? = null,
    /** 点单聊通话记录 → 按原类型回拨（参数：是否视频）。宿主不判忙，走与顶栏「语音 / 视频」同一入口。 */
    onCallBack: ((Boolean) -> Unit)? = null,
    /** 待发气泡没有 [MessageEntity]，类型要显式传（目前只有通话记录用到）。 */
    pendingType: String? = null,
    /** 译文（只在内存，对齐 iOS：挂在同一气泡里、原文之后，14sp 次要色）。 */
    translation: String? = null,
    /** 长文本（≥300 字或 ≥10 行）当前是不是展开着。 */
    textExpanded: Boolean = false,
    /** 点气泡先问长文本处理（展开/收起/开阅读页）；返回 true = 已处理。 */
    onTapLongText: ((MessageEntity) -> Boolean)? = null,
    /** 点名片气泡 → 该用户资料页（自己则进个人资料，由宿主的 MemberProfileHost 收口）。 */
    onOpenContact: ((String) -> Unit)? = null,
    /** 媒体地址补全用的当前 host。 */
    host: String = "",
    useTls: Boolean = false,
    timestamp: Long,
    /** 发送者显示名（备注 > 群成员名 > 消息上的昵称）。头像首字母也用它。 */
    senderName: String?,
    /** 本条是否在气泡上方画昵称——只有连续段首条画（见 [showsSenderName]）。 */
    showSenderName: Boolean = false,
    /** 昵称旁的角色徽标；null = 普通成员不画。 */
    senderBadge: SenderRun.Badge? = null,
    /** 被引用者的显示名（群聊两行式引用条用）。单聊传 null。 */
    replyFromName: String? = null,
    /**
     * 引用快照。**由调用方给**，因为它有三个来源、优先级不同：
     * 服务端冻结的 `reply_snapshot` > 本地那条原消息现算 > 「原消息」。
     *
     * 为什么要"现算"这一档：`ack` 只回 5 个字段（client_msg_id/server_msg_id/conv_id/
     * conv_seq/timestamp），**冻结的快照回不来**——所以自己发的引用消息在**自己这一侧**
     * 恒无快照，而对端一切正常。又是 ack 不回带那一族（第五次）。
     */
    quoteSnapshot: String? = null,
    /** 被引用消息的极小缩略（本地反查到才有）。 */
    quoteThumb: String? = null,
    /** 点引用块跳原消息；null = 原消息不在本地，不可点。 */
    onTapQuote: (() -> Unit)? = null,
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
    /** 点群聊对方头像 → 进该成员资料页（对齐 iOS `onAvatarTap`）。null = 不可点（单聊/自己不挂）。 */
    onAvatarTap: (() -> Unit)? = null,
    /** 群聊——自动下载策略的单聊/群聊分档要用。 */
    isGroup: Boolean = false,
    /**
     * 本群成员表（显示名→uid），**只给没有 `mention_spans` 的老消息兜底**（按昵称扫文本）。
     * 超级群拿不到成员表就传空表：那时老消息里的 @ 不高亮，与协议里写的降级一致。
     */
    mentionNames: Map<String, String> = emptyMap(),
    /**
     * @提及片段的 JSON。默认取 [msg] 上的；**待发气泡要显式传**——那时还没有
     * [MessageEntity]，只有待发行，不传的话自己刚发出去的 @ 在 ack 落地前不高亮
     * （普通群里昵称老路会兜住，超级群没有成员表就真的不高亮了）。
     */
    mentionSpansJson: String? = null,
    /** 点气泡里的 `@某人` → 进他的资料页。null = 不可点（`@所有人` 无论如何都不可点）。 */
    onTapMention: ((String) -> Unit)? = null,
    /** 取链接预览。传 null = 不出预览卡（长按菜单里的原位重绘就传 null，别重复请求）。 */
    loadLinkPreview: (suspend (String) -> com.libeyond.imandroid.sdk.api.LinkPreview?)? = null,
    /** 会话内搜索的命中词（已 trim）。空串 = 不高亮。 */
    searchHighlight: String = "",
) {
    val c = IMTheme.colors
    val d = IMTheme.dimens
    val appearance = IMTheme.appearance
    // 点链接走应用内浏览器（宿主 WebLinkHost，iOS `openLink:`）。长按菜单的原位预览里由 ChatMessageMenu 置空。
    // **别改成按 onLongPress 判**：ChatRowView 传进来的长按回调恒非空，那条判据永远不生效
    val openLink = com.libeyond.imandroid.ui.components.LocalOpenLink.current
    // 记住整行矩形：长按菜单按它定位（iOS 是 UITargetedPreview 把位图钉回原位）。
    var bubbleRect by remember { mutableStateOf(Rect.Zero) }
    // 气泡最大宽是**内容区的比例**不是固定 dp（UI_SPEC §3）：固定值在窄机上过宽、宽机上过窄。
    // BoxWithConstraints 才能拿到本行可用宽度并按比例折算。
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
    val bubbleMax = bubbleMaxWidth(maxWidth, d.bubbleMaxWidthFraction)
    Row(
        // 菜单锚点取**整行**而不是气泡本体：整行天然是全宽，预览重绘时
        // 「Row 的左右对齐」不会再叠加一次偏移（第一版挂在气泡 Box 上，预览就画偏了）。
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned { bubbleRect = it.boundsInWindow() },
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
                modifier = Modifier.size(d.chatAvatar).align(Alignment.Bottom)
                    .then(
                        if (showAvatar && onAvatarTap != null) Modifier.clickable(onClick = onAvatarTap) else Modifier,
                    ),
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
            // 昵称 + 角色徽标：**只在连续段首条**（iOS `isFirstInSenderRun:`）。
            // 此前每条都显、还没有徽标——连发五条就是五行一样的名字（十七条对齐 #15）。
            if (showSenderName && !senderName.isNullOrBlank()) {
                SenderHeader(senderName, senderBadge)
            }
            val recalled = (msg?.recalledAt ?: 0) > 0
            // 图/视频要贴着气泡边渲染（撤回墓碑是纯文字，不算）
            val flushMedia = msg != null && !recalled &&
                (msg.contentType == ContentType.IMAGE || msg.contentType == ContentType.VIDEO)
            // 「转发自 X」放在**气泡外上方**（与发送者昵称同一列），不进气泡内：
            // 进气泡内会被当成正文的一部分被复制/引用走。撤回墓碑上不显。
            val fwd = msg?.forwardFrom
            if (!fwd.isNullOrBlank() && !recalled) {
                Text(
                    text = stringResource(R.string.chat_bubble_forward_from, fwd),
                    color = c.textTertiary,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(start = 4.dp, bottom = 2.dp),
                )
            }
            // 通话记录：整个气泡可点，按下 alpha 0.7、不做涟漪（UX 稿 §03）
            val isCall = (msg?.contentType ?: pendingType) == ContentType.CALL
            val pressSource = remember { MutableInteractionSource() }
            val pressed by pressSource.collectIsPressedAsState()
            Box(
                modifier = Modifier
                    .widthIn(max = bubbleMax)
                    .then(if (isCall && pressed) Modifier.alpha(0.7f) else Modifier)
                    .clip(RoundedCornerShape(appearance.bubbleRadius))
                    .background(if (mine) c.bubbleMe else c.bubbleThem)
                    .then(
                        if (onLongPress != null && !recalled) {
                            Modifier.combinedClickable(
                                interactionSource = pressSource,
                                indication = null,
                                // 合并转发卡点开详情页；其余类型点击**不做事**——文本气泡点一下就跳走是很怪的交互。
                                // 图/视频由媒体块自己接（得先看下没下下来，见 MediaContent 的 onOpenMedia）
                                onClick = {
                                    msg?.let {
                                        when {
                                            onTapLongText?.invoke(it) == true -> Unit
                                            it.contentType == ContentType.CONTACT ->
                                                CardContent.parseContact(it.content)?.let { c -> onOpenContact?.invoke(c.uid) }
                                            else -> onTapBubble(it, onOpenRecord, onCallBack)
                                        }
                                    }
                                },
                                onLongClick = { onLongPress(bubbleRect) },
                            )
                        } else Modifier
                    )
                    // **图说整体化**（Telegram/iOS `_captionBG` 模型）：媒体气泡的图要**贴着气泡边**，
                    // 不能被 12/6 的内边距框出一圈底色。所以这里按类型给内边距，
                    // 非媒体的子元素各自补回来（见下面每处的 innerPad）。
                    .padding(if (flushMedia) PaddingValues(0.dp) else PaddingValues(d.bubblePaddingH, d.bubblePaddingV)),
            ) {
                // 媒体贴边时，文字类子元素要自己把内边距补回来
                val innerPad = if (flushMedia) {
                    Modifier.padding(horizontal = d.bubblePaddingH)
                } else Modifier
                // 图/视频按原图像素定尺寸（iOS `IMMediaDisplaySize`）。贴边气泡**整列就是图那么宽**：
                // 列宽一旦被引用块/图说的 fillMaxWidth 撑到气泡最大宽，图就离气泡左边空出一截（#16）
                val mediaSize = if (flushMedia) rememberMediaDisplaySize(msg!!) else null
                // 内容一律**左对齐**，只有时间行靠右（iOS `IMBubbleCell` 同）：
                // 此前整列 End，引用块比正文宽时，我发的短正文被推到气泡右侧
                Column(
                    modifier = if (mediaSize != null) Modifier.width(mediaSize.width) else Modifier,
                    horizontalAlignment = Alignment.Start,
                ) {
                    // 引用条：被引用消息的降级快照（发送时冻结，原消息删了仍可展示）。
                    // **判据是 replyToConvSeq > 0 而不是"有没有快照"**（同 iOS `IMBubbleCell`）：
                    // 自己发的那条拿不到冻结快照，按"有快照才画"就会在自己这一侧整个不显示。
                    // **调用方（`quoteSnapshotFor`）已经按三档优先级算好并做过 ReplySnapshots.canonical
                    // 还原**，这里不重复算一遍——重复一遍只会多一份容易与调用方脱节的实现。
                    val snap = quoteSnapshot
                    if (!snap.isNullOrBlank() && !recalled) {
                        QuoteBlock(
                            snapshot = snap,
                            thumb = quoteThumb,
                            onTap = onTapQuote,
                            // 群聊两行式（对齐 iOS `IMBubbleCell`）：被引用者昵称独占一行。
                            // 单聊不显——只有两个人，写谁的名字都是废话。
                            fromName = replyFromName,
                            modifier = Modifier
                                .then(innerPad)
                                .then(if (flushMedia) Modifier.padding(top = d.bubblePaddingV) else Modifier)
                                .fillMaxWidth(),
                        )
                    }
                    val isMedia = msg != null && msg.contentType in MEDIA_TYPES
                    // 名片 / 聊天记录卡 / 通话记录的时间并进卡片脚注那一行，不在下面另起一行（iOS/Web 同）
                    val isCard = !recalled && !isMedia &&
                        (msg?.contentType == ContentType.CONTACT || msg?.contentType == ContentType.CHAT_RECORD ||
                            (msg?.contentType ?: pendingType) == ContentType.CALL)
                    // 长文本三档（对齐 iOS/Web，阈值见 LongText）：只对已确认、未撤回的文本消息
                    val tier = if (msg != null && msg.contentType == ContentType.TEXT && !recalled) LongText.tierOf(text) else TextTier.Short
                    // 搜索命中词在折叠段之外时自动展开，免得命中了却看不见（iOS 不做，属体验改良）
                    val collapsedNow = tier == TextTier.Long && !textExpanded &&
                        !(searchHighlight.isNotBlank() && text.contains(searchHighlight, ignoreCase = true))
                    val timeMeta: @Composable () -> Unit = {
                        BubbleTimeMeta(
                            timestamp, mine, sending = sending, delivered = delivered, read = read,
                            edited = (msg?.editedAt ?: 0L) > 0L,
                        )
                    }
                    when {
                        recalled -> Text(
                            text = stringResource(R.string.conv_list_recalled_self),
                            color = c.textTertiary,
                            fontSize = appearance.chatFontSize,
                        )

                        isMedia -> Box {
                            MediaContent(
                                msg!!, host, useTls, isGroup = isGroup,
                                // 自己发的不门控（转发来的图在自己这侧曾是个空盒，见 MediaContent.mine）
                                mine = mine,
                                // 文件行占满气泡内容区（iOS 文件气泡定宽），名字才有地方中间截断
                                fileRowWidth = bubbleMax - d.bubblePaddingH * 2,
                                // 图/视频整块自己接点击（就绪才打开）；可点的口径与下面长按一致
                                onOpenMedia = if (onLongPress != null) onOpenMedia else null,
                            )
                            // 时间胶囊**浮在媒体右下角**，不在下方另起一行——
                            // iOS `IMImageCell` 的 `_metaWrap` 就恒定钉在 thumb 右下（不论有无图说）。
                            // 图片是不透明的，所以胶囊必须自带底色才看得清。
                            if (!flushMedia) Unit else MediaMetaChip(
                                modifier = Modifier.align(Alignment.BottomEnd),
                                timestamp = timestamp,
                                mine = mine, sending = sending, delivered = delivered, read = read,
                            )
                        }
                        // 卡片类：名片 / 合并转发。**在这之前它们走 else 分支被当纯文本，
                        // 于是聊天页里直接显示裸 JSON**（实体机实测发现）。
                        msg?.contentType == ContentType.CONTACT -> ContactCardContent(text, footerTrailing = timeMeta)
                        msg?.contentType == ContentType.CHAT_RECORD -> ChatRecordCardContent(text, footerTrailing = timeMeta)
                        // 通话记录（单聊）：图标 + 一句话 + 时间勾；群记录不走气泡（ChatRowView 里是系统条）
                        (msg?.contentType ?: pendingType) == ContentType.CALL ->
                            CallRecordContent(text, viewerIsSender = mine, footerTrailing = timeMeta)
                        else -> Column {
                        if (tier == TextTier.Huge) LongTextCard(text, appearance.chatFontSize) else {
                        Text(
                            // @提及高亮、链接、搜索命中底色是同一次遍历铺的几层（见 chatBodyText）
                            text = chatBodyText(
                                text = if (collapsedNow) LongText.collapsed(text) + "…" else text,
                                spans = remember(mentionSpansJson, msg?.mentionSpans) {
                                    Mention.parseSpans(mentionSpansJson ?: msg?.mentionSpans)
                                },
                                memberNames = mentionNames,
                                searchNeedle = searchHighlight,
                                highlightBackground = c.accentSoft,
                                mentionColor = c.link,
                                onTapMention = onTapMention,
                                // 链接蓝字下划线、点开走应用内浏览器（iOS `applyURLHighlight:` + `openLink:`）
                                linkify = true,
                                linkColor = c.link,
                                onTapLink = openLink,
                            ),
                            color = c.textPrimary,
                            fontSize = appearance.chatFontSize,
                        )
                        if (tier == TextTier.Long) LongTextAffordance(expanded = !collapsedNow)
                        // 译文：同一气泡、原文之后，不加标签与分隔线（iOS 同）；折叠态不画（展开后才出现）
                        if (!translation.isNullOrBlank() && !recalled && !collapsedNow) {
                            Text(translation, color = c.textSecondary, fontSize = 14.sp)
                        }
                        }
                        }
                    }
                    // 图说画不画、画在哪：**判据在 [BubbleCaption]**。此前挂在 `flushMedia` 上（只有图/视频），
                    // 文件文的图说于是整段不画——同一条消息 iOS/Web 有字、本端只剩文件卡（2026-09-15 用户报）。
                    val capPlacement = BubbleCaption.placementOf(msg?.contentType, msg?.caption, recalled)
                    if (capPlacement != CaptionPlacement.None) {
                        Text(
                            // 图说也参与命中（与后端 G4、im-web 的判据一致），所以也要高亮；
                            // @ 片段的参照系在非 text 消息上**就是 caption**（PROTOCOL §4.1）
                            text = chatBodyText(
                                text = msg?.caption.orEmpty(),
                                spans = remember(mentionSpansJson, msg?.mentionSpans) {
                                    Mention.parseSpans(mentionSpansJson ?: msg?.mentionSpans)
                                },
                                memberNames = mentionNames,
                                searchNeedle = searchHighlight,
                                highlightBackground = c.accentSoft,
                                mentionColor = c.link,
                                onTapMention = onTapMention,
                            ),
                            color = c.textPrimary,
                            fontSize = appearance.chatFontSize,
                            modifier = if (capPlacement == CaptionPlacement.UnderMedia) {
                                // 贴边媒体：在气泡内补回内边距（iOS `_captionBG`：左右 10、上 6、下 10），
                                // 与媒体构成 Telegram 式的一整块；列宽已钉成图宽，长图说在图宽内换行
                                Modifier.fillMaxWidth()
                                    .padding(start = 10.dp, end = 10.dp, top = 6.dp, bottom = 10.dp)
                            } else {
                                // 文件卡：气泡自己有内边距，只与文件行隔开（iOS `_fileCaption` 距文件行 6）；
                                // 列宽由定宽的文件行撑，图说在它宽内换行，时间行照旧在最下面靠右
                                Modifier.padding(top = 6.dp)
                            },
                        )
                    }
                    // 文本气泡里首个 URL 的富预览卡（iOS `IMLinkPreviewView`）。
                    // 只对**已确认的纯文本**出卡：待发消息还没落定、媒体气泡自己就有图。
                    if (!recalled && loadLinkPreview != null && msg != null &&
                        msg.contentType == ContentType.TEXT
                    ) {
                        val url = remember(text) { LinkDetect.firstUrl(text) }
                        if (url != null) LinkPreviewCard(url, loadLinkPreview, host, useTls, onTap = openLink)
                    }
                    // 图/视频的时间**恒在图上的胶囊里**，有图说也不在图说下面再画一行（iOS 同）
                    if (flushMedia || isCard) return@Column
                    Spacer(Modifier.height(2.dp))
                    Box(Modifier.align(Alignment.End)) { timeMeta() }
                }
            }
            // 语音转写面板：挂在气泡**外面**（这条消息列，气泡下方），不进气泡内——
            // 同 Web `msg-item` 的结构，不是 `VoiceBubbleBody` 的一部分。
            if (msg != null && msg.convSeq > 0 && msg.contentType == ContentType.VOICE && !recalled) {
                val transcriber = com.libeyond.imandroid.ui.voice.LocalVoiceTranscriber.current
                // convSeq > 0 已由上面的守卫保证，playableId 在这个分支恒不为 null。
                val mid = com.libeyond.imandroid.voice.VoiceRules.playableId(msg.convSeq, msg.clientMsgId)!!
                if (transcriber != null) {
                    // 全会话共享同一张 Map（[VoiceTranscriber.state]）——直接 collectAsState 会让
                    // 「随便哪条语音的转写状态变了」都重组本气泡；derivedStateOf 把订阅收窄到
                    // 「只有这条（mid）自己的值变了才重组」，同屏多条语音气泡时避免互相拖累重组。
                    val mapState = transcriber.state.collectAsState()
                    val ts by remember(mid) { derivedStateOf { mapState.value[mid] } }
                    ts?.let { com.libeyond.imandroid.ui.voice.VoiceTranscriptPanel(it, modifier = Modifier.widthIn(max = bubbleMax)) }
                }
            }
        }
    }
    } // BoxWithConstraints（量本行可用宽 → 气泡最大宽按比例）
}

/** 气泡时间 + 发送态（🕐 / ✓ / ✓✓）。文本类挂气泡右下角，卡片类挂脚注行右端。 */
@Composable
private fun BubbleTimeMeta(
    timestamp: Long, mine: Boolean, sending: Boolean, delivered: Boolean, read: Boolean,
    /** 被编辑过：时间前加「已编辑 」（iOS `chat.message.edited_prefix`，自己与别人的消息都有）。 */
    edited: Boolean = false,
) {
    val c = IMTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            // 资源里末尾的空格会被 XML 吃掉，前缀与时间之间的空格在这里补
            text = (if (edited) stringResource(R.string.chat_message_edited_prefix).trimEnd() + " " else "") +
                TimeFormat.bubbleTime(timestamp),
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

/**
 * 轻点气泡本体。文件行、引用块、图/视频块各自处理自己的轻点（passThroughTap 会先消费掉抬起）。
 *
 * **图/视频不在这里开**：这里不知道它下没下下来。此前在这里无条件打开，
 * 于是点未下载视频的徽标旁边空白就把它打开了（2026-09-10 用户报 #8）。
 */
private fun onTapBubble(m: MessageEntity, onOpenRecord: ((String) -> Unit)?, onCallBack: ((Boolean) -> Unit)?) {
    if (m.contentType == ContentType.CALL) {
        CallRecord.parse(m.content)?.takeIf { !it.isGroup }?.let { onCallBack?.invoke(it.video) }
        return
    }
    // 坏数据不开（iOS `IMLooksLikeChatRecordJSON` 同一道守卫）：否则推出一页空白的「聊天记录」
    if (m.contentType == ContentType.CHAT_RECORD && CardContent.looksLikeRecord(m.content)) onOpenRecord?.invoke(m.content)
}

/**
 * 引用快照的**前缀 token 本地化**（PROTOCOL §4.3）。
 *
 * 服务端下发的是 `[image]` / `[file] 报表.xlsx` / `[voice] 0:12` 这种带前缀 token 的形式，
 * **只替换前缀，保留尾随的文件名/时长**。
 * `chat_record` / `contact` 由服务端预本地化下发，客户端只对存量裸 token 精确匹配兜底。
 */
internal fun localizeReplySnapshot(raw: String): String = when {
    // 带尾缀的 `[chat_record] 标题` / `[contact] 名字` 只来自结构化标记还原（ReplySnapshots），服务端老字段不下发这种形态
    raw == ReplySnapshots.RECALLED -> Str.s(R.string.quote_snapshot_recalled)
    raw.startsWith("[chat_record]") -> raw.replaceFirst("[chat_record]", Str.s(R.string.quote_snapshot_chat_record))
    raw.startsWith("[contact]") -> raw.replaceFirst("[contact]", Str.s(R.string.quote_snapshot_contact))
    raw == "[call]" -> Str.s(R.string.quote_snapshot_call)
    raw.startsWith("[image]") -> raw.replaceFirst("[image]", Str.s(R.string.preview_image))
    raw.startsWith("[video]") -> raw.replaceFirst("[video]", Str.s(R.string.preview_video))
    raw.startsWith("[file]") -> raw.replaceFirst("[file]", Str.s(R.string.preview_file))
    raw.startsWith("[voice]") -> raw.replaceFirst("[voice]", Str.s(R.string.preview_voice))
    else -> raw
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
