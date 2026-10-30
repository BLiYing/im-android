package com.libeyond.imandroid.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.ShieldCheck
import com.libeyond.imandroid.data.TwoStepRules
import com.libeyond.imandroid.ui.components.IMTopBar
import com.libeyond.imandroid.ui.theme.IMTheme

/** 两步验证各页共用的页面骨架：灰底 + 顶栏 + 可滚动内容（`imePadding` 让按钮滚到键盘之上）。 */
@Composable
internal fun TwoStepPage(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().background(IMTheme.colors.groupedBackground).statusBarsPadding()) {
        IMTopBar(title = title, onLeft = onBack)
        Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState())) { content() }
    }
}

/** 说明文字 / 红字：左右 32dp，距上 8dp（设计稿 §5.2）。 */
@Composable
internal fun TwoStepNote(text: String, error: Boolean = false) {
    if (text.isEmpty()) return
    val d = IMTheme.dimens
    Text(
        text = text,
        color = if (error) IMTheme.colors.danger else IMTheme.colors.textSecondary,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.fillMaxWidth().padding(start = d.space4 * 2, end = d.space4 * 2, top = d.space2),
    )
}

/** 盾牌图标 + 标题 + 副文（介绍页 / 状态页）。 */
@Composable
internal fun TwoStepHero(title: String, subtitle: String) {
    val c = IMTheme.colors
    Column(
        Modifier.fillMaxWidth().padding(start = IMTheme.dimens.space4 * 2, end = IMTheme.dimens.space4 * 2, top = IMTheme.dimens.sectionGap),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(IMTheme.dimens.space2),
    ) {
        Image(Lucide.ShieldCheck, null, Modifier.size(56.dp), colorFilter = ColorFilter.tint(c.accent))
        Text(title, color = c.textPrimary, fontSize = 20.sp, textAlign = TextAlign.Center)
        if (subtitle.isNotEmpty()) {
            Text(subtitle, color = c.textSecondary, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
        }
    }
}

/**
 * 验证页里的一格码输入（设置行样式）：动态码 = 数字键盘 + 清洗；恢复码 = 文本框，关闭自动大写 / 纠正，
 * **不改写**（只限长，归一化在提交时做）。
 */
@Composable
internal fun CodeRow(
    value: String,
    onChange: (String) -> Unit,
    recovery: Boolean,
    placeholder: String,
    enabled: Boolean,
    focusRequester: FocusRequester,
    onDone: () -> Unit,
) {
    val c = IMTheme.colors
    val d = IMTheme.dimens
    val style = MaterialTheme.typography.bodyLarge
    Box(Modifier.fillMaxWidth().defaultMinSize(minHeight = d.settingsRowHeight).padding(horizontal = d.space4), contentAlignment = Alignment.CenterStart) {
        BasicTextField(
            value = value,
            onValueChange = { onChange(if (recovery) TwoStepRules.acceptRecoveryInput(value, it) else TwoStepRules.sanitizeCode(it)) },
            enabled = enabled,
            singleLine = true,
            textStyle = style.copy(color = c.textPrimary, fontFamily = if (recovery) FontFamily.Monospace else style.fontFamily),
            cursorBrush = SolidColor(c.accent),
            keyboardOptions = codeKeyboard(recovery),
            keyboardActions = KeyboardActions(onDone = { onDone() }),
            modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) Text(placeholder, color = c.textTertiary, style = style)
                    inner()
                }
            },
        )
    }
}

internal fun codeKeyboard(recovery: Boolean): KeyboardOptions =
    if (recovery) {
        KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done)
    } else {
        KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done)
    }

/** 绑定页的 6 位大号输入：28sp 等宽、字距 8sp、居中；错误态 1dp danger 边框。 */
@Composable
internal fun BigCodeField(
    value: String,
    onChange: (String) -> Unit,
    isError: Boolean,
    enabled: Boolean,
    focusRequester: FocusRequester,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    background: Boolean = true,
) {
    val c = IMTheme.colors
    val d = IMTheme.dimens
    val textStyle = MaterialTheme.typography.bodyLarge.copy(
        color = c.textPrimary, fontFamily = FontFamily.Monospace, fontSize = 28.sp, letterSpacing = 8.sp, textAlign = TextAlign.Center,
    )
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(d.radiusCard)
    Box(
        modifier = modifier.fillMaxWidth()
            .then(if (background) Modifier.background(c.cardBackground, shape) else Modifier)
            .then(if (isError) Modifier.border(1.dp, c.danger, shape) else Modifier)
            .defaultMinSize(minHeight = 56.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicTextField(
            value = value,
            onValueChange = { onChange(TwoStepRules.sanitizeCode(it)) },
            enabled = enabled,
            singleLine = true,
            textStyle = textStyle,
            cursorBrush = SolidColor(c.accent),
            keyboardOptions = codeKeyboard(false),
            keyboardActions = KeyboardActions(onDone = { onDone() }),
            modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.Center) {
                    if (value.isEmpty()) Text("······", color = c.textTertiary, style = textStyle)
                    inner()
                }
            },
        )
    }
}

/** 文字链（accent）：「改用恢复码」「复制」。内容垂直居中；[minHeight] 给设置行里的整行用。 */
@Composable
internal fun TwoStepLink(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    secondary: Boolean = false,
    minHeight: androidx.compose.ui.unit.Dp = androidx.compose.ui.unit.Dp.Unspecified,
) {
    val c = IMTheme.colors
    Box(
        modifier = modifier.fillMaxWidth()
            .then(if (minHeight != androidx.compose.ui.unit.Dp.Unspecified) Modifier.defaultMinSize(minHeight = minHeight) else Modifier)
            .clickable(onClick = onClick).padding(vertical = IMTheme.dimens.space2),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = if (secondary) c.textSecondary else c.accent,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
    }
}
