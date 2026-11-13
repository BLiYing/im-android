package com.libeyond.imandroid.ui.components

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.libeyond.imandroid.R
import com.libeyond.imandroid.data.EmailRules
import com.libeyond.imandroid.data.PhoneAuthRules
import com.libeyond.imandroid.sdk.api.SmsRegion
import com.libeyond.imandroid.ui.theme.IMTheme
import kotlinx.coroutines.delay

/** 国家码框宽度（PHONE_AUTH_UX_SKETCH 2-1：Android 84，与号码框间距 8）。 */
private val COUNTRY_BOX_WIDTH = 84.dp
private val FIELD_MIN_HEIGHT = 56.dp
private const val COUNTDOWN_TICK_MS = 1000L

/**
 * 重发倒计时剩余秒数：按 [resendAt]（`SystemClock.elapsedRealtime` 基准）每秒重算，走完停表。
 * 状态在调用方（截止时刻），这里只负责让界面跟着秒数刷新。
 */
@Composable
fun rememberSecondsLeft(resendAt: Long): Int {
    var left by remember { mutableIntStateOf(EmailRules.secondsLeft(resendAt, SystemClock.elapsedRealtime())) }
    LaunchedEffect(resendAt) {
        while (true) {
            left = EmailRules.secondsLeft(resendAt, SystemClock.elapsedRealtime())
            if (left <= 0) break
            delay(COUNTDOWN_TICK_MS)
        }
    }
    return left
}

/** 「获取验证码 / N 秒后重发」的文案。 */
@Composable
fun sendCodeLabel(secondsLeft: Int, sentOnce: Boolean): String = when {
    secondsLeft > 0 -> stringResource(R.string.login_sms_code_resend_in, secondsLeft)
    sentOnce -> stringResource(R.string.login_sms_code_resend)
    else -> stringResource(R.string.login_sms_code_send)
}

/** 行内发码文字按钮：bodyMedium 13 accent，倒计时 / 不可点时 textTertiary；右内 16（稿 2-1 / 2-6）。 */
@Composable
fun SendCodeButton(text: String, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = IMTheme.colors
    Text(
        text = text,
        color = if (enabled) c.accent else c.textTertiary,
        style = MaterialTheme.typography.bodyMedium,
        maxLines = 1,
        modifier = modifier.clickable(enabled = enabled, onClick = onClick).padding(horizontal = IMTheme.dimens.space4, vertical = IMTheme.dimens.space2),
    )
}

/**
 * 登录页样式的「国家码 + 手机号」一行：国家码框宽 84、与号码框间距 8。
 * 只有一个开放地区时国家码固定不可点；多于一个时点开地区菜单（[regions] 传全部地区）。
 */
@Composable
fun PhoneNumberField(
    countryCode: String,
    regions: List<SmsRegion>,
    onCountryCode: (String) -> Unit,
    phone: String,
    onPhone: (String) -> Unit,
    label: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(IMTheme.dimens.space2), verticalAlignment = Alignment.CenterVertically) {
        CountryCodeBox(countryCode, regions, onCountryCode, enabled)
        IMTextField(
            phone, onPhone, label, Modifier.weight(1f), enabled = enabled,
            keyboard = KeyboardOptions(keyboardType = KeyboardType.Phone),
        )
    }
}

@Composable
private fun CountryCodeBox(code: String, regions: List<SmsRegion>, onPick: (String) -> Unit, enabled: Boolean) {
    val c = IMTheme.colors
    val multi = PhoneAuthRules.showRegionPicker(regions)
    val pickable = multi && enabled
    var open by remember { mutableStateOf(false) }
    val line = c.separator
    Box(
        Modifier.width(COUNTRY_BOX_WIDTH).heightIn(min = FIELD_MIN_HEIGHT)
            .clip(RoundedCornerShape(topStart = IMTheme.dimens.radiusCard, topEnd = IMTheme.dimens.radiusCard))
            .background(c.pageBackground)
            // 与 M3 TextField 的未聚焦下划线同色同粗（1dp separator），两框底边对齐成一条线
            .drawBehind { drawLine(line, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1.dp.toPx()) }
            .clickable(enabled = pickable) { open = true }
            .padding(horizontal = IMTheme.dimens.space4),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(if (multi) "+$code ▾" else "+$code", color = c.textPrimary, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = c.surfaceElevated) {
            Text(
                stringResource(R.string.login_region_title), color = c.textSecondary, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = IMTheme.dimens.space4, vertical = IMTheme.dimens.space1),
            )
            regions.forEach { r ->
                DropdownMenuItem(
                    text = { Text("+${r.code}  ${r.iso}", color = c.textPrimary) },
                    onClick = { open = false; onPick(r.code) },
                )
            }
        }
    }
}

/** 登录页样式的验证码框：M3 TextField + 右侧「获取验证码」文字按钮。 */
@Composable
fun SmsCodeField(
    code: String,
    onCode: (String) -> Unit,
    sendText: String,
    sendEnabled: Boolean,
    onSend: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
) {
    IMTextField(
        code, onCode, stringResource(R.string.login_sms_code_placeholder), modifier, enabled = enabled,
        placeholder = stringResource(R.string.login_sms_code_six_digits),
        keyboard = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        trailing = { SendCodeButton(sendText, sendEnabled, onSend) },
        isError = isError,
    )
}

/** 设置页分组里的一行文本输入（验证码 / 手机号）+ 可选右侧文字按钮；最小高 50、左 16（稿 2-6 / 2-8）。 */
@Composable
fun SettingsInputRow(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    keyboardType: KeyboardType,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    leading: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    focusRequester: androidx.compose.ui.focus.FocusRequester? = null,
) {
    val c = IMTheme.colors
    val d = IMTheme.dimens
    val style = MaterialTheme.typography.bodyLarge
    Row(
        modifier.fillMaxWidth().heightIn(min = d.settingsRowHeight).padding(start = d.space4, end = if (trailing == null) d.space4 else 0.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) Text(leading, color = c.textPrimary, style = style, modifier = Modifier.padding(end = d.space3))
        androidx.compose.foundation.text.BasicTextField(
            value = value, onValueChange = onValueChange, enabled = enabled, singleLine = true,
            textStyle = style.copy(color = c.textPrimary),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(c.accent),
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            modifier = Modifier.weight(1f).then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) Text(placeholder, color = c.textTertiary, style = style)
                    inner()
                }
            },
        )
        trailing?.invoke()
    }
}
