package com.libeyond.imandroid.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.res.stringResource
import com.libeyond.imandroid.R
import com.libeyond.imandroid.data.EmailRules
import com.libeyond.imandroid.data.EmailStep
import com.libeyond.imandroid.i18n.Str
import com.libeyond.imandroid.ui.components.IMConfirmDialog
import com.libeyond.imandroid.ui.components.IMToast
import com.libeyond.imandroid.ui.components.PushTransition
import com.libeyond.imandroid.ui.screens.EmailBoundScreen
import com.libeyond.imandroid.ui.screens.EmailCodeScreen
import com.libeyond.imandroid.ui.screens.EmailFormScreen
import com.libeyond.imandroid.ui.screens.EmailIntroScreen
import com.libeyond.imandroid.ui.screens.EmailUnbindScreen
import java.text.DateFormat
import java.util.Date

/**
 * 隐私与安全 ▸ 邮箱登录（流程见 `IMServer/docs/design/EMAIL_LOGIN_DESIGN.md` §2）。
 * 状态与动作在 [EmailController]（由隐私页宿主持有），这里只接线：返回拦截、确认框、toast。
 */
@Composable
fun EmailHost(ctl: EmailController, onExit: () -> Unit) {
    // 进页：落到已知状态，再取一次最新值（多端之间以进页时的取值为准）
    LaunchedEffect(Unit) { ctl.open(); ctl.refresh() }

    // 无论怎么离开（返回键 / 搜索跳转 / 被替换）都清掉内存里的密码与验证码
    DisposableEffect(ctl) { onDispose { ctl.reset() } }

    val back = { if (!ctl.back()) { ctl.reset(); onExit() } }
    BackHandler(onBack = back)

    PushTransition(targetState = ctl.step, depthOf = { stepDepth(it) }) { s ->
        when (s) {
            EmailStep.Intro -> EmailIntroScreen(unavailable = EmailRules.introDisabled(ctl.status), onStart = ctl::startForm, onBack = back)
            EmailStep.Form -> EmailFormScreen(
                changing = ctl.isBound, email = ctl.email, password = ctl.password,
                onEmail = ctl::onEmail, onPassword = ctl::onPassword,
                error = ctl.error, busy = ctl.busy, onSubmit = ctl::submitSend, onBack = back,
            )
            EmailStep.Code -> EmailCodeScreen(
                sentTo = ctl.sentTo, code = ctl.code, onCode = ctl::onCode, error = ctl.error, busy = ctl.busy,
                resendAt = ctl.resendAt, focusTick = ctl.focusTick,
                onResend = ctl::resend, onChangeEmail = back, onBack = back,
            )
            EmailStep.Bound -> {
                val st = ctl.status
                EmailBoundScreen(
                    email = st?.email.orEmpty(),
                    sinceLabel = st?.takeIf { it.verifiedAt > 0 }?.let {
                        Str.s(R.string.email_bound_since, DateFormat.getDateInstance(DateFormat.LONG).format(Date(it.verifiedAt)))
                    }.orEmpty(),
                    onChange = ctl::startForm, onUnbind = ctl::startUnbind, onBack = back,
                )
            }
            EmailStep.Unbind -> EmailUnbindScreen(
                password = ctl.password, onPassword = ctl::onPassword, error = ctl.error, busy = ctl.busy,
                onSubmit = ctl::askUnbind, onBack = back,
            )
        }
    }

    if (ctl.confirmUnbind) {
        IMConfirmDialog(
            title = stringResource(R.string.email_unbind_confirm_title), message = stringResource(R.string.email_unbind_body),
            confirmText = stringResource(R.string.email_action_unbind),
            onConfirm = ctl::runUnbind, onDismiss = { ctl.confirmUnbind = false },
        )
    }
    ctl.toast?.let { IMToast(it) { ctl.toast = null } }
}

/** 页深：说明 / 已绑定 = 0，填表 / 解绑 = 1，验证码 = 2（决定推入 / 退出的转场方向）。 */
private fun stepDepth(s: EmailStep): Int = when (s) {
    EmailStep.Intro, EmailStep.Bound -> 0
    EmailStep.Form, EmailStep.Unbind -> 1
    EmailStep.Code -> 2
}
