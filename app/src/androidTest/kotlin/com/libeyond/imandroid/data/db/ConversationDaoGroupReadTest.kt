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
 * `ConversationDao.raiseGroupRead` 的**真实 SQL**（群「全员已读」实时帧，GROUP_READ_REALTIME_DESIGN §2.4）。
 *
 * 跑法：`installDebug installDebugAndroidTest` 后
 * `adb shell am instrument -w -e class com.libeyond.imandroid.data.db.ConversationDaoGroupReadTest com.libeyond.imandroid.test/androidx.test.runner.AndroidJUnitRunner`。
 */
@RunWith(AndroidJUnit4::class)
class ConversationDaoGroupReadTest {
    private lateinit var db: IMDatabase

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, IMDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After
    fun close() = db.close()

    @Test
    fun groupReadOnlyMovesForward() = runBlocking {
        val dao = db.conversations()
        dao.upsert(ConversationEntity(ownerUid = "me", convId = "g_a", isGroup = true, peerReadSeq = 10))
        dao.raiseGroupRead("me", "g_a", 30)
        assertEquals(30L, dao.byId("me", "g_a")!!.peerReadSeq)
        dao.raiseGroupRead("me", "g_a", 20) // 迟到的旧值不能把双勾退回去
        assertEquals(30L, dao.byId("me", "g_a")!!.peerReadSeq)
    }

    @Test
    fun groupReadNeverTouchesPrivateChat() = runBlocking {
        // 单聊这一列是对端读位点（receipt 维护），群帧误写会让单聊凭空变双勾
        val dao = db.conversations()
        dao.upsert(ConversationEntity(ownerUid = "me", convId = "u_1_u_2", isGroup = false, peerReadSeq = 5))
        dao.raiseGroupRead("me", "u_1_u_2", 99)
        assertEquals(5L, dao.byId("me", "u_1_u_2")!!.peerReadSeq)
    }

    @Test
    fun groupReadDoesNotCreateRowAndIsOwnerScoped() = runBlocking {
        val dao = db.conversations()
        dao.raiseGroupRead("me", "g_ghost", 9)
        assertNull(dao.byId("me", "g_ghost"))
        dao.upsert(ConversationEntity(ownerUid = "other", convId = "g_a", isGroup = true))
        dao.raiseGroupRead("me", "g_a", 9)
        assertEquals(0L, dao.byId("other", "g_a")!!.peerReadSeq)
    }
}
