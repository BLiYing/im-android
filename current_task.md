# Current Task — im-android（Kotlin + Compose 客户端）

> **活快照**：只记当前状态，**就地覆盖、不追加**。逐功能×端状态以
> `../IMServer/docs/CLIENT_PARITY.md` 的 Android 列为唯一来源；历史流水见 `git log`
> 与 `current_task.archive.md`（尚未创建，第一次裁剪本文件前先建）。
> 工程规范见 `CLAUDE.md` 与 `CODING_STYLE.md`。

## 当前焦点

> **P1~P13 一夜落地并逐项实测（2026-09-07）**。空仓 → 一个能登录、能收发文字与图片、
> 有会话列表/通讯录/群资料/资料页、能撤回引用置顶的客户端。
> **19 个 commit 全部已 push；95 例单测**，每条判据都做过变异验证。

| P | 内容 | 实测证据 |
|---|---|---|
| P1 | HTTP 层：业务码保真、Request ID、会话生命周期 | Request ID 在后端日志三条记录里对上账 |
| P2 | WebSocket：Bearer 握手、25s 心跳、退避、探活看门狗、401/403 停重连 | 唤醒重连不再被看门狗掐断 |
| P3 | 登录闭环 + lucide 图标 | user1001 真登录 → WS 连上 |
| P4 | Room 本地库 + 协议 DTO + 同步游标判据 | — |
| P5 | 收发链路：先落库再发帧、幂等重发、帧分派、同步编排 | — |
| P6 | 会话列表 + 聊天页 | 22 条真实会话；发送 seq=130013 |
| P7 | presence 租约 / typing / 已读双勾 / ↓N | 副标题「在线」；旧消息绿✓✓、新的灰✓ |
| P8 | 通讯录 / 好友 / 找人 + 底部 Tab | 真实好友列表与 @句柄 |
| P9 | 消息与会话长按菜单、撤回、引用、置顶/免打扰/删除 | 超 2min 的消息**正确地没有「撤回」** |
| P10 | 媒体气泡 + **渲染窗口有界化** | 13 万条的会话从白屏修到正常渲染 |
| P11 | 发图片（Photo Picker + 上传） | upload_ok 7858B → seq=130014 |
| P12 | 群资料 / 成员分页 / 角色徽标 / 退群 | 2 万人大群正确识别为超级群 |
| P13 | 用户资料页 + 建群 | 建群页群名 + 好友勾选正常 |

**抽成纯函数 + 变异验证过的判据**（这些是本仓的价值密集区，改它们要谨慎）：
`RestoreDecision`（会话恢复三判据）· `wakeActionFor`/`handshakeFailureFor` ·
`SyncCursorRule`（游标只认 `covered_conv_seq`）· `ChatEntry`（进会话定位只认真实未读数）·
`Presence`（租约过期不得显示在线）· `ReadCursor`（已读位点单调）· `ConvId`（字典序推导）·
`MessageActions`（长按矩阵）· `MediaUrl` · `isLocalUri`。

**实测抓出、文档里查不到的四条**（都已修 + 配回归测试）：
1. **Go 的 nil slice marshal 成 `null` 不是 `[]`** —— `"messages": null` 让**整帧 sync_resp 报废**，
   界面表现为「会话列表有，点进去一条消息都没有」。修法 `coerceInputValues = true`。
2. **`openSocket` 只挡 Connecting 没挡 Connected** —— 同一设备孤儿化出两条连接。
3. **`observeAll` 无界查询** —— 13 万条的会话把聊天页渲染成空白（iOS 文档里记的
   「几十万行全构造成对象」，本端一样躲不过）。
4. **上传中被杀进程 → 本地 `content://` uri 被当消息正文发出去**，收件人拿到打不开的地址。

自审四轮共修 **18 条**，见 `git log` 里的四条 `fix(android)` commit。

## 下一步

按 `../IMServer/docs/CLIENT_PARITY.md` 追 iOS，优先级从高到低：

