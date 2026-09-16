package com.libeyond.imandroid.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.X
import com.libeyond.imandroid.data.PasteImage
import com.libeyond.imandroid.sdk.logging.IMLog
import com.libeyond.imandroid.ui.theme.IMTheme
import com.libeyond.mediapicker.PickedMedia

/**
 * 粘贴进输入框的待发图片（对齐 iOS 的 `pendingPasteImages` + `pasteBar`）。
 *
 * 机制上的端差异与"为什么要在正文里认 URI"，写在判据 [PasteImage] 的注释里。
 * 这一层只管：**认出来的先挂着，等用户点发送**——不即时发出去。
 * iOS 也是挂一排可撤销的缩略图：粘贴是个容易手滑的动作，直接发出去就撤不回了。
 */
@Stable
internal class PasteImages {
    var items by mutableStateOf<List<PickedMedia>>(emptyList())
        private set

    val isEmpty: Boolean get() = items.isEmpty()

    /**
     * 问过系统、确认**不是图片**的那些 URI。
     *
     * **必须记住**：这段字会原地留在正文里，而 `onInputChange` 每敲一个字都要重跑一遍判据——
     * 不记的话，每按一次键就对同一个 URI 同步调一次 `ContentResolver.getType` + `query`，
     * 而这是在主线程上（碰上慢 provider 就是输入卡顿甚至 ANR）。
     * 这不是 Compose 状态：它只影响"要不要再问一次系统"，不需要触发重组。
     */
    private val rejected = HashSet<String>()

    fun reject(uri: String) {
        rejected += uri
    }

    fun isRejected(uri: String): Boolean = uri in rejected

    /** 满了就不再收（`MAX_PENDING`）。**回报有没有被丢掉**，调用方据此说一句，别静默。 */
    fun add(more: List<PickedMedia>): Boolean {
        if (more.isEmpty()) return false
        val merged = (items + more).distinctBy { it.uri }
        val dropped = merged.size > PasteImage.MAX_PENDING
        items = merged.take(PasteImage.MAX_PENDING)
        return dropped
    }

    fun remove(uri: String) {
        items = items.filterNot { it.uri == uri }
    }

    fun clear() {
        items = emptyList()
    }
}

@Composable
internal fun rememberPasteImages(convId: String): PasteImages = remember(convId) { PasteImages() }

/**
 * 输入框的新值里若混进了粘贴来的图片 URI，把它们收走，返回**应当回填到输入框的值**。
 *
 * 没认出任何图片时**原样返回入参**（连光标位置都不动）——没命中就绝不能碰用户正在打的字。
 *
 * **只摘走自己认领的那几段**（[PasteImage.removing] 按区间摘）：系统说不是图片的那些
 * 原地留着。早先的实现把没认领的统一拼到正文末尾，
 * 「看这个 content://weird 和这个 content://real.jpg」会变成「看这个 和这个 content://weird」
 * ——用户没删没改，字却被搬了家（2026-09-16 `/code-review` 抓出）。
 *
 * @param onNotice 有话要对用户说时调（目前只有"超过上限没收下"）。**不能静默丢**。
 */
internal fun PasteImages.consumeFrom(
    context: Context,
    value: TextFieldValue,
    onNotice: (String) -> Unit,
): TextFieldValue {
    // 已经问过系统、确认不是图片的那些不再重复问（见 PasteImages.rejected）
    val found = PasteImage.find(value.text).filterNot { isRejected(it.uri) }
    if (found.isEmpty()) return value
    val claimed = mutableListOf<PasteImage.Found>()
    val picked = mutableListOf<PickedMedia>()
    for (f in found) {
        val m = pickedImageOrNull(context, f.uri)
        if (m == null) reject(f.uri) else { claimed += f; picked += m }
    }
    if (picked.isEmpty()) return value
    if (add(picked)) onNotice("最多粘 ${PasteImage.MAX_PENDING} 张，多出来的没收下")
    val text = PasteImage.removing(value.text, claimed)
    return TextFieldValue(text, selection = androidx.compose.ui.text.TextRange(text.length))
}

/**
 * 一条 `content://` 是不是能发的图片。不是图片、读不到、没权限一律回 null。
 *
 * 读不到很常见且**不是错误**：剪贴板里可能是别的应用的私有 URI，我们没有它的读权限。
 * 那种情况下这段字就该原样留在输入框里当文本，而不是变成一次失败的发送。
 */
private fun pickedImageOrNull(context: Context, uri: String): PickedMedia? = try {
    val u = Uri.parse(uri)
    val mime = context.contentResolver.getType(u)
    if (mime == null || !mime.startsWith("image/")) {
        null
    } else {
        var name = "pasted.jpg"
        var size = 0L
        context.contentResolver.query(u, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }
                    ?.let { i -> c.getString(i)?.let { name = it } }
                c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }
                    ?.let { i -> if (!c.isNull(i)) size = c.getLong(i) }
            }
        }
        PickedMedia(uri = uri, displayName = name, mime = mime, sizeBytes = size, isVideo = false)
    }
} catch (e: SecurityException) {
    // 别的应用的私有 URI：没权限读是正常的，当它不是图片
    IMLog.tag("IM.Media").w("paste_uri_denied")
    null
} catch (e: Exception) {
    IMLog.tag("IM.Media").w("paste_uri_failed", "err" to e.javaClass.simpleName)
    null
}

/**
 * 输入栏上方那一排可撤销的缩略图（iOS `refreshPasteBar` 的那一条）。
 *
 * **发送归输入栏那颗发送键**（与 iOS 同）：本条只负责"挂着、能逐张撤掉"。
 * 早先这里自带过一颗「发送 n」，是因为输入栏那颗的可用态只看正文、而当时不便改
 * ——2026-09-16 把 `ChatScreen` 的滚动时序拆出去腾出体量后，改成了 `Composer.extraSendable`，
 * 这条与 iOS 的差异就消掉了。
 */
@Composable
internal fun PasteImageBar(images: PasteImages) {
    val c = IMTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().background(c.surface).padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LazyRow(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(images.items, key = { it.uri }) { m ->
                Box(Modifier.size(48.dp)) {
                    AsyncImage(
                        model = m.uri,
                        contentDescription = "待发送的图片",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(6.dp)).background(c.subtleFill),
                    )
                    // ✕ 压在右上角：粘错了要能当场撤掉（iOS `removePastedImageChip:`）
                    Box(
                        Modifier.align(Alignment.TopEnd).size(16.dp).clip(CircleShape)
                            // 用语义令牌，别就地写十六进制（CODING_STYLE §六）
                            .background(c.overlayStrong)
                            .clickable { images.remove(m.uri) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Image(
                            Lucide.X, "移除", Modifier.size(10.dp),
                            colorFilter = ColorFilter.tint(Color.White),
                        )
                    }
                }
            }
        }
    }
}
