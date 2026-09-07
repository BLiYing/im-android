# Current Task — im-android（Kotlin + Compose 客户端）

> **活快照**：只记当前状态，**就地覆盖、不追加**。逐功能×端状态以
> `../IMServer/docs/CLIENT_PARITY.md` 的 Android 列为唯一来源；历史流水见 `git log`
> 与 `current_task.archive.md`（尚未创建，第一次裁剪本文件前先建）。
> 工程规范见 `CLAUDE.md` 与 `CODING_STYLE.md`。

## 当前焦点

> **改用实体机调试 ✅ 2026-09-07（下午）**。模拟器在本机负载下反复整体 ANR（连 Pixel Launcher
> 都卡死），用户指示直接用实体机（Pixel 2 XL，1440×2880 @560dpi，**深色模式**）。
> 连本机后端走 **`adb reverse tcp:8081 tcp:8080`** + 应用内服务器地址填 `127.0.0.1:8081`——
> 比改 `network_security_config.xml` 放宽内网 IP 干净（`localhost` 本来就在明文白名单里，
> 不用为调试动生产配置）。登录页那行提示还写着"真机填内网 IP"，**已过期，待改**。
>
> **实体机上一次跑通并逐项看过**：系统消息居中灰字 ✅、群内发送者头像底对齐 ✅、
> 日期胶囊 ✅、气泡时间恒 `HH:mm` ✅、Room v1→v2 迁移在**有数据的旧库**上无痛 ✅、
> 转发全链路 ✅（气泡上方显示「转发自 @user1001」——是句柄不是「我」，正是那条隐私纪律的实证）。

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

> **组件尺寸接上基准 ✅ 2026-09-07（`3233922`）**。此前颜色/字号是照 `UI_COLOR.md` 抄的（可核对），
> 但头像直径、行高、气泡最大宽、输入栏高度**是按 Android 惯例拍的**。去 iOS/Web 代码里量了一遍，
> 本端 5 处对不上，其中两处是真错：气泡最大宽写成固定 280dp（两端都是比例，固定值在 360dp 机上
> 占 78%、411dp 上占 68%），输入框圆角写死 20（iOS 是跟随用户可调的气泡圆角）。
> 基准表落在 `../IMServer/docs/UI_SPEC.md`，尺寸全部收进 `IMDimens` 并注明三端出处；
> `SYMMETRY.md` 已登记，改数字会被 pre-commit 念。
> **验证换了方法**：不再肉眼比截图，改用 PIL 扫像素——头像 143px=52.0dp、行高 210px=76.4dp、
> 分割线起点 220px=80.0dp、日期胶囊 66px=24.0dp，逐项吻合（日期胶囊我目测成"偏小"，量出来是准的）。

> **UI_SPEC 七条待定项已拍板并三端落地 ✅ 2026-09-07（下午）**。用户定：时间格式取四段式、
> B/C/D 按 iOS、E/F/G 移动端跟 iOS 而 Web 维持现状。本端随之改了气泡内边距 12、未读徽标高 20、
> 输入栏按钮距边 8、跳到底部钮 36，并**新实现群内发送者头像**（30dp，只在连续段末挂、段内占位，
> 纯函数 `showsSenderAvatar` 已变异验证）。
> **实测又逮到一处自己引入的**：头像列左边距写成 24 而非 12——消息列表本身已有 12dp 横向内边距，
> 我在气泡侧又加了一遍。改成「列表那 12 就是规格里的 chatAvatarLeading」，并加测试钉住
> `12+30+6=48`（与 iOS `_leading.constant` 同值）。修后实测 12.0 / 30.2 / 48.4dp。

## 下一步

**0. 群成员头像图 URL 缺失**：`showsSenderAvatar` 挂的是首字母色块（取色三端同源，颜色对），
   但没有头像图——群成员头像无本地缓存。要接得先做 `POST /users/batch` 解析器（CLIENT_PARITY 有这行）。
**0. 多选（M4-3 的另一半）**：长按菜单的「多选」本轮**刻意没加**——不带着只会弹
   「还没做」的死菜单项交付。`Forward.toggleCapped` 与 100 条上限已就位并测过，
   缺的是多选态 UI + 底部批量栏。
**0a. 合并转发（chat_record 一张卡片）本端没做**：现在只有逐条转发。
   合并转发要一整套卡片渲染 + 匿名 sender key（im-web `useForward.ts` 那套），是独立一块。
**0b. ~~系统消息渲染~~** ✅ 实体机实测通过（见当前焦点）。原文：**系统消息渲染已改成居中灰字**（`SystemNote`）——但**只过了编译与单测，没实测**：
   模拟器在做这一步时整体 ANR（连 Pixel Launcher 都卡死，机器负载过高），
   下次起模拟器第一件事就是看一眼群聊里的「XX 被设为管理员」是不是居中灰字。

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

- **`CLIENT_PARITY.md` 的 Android 列还全是 ⬜**：限制已于 2026-09-07 解除
  （用户明确「相关文档在其他地方该更新更新」），但那张表约 30 行、还没补。
  **这是目前最误导人的一份文档**，优先补。
- **布局验证靠像素测量，不是靠肉眼**：`adb exec-out screencap` 出图后用 PIL 扫，
  比对 `UI_SPEC.md` 的规格值。本轮就靠它纠正了一次目测误判。**但这是手工的**，
  没有自动化——真正的回归还得靠 Robolectric/Compose 或截图基线，两者都还没做。
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
