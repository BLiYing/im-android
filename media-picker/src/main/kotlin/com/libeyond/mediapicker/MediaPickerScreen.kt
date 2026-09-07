package com.libeyond.mediapicker

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

/**
 * 自建相册多选页（宫格 + 编号选中 + 相册切换 + 原图开关 + 长按预览）。
 *
 * 为什么自建见 [MediaPick] 的注释（Android 11 上系统 Photo Picker 根本不存在）。
 * 与 iOS 的这条分歧登记在 IMServer `docs/UI_SPEC.md` §6.4。
 *
 * **本组件是无状态的**：选中列表、当前相册、分页游标都由 [MediaPickerHost] 持有——
 * 页面自己 `remember` 这些的话，配置变更（转屏）就会把用户选的 9 张全丢掉。
 *
 * 交互与 iOS `PHPicker` 对齐：**点击 = 选中 / 取消，长按 = 预览大图**；
 * 底部另有「预览」按钮翻已选的那几张。
 */
@Composable
fun MediaPickerScreen(
    buckets: List<MediaBucket>,
    assets: List<MediaAsset>,
    bucketId: String,
    onBucketChange: (String) -> Unit,
    selected: List<Long>,
    onToggle: (MediaAsset) -> Unit,
    sendOriginal: Boolean,
    onOriginalChange: (Boolean) -> Unit,
    access: MediaPermission.Access,
    loadingMore: Boolean,
    onLoadMore: () -> Unit,
    onSend: () -> Unit,
    onCancel: () -> Unit,
    onToast: (String) -> Unit,
    /** Android 14 部分授权时的「管理选中的照片」入口；其余情况传 null。 */
    onManagePhotos: (() -> Unit)? = null,
) {
    val s = LocalMediaPickerSkin.current
    var bucketOpen by remember { mutableStateOf(false) }
    /** 预览态：null = 不在预览；否则是要从第几张开始翻 + 翻哪一组。 */
    var preview by remember { mutableStateOf<PreviewTarget?>(null) }

    val title = buckets.firstOrNull { it.id == bucketId }?.name ?: "全部"
    val gridState = rememberLazyGridState()

    // 滑到倒数一屏就续页。**不能在 item 的 composable 里触发**——
    // 那样每次重组都会重复触发，分页游标会一次跳好几页。
    LaunchedEffect(gridState, assets.size, bucketId) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .collect { last -> if (last >= assets.size - COLUMNS * 3) onLoadMore() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(s.pageBackground)
            .systemBarsPadding(),
    ) {
        PickerTopBar(
            title = title,
            canSwitch = buckets.size > 1,
            expanded = bucketOpen,
            onToggleBuckets = { bucketOpen = !bucketOpen },
            onCancel = onCancel,
        )

        Box(Modifier.weight(1f)) {
            if (assets.isEmpty() && !loadingMore) {
                PickerEmpty(access, onManagePhotos)
            } else {
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Fixed(COLUMNS),
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(GAP),
                    verticalArrangement = Arrangement.spacedBy(GAP),
                    contentPadding = PaddingValues(GAP),
                ) {
                    items(assets, key = { it.id }) { a ->
                        MediaTile(
                            asset = a,
                            index = MediaPick.indexOf(selected, a.id),
                            onClick = {
                                if (!MediaPick.selectable(a)) {
                                    onToast(
                                        if (a.sizeBytes > MediaPick.MAX_BYTES) {
                                            "超过 ${MediaPick.sizeLabel(MediaPick.MAX_BYTES)}"
                                        } else {
                                            "这个文件读不出来"
                                        },
                                    )
                                } else {
                                    onToggle(a)
                                }
                            },
                            onLongPress = { preview = PreviewTarget(assets, assets.indexOf(a)) },
                        )
                    }
                }
            }

            if (bucketOpen) {
                BucketSheet(
                    buckets = buckets,
                    onPick = { onBucketChange(it); bucketOpen = false },
                    onDismiss = { bucketOpen = false },
                )
            }
        }

        PickerBottomBar(
            selectedCount = selected.size,
            totalBytes = MediaPick.totalBytes(assets, selected),
            sendOriginal = sendOriginal,
            onOriginalChange = onOriginalChange,
            access = access,
            onManagePhotos = onManagePhotos,
            onPreview = {
                val chosen = MediaPick.ordered(assets, selected)
                if (chosen.isNotEmpty()) preview = PreviewTarget(chosen, 0)
            },
            onSend = onSend,
        )
    }

    preview?.let { t ->
        MediaPreviewPager(
            assets = t.assets,
            startIndex = t.index,
            selected = selected,
            onToggle = onToggle,
            onToast = onToast,
            onClose = { preview = null },
            sendOriginal = sendOriginal,
            onOriginalChange = onOriginalChange,
            onSend = { preview = null; onSend() },
        )
    }
}

