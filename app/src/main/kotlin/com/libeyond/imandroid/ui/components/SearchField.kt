package com.libeyond.imandroid.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Search
import com.composables.icons.lucide.X
import com.libeyond.imandroid.R
import com.libeyond.imandroid.ui.theme.IMTheme

/**
 * 列表页的搜索框（对齐 iOS `IMListSearchBarMake`：放大镜 + 占位字 + 清空钮，边打边搜）。
 * 匹配口径不在这里，在 `data/ListSearch.kt`。
 *
 * 外观与会话内搜索条（`ChatSearchBar`）的输入框同一套圆角与字号；底色用 subtleFill——
 * 本组件常放在页面底色上，用页面底色就看不出框在哪。
 */
@Composable
fun IMSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    val c = IMTheme.colors
    val d = IMTheme.dimens
    val focus = LocalFocusManager.current
    Row(
        modifier = modifier
            .heightIn(min = d.inputControl)
            .clip(RoundedCornerShape(IMTheme.appearance.bubbleRadius))
            .background(c.subtleFill)
            .padding(horizontal = d.space3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            imageVector = Lucide.Search,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            colorFilter = ColorFilter.tint(c.textTertiary),
        )
        Spacer(Modifier.width(d.space2))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) {
                Text(placeholder, color = c.textTertiary, fontSize = 15.sp)
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = TextStyle(color = c.textPrimary, fontSize = 15.sp),
                cursorBrush = SolidColor(c.accent),
                // 关自动纠正与首字母大写：搜 uid / 英文昵称时被输入法改坏就搜不到了（iOS 同样关掉）
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Search,
                ),
                // 边打边搜，回车只用来收键盘（iOS `searchBarSearchButtonClicked:` 同）
                keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (value.isNotEmpty()) {
            Image(
                imageVector = Lucide.X,
                contentDescription = stringResource(R.string.common_clear),
                modifier = Modifier.size(16.dp).clickable { onValueChange("") },
                colorFilter = ColorFilter.tint(c.textTertiary),
            )
        }
    }
}

/**
 * 会话列表顶部的搜索**入口胶囊**（对齐 iOS `searchEntryTapped`）：长得像 [IMSearchField]，
 * 但不承载输入——点一下整条进全局搜索页，在那一页里输入。
 */
@Composable
fun IMSearchEntry(placeholder: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = IMTheme.colors
    val d = IMTheme.dimens
    Row(
        modifier = modifier
            .heightIn(min = d.inputControl)
            .clip(RoundedCornerShape(IMTheme.appearance.bubbleRadius))
            .background(c.subtleFill)
            .clickable(onClick = onClick)
            .padding(horizontal = d.space3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            imageVector = Lucide.Search,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            colorFilter = ColorFilter.tint(c.textTertiary),
        )
        Spacer(Modifier.width(d.space2))
        Text(placeholder, color = c.textTertiary, fontSize = 15.sp)
    }
}
