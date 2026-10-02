package com.libeyond.imandroid.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Room 迁移测试：旧版本库里的行迁移后**原样还在**，新列取默认值，且迁移后的表结构与导出的
 * schema JSON 一致（`runMigrationsAndValidate` 的 validate 那一半）。
 *
 * 真机/模拟器跑：`./gradlew installDebug installDebugAndroidTest` 后
 * `adb shell am instrument -w -e class com.libeyond.imandroid.data.db.MigrationTest com.libeyond.imandroid.test/androidx.test.runner.AndroidJUnitRunner`
 * ——**别用 `connectedDebugAndroidTest`**，它跑完会卸载 App、清掉真机登录态。
 * 以后每加一个 `MIGRATION_x_y` 就在这里补一条同样的用例。
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), IMDatabase::class.java)

    /** 12→13 加 `muteUntil`（定时免打扰）：老的「永久免打扰」行迁移后仍是永久（muteUntil=0），别的列不动。 */
    @Test
    fun migrate12To13KeepsMutedRowsPermanent() {
        helper.createDatabase(DB, 12).use { db ->
            db.execSQL(
                """INSERT INTO conversation (ownerUid, convId, isGroup, peerUid, title, avatarUrl, peerRemark,
                   lastContent, lastContentType, lastTimestamp, lastConvSeq, lastFrom, lastFromNickname, lastRecalled,
                   lastSysEvent, lastSysArgs, lastSysSegments, unread, mentionUnread, readSeq, peerReadSeq,
                   syncedConvSeq, pinnedAt, muted, markedUnread)
                   VALUES ('me', 'u_a_u_b', 0, 'b', 'B', '', '', 'hi', 'text', 100, 7, 'b', '', 0,
                   '', '', '', 3, 0, 5, 5, 7, 5000, 1, 0)""",
            )
        }
        helper.runMigrationsAndValidate(DB, 13, true, IMDatabase.MIGRATION_12_13).use { db ->
            db.query("SELECT muted, muteUntil, pinnedAt, unread, lastContent FROM conversation WHERE convId = 'u_a_u_b'").use { c ->
                assertEquals(1, c.count)
                c.moveToFirst()
                assertEquals(1, c.getInt(0))
                assertEquals(0L, c.getLong(1))
                assertEquals(5000L, c.getLong(2))
                assertEquals(3, c.getInt(3))
                assertEquals("hi", c.getString(4))
            }
        }
    }

    /** 13→14 加 `isSuper` / `headConvSeq`：老行原样还在，新列取默认（普通会话、head 未知）。 */
    @Test
    fun migrate13To14AddsBacklogColumnsWithDefaults() {
        helper.createDatabase(DB, 13).use { db ->
            db.execSQL(
                """INSERT INTO conversation (ownerUid, convId, isGroup, peerUid, title, avatarUrl, peerRemark,
                   lastContent, lastContentType, lastTimestamp, lastConvSeq, lastFrom, lastFromNickname, lastRecalled,
                   lastSysEvent, lastSysArgs, lastSysSegments, unread, mentionUnread, readSeq, peerReadSeq,
                   syncedConvSeq, pinnedAt, muted, muteUntil, markedUnread)
                   VALUES ('me', 'g_x', 1, '', 'G', '', '', 'hi', 'text', 100, 7, 'b', '', 0,
                   '', '', '', 3, 0, 5, 0, 7, 0, 0, 0, 0)""",
            )
        }
        helper.runMigrationsAndValidate(DB, 14, true, IMDatabase.MIGRATION_13_14).use { db ->
            db.query("SELECT isSuper, headConvSeq, syncedConvSeq, lastContent FROM conversation WHERE convId = 'g_x'").use { c ->
                assertEquals(1, c.count)
                c.moveToFirst()
                assertEquals(0, c.getInt(0))
                assertEquals(0L, c.getLong(1))
                assertEquals(7L, c.getLong(2))
                assertEquals("hi", c.getString(3))
            }
        }
    }

    /** 14→15 建 `conv_range_local` 并回填：升级前「从头连续拉到游标处」就是一段 `[1, synced]`。 */
    @Test
    fun migrate14To15BackfillsPrefixRangeFromCursor() {
        helper.createDatabase(DB, 14).use { db ->
            fun conv(id: String, synced: Int) = db.execSQL(
                """INSERT INTO conversation (ownerUid, convId, isGroup, peerUid, title, avatarUrl, peerRemark,
                   lastContent, lastContentType, lastTimestamp, lastConvSeq, lastFrom, lastFromNickname, lastRecalled,
                   lastSysEvent, lastSysArgs, lastSysSegments, unread, mentionUnread, readSeq, peerReadSeq,
                   syncedConvSeq, pinnedAt, muted, muteUntil, markedUnread, isSuper, headConvSeq)
                   VALUES ('me', '$id', 1, '', 'G', '', '', '', 'text', 0, 0, '', '', 0,
                   '', '', '', 0, 0, 0, 0, $synced, 0, 0, 0, 0, 0, 0)""",
            )
            conv("g_synced", 7)
            conv("g_fresh", 0)
        }
        helper.runMigrationsAndValidate(DB, 15, true, IMDatabase.MIGRATION_14_15).use { db ->
            db.query("SELECT convId, lo, hi FROM conv_range_local ORDER BY convId").use { c ->
                assertEquals(1, c.count) // 游标为 0 的会话不回填
                c.moveToFirst()
                assertEquals("g_synced", c.getString(0))
                assertEquals(1L, c.getLong(1))
                assertEquals(7L, c.getLong(2))
            }
        }
    }

    /** 15→16 回填 `clearedUpTo`：游标以内本地没有的那一截，当作用户清掉的。 */
    @Test
    fun migrate15To16DerivesClearedFloorFromLocalRows() {
        helper.createDatabase(DB, 15).use { db ->
            fun conv(id: String, synced: Int) = db.execSQL(
                """INSERT INTO conversation (ownerUid, convId, isGroup, peerUid, title, avatarUrl, peerRemark,
                   lastContent, lastContentType, lastTimestamp, lastConvSeq, lastFrom, lastFromNickname, lastRecalled,
                   lastSysEvent, lastSysArgs, lastSysSegments, unread, mentionUnread, readSeq, peerReadSeq,
                   syncedConvSeq, pinnedAt, muted, muteUntil, markedUnread, isSuper, headConvSeq)
                   VALUES ('me', '$id', 1, '', 'G', '', '', '', 'text', 0, 0, '', '', 0,
                   '', '', '', 0, 0, 0, 0, $synced, 0, 0, 0, 0, 0, 0)""",
            )
            fun msg(id: String, seq: Int) = db.execSQL(
                """INSERT INTO message (ownerUid, convId, convSeq, serverMsgId, clientMsgId, sender, contentType, content, timestamp)
                   VALUES ('me', '$id', $seq, 's$seq', 'c$seq', 'peer', 'text', 'x', $seq)""",
            )
            conv("g_full", 9); (1..9).forEach { msg("g_full", it) }          // 从 1 起齐全：位点 0
            conv("g_partial", 9); (5..9).forEach { msg("g_partial", it) }    // 清过后又收了 5..9：位点 4
            conv("g_cleared", 7)                                            // 游标 7 而一条没有：位点 7
            conv("g_fresh", 0)                                              // 游标 0：位点 0
            conv("g_island", 500); msg("g_island", 900)                     // ≤ 游标的一条没有（900 是孤岛）：位点 500
        }
        helper.runMigrationsAndValidate(DB, 16, true, IMDatabase.MIGRATION_15_16).use { db ->
            db.query("SELECT convId, clearedUpTo FROM conversation ORDER BY convId").use { c ->
                val got = buildMap { while (c.moveToNext()) put(c.getString(0), c.getLong(1)) }
                assertEquals(0L, got["g_full"])
                assertEquals(4L, got["g_partial"])
                assertEquals(7L, got["g_cleared"])
                assertEquals(0L, got["g_fresh"])
                assertEquals(500L, got["g_island"])
            }
        }
    }

    private companion object {
        const val DB = "migration-test.db"
    }
}
