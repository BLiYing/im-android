package com.libeyond.imandroid.data

import com.libeyond.imandroid.data.UploadPrecheck.Failure
import com.libeyond.imandroid.sdk.protocol.ErrCode
import com.libeyond.mediapicker.MediaPick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** S3a 上传策略预检（对端 im-web `src/sdk/uploadPrecheck.test.ts`，判据同序）。 */
class UploadPrecheckTest {
    private val mb = 1L shl 20
    private val open = ServerLimits(uploadEnabled = true, uploadMaxFileBytes = 100 * mb, uploadBlockedExtensions = listOf(".exe", ".apk"))

    @Test fun `默认（老服务端）放行 2GB 以内任意类型`() {
        assertNull(UploadPrecheck.check("a.exe", 2L shl 30, false, ServerLimits()))
        assertEquals(Failure.TooLarge("2 GB"), UploadPrecheck.check("a.mp4", (2L shl 30) + 1, false, ServerLimits()))
    }

    @Test fun `总开关关：一切都拦，语音也拦，且排在类型与大小之前`() {
        val off = open.copy(uploadEnabled = false)
        assertEquals(Failure.Disabled, UploadPrecheck.check("a.jpg", 1, false, off))
        assertEquals(Failure.Disabled, UploadPrecheck.check("v.m4a", 1, true, off))
        assertEquals(Failure.Disabled, UploadPrecheck.check("a.exe", 999 * mb, false, off))
    }

    @Test fun `扩展名大小写不敏感，且先于大小判`() {
        assertEquals(Failure.BlockedType, UploadPrecheck.check("Setup.EXE", 1, false, open))
        assertEquals(Failure.BlockedType, UploadPrecheck.check("dir/x.Apk", 999 * mb, false, open))
        assertNull(UploadPrecheck.check("x.apk.txt", 1, false, open))
    }

    @Test fun `没扩展名 点开头 点结尾都不算命中`() {
        val l = open.copy(uploadBlockedExtensions = listOf(".bashrc", ".exe"))
        assertNull(UploadPrecheck.check(".bashrc", 1, false, l))
        assertNull(UploadPrecheck.check("exe", 1, false, l))
        assertNull(UploadPrecheck.check("a.", 1, false, l))
        assertEquals("", UploadPrecheck.lowerExtension("a\\b.c/d"))
        assertEquals(".gz", UploadPrecheck.lowerExtension("x/a.tar.GZ"))
    }

    @Test fun `超限：等于上限放行，多一字节拦`() {
        assertNull(UploadPrecheck.check("a.mp4", 100 * mb, false, open))
        assertEquals(Failure.TooLarge("100 MB"), UploadPrecheck.check("a.mp4", 100 * mb + 1, false, open))
    }

    @Test fun `语音豁免类型与大小（服务端另有固定 16MB 上限）`() {
        val l = open.copy(uploadMaxFileBytes = 1 * mb, uploadBlockedExtensions = listOf(".m4a"))
        assertNull(UploadPrecheck.check("v.m4a", 10 * mb, true, l))
        assertEquals(Failure.BlockedType, UploadPrecheck.check("v.m4a", 10 * mb, false, l))
    }

    @Test fun `上限文案：GiB 整数倍写 GB，否则按 MB 取整、至少 1 MB`() {
        assertEquals("2 GB", UploadPrecheck.sizeLabel(2L shl 30))
        assertEquals("1 GB", UploadPrecheck.sizeLabel(1L shl 30))
        assertEquals("1536 MB", UploadPrecheck.sizeLabel(1536 * mb))
        assertEquals("500 MB", UploadPrecheck.sizeLabel(500 * mb))
        assertEquals("1.5 MB", UploadPrecheck.sizeLabel(3 * mb / 2)) // 非整 MiB 保留一位小数（同服务端）
        assertEquals("500.0 MB", UploadPrecheck.sizeLabel(500 * mb + 123))
    }

    @Test fun `失败结果带的码与服务端同`() {
        assertEquals(ErrCode.UPLOAD_DISABLED, Failure.Disabled.code)
        assertEquals(ErrCode.FILE_TYPE_INVALID, Failure.BlockedType.code)
        assertEquals(ErrCode.FILE_TOO_LARGE, Failure.TooLarge("1 MB").code)
        assertEquals(500003, ErrCode.UPLOAD_DISABLED)
    }

    @Test fun `兜底上限与选择器模块的兜底同值`() =
        assertEquals(MediaPick.DEFAULT_MAX_BYTES, ServerLimits.DEFAULT_UPLOAD_MAX_FILE_BYTES)
}
