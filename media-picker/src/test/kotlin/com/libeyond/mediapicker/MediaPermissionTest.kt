package com.libeyond.mediapicker

import com.libeyond.mediapicker.MediaPermission.Access
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
        // 默认收视频，所以是图片 + 视频两条（不收视频的分支另有一条用例）
        assertEquals(
            listOf(MediaPermission.READ_IMAGES, MediaPermission.READ_VIDEO),
            MediaPermission.required(33),
        )
    }

    @Test
    fun `Android 14 起要一并申请部分授权`() {
        assertEquals(
            listOf(MediaPermission.READ_IMAGES, MediaPermission.READ_VIDEO, MediaPermission.READ_USER_SELECTED),
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

    // —— 视频权限是 Android 13 起分出来的独立一条 ——

    @Test
    fun `不收视频就不申请视频权限`() {
        // 申请了却用不上就是权限过度索取，商店审核会问
        assertEquals(listOf(MediaPermission.READ_IMAGES), MediaPermission.required(33, includeVideo = false))
        assertEquals(
            listOf(MediaPermission.READ_IMAGES, MediaPermission.READ_VIDEO),
            MediaPermission.required(33, includeVideo = true),
        )
        // ≤32 只有一条老权限、它同时覆盖图片和视频，所以这一段没有区别
        assertEquals(MediaPermission.required(30, false), MediaPermission.required(30, true))
    }

    @Test
    fun `只拒了视频不该让整个选择器降级`() {
        // 以图片权限为准：单独拒视频的用户仍然要能发图
        val onlyImages = setOf(MediaPermission.READ_IMAGES)
        assertEquals(Access.Full, MediaPermission.access(33, onlyImages))
        assertFalse(MediaPermission.canReadVideo(33, onlyImages))
        assertTrue(MediaPermission.canReadVideo(33, onlyImages + MediaPermission.READ_VIDEO))
        // 老系统上一条权限管两样
        assertTrue(MediaPermission.canReadVideo(30, setOf(MediaPermission.READ_EXTERNAL)))
    }
}
