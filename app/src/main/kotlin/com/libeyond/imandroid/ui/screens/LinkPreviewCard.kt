package com.libeyond.imandroid.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.libeyond.imandroid.data.LinkDetect
import com.libeyond.imandroid.data.MediaUrl
import com.libeyond.imandroid.sdk.api.LinkPreview
import com.libeyond.imandroid.ui.components.passThroughTap
import com.libeyond.imandroid.ui.theme.IMTheme

/**
 * 文本气泡里首个 URL 的富预览卡（对齐 iOS `IMLinkPreviewView`）。
 *
 * 规格：标题 semibold 最多 2 行、描述 12 号最多 2 行、站点名 11 号次要色、左侧强调色竖条。
 *
 * **抓不到就不出卡**：服务端抓空、限流、断网一律安静退化成纯链接文本——
 * 出一张只有 URL 的空卡片等于噪音（iOS 的 nil 语义同）。
 */
@Composable
internal fun LinkPreviewCard(
    url: String,
    /** 取预览。由 ChatHost 注入——**screen 层不持有 IMClient**，只吃数据与回调。 */
    load: suspend (String) -> LinkPreview?,
    host: String,
    useTls: Boolean,
    /** 点卡片打开链接（iOS `IMLinkPreviewView.onTap` → `openLink:`）。null = 不可点。此前卡片没有任何点击。 */
    onTap: ((String) -> Unit)? = null,
) {
    val c = IMTheme.colors
    var preview by remember(url) { mutableStateOf<LinkPreview?>(null) }

    LaunchedEffect(url) {
        // 进程内缓存：同一条链接在一屏里可能出现多次，且这个接口与 /qr/resolve
        // 共享每账号 60/min——一屏十条各请求一次就能把配额打光。
        preview = LinkPreviewCache.get(url) ?: load(url)?.also { LinkPreviewCache.put(url, it) }
    }

    val p = preview ?: return

    Column(
        modifier = Modifier
            .padding(top = 6.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(c.subtleFill)
            // 只吃轻点、长按让给外层气泡：用 clickable 的话「长按带链接的文本」在卡片上弹不出菜单
            .passThroughTap(enabled = onTap != null) { onTap?.invoke(url) },
    ) {
        if (p.image.isNotBlank()) {
            // 图**贴卡片上、左、右三边**，上面两角由卡片的圆角裁（iOS `IMLinkPreviewView` 的 `_thumb`
            // 三边贴 self、Web 同）。此前整卡 8/6 内边距把图框在里面，三端一眼两样（2026-09-15 用户报）
            AsyncImage(
                model = MediaUrl.absolute(p.image, host, useTls),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .background(c.neutralControl),
            )
        }
        // 文字仍留内边距；有图时这 6 的顶距就是图与标题的间隔
        Column(Modifier.fillMaxWidth().padding(start = 8.dp, top = 6.dp, end = 8.dp, bottom = 6.dp)) {
            LinkPreviewTexts(p, url)
        }
    }
}

@Composable
private fun LinkPreviewTexts(p: LinkPreview, url: String) {
    val c = IMTheme.colors
    Column {
        if (p.title.isNotBlank()) {
            Text(
                p.title, color = c.textPrimary, fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
        if (p.description.isNotBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(
                p.description, color = c.textSecondary, fontSize = 12.sp,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(3.dp))
        Text(
            p.siteName.ifBlank { LinkDetect.hostOf(url) },
            color = c.textTertiary, fontSize = 11.sp, maxLines = 1,
        )
    }
}

/**
 * 链接预览的**进程内**缓存。
 *
 * 刻意不落库：预览是可再取的派生数据，落库要跟着做失效策略。
 * 上限 200 条、超了整体清空（不做 LRU）——聊天页一次会话撑死几十条链接，
 * 为这点量写淘汰算法不划算，而无界 Map 在长会话里是真的会涨。
 */
private object LinkPreviewCache {
    private const val CAP = 200
    private val map = java.util.concurrent.ConcurrentHashMap<String, LinkPreview>()
    fun get(url: String): LinkPreview? = map[url]
    fun put(url: String, p: LinkPreview) {
        if (map.size >= CAP) map.clear()
        map[url] = p
    }
}
