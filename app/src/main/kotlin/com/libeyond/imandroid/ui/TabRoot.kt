package com.libeyond.imandroid.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Tab 根页的外壳：内容 + 底部 Tab 栏。**只有根页用它**，二级页整屏铺满、不带底栏
 * （判据 `PushNav.showsTabBar`；各 Host 在 depth=0 那一支里套这一层）。
 *
 * **为什么是插槽而不是把各 Tab 的页面状态提升到 MainScreen**：
 * ① 同一次组合里就定了画不画，不会有「先画一帧底栏再收起」的跳动；
 * ② 各 Host 的页面状态与它的副作用绑得很紧（进搜索页要清旧结果、进群聊页要拉列表），
 *    提升上去等于把这些胶水一并搬走；
 * ③ 底栏长在根页里，push 时它**跟着根页一起滑走**——正是 iOS `hidesBottomBarWhenPushed` 的样子。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TabRoot(bottomBar: @Composable () -> Unit, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        // 底栏自己垫了导航栏那一截（navigationBarsPadding）。根页内部的 systemBarsPadding
        // 不该再垫一遍，否则列表与底栏之间空出一条导航栏高的空白
        Box(Modifier.weight(1f).consumeWindowInsets(WindowInsets.navigationBars)) { content() }
        bottomBar()
    }
}
