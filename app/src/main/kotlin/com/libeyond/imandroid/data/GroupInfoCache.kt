package com.libeyond.imandroid.data

import com.libeyond.imandroid.sdk.api.GroupInfo
import com.libeyond.imandroid.sdk.logging.IMLog
import com.libeyond.imandroid.sdk.protocol.ProtocolJson
import java.io.File

/**
 * 群资料（`GET /groups/{id}`）的本地快照：让群资料页进页就能画整页，网络回来再静默刷新（对齐 iOS 先用本地数据画）。
 *
 * - 近 2000 人的非超级群，`GET /groups/{id}` 要回整张成员表，慢；没有快照时进页只能先画占位。
 * - **只存群资料本身，成员表剔掉**：成员列表走 `GroupMembersState`（分页），页面不读 `info.members`；
 *   留着会把一个群的快照撑到 MB 级。
 * - 按「登录账号 + 群」分文件，换号不串。读写任何失败都当没有快照（返回 null / 吞掉），不影响正常拉取。
 * - 快照可能过期（别人刚改了群）：只当首屏种子，真资料回来就覆盖，权限类入口以后者为准。
 */
class GroupInfoCache(private val dir: File) {
    private val log = IMLog.tag("IM.Group")

    fun load(owner: String, convId: String): GroupInfo? = runCatching {
        val f = file(owner, convId)
        if (!f.isFile) null else decode(f.readText())
    }.getOrNull()

    fun save(owner: String, info: GroupInfo) {
        runCatching {
            dir.mkdirs()
            val tmp = File(dir, "${name(owner, info.convId)}.tmp")
            tmp.writeText(encode(info))
            // 先写临时文件再改名：写到一半被杀进程不会留下半截 JSON
            if (!tmp.renameTo(file(owner, info.convId))) tmp.delete()
        }.onFailure { log.w("group_info_cache_save_failed") }
    }

    private fun file(owner: String, convId: String) = File(dir, name(owner, convId))

    private fun name(owner: String, convId: String) = "${safe(owner)}_${safe(convId)}.json"

    companion object {
        private fun safe(s: String) = s.replace(Regex("[^A-Za-z0-9_-]"), "_")

        fun encode(info: GroupInfo): String =
            ProtocolJson.encodeToString(GroupInfo.serializer(), info.copy(members = emptyList()))

        fun decode(json: String): GroupInfo? =
            runCatching { ProtocolJson.decodeFromString(GroupInfo.serializer(), json) }.getOrNull()
                ?.takeIf { it.convId.isNotBlank() }
    }
}
