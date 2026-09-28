# Current Task — im-android（Kotlin + Compose 客户端）

> **活快照**：只记当前状态，**就地覆盖、不追加**。逐功能×端状态以
> `../IMServer/docs/CLIENT_PARITY.md` 的 Android 列为唯一来源；历史流水见 `git log`
> 与 `current_task.archive.md`（只读归档）。
> 工程规范见 `CLAUDE.md` 与 `CODING_STYLE.md`。

## 当前焦点

> **好友/群成员备注编辑：回填补齐到通讯录列表与群成员资料页 ✅（2026-09-28，`d4cc22e`，已推送）**：
> `UserProfileHost.onRemarkChanged` 此前只接了 `ChatDetailHost` 一条路（`/code-review` 抓出的坑：
> 改完备注退回上级页仍显旧值）。补齐两处真正会复现的：`ContactsHost`（好友列表点进资料页改备注，
> 回填 `friends` 状态）、`GroupInfoHost`（群成员资料页改备注，加 `remarkOverrides` 覆盖
> `knownFriends` 这份整会话只拉一次的快照）。`FavoritesHost`/`ChatPickerLayers`/`QrRouteHost`
> 三处未动——都是「选人即用」场景，资料页没有可复现的常驻展示位，见「已知坑」。
> `./scripts/test.sh` 952/952 绿（纯 Compose 状态回填，无可单测的新逻辑分支）。

> **语音消息三期全部完成 ✅（2026-09-28，三端对齐，已提交并推送，详情见
> `../IMServer/docs/CLIENT_PARITY.md` voice P0/P1 两行 Android 列）**：① 播放（`voice/VoicePlayer`+
> `ui/voice/VoiceViews`）、② 录制（`voice/VoiceRecorder`+`ui/voice/VoiceRecordUi`，手势/悬浮层/
> 锁定行/暂停试听/5min 上限/中断转暂停全套，`/code-review --fix` 修 7 条 + 用户真机复测又报的 2 条
> bug 均已修——完整清单见 `current_task.archive.md` 2026-09-28 条目）、③ **转文字**（`b3cf207`，
> 同批提交）。上滑锁定的悬浮层顺手又补了一条：锁钮里**加了呼吸上箭头**（`Lucide.ChevronUp`，
> `position.y` 上下 4dp、0.7s、线性、无限往复，对齐 iOS `IMVoicePressOverlay.restartArrowBreathe`
> 逐参数抄的——之前只把锁钮渲染出来了，没照 iOS 补这个"往上滑到这里"的动效提示，用户对照 iOS 截图
> 指出后补上），真机 adb 分帧摆拍确认箭头在两帧之间有位移。
>
> **③ 转文字**：长按菜单「转文字」（仅语音、`convSeq>0`）→ `voice/VoiceApi.transcribe` 调
> `POST /voice/transcripts`（只传消息坐标不传音频路径）→ 气泡下方展开面板（左侧引用线+文本+隐私
> 说明尾行，同 iOS/Web 视觉语系）；命中缓存秒出，未命中先显「识别中…」，结果经 `voice_transcript`
> 帧（`MessageService.voiceTranscripts` → `VoiceTranscriber.applyRemote`）到达。新增
> `voice/VoiceTranscriber.kt`（展开态 `StateFlow`）+ `voice/VoiceTranscriptStore`（`PrefsVoiceKv`
> 持久化：文本按**音频内容**缓存、折叠态按 mid 落盘，两条判据对齐 iOS `IMVoiceTranscriber`，
> FIFO 封顶 2000/500）；`MessageActions`/`ChatMessageMenu` 补「转文字」/「取消转文字」互斥对。
> **调研纠偏**：动手前一度误判 iOS/Web 都没做这个新方案（分别被 Objective-C 文件后缀、CLIENT_PARITY
> 里一条已废弃的旧设计行带偏），用户当场指出后重新核实——iOS `IMVoiceTranscriber`(.h/.m)+
> `IMChatViewController+Menu.m`/`+Voice.m`、Web `useVoiceTranscript.ts` 其实都已实现且完整，
> 本轮 Android 实现直接照抄两边的判据（内容去重缓存、折叠态持久化、识别中途取消不被迟到结果撑开）。
> `VoiceTranscriberTest` 8 例 + `VoiceTranscriptStore` 4 例 + `MessageActionsTest` 1 例（均先见红，
> 含一例用 `CompletableDeferred` 钉住"请求真在途时取消"的竞态）。`MessageRepository.kt` 顺手拆分
> （新增 `MessageRepositorySend.kt`，600 行硬闸触顶所致，纯平移无逻辑改动）。`./scripts/test.sh`
> **948/948 绿**；OPPO 真机对着真实识别引擎（本机已装 `install-transcribe.sh`）实测通过：菜单→
> 识别中→文本落地（含隐私说明尾行）→取消转文字收起，全链路走通。
> **已知简化**：面板撑高后「补进视口」只做了近似（历史中间某条转写可能需要用户自己再滑一下，
> 不像 iOS/Web 那样按行几何精确计算），多数场景（末条是语音）够用，留作后续小优化。

