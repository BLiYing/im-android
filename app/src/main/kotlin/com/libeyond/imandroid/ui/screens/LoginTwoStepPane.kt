package com.libeyond.imandroid.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.libeyond.imandroid.R
import com.libeyond.imandroid.data.TwoStepRules
import com.libeyond.imandroid.ui.components.IMErrorText
import com.libeyond.imandroid.ui.components.IMPrimaryButton
import com.libeyond.imandroid.ui.theme.IMTheme

/**
 * 登录二步页（同登录页内切换，不新开页面）：标题 + 说明 + 6 位框（输满自动提交）/ 恢复码文本框 +
 * 「使用恢复码」文字链 + 「返回」（丢 ticket 回密码页）。
 *
 * 纯展示：ticket 与请求在 `AppRoot`。[failTick] 每次一次提交被拒就 +1，框据此清空并重新聚焦。
 * 6 位框是 NumberPassword 键盘、输入即过滤，无 IME 组词；恢复码框不改写文本（归一化在提交时做）。
 */
@Composable
fun LoginTwoStepPane(
    busy: Boolean,
    error: String,
    failTick: Int,
    onSubmit: (code: String) -> Unit,
    onBack: () -> Unit,
    onEdit: () -> Unit,
) {
    val c = IMTheme.colors
    val d = IMTheme.dimens
    var recovery by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    // 系统返回 = 页内「返回」（丢 ticket 回密码页），而不是退出整个 App
    BackHandler(enabled = !busy, onBack = onBack)

    // 被拒：清空并重新聚焦（输满 6 位被拒，留着无意义）。切换模式的清空在点击处同步做——
    // 若留给 effect，自动提交那一轮仍拿着旧模式的文本（如 6 个字符的恢复码前缀）当动态码发出去，白白烧一次尝试
    LaunchedEffect(failTick) { if (failTick > 0) code = "" }
    LaunchedEffect(failTick, recovery, busy) { if (!busy) runCatching { focus.requestFocus() } }
    // 输满 6 位自动提交
    LaunchedEffect(code, recovery) {
        if (!recovery && TwoStepRules.shouldAutoSubmit(code, busy)) onSubmit(TwoStepRules.submitCode(code, false))
    }
    val canLogin = recovery && TwoStepRules.isRecoveryComplete(code) && !busy

    Column(
        modifier = Modifier.widthIn(max = 360.dp).fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(d.space3),
    ) {
        Text(stringResource(R.string.twostep_login_title), color = c.textPrimary, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Text(
            stringResource(if (recovery) R.string.twostep_login_hint_recovery else R.string.twostep_login_hint),
            color = c.textSecondary, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
        )
        if (recovery) {
            val shape = RoundedCornerShape(d.radiusCard)
            androidx.compose.foundation.layout.Box(
                Modifier.fillMaxWidth().background(c.cardBackground, shape).border(1.dp, if (error.isNotEmpty()) c.danger else c.separator, shape).padding(vertical = 4.dp),
            ) {
                CodeRow(
                    value = code, onChange = { code = it; onEdit() }, recovery = true,
                    placeholder = stringResource(R.string.twostep_field_recovery),
                    enabled = !busy, focusRequester = focus, onDone = { if (canLogin) onSubmit(TwoStepRules.submitCode(code, true)) },
                )
            }
        } else {
            BigCodeField(
                value = code, onChange = { code = it; onEdit() }, isError = error.isNotEmpty(), enabled = !busy,
                focusRequester = focus, onDone = {},
            )
        }
        IMErrorText(error)
        if (recovery) {
            IMPrimaryButton(
                text = stringResource(R.string.twostep_login_submit),
                onClick = { onSubmit(TwoStepRules.submitCode(code, true)) },
                enabled = canLogin, loading = busy,
            )
        }
        TwoStepLink(stringResource(if (recovery) R.string.twostep_use_code else R.string.twostep_use_recovery), { if (!busy) { recovery = !recovery; code = ""; onEdit() } })
        Text(
            stringResource(R.string.twostep_login_no_recovery),
            color = c.textTertiary, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
        )
        TwoStepLink(stringResource(R.string.twostep_login_back), { if (!busy) onBack() }, secondary = true)
    }
}
