package com.libeyond.imandroid.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.sp
import com.libeyond.imandroid.ui.theme.IMTheme

/**
 * 共用基础组件。**只做外壳，不持业务状态**（CODING_STYLE §7②）。
 * 颜色一律走 `IMTheme.colors`，禁止在这里写 Hex。
 */

/** 主行动按钮。对应 Web 的 `.login-submit`。 */
@Composable
fun IMPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    val c = IMTheme.colors
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .background(
                color = if (enabled && !loading) c.accent else c.accent.copy(alpha = 0.5f),
                shape = RoundedCornerShape(IMTheme.dimens.radiusCard),
            )
            .clickable(enabled = enabled && !loading) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp).padding(end = 0.dp),
                    color = c.onAccent,
                    strokeWidth = 2.dp,
                )
            } else {
                Text(text = text, color = c.onAccent)
            }
        }
    }
}

/** 次要按钮（描边）。 */
@Composable
fun IMSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val c = IMTheme.colors
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .background(c.cardBackground, RoundedCornerShape(IMTheme.dimens.radiusCard))
            .border(1.dp, c.accent, RoundedCornerShape(IMTheme.dimens.radiusCard))
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, color = c.accent)
    }
}

/** 输入框。输入区用页面背景色 + 分割线描边（UI_COLOR §4）。 */
@Composable
fun IMTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    isPassword: Boolean = false,
    enabled: Boolean = true,
    /** 键盘类型/大小写策略。用户名那种 ASCII 小写字段必须传，否则输入法会自动首字母大写。 */
    keyboard: KeyboardOptions = KeyboardOptions.Default,
    /** false＝多行（群简介/群公告这类长文本），撑到 [minLines]~[maxLines] 行、允许换行。 */
    singleLine: Boolean = true,
) {
    val c = IMTheme.colors
    TextField(
        value = value,
        onValueChange = onValueChange,
        // 空标签＝不画标签（弹窗里标题已经说清了字段，再来一个同名浮标签只是重复）
        label = if (label.isBlank()) null else ({ Text(label, color = c.textSecondary) }),
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 3,
        maxLines = if (singleLine) 1 else 8,
        enabled = enabled,
        keyboardOptions = keyboard,
        visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(IMTheme.dimens.radiusCard),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = c.pageBackground,
            unfocusedContainerColor = c.pageBackground,
            disabledContainerColor = c.pageBackground,
            focusedTextColor = c.textPrimary,
            unfocusedTextColor = c.textPrimary,
            cursorColor = c.accent,
            focusedIndicatorColor = c.accent,
            unfocusedIndicatorColor = c.separator,
        ),
    )
}

/** 错误提示行。 */
@Composable
fun IMErrorText(text: String, modifier: Modifier = Modifier) {
    if (text.isEmpty()) return
    Text(text = text, color = IMTheme.colors.danger, modifier = modifier)
}

/**
 * 一次性提示条（对齐 iOS 的 toast / Web 的 `setToast`）。
 *
 * 刻意**不用 Material3 的 Snackbar**：那要一整套 `SnackbarHostState` + `Scaffold`，
 * 而本端页面结构是自己拼的 Column，塞 Scaffold 只为一条提示不划算。
 *
 * 自动消失后回调 [onDismiss] 清状态——**不清的话同一条消息第二次触发时不会再显示**
 * （状态没变，Compose 不重组）。
 */
@Composable
fun IMToast(text: String, durationMs: Long = 2000, onDismiss: () -> Unit) {
    val c = IMTheme.colors
    LaunchedEffect(text) {
        kotlinx.coroutines.delay(durationMs)
        onDismiss()
    }
    Box(
        // **imePadding 不能省**：键盘弹起时这条会整个落在键盘背后，等于没有提示
        // （2026-09-09 真机撞见：搜索态下点▲跳不动，提示一个字都看不到）。
        modifier = Modifier.fillMaxSize().imePadding().padding(bottom = 96.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(c.datePillBackground)
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Text(text, color = c.onMedia, fontSize = 14.sp)
        }
    }
}
