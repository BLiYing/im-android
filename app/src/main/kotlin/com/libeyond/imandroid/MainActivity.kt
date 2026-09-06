package com.libeyond.imandroid

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import com.libeyond.imandroid.ui.theme.IMAppTheme
import com.libeyond.imandroid.ui.theme.IMTheme

/**
 * 应用入口。
 *
 * 目前是**骨架**：只验证主题令牌通到屏幕上（浅色/深色各一套取值）。
 * 登录页 / 会话列表 / 聊天页按 `../IMServer/docs/CLIENT_PARITY.md` 的 M0 行逐项接，见 `current_task.md`。
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            IMAppTheme {
                ScaffoldPlaceholder()
            }
        }
    }
}

@Composable
private fun ScaffoldPlaceholder() {
    val c = IMTheme.colors
    val d = IMTheme.dimens
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(c.groupedBackground)
            .systemBarsPadding()
            .padding(d.space4),
        verticalArrangement = Arrangement.spacedBy(d.space2, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "IM Android",
            style = androidx.compose.material3.MaterialTheme.typography.headlineSmall,
            color = c.textPrimary,
        )
        Text(
            text = "骨架已就位：主题令牌已接通，业务界面待接。",
            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
            color = c.textSecondary,
            textAlign = TextAlign.Center,
        )
    }
}

@Preview(name = "浅色", showBackground = true)
@Composable
private fun PreviewLight() {
    IMAppTheme(mode = com.libeyond.imandroid.ui.theme.IMThemeMode.Light) { ScaffoldPlaceholder() }
}

@Preview(name = "深色", showBackground = true)
@Composable
private fun PreviewDark() {
    IMAppTheme(mode = com.libeyond.imandroid.ui.theme.IMThemeMode.Dark) { ScaffoldPlaceholder() }
}
