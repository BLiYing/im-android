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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.libeyond.imandroid.data.db.MessageEntity
import com.libeyond.imandroid.ui.components.TimeFormat
import com.libeyond.imandroid.ui.components.IMAvatar
import com.libeyond.imandroid.data.LinkDetect
import com.libeyond.imandroid.sdk.protocol.ContentType
import androidx.compose.foundation.text.ClickableText
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import com.libeyond.imandroid.data.SysSegments
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.text.style.TextOverflow
import com.composables.icons.lucide.IdCard
import com.composables.icons.lucide.Image
import com.composables.icons.lucide.MessageSquare
import com.composables.icons.lucide.Mic
import com.composables.icons.lucide.Video
import com.libeyond.imandroid.ui.components.FileTypeIcon
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.ColorFilter
import com.composables.icons.lucide.Lucide
import com.libeyond.imandroid.data.MediaUrl
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

/**
 * 系统消息：**居中灰字，不是气泡**（与 iOS `IMSystemCell` / Web `.sys-note` 同一形态）。
 *
 * 本端此前把 `content_type=system` 当成对方的普通消息画成左气泡，还占了群头像列——
 * 「光辉岁月 被设为管理员」显示成有人在说话。2026-09-07 实测发现。
 *
 * 字号走 `sysFontSize`（= 聊天字号 × 0.8，跟随用户设置），宽度上限 80%——
 * 与 Web `.sys-note span { max-width: 80% }` 同口径，长系统消息换行而不贴边。
 */
@Composable
internal fun SystemNote(
    text: String,
    /** 落库的分段 JSON；null/坏数据 → 回退整句（历史消息本来就没有分段）。 */
    sysSegments: String? = null,
    /** 名字段的本地显示名：uid → 备注/群昵称。返回 null 用服务端给的公开昵称。 */
    localName: (String) -> String? = { null },
    /** 点名字。不传则名字只染色不可点（与 iOS `onTapUID` 为空时同）。 */
    onTapUid: ((String) -> Unit)? = null,
) {
    val c = IMTheme.colors
    val appearance = IMTheme.appearance
    val segs = remember(sysSegments, text) { SysSegments.render(sysSegments, text) }

    // 名字段用**琥珀色半粗**，不用 accent：胶囊底是主题绿，把名字染成同样是绿的 accent
    // 两者色相几乎重合，看不出哪几个字是名字（iOS 2026-08-30 用户反馈过）。
    // 也不用白——那与胶囊正文同色，只剩粗细之差。琥珀在绿胶囊与黑胶囊上都跳得出来。
    val annotated = remember(segs, sysSegments) {
        buildAnnotatedString {
            segs.forEach { seg ->
                if (!SysSegments.isName(seg)) {
                    append(seg.text)
                    return@forEach
                }
                val shown = SysSegments.displayName(seg, localName(seg.uid), null)
                pushStringAnnotation(SYS_NAME_TAG, seg.uid)
                withStyle(SpanStyle(color = SYS_NAME_COLOR, fontWeight = FontWeight.SemiBold)) {
                    append(shown)
                }
                pop()
            }
        }
    }

    Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp, horizontal = 40.dp),
        contentAlignment = Alignment.Center,
    ) {
        // 胶囊：对齐 iOS `IMSystemCell` 的 _pill（圆角 11、datePillBg、内边距 10/4）
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(11.dp))
                .background(c.datePillBackground)
                .padding(horizontal = 10.dp, vertical = 4.dp),
        ) {
            ClickableText(
                text = annotated,
                style = TextStyle(
                    color = c.onMedia,
                    fontSize = appearance.sysFontSize,
                    textAlign = TextAlign.Center,
                ),
                onClick = { offset ->
                    // 点在名字上才响应，点在固定文案上不动作（同 iOS 的 TextKit 反查）
                    annotated.getStringAnnotations(SYS_NAME_TAG, offset, offset)
                        .firstOrNull()?.let { onTapUid?.invoke(it.item) }
                },
            )
        }
    }
}

