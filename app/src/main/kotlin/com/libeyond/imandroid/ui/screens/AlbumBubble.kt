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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.libeyond.imandroid.data.AlbumLayout
import com.libeyond.imandroid.ui.rememberFrostedPainter
import com.libeyond.imandroid.ui.components.DownloadBadge
import com.libeyond.imandroid.ui.components.rememberGate
import com.libeyond.imandroid.data.MediaUrl
import com.libeyond.imandroid.data.db.MessageEntity
import com.libeyond.imandroid.sdk.protocol.ContentType
import com.libeyond.imandroid.ui.components.TimeFormat
import com.libeyond.imandroid.ui.theme.IMTheme

/**
 * 相册宫格气泡（M4+，对齐 iOS `IMAlbumCell`）。
 *
 * 布局全部走 [AlbumLayout]（纯函数、已单测）——**这里一个尺寸都不要自己算**：
 * 三端宫格排法不同的话，同一组图在三端裁剪出的构图都不一样，用户会以为发出去的东西被改了。
 *
 * 与普通气泡的两点不同，都是刻意的：
 * ① **不套气泡底**：宫格本身就是一整块，再垫一层底色只会多出一圈边（iOS 同）；
 * ② **时间/状态是右下角的小胶囊**，浮在最后一张图上——图片是不透明的，
 *    时间落在图上必须有底色才看得清。
 */
/**
 * 宫格里一格要的数据。已确认与待发两路都映射到它——
 * **不为两路各写一份宫格**：那两份迟早在圆角/间隙/时长角标上分叉。
 */
