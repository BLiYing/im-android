# Current Task — im-android（Kotlin + Compose 客户端）

> **活快照**：只记当前状态，**就地覆盖、不追加**。逐功能×端状态以
> `../IMServer/docs/CLIENT_PARITY.md` 的 Android 列为唯一来源；历史流水见 `git log`
> 与 `current_task.archive.md`（只读归档）。
> 工程规范见 `CLAUDE.md` 与 `CODING_STYLE.md`。

## 当前焦点

> **通话记录：被叫侧 `cancel` 文案「未接来电」→「对方已取消」（三端 + 设计文档，2026-09-27，与用户讨论后拍板）**：
> `cancel`（主叫主动撤回）跟真正错过（`no_answer`/`busy`/`offline`）不是一回事，只改这一种 reason 的措辞，其余三种
> 与推送文案不变；`tone`（红/计未读/推送）完全不变，纯文案改动。本端改动：`data/CallRecord.kt` 新增
> `CANCELLED_BY_PEER_TEXT = "对方已取消"` 常量替换 `cancel` 被叫分支的 `MISSED_TEXT`（本端未接入 i18n
> 表，是硬编码中文字面量，符合本端现状——`scripts/i18n/targets.json` 里 Android 目标仍 `enabled:false`）。
> **顺手修了一个真实隐患**：`isMissedPreview` 原按字符串**后缀**匹配「未接来电」判断会话列表该不该标红
> （`lastContent` 写库那一刻就烤好预览串，见 `MessagePreview.kt`），只改文案不改这个判据的话 `cancel`
> 的会话列表预览会**悄悄丢红**——已改成同时匹配两种后缀，并在 `CallRecordTest.kt` 的
> `onlyCalleeMissedPreviewIsRed` 补了专门锁住这条的用例。`./scripts/test.sh` 全量 **886/886 绿**
> （用例数不变，因为是给既有测试方法加断言，不是新增方法）。三端共用向量 `docs/conformance/call_record.json`
> 改的那条用例已同步拷贝进本仓 `app/src/test/resources/call_record.json`（防漂移测试
> `resourceMatchesSourceOfTruthWhenPresent` 已过）。细节见 `../IMServer/current_task.md`。
> **未做**：真机上实际走一遍"A 呼叫 B、A 取消"看会话列表预览是否真的标红（本次只验证了纯函数）。

## 下一步

0d. **群资料页「成员」tab 缺搜索入口**（2026-09-24 用户报后调研发现，未改代码）：`GroupApi.members()`
   已支持 `q` 参数、服务端本就能分页搜索，复用 `MentionComposerState`/`RtcInviteProvider` 的调用模式
   即可实现；参照 iOS `IMGroupMemberSearchViewController`（搜索框 + 服务端分页 + 下拉加载更多）。
0b. **上几批仍欠的真机回归**（这两轮没动到、也没回归）：归档查看器「更多」五项、合并转发记录页内翻页、
   长按预览里点图/点链接只关菜单、以及 2026-09-15/16 那两批的清单（见 `current_task.archive.md` 顶部）。
0c. **本批（2026-09-23）真机回归**——📅/👤/撤回实时刷新/非 UTC+8 时区换算均已测过（见 archive），
   仍欠：**转发带 @ 图片到群里，"别人视角"点开被 @ 的名字、有没有收到强提醒**——真机 UI 这条链路没跑通
   （系统图片选择器多级页面盲点坐标屡次踩偏），也没有第二台设备/账号可以扮演"收端"；只验证到
   "提及渲染可点 + 点了跳资料页"这条基础设施是通的，`mentions` 随转发存活只有单测覆盖。
   另外**"对方撤回"的两种文案**（"XX/对方撤回了一条消息"）同样因为单设备单账号测不出来，只测了"自己撤回"。
1. **语音播放整端缺失**：聊天页气泡与归档语音行都只画波形、点不响（全 App 没有播放器，也没有录音）。
   iOS 那行的 ▶ 与波形都能就地播（`IMVoicePlayer sharedPlayer`）。这是本端与 iOS 最后一处**能力差**。
