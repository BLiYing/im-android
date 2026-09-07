package com.libeyond.imandroid.data

import com.libeyond.imandroid.data.MediaPermission.Access
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 读相册权限按版本换过三次名字，换错了**不报错、只是永远拿不到照片**——所以逐版本钉死。
 */
class MediaPermissionTest {

    @Test
    fun `Android 11 用 READ_EXTERNAL_STORAGE`() {
        assertEquals(listOf(MediaPermission.READ_EXTERNAL), MediaPermission.required(30))
        assertEquals(listOf(MediaPermission.READ_EXTERNAL), MediaPermission.required(32))
    }

    @Test
    fun `Android 13 换成 READ_MEDIA_IMAGES`() {
        assertEquals(listOf(MediaPermission.READ_IMAGES), MediaPermission.required(33))
    }

    @Test
    fun `Android 14 起要一并申请部分授权`() {
        assertEquals(
            listOf(MediaPermission.READ_IMAGES, MediaPermission.READ_USER_SELECTED),
            MediaPermission.required(34),
        )
    }

    @Test
    fun `旧系统拿到旧权限算 Full`() {
        assertEquals(Access.Full, MediaPermission.access(30, setOf(MediaPermission.READ_EXTERNAL)))
        // 旧系统上给的是新权限名 → 无效（这正是「换名字换错了」的样子）
        assertEquals(Access.None, MediaPermission.access(30, setOf(MediaPermission.READ_IMAGES)))
    }

    @Test
    fun `Android 13 上旧权限名无效`() {
        assertEquals(Access.None, MediaPermission.access(33, setOf(MediaPermission.READ_EXTERNAL)))
        assertEquals(Access.Full, MediaPermission.access(33, setOf(MediaPermission.READ_IMAGES)))
    }

    @Test
    fun `Android 14 只授权部分照片是 Partial 不是 None`() {
        // 认成 None 会降级回系统选择器；认成 Full 会让用户以为相册里只有几张 —— 都错
        assertEquals(
            Access.Partial,
            MediaPermission.access(34, setOf(MediaPermission.READ_USER_SELECTED)),
        )
        assertEquals(
            Access.Full,
            MediaPermission.access(34, setOf(MediaPermission.READ_IMAGES, MediaPermission.READ_USER_SELECTED)),
        )
        assertEquals(Access.None, MediaPermission.access(34, emptySet()))
    }

    @Test
    fun `部分授权也能走自建宫格`() {
        assertTrue(MediaPermission.canBrowse(Access.Full))
        assertTrue(MediaPermission.canBrowse(Access.Partial))
        assertFalse(MediaPermission.canBrowse(Access.None))
    }
}
