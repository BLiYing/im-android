package com.libeyond.imandroid.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.FileText
import com.composables.icons.lucide.Lucide
import com.libeyond.imandroid.R
import com.libeyond.imandroid.data.LongText
import com.libeyond.imandroid.ui.theme.IMTheme

/** 「展开全文 ∨」/「收起 ∧」那一行（13sp 中粗、强调色）。整个气泡可点，这行只是提示。 */
@Composable
internal fun LongTextAffordance(expanded: Boolean) {
    val c = IMTheme.colors
    Text(
        text = if (expanded) stringResource(R.string.chat_text_collapse) + " ∧" else stringResource(R.string.chat_text_expand) + " ∨",
        color = c.accent, fontSize = 13.sp, fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(top = 4.dp),
    )
}

/**
 * 超长文本（≥2000 字 / ≥60 行）的摘要卡，**气泡里不出现正文**：
 * 「📄 长文本 · 约 8,400 字」/ 前 3 行 120 字预览 / 「查看全文 ›」。点开进全屏阅读页。
 */
@Composable
internal fun LongTextCard(content: String, chatFontSize: androidx.compose.ui.unit.TextUnit) {
    val c = IMTheme.colors
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Lucide.FileText, null, tint = c.textPrimary, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(4.dp))
            Text(
                stringResource(
                    R.string.chat_text_long_title,
                    stringResource(R.string.chat_text_approx_chars, LongText.countLabel(LongText.charCount(content))),
                ),
                color = c.textPrimary, fontSize = chatFontSize, fontWeight = FontWeight.SemiBold,
            )
        }
        Text(
            LongText.hugePreview(content), color = c.textSecondary, fontSize = 13.sp,
            maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp),
        )
        Text(
            stringResource(R.string.chat_text_view_full) + " ›",
            color = c.accent, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 4.dp),
        )
    }
}
