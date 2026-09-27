package com.libeyond.imandroid.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Eye
import com.composables.icons.lucide.EyeOff
import com.composables.icons.lucide.Lucide
import com.libeyond.imandroid.R
import com.libeyond.imandroid.data.ChangePasswordRules
import com.libeyond.imandroid.data.PasswordFeedback
import com.libeyond.imandroid.data.PasswordField
import com.libeyond.imandroid.ui.components.IMPrimaryButton
import com.libeyond.imandroid.ui.components.IMRowDivider
import com.libeyond.imandroid.ui.components.IMSectionFooter
import com.libeyond.imandroid.ui.components.IMSettingsGroup
import com.libeyond.imandroid.ui.components.IMTopBar
import com.libeyond.imandroid.ui.theme.IMTheme

private val EYE_TOUCH = 40.dp
private val EYE_GLYPH = 20.dp

/**
 * 修改密码（对齐 iOS `IMChangePasswordViewController`）：旧密码一组、新密码 + 确认一组，
 * 每个框右侧眼睛切换明文；失败时红字 + 对应框描红，**输入不清空**。
 *
 * 纯展示：三个值、错误、提交态都由 `PrivacySecurityHost` 持有——提交挂在 `client.scope` 上，
 * 值放在页面里的话，用户在请求途中返回再进来，拿不到那次提交的结果。
 */
@Composable
fun ChangePasswordScreen(
    oldPassword: String,
    newPassword: String,
    confirmPassword: String,
    onOldChange: (String) -> Unit,
    onNewChange: (String) -> Unit,
    onConfirmChange: (String) -> Unit,
    error: PasswordFeedback.Inline?,
    submitting: Boolean,
    onSubmit: () -> Unit,
    onBack: () -> Unit,
) {
    val c = IMTheme.colors
    val d = IMTheme.dimens
    val oldFocus = remember { FocusRequester() }
    val newFocus = remember { FocusRequester() }
    val confirmFocus = remember { FocusRequester() }
    val canSubmit = ChangePasswordRules.canSubmit(oldPassword, newPassword, confirmPassword) && !submitting

    // 进页即聚焦旧密码框（iOS `becomeFirstResponder`）。只在进入时一次，故 key 用 Unit
    LaunchedEffect(Unit) { oldFocus.requestFocus() }

    Column(Modifier.fillMaxSize().background(c.groupedBackground).statusBarsPadding()) {
        IMTopBar(title = ChangePasswordRules.TITLE, onLeft = onBack)

        // imePadding：键盘弹起时按钮要能滚到键盘上面（iOS 调 contentInset 同理）
        Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(d.sectionGap))
            IMSettingsGroup {
                PasswordRow(
                    value = oldPassword, onValueChange = onOldChange,
                    placeholder = stringResource(R.string.password_field_old),
                    isError = error?.field == PasswordField.Old, enabled = !submitting,
                    focusRequester = oldFocus, imeAction = ImeAction.Next, onImeAction = { newFocus.requestFocus() },
                )
            }
            IMSectionFooter(ChangePasswordRules.FOOTER)

            Spacer(Modifier.height(d.sectionGap))
            IMSettingsGroup {
                PasswordRow(
                    value = newPassword, onValueChange = onNewChange,
                    placeholder = stringResource(R.string.password_field_new),
                    isError = error?.field == PasswordField.New, enabled = !submitting,
                    focusRequester = newFocus, imeAction = ImeAction.Next, onImeAction = { confirmFocus.requestFocus() },
                )
                IMRowDivider(insetStart = d.space4)
                PasswordRow(
                    value = confirmPassword, onValueChange = onConfirmChange,
                    placeholder = stringResource(R.string.password_field_confirm),
                    isError = error?.field == PasswordField.Confirm, enabled = !submitting,
                    focusRequester = confirmFocus, imeAction = ImeAction.Done,
                    onImeAction = { if (canSubmit) onSubmit() },
                )
            }

            error?.let {
                Text(
                    text = it.message,
                    color = c.danger,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(start = d.space4 * 2, end = d.space4 * 2, top = d.space2),
                )
            }
            Text(
                text = ChangePasswordRules.HELPER,
                color = c.textSecondary,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = d.space4 * 2, end = d.space4 * 2, top = d.space2),
            )
            Spacer(Modifier.height(d.space4 + d.space1))
            IMPrimaryButton(
                text = ChangePasswordRules.TITLE,
                onClick = onSubmit,
                modifier = Modifier.padding(horizontal = d.space4),
                enabled = canSubmit,
                loading = submitting,
            )
            Spacer(Modifier.height(d.sectionGap))
        }
    }
}

/** 一格密码输入：密文 + 右侧眼睛。明暗切换是纯界面态，留在这里（不上提）。 */
@Composable
private fun PasswordRow(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    isError: Boolean,
    enabled: Boolean,
    focusRequester: FocusRequester,
    imeAction: ImeAction,
    onImeAction: () -> Unit,
) {
    val c = IMTheme.colors
    val d = IMTheme.dimens
    var visible by remember { mutableStateOf(false) }
    val textStyle = MaterialTheme.typography.bodyLarge

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (isError) Modifier.border(1.dp, c.danger) else Modifier)
            .defaultMinSize(minHeight = d.settingsRowHeight)
            .padding(start = d.space4, end = d.space1),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = true,
            textStyle = textStyle.copy(color = c.textPrimary),
            cursorBrush = SolidColor(c.accent),
            // 键盘类型恒为 Password（不随明暗切换）：中途换类型会让输入法重置、吞掉正在输的那个字
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = imeAction),
            keyboardActions = KeyboardActions(onNext = { onImeAction() }, onDone = { onImeAction() }),
            visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
            modifier = Modifier.weight(1f).focusRequester(focusRequester),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) Text(placeholder, color = c.textTertiary, style = textStyle)
                    inner()
                }
            },
        )
        Box(
            modifier = Modifier.size(EYE_TOUCH).clickable { visible = !visible },
            contentAlignment = Alignment.Center,
        ) {
            // 密文态画「划掉的眼睛」、明文态画眼睛（iOS eye.slash.fill / eye.fill 同）
            Image(
                imageVector = if (visible) Lucide.Eye else Lucide.EyeOff,
                contentDescription = if (visible) {
                    stringResource(R.string.password_eye_hide)
                } else {
                    stringResource(R.string.password_eye_show)
                },
                modifier = Modifier.size(EYE_GLYPH),
                colorFilter = ColorFilter.tint(c.textSecondary),
            )
        }
    }
}
