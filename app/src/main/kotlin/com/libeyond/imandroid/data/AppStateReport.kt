package com.libeyond.imandroid.data

import com.libeyond.imandroid.sdk.protocol.AppStateData
import com.libeyond.imandroid.sdk.protocol.FrameType
import com.libeyond.imandroid.sdk.protocol.ProtocolJson
import com.libeyond.imandroid.sdk.ws.IMSocketManager

/**
 * 前后台状态上报（app_state，PROTOCOL §6.12，M5 批次 2）——FCM 离线推送要靠它才能只在
 * "真的不在前台"时触达（见 `IMServer/docs/design/PUSH_M5_DESIGN.md` §6 已知限制①：这条没接之前，
 * 服务端恒判该会话前台，FCM 实际只在进程被杀/断连时才会发）。
 *
 * 两个调用点（同 iOS `sceneDidEnterBackground`/`sceneWillEnterForeground` 直接调用同款帧的做法，
 * 不搞额外的响应式订阅）：
 * ① [IMSocketManager.state] 变 `Connected` 时发一次**当前实际状态**（新连接/断线重连都会经过这里）——
 *    **不能偷懒假设新连接一律 foreground**：iOS 当初就是因为"只在 sceneWillEnterForeground 补发"
 *    漏了"后台期间断线重连"这种场景才补的这个教训（`sendAppStateAfterHandshake`），本端直接照做对；
 * ② `MainActivity` 的 `AppActive.current` 赋值点，状态真变化时发一次。
 *
 * 未连接时 `send` 返回 false、帧静默丢弃——协议允许（§6.12："帧丢了只会退化成 60 秒心跳超时前不推，
 * 不会重复推"），且①那条会在下次连上时把当前实际状态补一遍，不需要自己排队重试。
 */
fun IMSocketManager.reportAppState(foreground: Boolean) {
    send(
        FrameType.APP_STATE,
        ProtocolJson.encodeToJsonElement(
            AppStateData.serializer(),
            AppStateData(state = if (foreground) "foreground" else "background"),
        ),
    )
}
