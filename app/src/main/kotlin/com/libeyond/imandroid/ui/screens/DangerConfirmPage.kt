package com.libeyond.imandroid.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.TriangleAlert
import com.libeyond.imandroid.ui.components.IMErrorText
import com.libeyond.imandroid.ui.components.IMPrimaryButton
import com.libeyond.imandroid.ui.components.IMSettingsGroup
import com.libeyond.imandroid.ui.components.IMTopBar
import com.libeyond.imandroid.ui.theme.IMTheme

/**
 * 危险操作二级页的共用版式（注销账号 / 清除所有对话；两页**不复制分叉**，见 CLEAR_ALL_CONVERSATIONS_DESIGN §2）。
 *
 * 自上而下：警示头 → 后果卡（`•` 开头）→ 提示条 →（[extras]：阻断卡 / 密码框等页面独有内容）→ 错误行 → 红按钮。
 * 纯展示，状态与确认弹窗由各自的 Host 持有。
 */
@Composable
fun DangerConfirmPage(
    title: String,
    header: String,
    consequences: List<String>,
    hint: String,
    error: String,
    buttonText: String,
    buttonEnabled: Boolean,
    loading: Boolean,
    onSubmit: () -> Unit,
    onBack: () -> Unit,
    extras: @Composable ColumnScope.() -> Unit = {},
) {
    val d = IMTheme.dimens
    Column(Modifier.fillMaxSize().background(IMTheme.colors.groupedBackground).statusBarsPadding()) {
        IMTopBar(title = title, onLeft = onBack)

        Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(d.space4 * 2))
            DangerHeader(header)
            Spacer(Modifier.height(d.space4))
            DangerConsequences(consequences)
            Spacer(Modifier.height(d.space3))
            DangerInfoBar(hint)
            extras()
            IMErrorText(
                text = error,
                modifier = Modifier.padding(start = d.space4 * 2, end = d.space4 * 2, top = d.space2),
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(d.space4 + d.space1))
            IMPrimaryButton(
                text = buttonText,
                onClick = onSubmit,
                modifier = Modifier.padding(horizontal = d.space4),
                enabled = buttonEnabled,
                loading = loading,
                danger = true,
            )
            Spacer(Modifier.height(d.sectionGap))
        }
    }
}

/** 警示头：64dp 圆（danger 12%）+ 30dp 图标 + 18sp 粗标题。 */
@Composable
private fun DangerHeader(text: String) {
    val c = IMTheme.colors
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(64.dp).clip(CircleShape).background(c.dangerSoft),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                imageVector = Lucide.TriangleAlert,
                contentDescription = null,
                modifier = Modifier.size(30.dp),
                colorFilter = ColorFilter.tint(c.danger),
            )
        }
        Spacer(Modifier.height(IMTheme.dimens.space3))
        Text(
            text = text,
            color = c.textPrimary,
            style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp, fontWeight = FontWeight.SemiBold),
        )
    }
}

/** 后果（分组卡内，`•` 开头，不可点）：15sp、行间 8dp、内边距 space4。 */
@Composable
private fun DangerConsequences(lines: List<String>) {
    val c = IMTheme.colors
    IMSettingsGroup {
        Column(Modifier.padding(IMTheme.dimens.space4), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            lines.forEach { line ->
                Row {
                    Text("•  ", color = c.textPrimary, style = MaterialTheme.typography.bodyLarge)
                    Text(line, color = c.textPrimary, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

/** 提示条：13sp，accent 12% 底，圆角 12dp，内边距 12dp。 */
@Composable
private fun DangerInfoBar(text: String) {
    val c = IMTheme.colors
    Text(
        text = text,
        color = c.accent,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier
            .padding(horizontal = IMTheme.dimens.space4)
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(c.accentSoft)
            .padding(12.dp),
    )
}
