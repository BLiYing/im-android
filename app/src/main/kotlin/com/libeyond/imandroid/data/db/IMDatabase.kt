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
    entities = [
        MessageEntity::class, PendingMessageEntity::class, ConversationEntity::class, ConvRangeEntity::class,
        FriendLocalEntity::class, GroupLocalEntity::class,
    ],
    version = 17,
    exportSchema = true,
)
abstract class IMDatabase : RoomDatabase() {
    abstract fun messages(): MessageDao
    abstract fun pending(): PendingMessageDao
    abstract fun conversations(): ConversationDao
    abstract fun ranges(): ConvRangeDao
    abstract fun friendLocal(): FriendLocalDao
    abstract fun groupLocal(): GroupLocalDao

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

        /**
         * v7 → v8：消息与待发行各加 `mentionSpans`（@提及片段，M4-8 / PROTOCOL §4.1）。
         *
         * 老行为 NULL —— 本端接这个字段之前收发的消息都没有，渲染回落"按本群昵称表扫文本"
         * 的老路（普通群里够用；超级群不下发成员表，那里就是不高亮，与协议里写的降级一致）。
         *
         * **两张表都要加**：ack 不回带片段，待发行里没有的话**自己发的 @ 在自己这一侧
         * 不高亮**、对端却一切正常——这一族"只在发送者一侧坏"的坑，本仓已经踩到第六次。
         */
        internal val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE message ADD COLUMN mentionSpans TEXT")
                db.execSQL("ALTER TABLE pending_message ADD COLUMN mentionSpans TEXT")
                // mentions 只在待发行上：正式消息行不需要（收端要的是片段），
                // 而重发要原样重发谁被 @ 了——重名成员没法从片段反推
                db.execSQL("ALTER TABLE pending_message ADD COLUMN mentions TEXT")
            }
        }

        /**
         * v8 → v9：**待发行**加 `waveform`（语音振幅指纹，PROTOCOL §4.1）。
         *
         * 只加待发那张表——`message` 表从 v1 起就有这一列。
         * ack 不回带波形，待发行里没有的话**自己转发出去的语音在自己这一侧是等高条纹**、
         * 对端却正常：又是 [com.libeyond.imandroid.data.AckCarryOver] 表里那一族
         * 「只在发送者一侧坏」的坑，这是第七次。
         */
        internal val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE pending_message ADD COLUMN waveform TEXT")
            }
        }

        /** v2 → v3：消息加 `groupId`（相册宫格，M4+）。老行为 NULL = 不属于任何相册。 */
        internal val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE message ADD COLUMN groupId TEXT")
            }
        }

        /**
         * v9 → v10：会话加 `lastFrom` / `lastFromNickname` / `lastRecalled`
         * （会话列表副标题"谁发的"+撤回态，2026-09-22 用户报；对齐 iOS `lastFrom`/`lastFromNickname`）。
         *
         * 老行为：三列分别是空串/空串/false —— 群聊列表在升级完那一刻仍显示无前缀的老预览，
         * 下一条消息（或下一次拉会话列表）进来就会自然补上，不必回填历史。
         */
        internal val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE conversation ADD COLUMN lastFrom TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE conversation ADD COLUMN lastFromNickname TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE conversation ADD COLUMN lastRecalled INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * v10 → v11：系统消息结构化事件（P3 i18n，PROTOCOL §6.6）。消息加 `sysEvent`/`sysArgs`，
         * 会话加最后一条的 `lastSysEvent`/`lastSysArgs`/`lastSysSegments`（列表预览按当前语言现算）。
         *
         * 老行为 NULL/空串 = 没有结构化事件，回退整句中文——服务端对存量历史消息本来就不回填，
         * 协议里明写接受这个限制。
         */
        internal val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE message ADD COLUMN sysEvent TEXT")
                db.execSQL("ALTER TABLE message ADD COLUMN sysArgs TEXT")
                db.execSQL("ALTER TABLE conversation ADD COLUMN lastSysEvent TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE conversation ADD COLUMN lastSysArgs TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE conversation ADD COLUMN lastSysSegments TEXT NOT NULL DEFAULT ''")
            }
        }

        /**
         * v11 → v12：消息加 `replySnapshotKind`/`replySnapshotArgs`（引用快照结构化，P3 i18n，PROTOCOL §4.3）。
         * 老行为 NULL = 按 `replySnapshot` 原文 + 前缀 token 本地化显示（存量行为不变）。
         */
        internal val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE message ADD COLUMN replySnapshotKind TEXT")
                db.execSQL("ALTER TABLE message ADD COLUMN replySnapshotArgs TEXT")
            }
        }

        /**
         * v12 → v13：会话加 `muteUntil`（定时免打扰到期毫秒，第二批 NOTIFICATIONS_P1_DESIGN §5）。
         * 老行为 NULL DEFAULT 0 = 「永久或未免打扰」——与现状语义一致（`muted` 老行本就是"要么永久
         * 免打扰要么不免打扰"），不用回填，下次拉会话列表 / 收到 conv_update 会带上真实值。
         */
        internal val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE conversation ADD COLUMN muteUntil INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * 13→14：离线积压 C2 收尾（OFFLINE_BACKLOG_DESIGN §4.5）。加 `isSuper`（超级群 `max_gap=0`）与
         * `headConvSeq`（服务端最新位点，只增不减）。老行取 0/false：下次拉会话列表立刻回填真实值，
         * 而 `connect → refreshConversations → requestSync` 的顺序保证首次 sync 之前列表已刷过。
         */
        internal val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE conversation ADD COLUMN isSuper INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE conversation ADD COLUMN headConvSeq INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * 14→15：离线积压 C1 区间清单表 `conv_range_local`（OFFLINE_BACKLOG_DESIGN §4.2）。
         *
         * **回填**：升级前本地是「从头连续拉到游标处」，正好是一段 `[1, syncedConvSeq]`——不回填的话所有老用户
         * 升级后都会被判成「整个会话有缺口」。这与 iOS/Web 不同：它们在**读**时用 `[1,synced]` 兜底（没有任何区间行才生效，
         * 一旦登记了第一个孤岛兜底就失效），Android 一次性**物化**成行，之后只有一种来源。
         * ⚠️ 已知偏差：升级前「清空聊天记录」过的会话，游标保留而消息已没，回填会宣称「齐全」却一条没有——
         * 进会话判据（C3）必须带「清单说齐、手里没东西 → 问服务端」的兜底（Web `planEntryWindow` 同款）。
         */
        internal val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `conv_range_local` (`ownerUid` TEXT NOT NULL, `convId` TEXT NOT NULL, " +
                        "`lo` INTEGER NOT NULL, `hi` INTEGER NOT NULL, PRIMARY KEY(`ownerUid`, `convId`, `lo`))",
                )
                db.execSQL(
                    "INSERT INTO conv_range_local (ownerUid, convId, lo, hi) " +
                        "SELECT ownerUid, convId, 1, syncedConvSeq FROM conversation WHERE syncedConvSeq > 0",
                )
            }
        }

        /**
         * 15→16：本机清空位点 `clearedUpTo`（见 [ConversationEntity.clearedUpTo]）。
         *
         * **回填**：升级前「清空聊天记录」只删消息、游标原样保留，**没有任何痕迹**；而 14→15 回填的区间 `[1, synced]`
         * 会宣称「这段齐全」。不处理的话，C3 的「清单说齐、手里没东西 → 问服务端」兜底会把这些会话清掉的历史又拉回来。
         * 判据：游标以内**本地一条都没有**的那一截就当作用户清掉的——有消息时取（最小本地 seq − 1），一条没有取游标本身。
         * 误伤面：群 `history_visible` 抬高的下界、开头几条是不落库的事件行——那些序号下本来就没有可显示的东西，当下界无害。
         */
        internal val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE conversation ADD COLUMN clearedUpTo INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    "UPDATE conversation SET clearedUpTo = COALESCE(" +
                        "(SELECT MIN(m.convSeq) FROM message m WHERE m.ownerUid = conversation.ownerUid " +
                        "AND m.convId = conversation.convId AND m.convSeq > 0 AND m.convSeq <= conversation.syncedConvSeq) - 1, " +
                        "syncedConvSeq) WHERE syncedConvSeq > 0",
                )
            }
        }

        /**
         * 16→17：好友 / 群列表的本地快照两张新表（断网回退，见 [FriendLocalEntity]）。
         * **DDL 必须与导出的 17.json 逐字一致**（Room 迁移后会拿它校验表结构）。纯新增表，不动老数据。
         */
        internal val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `friend_local` (`ownerUid` TEXT NOT NULL, `userId` TEXT NOT NULL, " +
                        "`sortOrder` INTEGER NOT NULL, `username` TEXT NOT NULL, `nickname` TEXT NOT NULL, " +
                        "`remark` TEXT NOT NULL, `avatarUrl` TEXT NOT NULL, `blocked` INTEGER NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL, PRIMARY KEY(`ownerUid`, `userId`))",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `group_local` (`ownerUid` TEXT NOT NULL, `convId` TEXT NOT NULL, " +
                        "`sortOrder` INTEGER NOT NULL, `name` TEXT NOT NULL, `avatarUrl` TEXT NOT NULL, " +
                        "`owner` TEXT NOT NULL, `ownerNickname` TEXT NOT NULL, `ownerUsername` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, `myRole` TEXT NOT NULL, `memberCount` INTEGER NOT NULL, " +
                        "`isSuper` INTEGER NOT NULL, PRIMARY KEY(`ownerUid`, `convId`))",
                )
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
                .addMigrations(
                    MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5,
                    MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9,
                    MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17,
                )
                .build().also { instance = it }
        }
    }
}