/** 名字段的标注 tag 与颜色（iOS `datePillNameText` = #FFD98A）。 */
private const val SYS_NAME_TAG = "sysName"
private val SYS_NAME_COLOR = androidx.compose.ui.graphics.Color(0xFFFFD98A)

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
    /** 长按回调，带上**气泡在窗口里的矩形**——菜单要浮在气泡旁边（对齐 iOS UIContextMenu）。 */
    onLongPress: ((Rect) -> Unit)? = null,
    /** 点开媒体查看器（只对 image/video 生效）。 */
    onOpenMedia: ((MessageEntity) -> Unit)? = null,
    /** 媒体地址补全用的当前 host。 */
    host: String = "",
    useTls: Boolean = false,
    timestamp: Long,
    senderName: String?,
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
    /** 取链接预览。传 null = 不出预览卡（长按菜单里的原位重绘就传 null，别重复请求）。 */
    loadLinkPreview: (suspend (String) -> com.libeyond.imandroid.sdk.api.LinkPreview?)? = null,
) {
    val c = IMTheme.colors
    val d = IMTheme.dimens
    val appearance = IMTheme.appearance
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
            // 图/视频要贴着气泡边渲染（撤回墓碑是纯文字，不算）
            val flushMedia = msg != null && !recalled &&
                (msg.contentType == ContentType.IMAGE || msg.contentType == ContentType.VIDEO)
            // 「转发自 X」放在**气泡外上方**（与发送者昵称同一列），不进气泡内：
            // 进气泡内会被当成正文的一部分被复制/引用走。撤回墓碑上不显。
            val fwd = msg?.forwardFrom
            if (!fwd.isNullOrBlank() && !recalled) {
                Text(
                    text = "转发自 $fwd",
                    color = c.textTertiary,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(start = 4.dp, bottom = 2.dp),
                )
            }
            Box(
                modifier = Modifier
                    .widthIn(max = bubbleMax)
                    .clip(RoundedCornerShape(appearance.bubbleRadius))
                    .background(if (mine) c.bubbleMe else c.bubbleThem)
                    .then(
                        if (onLongPress != null && !recalled) {
                            Modifier.combinedClickable(
                                // 图片/视频点开进查看器；其余类型点击**不做事**——
                                // 文本气泡点一下就跳走是很怪的交互（iOS/Web 同样只有媒体可点）
                                onClick = {
                                    val m = msg
                                    if (onOpenMedia != null && m != null &&
                                        (m.contentType == ContentType.IMAGE || m.contentType == ContentType.VIDEO)
                                    ) {
                                        onOpenMedia(m)
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
                Column(horizontalAlignment = Alignment.End) {
                    // 引用条：被引用消息的降级快照（发送时冻结，原消息删了仍可展示）。
                    // **判据是 replyToConvSeq > 0 而不是"有没有快照"**（同 iOS `IMBubbleCell`）：
                    // 自己发的那条拿不到冻结快照，按"有快照才画"就会在自己这一侧整个不显示。
                    val snap = quoteSnapshot ?: msg?.replySnapshot
                    if (!snap.isNullOrBlank() && !recalled) {
                        QuoteBlock(
                            snapshot = snap,
                            // 群聊两行式（对齐 iOS `IMBubbleCell`）：被引用者昵称独占一行。
                            // 单聊不显——只有两个人，写谁的名字都是废话。
                            fromName = replyFromName,
                            modifier = Modifier
                                .then(innerPad)
                                .then(if (flushMedia) Modifier.padding(top = d.bubblePaddingV) else Modifier)
                                .fillMaxWidth(),
                        )
                        Spacer(Modifier.height(3.dp))
                    }
                    val isMedia = msg != null && msg.contentType in MEDIA_TYPES
                    when {
                        recalled -> Text(
                            text = "你撤回了一条消息",
                            color = c.textTertiary,
                            fontSize = appearance.chatFontSize,
                        )

                        isMedia -> Box {
                            MediaContent(msg!!, host, useTls)
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
                        msg?.contentType == ContentType.CONTACT -> ContactCardContent(text)
                        msg?.contentType == ContentType.CHAT_RECORD -> ChatRecordCardContent(text)
                        else -> Text(
                            text = text,
                            color = c.textPrimary,
                            fontSize = appearance.chatFontSize,
                        )
                    }
                    // 图说：媒体下方的随附文本，**在气泡内**补回左右内边距，
                    // 与媒体一起构成 Telegram 式的一整块（iOS `_captionBG`）。
                    val cap = msg?.caption
                    if (flushMedia && !cap.isNullOrBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = cap,
                            color = c.textPrimary,
                            fontSize = appearance.chatFontSize,
                            modifier = innerPad.fillMaxWidth(),
                        )
                    }
                    // 文本气泡里首个 URL 的富预览卡（iOS `IMLinkPreviewView`）。
                    // 只对**已确认的纯文本**出卡：待发消息还没落定、媒体气泡自己就有图。
                    if (!recalled && loadLinkPreview != null && msg != null &&
                        msg.contentType == ContentType.TEXT
                    ) {
                        val url = remember(text) { LinkDetect.firstUrl(text) }
                        if (url != null) LinkPreviewCard(url, loadLinkPreview, host, useTls)
                    }
                    // 媒体气泡的时间已经浮在图上了，下面这一行只给非媒体气泡画
                    if (flushMedia && msg?.caption.isNullOrBlank()) return@Column
                    Spacer(Modifier.height(2.dp))
                    Row(
                        modifier = innerPad
                            .then(if (flushMedia) Modifier.padding(bottom = d.bubblePaddingV) else Modifier),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
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

/**
 * 气泡顶部的引用块（M4-2），结构对齐 iOS `IMBubbleCell` 的引用段：
 * **左侧竖条 + 群聊两行式（被引用者昵称独占一行）+ 类型图标 + 灰字快照**。
 *
 * 此前本端只有一个灰底圆角框套一行小字——竖条、昵称行、类型图标三样都没有，
 * 与 iOS 差得最明显的就是"看不出引用的是什么类型"。
 *
 * **文件类快照用文件类型图标**（对齐 iOS 的 `IMFileTypeIconForName`）：
 * 快照形如 `[文件] 报表.xlsx`，能取到名字就按扩展名给图。
 */
@Composable
internal fun QuoteBlock(snapshot: String, fromName: String?, modifier: Modifier = Modifier) {
    val c = IMTheme.colors
    val localized = localizeReplySnapshot(snapshot)
    val kindGlyph = quoteGlyphFor(localized)
    val fileName = quoteFileNameOf(localized)

    Row(modifier = modifier.height(IntrinsicSize.Min)) {
        // 竖条：iOS 用 `▏` 字形，本端画一条真的——字形在不同字体下宽窄不一
        Box(Modifier.width(2.dp).fillMaxHeight().clip(RoundedCornerShape(1.dp)).background(c.accent))
        Spacer(Modifier.width(6.dp))
        Column(Modifier.weight(1f)) {
            if (!fromName.isNullOrBlank()) {
                Text(
                    fromName,
                    color = c.accent,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                when {
                    fileName != null -> {
                        FileTypeIcon(fileName, size = 16.dp)
                        Spacer(Modifier.width(4.dp))
                    }
                    kindGlyph != null -> {
                        Image(
                            kindGlyph, null, Modifier.size(13.dp),
                            colorFilter = ColorFilter.tint(c.textSecondary),
                        )
                        Spacer(Modifier.width(4.dp))
                    }
                }
                Text(
                    text = localized,
                    color = c.textSecondary,
                    fontSize = 13.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** 快照是媒体占位时给个小图标（对齐 iOS `IMMediaGlyphForSnippet`）；否则 null。 */
private fun quoteGlyphFor(localized: String): androidx.compose.ui.graphics.vector.ImageVector? = when {
    localized.startsWith("[图片]") -> Lucide.Image
    localized.startsWith("[视频]") -> Lucide.Video
    localized.startsWith("[语音]") -> Lucide.Mic
    localized.startsWith("[聊天记录]") -> Lucide.MessageSquare
    localized.startsWith("[个人名片]") -> Lucide.IdCard
    else -> null
}

/**
 * `[文件] 报表.xlsx` → `报表.xlsx`（对齐 iOS `IMReplySnippetFileName`）。
 * 没带名字（只有 `[文件]`）返回 null，让调用方退回通用图标。
 */
private fun quoteFileNameOf(localized: String): String? {
    if (!localized.startsWith("[文件]")) return null
    return localized.removePrefix("[文件]").trim().takeIf { it.isNotEmpty() }
}

/**
 * 本机为「正在引用的那条」生成的快照文案（输入栏引用条用）。
 *
 * **不能直接用 `msg.content`**：媒体消息的 content 是 `/uploads/req-xxx__原名.jpg`，
 * 直接截 60 个字符显示出来就是一串路径（2026-09-08 撞见）。
 * 口径与服务端冻结的 `reply_snapshot` 一致（PROTOCOL §4.3），
 * 这样"引用时看到的"和"发出去以后气泡里显示的"是同一句话。
 */
internal fun replyPreviewOf(
    contentType: String,
    content: String,
    fileName: String?,
    caption: String?,
): String = when (contentType) {
    ContentType.IMAGE -> "[图片]" + captionSuffix(caption)
    ContentType.VIDEO -> "[视频]" + captionSuffix(caption)
    ContentType.VOICE -> "[语音]"
    ContentType.FILE -> "[文件] " + MediaUrl.displayFileName(content, fileName.orEmpty())
    ContentType.CONTACT -> "[个人名片]"
    ContentType.CHAT_RECORD -> "[聊天记录]"
    else -> content
}

private fun captionSuffix(caption: String?): String =
    if (caption.isNullOrBlank()) "" else " " + caption
