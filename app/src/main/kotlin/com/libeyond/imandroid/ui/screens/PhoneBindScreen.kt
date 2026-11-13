package com.libeyond.imandroid.ui.screens

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import com.libeyond.imandroid.R
import com.libeyond.imandroid.ui.components.IMPrimaryButton
import com.libeyond.imandroid.ui.components.IMRowDivider
import com.libeyond.imandroid.ui.components.IMSectionHeader
import com.libeyond.imandroid.ui.components.IMSettingsGroup
import com.libeyond.imandroid.ui.components.SendCodeButton
import com.libeyond.imandroid.ui.components.SettingsInputRow
import com.libeyond.imandroid.ui.theme.IMAppTheme
import com.libeyond.imandroid.ui.theme.IMTheme
import com.libeyond.imandroid.ui.theme.IMThemeMode

/**
 * 绑定 / 更换登录手机号（PHONE_AUTH_UX_SKETCH 2-8）：更换时顶部「当前手机号 …」；
 * 「新手机号」组 = 国家码 + 号码 / 验证码 + 行内发码；错误 = TwoStepNote 错误态；「下一步」弹确认身份弹层（在 Host）。
 * 纯展示：值 / 倒计时 / 错误 / 忙态都在 `PhoneBindController`。
 */
@Composable
fun PhoneBindScreen(
    changing: Boolean,
    currentMasked: String,
    countryCode: String,
    phone: String,
    onPhone: (String) -> Unit,
    code: String,
    onCode: (String) -> Unit,
    sendText: String,
    sendEnabled: Boolean,
    onSend: () -> Unit,
    error: String,
    busy: Boolean,
    canNext: Boolean,
    onNext: () -> Unit,
    onBack: () -> Unit,
    /** 旧号码提示文案（PHONE_AUTH_DESIGN §15.1）；null = 不提示。 */
    legacyPrompt: String? = null,
    legacyBusy: Boolean = false,
    onLegacyBind: () -> Unit = {},
    onLegacyDismiss: () -> Unit = {},
) {
    val d = IMTheme.dimens
    TwoStepPage(stringResource(if (changing) R.string.security_phone_change_title else R.string.security_phone_bind_title), onBack) {
        if (changing && currentMasked.isNotEmpty()) {
            Spacer(Modifier.height(d.space2))
            TwoStepNote(stringResource(R.string.security_phone_current, currentMasked))
        }
        if (legacyPrompt != null) LegacyPrompt(legacyPrompt, legacyBusy, onLegacyBind, onLegacyDismiss)
        IMSectionHeader(stringResource(R.string.security_phone_new_placeholder))
        IMSettingsGroup {
            SettingsInputRow(
                value = phone, onValueChange = onPhone,
                placeholder = stringResource(R.string.login_phone_placeholder),
                keyboardType = KeyboardType.Phone, enabled = !busy, leading = "+$countryCode",
            )
            IMRowDivider(insetStart = d.space4)
            SettingsInputRow(
                value = code, onValueChange = onCode,
                placeholder = stringResource(R.string.login_sms_code_six_digits),
                keyboardType = KeyboardType.NumberPassword, enabled = !busy,
                trailing = { SendCodeButton(sendText, sendEnabled && !busy, onSend) },
            )
        }
        TwoStepNote(error, error = true)
        TwoStepNote(stringResource(if (changing) R.string.security_phone_change_footer else R.string.security_phone_bind_footer))
        Spacer(Modifier.height(d.space4 + d.space1))
        IMPrimaryButton(
            text = stringResource(R.string.security_phone_next), onClick = onNext,
            modifier = Modifier.padding(horizontal = d.space4), enabled = canNext, loading = busy,
        )
        // 「下一步」会弹确认身份弹层：先说一声（稿 2-8）
        TwoStepNote(stringResource(R.string.security_phone_next_hint))
        Spacer(Modifier.height(d.sectionGap))
    }
}

/** 「你曾在资料中填写过 …，是否绑定为登录手机号？」+「绑定 / 不再提示」。 */
@Composable
private fun LegacyPrompt(text: String, busy: Boolean, onBind: () -> Unit, onDismiss: () -> Unit) {
    val d = IMTheme.dimens
    Spacer(Modifier.height(d.space3))
    IMSettingsGroup {
        Text(
            text = text,
            color = IMTheme.colors.textPrimary,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.fillMaxWidth().padding(horizontal = d.space4, vertical = d.space3),
        )
        IMRowDivider(insetStart = d.space4)
        Row(Modifier.fillMaxWidth()) {
            TwoStepLink(stringResource(R.string.security_phone_legacy_dismiss), { if (!busy) onDismiss() }, Modifier.weight(1f), secondary = true, minHeight = d.settingsRowHeight)
            TwoStepLink(stringResource(R.string.security_phone_legacy_bind), { if (!busy) onBind() }, Modifier.weight(1f), minHeight = d.settingsRowHeight)
        }
    }
}

@Preview(name = "绑定手机号·旧号码提示", showBackground = true)
@Composable
private fun PreviewPhoneBindLegacy() {
    IMAppTheme(mode = IMThemeMode.Light) {
        PhoneBindScreen(
            false, "", "86", "", {}, "", {}, "获取验证码", true, {}, "", false, false, {}, {},
            legacyPrompt = "你曾在资料中填写过 +86 137****0002，是否绑定为登录手机号？",
        )
    }
}

@Preview(name = "更换手机号·浅色", showBackground = true)
@Composable
private fun PreviewPhoneBindLight() {
    IMAppTheme(mode = IMThemeMode.Light) {
        PhoneBindScreen(true, "+86 138****8000", "86", "", {}, "", {}, "获取验证码", true, {}, "该手机号已绑定其他账号", false, false, {}, {})
    }
}

@Preview(name = "绑定手机号·深色", showBackground = true)
@Composable
private fun PreviewPhoneBindDark() {
    IMAppTheme(mode = IMThemeMode.Dark) {
        PhoneBindScreen(false, "", "86", "13622220001", {}, "000000", {}, "58 秒后重发", false, {}, "", false, true, {}, {})
    }
}