private data class PreviewTarget(val assets: List<MediaAsset>, val index: Int)

@Composable
private fun PickerTopBar(
    title: String,
    canSwitch: Boolean,
    expanded: Boolean,
    onToggleBuckets: () -> Unit,
    onCancel: () -> Unit,
) {
    val s = LocalMediaPickerSkin.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(s.surface)
            .padding(horizontal = s.space3, vertical = s.space3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text0("取消", s.accent, 17.sp, Modifier.width(64.dp).clickable(onClick = onCancel))
        Row(
            modifier = Modifier.weight(1f).clickable(enabled = canSwitch, onClick = onToggleBuckets),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text0(title, s.textPrimary, 17.sp, weight = FontWeight.Medium)
            if (canSwitch) {
                Spacer(Modifier.width(s.space1))
                // 用文字箭头而不是图标：本模块不该为一个三角形拖进图标库依赖
                Text0(if (expanded) "▲" else "▼", s.textSecondary, 10.sp)
            }
        }
        Box(Modifier.width(64.dp))
    }
}

@Composable
private fun MediaTile(
    asset: MediaAsset,
    index: Int,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
) {
    val s = LocalMediaPickerSkin.current
    val usable = MediaPick.selectable(asset)
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .background(s.subtleFill)
            .combinedClickableCompat(onClick = onClick, onLongClick = onLongPress),
    ) {
        AsyncImage(
            imageLoader = currentImageLoader(),
            model = asset.uri,
            contentDescription = asset.displayName,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().alpha(if (usable) 1f else 0.35f),
        )
        // 选中的压一层暗底，让编号在浅色照片上也读得出来
        if (index > 0) Box(Modifier.fillMaxSize().background(s.overlay))

        if (asset.isVideo) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp)
                    .background(s.overlay, RoundedCornerShape(3.dp))
                    .padding(horizontal = 4.dp, vertical = 1.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text0("▶ " + MediaPick.durationLabel(asset.durationMs), Color.White, 10.sp)
            }
        }

        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(4.dp)
                .size(22.dp)
                .clip(CircleShape)
                .background(if (index > 0) s.accent else s.overlay),
            contentAlignment = Alignment.Center,
        ) {
            if (index > 0) Text0("$index", s.onAccent, 12.sp, weight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun BucketSheet(
    buckets: List<MediaBucket>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val s = LocalMediaPickerSkin.current
    Box(
        modifier = Modifier.fillMaxSize().background(s.overlay).clickable(onClick = onDismiss),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp).background(s.surfaceElevated),
        ) {
            items(buckets, key = { it.id }) { b ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPick(b.id) }
                        .padding(horizontal = s.space4, vertical = s.space2),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AsyncImage(
                        imageLoader = currentImageLoader(),
                        model = b.coverUri,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(48.dp).clip(RoundedCornerShape(4.dp)).background(s.subtleFill),
                    )
                    Spacer(Modifier.width(s.space3))
                    Text0(b.name, s.textPrimary, 15.sp, Modifier.weight(1f))
                    Text0("${b.count}", s.textSecondary, 13.sp)
                }
            }
        }
    }
}

