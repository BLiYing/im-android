package com.libeyond.imandroid.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [avatarColorForSeed]：取色必须与 iOS `IMTheme avatarColorForSeed:` / Web `avatarColor` **位对位一致**
 * ——同一个 uid/conv_id 三端必须同色，否则换个端看头像变色会让人以为不是同一个人/同一个群
 * （2026-10-02 真机走查发现：Android 曾因取模用了有符号数而跟另外两端不一致，见下面的回归测试）。
 *
 * 下面这几个期望下标是用 Web 的 `avatarColor()`（BigInt 无符号实现，当基准）离线跑出来的，
 * 不是拍脑袋编的——哈希/取模任何一端改了都会在这几个真实种子上露出来。断言直接引用 [AVATAR_PALETTE]
 * 本体（没有另抄一份 RGB 字面量）：这条测的是「种子选对了下标」，不是「色板颜色是哪几个」。
 */
class AvatarColorTest {

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
            assertEquals("seed=$seed", AVATAR_PALETTE[idx], avatarColorForSeed(seed))
        }
    }

    @Test
    fun `空种子回落第一色`() {
        assertEquals(AVATAR_PALETTE[0], avatarColorForSeed(""))
    }

    @Test
    fun `同一种子稳定同色`() {
        assertEquals(avatarColorForSeed("abc123"), avatarColorForSeed("abc123"))
    }
}
