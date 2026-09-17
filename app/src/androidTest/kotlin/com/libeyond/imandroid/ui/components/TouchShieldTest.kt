package com.libeyond.imandroid.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 覆盖页的触摸屏蔽层（[blockPointerInput]）必须同时做到两件事：
 * 挡住落到下层页面的触摸，且**不打断**上层列表自己的拖动。
 *
 * 后者是 2026-09-17「聊天信息页划不动、有时能划」的根因——JVM 单测模拟不了指针派发，只能在设备上跑：
 * `./gradlew :app:connectedDebugAndroidTest`（会卸载 App，想保留登录态就手动 `adb install -r` + `am instrument`）。
 */
@RunWith(AndroidJUnit4::class)
class TouchShieldTest {
    @get:Rule val rule = createComposeRule()

    private var underTapped = false
    private lateinit var list: LazyListState

    private fun setUp() {
        rule.setContent {
            list = rememberLazyListState()
            Box(Modifier.fillMaxSize()) {
                // 被盖住的下层页（聊天页）
                Box(Modifier.fillMaxSize().clickable { underTapped = true })
                // 覆盖页：顶部一段没有任何手势的留白，下面是长列表
                Box(Modifier.fillMaxSize().blockPointerInput()) {
                    LazyColumn(Modifier.fillMaxSize().testTag("list"), state = list) {
                        item { Spacer(Modifier.fillMaxWidth().height(200.dp)) }
                        items(200) { i -> Text("row $i", Modifier.fillMaxWidth().height(60.dp)) }
                    }
                }
            }
        }
    }

    /** 手指慢慢推：每一帧位移都小于 touch slop。修之前这种拖法整个页面纹丝不动。 */
    @Test
    fun slowDragStillScrollsList() {
        setUp()
        rule.onNodeWithTag("list").performTouchInput {
            down(Offset(centerX, bottom - 50f))
            repeat(80) { moveBy(Offset(0f, -4f)) }
            up()
        }
        rule.waitForIdle()
        assertTrue(
            "慢速拖动没有滚动：index=${list.firstVisibleItemIndex} offset=${list.firstVisibleItemScrollOffset}",
            list.firstVisibleItemIndex > 0 || list.firstVisibleItemScrollOffset > 0,
        )
    }

    @Test
    fun tapOnBlankAreaDoesNotReachPageBelow() {
        setUp()
        rule.onNodeWithTag("list").performTouchInput { click(Offset(centerX, 40f)) }
        rule.waitForIdle()
        assertFalse("点覆盖页留白处穿透到了下层页", underTapped)
    }
}