> **多语言 P3 全部完成 ✅（2026-09-27，OPPO 真机中↔英实测通过）**——① `sys_event`/`sys_args`：新增
> `data/SysEvents.kt`（对齐 iOS `IMSysEventFormatter`/Web `sysEventRender.ts`）——群系统消息按事件表拼出与
> `sys_segments` 同构的分段，喂回 `SystemNote` 原有的「本地显示名 + 可点」管线（邀请多人时被邀请者逐个出段、各自可点）；
> 系统通知单聊（777000）按 `sys_args` 拼多行气泡正文。会话列表预览**按当前语言现算**（会话表存
> `lastSysEvent/lastSysArgs/lastSysSegments`，同 iOS），切语言立即生效。落库：消息加 `sysEvent`/`sysArgs`，
> Room v10→v11（真机覆盖安装验证迁移无误）。事件为空/不认识 → 回退服务端中文整句。`SysEventsTest` 12 例（先看红过）；
> `./scripts/test.sh` **908/908 绿**。**存量限制**：升级前已落库的消息行没有这两列（旧版没存），聊天页里仍是中文；
> 服务端 P3 上线前产生的系统消息本身也不带 `sys_event`（协议明写不回填）。
> ② `reply_snapshot_kind`/`_args`：`data/ReplySnapshots.kt` 把结构化标记还原成引用块已认得的原始 token
> （`[chat_record] 标题`/`[contact] 名字`/`[file] 名`/`[voice] m:ss`/`[recalled]`），显示仍统一走 `localizeReplySnapshot`，
> 图标/文件名判据不分叉；Room v11→v12（消息加 `replySnapshotKind`/`replySnapshotArgs`）。真机：英文下引用名片显示 `[Contact] 名字`。
> ③ 顺修既有 bug：**群系统消息被计入未读**——`IncomingRule.countsAsUnread` 与服务端 M4-8 同口径排除 `system`，
> 聊天页未读分割线也不再以系统消息为首条；真机验证群公告进来红点不再 +1。
> ④ `ONLY=Xxx ./scripts/test.sh` 修好（只把 `--tests` 交给含匹配测试的模块；用例数自检只数匹配的报告）。
> ⑤ 新增 145 键英文逐条复核（2026-09-27）：改 34 条（含 code-review 后补 7 条；复数补 one 形态、术语对齐 Mute everyone/Log out/[Chat History]/[Call]、
> `{op}: done/failed` 取代生硬拼接）；真机英文看过收藏、数据和存储（含自动下载子页）、群资料、群管理，无截断。
> `./scripts/test.sh` **915/915 绿**（新增 `SysEventsTest` 12、`ReplySnapshotKindTest` 6、`IncomingRuleTest` +1，均先看红过）。

