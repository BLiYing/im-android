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

    private companion object {
        const val DB = "migration-test.db"
    }
}
