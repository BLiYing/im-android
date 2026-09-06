# Current Task — im-android（Kotlin + Compose 客户端）

> **活快照**：只记当前状态，**就地覆盖、不追加**。逐功能×端状态以
> `../IMServer/docs/CLIENT_PARITY.md` 的 Android 列为唯一来源；历史流水见 `git log`
> 与 `current_task.archive.md`（尚未创建，第一次裁剪本文件前先建）。
> 工程规范见 `CLAUDE.md` 与 `CODING_STYLE.md`。

## 当前焦点

> **P1~P8 已落地并实测（2026-09-07 一夜）**。空仓 → 能登录、能收发、能看会话列表、能加好友的
> Kotlin + Compose 客户端。每个 P 一个 commit，全部已 push。
>
> | 阶段 | 内容 | 实测 |
> |---|---|---|
> | P1 | HTTP 层：业务码保真、Request ID、会话生命周期（探活→续期→重登） | Request ID 与后端日志对上账 |
> | P2 | WebSocket：Bearer 握手、25s 心跳、退避重连、探活看门狗、401/403 停重连 | 唤醒重连不再被看门狗掐断 |
> | P3 | 登录闭环 + lucide 图标 | 真账号登录 → WS 连上 |
> | P4 | Room 本地库 + 协议 DTO + 同步游标判据 | — |
> | P5 | 收发链路：先落库再发帧、幂等重发、帧分派、同步编排 | — |
> | P6 | 会话列表 + 聊天页 | 22 条真实会话、发送 seq=130013 落库 |
> | P7 | presence 租约 / typing / 已读双勾 / ↓N | 副标题显示「在线」 |
> | P8 | 通讯录 / 好友 / 找人 + 底部 Tab | 好友列表与 @句柄正确 |
>
> **抽成纯函数 + 单测的判据（74 例，每条都做过变异验证）**：
> `RestoreDecision`（会话恢复三判据）· `wakeActionFor`/`handshakeFailureFor`（唤醒与握手）·
> `SyncCursorRule`（游标只认 covered_conv_seq）· `ChatEntry`（进会话定位只认真实未读数）·
> `Presence`（租约到期不得显示在线）· `ReadCursor`（已读位点单调）· `ConvId`（字典序推导）。
>
> **实测抓出、文档里查不到的两条**：
> ① **Go 的 nil slice marshal 成 `null` 不是 `[]`** —— 没有新消息的会话下发 `"messages": null`，
>    kotlinx 对非空 List 收到 null 直接抛，**整帧 sync_resp 报废**，界面表现为
>    「会话列表有，点进去一条消息都没有」。修法是 `coerceInputValues = true`。
> ② **openSocket 只挡 Connecting 没挡 Connected** —— NetworkMonitor 启动瞬间已连上，
>    restore 成功后又 connect 一次，旧连接被字段覆盖孤儿化，同一设备挂两条连接。
>
> 自审两轮共修 11 条（详见 `git log` 的两条 fix commit）。

## 已知坑 / 限制

- **骨架期，没有任何业务功能**：当前 `MainActivity` 只是令牌验证页，不能登录、不能收发。
- **没跑过模拟器**：本轮只到 `assembleDebug` + JVM 单测绿，**APK 没在设备上装起来看过**。
  Compose 的布局/滚动/输入法遮挡这类**单测测不到**，接 UI 时必须跑模拟器。
- **本机 `JAVA_HOME` 是坏的**：被设成 Homebrew 未替换的占位符 `@@HOMEBREW_JAVA@@`，
  裸跑 `gradle` 报 "invalid directory"。`scripts/test.sh` 已自愈；
  手拼 `./gradlew` 要自己 `export JAVA_HOME=$(/usr/libexec/java_home -v 17)`。
- **`values-night` 只管启动窗口**，跟随的是**系统**深色。用户在应用内选浅色时，
  启动那一瞬间仍是深色底 → 闪一下。接了应用内主题偏好后要一并解决（`docs/UI_COLOR.md` §4.1）。
- **明文 HTTP 只对 `10.0.2.2`/`localhost`/`127.0.0.1` 放行**（`res/xml/network_security_config.xml`）。
  真机连开发机要把内网 IP 加进去，**且别留到生产**。没有用 `usesCleartextTraffic="true"`
  全局开口子——那会让 release 包也能明文出网。
- **启动图标是占位**（绿底白气泡矢量图），待 UI 定稿替换。
- **`configuration-cache` 已开**：改 Gradle 脚本后若报缓存相关的怪错，
  `./gradlew --no-configuration-cache <task>` 排一下。
- **没有 instrumented 测试**：`androidTest` 依赖已配好但一个用例都没写。

## 关联工程 / 常用命令

- 后端 `../IMServer`；iOS `../IMProgram`；Web `../im-web`。
- **参考实现**：功能对齐看 iOS 与 Web 的现成实现——**判据类的纯函数尤其可以直接照搬**
  （Web 的 `entryWindow.ts` / `unreadBelow.ts` / `listSearch.ts` 这类），
  但要守 `../IMServer/docs/SYMMETRY.md` 那条：**对称的是不变式，不是代码形状**。
- 本仓：
  ```bash
  ./scripts/test.sh                # 唯一测试入口（门禁 + 编译 + 单测）
  BUILD_ONLY=1 ./scripts/test.sh   # 只编译
  ONLY=EnvelopeTest ./scripts/test.sh
  ./scripts/install-hooks.sh       # 每个 clone 一次
  ```
- 后端（改了后端代码**必须重启**）：`cd ../IMServer && ./scripts/dev.sh --no-tail`
- 模拟器连宿主机后端：`10.0.2.2:8080`（`127.0.0.1` 在模拟器里指模拟器自己）。
- 本地测试账号：`user1001` / `user1002` / `e2etest1`，密码统一 `123456`。
