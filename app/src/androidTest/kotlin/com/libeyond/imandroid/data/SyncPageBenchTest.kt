package com.libeyond.imandroid.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.libeyond.imandroid.data.db.ConversationEntity
import com.libeyond.imandroid.data.db.IMDatabase
import com.libeyond.imandroid.data.db.RoomTx
import com.libeyond.imandroid.sdk.logging.IMLog
import com.libeyond.imandroid.sdk.protocol.MessageData
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 一页 sync 入库的**真机耗时拆分**（压测遗留：C1 之后 `apply_ms` 比 C1 之前高，没定性）。
 * 文件库（不是内存库：内存库没有 fsync，量不出事务提交的真实代价）。结果走 IMLog（tag `IM.Bench`），
 * 跑完 `adb logcat -d -s IM.Bench` 读数。断言只防「慢到离谱」，不当性能门禁。
 */
@RunWith(AndroidJUnit4::class)
class SyncPageBenchTest {
    private lateinit var db: IMDatabase
    private lateinit var repo: MessageRepository
    private val log = IMLog.tag("IM.Bench")
    private val me = "me"

    @Before
    fun setUp() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        ctx.deleteDatabase("bench.db")
        db = Room.databaseBuilder(ctx, IMDatabase::class.java, "bench.db").allowMainThreadQueries().build()
        repo = MessageRepository(db.messages(), db.pending(), db.conversations(), ConvRanges(db.ranges()), RoomTx(db))
        db.conversations().upsert(ConversationEntity(ownerUid = me, convId = "g_b"))
    }

    @After
    fun tearDown() {
        db.close()
        InstrumentationRegistry.getInstrumentation().targetContext.deleteDatabase("bench.db")
    }

    private fun page(conv: String, from: Long, n: Int) = (from until from + n).map {
        MessageData(convId = conv, convSeq = it, from = "peer", content = "消息正文 #$it ".repeat(4), timestamp = 1_700_000_000_000 + it)
    }

    private inline fun ms(block: () -> Unit): Double {
        val t = System.nanoTime(); block(); return (System.nanoTime() - t) / 1e6
    }

    @Test
    fun pageApplyBreakdown() = runBlocking {
        val rows = 200
        val pages = 8
        val full = ArrayList<Double>()
        for (p in 0 until pages) {
            val from = 1L + p * rows
            full += ms { repo.onSyncPage(me, "g_b", page("g_b", from, rows), covered = from + rows - 1) }
        }
        // 对照：同样条数只写消息（没有区间 / 游标）——C1 多出来的开销 = full - onlyMessages
        val only = ArrayList<Double>()
        for (p in 0 until pages) {
            val from = 100_000L + p * rows
            only += ms { db.messages().upsert(page("g_b", from, rows).map { it.toEntity(me) }) }
        }
        log.i("sync_page_bench", "rows" to rows, "full_ms" to full.joinToString { "%.0f".format(it) }, "only_msgs_ms" to only.joinToString { "%.0f".format(it) })
        assertTrue("一页 200 条入库 > 3s，离谱", full.max() < 3000)
    }
}
