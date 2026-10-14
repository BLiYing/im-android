package com.libeyond.imandroid.data

import android.content.Context
import com.libeyond.imandroid.sdk.api.ChatFoldersRemote
import com.libeyond.imandroid.sdk.logging.IMLog
import com.libeyond.imandroid.sdk.protocol.FolderUpdateData
import com.libeyond.imandroid.sdk.protocol.FolderWire
import com.libeyond.imandroid.sdk.protocol.ProtocolJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/** 落盘（冷启动先画 Tab 条，随后被 GET 覆盖）。按账号隔离。 */
interface FolderPersistence {
    fun load(owner: String): FolderSnapshot?
    fun save(owner: String, snapshot: FolderSnapshot)
    fun clear(owner: String)
}

/** SharedPreferences 实现：内容即 folder_update 同形 JSON。读写失败一律吞（缓存而已，不该拦住功能）。 */
class PrefsFolderPersistence(context: Context) : FolderPersistence {
    private val prefs = context.applicationContext.getSharedPreferences("im_chat_folders", Context.MODE_PRIVATE)

    private fun key(owner: String) = "folders.$owner"

    override fun load(owner: String): FolderSnapshot? {
        if (owner.isEmpty()) return null
        val raw = runCatching { prefs.getString(key(owner), null) }.getOrNull() ?: return null
        return runCatching { ProtocolJson.decodeFromString(FolderUpdateData.serializer(), raw).toSnapshot() }.getOrNull()
    }

    override fun save(owner: String, snapshot: FolderSnapshot) {
        if (owner.isEmpty()) return
        val data = FolderUpdateData(
            version = snapshot.version,
            folders = snapshot.folders.map { FolderWire(it.id, it.name, it.position, it.convIds) },
        )
        runCatching { prefs.edit().putString(key(owner), ProtocolJson.encodeToString(FolderUpdateData.serializer(), data)).apply() }
    }

    override fun clear(owner: String) {
        if (owner.isEmpty()) return
        runCatching { prefs.edit().remove(key(owner)).apply() }
    }
}

/**
 * 会话文件夹的客户端状态（**进程内一份、按账号隔离**，对齐 iOS `IMChatFolderStore`）。
 *
 * - 整表快照 + 单调版本：REST 写入回包与 `folder_update` 帧都走 [apply]，**版本 ≤ 本地的丢弃**
 *   （同版本的帧 / 乱序的旧帧 / 自己写入回包之后才到的同一版帧）。
 * - 写操作服务端权威：成功后用回包整表替换，不做本地乐观写；失败原样抛给调用方（业务码保留）。
 * - 换账号：[adopt] 清内存并读该账号落盘；退出登录：[forget]。
 */
class ChatFolderStore(
    private val remote: ChatFoldersRemote,
    private val persistence: FolderPersistence,
    private val ownerProvider: () -> String?,
) {
    private val log = IMLog.tag("IM.Folder")
    private val _state = MutableStateFlow(FolderSnapshot())
    val state: StateFlow<FolderSnapshot> = _state.asStateFlow()

    @Volatile
    private var owner: String? = null

    /** 切到账号 [uid]（幂等）：与当前不同则清内存、读该账号落盘。 */
    @Synchronized
    fun adopt(uid: String?) {
        if (uid.isNullOrEmpty() || uid == owner) return
        owner = uid
        _state.value = persistence.load(uid) ?: FolderSnapshot()
    }

    /** 退出登录 / 被踢：清内存（落盘按账号保留，下次同账号登录先画缓存）。 */
    @Synchronized
    fun forget() {
        owner = null
        _state.value = FolderSnapshot()
    }

    /**
     * 应用一份整表快照；返回是否真的替换了。上限（limits）随任何回包更新，即使版本没推进
     * （GET 回 version 0 的空表也带着 limits）。
     */
    @Synchronized
    fun apply(snapshot: FolderSnapshot, fromFrame: Boolean = false): Boolean {
        adopt(ownerProvider())
        val cur = _state.value
        // 帧不带 limits：沿用本地已知的上限，别被默认值冲掉
        val next = if (fromFrame) snapshot.copy(limits = cur.limits) else snapshot
        if (next.limits != cur.limits) _state.value = cur.copy(limits = next.limits)
        if (next.version <= cur.version) return false
        _state.value = next
        owner?.let { persistence.save(it, next) }
        return true
    }

    /** `GET /folders` 整表拉取（连上 / 进页时）。失败保留本地缓存。 */
    suspend fun refresh() {
        adopt(ownerProvider())
        val askedFor = owner // 回包时已换号 / 退登则丢弃：别把上一个账号的整表写进新账号
        try {
            val snap = remote.list()
            if (owner != askedFor) return
            apply(snap)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w("folders_fetch_failed", "error" to e.javaClass.simpleName)
        }
    }

    /** 新建（可带初始会话）。返回新建的文件夹（回包里按 client_id 派生的 id 找回）。 */
    suspend fun create(name: String, convIds: List<String>): ChatFolder? {
        val clientId = UUID.randomUUID().toString().replace("-", "")
        val snap = remote.create(name, convIds, clientId)
        apply(snap)
        val state = _state.value
        return state.folder("f_$clientId") ?: state.folders.firstOrNull { it.name.equals(name, ignoreCase = true) }
    }

    suspend fun rename(folderId: String, name: String) { apply(remote.rename(folderId, name)) }

    suspend fun delete(folderId: String) { apply(remote.delete(folderId)) }

    suspend fun reorder(folderIds: List<String>) { apply(remote.reorder(folderIds)) }

    suspend fun changeConvs(folderId: String, add: List<String>, remove: List<String>) {
        apply(remote.changeConvs(folderId, add, remove))
    }
}
