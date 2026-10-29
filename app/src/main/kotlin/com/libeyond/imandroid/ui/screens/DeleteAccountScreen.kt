package com.libeyond.imandroid.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.libeyond.imandroid.R
import com.libeyond.imandroid.data.DeleteAccountRules
import com.libeyond.imandroid.data.DisplayName
import com.libeyond.imandroid.sdk.api.OwnedGroup
import com.libeyond.imandroid.ui.components.IMSettingsGroup
import com.libeyond.imandroid.ui.theme.IMTheme

/**
 * 注销账号二级页（IMServer `docs/design/DELETE_ACCOUNT_DESIGN.md` §3/§4 + 草图 DELETE_ACCOUNT_UX_SKETCH.html）。
 *
 * 纯展示：密码 / 错误 / 群主阻断名单 / 提交态由 `DeleteAccountHost` 持有。
 * 版式在共用的 [DangerConfirmPage]；这里只补页面独有的（群主阻断卡 + 密码框）。
 */
@Composable
fun DeleteAccountScreen(
    password: String,
    onPasswordChange: (String) -> Unit,
    error: String,
    ownedGroups: List<OwnedGroup>,
    onOpenGroup: (OwnedGroup) -> Unit,
    submitting: Boolean,
    onSubmit: () -> Unit,
    onBack: () -> Unit,
) {
    val d = IMTheme.dimens
    val blocked = ownedGroups.isNotEmpty()
    val canSubmit = DeleteAccountRules.canSubmit(password, submitting, ownedGroups)
    val focus = remember { FocusRequester() }

    DangerConfirmPage(
        title = stringResource(R.string.delete_account_title),
        header = stringResource(R.string.delete_account_header),
        consequences = listOf(
            R.string.delete_account_consequence_1, R.string.delete_account_consequence_2,
            R.string.delete_account_consequence_3, R.string.delete_account_consequence_4,
        ).map { stringResource(it) },
        hint = stringResource(R.string.delete_account_cooldown_hint),
        error = error,
        buttonText = stringResource(R.string.delete_account_title),
        buttonEnabled = canSubmit,
        loading = submitting,
        onSubmit = onSubmit,
        onBack = onBack,
    ) {
        if (blocked) {
            Spacer(Modifier.height(d.space3))
            OwnedGroupsCard(ownedGroups, onOpenGroup)
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
    }
}

/** 群主阻断卡：说明 + 群名（最多 3 个，每个可点进该群管理页；其余「等 N 个」）。无 conv_id 的项不可点。 */
@Composable
private fun OwnedGroupsCard(groups: List<OwnedGroup>, onOpenGroup: (OwnedGroup) -> Unit) {
    val c = IMTheme.colors
    val d = IMTheme.dimens
    val more = DeleteAccountRules.moreCount(groups)
    Column(
        Modifier.padding(horizontal = d.space4).fillMaxWidth()
            .clip(RoundedCornerShape(12.dp)).background(c.dangerSoft).padding(12.dp),
    ) {
        Text(stringResource(R.string.delete_account_owned_groups), color = c.danger, style = MaterialTheme.typography.bodyMedium)
        DeleteAccountRules.listedGroups(groups).forEach { g ->
            Text(
                text = "「${g.name.ifBlank { DisplayName.UNNAMED_GROUP }}」 ›",
                color = c.danger,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier.fillMaxWidth()
                    .then(if (g.convId.isNotEmpty()) Modifier.clickable { onOpenGroup(g) } else Modifier)
                    .padding(top = 6.dp),
            )
        }
        if (more > 0) {
            Text(
                stringResource(R.string.delete_account_owned_groups_more, more),
                color = c.danger, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}
