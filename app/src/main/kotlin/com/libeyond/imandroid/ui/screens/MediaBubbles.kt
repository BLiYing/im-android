package com.libeyond.imandroid.ui.screens

import com.libeyond.imandroid.ui.voice.VoiceBubbleBody
import com.libeyond.imandroid.ui.voice.VoiceSource
import com.libeyond.imandroid.voice.VoiceRules
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Mic
import com.composables.icons.lucide.Pause
import com.composables.icons.lucide.Play
import com.libeyond.imandroid.R
import com.libeyond.imandroid.data.DownloadLabels
import com.libeyond.imandroid.data.DownloadPhase
import com.libeyond.imandroid.data.MediaDisplaySize
import com.libeyond.imandroid.data.MediaUrl
import com.libeyond.imandroid.data.db.MessageEntity
import com.libeyond.imandroid.data.Waveform
import androidx.compose.runtime.remember
import com.libeyond.imandroid.ui.rememberFrostedPainter
import com.libeyond.imandroid.ui.components.FileGateSlot
import com.libeyond.imandroid.ui.components.GateInfo
import com.libeyond.imandroid.ui.components.MediaGateOverlay
import com.libeyond.imandroid.ui.components.MiddleEllipsisText
import com.libeyond.imandroid.ui.components.passThroughTap
import com.libeyond.imandroid.ui.components.rememberGate
import com.libeyond.imandroid.ui.components.TimeFormat
import com.libeyond.imandroid.ui.theme.IMTheme

/**
 * 媒体气泡内容（图片 / 视频 / 语音 / 文件）。
 *
 * **不为了拿尺寸或时长去预下载媒体**（PROTOCOL §4.3 明文）：
 * `media_w/media_h/duration/file_size` 服务端会随消息下发，缺字段就按未知处理，
 * 用一个中性占位比先下完再排版好——后者会让长列表滚动时疯狂拉流量。
 */
@Composable
fun MediaContent(
    msg: MessageEntity,
    host: String,
    useTls: Boolean,
    /** 群聊——自动下载策略的单聊/群聊分档要用（`DownloadPolicy.shouldAutoDownload`）。 */
    isGroup: Boolean = false,
    /**
     * 这条是**我自己发出去的**。为真时图/视频**不走下载门控**，直接按地址显示。
     *
     * 起因是 2026-09-17 用户报的「转发了一张图，却没看到那条图片消息」：转发透传的是原图 URL、
     * **本机并没有这张图的字节**（原图当初就没下过），于是自己刚发出去的那条在**自己这一侧**
     * 渲染成一个带 ↓ 的空盒子——要用户对自己刚发的东西再点一次「下载」才看得见。
     * 门控是拿来挡"别人发来的、我还没决定要不要下"的流量的，**对自己发出去的内容没有意义**。
     * 普通发图那条路不受影响（字节本就在 `MediaCache` 里，门控恒 Ready），受影响的只有转发。
     */
    mine: Boolean = false,
    /** 文件行的宽度 = 气泡内容区宽。定宽才有地方把长文件名截中间。 */
    fileRowWidth: Dp = 240.dp,
    /**
     * 点开图片/视频查看器。null = 这块图不接点击（长按菜单里的原位重绘）。
     *
     * **图/视频整块自己接点击**，不再交给气泡：就绪才打开，没下下来点哪儿都是「开始 / 暂停 / 重试」。
     * 此前只有中间那枚徽标会下载，点徽标旁边的空白落到气泡上，直接把没下载的视频打开了（2026-09-10 用户报 #8）。
     */
    onOpenMedia: ((MessageEntity) -> Unit)? = null,
) {
    // **图说整体化**：有 caption 时媒体只圆上角，和下方文字连成一整块（iOS `_captionBG`）；
    // 没有 caption 时四角都圆——此时媒体本身就是整个气泡。
    val r = IMTheme.appearance.bubbleRadius
    val shape = if (msg.caption.isNullOrBlank()) {
        RoundedCornerShape(r)
    } else {
        RoundedCornerShape(topStart = r, topEnd = r, bottomStart = 0.dp, bottomEnd = 0.dp)
    }
    when (msg.contentType) {
        "image" -> ImageContent(msg, shape, host, useTls, isGroup, mine, onOpenMedia)
        "video" -> VideoContent(msg, shape, host, useTls, isGroup, mine, onOpenMedia)
        "voice" -> VoiceBubbleBody(
            VoiceSource(
                id = VoiceRules.playableId(msg).orEmpty(), convId = msg.convId, url = msg.content,
                durationMs = (msg.duration ?: 0).toLong(), waveform = msg.waveform,
            ),
            mine = mine,
            horizontalInset = IMTheme.dimens.bubblePaddingH,
        )
        else -> FileContent(msg, isGroup, fileRowWidth)
    }
}

