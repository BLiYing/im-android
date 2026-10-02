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
 * `MessageDao.firstConvSeqBetween` 的真实 SQL：有缺口又没有服务端日历时，日历跳某天只认**当天**，
 * 不能像 `firstConvSeqAtOrAfter` 那样跳过缺口静默落到别的日子（设计 §4.9 第 4 项）。
 */
@RunWith(AndroidJUnit4::class)
class MessageDayQueryTest {
    private lateinit var db: IMDatabase
    private val day = 86_400_000L * 20_000 // 任取一个整天起点

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, IMDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After
    fun close() = db.close()

    private fun msg(seq: Long, ts: Long, type: String = "text") =
        MessageEntity(ownerUid = "me", convId = "c", convSeq = seq, timestamp = ts, contentType = type)

    @Test
    fun onlyReturnsAMessageInsideThatDay() = runBlocking {
        val dao = db.messages()
        dao.upsert(listOf(msg(10, day + 1_000), msg(11, day + 5_000), msg(50, day + 3 * 86_400_000L)))
        assertEquals(10L, dao.firstConvSeqBetween("me", "c", day, day + 86_400_000L))
        // 当天本地没有（缺口里）：at-or-after 会落到第 3 天的 50，当天版必须回 null
        assertEquals(50L, dao.firstConvSeqAtOrAfter("me", "c", day + 86_400_000L))
        assertNull(dao.firstConvSeqBetween("me", "c", day + 86_400_000L, day + 2 * 86_400_000L))
    }

    @Test
    fun upperBoundIsExclusiveAndSystemRowsAreIgnored() = runBlocking {
        val dao = db.messages()
        dao.upsert(listOf(msg(1, day + 86_400_000L), msg(2, day + 10, "system")))
        assertNull(dao.firstConvSeqBetween("me", "c", day, day + 86_400_000L))
    }
}
