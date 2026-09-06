package com.libeyond.imandroid.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * 本地库（对应 iOS 的 SQLite/FMDB、Web 的 IndexedDB）。
 *
 * **单库多账号**：所有表的主键都带 `ownerUid`，切账号不换库、只换查询条件。
 * 这样切回旧账号时数据还在（iOS/Web 同构）。
 */
@Database(
    entities = [MessageEntity::class, PendingMessageEntity::class, ConversationEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class IMDatabase : RoomDatabase() {
    abstract fun messages(): MessageDao
    abstract fun pending(): PendingMessageDao
    abstract fun conversations(): ConversationDao

    companion object {
        @Volatile private var instance: IMDatabase? = null

        fun get(context: Context): IMDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                IMDatabase::class.java,
                "im.db",
            )
                // 刻意**不加** fallbackToDestructiveMigration：那会在版本号一变时
                // 直接删库重建，用户的本地消息全没。加列要写真的 Migration。
                .build().also { instance = it }
        }
    }
}