/**
 * 图/视频在聊天页里的显示尺寸（iOS `IMMediaDisplaySize` + `IMImageCell.maxBox`，式子在 [MediaDisplaySize]）。
 * 按原图像素只缩不放、短边不足 80 再放大；像素未知时是方块——**视频也一样**，不再猜 16:9。
 */
@Composable
internal fun rememberMediaDisplaySize(msg: MessageEntity): DpSize {
    val screenW = LocalConfiguration.current.screenWidthDp
    return remember(msg.mediaW, msg.mediaH, screenW) {
        val s = MediaDisplaySize.fit(msg.mediaW, msg.mediaH, MediaDisplaySize.box(screenW.toFloat()))
        DpSize(s.width.dp, s.height.dp)
    }
}

/**
 * 图/视频整块的轻点：就绪 → 打开；没就绪 → 门控动作（开始 / 暂停 / 重试）；失效 → 不接（iOS 失效层点击穿透）。
 * 用 passThroughTap：只吃抬起，气泡上的长按菜单照常弹。
 */
private fun Modifier.mediaTap(
    gate: GateInfo,
    msg: MessageEntity,
    onOpenMedia: ((MessageEntity) -> Unit)?,
    /** 绕过门控直接打开（自己发的，见 [MediaContent] 的 `mine`）。 */
    openAnyway: Boolean = false,
): Modifier =
    passThroughTap(enabled = onOpenMedia != null && (openAnyway || gate.state.phase != DownloadPhase.Expired)) {
        if (gate.ready || openAnyway) onOpenMedia?.invoke(msg) else gate.onTap()
    }

@Composable
private fun ImageContent(
    msg: MessageEntity,
    shape: androidx.compose.ui.graphics.Shape,
    host: String,
    useTls: Boolean,
    isGroup: Boolean,
    mine: Boolean,
    onOpenMedia: ((MessageEntity) -> Unit)?,
) {
    val c = IMTheme.colors
    // 磨砂占位（M4-7）：原图到位之前显示消息里内嵌的 ~20px 缩略放大 + 模糊，
    // 而不是一块空底。没有 thumb（老消息 / 对端没带）就回退中性底——**不为占位联网**。
    val frosted = rememberFrostedPainter(msg.thumb)
    // 下载门控（M4-7）：**未就绪时 model 是 null 而不是远端地址**——给远端地址等于
    // Coil 照样把原图拉下来，门控就成了纯装饰。
    val gate = rememberGate(msg.content, msg.contentType, msg.fileSize ?: 0L, isGroup)
    // **自己发出去的不门控**（理由见 [MediaContent] 的 `mine`）：已就绪走本地件，
    // 没就绪就按地址显示，而不是给用户一个要再点一次下载的空盒子。
    //
    // ⚠️ **但「已失效」不在豁免之列**（2026-09-17 `/code-review` 抓出）：服务端清理过的媒体
    // 自己发的那条同样会失效，豁免掉的话它会**装作一切正常**——图渲染不出来却没有任何说明，
    // 点一下还打开一个空查看器。失效是终态，必须照常画失效层（[ungated] 只豁免"没下载"这一档）。
    val ungated = mine && gate.state.phase != DownloadPhase.Expired
    val model = gate.model
        ?: if (ungated) MediaUrl.absolute(msg.content, host, useTls).takeIf { it.isNotBlank() } else null
    Box(
        // 尺寸在排版前就定死（服务端给的宽高），加载完不跳版把下面的消息挤走
        modifier = Modifier
            .size(rememberMediaDisplaySize(msg))
            .clip(shape)
            .background(c.subtleFill)
            .mediaTap(gate, msg, onOpenMedia, openAnyway = ungated),
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = model,
            contentDescription = stringResource(R.string.common_image),
            contentScale = ContentScale.Crop,
            placeholder = frosted,
            error = frosted,
            fallback = frosted,
            modifier = Modifier.fillMaxSize(),
        )
        // 自己发的不画门控层——它表达的是"要不要下载"；**失效那一档除外**（见上）
        if (!ungated) {
            MediaGateOverlay(
                gate.state, msg.fileSize ?: 0L, durationText = null,
                expiredCaption = stringResource(R.string.media_image_expired),
            )
        }
    }
}

