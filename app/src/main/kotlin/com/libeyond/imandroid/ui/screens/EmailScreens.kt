package com.libeyond.imandroid.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Mail
import com.libeyond.imandroid.R
import com.libeyond.imandroid.ui.components.IMPrimaryButton
import com.libeyond.imandroid.ui.components.IMRowDivider
import com.libeyond.imandroid.ui.components.IMSettingsGroup
import com.libeyond.imandroid.ui.components.IMSettingsRow
import com.libeyond.imandroid.ui.theme.IMTheme

/** 信封图标 56dp accent + 内容槽（说明页 / 已绑定页共用的头部）。 */
@Composable
private fun EmailHero(content: @Composable () -> Unit) {
    val d = IMTheme.dimens
    Column(
        Modifier.fillMaxWidth().padding(start = d.space4 * 2, end = d.space4 * 2, top = d.sectionGap),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(d.space2),
    ) {
        Image(Lucide.Mail, null, Modifier.size(56.dp), colorFilter = ColorFilter.tint(IMTheme.colors.accent))
        content()
    }
}

/** ① 说明页（未绑定）。[unavailable]（服务端 `available:false`）时主按钮置灰并追加一行灰字。 */
@Composable
fun EmailIntroScreen(unavailable: Boolean, onStart: () -> Unit, onBack: () -> Unit) {
    val c = IMTheme.colors
    val d = IMTheme.dimens
    TwoStepPage(stringResource(R.string.email_title), onBack) {
        EmailHero {
            Text(stringResource(R.string.email_title), color = c.textPrimary, fontSize = 20.sp, textAlign = TextAlign.Center)
            Text(stringResource(R.string.email_intro), color = c.textSecondary, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
            if (unavailable) {
                Text(stringResource(R.string.email_unavailable), color = c.textSecondary, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
            }
        }
        Spacer(Modifier.height(28.dp))
        IMPrimaryButton(stringResource(R.string.email_action_bind), onStart, Modifier.padding(horizontal = d.space4), enabled = !unavailable)
        Spacer(Modifier.height(d.sectionGap))
    }
}

/** ④ 已绑定页：邮箱（可换行）+ 绑定日期 + 更换邮箱 + 解除绑定（红字居中）+ footer。 */
@Composable
fun EmailBoundScreen(email: String, sinceLabel: String, onChange: () -> Unit, onUnbind: () -> Unit, onBack: () -> Unit) {
    val c = IMTheme.colors
    val d = IMTheme.dimens
    TwoStepPage(stringResource(R.string.email_title), onBack) {
        EmailHero {
            Text(email, color = c.textPrimary, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            if (sinceLabel.isNotEmpty()) {
                Text(sinceLabel, color = c.textSecondary, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
            }
        }
        Spacer(Modifier.height(d.sectionGap))
        IMSettingsGroup {
            IMSettingsRow(title = stringResource(R.string.email_action_change), onClick = onChange)
            IMRowDivider(insetStart = d.space4)
            IMSettingsRow(title = stringResource(R.string.email_action_unbind), onClick = onUnbind, destructive = true)
        }
        TwoStepNote(stringResource(R.string.email_bound_footer))
        Spacer(Modifier.height(d.sectionGap))
    }
}

/** 解绑密码页：一个密码框 + footer（解除后的影响）+ 红色主按钮；二次确认弹窗在 Host。 */
@Composable
fun EmailUnbindScreen(
    password: String,
    onPassword: (String) -> Unit,
    error: String,
    busy: Boolean,
    onSubmit: () -> Unit,
    onBack: () -> Unit,
) {
    val d = IMTheme.dimens
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val can = password.isNotEmpty() && !busy
    TwoStepPage(stringResource(R.string.email_action_unbind), onBack) {
        Spacer(Modifier.height(d.sectionGap))
        IMSettingsGroup {
            PasswordRow(
                value = password, onValueChange = onPassword,
                placeholder = stringResource(R.string.email_field_password),
                isError = false, enabled = !busy, focusRequester = focus,
                imeAction = ImeAction.Done, onImeAction = { if (can) onSubmit() },
            )
        }
        TwoStepNote(error, error = true)
        TwoStepNote(stringResource(R.string.email_unbind_body))
        Spacer(Modifier.height(d.space4 + d.space1))
        IMPrimaryButton(
            text = stringResource(R.string.email_action_unbind), onClick = onSubmit, modifier = Modifier.padding(horizontal = d.space4),
            enabled = can, loading = busy, danger = true,
        )
        Spacer(Modifier.height(d.sectionGap))
    }
}
