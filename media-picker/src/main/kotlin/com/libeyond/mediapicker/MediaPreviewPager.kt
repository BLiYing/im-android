package com.libeyond.mediapicker

import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 预览大图：左右翻 + 双指缩放（[ZoomableImage]），顶部可勾选/取消，底部直接发送。
 *
 * **视频只显首帧不播放**：播放要拖进播放器依赖（Media3/ExoPlayer 约 1.5MB），
 * 而这是个「选文件」的模块，播放不是它的职责——聊天页那边接 Media3 时自己放。
 * 这里显 `▶` + 时长，明确告诉用户「这是段视频、多长」，而不是假装能播然后点了没反应。
 *
 * 视频首帧靠 `coil-video` 的 `VideoFrameDecoder` 出图；调用方要在自己的 `ImageLoader`
 * 里注册它（见 [MediaPickerHost] 的注释），没注册就只显黑底 + 时长，不会崩。
 */
@Composable
internal fun MediaPreviewPager(
    assets: List<MediaAsset>,
    startIndex: Int,
    selected: List<Long>,
    onToggle: (MediaAsset) -> Unit,
    onToast: (String) -> Unit,
    onClose: () -> Unit,
    sendOriginal: Boolean,
    onOriginalChange: (Boolean) -> Unit,
    onSend: () -> Unit,
) {
    if (assets.isEmpty()) return
    val s = LocalMediaPickerSkin.current
    val limitMsg = stringResource(R.string.mp_limit, MediaPick.LIMIT)
    val unreadableMsg = stringResource(R.string.mp_unreadable)
    val state = rememberPagerState(
        initialPage = startIndex.coerceIn(0, assets.lastIndex),
        pageCount = { assets.size },
    )
    val current = assets[state.currentPage.coerceIn(0, assets.lastIndex)]
    val index = MediaPick.indexOf(selected, current.id)

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(
            state = state,
            modifier = Modifier.fillMaxSize(),
            // 缩放中的那一页不该被水平翻页抢走手势；1 倍时正常翻。
            // ZoomableImage 内部消费了多指手势，单指横滑仍交给 pager。
            pageSpacing = 8.dp,
        ) { page ->
            val a = assets[page]
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                ZoomableImage(model = a.uri, contentDescription = a.displayName)
                if (a.isVideo) {
                    Row(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 120.dp)
                            .background(Color(0x99000000), RoundedCornerShape(14.dp))
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text0(stringResource(R.string.mp_video_preview_note, MediaPick.durationLabel(a.durationMs)), Color.White, 13.sp)
                    }
                }
            }
        }

        // 顶栏：返回 + i/N + 勾选
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .systemBarsPadding()
                .padding(horizontal = s.space3, vertical = s.space3),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text0(stringResource(R.string.mp_back), s.accent, 17.sp, Modifier.width(64.dp).clickable(onClick = onClose))
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Text0("${state.currentPage + 1}/${assets.size}", Color.White, 15.sp)
            }
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(if (index > 0) s.accent else Color(0x66FFFFFF))
                    .clickable {
                        if (index == 0 && selected.size >= MediaPick.LIMIT) {
                            onToast(limitMsg)
                        } else if (!MediaPick.selectable(current)) {
                            onToast(unreadableMsg)
                        } else {
                            onToggle(current)
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                if (index > 0) Text0("$index", s.onAccent, 13.sp, weight = FontWeight.SemiBold)
            }
        }

        // 底栏：原图 + 发送（与宫格页同一套语义，别让用户在两页看到两套规则）
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color(0xCC000000))
                .systemBarsPadding()
                .padding(s.space3),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PreviewOriginalToggle(
                    checked = sendOriginal,
                    totalBytes = MediaPick.totalBytes(assets, selected),
                    enabled = selected.isNotEmpty(),
                    onChange = onOriginalChange,
                )
                Spacer(Modifier.weight(1f))
                val n = selected.size
                Box(
                    modifier = Modifier
                        .height(36.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (n > 0) s.accent else s.accent.copy(alpha = 0.4f))
                        .clickable(enabled = n > 0, onClick = onSend)
                        .padding(horizontal = s.space4),
                    contentAlignment = Alignment.Center,
                ) {
                    Text0(if (n > 0) stringResource(R.string.mp_send_count, n) else stringResource(R.string.mp_send), s.onAccent, 15.sp, weight = FontWeight.Medium)
                }
            }
        }
    }
}

@Composable
private fun PreviewOriginalToggle(
    checked: Boolean,
    totalBytes: Long,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
) {
    val s = LocalMediaPickerSkin.current
    Row(
        modifier = Modifier.clickable(enabled = enabled) { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        // 同宫格页：未选态描边空心圈（实心的会被读成「已选中、只是灰的」）
        Box(
            modifier = Modifier
                .size(18.dp)
                .clip(CircleShape)
                .then(
                    if (checked && enabled) {
                        Modifier.background(s.accent)
                    } else {
                        Modifier.border(1.5.dp, Color(0x99FFFFFF), CircleShape)
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (checked && enabled) Text0("✓", s.onAccent, 11.sp, weight = FontWeight.Bold)
        }
        Spacer(Modifier.width(s.space1))
        Text0(
            if (checked && enabled) stringResource(R.string.mp_original_size, MediaPick.sizeLabel(totalBytes)) else stringResource(R.string.mp_original),
            if (enabled) Color.White else Color(0x88FFFFFF),
            14.sp,
        )
    }
}
