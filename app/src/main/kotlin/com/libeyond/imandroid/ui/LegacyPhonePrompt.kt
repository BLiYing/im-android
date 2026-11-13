package com.libeyond.imandroid.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.libeyond.imandroid.R
import com.libeyond.imandroid.data.PhoneAuthRules
import com.libeyond.imandroid.i18n.Str
import com.libeyond.imandroid.sdk.http.ApiException
import com.libeyond.imandroid.sdk.logging.IMLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 「登录手机号」页的旧号码提示（PHONE_AUTH_DESIGN §15.1）：`GET /users/me/security` 带 `legacy_phone`（资料里曾自填、
 * 还没绑定的号码）时，提示「你曾在资料中填写过 137****0001，是否绑定为登录手机号？」。
 *
 * - 「绑定」→ [bind]：把国家码与号码预填进绑定表单（仍要验证码 + 确认身份），本次进页不再提示；
 * - 「不再提示」→ [dismiss]：`DELETE /users/me/legacy-phone`，成功后本地去掉 `legacy_phone`；失败吐司、提示保留。
 * - 绑定成功服务端顺带清掉（页面会重取 security）。
 *
 * **日志里不写号码**。
 */
class LegacyPhonePrompt(
    private val sec: SecurityState,
    private val scope: CoroutineScope,
    private val dismissCall: suspend () -> Unit,
) {
    private val log = IMLog.tag("IM.Phone")

    /** 本次进页点过「绑定」：提示收起，且晚到的默认国家码不再覆盖预填的国家码。 */
    var prefilled by mutableStateOf(false); private set
    var dismissing by mutableStateOf(false); private set

    val visible: Boolean get() = PhoneAuthRules.showLegacyPrompt(sec.status, prefilled)
    val masked: String get() = sec.status?.legacyPhone?.masked.orEmpty()

    /** 进页 / 离开：重新按服务端状态决定是否提示。 */
    fun reset() { prefilled = false }

    /** 「绑定」：预填国家码 + 号码。 */
    fun bind(form: PhoneCodeForm) {
        val lp = sec.status?.legacyPhone ?: return
        form.onCountryCode(lp.countryCode)
        form.onPhone(lp.phone)
        prefilled = true
        log.i("legacy_phone_prefill")
    }

    /** 「不再提示」：失败把通用错误文案交 [onError]（吐司），提示保留。 */
    fun dismiss(onError: (String) -> Unit) {
        if (dismissing) return
        dismissing = true
        scope.launch {
            val r = try { runCatchingCancellable { dismissCall() } } finally { dismissing = false }
            r.onSuccess {
                log.i("legacy_phone_dismissed")
                sec.update { it.copy(legacyPhone = null) }
            }.onFailure { e ->
                log.w("legacy_phone_dismiss_failed", "code" to ((e as? ApiException)?.code ?: 0))
                onError(e.userMessage(Str.s(R.string.net_error_unreachable)))
            }
        }
    }
}
