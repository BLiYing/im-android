package com.libeyond.imandroid.sdk

import com.libeyond.imandroid.data.PowerSavingStore
import com.libeyond.imandroid.data.ServerConfigStore
import com.libeyond.imandroid.sdk.api.ServerConfig
import com.libeyond.imandroid.sdk.ws.ConnState
import kotlinx.coroutines.launch

/**
 * 部署级配置（`GET /server-config`）的同步接线，从 [IMClient] 拆出来（那个文件贴着 600 行红线）。
 *
 * PROTOCOL 要求「每次连上（含重连）与回到前台」重拉——后台「系统设置」S2 的撤回 / 编辑窗口、
 * 转发上限、群名 / 公告长度运行中可改，只在登录后拉一次的话端上会一直按旧值显菜单。
 * 节流与失败保留旧值在 [ServerConfigStore] 里。
 *
 * 拉到的 `fcm_enabled` 顺手喂给 [PowerSavingStore]：它本就是运行时值（凭据上传 / 删除即变），
 * 此前只在进主界面与打开省电页时各拉一次。
 */
internal fun IMClient.startServerConfigSync() {
    ServerConfigStore.refresher = { reason -> scope.launch { refreshServerConfig(reason) } }
    scope.launch {
        socket.state.collect { if (it == ConnState.Connected) refreshServerConfig("ws_connected") }
    }
    scope.launch { socket.sessionEnded.collect { ServerConfigStore.forget() } }
}

/**
 * 按节流重拉一次（进主界面 / 连上 / 回前台）。未登录不拉（接口要鉴权）。
 * @return 这次拉到的配置；被节流或失败回 null（store 里仍是上一次的值）。
 */
suspend fun IMClient.refreshServerConfig(reason: String): ServerConfig? {
    if (uid.isNullOrEmpty()) return null
    val cfg = ServerConfigStore.refresh(reason) { conversationsApi.serverConfig() } ?: return null
    PowerSavingStore.setFcmEnabled(cfg.fcmEnabled)
    return cfg
}

/** App 回到前台：挂在 [IMClient.scope] 上，不随 Activity 生命周期取消。 */
fun IMClient.refreshServerConfigOnForeground() {
    scope.launch { refreshServerConfig("foreground") }
}
