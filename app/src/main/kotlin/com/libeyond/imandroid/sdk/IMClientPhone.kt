package com.libeyond.imandroid.sdk

import com.libeyond.imandroid.sdk.api.SmsLoginResult

/**
 * 验证码登录（PROTOCOL §7.3），从 [IMClient] 拆出来（那个文件贴着 600 行红线）。
 * 号码已绑定账号且直接成功 → 走与密码登录完全相同的收尾（存凭据、连 WS；用户名取响应里的）；
 * 号码没注册过 → 原样返回 [SmsLoginResult.NeedRegister]（不建号、不签 token）。
 * 两步验证 `200020` / 冷静期 `200005` / 封禁等以 `ApiException` 抛出，调用方与密码登录同样处理。
 */
suspend fun IMClient.loginSms(countryCode: String, phone: String, code: String): SmsLoginResult {
    val r = phoneAuth.loginSms(countryCode, phone, code)
    if (r is SmsLoginResult.LoggedIn) finishLogin(r.result)
    return r
}

/** 完善资料：凭 `register_ticket` 建号（无密码）并登录，收尾同 [loginSms]。 */
suspend fun IMClient.registerPhone(ticket: String, nickname: String) {
    finishLogin(phoneAuth.registerPhone(ticket, nickname))
}