internal data class AlbumTile(
    val url: String,
    val contentType: String,
    val durationMs: Int?,
    /** 待发中（还没 ack）。用来压暗那一格，让人看出「还在发」。 */
    val sending: Boolean = false,
    /**
     * 分片上传百分比；`null` = 不在分片上传中（图片走整包上传，没有回调）。
     *
     * **宫格里也要有进度**：此前只有单条待发气泡（[PendingMediaBubble]）画进度环，
     * 宫格的格子只压一层暗底——于是「九宫格里混发图片和视频」时，那段几十上百 MB 的
     * 视频传上几分钟，屏幕上一点进度都没有（2026-09-08 用户报的就是这个）。
     * iOS 的 `IMAlbumTileView` 逐格有环（`kIMDownloadRingSide`），本端补齐。
     */
    val progress: Int? = null,
    /**
     * 这一格发失败了。**必须与 [sending] 分开**：失败的格子若还按"在传"画，
     * 转圈会一直转下去——用户以为还在传，实际上永远不会好。
     */
    val failed: Boolean = false,
    /** 极小模糊缩略（M4-7）——原图到位前的磨砂占位。 */
    val thumb: String? = null,
    /** 服务端给的字节数（自动下载的大小闸要用）。 */
    val sizeBytes: Long = 0,
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun AlbumBubble(
    tiles: List<AlbumTile>,
    mine: Boolean,
    /** 群聊——自动下载策略的单聊/群聊分档要用。 */
    isGroup: Boolean = false,
    timestamp: Long,
    host: String,
    useTls: Boolean,
    /**
     * 长按第 n 格，带上**这一格自己**在窗口里的矩形。
     *
     * **逐格，不是整格一个回调**（iOS 的 `UITargetedPreview` 也是浮起单格）：
     * 传整个宫格的矩形时，浮起来的是一整块九宫格而不是手指按住的那一张，
     * 与"按住哪张浮哪张"的直觉对不上。
     */
    onLongPressTile: (Int, Rect) -> Unit,
    /** 点开第 n 格（**逐格**，不是整格一个回调）。 */
    onTapTile: (Int) -> Unit = {},
    /** 要隐形的那一格（长按时它由浮层接管，原位留空避免"重叠感"）。-1 = 都不隐。 */
    hiddenIndex: Int = -1,
) {
    val c = IMTheme.colors
    var rect by remember { mutableStateOf(Rect.Zero) }
    val pattern = remember(tiles.size) { AlbumLayout.rowPattern(tiles.size) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned { rect = it.boundsInWindow() },
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
    ) {
        Box(
            modifier = Modifier
                .width(AlbumLayout.WIDTH.dp)
                .clip(RoundedCornerShape(IMTheme.appearance.bubbleRadius)),
        ) {
            Column {
                var idx = 0
                pattern.forEachIndexed { rowIdx, cols ->
                    if (rowIdx > 0) Spacer(Modifier.height(AlbumLayout.GAP.dp))
                    val tile = AlbumLayout.tileSize(cols).dp
                    Row {
                        repeat(cols) { col ->
                            if (col > 0) Spacer(Modifier.width(AlbumLayout.GAP.dp))
                            val m = tiles.getOrNull(idx++)
                            if (m == null) {
                                // 格数比消息多（理论上不会，pattern 由 size 推出）——留空不崩
                                Spacer(Modifier.size(tile))
                            } else {
                                val at = idx - 1 // idx 已在上面自增过
                                // **长按必须挂在每一格上**：格子自己的 clickable 会吃掉 down 事件，
                                // 挂在外层容器上的 combinedClickable 永远收不到长按
                                // ——2026-09-08 用户报的「九宫格消息不支持长按」就是这个。
                                AlbumTileView(
                                    m, tile, host, useTls, isGroup,
                                    onTap = { onTapTile(at) },
                                    onLongPress = { r -> onLongPressTile(at, r) },
                                    hidden = at == hiddenIndex,
                                )
                            }
                        }
                    }
                }
            }
            // 时间胶囊：浮在右下角图片之上，必须带底色（图是不透明的）
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(c.overlay)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text(
                    TimeFormat.bubbleTime(timestamp),
                    color = c.onMedia,
                    fontSize = 10.sp,
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AlbumTileView(
    m: AlbumTile,
    size: androidx.compose.ui.unit.Dp,
    host: String,
    useTls: Boolean,
    isGroup: Boolean,
    onTap: () -> Unit = {},
    onLongPress: (Rect) -> Unit = {},
    hidden: Boolean = false,
) {
    val c = IMTheme.colors
    // 每一格记住**自己**的矩形：长按浮起的是这一格，不是整个宫格
    var tileRect by remember { mutableStateOf(Rect.Zero) }
    Box(
        modifier = Modifier.size(size).background(c.subtleFill)
            .onGloballyPositioned { tileRect = it.boundsInWindow() }
            .alpha(if (hidden) 0f else 1f)
            .combinedClickable(onClick = onTap, onLongClick = { onLongPress(tileRect) }),
    ) {
        AsyncImage(
            // 待发那格的 content 是本地 content:// uri——Coil 直接能加载，
            // 所以选完立刻有图，不用等上传完
            model = MediaUrl.absolute(m.url, host, useTls),
            contentDescription = if (m.contentType == ContentType.VIDEO) "视频" else "图片",
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(size),
        )
        if (m.sending || m.failed) {
            // 还在发 / 发失败：压一层暗底，让人看出这一格没完成
            Box(Modifier.size(size).background(c.overlay))
            // 进度：分片上传有百分比就画环 + 数字；整包上传（图片）没有回调，
            // 给一个转圈的——**"在传但不知道传到哪"也是信息**，比只有一层暗底强。
            // 环的尺寸跟着格子走：3 列宫格里格子只有 79dp，44dp 的环会顶满整格。
            val ring = AlbumLayout.ringSize(size.value).dp
            Box(Modifier.size(size), contentAlignment = Alignment.Center) {
                if (m.failed) {
                    // 失败：红❗（点击重发挂在格子上，与单条待发气泡同一套语义）
                    Text("！", color = c.danger, fontSize = 22.sp)
                } else if (m.progress != null) {
                    CircularProgressIndicator(
                        progress = { m.progress / 100f },
                        modifier = Modifier.size(ring),
                        color = c.onMedia,
                        trackColor = c.overlay,
                        strokeWidth = 2.dp,
                    )
                    if (AlbumLayout.showsRingPercent(size.value)) {
                        Text("${m.progress}%", color = c.onMedia, fontSize = 10.sp)
                    }
                } else {
                    CircularProgressIndicator(
                        modifier = Modifier.size(ring),
                        color = c.onMedia,
                        strokeWidth = 2.dp,
                    )
                }
            }
        }
        // 视频格左上角显时长（服务端给了才显，**不为拿它去下载视频**）
        if (m.contentType == ContentType.VIDEO && (m.durationMs ?: 0) > 0) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(4.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(c.overlay)
                    .padding(horizontal = 4.dp, vertical = 1.dp),
            ) {
                Text(MediaUrl.formatDuration(m.durationMs), color = c.onMedia, fontSize = 9.sp)
            }
        }
    }
}
