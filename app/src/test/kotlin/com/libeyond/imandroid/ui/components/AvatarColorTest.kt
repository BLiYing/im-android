package com.libeyond.imandroid.ui.components

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [avatarColorForSeed]：取色必须与 iOS `IMTheme avatarColorForSeed:` / Web `avatarColor` **位对位一致**
 * ——同一个 uid/conv_id 三端必须同色，否则换个端看头像变色会让人以为不是同一个人/同一个群
 * （2026-10-02 真机走查发现：Android 曾因取模用了有符号数而跟另外两端不一致，见下面的回归测试）。
 *
 * 下面这几个期望值是用 Web 的 `avatarColor()`（BigInt 无符号实现，当基准）离线跑出来的，
 * 不是拍脑袋编的——任何一端改了哈希/取模都会在这几个真实种子上露出来。
 */
class AvatarColorTest {

    private val palette = listOf(
        Color(red = 0.20f, green = 0.60f, blue = 0.96f, alpha = 1f), // 蓝 0
        Color(red = 0.31f, green = 0.78f, blue = 0.47f, alpha = 1f), // 绿 1
        Color(red = 0.96f, green = 0.62f, blue = 0.20f, alpha = 1f), // 橙 2
        Color(red = 0.90f, green = 0.36f, blue = 0.42f, alpha = 1f), // 红 3
        Color(red = 0.58f, green = 0.45f, blue = 0.90f, alpha = 1f), // 紫 4
        Color(red = 0.18f, green = 0.72f, blue = 0.74f, alpha = 1f), // 青 5
    )

    @Test
    fun `与Web avatarColor 跑出的基准值逐一对齐`() {
        // seed to 预期下标（Web BigInt 无符号实现跑出来的基准，见类注释）
        val expected = mapOf(
            "g_f87fa35c240b73bd" to 1, // 真机走查那个「光」字群：Android 曾算成 3（红），应为 1（绿）
            "1000156391" to 2,
            "5205766476" to 0,
            "9801803917" to 4,
            "5638703414" to 5,
            "g_75dfcc0e9dbb2108" to 5,
            "libeyond" to 2,
        )
        for ((seed, idx) in expected) {
            assertEquals("seed=$seed", palette[idx], avatarColorForSeed(seed))
        }
    }

    @Test
    fun `空种子回落第一色`() {
        assertEquals(palette[0], avatarColorForSeed(""))
    }

    @Test
    fun `同一种子稳定同色`() {
        assertEquals(avatarColorForSeed("abc123"), avatarColorForSeed("abc123"))
    }
}
