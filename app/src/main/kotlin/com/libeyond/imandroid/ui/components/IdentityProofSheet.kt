package com.libeyond.imandroid.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.libeyond.imandroid.R
import com.libeyond.imandroid.data.PhoneAuthRules
import com.libeyond.imandroid.data.ProofTab
import com.libeyond.imandroid.ui.screens.PasswordRow
import com.libeyond.imandroid.ui.screens.SegTabBar
import com.libeyond.imandroid.ui.theme.IMTheme
import kotlinx.coroutines.launch

/**
 * 确认身份弹层（PHONE_AUTH_DESIGN §7.2，稿 2-9）：M3 `ModalBottomSheet`（顶角 28、容器 pageBackground、
 * 跳过半展开），标题 + 右侧「取消」同 `SharePickConfirmSheet`；两种方式都可用时用 [SegTabBar] 分段，
 * 只有一种时不画分段；验证码页签里有行内发码按钮与「验证码将发送至 …」；主按钮「确认」。
 *
 * 纯展示：页签 / 输入 / 倒计时 / 错误 / 忙态都由调用方持有（`ProofController`），这里不发请求。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IdentityProofSheet(
    tabs: List<ProofTab>,
    tab: ProofTab,
    onTab: (ProofTab) -> Unit,
    password: String,
    onPassword: (String) -> Unit,
    code: String,
    onCode: (String) -> Unit,
    /** 本人绑定号码的打码形式；空串不画「验证码将发送至」。 */
    phoneMasked: String,
    sendText: String,
    sendEnabled: Boolean,
    onSend: () -> Unit,
    error: String,
    busy: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = IMTheme.colors
    val d = IMTheme.dimens
    // 提交中不许下滑 / 点外部关：结果回来时弹层必须还在（证明错要落在弹层里）。sheetState 只建一次，读最新的 busy
    val busyNow by rememberUpdatedState(busy)
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { it != SheetValue.Hidden || !busyNow })
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    val canConfirm = PhoneAuthRules.canConfirmProof(tab, password, code, busy)
    fun hideThen(after: () -> Unit) {
        scope.launch { state.hide() }.invokeOnCompletion { after() }
    }
    LaunchedEffect(tab) { runCatching { focus.requestFocus() } }

    ModalBottomSheet(onDismissRequest = { if (!busyNow) onDismiss() }, sheetState = state, containerColor = c.pageBackground) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = d.space4).padding(bottom = d.space4)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.proof_title), color = c.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    stringResource(R.string.common_cancel), color = c.accent, fontSize = 16.sp,
                    modifier = Modifier.clickable(enabled = !busy) { hideThen(onDismiss) }.padding(d.space2),
                )
            }
            if (PhoneAuthRules.showProofSegments(tabs)) {
                SegTabBar(
                    titles = tabs.map { stringResource(if (it == ProofTab.Password) R.string.proof_tab_password else R.string.proof_tab_sms) },
                    selected = tabs.indexOf(tab).coerceAtLeast(0),
                    onSelect = { i -> if (!busy) onTab(tabs[i]) },
                )
            } else {
                Spacer(Modifier.height(d.space3))
            }
            if (tab == ProofTab.Sms && phoneMasked.isNotEmpty()) {
                Text(
                    stringResource(R.string.security_code_sent_to, phoneMasked), color = c.textSecondary,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(start = d.space4, end = d.space4, bottom = d.space2),
                )
            }
            val shape = RoundedCornerShape(d.radiusCard)
            // 白底上需要边界：输入组带 1dp separator 描边（稿 2-9）
            Column(Modifier.fillMaxWidth().clip(shape).background(c.cardBackground).border(1.dp, c.separator, shape)) {
                if (tab == ProofTab.Password) {
                    PasswordRow(
                        value = password, onValueChange = onPassword,
                        placeholder = stringResource(R.string.proof_password_placeholder),
                        isError = false, enabled = !busy, focusRequester = focus,
                        imeAction = ImeAction.Done, onImeAction = { if (canConfirm) onConfirm() },
                    )
                } else {
                    SettingsInputRow(
                        value = code, onValueChange = onCode,
                        placeholder = stringResource(R.string.login_sms_code_six_digits),
                        keyboardType = KeyboardType.NumberPassword, enabled = !busy,
                        focusRequester = focus,
                        trailing = { SendCodeButton(sendText, sendEnabled && !busy, onSend) },
                    )
                }
            }
            if (error.isNotEmpty()) {
                Text(
                    error, color = c.danger, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(start = d.space4, end = d.space4, top = d.space2),
                )
            }
            Spacer(Modifier.height(d.space4))
            IMPrimaryButton(
                text = stringResource(R.string.proof_confirm),
                onClick = onConfirm,
                enabled = canConfirm,
                loading = busy,
            )
        }
    }
}
