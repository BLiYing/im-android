package com.libeyond.imandroid.data.db

import androidx.room.withTransaction

/**
 * 跨 DAO 的事务入口。Repository 只依赖这个接口：JVM 单测里换成直通实现（没有 Room），
 * 真实事务语义（回滚）由 instrumented 测试验证。
 */
interface DbTx {
    suspend fun <R> run(block: suspend () -> R): R
}

internal class RoomTx(private val db: IMDatabase) : DbTx {
    override suspend fun <R> run(block: suspend () -> R): R = db.withTransaction(block)
}
