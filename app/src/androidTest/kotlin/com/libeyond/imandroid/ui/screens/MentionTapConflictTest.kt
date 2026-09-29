package com.libeyond.imandroid.ui.screens

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.libeyond.imandroid.data.Mention
import com.libeyond.imandroid.sdk.protocol.MentionSpan
import com.libeyond.imandroid.ui.theme.IMAppTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 复现根因排查：`Bubble` 气泡本体挂了 `combinedClickable`（长按菜单 + 轻点），
 * 气泡内的 `@提及` 走 Compose `LinkAnnotation.Clickable`（`chatBodyText`/`withClickable`）。
 * 两套手势识别器嵌套在同一棵子树里——`data/Mention.kt` 与 `MentionText.kt` 的纯函数单测
 * 只能测「切段对不对」，测不到「指针事件到底派发给了谁」（`TouchShieldTest.kt` 同一结论，
 * JVM 单测模拟不了指针派发）。本测试在真机/模拟器上验证：点气泡里的 `@某人` 究竟落进
 * `onTapMention` 还是被外层气泡吃掉。
 */
@RunWith(AndroidJUnit4::class)
class MentionTapConflictTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun tapOnMentionInsideBubbleReachesOnTapMention() {
        var tappedUid: String? = null
        var bubbleLongPressed = false
        val spansJson = Mention.encodeSpans(listOf(MentionSpan(offset = 0, length = 4, uid = "u1")))

        rule.setContent {
            IMAppTheme {
                Bubble(
                    text = "@bob",
                    mine = false,
                    timestamp = 0L,
                    senderName = null,
                    mentionSpansJson = spansJson,
                    onTapMention = { tappedUid = it },
                    onLongPress = { bubbleLongPressed = true },
                )
            }
        }

        rule.onNodeWithText("@bob").performClick()
        rule.waitForIdle()

        assertEquals("轻点气泡里的 @bob 应命中 onTapMention(uid)，而不是被气泡外层手势吃掉", "u1", tappedUid)
        assertEquals(false, bubbleLongPressed)
    }
}
