package com.libeyond.imandroid.data

import com.libeyond.mediapicker.MediaPick
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 选择器上限与宫格上限必须相等。
 *
 * `:media-picker` **不认识** `AlbumLayout`（依赖单向：app → media-picker），
 * 所以这条不变式没法用引用守住，只能用测试守住。
 *
 * 不相等的后果：选择器放 12 张进来、宫格只画 9 格，多出来那 3 张既不显示也没报错——
 * 用户以为发了 12 张，对端看到 9 张。
 */
class AlbumLimitParityTest {

    @Test
    fun `选择器上限等于宫格上限`() {
        assertEquals(AlbumLayout.MAX, MediaPick.LIMIT)
        assertEquals(9, AlbumLayout.MAX)
    }
}