1. **转发 / 多选 / 收藏**（M4-3、M4-4）：长按菜单现在缺这三项，是 iOS 用得最多的一批。
2. **消息编辑**（M4-5）：`msg_op op=edit` 协议侧已接，缺 UI。
3. **会话详情页**（媒体/文件/链接归档，M4.5-3）。
4. **设置页逐项**（外观/字号/主题偏好）：`IMAppearance` 令牌层已就位，缺持久化与界面；
   同时解掉 `values-night` 只跟系统深色导致的启动闪烁（见已知坑）。
5. **群管理写操作**（改群名/公告/禁言/踢人/转让）：`GroupApi` 有骨架，缺写接口与 UI。
6. **@提及**（`mention_spans`，M4-8）：协议字段已在 DTO 里，缺高亮与 @面板。
7. **扫码登录 / 二维码**（QR P1）：需相机权限与 ML Kit，工作量单独一档。
8. **推送**（M5）：依赖 FCM，iOS 侧也还没做。

## 已知坑 / 限制

- **`CLIENT_PARITY.md` 的 Android 列还全是 ⬜**：用户明确要求本轮只改 im-android，
  故没动 IMServer 仓的状态矩阵。**下次进 IMServer 时要补**，否则那张表会一直误导人。
- **没有 instrumented 测试**：`androidTest` 依赖配好了但一个用例没写。
  Compose 的布局/滚动/输入法这类只能靠模拟器手测，目前全靠人肉截图核对。
- **媒体只做了图片**：视频/语音/文件只能**看**不能**发**，也不能播放/下载。
  语音气泡的波形是等高条纹占位（`waveform` 字段还没解析）。
- **图片不压缩**：只在端上挡 20MB，超了直接不发。iOS/Web 都会先压。
- **消息编辑、转发、多选、收藏、@提及、扫码全未做**。
- **群管理只读**：能看群资料与成员、能退群，不能改群名/公告/禁言/踢人/转让。
- **设置页只有退出登录**：主题/字号/壁纸等外观偏好的令牌层在（`IMAppearance`），
  但没有持久化也没有界面。
- **`values-night` 只管启动窗口且跟随系统深色**：用户在应用内选浅色时，
  启动那一瞬间仍是深色底 → 闪一下。接主题偏好时一并解决。
- **明文 HTTP 只对 `10.0.2.2`/`localhost`/`127.0.0.1` 放行**。
  真机连开发机要把内网 IP 加进 `res/xml/network_security_config.xml`，**且别留到生产**。
- **本机 `JAVA_HOME` 是坏的**（Homebrew 未替换占位符 `@@HOMEBREW_JAVA@@`）。
  `scripts/test.sh` 已自愈；手拼 `./gradlew` 要自己
  `export JAVA_HOME=$(/usr/libexec/java_home -v 17)`。
- **启动图标是占位**（绿底白气泡矢量图）。
- **`SessionStore` 用普通 SharedPreferences**：应用私有目录，未 root 读不到，
  但**没有额外加密**（`androidx.security:security-crypto` 已废弃）。要再加一层得自己包 Keystore。

## 关联工程 / 常用命令

- 后端 `../IMServer`；iOS `../IMProgram`；Web `../im-web`。
- **参考实现**：判据类的纯函数可直接照搬 Web（`entryWindow.ts`/`unreadBelow.ts`/`wake.ts`），
  但要守 `../IMServer/docs/SYMMETRY.md` 那条：**对称的是不变式，不是代码形状**。
- 本仓：
  ```bash
  ./scripts/test.sh                # 唯一测试入口（门禁 + 编译 + 单测）
  BUILD_ONLY=1 ./scripts/test.sh   # 只编译
  ONLY=EnvelopeTest ./scripts/test.sh
  ./scripts/install-hooks.sh       # 每个 clone 一次
  ```
- 后端（改了后端代码**必须重启**）：`cd ../IMServer && ./scripts/dev.sh --no-tail`
- 模拟器：AVD 名 `im_test`（API 36 / pixel_5）。启动：
  ```bash
  ~/Library/Android/sdk/emulator/emulator -avd im_test -no-snapshot-load -gpu swiftshader_indirect
  ```
- 模拟器连宿主机后端：`10.0.2.2:8080`（`127.0.0.1` 在模拟器里指模拟器自己）。
- 本地测试账号：`user1001` / `user1002` / `e2etest1`，密码统一 `123456`。
