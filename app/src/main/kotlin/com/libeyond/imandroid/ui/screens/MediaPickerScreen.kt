package com.libeyond.imandroid.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.Lucide
import com.libeyond.imandroid.data.MediaAsset
import com.libeyond.imandroid.data.MediaPermission
import com.libeyond.imandroid.data.MediaPick
import com.libeyond.imandroid.ui.components.IMPrimaryButton
import com.libeyond.imandroid.ui.theme.IMTheme

/**
 * 自建相册多选页（宫格 + 编号选中 + 相册切换）。
 *
 * **为什么不用系统选择器**：项目最低支持 Android 11，那上面没有系统 Photo Picker、
 * androidx 会回退到 DocumentsUI 文件浏览器——详见 [MediaPick] 的注释与实测数据。
 * 这是与 iOS（`PHPickerViewController`）的**刻意分歧**，登记在 IMServer `docs/UI_SPEC.md` §6.4。
 *
 * 刻意**不做**的两件事，写下来免得被当成漏做：
 * - **没有「原图 / 压缩」开关**。本端目前根本没有压缩（`readPickedImage` 是原样上传，
 *   压缩是 TODO），放一个不起作用的开关比没有更糟——它会让用户以为省了流量。
 *   等压缩接上时和 iOS 的「发送 / 发送原图」一起对齐。
 * - **没有预览大图**。要的是一个可缩放的图片查看器，本端还没有；单独在这里搓一个
 *   会变成第二份实现。等聊天页的图片查看器落地时复用它。
 */
@Composable
fun MediaPickerScreen(
    assets: List<MediaAsset>,
    access: MediaPermission.Access,
    onSend: (List<MediaAsset>) -> Unit,
    onCancel: () -> Unit,
    onToast: (String) -> Unit,
    /** Android 14 部分授权时的「管理选中的照片」入口；其余情况传 null。 */
    onManagePhotos: (() -> Unit)? = null,
) {
    val c = IMTheme.colors
    val d = IMTheme.dimens
    var selected by remember { mutableStateOf(listOf<Long>()) }
    var bucketId by remember { mutableStateOf(MediaPick.ALL_BUCKET) }
    var bucketOpen by remember { mutableStateOf(false) }

    val buckets = remember(assets) { MediaPick.buckets(assets) }
    val shown = remember(assets, bucketId) { MediaPick.filter(assets, bucketId) }
    val title = buckets.firstOrNull { it.id == bucketId }?.name ?: "全部"

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(c.pageBackground)
            .systemBarsPadding(),
    ) {
        // —— 顶栏：标题可点，点开相册切换 ——
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(c.surface)
                .padding(horizontal = d.space3, vertical = d.space3),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "取消",
                color = c.accent,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.width(64.dp).clickable(onClick = onCancel),
            )
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clickable { if (buckets.size > 1) bucketOpen = !bucketOpen },
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    color = c.textPrimary,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (buckets.size > 1) {
                    Spacer(Modifier.width(d.space1))
                    Image(
                        imageVector = Lucide.ChevronDown,
                        contentDescription = "切换相册",
                        modifier = Modifier.size(16.dp),
                        colorFilter = ColorFilter.tint(c.textSecondary),
                    )
                }
            }
            Box(Modifier.width(64.dp))
        }

        Box(Modifier.weight(1f)) {
            if (shown.isEmpty()) {
                MediaPickerEmpty(access, onManagePhotos)
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(COLUMNS),
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(GAP),
                    verticalArrangement = Arrangement.spacedBy(GAP),
                    contentPadding = PaddingValues(GAP),
                ) {
                    items(shown, key = { it.id }) { a ->
                        MediaTile(
                            asset = a,
                            index = MediaPick.indexOf(selected, a.id),
                            onClick = {
                                if (!MediaPick.selectable(a)) {
                                    onToast(if (a.sizeBytes > MediaPick.MAX_IMAGE_BYTES) "图片超过 20MB" else "这张图读不出来")
                                    return@MediaTile
                                }
                                val next = MediaPick.toggle(selected, a.id)
                                if (next == null) onToast("最多选 ${MediaPick.LIMIT} 张") else selected = next
                            },
                        )
                    }
                }
            }

            // 相册切换浮层：盖在宫格上，点空白收起
            if (bucketOpen) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(c.overlay)
                        .clickable { bucketOpen = false },
                ) {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 360.dp)
                            .background(c.surfaceElevated),
                    ) {
                        items(buckets, key = { it.id }) { b ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        bucketId = b.id
                                        bucketOpen = false
                                    }
                                    .padding(horizontal = d.space4, vertical = d.space2),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                AsyncImage(
                                    model = b.coverUri,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .size(48.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(c.subtleFill),
                                )
                                Spacer(Modifier.width(d.space3))
                                Text(
                                    text = b.name,
                                    color = c.textPrimary,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text("${b.count}", color = c.textSecondary, fontSize = 13.sp)
                            }
                        }
                    }
                }
            }
        }

        // —— 底栏：发送 ——
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(c.surface)
                .padding(d.space3),
        ) {
            if (access == MediaPermission.Access.Partial && onManagePhotos != null) {
                // 部分授权时相册天然「少」，不给入口用户只会以为是 bug
                Text(
                    text = "只能看到你授权的照片 · 管理",
                    color = c.accent,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onManagePhotos)
                        .padding(bottom = d.space2),
                )
            }
            IMPrimaryButton(
                text = if (selected.isEmpty()) "发送" else "发送(${selected.size})",
                enabled = selected.isNotEmpty(),
                onClick = { onSend(MediaPick.ordered(assets, selected)) },
            )
        }
    }
}

@Composable
private fun MediaTile(asset: MediaAsset, index: Int, onClick: () -> Unit) {
    val c = IMTheme.colors
    val usable = MediaPick.selectable(asset)
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .background(c.subtleFill)
            .clickable(onClick = onClick),
    ) {
        AsyncImage(
            model = asset.uri,
            contentDescription = asset.displayName,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().alpha(if (usable) 1f else 0.35f),
        )
        // 选中的压一层暗底，让编号在浅色照片上也读得出来
        if (index > 0) {
            Box(Modifier.fillMaxSize().background(c.overlay))
        }
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(4.dp)
                .size(22.dp)
                .clip(CircleShape)
                .background(if (index > 0) c.accent else c.overlay),
            contentAlignment = Alignment.Center,
        ) {
            if (index > 0) {
                Text("$index", color = c.onAccent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun MediaPickerEmpty(access: MediaPermission.Access, onManagePhotos: (() -> Unit)?) {
    val c = IMTheme.colors
    val d = IMTheme.dimens
    Column(
        modifier = Modifier.fillMaxSize().padding(d.space4),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = if (access == MediaPermission.Access.Partial) "你授权的照片里没有图片" else "相册里没有图片",
            color = c.textSecondary,
        )
        if (onManagePhotos != null) {
            Spacer(Modifier.height(d.space3))
            Text(
                text = "管理授权的照片",
                color = c.accent,
                modifier = Modifier.clickable(onClick = onManagePhotos),
            )
        }
    }
}

/** 4 列：与微信/系统相册同密度；3 列格子太大一屏看不到几张，5 列在 360dp 机上缩略图糊。 */
private const val COLUMNS = 4
private val GAP = 2.dp