2. **Android 离线积压整套未启动**（`../IMServer/docs/design/OFFLINE_BACKLOG_DESIGN.md` §4.11.1 / §5 B3a）：
   建议先让 sync 带 `max_gap`；另缺区间清单、`conv_bump` 被丢弃、sync/window 路径不回 `delivered`；
   C4 未做；会话内检索只取一页。
3. **转场没接的几处**（`docs/UI_PARITY_IOS.md` §4）：群资料 / 聊天信息内部子页读的是已置空的状态。
4. **卡片弹层推广**：@提及、选文件、已读详情、日期跳转、选联系人发名片仍是整屏/底部面板，逐个换 `IMCardSheet`。
5. **收藏的剩余项**：「以聊天模式查看」（按来源会话分组下钻）、来源名到群昵称级（现只到好友备注/昵称/补拉名片）；**长按菜单缺项**：举报、翻译。
6. **宫格**按 `IMAlbumRowPattern` 重写布局 + 五道防跳版闸；相册宫格逐格勾选。
7. 按 `docs/UI_PARITY_IOS.md` 剩下的 🔴（📅/👤 已于 2026-09-23 收口）：水滴头部形变、语音页签内播放、「名片」页签、隐私页无障碍。
8. 按 `CLIENT_PARITY` 追 iOS：消息编辑（M4-5）→ 设置页其余 6 项 → 头像裁切页 → 推送（M5）。
9. **群成员头像图**：首字母色块对，但无头像缓存；要先做 `POST /users/batch` 解析器。

## 已知坑 / 限制

- **合并转发记录里的名片 →「发消息」不能真正换会话**（2026-09-25 真机回归发现，未修）：`ChatHost.kt`
  把 `ChatRecordLayer` 内名片卡片的「发消息」接到同一份 `openUser` 状态，点了只 `onCloseUser()`
  关资料层、退回记录页，不会直接落回聊天——因为 `ChatHost` 绑死单一 `convId`，没法就地换会话。
  名片指向的人恰好是当前会话对方时不明显（多按一次返回就到了），指向别人时发消息形同无效。
- **App Links 系统级深链接：明确暂不做**（2026-09-22 用户拍板）。指从系统任意来源（短信/微信/邮件/
  系统相机扫码）点开邀请链接直接拉起 App、跳过浏览器——区别于已接的"App 内已打开的链接里拦截"
  （`QrRouteHost`/`isOwnInviteLink`）。卡点是基础设施而非代码量：Android 侧需在清单声明
  `autoVerify` + 域名，同时**该域名根目录**要放一份 `assetlinks.json` 供系统联网校验，这要求一个
  真实可访问、带 HTTPS 证书的**固定公网域名**；本项目开发/联调目前全用 `10.0.2.2`/局域网 IP
  （`-public-url` 只是开发占位），没有这样的域名就无法验证功能是否真的生效。iOS 对应的 Universal
  Links、Web 落地页「在网页版中打开」按钮同理都还没做——是三端联动的活。**等项目有固定部署域名后
  再重新评估**，别在没有域名前先写代码——写了也测不出有没有生效。
- **要不要改原生 Material Design 风格：暂缓，未决策**（2026-09-18 讨论，见
  [`docs/design/MATERIAL_DESIGN_EVAL.md`](docs/design/MATERIAL_DESIGN_EVAL.md)）。触发条件：想清楚
  「这个 Android 端主要给谁用」再重新评估；别在没想清楚前顺手改配色/图标/弹层。
- **浮层里的协程必须由宿主传 `scope`**（2026-09-17 真机抓到）：长按菜单/归档菜单点完就 `onDismiss()`，
  `rememberCoroutineScope()` 绑的是那一层，挂上去的活会**当场被取消**且只在日志里留一句
  `LeftCompositionCancellationException`。已收口的有 `ChatMessageMenu`、`ArchiveActionsHost`、
  `ArchiveViewer.kt` 整族；`ChatViewerLayer` 恒在组合里，自建是安全的。
- **注释里别写「斜杠 + 星号」的通配 MIME 字面量**：Kotlin 块注释**可嵌套**，它会当场开一层内层注释，
  本段的结束符只关掉里层、外层一路吞到文件尾，而编译器报的是「Missing '}'」（2026-09-17 栽过一次）。
