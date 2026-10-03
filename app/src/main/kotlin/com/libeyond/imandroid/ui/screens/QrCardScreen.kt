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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.libeyond.imandroid.R
import com.libeyond.imandroid.data.QrEncode
import com.libeyond.imandroid.sdk.api.QrCard
import com.libeyond.imandroid.ui.components.IMAvatar
import com.libeyond.imandroid.ui.components.IMTopBar
import com.libeyond.imandroid.ui.components.QrCodeView
import com.libeyond.imandroid.ui.theme.IMTheme

/**
 * 一枚码的展示页——名片码 / 群二维码 / 群邀请链接三种共用（对齐 iOS `IMQRCardViewController`：
 * 一个 VC、`asLink` 只改标题文案；群二维码与群邀请链接同源同权限，此处对应 [title] 不同）。
 *
 * 副标题（[subtitle]）显示**公开信息**而不是内部 ID：名片码是公开句柄，群码是「XX 人」——
 * 都不落 uid/群内部 ID。没有就留空，不显示「未设置」。
 */
@Composable
fun QrCardScreen(
    /** 嵌入扫一扫页时为 false：宿主已有顶栏。 */
    showTopBar: Boolean = true,
    card: QrCard?,
    title: String,
    displayName: String,
    subtitle: String,
    avatarUrl: String,
    seed: String,
    error: String,
    hint: String,
    onCopyLink: () -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit,
    /** null＝不显示「重置」（群码非群主/管理员时——对齐 iOS `resetButton.hidden`）。 */
    onReset: (() -> Unit)?,
    onBack: () -> Unit,
) {
    val c = IMTheme.colors
    val d = IMTheme.dimens
    val code = card?.codeString.orEmpty()
    // 编码只跟内容有关，重组不该重算（一次编码是毫秒级但每帧都做就不合适了）。
    val matrix = remember(code) { QrEncode.encode(code) }
    val ready = matrix != null

    Column(Modifier.fillMaxSize().background(c.groupedBackground).statusBarsPadding()) {
        if (showTopBar) IMTopBar(title = title, onLeft = onBack)

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
                if (subtitle.isNotEmpty()) {
                    Spacer(Modifier.height(2.dp))
                    Text(subtitle, color = c.textSecondary, style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(20.dp))
                QrCodeView(
                    matrix = matrix,
                    size = d.qrCode,
                    modifier = Modifier.clip(RoundedCornerShape(d.space2)),
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    text = error.ifEmpty { hint },
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
                CardButton(stringResource(R.string.qr_card_save_to_album), primary = false, enabled = ready, onClick = onSave, modifier = Modifier.weight(1f))
                CardButton(stringResource(R.string.common_share), primary = true, enabled = ready, onClick = onShare, modifier = Modifier.weight(1f))
            }

            Spacer(Modifier.height(d.space3))
            Text(
                text = stringResource(R.string.qr_copy_link),
                color = if (ready) c.accent else c.textTertiary,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.clickable(enabled = ready, onClick = onCopyLink).padding(6.dp),
            )
            if (onReset != null) {
                Spacer(Modifier.height(d.space2))
                Text(
                    text = stringResource(R.string.qr_reset),
                    color = c.textSecondary,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.clickable(onClick = onReset).padding(6.dp),
                )
            }
            Spacer(Modifier.height(d.sectionGap))
        }
    }
}

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