@Composable
private fun VideoContent(
    msg: MessageEntity,
    shape: androidx.compose.ui.graphics.Shape,
    host: String,
    useTls: Boolean,
    isGroup: Boolean,
    mine: Boolean,
    onOpenMedia: ((MessageEntity) -> Unit)?,
) {
    val c = IMTheme.colors
    // 门控态只显磨砂 thumb（不拉封面，对齐 iOS）；下完 / 自己发的才画封面。
    val gate = rememberGate(msg.content, msg.contentType, msg.fileSize ?: 0L, isGroup)
    // 自己发的豁免门控，但**失效不豁免**（理由同 [ImageContent] 的 `ungated`）
    val ungated = mine && gate.state.phase != DownloadPhase.Expired
    Box(
        modifier = Modifier.size(rememberMediaDisplaySize(msg)).clip(shape).background(c.subtleFill)
            .mediaTap(gate, msg, onOpenMedia, openAnyway = ungated),
        contentAlignment = Alignment.Center,
    ) {
        val poster = msg.poster
        val frosted = rememberFrostedPainter(msg.thumb)
        val gatedNow = !gate.ready && !ungated
        if (gatedNow) {
            // **未下载（门控态）一律只显内嵌 thumb 的磨砂图**，不拉封面——与 iOS `IMImageCell`（gated：thumb 优先、
            // 无 thumb 留中性底、「绝不为占位联网」）和图片气泡同口径。此前有 poster 就直接画清晰封面，
            // 自动下载关着时视频看着已经「下好了」，磨砂占位成了死代码（2026-10-03 用户报，iOS 早年同一个坑）。
            if (frosted != null) {
                Image(painter = frosted, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        } else if (!poster.isNullOrBlank()) {
            // 封面：**解不了 HEVC 的端只能靠这张图**，没有它就是一片黑底加个播放钮。
            // poster 为空时不画 AsyncImage —— 传空串给 Coil 会触发一次必然失败的加载。
            AsyncImage(
                model = MediaUrl.absolute(poster, host, useTls),
                contentDescription = stringResource(R.string.chat_media_alt_video_cover),
                contentScale = ContentScale.Crop,
                // 封面还在下载 / 下不动时，先给内嵌缩略的磨砂版（M4-7）
                placeholder = frosted,
                error = frosted,
                modifier = Modifier.fillMaxSize(),
            )
        } else if (frosted != null) {
            // 连封面 URL 都没有（老视频 / 抽帧失败）：磨砂占位总比纯黑底强
            Image(
                painter = frosted,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        val durationText = msg.duration?.takeIf { it > 0 }?.let { MediaUrl.formatDuration(it) }
        // 自己发的：直接当就绪渲染（播放钮 + 时长），点开在查看器里流式播（同 `mine` 的理由）；
        // 失效那一档仍走门控层，显示「视频已失效」
        if (!gate.ready && !ungated) {
            // 没下下来：时长并进左上角那块胶囊（「大小 · 时长」），不另画一块（iOS `renderGatedDownloadUI`）
            MediaGateOverlay(gate.state, msg.fileSize ?: 0L, durationText, expiredCaption = stringResource(R.string.media_video_expired))
            return@Box
        }
        // 与相册宫格的视频格同一枚角标（VideoPlayBadge），两处才不会各画各的
        com.libeyond.imandroid.ui.components.VideoPlayBadge()
        // 时长角标：服务端给了才显，**不为拿它去下载视频**。
        // 位置是**左上角**——协议 §4.1 明写「据 duration 在视频封面左上角显 mm:ss」，
        // 三端同一份口径。画在右下角会和时间胶囊叠在一起（2026-09-07 真机实测撞见）。
        // 形状与右下角的时间胶囊同一套（高 18、圆角 9），等宽数字不随秒数跳宽（iOS `_durationBadge`）
        if (durationText != null) {
            Box(
                modifier = Modifier.align(Alignment.TopStart).padding(6.dp).height(18.dp)
                    .clip(RoundedCornerShape(9.dp)).background(c.overlay)
                    .padding(horizontal = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(durationText, color = c.onMedia, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

@Composable
private fun FileContent(msg: MessageEntity, isGroup: Boolean, rowWidth: Dp) {
    val c = IMTheme.colors
    val appearance = IMTheme.appearance
    val context = androidx.compose.ui.platform.LocalContext.current
    val gate = rememberGate(msg.content, msg.contentType, msg.fileSize ?: 0L, isGroup)
    val toast = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    toast.value?.let { com.libeyond.imandroid.ui.components.IMToast(it) { toast.value = null } }
    val name = MediaUrl.displayFileName(msg.content, msg.fileName.orEmpty())
    val localMissingRetryText = stringResource(R.string.chat_file_local_missing_retry)
    Row(
        modifier = Modifier.width(rowWidth).padding(vertical = 2.dp)
            // **就绪就能点开**（对齐 iOS：文件行/气泡在 ready 态点一下就是打开它）；
            // 没就绪时点一下等于点 ↓（失效时门控动作为空，点了不做事）。
            // 用 passThroughTap 不用 clickable：后者吃掉 down，长按文件行弹不出菜单（#13）
            .passThroughTap {
                if (gate.ready) {
                    val f = gate.localFile
                    toast.value = if (f == null) {
                        // 缓存被清了：退回"点一下开始下载"，别报错
                        gate.onTap()
                        localMissingRetryText
                    } else {
                        com.libeyond.imandroid.ui.OpenFile.open(context, f, name)
                    }
                } else {
                    gate.onTap()
                }
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 图标位：就绪才是按扩展名的类型图标（对齐 iOS `IMFileTypeIconForName`）；
        // 没下下来时这一格画下载状态——此前类型图标上再压一枚徽标，看着像已经能打开（#8）
        FileGateSlot(gate.state, msg.fileSize ?: 0L, name)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            // 名字用强调色、跟随聊天字号、**放不下截中间**（iOS `NSLineBreakByTruncatingMiddle`）：
            // 截尾会把扩展名切掉，「季度报表-最终版-改…」看不出是表格还是文档
            MiddleEllipsisText(name, color = c.accent, fontSize = appearance.chatFontSize, maxLines = 2)
            // 大小直接用服务端给的字节数格式化，**不重新下载文件去算**；状态文案照 iOS（见 DownloadLabels）
            val line = DownloadLabels.fileStatusLine(gate.state, msg.fileSize ?: 0L)
            if (line.isNotEmpty()) {
                Spacer(Modifier.height(3.dp))
                val tint = if (DownloadLabels.fileStatusIsDanger(gate.state.phase)) c.danger else c.textSecondary
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (gate.state.phase == DownloadPhase.Paused) {
                        Image(Lucide.Pause, null, Modifier.size(10.dp), colorFilter = ColorFilter.tint(tint))
                        Spacer(Modifier.width(3.dp))
                    }
                    Text(line, color = tint, fontSize = 12.sp)
                }
            }
        }
    }
}

/** 语音波形柱数。与气泡宽度无关（下采样已按比例取），够看出起伏即可。 */

/**
 * 媒体气泡右下角的时间 + 状态胶囊（对齐 iOS `IMImageCell` 的 `_metaWrap`：高 18、圆角 9、左右 6）。
 *
 * **必须自带底色**：它浮在不透明的图片上，没底色时遇到浅色照片就完全看不见。
 */
@Composable
internal fun MediaMetaChip(
    modifier: Modifier = Modifier,
    timestamp: Long,
    mine: Boolean,
    sending: Boolean,
    delivered: Boolean,
    read: Boolean,
) {
    val c = IMTheme.colors
    Row(
        modifier = modifier
            .padding(6.dp)
            .height(18.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(c.overlay)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(TimeFormat.bubbleTime(timestamp), color = c.onMedia, fontSize = 10.sp)
        if (!mine) return@Row
        if (sending) {
            Spacer(Modifier.width(3.dp))
            Text("🕐", fontSize = 9.sp)
        } else if (delivered) {
            Spacer(Modifier.width(3.dp))
            Text(
                text = if (read) "✓✓" else "✓",
                color = if (read) c.checkRead else c.onMedia,
                fontSize = 10.sp,
            )
        }
    }
}
