package com.libeyond.imandroid.data.db

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `ConversationDao.raiseHead` 的**真实 SQL**（JVM 单测摸不到 Room 生成的查询，`@Query` 改动要在这里走一遍）。
 * 内存库，不碰真机上的登录态与数据。
 *
 * 跑法同 [MigrationTest]：`installDebug installDebugAndroidTest` 后
 * `adb shell am instrument -w -e class com.libeyond.imandroid.data.db.ConversationDaoHeadTest com.libeyond.imandroid.test/androidx.test.runner.AndroidJUnitRunner`。
 */
@RunWith(AndroidJUnit4::class)
class ConversationDaoHeadTest {
    private lateinit var db: IMDatabase

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, IMDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After
    fun close() = db.close()

    @Test
    fun raiseHeadOnlyMovesForward() = runBlocking {
        val dao = db.conversations()
        dao.upsert(ConversationEntity(ownerUid = "me", convId = "g_a", headConvSeq = 100))
        dao.raiseHead("me", "g_a", 250)
        assertEquals(250L, dao.byId("me", "g_a")!!.headConvSeq)
        dao.raiseHead("me", "g_a", 120) // 倒退的值（迟到的旧帧）必须被忽略
        assertEquals(250L, dao.byId("me", "g_a")!!.headConvSeq)
        dao.raiseHead("me", "g_a", 250) // 等值也不动
        assertEquals(250L, dao.byId("me", "g_a")!!.headConvSeq)
    }

    @Test
    fun raiseHeadDoesNotCreateAPlaceholderRow() = runBlocking {
        val dao = db.conversations()
        dao.raiseHead("me", "g_ghost", 99)
        assertNull(dao.byId("me", "g_ghost"))
    }

    @Test
    fun raiseHeadIsScopedToOwner() = runBlocking {
        val dao = db.conversations()
        dao.upsert(ConversationEntity(ownerUid = "me", convId = "g_a"))
        dao.upsert(ConversationEntity(ownerUid = "other", convId = "g_a"))
        dao.raiseHead("me", "g_a", 77)
        assertEquals(77L, dao.byId("me", "g_a")!!.headConvSeq)
        assertEquals(0L, dao.byId("other", "g_a")!!.headConvSeq)
    }
}