> **应用内多语言全量迁移 ✅（2026-09-27，真机 OPPO Android 15 实测中↔英通过）**：全 App 文案接上
> `IMServer/docs/i18n/strings.json`（本轮新增 145 个键，优先复用 iOS/Web 译文；表现 1538 键）。
> 取文案两条路：Compose 用 `stringResource`；非 Compose（`data/` 纯函数、回调、toast）用 **`i18n/Str`**
> ——可插拔解析器，App 里跟随 `LanguageStore`，JVM 单测经 ServiceLoader 读 `values/` 简体中文，故既有中文断言原样成立。
> 切换：API 33+ 平台 `LocaleManager`；**所有版本** `MainActivity.attachBaseContext` 按当前语言包 Context，
> API 33 以下切换时自行 `recreate()`（原"<33 不能即时切换"的限制已消除）。`media-picker` 模块看不到 app 的 `R`，
> 自带一份 `mp_*` 中英资源（手写，改时两份一起改）。`check-i18n.mjs` 已接 Android 源码扫描（`R.string.a_b` 反查回表键）。
> 顺手修的真实 bug：引用块/输入栏回复条的类型图标与文件名判据原先比对本地化后的中文（`[图片]`），英文下会全部失效——
> 改为只认原始快照 token（`[image]` 等 + 服务端预本地化的 `[聊天记录]`/`[个人名片]` + 存量中文）；
> `replyPreviewOf` 改产出原始 token，与服务端冻结快照同一形态，显示时统一 `localizeReplySnapshot`。
> `./scripts/test.sh` **896/896 绿**。真机看过：消息列表、通讯录、我、语言页、聊天页、聊天详情、通话界面。
> **刻意保留中文（DEFERRED，同 iOS）**：会写进消息内容外发的（@全员 token `Mention.ALL_LABEL`、合并转发兜底标题
> `SelectionActions.chatRecordTitle`、转发来源名/群成员 displayName 的"未命名用户"兜底）、sdk 传输层诊断
> （上层 `userMessage()` 会整体替换，不到达屏幕）、开发期 UI（免密登录/服务器地址）、拼音分组表。

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
1. **Android 离线积压整套未启动**（`../IMServer/docs/design/OFFLINE_BACKLOG_DESIGN.md` §4.11.1 / §5 B3a）：
   建议先让 sync 带 `max_gap`；另缺区间清单、`conv_bump` 被丢弃、sync/window 路径不回 `delivered`；
   C4 未做；会话内检索只取一页。
2. **转场没接的几处**（`docs/UI_PARITY_IOS.md` §4）：群资料 / 聊天信息内部子页读的是已置空的状态。
3. **卡片弹层推广**：@提及、选文件、已读详情、日期跳转、选联系人发名片仍是整屏/底部面板，逐个换 `IMCardSheet`。
4. **收藏的剩余项**：「以聊天模式查看」（按来源会话分组下钻）、来源名到群昵称级（现只到好友备注/昵称/补拉名片）；**长按菜单缺项**：举报、翻译。
5. **宫格**按 `IMAlbumRowPattern` 重写布局 + 五道防跳版闸；相册宫格逐格勾选。
6. 按 `docs/UI_PARITY_IOS.md` 剩下的 🔴（📅/👤 已于 2026-09-23 收口）：水滴头部形变、「名片」页签、隐私页无障碍（语音页签内播放已于 2026-09-28 随语音 P1 完成，见 CLIENT_PARITY）。
7. 按 `CLIENT_PARITY` 追 iOS：消息编辑（M4-5）→ 设置页其余 6 项 → 头像裁切页 → 推送（M5）。
8. **群成员头像图**：首字母色块对，但无头像缓存；要先做 `POST /users/batch` 解析器。

## 已知坑 / 限制

- **`FavoritesHost`/`ChatPickerLayers`/`QrRouteHost` 三处进 `UserProfileHost` 没接 `onRemarkChanged`**
  （2026-09-28，与 `ContactsHost`/`GroupInfoHost` 同一个坑，本次只补了后两处）：这三处进资料页都是
  「选人即用」场景（收藏来源名片、转发选目标、扫码加人），资料页本身没有像 ChatDetailHost/ContactsHost
  那样退回后立刻可见的常驻展示位，复现路径没那么直给，暂缓；要补的话按同一套模式（本机状态覆盖）接上即可。