@Composable
private fun PickerBottomBar(
    selectedCount: Int,
    totalBytes: Long,
    sendOriginal: Boolean,
    onOriginalChange: (Boolean) -> Unit,
    access: MediaPermission.Access,
    onManagePhotos: (() -> Unit)?,
    onPreview: () -> Unit,
    onSend: () -> Unit,
) {
    val s = LocalMediaPickerSkin.current
    Column(modifier = Modifier.fillMaxWidth().background(s.surface).padding(s.space3)) {
        if (access == MediaPermission.Access.Partial && onManagePhotos != null) {
            // 部分授权时相册天然「少」，不给入口用户只会以为是 bug
            Text0(
                "只能看到你授权的照片 · 管理", s.accent, 13.sp,
                Modifier.fillMaxWidth().clickable(onClick = onManagePhotos).padding(bottom = s.space2),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text0(
                "预览",
                if (selectedCount > 0) s.accent else s.textSecondary,
                15.sp,
                Modifier.clickable(enabled = selectedCount > 0, onClick = onPreview).padding(end = s.space4),
            )
            OriginalToggle(sendOriginal, totalBytes, selectedCount, onOriginalChange)
            Spacer(Modifier.weight(1f))
            SendButton(selectedCount, onSend)
        }
    }
}

/**
 * 「原图」勾选。**只有真的会改变发送字节时才给用户看到它**——
 * 一个不起作用的开关比没有更糟，它会让用户以为省了流量。
 * 本端已实现压缩（[MediaCompressor]，长边 ≤2048 / JPEG 0.8，与 iOS 同口径），所以它是真的。
 */
@Composable
private fun OriginalToggle(
    checked: Boolean,
    totalBytes: Long,
    selectedCount: Int,
    onChange: (Boolean) -> Unit,
) {
    val s = LocalMediaPickerSkin.current
    val enabled = selectedCount > 0
    Row(
        modifier = Modifier.clickable(enabled = enabled) { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 未选态是**描边空心圈**，不是实心灰点——实心的会被读成「已经选中了、只是灰的」
        Box(
            modifier = Modifier
                .size(18.dp)
                .clip(CircleShape)
                .then(
                    if (checked && enabled) {
                        Modifier.background(s.accent)
                    } else {
                        Modifier.border(1.5.dp, s.textSecondary, CircleShape)
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (checked && enabled) Text0("✓", s.onAccent, 11.sp, weight = FontWeight.Bold)
        }
        Spacer(Modifier.width(s.space1))
        Text0(
            if (checked && enabled) "原图 (${MediaPick.sizeLabel(totalBytes)})" else "原图",
            if (enabled) s.textPrimary else s.textSecondary,
            14.sp,
        )
    }
}

@Composable
private fun SendButton(count: Int, onSend: () -> Unit) {
    val s = LocalMediaPickerSkin.current
    val enabled = count > 0
    Box(
        modifier = Modifier
            .height(36.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (enabled) s.accent else s.accent.copy(alpha = 0.4f))
            .clickable(enabled = enabled, onClick = onSend)
            .padding(horizontal = s.space4),
        contentAlignment = Alignment.Center,
    ) {
        Text0(if (enabled) "发送($count)" else "发送", s.onAccent, 15.sp, weight = FontWeight.Medium)
    }
}

@Composable
private fun PickerEmpty(access: MediaPermission.Access, onManagePhotos: (() -> Unit)?) {
    val s = LocalMediaPickerSkin.current
    Column(
        modifier = Modifier.fillMaxSize().padding(s.space4),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text0(
            if (access == MediaPermission.Access.Partial) "你授权的照片里没有内容" else "相册里没有照片或视频",
            s.textSecondary, 15.sp,
        )
        if (onManagePhotos != null) {
            Spacer(Modifier.height(s.space3))
            Text0("管理授权的照片", s.accent, 15.sp, Modifier.clickable(onClick = onManagePhotos))
        }
    }
}

/** 4 列：与微信/系统相册同密度；3 列格子太大一屏看不到几张，5 列在 360dp 机上缩略图糊。 */
internal const val COLUMNS = 4
internal val GAP = 2.dp
