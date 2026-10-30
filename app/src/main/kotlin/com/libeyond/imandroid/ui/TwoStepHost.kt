package com.libeyond.imandroid.ui

import android.content.Context
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import com.libeyond.imandroid.R
import com.libeyond.imandroid.data.TwoStepRules
import com.libeyond.imandroid.data.TwoStepStep
import com.libeyond.imandroid.i18n.Str
import com.libeyond.imandroid.sdk.IMClient
import com.libeyond.imandroid.sdk.logging.IMLog
import com.libeyond.imandroid.ui.components.IMConfirmDialog
import com.libeyond.imandroid.ui.components.IMToast
import com.libeyond.imandroid.ui.components.PushTransition
import com.libeyond.imandroid.ui.screens.TwoStepBindScreen
import com.libeyond.imandroid.ui.screens.TwoStepCodesScreen
import com.libeyond.imandroid.ui.screens.TwoStepIntroScreen
import com.libeyond.imandroid.ui.screens.TwoStepStatusScreen
import com.libeyond.imandroid.ui.screens.TwoStepVerifyScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/**
 * 隐私与安全 ▸ 两步验证（流程见 `IMServer/docs/design/TWO_STEP_VERIFICATION_DESIGN.md` §2）。
 * 状态与动作在 [TwoStepController]（由隐私页宿主持有），这里只接线：返回拦截、剪贴板、保存文件、确认框。
 */
@Composable
fun TwoStepHost(client: IMClient, ctl: TwoStepController, onExit: () -> Unit) {
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val header = stringResource(R.string.twostep_codes_file_header)

    // 进页：就地恢复未保存的恢复码，否则按已知状态落页，再刷新一次
    LaunchedEffect(Unit) { ctl.open(); ctl.refresh(); ctl.syncStepToStatus() }

    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val list = ctl.codes
        if (uri != null && list != null) client.scope.launch { ctl.toast = writeCodes(ctx, uri, TwoStepRules.fileText(header, list)) }
    }

    val back = { if (!ctl.back()) onExit() }
    BackHandler(onBack = back)

    PushTransition(targetState = ctl.step, depthOf = { stepDepth(it) }) { s ->
        when (s) {
            TwoStepStep.Intro -> TwoStepIntroScreen(onStart = { ctl.goto(TwoStepStep.Password) }, onBack = back)
            TwoStepStep.Password -> TwoStepVerifyScreen(
                title = stringResource(R.string.twostep_verify_pw_title), footer = stringResource(R.string.twostep_verify_pw_footer),
                submitText = stringResource(R.string.twostep_action_continue),
                password = ctl.password, onPassword = ctl::onPassword,
                showCode = false, code = "", onCode = {}, recoveryMode = false, onToggleRecovery = {},
                error = ctl.error, busy = ctl.busy, canSubmit = ctl.password.isNotEmpty(), danger = false,
                onSubmit = ctl::submitSetup, onBack = back,
            )
            TwoStepStep.Bind -> ctl.setup?.let { su ->
                TwoStepBindScreen(
                    otpauthUri = su.otpauthUri, secret = su.secret,
                    code = ctl.code, onCode = ctl::onCode, error = ctl.error, busy = ctl.busy,
                    onCopySecret = {
                        clipboard.setText(AnnotatedString(TwoStepRules.copySecret(su.secret)))
                        ctl.toast = Str.s(R.string.twostep_bind_copied)
                    },
                    onSubmit = ctl::submitBind, onBack = back,
                )
            }
            TwoStepStep.Codes -> ctl.codes?.let { list ->
                TwoStepCodesScreen(
                    codes = list, regenerated = ctl.codesRegenerated, saved = ctl.saved, onSavedChange = { ctl.saved = it },
                    onCopyAll = {
                        clipboard.setText(AnnotatedString(TwoStepRules.copyAllText(list)))
                        ctl.toast = Str.s(R.string.twostep_bind_copied)
                    },
                    onSaveFile = { saver.launch(TwoStepRules.FILE_NAME) },
                    onDone = { ctl.finishCodes(leftUnsaved = false) },
                    onBack = back,
                )
            }
            TwoStepStep.Status -> {
                val st = ctl.status
                TwoStepStatusScreen(
                    sinceLabel = st?.takeIf { it.enabledAt > 0 }?.let {
                        Str.s(R.string.twostep_on_since, DateFormat.getDateInstance(DateFormat.LONG).format(Date(it.enabledAt)))
                    }.orEmpty(),
                    remaining = st?.recoveryRemaining ?: 0, unsaved = ctl.unsaved,
                    onRecovery = { ctl.goto(TwoStepStep.Regen) }, onDisable = { ctl.goto(TwoStepStep.Disable) }, onBack = back,
                )
            }
            TwoStepStep.Disable, TwoStepStep.Regen -> {
                val disable = s == TwoStepStep.Disable
                TwoStepVerifyScreen(
                    title = stringResource(if (disable) R.string.twostep_disable_title else R.string.twostep_regen_title),
                    footer = stringResource(if (disable) R.string.twostep_disable_footer else R.string.twostep_regen_footer),
                    submitText = stringResource(if (disable) R.string.twostep_disable_row else R.string.twostep_regen_submit),
                    password = ctl.password, onPassword = ctl::onPassword,
                    showCode = true, code = ctl.code, onCode = ctl::onCode,
                    recoveryMode = ctl.recoveryMode, onToggleRecovery = ctl::toggleRecovery,
                    error = ctl.error, busy = ctl.busy,
                    canSubmit = TwoStepRules.canSubmitVerify(ctl.password, ctl.code, ctl.recoveryMode), danger = disable,
                    onSubmit = ctl::submitVerify, onBack = back,
                )
            }
        }
    }

    if (ctl.leaveDialog) {
        IMConfirmDialog(
            title = stringResource(R.string.twostep_codes_leave_title), message = stringResource(R.string.twostep_codes_leave_body),
            confirmText = stringResource(R.string.twostep_codes_leave_confirm), destructive = false,
            onConfirm = { ctl.finishCodes(leftUnsaved = !ctl.saved) }, onDismiss = { ctl.leaveDialog = false },
        )
    }
    if (ctl.confirmDisable) {
        IMConfirmDialog(
            title = stringResource(R.string.twostep_disable_confirm_title), message = stringResource(R.string.twostep_disable_confirm_body),
            confirmText = stringResource(R.string.twostep_disable_row),
            onConfirm = ctl::runDisable, onDismiss = { ctl.confirmDisable = false },
        )
    }
    ctl.toast?.let { IMToast(it) { ctl.toast = null } }
}

/** 页深：Intro/Status = 0，流程页 = 1，恢复码页 = 2（决定推入 / 退出的转场方向）。 */
private fun stepDepth(s: TwoStepStep): Int = when (s) {
    TwoStepStep.Intro, TwoStepStep.Status -> 0
    TwoStepStep.Password, TwoStepStep.Disable, TwoStepStep.Regen -> 1
    TwoStepStep.Bind -> 2
    TwoStepStep.Codes -> 3
}

/** 写恢复码文件；返回要给用户看的提示（失败给错误提示，成功给「已保存」）。 */
private suspend fun writeCodes(ctx: Context, uri: Uri, text: String): String = withContext(Dispatchers.IO) {
    try {
        ctx.contentResolver.openOutputStream(uri, "w")?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            ?: error("no stream")
        Str.s(R.string.export_download_done)
    } catch (e: Exception) {
        IMLog.tag("IM.TwoStep").w("recovery_file_write_failed", "err" to e.javaClass.simpleName)
        Str.s(R.string.common_save_failed)
    }
}
