package com.libeyond.imandroid.data

import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PendingMediaStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun store() = PendingMediaStore(File(tmp.root, "pending_media"))

    @Test fun `副本保留扩展名，引用只认本目录`() {
        val s = store()
        val f = s.newFile("cid1", "a.b.MP4")
        assertEquals("cid1.MP4", f.name)
        f.writeText("x")
        assertEquals(f.canonicalPath, s.fileOf(s.refOf(f))!!.canonicalPath)
        assertNull(s.fileOf("file://" + File(tmp.root, "voice_pending/x.m4a").path))
        assertNull(s.fileOf("content://media/1"))
        assertNull(s.fileOf("file://" + File(f.parentFile, "../escape").path))
    }

    @Test fun `upload_id 旁路文件与副本同生共死`() {
        val s = store()
        val f = s.newFile("c", "x.bin"); f.writeText("data")
        assertNull(s.uploadIdOf(f))
        s.setUploadId(f, "up-1")
        assertEquals("up-1", s.uploadIdOf(f))
        s.setUploadId(f, "")
        assertNull(s.uploadIdOf(f))
        s.setUploadId(f, "up-2")
        s.remove(f)
        assertFalse(f.exists()); assertNull(s.uploadIdOf(f))
    }

    @Test fun `流复制成功落盘，源读不到返回 false`() = runTest {
        val s = store()
        val f = s.newFile("c", "x.bin")
        assertTrue(s.copyFrom({ "hello".byteInputStream() }, f))
        assertEquals("hello", f.readText())
        assertFalse(s.copyFrom({ null }, s.newFile("d", "x.bin")))
        assertNotNull(f)
    }
}
