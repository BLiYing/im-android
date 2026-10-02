package com.libeyond.imandroid.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.composables.icons.lucide.Download
import com.composables.icons.lucide.Ellipsis
import com.composables.icons.lucide.LayoutGrid
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.X
import com.libeyond.imandroid.R
import com.libeyond.imandroid.data.MediaDownloader
import com.libeyond.imandroid.data.MediaTimeline
import com.libeyond.imandroid.data.MediaUrl
import com.libeyond.imandroid.data.OriginalVideo
import com.libeyond.imandroid.data.ViewerMedia
import com.libeyond.imandroid.sdk.protocol.ContentType
import com.libeyond.imandroid.ui.components.SheetItem
import com.libeyond.imandroid.ui.rememberFrostedPainter
import com.libeyond.imandroid.ui.theme.IMTheme
import com.libeyond.mediapicker.ZoomableImage
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

// 右下角圆钮照 iOS `circleButtonWithSymbol`：直径 44、钮间距 14、距安全区边 16
private val VIEWER_BUTTON = 44.dp
private val VIEWER_GAP = 14.dp
private val VIEWER_EDGE = 16.dp

/**
 * 视频进度条那一行的下沿：贴在右下角按钮排**上方** 14（iOS `row.bottom = downloadButton.top - 14`）。
 * 此前进度条画在屏幕最底、按钮排也在最底，两排叠在一起（2026-09-16 用户报）。
 */
internal val VIDEO_BAR_BOTTOM = VIEWER_EDGE + VIEWER_BUTTON + VIEWER_GAP

/** 「查看原视频」胶囊再往上让一层，别压住进度条。 */
private val ORIGINAL_CHIP_BOTTOM = VIDEO_BAR_BOTTOM + 44.dp

/**
 * 媒体查看器：图片可缩放、视频可播放、**在会话媒体时间线上左右翻页**。
 *
 * **图片那半复用 `:media-picker` 的 [ZoomableImage]**，不另写一份——
 * 那是本工程第一份可缩放查看器，两份实现必然在「边界回弹」「双击倍率」上分叉。
 * 它当初就是照着"外层套 HorizontalPager"写的：**1 倍时单指横滑放行给翻页，放大后才归它**
 * （那份注释里记着 2026-09-07 的真机症状）。
 *
 * 右下角一排**左→右：更多 / 媒体 / 下载**，与 iOS 查看器同位同序（用户靠位置形成肌肉记忆）。
 *
 * ### 壳固定、内容翻页（照 iOS 的 `IMMediaPagerViewController` + `chromeless`）
 * 关闭钮 / i·N / 右下角按钮排都画在**翻页容器之外**，翻页时不跟着滑。
 * 「更多」里的动作按**当前那一条**现算（[moreActionsFor]）——iOS 那侧的注释写明了理由：
 * 翻页容器按下标对每条各建一份，才能保证收藏/转发/删除/定位作用在正确的消息上。
 *
 * ### 序列在前面变长时要把页码挪回来
 * 续拉更早的一页会把整段往后推 `added` 格。**当前看的那条按 `conv_seq` 记、不记下标**，
 * 序列一变就重新定位回同一条；不这么做的话，正在看的图会在拉到新数据的瞬间自己跳走
 * （判据与计数在 `data/MediaTimeline.kt`，同一条不变式在会话内搜索翻页上栽过）。
 */
