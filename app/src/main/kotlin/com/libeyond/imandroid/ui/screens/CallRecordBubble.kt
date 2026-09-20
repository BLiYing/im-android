package com.libeyond.imandroid.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Phone
import com.composables.icons.lucide.Video
import com.libeyond.imandroid.data.CallRecord
import com.libeyond.imandroid.ui.theme.IMTheme

/**
 * 通话记录（单聊）气泡内容。规格取自 UX 稿 §03：
 * 图标边长 = chatFontSize + 5、与文字垂直居中、间距 8；正文 chatFontSize 单行不换行；时间 / 勾靠右底对齐、与文字间距 10；
 * 竖内边距比文本气泡多 4（撑出 ≥ 40 的触控高，气泡壳自带的 6 + 这里的 4）。
 * 颜色只用语义令牌：正文 `textPrimary`，被叫侧「未接来电」`danger`（不随聊天主题变）。
 *
 * 语音 / 视频只靠图标区分，正文不重复写「语音」「视频」。整个气泡的点击（回拨）与按下态由 [Bubble] 接。
 */
@Composable
internal fun CallRecordContent(
    content: String,
    viewerIsSender: Boolean,
    footerTrailing: (@Composable () -> Unit)? = null,
) {
    val c = IMTheme.colors
    val fontSize = IMTheme.appearance.chatFontSize
    val call = CallRecord.parse(content)
    val r = CallRecord.renderRaw(content, viewerIsSender)
    val tint = if (r.tone == CallRecord.Tone.Missed) c.danger else c.textPrimary
    Row(
        modifier = Modifier.padding(vertical = 4.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.Start,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(
                imageVector = if (call?.video == true) Lucide.Video else Lucide.Phone,
                contentDescription = null,
                modifier = Modifier.size((fontSize.value + 5).dp),
                colorFilter = ColorFilter.tint(tint),
            )
            Spacer(Modifier.width(8.dp))
            Text(text = r.text, color = tint, fontSize = fontSize, maxLines = 1, softWrap = false, overflow = TextOverflow.Clip)
        }
        if (footerTrailing != null) {
            Spacer(Modifier.width(10.dp))
            footerTrailing()
        }
    }
}
