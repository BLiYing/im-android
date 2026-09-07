package com.libeyond.imandroid.ui

import androidx.compose.ui.unit.dp
import com.libeyond.imandroid.ui.screens.bubbleMaxWidth
import com.libeyond.imandroid.ui.theme.IMDimens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 气泡最大宽必须**随屏宽缩放**（IMServer `docs/UI_SPEC.md` §3）。
 *
 * 这组测试钉的是「它是比例不是定值」——本端一度写死 280dp，
 * 在单一机型模拟器上肉眼完全看不出问题，只有换屏宽才暴露。
 */
class BubbleWidthTest {

    private val fraction = IMDimens().bubbleMaxWidthFraction

    @Test
    fun `窄机与宽机算出的最大宽必须不同——写死定值会让这条红`() {
        val narrow = bubbleMaxWidth(360.dp, fraction)   // 典型 5.x 寸
        val wide = bubbleMaxWidth(411.dp, fraction)     // Pixel 系
        assertTrue("气泡最大宽没有随屏宽变化，说明退回成了固定 dp", narrow < wide)
    }

    @Test
    fun `比例取 iOS 的 0_75，与 IMBubbleCell 的 multiplier 同值`() {
        assertEquals(0.75f, fraction, 0.0001f)
        assertEquals(270f, bubbleMaxWidth(360.dp, fraction).value, 0.01f)
        assertEquals(308.25f, bubbleMaxWidth(411.dp, fraction).value, 0.01f)
    }

    @Test
    fun `固定 280dp 这个历史值在两种屏宽下都不落在可接受区间`() {
        // 记录当初错在哪：280dp 在 360 宽占 77.8%、411 宽占 68.1%，
        // 而基准是恒定 75%。任何人想改回定值，先看这条。
        assertEquals(0.778f, 280f / 360f, 0.001f)
        assertEquals(0.681f, 280f / 411f, 0.001f)
    }

    @Test
    fun `群头像列合计 48——与 iOS _leading_constant 同值`() {
        // iOS：`_leading.constant = gutter ? 48 : 12`，注释写明 48 = 12 + 30 + 6。
        // 本端这三段分别由「消息列表横向内边距」「头像」「间隙」提供；
        // **实测踩过一次**：Bubbles 里又加了一遍 12，头像左边距变 24、气泡左缘变 60。
        val d = IMDimens()
        assertEquals(
            "头像列合计必须 = 12 + 30 + 6 = 48",
            48f,
            (d.chatAvatarLeading + d.chatAvatar + d.chatAvatarGap).value,
            0.01f,
        )
        assertEquals("列表横向内边距就是这 12，别各写各的", 12f, d.chatAvatarLeading.value, 0.01f)
    }

    @Test
    fun `会话行分割线缩进恰好对齐头像右缘`() {
        val d = IMDimens()
        assertEquals(
            "分割线缩进必须 = 行左边距 + 头像 + 间距，否则与头像列错位",
            (d.space4 + d.convAvatar + d.space3).value,
            d.convSeparatorInset.value,
            0.01f,
        )
        assertEquals(80f, d.convSeparatorInset.value, 0.01f)  // iOS separatorInset 同值
    }
}
