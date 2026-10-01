package com.libeyond.imandroid.fcm

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [fcmAvatarPlaceholderText]：占位图里画哪个字的纯逻辑。画位图本身（颜色/圆形裁剪）要 Robolectric，
 * 这个仓库没接，走真机肉眼验证；这一步的文字决策不碰 `android.graphics`，能直接单测。
 */
class FcmAvatarPlaceholderTextTest {

    @Test
    fun `正常名字——同DisplayName initials`() {
        assertEquals("丰", fcmAvatarPlaceholderText("张三丰", "1002"))
        assertEquals("L", fcmAvatarPlaceholderText("libeyond", "9801803917"))
    }

    /**
     * 名字为空（服务端老格式/异常 payload 没给标题）：退回用种子取字，不能画出一个没有字的纯色圆
     * ——`DisplayName.initials("")` 按新规则返回空串，这里必须兜底，不能直接传空名进去。
     */
    @Test
    fun `名字为空——退回用种子取字`() {
        assertEquals("用", fcmAvatarPlaceholderText("", "用户1001"))
        assertEquals("G", fcmAvatarPlaceholderText("   ", "g_f87fa35c240b73bd"))
    }
}