@Composable
@Suppress("LongParameterList")
internal fun MediaViewerScreen(
    /** 翻页序列（升序：旧→新）。**调用方保证非空**：定位不到时也至少放当前这一条。 */
    pages: List<ViewerMedia>,
    /** 打开时看哪一条（`conv_seq`）。不在 [pages] 里就从第一条开始。 */
    startSeq: Long,
    /**
     * 顶部标题＝**会话名**（iOS `IMMediaPagerViewController` 的 `conversationTitle`）。
     * 空串则只剩页码那一行。见 [ViewerTopBar] 的注释：iOS 是"标题在上、i/N 在下"两行。
     */
    title: String = "",
    host: String,
    useTls: Boolean,
    /** 已下载到本地的原件（由 Host 从下载器取）。有就用它显示/播放/存相册。 */
    localFileOf: (ViewerMedia) -> java.io.File? = { null },
    /**
     * 下载器：给「查看原视频」胶囊用（状态 + 发起下载）。
     * `null` = 不画胶囊——聊天记录页那条路的媒体不属于本会话，没有下载门控这回事。
     */
    downloads: MediaDownloader? = null,
    onSave: (url: String, isVideo: Boolean) -> Unit,
    /**
     * 「更多」里的外部动作（iOS `moreActions`），**按当前那一条现算**。
     * 空 = 不画「更多」钮；非空时本页在菜单最前面自己加一项「下载」。
     * **外部动作要先关查看器再执行**（调用方在 onClick 里关）：转发选择页、删除选择单要盖在
     * 聊天页上下文里，叠在查看器之上会出现「关掉上面那层还留着一层黑底大图」的怪状态。
     */
    moreActionsFor: (ViewerMedia) -> List<SheetItem> = { emptyList() },
    /** 「媒体」钮：去本会话的媒体归档（iOS 媒体库入口）。null = 不画——从归档里点进来的就不给，免得来回套娃。 */
    onOpenGallery: (() -> Unit)? = null,
    /**
     * 一句降级说明（`null` = 没有）。翻页序列续拉失败/离线时由宿主给出——
     * OFFLINE_BACKLOG_DESIGN §4.9 的口径是「可以少，不可以错；**少了要说出来**」：
     * 停在已下载的那段本身没错，不说才是错。
     */
    notice: String? = null,
    /**
     * 快翻到**最旧**那一端了（参数是当前下标），调用方可以去续拉更早的一页。
     * **在途守卫与「还有没有更早」由调用方判**（`MediaTimeline.wantsOlder`）——那两个事实在 Host 手上。
     */
    onNearOldest: (index: Int) -> Unit = {},
    /** 快翻到**最新**那一端了（本地段上沿）：调用方可去服务端要更新的一页（`MediaTimeline.wantsNewer`）。 */
    onNearNewest: (index: Int) -> Unit = {},
    onClose: () -> Unit,
) {
    if (pages.isEmpty()) return
    val startIndex = MediaTimeline.indexOf(pages, startSeq).coerceAtLeast(0)
    val state = rememberPagerState(initialPage = startIndex, pageCount = { pages.size })
    val current = pages[state.currentPage.coerceIn(0, pages.lastIndex)]

    // 当前看的那条**按 conv_seq 记**（见上方注释：下标会被续拉的更早一页整体推走）
    var anchorSeq by remember { mutableLongStateOf(if (startSeq > 0) startSeq else pages[startIndex].convSeq) }
    // ⚠️ **这个 effect 的 key 里绝不能有 `pages`**（2026-09-16 /code-review 抓出）：
    // 续拉把 N 条插到前面时 `currentPage` 数值不变、指向的却已经是另一条；带上 `pages` 的话它会先跑，
    // 把锚点覆盖成那条错的，下面那个"回正"随即判定「原地即可」而不再 scrollToPage——
    // 于是画面在用户没动的情况下静默跳到另一张图，而且下标一直停在边缘、每次续拉完又触发下一次，
    // 把整条会话的媒体历史连环拉完。只认 `currentPage`：**只有用户真的翻页才换锚点**。
    LaunchedEffect(state.currentPage) {
        pages.getOrNull(state.currentPage)?.let { anchorSeq = it.convSeq }
    }
    LaunchedEffect(pages) {
        val i = MediaTimeline.indexOf(pages, anchorSeq)
        if (i >= 0 && i != state.currentPage) state.scrollToPage(i)
    }
    LaunchedEffect(state.currentPage, pages.size) {
        if (state.currentPage <= MediaTimeline.PREFETCH_MARGIN) onNearOldest(state.currentPage)
        if (state.currentPage >= pages.size - 1 - MediaTimeline.PREFETCH_MARGIN) onNearNewest(state.currentPage)
    }

    var moreOpen by remember { mutableStateOf(false) }

    /**
     * **下完原件的那一刻要重算一次**「本地有没有它」：胶囊该消失、播放器该切到本地那份。
     *
     * `localFileOf` 是查看器在重组时现问下载器的，而下载器的状态在另一条流上——
     * 本函数不重组，这两件事就都停在下载开始前的答案（2026-09-16 用户报：
     * 下完了胶囊还在，重进才消失；播放器也继续流式播远端那份，等于白下）。
     *
     * **只收「谁已就绪」这个集合，不读整张状态表**：那张表在下载期间每 64KB 就发一次
     * （`MediaDownloader.report`），整个查看器（可缩放大图 + ExoPlayer 容器）跟着每秒重组几十次，
     * 换来的是比原先更明显的卡顿。就绪集合只在下载完成那一刻变一次，`distinctUntilChanged`
     * 把中间的进度帧全滤掉；百分比文案由 [OriginalVideoChip] 自己那一层单独 collect。
     */
    val readyUrls by remember(downloads) {
        downloads?.states?.map { OriginalVideo.readyUrls(it) }?.distinctUntilChanged()
            ?: flowOf(emptySet())
    }.collectAsState(initial = emptySet())

    // `remember(readyUrls)` 是这根线的**显式依赖**：就绪集合一变就换一个新的 lambda 实例，
    // 翻页容器与胶囊捕获到的东西变了，才会真的去重新问一遍本地文件。
    val localOf: (ViewerMedia) -> java.io.File? = remember(readyUrls, localFileOf) {
        { vm -> localFileOf(vm) }
    }
    val source = sourceOf(current, localOf, host, useTls)

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(state = state, modifier = Modifier.fillMaxSize()) { page ->
            val item = pages[page]
            // **只有当前这一页才建播放器**：ExoPlayer 一页一个实例，相邻页也建的话白占内存与解码器
            // （播不起来是不会的——`playWhenReady=false`，但没必要）。非当前页显封面。
            if (item.contentType == ContentType.VIDEO && page == state.currentPage) {
                VideoPlayer(
                    localFile = localOf(item),
                    url = item.content,
                    posterUrl = item.poster,
                    host = host,
                    useTls = useTls,
                    controlsBottomPadding = VIDEO_BAR_BOTTOM,
                )
            } else if (item.contentType == ContentType.VIDEO) {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    if (item.poster.isNotBlank()) {
                        AsyncImage(
                            model = MediaUrl.absolute(item.poster, host, useTls),
                            contentDescription = stringResource(R.string.chat_media_alt_video_cover),
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            } else {
                ZoomableImage(
                    model = sourceOf(item, localOf, host, useTls),
                    contentDescription = stringResource(R.string.common_image),
                    // 磨砂占位：没下到本地的原图是现拉的，那几秒不能是纯黑
                    // （iOS `showThumbPlaceholder` 同）。没有 thumb 的老消息仍回落黑底。
                    placeholder = rememberFrostedPainter(item.thumb),
                )
            }
        }

        // 「查看原视频」（iOS `_originalChip`）：没下到本地的视频是流式播的，拖进度要等、断网放不了。
        // 判据在 data/OriginalVideo.kt，这里只负责画。
        if (downloads != null) {
            OriginalVideoChip(
                item = current,
                hasLocal = localOf(current) != null,
                downloads = downloads,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = ORIGINAL_CHIP_BOTTOM, start = 16.dp, end = 16.dp),
            )
        }

        // 关闭：左上角。**图片不做「点空白关闭」**——可缩放，点空白与拖动/双击抢手势。
        Row(
            modifier = Modifier.fillMaxWidth().systemBarsPadding().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ViewerButton(Lucide.X, stringResource(R.string.common_close), onClose)
            // **标题在上、页码在下**，逐条对齐 iOS：`IMMediaPagerViewController` 用的是聊天页同款
            // `IMLiquidNavigationBar`，主标题＝会话名（17 semibold），副标题＝`i / N`（13 regular、次要灰），
            // 且 `_count <= 1` 时副标题为空串。本端此前只有一个居中的 `21/21`，没有标题
            // （2026-09-17 用户报：查看器标题与 iOS 不一致）。
            // i/N：只有真能翻页时才画（iOS `_count <= 1` 时副标题为空串）
            val showsCounter = pages.size > 1
            // **两样都没有就整块不画**：否则单张、又没给标题时会凭空多出一个空列 + 右侧占位
            // （2026-09-17 `/code-review` 抓出的 nit）。
            if (title.isNotBlank() || showsCounter) {
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (title.isNotBlank()) {
                        Text(
                            title,
                            color = Color.White,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (showsCounter) {
                        Text(
                            "${state.currentPage + 1} / ${pages.size}",
                            // iOS 副标题是 secondaryLabel，在深色查看器上解析成半透明白
                            color = Color(0xB3FFFFFF),
                            fontSize = 13.sp,
                        )
                    }
                }
                // 右侧留出与关闭钮等宽的空位，让标题真正居中
                Box(Modifier.size(VIEWER_BUTTON))
            }
        }

        // 降级说明：挂在顶栏下方，**不挡画面中心、也不与右下角按钮排抢位置**
        if (!notice.isNullOrBlank()) {
            Text(
                notice,
                color = Color.White,
                fontSize = 13.sp,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .systemBarsPadding()
                    .padding(top = 64.dp, start = 24.dp, end = 24.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0x99000000))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(end = VIEWER_EDGE, bottom = VIEWER_EDGE),
            horizontalArrangement = Arrangement.spacedBy(VIEWER_GAP),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val more = moreActionsFor(current)
            if (more.isNotEmpty()) {
                // 菜单锚在「⋯」上，靠近屏幕下沿时 DropdownMenu 自己往上展开（iOS IMPopoverCard 同）
                Box {
                    ViewerButton(Lucide.Ellipsis, stringResource(R.string.common_more)) { moreOpen = true }
                    ViewerMoreMenu(
                        open = moreOpen,
                        // 内置「下载」排最前、**不关查看器**（iOS `showMoreSheet` 同）
                        items = listOf(
                            SheetItem(stringResource(R.string.common_download), icon = Lucide.Download) { onSave(source, current.isVideo) },
                        ) + more,
                        onDismiss = { moreOpen = false },
                    )
                }
            }
            if (onOpenGallery != null) ViewerButton(Lucide.LayoutGrid, stringResource(R.string.favorites_category_media), onOpenGallery)
            ViewerButton(Lucide.Download, stringResource(R.string.qr_card_save_to_album)) { onSave(source, current.isVideo) }
        }
    }
}

/**
 * 「查看原视频 · 12.3MB」/「下载中 42%」/「下载失败，点击重试」。
 * 文案与"显不显"的判据在 [OriginalVideo]，这里只画与接线。
 */
@Composable
private fun OriginalVideoChip(
    item: ViewerMedia,
    hasLocal: Boolean,
    downloads: MediaDownloader,
    modifier: Modifier = Modifier,
) {
    // collect 才能在下载进度变化时重绘；没有条目时退回 stateOf（它会看本地文件）
    val states by downloads.states.collectAsState()
    val st = states[item.content] ?: downloads.stateOf(item.content, item.isVideo)
    val label = OriginalVideo.chipLabel(
        isVideo = item.isVideo,
        hasLocal = hasLocal,
        phase = st.phase,
        fraction = st.fraction,
        hasPercent = st.hasPercent,
        sizeBytes = item.sizeBytes,
    ) ?: return
    Text(
        label,
        color = Color.White,
        fontSize = 14.sp,
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xAA000000))
            .clickable {
                if (OriginalVideo.tapStartsDownload(st.phase)) {
                    downloads.start(item.content, isVideo = true, expectedBytes = item.sizeBytes)
                }
            }
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

/**
 * 这一条该从哪儿读。
 *
 * 待发/失败的那条 content 是本地 `content://` —— 原样用，别拼服务端前缀。
 * 存相册与渲染必须是**同一个地址**：分开算过一次就会出现「看到的是本地原图、
 * 存下来的是服务端压缩件」这种对不上账的情况。
 * **已下载的原件优先**：门控刚把它下到本地，查看器再从网络拉一遍就是白下。
 */
private fun sourceOf(
    item: ViewerMedia,
    localFileOf: (ViewerMedia) -> java.io.File?,
    host: String,
    useTls: Boolean,
): String = localFileOf(item)?.let { android.net.Uri.fromFile(it).toString() }
    ?: MediaUrl.absolute(item.content, host, useTls)

/** 查看器上的圆形按钮：黑底半透明 + 白色描边图标，压在任何画面上都看得见。 */
@Composable
private fun ViewerButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(VIEWER_BUTTON)
            .clip(CircleShape)
            .background(Color(0x66000000))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            imageVector = icon,
            contentDescription = label,
            modifier = Modifier.size(20.dp),
            colorFilter = ColorFilter.tint(Color.White),
        )
    }
}

@Composable
private fun ViewerMoreMenu(open: Boolean, items: List<SheetItem>, onDismiss: () -> Unit) {
    val c = IMTheme.colors
    DropdownMenu(expanded = open, onDismissRequest = onDismiss) {
        items.forEach { item ->
            val fg = if (item.destructive) c.danger else c.textPrimary
            DropdownMenuItem(
                text = { Text(item.label, color = fg) },
                leadingIcon = item.icon?.let { icon ->
                    { Image(icon, null, Modifier.size(18.dp), colorFilter = ColorFilter.tint(fg)) }
                },
                onClick = {
                    onDismiss()
                    item.onClick()
                },
            )
        }
    }
}