- **语音转文字 code review 跳过的两条小优化（2026-09-28，均不影响行为，留作后续）**：
  ① `VoiceTranscriptStore.putText` 每写一条都把整份 FIFO 顺序表（最多 2000 条）`joinToString` 后重写
  SharedPreferences——填满缓存近似 O(n²)，量小暂不改；要改可只在淘汰时写顺序表，或改落 Room。
  ② `PendingBubbles.kt` 的 `PendingVoiceBubble` 里 `bubbleWidthDp(...).dp.coerceAtLeast(160.dp)` 是多余的
  （`VoiceRules.bubbleWidthDp` 已保证 ≥160），可删。
- **`applySync` 每页只要 `c.messages` 非空就调用 `bumpConversationFromLatest`，哪怕这批全是 `msg_op`/
  墓碑这类非内容行**（2026-09-28，会话不上移/聊天页不跟底/补滚动条那批修复的 code review 发现，未改，
  留作后续优化）：这批消息会被 `onIncomingBatch` 内部过滤成空，白白多打一次 `latestWindow` 查询——
  影响可忽略（一次索引 SELECT），但要干净修得改 `onIncomingBatch` 的返回契约（让它顺带报"这批里有没有
  真消息"），而它还被 `WINDOW_RESP`（锚点开窗，翻历史用）共用，改动会超出当时那次 diff 的范围。
  见 `data/MessageSync.kt` 的 `applySync` / `bumpConversationFromLatest`。

- **`MessageService.resend`（点红❗重试）此前对 `Failed` 状态的行完全无效**（2026-09-28 随语音录制一起修）：
  它查 `repo.inFlight()`，而那个方法只挑 `state=Sending` 的行——`Failed` 的行永远查不到，点了跟没点一样，
  过去没人发现是因为没人真的手测过这条路径。已改成 `pendingByClientId` 按 `clientMsgId` 直查、不按状态过滤。
  顺带把「正文仍是本地 uri」的处理也理顺了：语音走 `MediaSendPipeline.reuploadVoice`（本地文件在应用私有
  目录，进程重启后仍读得到，重新上传即可）；图片/视频的本地 uri 是系统相册 `content://`，读权限随发起进程
  一起没了，读不回来，改成直接标失败（`chat_resend_upload_incomplete`），**不再有原来"会把 `content://`
  当正文发给对端"的风险**（原风险仅存在于"点了对失败行确实生效"的前提下，而那个前提本身就不成立，
  相当于一次修复顺带堵上一个从未真正暴露过的隐患）。
- **语音发送成功后本地 `voice_pending/` 缓存文件不清理**（2026-09-28，TODO）：字节已经在服务端、也已经靠
  `MediaCache.adopt` 进了本地缓存，理论上可以删源文件，但要等到**确认 ack**（不是仅仅"上传成功"）才安全删
  ——现在没做，量小暂不影响使用，是独立的小任务，别顺手在别的改动里夹带。
- **语音布局以 iOS/Web 现行实现为准，不以草图 v2.5 字面为准**（2026-09-28 核对）：草图写「时长 · HH:mm ✓✓」同一行、红点在 meta 行左，
  但 2026-08-27 用户拍板「时间独立一行」、设计文档 §7（08-30）定「红点右上角」，iOS `IMVoiceBubbleCell` / Web `VoiceBubble` 均已照此。
  **播放标识用 `seq:<conv_seq>`**（iOS 用 serverMsgID）：资料页归档条目只有 conv_seq，这样两处红点 / 播放态同步。
  Web 录的分片 MP4 `MediaPlayer.duration` 读出 11ms——分母一律回落消息 `duration`（`VoiceRules.effectiveDurationMs`）。

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
- **`GroupInfoHost.kt` 已顶到硬闸 600 行**（2026-09-28，本次接 `onRemarkChanged` 加的 2 行踩到线，
  已把新注释压到一行才卡着通过——**下一次改这个文件必须先拆**，不能再靠压行数混过去）。
  其余贴线文件（2026-09-23 `test.sh` 报的行数，WARN 线 480）：`ChatHost.kt` 595、`ChatScreen.kt` 557、
  `MessageService.kt` 547、`VoiceRecordUi.kt` 512——都还没触顶但都很近了，加东西前先规划拆分。
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
