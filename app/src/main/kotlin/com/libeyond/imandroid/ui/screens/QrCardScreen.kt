package com.libeyond.imandroid.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.libeyond.imandroid.data.QrEncode
import com.libeyond.imandroid.sdk.api.QrCard
import com.libeyond.imandroid.ui.components.IMAvatar
import com.libeyond.imandroid.ui.components.IMTopBar
import com.libeyond.imandroid.ui.components.QrCodeView
import com.libeyond.imandroid.ui.theme.IMTheme

/**
 * 我的二维码（对齐 iOS `IMQRCardViewController` 的名片码分支）。
 *
 * 副标题显示**公开句柄**而不是 uid：这张卡是给别人看的，一串 10 位随机数字对方认不出是谁。
 * 没有句柄就留空——不显示「未设置」，更不回退内部 ID。
 */
@Composable
fun QrCardScreen(
    card: QrCard?,
    displayName: String,
    handle: String,
    avatarUrl: String,
    seed: String,
    error: String,
    onCopyLink: () -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onReset: () -> Unit,
    onBack: () -> Unit,
) {
    val c = IMTheme.colors
    val d = IMTheme.dimens
    val code = card?.codeString.orEmpty()
    // 编码只跟内容有关，重组不该重算（一次编码是毫秒级但每帧都做就不合适了）。
    val matrix = remember(code) { QrEncode.encode(code) }
    val ready = matrix != null

    Column(Modifier.fillMaxSize().background(c.groupedBackground).statusBarsPadding()) {
        IMTopBar(title = "我的二维码", onLeft = onBack)

        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = d.space4),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(d.space3))

            Column(
                modifier = Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(d.radiusCard))
                    .background(c.cardBackground)
                    .padding(vertical = 24.dp, horizontal = d.space4),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                IMAvatar(displayName, seed = seed, avatarUrl = avatarUrl, size = d.qrCardAvatar)
                Spacer(Modifier.height(10.dp))
                Text(
                    text = displayName,
                    color = c.textPrimary,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                )
                if (handle.isNotEmpty()) {
                    Spacer(Modifier.height(2.dp))
                    Text(handle, color = c.textSecondary, style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(20.dp))
                QrCodeView(
                    matrix = matrix,
                    size = d.qrCode,
                    modifier = Modifier.clip(RoundedCornerShape(d.space2)),
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    text = if (error.isNotEmpty()) error else HINT,
                    color = if (error.isNotEmpty()) c.danger else c.textSecondary,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                )
            }

            Spacer(Modifier.height(d.space4))

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(d.space3),
            ) {
                CardButton("保存到相册", primary = false, enabled = ready, onClick = onSave, modifier = Modifier.weight(1f))
                CardButton("分享", primary = true, enabled = ready, onClick = onShare, modifier = Modifier.weight(1f))
            }

            Spacer(Modifier.height(d.space3))
            Text(
                text = "复制链接",
                color = if (ready) c.accent else c.textTertiary,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.clickable(enabled = ready, onClick = onCopyLink).padding(6.dp),
            )
            Spacer(Modifier.height(d.space2))
            Text(
                text = "重置二维码",
                color = c.textSecondary,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.clickable(onClick = onReset).padding(6.dp),
            )
            Spacer(Modifier.height(d.sectionGap))
        }
    }
}

/** 名片码长期有效，所以文案里写死「长期有效」；群码那套有效期文案等接群码时再说。 */
private const val HINT = "扫描二维码，加我为朋友\n该码长期有效，重置后旧码立即失效"

@Composable
private fun CardButton(
    text: String,
    primary: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = IMTheme.colors
    Text(
        text = text,
        color = when {
            !enabled -> c.textTertiary
            primary -> c.onAccent
            else -> c.accent
        },
        style = MaterialTheme.typography.titleSmall,
        textAlign = TextAlign.Center,
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(
                when {
                    !enabled -> c.subtleFill
                    primary -> c.accent
                    else -> c.cardBackground
                },
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 13.dp),
    )
}
