package com.libeyond.imandroid.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 本地库（对应 iOS 的 SQLite/FMDB、Web 的 IndexedDB）。
 *
 * **单库多账号**：所有表的主键都带 `ownerUid`，切账号不换库、只换查询条件。
 * 这样切回旧账号时数据还在（iOS/Web 同构）。
 */
@Database(
    entities = [MessageEntity::class, PendingMessageEntity::class, ConversationEntity::class],
    version = 7,
    exportSchema = true,
)
abstract class IMDatabase : RoomDatabase() {
    abstract fun messages(): MessageDao
    abstract fun pending(): PendingMessageDao
    abstract fun conversations(): ConversationDao

    companion object {
        @Volatile private var instance: IMDatabase? = null

        /**
         * v1 → v2：消息与待发消息各加一列 `forwardFrom`（转发溯源，M4-3）。
         *
         * **这是本仓第一条迁移，形状照抄给后面的人**：加列一律 `ALTER TABLE … ADD COLUMN`
         * 且允许 NULL；不写 `fallbackToDestructiveMigration`（那会在版本一变时删库重建，
         * 用户的本地消息全没）。老行的 forwardFrom 为 NULL = 「不是转发」，语义正确，无需回填。
         */
        internal val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE message ADD COLUMN forwardFrom TEXT")
                db.execSQL("ALTER TABLE pending_message ADD COLUMN forwardFrom TEXT")
            }
        }

        /** v3 → v4：**待发**消息也加 `groupId`——选完要立刻成宫格，不等 ack。 */
        internal val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE pending_message ADD COLUMN groupId TEXT")
            }
        }

/**
 * v4 → v5：媒体元数据落库。
 *
 * ack **不回带** media_w/media_h/duration/poster，所以待发行必须自己存一份，
 * 收到 ack 时从那里取——与 forwardFrom（v1→v2）、groupId（v2→v3、v3→v4）同一个坑，
 * 这是第三次。不存的结果是视频在**自己这一侧**没封面、没时长、气泡比例也不对，
 * 而对端一切正常，所以自查时很难发现。
 */
internal val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE message ADD COLUMN poster TEXT")
        db.execSQL("ALTER TABLE pending_message ADD COLUMN mediaW INTEGER")
        db.execSQL("ALTER TABLE pending_message ADD COLUMN mediaH INTEGER")
        db.execSQL("ALTER TABLE pending_message ADD COLUMN duration INTEGER")
        db.execSQL("ALTER TABLE pending_message ADD COLUMN poster TEXT")
    }
}

/**
 * v5 → v6：消息加 `sysSegments`（系统消息分段，PROTOCOL §6）。
 *
 * 老行为 NULL —— **历史系统消息本来就没有分段**（服务端 2026-08-29 才加这一列），
 * 收端回退按 `content` 整句渲染：名字仍是当时的昵称、且不可点。协议里明写"不做回溯"。
 */
internal val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE message ADD COLUMN sysSegments TEXT")
    }
}

        /**
         * v6 → v7：消息与待发行各加 `thumb`（极小模糊缩略，M4-7）。
         *
         * 老行为 NULL —— 本端接这个字段之前收发的消息都没有，占位回退中性底。
         * **两张表都要加**：ack 不回带 thumb，待发行里没有的话自己发的图在
         * 自己这一侧就没有占位（"只在发送者一侧坏"那一族，见 AckCarryOver）。
         */
        internal val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE message ADD COLUMN thumb TEXT")
                db.execSQL("ALTER TABLE pending_message ADD COLUMN thumb TEXT")
            }
        }

        /** v2 → v3：消息加 `groupId`（相册宫格，M4+）。老行为 NULL = 不属于任何相册。 */
        internal val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE message ADD COLUMN groupId TEXT")
            }
        }

        fun get(context: Context): IMDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                IMDatabase::class.java,
                "im.db",
            )
                // 刻意**不加** fallbackToDestructiveMigration：那会在版本号一变时
                // 直接删库重建，用户的本地消息全没。加列要写真的 Migration。
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
                .build().also { instance = it }
        }
    }
}
