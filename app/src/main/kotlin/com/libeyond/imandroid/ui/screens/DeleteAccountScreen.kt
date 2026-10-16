package com.libeyond.imandroid.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.TriangleAlert
import com.libeyond.imandroid.R
import com.libeyond.imandroid.data.DeleteAccountRules
import com.libeyond.imandroid.ui.components.IMPrimaryButton
import com.libeyond.imandroid.ui.components.IMSettingsGroup
import com.libeyond.imandroid.ui.components.IMTopBar
import com.libeyond.imandroid.ui.theme.IMTheme

/**
 * 注销账号二级页（IMServer `docs/design/DELETE_ACCOUNT_DESIGN.md` §3/§4 + 草图 DELETE_ACCOUNT_UX_SKETCH.html）。
 *
 * 纯展示：密码 / 错误 / 群主阻断名单 / 提交态由 `DeleteAccountHost` 持有。
 * 自上而下：警示头 → 4 条后果 → 冷静期提示条 →（群主阻断卡）→ 密码框 → 错误行 → 红按钮。
 */
@Composable
fun DeleteAccountScreen(
    password: String,
    onPasswordChange: (String) -> Unit,
    error: String,
    ownedGroups: List<String>,
    submitting: Boolean,
    onSubmit: () -> Unit,
    onBack: () -> Unit,
) {
    val c = IMTheme.colors
    val d = IMTheme.dimens
    val blocked = ownedGroups.isNotEmpty()
    val canSubmit = DeleteAccountRules.canSubmit(password, submitting, ownedGroups)
    val focus = remember { FocusRequester() }

    Column(Modifier.fillMaxSize().background(c.groupedBackground).statusBarsPadding()) {
        IMTopBar(title = stringResource(R.string.delete_account_title), onLeft = onBack)

        Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(d.space4 * 2))
            Header()
            Spacer(Modifier.height(d.space4))
            Consequences()
            Spacer(Modifier.height(d.space3))
            InfoBar(stringResource(R.string.delete_account_cooldown_hint), blocked = false)
            if (blocked) {
                Spacer(Modifier.height(d.space3))
                OwnedGroupsCard(ownedGroups)
            }
            Spacer(Modifier.height(d.sectionGap))
            IMSettingsGroup {
                PasswordRow(
                    value = password, onValueChange = onPasswordChange,
                    placeholder = stringResource(R.string.delete_account_password_hint),
                    isError = false, enabled = !submitting && !blocked,
                    focusRequester = focus, imeAction = ImeAction.Done,
                    onImeAction = { if (canSubmit) onSubmit() },
                )
            }
            if (error.isNotEmpty()) {
                Text(
                    text = error,
                    color = c.danger,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(start = d.space4 * 2, end = d.space4 * 2, top = d.space2),
                )
            }
            Spacer(Modifier.height(d.space4 + d.space1))
            IMPrimaryButton(
                text = stringResource(R.string.delete_account_title),
                onClick = onSubmit,
                modifier = Modifier.padding(horizontal = d.space4),
                enabled = canSubmit,
                loading = submitting,
                danger = true,
            )
            Spacer(Modifier.height(d.sectionGap))
        }
    }
}

/** 警示头：64dp 圆（danger 12%）+ 30dp 图标 + 18sp 粗标题。 */
@Composable
private fun Header() {
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
            text = stringResource(R.string.delete_account_header),
            color = c.textPrimary,
            style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp, fontWeight = FontWeight.SemiBold),
        )
    }
}

/** 4 条后果（分组卡内，`•` 开头，不可点）：15sp、行间 8dp、内边距 space4。 */
@Composable
private fun Consequences() {
    val c = IMTheme.colors
    val d = IMTheme.dimens
    val lines = listOf(
        R.string.delete_account_consequence_1, R.string.delete_account_consequence_2,
        R.string.delete_account_consequence_3, R.string.delete_account_consequence_4,
    )
    IMSettingsGroup {
        Column(Modifier.padding(d.space4), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
            lines.forEach { res ->
                Row {
                    Text("•  ", color = c.textPrimary, style = MaterialTheme.typography.bodyLarge)
                    Text(stringResource(res), color = c.textPrimary, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

/** 提示条：13sp，圆角 12dp，内边距 12dp；冷静期用 accent（非红），阻断卡用 danger。 */
@Composable
private fun InfoBar(text: String, blocked: Boolean) {
    val c = IMTheme.colors
    val d = IMTheme.dimens
    Text(
        text = text,
        color = if (blocked) c.danger else c.accent,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier
            .padding(horizontal = d.space4)
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (blocked) c.dangerSoft else c.accentSoft)
            .padding(12.dp),
    )
}

/** 群主阻断卡：说明 + 群名（最多 3 个，其余「等 N 个」）。 */
@Composable
private fun OwnedGroupsCard(groups: List<String>) {
    val more = DeleteAccountRules.moreCount(groups)
    val names = DeleteAccountRules.listedGroups(groups).joinToString("、") { "「$it」" }
    val tail = if (more > 0) stringResource(R.string.delete_account_owned_groups_more, more) else ""
    InfoBar(stringResource(R.string.delete_account_owned_groups) + "\n" + names + tail, blocked = true)
}
