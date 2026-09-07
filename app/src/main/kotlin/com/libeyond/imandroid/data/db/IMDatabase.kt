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
    version = 4,
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
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .build().also { instance = it }
        }
    }
}
