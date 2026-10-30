package com.libeyond.imandroid.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.libeyond.imandroid.R
import com.libeyond.imandroid.data.TwoStepErrorKind
import com.libeyond.imandroid.data.TwoStepRules
import com.libeyond.imandroid.i18n.Str
import com.libeyond.imandroid.sdk.api.TwoStepChallenge
import com.libeyond.imandroid.sdk.http.ApiException

/**
 * 登录第二步的状态：ticket 与用户名**只留内存**，离开二步页（成功 / 返回 / 失效）即丢弃。
 * 请求与收尾由 `AppRoot` 做（成功后走原有登录成功路径）；这里放可单测的判定。
 */
class LoginTwoStep {
    /** 非 null = 正在二步页。 */
    var challenge by mutableStateOf<TwoStepChallenge?>(null); private set
    var username by mutableStateOf(""); private set
    /** 每次提交被拒 +1，二步页据此清空并重新聚焦输入框。 */
    var failTick by mutableIntStateOf(0); private set

    val active: Boolean get() = challenge != null

    fun begin(c: TwoStepChallenge, user: String) { challenge = c; username = user; failTick = 0 }

    fun drop() { challenge = null; username = ""; failTick = 0 }

    /** 提交被拒后的处置：ticket 失效回密码页（[Outcome.BackToPassword]）；其余留在本页并清空输入。 */
    fun onRejected(e: ApiException): Outcome {
        val kind = TwoStepRules.kindOf(e.code)
        if (kind == TwoStepErrorKind.Expired) { drop(); return Outcome.BackToPassword(TwoStepRules.message(kind, login = true).orEmpty()) }
        failTick++
        val msg = TwoStepRules.message(kind, TwoStepChallenge.attemptsLeft(e), login = true)
            ?: if (e.isTransport) Str.s(R.string.net_error_unreachable) else e.message
        return Outcome.Stay(msg)
    }

    sealed interface Outcome {
        val message: String
        data class Stay(override val message: String) : Outcome
        data class BackToPassword(override val message: String) : Outcome
    }
}