- **门控的边界**：聊天气泡（别人发的）守门控；**归档/收藏宫格按 `DownloadPolicy.archiveTileUngated`**（自己发的、图片且策略放行才直接显示）；**自己发的（`mine`）不门控**。
  改任一处都要回头看另两处，否则又会出现"某个入口看不到图"。
  ⚠️ **三条边界条件**：① **「已失效」不在豁免之列**——自己发的媒体一样会被服务端清理，豁免掉的话
  它会装作正常、点进去是空查看器（2026-09-17 `/code-review` 抓出，判据 `ungated`）；
  ② 宫格里放行的图片**按原图地址加载**（服务端没有缩略图接口，`thumb` 只是 ~20px 磨砂占位），翻历史会拉原件流量；
  与 iOS 一致（图片策略放行即按 URL 加载），账号把图片设成手动时宫格只显磨砂 + ↓。
  ③ **门控外观只准复用 `GateOverlays.kt` 的组件**（聊天页、详情页、收藏页三处同一套），别再另画徽标。
- **归档与收藏里语音仍不能播**（见「下一步 1」）；**波形已能显示**（本地兜底），但**只覆盖本地已加载的那一段**，
  拿不到就是等高条纹（协议允许的合法状态）。发送者名同理：成员表 → 本地昵称快照 → 空（**不落内部 uid**）。
- **宫格列数 = 3**（`MediaGrid.COLUMNS`，与 iOS 两处宫格逐字一致）。改它先改 `../IMServer/docs/UI_SPEC.md`。
- **转发必须带 `poster`/`media_w`/`media_h`/`duration`/`thumb`/`waveform`/`mentions`/`mentionSpans`**（判据
  `Forward.attributesOf`，SYMMETRY 已登记）：漏带全程静默，只有收件人看得出来，且事后补不回来。
  **两个入口**（聊天页长按/多选、详情页归档查看器）都要走到，**待发行也要写**（resend 从它读）；
  ⚠️ `mentions`/`mentionSpans` 只在图片/视频上带（文本消息 iOS 本就不转发提及）；归档/收藏两个转发入口
  服务端不回带 `mentionSpans`，恒为空，是结构性限制不是漏改（2026-09-23）。
- **加一个"随消息走"的新字段要动七处**：协议负载 → `Forward.attributesOf` → `createPending` → `transmit` →
  `resend` → Room 迁移 → **`AckCarryOver.CARRIED`**。最后那处有反射闸会当场变红逼你做决定。
- **`ChatScreen.kt` 的滚动时序已整组搬到 `ui/screens/ChatScroll.kt`**：四条 effect 读写同一份 `ChatScrollMarks`、
  彼此有先后与互斥，**必须待在一起**，别再往回挪、也别拆散。
- **宫格绝不能再用「LazyColumn 的 item 里塞 LazyVerticalGrid」**：纵向嵌套同向滚动 + 固定高度封顶，
  两条症状一个根因。正解是由外层列表逐行渲染（`data/MediaGrid.kt`）。
- **粘贴判据的三条纪律**（`data/PasteImage.kt`）：只摘走自己认领的区间；超 `MAX_PENDING` 要说一句；
  问过系统确认不是图片的 URI 要记住（`PasteImages.rejected`），否则每次按键都在主线程查一次 `ContentResolver`。
- **长按菜单的原位预览里链接与图片都不可点**（点了只关菜单）：`ChatMessageMenu` 画预览时把 `LocalOpenLink`
  置空、`onOpenMedia` 传 `{ onDismiss() }`。别改成在 `Bubble` 里按 `onLongPress != null` 判（那条判据永远不生效）。
- **`IMCardSheet` 关闭途中也拦着返回键**：放行会让「点发送紧接着按返回」那次转发丢。
- **OkHttp 收到服务端关闭帧不会自己回帧**：不在 `onClosing` 里 `close(1000, null)`，踢下线/改密下线要等 25s。
- **会话列表离线首登是空白**；**改密后的新续期凭据只出现一次**（`NonCancellable` + 带发起时 uid）。
- **应用内浏览器**：release 包不放行明文 http，debug 变体整个放开，所以 debug 真机看不出来。
- **`ONLY=X ./scripts/test.sh` 跑不了**（`:media-picker` 报 "No tests found"）：改用
  `./gradlew :app:testDebugUnitTest --tests '*A*'`。**JVM 单测里 `android.util.Log` 是桩**，先 `IMLog.useSinksForTest()`。
