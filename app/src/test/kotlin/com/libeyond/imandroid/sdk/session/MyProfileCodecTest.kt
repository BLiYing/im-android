package com.libeyond.imandroid.sdk.session

import com.libeyond.imandroid.sdk.api.UserCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MyProfileCodecTest {
    private val card = UserCard(userId = "1", username = "lan", nickname = "蓝", avatarUrl = "/a.png", phone = "138")

    @Test fun `往返保留昵称头像句柄，且不落手机号`() {
        val back = MyProfileCodec.decode("1", MyProfileCodec.encode("1", card))!!
        assertEquals("蓝", back.nickname)
        assertEquals("lan", back.username)
        assertEquals("/a.png", back.avatarUrl)
        assertEquals("", back.phone)
        assertEquals(false, MyProfileCodec.encode("1", card).contains("138"))
    }

    @Test fun `换号读不到上一个账号的副本`() {
        assertNull(MyProfileCodec.decode("2", MyProfileCodec.encode("1", card)))
    }

    @Test fun `脏数据与空值一律当没有而不抛`() {
        assertNull(MyProfileCodec.decode("1", "{not json"))
        assertNull(MyProfileCodec.decode("1", null))
        assertNull(MyProfileCodec.decode("", MyProfileCodec.encode("1", card)))
    }
}
