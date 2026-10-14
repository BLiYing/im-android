package com.libeyond.imandroid.data

import com.libeyond.imandroid.sdk.api.ChatFoldersRemote
import com.libeyond.imandroid.sdk.logging.IMLog
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** [ChatFolderStore] 的整表替换语义：版本去重、limits、按账号落盘与隔离、写操作以回包为准。 */
class ChatFolderStoreTest {

    @Before
    fun quietLogs() = IMLog.useSinksForTest()

    private class FakePersistence : FolderPersistence {
        val saved = HashMap<String, FolderSnapshot>()
        override fun load(owner: String) = saved[owner]
        override fun save(owner: String, snapshot: FolderSnapshot) { saved[owner] = snapshot }
        override fun clear(owner: String) { saved.remove(owner) }
    }

    /** 假服务端：每次写 version+1，按真实语义维护整表。 */
    private class FakeRemote : ChatFoldersRemote {
        var version = 0L
        var folders = mutableListOf<ChatFolder>()
        var failNext: Exception? = null
        private fun snap(): FolderSnapshot { failNext?.let { failNext = null; throw it }; return FolderSnapshot(version, folders.toList()) }
        override suspend fun list() = snap()
        override suspend fun create(name: String, convIds: List<String>, clientId: String): FolderSnapshot {
            version++; folders.add(ChatFolder("f_$clientId", name, folders.size, convIds)); return snap()
        }
        override suspend fun rename(folderId: String, name: String): FolderSnapshot {
            version++; folders = folders.map { if (it.id == folderId) it.copy(name = name) else it }.toMutableList(); return snap()
        }
        override suspend fun delete(folderId: String): FolderSnapshot {
            version++; folders = folders.filter { it.id != folderId }.mapIndexed { i, f -> f.copy(position = i) }.toMutableList(); return snap()
        }
        override suspend fun reorder(folderIds: List<String>): FolderSnapshot {
            version++; folders = folderIds.mapIndexed { i, id -> folders.first { it.id == id }.copy(position = i) }.toMutableList(); return snap()
        }
        override suspend fun changeConvs(folderId: String, add: List<String>, remove: List<String>): FolderSnapshot {
            version++
            folders = folders.map { f ->
                if (f.id != folderId) f else f.copy(convIds = (f.convIds - remove.toSet()) + add.filter { it !in f.convIds })
            }.toMutableList()
            return snap()
        }
    }

    private fun snap(version: Long, vararg folders: ChatFolder, limits: FolderLimits = FolderLimits()) =
        FolderSnapshot(version, folders.toList(), limits)

    private fun f(id: String, name: String, pos: Int = 0, vararg convs: String) = ChatFolder(id, name, pos, convs.toList())

    private fun newStore(persistence: FolderPersistence = FakePersistence(), remote: ChatFoldersRemote = FakeRemote(), uid: String = "u1") =
        ChatFolderStore(remote, persistence) { uid }

    @Test
    fun `版本更新才替换，同版本与更旧的丢弃`() {
        val s = newStore()
        assertTrue(s.apply(snap(5, f("f1", "A"))))
        assertFalse("同版本（写回包与同版本帧）", s.apply(snap(5, f("f9", "Z"))))
        assertFalse("更旧（乱序帧）", s.apply(snap(4)))
        assertEquals(listOf("f1"), s.state.value.folders.map { it.id })
        assertTrue(s.apply(snap(6))) // 清空也是合法的整表替换
        assertTrue(s.state.value.folders.isEmpty())
    }

    @Test
    fun `limits 随任何回包更新，帧不带 limits 时沿用本地`() {
        val s = newStore()
        s.apply(snap(1, limits = FolderLimits(maxFolders = 5, maxConvs = 20, maxName = 8)))
        assertEquals(5, s.state.value.limits.maxFolders)
        s.apply(snap(2, f("f1", "A")), fromFrame = true) // 帧里的 limits 是默认值，不能把 5 冲回 10
        assertEquals(5, s.state.value.limits.maxFolders)
        assertEquals(20, s.state.value.limits.maxConvs)
    }

    @Test
    fun `落盘按账号隔离，切账号清内存，切回读盘`() {
        val p = FakePersistence()
        var uid = "a"
        val s = ChatFolderStore(FakeRemote(), p) { uid }
        s.apply(snap(7, f("f1", "A", 0, "c1")))
        uid = "b"
        s.adopt("b")
        assertTrue(s.state.value.folders.isEmpty())
        assertEquals(0L, s.state.value.version)
        s.apply(snap(1, f("g1", "B-only")))
        uid = "a"
        s.adopt("a")
        assertEquals(7L, s.state.value.version)
        assertEquals(listOf("f1"), s.state.value.folders.map { it.id })
        assertNull(p.saved["c"])
    }

    @Test
    fun `forget 清内存但落盘保留`() {
        val p = FakePersistence()
        val s = newStore(p)
        s.apply(snap(3, f("f1", "A")))
        s.forget()
        assertTrue(s.state.value.folders.isEmpty())
        assertNotNull(p.saved["u1"])
    }

    @Test
    fun `写操作以回包为准并找回新建项`() = runTest {
        val s = newStore()
        val created = s.create("工作", listOf("c1"))
        assertNotNull(created)
        assertEquals("工作", created!!.name)
        assertEquals(listOf("c1"), s.state.value.folder(created.id)!!.convIds)
        s.changeConvs(created.id, add = listOf("c2"), remove = listOf("c1"))
        assertEquals(listOf("c2"), s.state.value.folder(created.id)!!.convIds)
        s.rename(created.id, "项目")
        assertEquals("项目", s.state.value.folder(created.id)!!.name)
        s.delete(created.id)
        assertTrue(s.state.value.folders.isEmpty())
    }

    @Test
    fun `排序以回包为准`() = runTest {
        val r = FakeRemote()
        val s = newStore(remote = r)
        val a = s.create("A", emptyList())!!
        val b = s.create("B", emptyList())!!
        s.reorder(listOf(b.id, a.id))
        assertEquals(listOf("B", "A"), s.state.value.folders.map { it.name })
        assertEquals(listOf(0, 1), s.state.value.folders.map { it.position })
    }

    @Test
    fun `refresh 失败保留本地缓存`() = runTest {
        val r = FakeRemote()
        val s = newStore(remote = r)
        s.apply(snap(2, f("f1", "A")))
        r.failNext = java.io.IOException("offline")
        s.refresh()
        assertEquals(listOf("f1"), s.state.value.folders.map { it.id })
    }
}