- **覆盖页的触摸屏蔽层绝不能 consume**（`ui/components/TouchShield.kt` 文件头）：父级在 Main 阶段吞事件
  会让子列表的慢速拖动整次作废，症状是「有时能划有时划不动」。要屏蔽下层兄弟，占住命中测试就够了。
- **本仓 JVM 单测摸不到 Room 生成的真实 SQL**（没有任何测试真跑过 `@Query`，2026-09-23 真机抓到
  `MessageDao.search()` 一条 SQL 逻辑 bug——`test.sh` 879 例全绿却没测出来，见「当前焦点」）。DAO 层的
  `@Query` 改动**光靠单测不算数**，要么真机走一遍界面，要么起个 Robolectric/`Room.inMemoryDatabaseBuilder`
  的桩（本仓目前两者都没有，暂时只能靠真机）。
- **instrumented 测试只有 `TouchShieldTest` 一个，test.sh 不跑它**（要真机）。别用 `connectedAndroidTest`
  （跑完会卸载 App、丢登录态），用：`assembleDebug assembleDebugAndroidTest` → 两个 APK 各 `adb install -r` →
  `adb shell am instrument -w -e class com.libeyond.imandroid.ui.components.TouchShieldTest com.libeyond.imandroid.test/androidx.test.runner.AndroidJUnitRunner`。
  其余布局/滚动/手势/输入法仍靠真机截图核对
  （`adb exec-out screencap`；用 `uiautomator dump` 找控件坐标比按像素猜可靠）。
- **视频不转码**、**分片上传不跨进程续传**、**视频没有本地缓存**、**图片不压缩**（只挡 20MB）、**无断点续传**。
- **查看器翻页只能往更旧续拉**（服务端媒体接口是 `conv_seq < cursor` 倒序分页），本地一次最多取 300 条。
- **贴线文件持续在涨，暂未拆**（上限 600，**WARN 线 480**，2026-09-23 `test.sh` 报的最新行数）：
  `GroupInfoHost.kt` 594、`ChatHost.kt` 575（本次日历/来自筛选加了约 30 行）、`ChatScreen.kt` 541、
  `MessageRepository.kt` 552、`MessageService.kt` 507。**都还没触顶但都很近了**——下次往这几个文件加东西前
  先规划拆分，别等 WARN 变红闸。
- **明文 HTTP 只对 `10.0.2.2`/`localhost`/`127.0.0.1` 放行**（release）；**本机 `JAVA_HOME` 是坏的**（test.sh 已自愈）。

## 关联工程 / 常用命令

- 后端 `../IMServer`；iOS `../IMProgram`；Web `../im-web`。
- **参考实现**：判据类纯函数可照搬 Web，但守 `../IMServer/docs/SYMMETRY.md`：**对称的是不变式，不是代码形状**。
- 本仓：
  ```bash
  ./scripts/test.sh                # 唯一测试入口（门禁 + 编译 + 单测）
  BUILD_ONLY=1 ./scripts/test.sh   # 只编译
  ./scripts/install-hooks.sh       # 每个 clone 一次
  ```
- 后端（改了后端代码**必须重启**）：`cd ../IMServer && ./scripts/dev.sh --no-tail`
- 真机：`GMGY7XF6LBJB6PFU`，adb 在 `~/Library/Android/sdk/platform-tools/adb`；
  装包 `adb -s GMGY7XF6LBJB6PFU install -r app/build/outputs/apk/debug/app-debug.apk`。
  **真机连的是 `192.168.1.12:8080`**（登录页底部可见），不是模拟器那个 `10.0.2.2`。
- 模拟器：AVD `im_test`（API 36 / pixel_5），连宿主机后端用 `10.0.2.2:8080`。
- 本地测试账号：`user1001` / `user1002` / `e2etest1`，密码统一 `123456`
  （**user1002 的密码已被改密测试轮换过，登不上就用登录页的「免密登录（开发）」**）。
