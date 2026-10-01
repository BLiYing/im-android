## 2026-09-29 im-rtc 换票 + 转场 Media 分支 + 离线积压 C2 + 群成员搜索入口（从 current_task.md 移入，均已完成；转场剩余分支/离线积压后续切片的待办仍在 current_task.md「下一步」，未连带移入）

> **im-rtc 换票：从调试密钥迁移到 IMServer 真实换票接口 ✅（2026-09-28，本端已完成——三端全部完成）**：
> 此前 `RtcCall.signToken`（同步、`IMDebugTokenGenerator` 本地签调试票）改成调 IMServer
> `POST /api/v1/rtc/token`：
> - `signToken` 改成 `private suspend fun`，调新增的 `sdk/api/RtcApi.kt#fetchToken`。`HttpClient`
>   默认 `authenticated=true`，会自动带上当前 IM 会话的 Bearer token——本端**不用**像 iOS/Web 那样
>   手动取/传 token 字符串，直接 `http.call("POST", "/api/v1/rtc/token", ...)` 就够了。
> - `start`/`onTokenWillExpire`（SDK 回调，非协程上下文）都经 `RtcCall` 自建的
>   `CoroutineScope(SupervisorJob() + Dispatchers.Main)` 发起换票；两处都加了 `generation` 判定
>   （换票是异步的，回来时可能已经 `stop` 过——切账号/登出，同已有的 `HostListener.stale` 同一思路）。
> - 换票结果 → token 的判定逻辑抽成包级函数 `rtcTokenFrom(result: Result<RtcTokenResult>)`
>   （**不放 `object RtcCall` 内**：`RtcCall` 的类初始化含 `Handler(Looper.getMainLooper())`，
>   纯 JVM 单测碰它任何成员都会抛异常——第一版把这个纯函数写进 object 内，`RtcCallTest` 4 个用例
>   全红，改成包级函数后才过；已把这条教训写进 `../IMServer/docs/SYMMETRY.md`）。
> - `RtcConfig` 精简为只剩 `wsUrl`（去掉 `appId`/`keyId`/`secret`）；`local.properties`/
>   `app/build.gradle.kts` 的 `RTC_APP_ID`/`RTC_KEY_ID`/`RTC_DEBUG_SECRET` 三个 `buildConfigField`
>   一并删除。
> - `IMClient` 新增 `val rtc = RtcApi(http)`；`AppRoot.kt` 的 `RtcCall.start(...)` 调用点传
>   `rtcApi = client.rtc`。
> - 新增测试 `RtcCallTest.kt`（4 例，覆盖成功/空 token/业务异常/传输异常四条路径，变异验红过）；
>   `RtcConfigTest.kt` 同步精简（字段从四项减到一项）。这个项目对网络层（`XxxApi` 类）本身没有
>   mock 基础设施覆盖的先例（`ProfileApi`/`AuthApi` 等既有类同样没测），未额外引入。
> - `./scripts/test.sh` 全量 **969/969 绿**（新增 8 例）。
> - **真机实测 ✅ 已通过**（用户确认）：`adb install` 装到已连接的真机（OnePlus PKD130）后起 App
>   无崩溃，通话流程验证通过——三端（Web/iOS/Android）换票迁移**全部完成且全部真机/浏览器联调过**。

> **转场没接的几处：`GroupInfoHost` 的 `Media` 分支已落地 ✅（2026-09-28，未做真机验证）**：
> `ChatDetailHost` 的 3 个分支（Detail/Profile/Media）已在上两轮全部接完。这一轮照抄同一套「open
> 标志/data 分离」模型，只动 `GroupInfoHost` 的 `Media`（`viewing: ConvMediaItem?`）这一支——它与
> `ChatDetailHost` 那支是同一个坑：拆成 `viewingOpen: Boolean` + `viewingData: ConvMediaItem?`，
> 关闭（`onClose`/`onLocateInChat`/`BackHandler` 的 Media 分支）只翻 `viewingOpen = false`，
> `viewingData` 留着不清（下次 `onOpenArchive` 才覆盖）。**与 `ChatDetailHost` 不同的一点**：
> `GroupInfoHost` 的 if/else-if 链有 9 个分支，不是 3 个，没有把全部 9 支都改成 `when` 吃
> `PushTransition` 冻结的 `state`（那是「下一步 2」第 4 步的活，范围大得多）——本轮只把
> `Media`/`Detail` 这一对相邻的 `else if`/`else` 换成内部套一层
> `PushTransition(targetState = innerPage, depthOf = {...})`（`innerPage` 只在 `Media`/`Detail`
> 两值间取值，没有改 `GroupInfoPage` 枚举本身，`depthOf` 就地写成 lambda，没有像 `ChatDetailPage`
> 那样加 `depth` 字段——9 个分支还没全接，加了也只对得上这一对）；其余 8 个分支（Pick/Bans/Admins/
> JoinRequests/MemberProfile/MemberSearch/Manage/Qr）原样是整页替换，完全没动。**副作用**：为了让
> `Media`/`Detail` 在 if-else 链里相邻好嵌套，把 `managing`/`qrCardAsLink` 两个分支的判断顺序挪到了
> `Media` 前面（原顺序 MemberSearch→Media→Manage→Qr→Detail，现在 MemberSearch→Manage→Qr→
> Media/Detail）——核对过这几个标志位在运行时互斥（`viewing` 只在 Detail 页的 `onOpenArchive` 里
> 置位，`managing`/`qrCardAsLink` 为真时不可能同时置位 `viewing`），改变判断顺序不影响实际行为。
> `GroupInfoHost.kt` 574→**587 行**（还有 13 行余量，比 `Media` 这轮之前更紧，下一块动它前先看着点）。
> 没加新单测——改动是渲染结构重排 + 状态拆分，不是新的纯函数逻辑（`GroupInfoNav`/`GroupInfoPage`
> 本身没变，既有 `GroupInfoNavTest` 原样覆盖）。`./scripts/test.sh` **965/965 绿**。**未做真机验证**：
> 查看器进出是否真的滑动、`viewingData` 保留旧值有没有意外副作用，以及 `managing`/`qrCardAsLink`
> 判断顺序调整后这两条路径本身（与本轮改动其实无关，但顺手挪了判断顺序，值得真机点一遍确认没引入
> 回归）。`GroupInfoHost` 剩下的 8 个分支（`else if` 链上真正复杂的部分）仍是拆分方案里最大的一块，
> 留给后续，一个分支一个分支来。根因诊断与三种导航形态的完整拆分方案见「下一步 2」。

> **离线积压 C2：sync 带 max_gap 闸门 ✅（2026-09-28，只做了 Android 审计建议的第一小块，未做真机验证）**：
> `../IMServer/docs/design/OFFLINE_BACKLOG_DESIGN.md` §4.11.1 审计发现 Android C1~C6 全未启动、
> 且明确"建议先做这一条"——服务端 `max_gap`/`too_long`/`head_conv_seq` 早已上线（`internal/protocol/
> envelope.go`），但 Android 的 `sync_req` 从不带 `max_gap`，等于对服务端说"不限深度"，超级群重连
> 也会把积压整段抄完（`IncomingRule.kt` 头注释记着 2026-09-09 在 11 万条大群真机撞见过）。本轮只做
> 这一条：`SyncCursorItem` 加 `maxGap`（可空 Long，对齐 Go `*int64` 指针语义）、`SyncConversation`
> 加 `tooLong`/`headConvSeq`；`requestSync` 每条游标恒发 `SyncDefaults.MAX_GAP=400`（含 `has_more`
> 续页，续页此前会漏发）；`applySync` 收到 `too_long` 时留痕一条日志（游标本就因为 `covered_conv_seq`
> 原样等于 `since` 而不会推进，`advanceCursor`/`SyncCursorRule` 早已保证这点，不用额外分支）。
> `EnvelopeTest.kt` 新增 `SyncGapTest` 4 例（编码省略/保留 `max_gap`、解码 `too_long`、老服务端无此
> 字段时退化默认值），先临时撤掉这两个字段确认真的编译失败过（`git stash` 验红）。`./scripts/test.sh`
> **964/964 绿**。**范围边界（刻意不做，留给后续）**：① 不区分超级群——本该给超级群发
> `max_gap=0`（永远不自动补），但那需要本地先知道"这个会话是不是超级群"，`ConversationEntity`
> 目前不落这一列，做了也只是一半（还得管迁移/回填），归进 C1 一起做；② 收到 `too_long`后**没有任何
> 后续动作**——本地没有区间清单（C1 未做），只是"不再无限追平"，不产生"按需开窗补"的下一步，缺口
> 目前对用户不可见（不影响正确性，只是体验上"这个会话消息好像还没到最新"要等下次能补齐的 sync 才
> 追上，或用户自己下滑触发本地已有的按需开窗）；③ C1（区间清单）/C3（进会话锚点开窗）/C4（↓/实时/
> conv_bump 处理）/C5（sync 路径回 delivered）/C6（八项分流除已接的搜索/查看器翻页外其余几项）**均未
> 动**，见「下一步 1」的拆分清单。**未做真机验证**：改动只影响重连时的 sync 请求形状，需要真机连接
> →断网/杀连接→重连，抓包或看日志确认 `max_gap` 真的发出去了、大群重连不再整段追平。

> **群资料页「成员」tab 补搜索入口 ✅（2026-09-28，大群专用，未做真机验证）**：`GroupApi.members()`
> 早已支持服务端 `?q=` 分页搜索，本轮接上。判据 `data/GroupMemberSearch.kt`（阈值/去抖/续拉/去重，
> 逐条对齐 iOS `IMGroupMemberSearchViewController` 顶部那组 C 函数，有单测）；新页面
> `ui/GroupMemberSearchHost.kt`（首页去抖走 `LaunchedEffect` 换 key 自动取消在途请求；续页请求跑在
> `rememberCoroutineScope()` 上不受 `query` 变化牵连，故仍需 iOS `_searchToken` 的等价物
> `searchGen` 代次令牌判过期——动手时一度以为能全靠 `LaunchedEffect` 躲开手记 token，
> `/code-review` 抓出续页这条路躲不开、旧词迟到的翻页响应会污染新词已显示的结果，已修 `12bf333`）
> + `ui/screens/GroupMemberSearchScreen.kt`（复用 `IMSearchField`/`MemberRow`）。`/code-review` 顺带
> 抓出第二处竞态并同批修：在途守卫原设在 `fetch()` 内部（协程真正跑起来才生效），大列表里同一帧
> 多行触发 `onLoadMore` 会一起穿过守卫、打出重复请求——改成 `requestLoadMore()` 里同步设置。
> 群总人数 > 50 时「成员」tab 顶部才出现入口行，恒走服务端过滤不做本地过滤（超级群本地只有已翻到
> 的那几页，本地过滤会悄悄搜错）。**顺手做的拆分**：`GroupInfoHost.kt` 已贴到 600 行硬闸，把
> 成员分页那簇状态（`members/cursor/hasMore/loading` + 四处调用点）抽成 `ui/GroupMembersState.kt`
> （CODING_STYLE §7①），才腾出线程加新页面，改完降到 574 行。新增 `GroupInfoPage.MemberSearch`
> 枚举页（返回键走既有"一处派发"机制，`GroupInfoNavTest` 补两条层级断言）。
> **已知简化**：① 未做搜索结果命中高亮（iOS 有，这里判定为纯装饰，跳过）；② 从搜索结果点开一个人的
> 资料页、又退回来时，搜索词与结果会清空（因为这条路复用 `GroupInfoHost` 顶层"整页替换"导航，
> 资料页与搜索页互斥、不共存于同一份组合状态里；iOS 用 push/pop 天然不丢，本端要接住得让搜索页
> 常驻组合树、资料页浮在它上面，超出本轮范围，留作后续小优化）。`./scripts/test.sh` **960/960 绿**
> （新增 `GroupMemberSearchTest` 7 例 + `GroupInfoNavTest` 补 1 例，均先见红过）。**未做真机验证**：
> 没有现成的 50+ 人测试群，也没有 Android 端的屏幕自动化工具可用，只验证了编译与 JVM 单测；
> 入口显隐、搜索/翻页/选人整条链路需要用户真机跑一遍。

## 2026-09-28 好友备注回填 + 语音消息三期 + 多语言 P3 + 应用内多语言全量迁移（从 current_task.md 移入，均已完成/已推送/已真机验证，无未结下一步指回它们）

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

## 2026-09-28 语音消息 P2 录制 + code review 修复 + 两个真机 bug 修复

> **语音消息 P2 录制 ✅（OPPO 真机实测核心手势）**：按设计稿 `VOICE_MESSAGE_DESIGN.md` +
> 草图 v2.5 分三期，① 播放已于同日早些时候完成；这次是 ② 录制。新增 `voice/VoiceRecorder.kt`
> （AAC-LC 单声道 16kHz 24kbps；暂停=收尾当前段可试听、继续=新起一段，`voice/VoiceSegments.kt` 用
> MediaExtractor/MediaMuxer 按时间戳顺延拼接）与 `ui/voice/VoiceRecordUi.kt`（手势/悬浮层/锁定行/事件
> 分派）。手势判定全落在 `VoiceRules.kt` 的纯函数上（`lockPhase`/`cancelReady`/`slideHint`/
> `countdownSeconds`/`encodeWaveform`/`amplitudeOf`/`amplitudeByte`，`VoiceRulesTest` 新增 7 例先见红）。
> UI 用 `awaitEachGesture` 手写按下/拖拽/松手（不用 `detectDragGestures`，因为要**从按下瞬间**而非
> 越过触摸容差才响应）；手势节点在按住→录制（未锁定）期间**必须是同一个 Composable 调用点**——
> 一旦被外层 `if/else` 分支切换成别的位置，Compose 会把正在追踪的那次拖拽连带协程一起销毁重建。
> 输入栏 🎙/➤ 原地换脸（空输入显 🎙）；按住大圆钮 58dp 跟手+振幅呼吸环；锁钮 36×52dp 固定在按下点
> 上方 86dp（70dp 高亮/34dp 到位即锁，含"手指飞过锁钮"兜底）；左滑 40% 行宽取消（提示文字位移×0.4
> +渐隐 1.0→0.2，过阈值整行转红居中「松开 取消」）；锁定行 🗑|胶囊(录制中跑马灯波形/暂停后迷你播放器,
> 复用既有 `VoicePlayer.toggleFile` 试听)|⏸⇄▶|➤；5min 双态（按住到点转锁定暂停等用户决定/锁定到点
> 自动发送+toast）；切后台（`MainActivity.onStop`）/离开会话页（`PauseRecordingOnLeave`）均转中断暂停。
> **顺手修了一个真实 bug**：`MessageService.resend` 原先按 `repo.inFlight()`（只挑 `state=Sending`）
> 找待发行——但点红❗重试要找的正是 `Failed` 状态那一条，**这条判据错了导致点重试完全无效**（点了跟
> 没点一样，之前没被发现是因为没人真的手测过这条路径）。改成按 `clientMsgId` 直查
> （`MessageRepository.pendingByClientId`，不按状态过滤)；顺带堵上「语音重传前先复位 Failed→Sending」
> （`markPendingSending`）与「图片/视频本地 uri 找不回字节时改标失败而不是误发 `content://` 给对端」两处。
> **未做 / 已知限制**：5min 上限两种处理与中断转暂停只做了代码走查；发送成功后本地 `voice_pending/`
> 缓存文件不清理（TODO，需要等到确认 ack 而不是仅仅"上传成功"才能安全删）；未验证 RECORD_AUDIO
> 被拒绝的真机提示（测试机早已授权，测不出"首次请求"/"被拒绝"两条分支）；表情面板本端本就没做。
>
> **`/code-review --fix` 抓出 8 条、已修 7 条**：① `VoiceRecorder` 硬闸（`setMaxDuration`）与软闸
> （tick）目标时长相同、硬闸几乎总赢，此前硬闸直接 `finish(ReachedMax)` 会被 UI 当 `UserSend`
> 处理——**按住态到点转锁定暂停从没真正生效过，一直被强制发送**；改成硬闸也走 `pause()`+统一的
> `Event.ReachedMax` 通知，两条闸门收敛到 `VoiceRecordEvents` 一个决策点；② `resendInFlight`
> （重连自动补发）漏了语音的本地文件特判、且没透传 `waveform`，与手动 `resend` 不对称，已同步补上；
> ③ `MediaSendFlow.sendVoice` 读本地文件没有 try/catch，读失败会让协程崩溃、待发行卡死在"发送中"，
> 已补上（对齐 `reuploadVoice` 的处理）；④ `reuploadVoice` 的 `catch (e: Exception)` 吞了
> `CancellationException`（违反 CODING_STYLE §5），已改成先接重抛；⑤ 麦克风"问过一次"标记另开了
> 一份裸 `SharedPreferences`，改用既有 `PrefsVoiceKv`；⑥ `encodeWaveform` 与 `data/Waveform.bars`
> 重复实现了同一套按桶取最大值下采算法，改成复用；⑦ `finish()`/`discard()` 重复五步收尾逻辑，抽了
> `teardown()`。**跳过 1 条**：`MediaRecorder.prepare/start/stop` 在主线程同步跑，短语音实测无感知
> 卡顿，改成异步要重构调用形态、牵动 UI 层多处调用点，风险与收益不对等，留作后续单独任务。
>
> 用户随后真机复测又报 2 条真实 bug，均已修：⑨ **上滑锁定看不到锁钮反馈**——`VoiceHoldOverlay`
> 套了层 `Box(Modifier.size(0.dp))` 当"零尺寸锚点"，指望子项都用负 offset 飘出去；实际 Compose
> 量这层 Box 时把 `(0,0)` 的约束**连带传给了子项**，子项 `.size(58.dp)`/`.size(36.dp,52.dp)` 被
> 顶成 0×0——大圆钮、锁钮**真机上从头到尾都没画出来过**（不是被挡住，是根本没渲染；adb
> `input motionevent DOWN/MOVE/UP` 分步摆拍验证的，`input swipe` 一步到位摆不出中间帧）。改成
> `VoiceHoldOverlay` 是 `BoxScope` 扩展、两个子项直接画在调用方（`ComposerBar`）那个本来就有真实
> 尺寸的 `Box` 里，不再单独包一层；顺手把锁钮的合成顺序挪到大圆钮之后（原来圆钮跟手飘到锁钮位置
> 会整个盖住锁钮，恰好盖住最需要看反馈的那一刻）。⑩ **语音发送先闪一下文本气泡再变语音气泡**——
> `Bubbles.kt` 的 `isMedia` 判据是 `msg != null && msg.contentType in MEDIA_TYPES`，**没有 `msg`
> 时（ack 落地前的待发行）恒为 false**，于是待发语音落到最后的 `else -> Text(...)` 分支，画出一条
> 写着 `file:///.../xxx.m4a` 的文本气泡，ack 落地后才"跳变"成正常语音气泡——图片/视频/文件早年
> 就在 `PendingBubbles.kt` 踩过同一个坑，唯独语音这次新增漏补。新增 `PendingVoiceBubble`（播放走
> 本地文件预览 `VoicePlayer.toggleFile`），`ChatRowView.kt` 补 `isVoice` 分支。
>
> `./scripts/test.sh` 全程保持绿（最终 934/934）。真机（OPPO）adb 摆拍全部验证过：按住原地松手
> 发送、左滑取消、上滑锁定+锁钮反馈不被遮挡、锁定行暂停/试听/试听播放、删除二次确认、松手直接进
> 语音气泡（不再闪文本）。commit：`feat(voice): 语音消息录制（第 2 期）+ code review 修复`。

## 2026-09-27 通话记录：被叫侧 cancel 文案改「对方已取消」

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

## 2026-09-27 应用内多语言：基础设施 + 两个试点（已被全量迁移取代）

> **应用内多语言：搭基础设施 + 迁两个试点模块（语言设置页、登录页），复用 iOS 现有翻译（2026-09-27）**：
> 接上一轮「im-rtc 2.1.0 通话 Kit 多语言」之后，这轮把本端应用自己的界面文案也接上了
> `IMServer/docs/i18n/strings.json`（`scripts/i18n/targets.json` 的 Android 目标 `enabled` 已从 `false`
> 翻正、跑生成器产出 `res/values(-en)/i18n_strings.xml`）。运行时切换靠**直接调平台
> `LocaleManager.applicationLocales`**（`data/LanguageStore.kt` 的 `setPref()`/`init()` 里调，API 33+，
> 本端 minSdk 26 故 <33 设备暂不支持免重启切换），不用像 iOS `IMLocalization` 那样手写 bundle 查表。
> ⚠️ **真机踩坑记录（2026-09-27，Android 15/API 35 OPPO 机型实测才发现）**：一开始按 androidx 官方文档
> 用的是 `AppCompatDelegate.setApplicationLocales`（配 `androidx.appcompat` 依赖 +
> `AppLocalesMetadataHolderService`），编译、单测全过，但**真机上点了完全没反应**——`adb shell cmd
> locale get-app-locales` 恒为空、调用本身不抛异常。改成直接调 `Context.getSystemService(LocaleManager
> ::class.java).applicationLocales = ...` 后当场生效（同一台机器验证）。怀疑是本端全仓没有任何
> `AppCompatActivity`（`MainActivity` 是 `ComponentActivity`）导致 `AppCompatDelegate` 内部拿不到有效
> 引用，但没有去读 androidx 源码坐实，只确认了现象与解法。**已改用直连方案，`androidx.appcompat`
> 依赖与相关 manifest service 已移除**（不必要的依赖，且不生效）。另外**必须**声明
> `android:localeConfig="@xml/locales_config"` + `res/xml/locales_config.xml`——没有这个文件时
> `LocaleManager` 的调用同样悄无声息地不生效，这是 Android 13+/targetSdk 34+ 的硬性要求。
> **已在真机上完整走通全流程并截图确认**：「我」页语言行→语言设置页选 English→Activity 重建→
> 「我」页与语言设置页文案变英文（含 `settings.language.current_system` 占位符渲染）；退出登录→
> 登录页确认按钮/输入框标签/tab 全部变英文；免密登录（开发）登回同一账号验证会话数据无损；
> 切回简体中文全部复原。**试点一**：语言设置页（`LanguageScreen.kt`/`MeScreen.kt` 语言行）直接复用刚好现成的
> `settings.language.*` 键；`LanguageStore` 里手写的 `displayName()`/`currentLabel()` 挪到 UI 层
> （`ui/screens/LanguageScreen.kt` 的 `languagePrefDisplayName()`/`languageCurrentLabel()`，因为要用
> `@Composable` 的 `stringResource()`，`data/` 层不该依赖 Compose）。**试点二**：登录页
> （`LoginScreen.kt` 的按钮/输入框标签、`LoginError.kt` 的错误文案）复用 `login.*`/`err.*` 键；
> **刻意不复用**的两处都有明确理由——① 密码错误文案不改用共享表的 `err.200002`（"密码错误"，会暴露
> "用户名对了只是密码错"），保留本端已有的用户名枚举防护措辞"用户名或密码错误"；② 登录页两个页签是
> "登录/注册"（按操作分），与 iOS 的"密码登录/扫码登录"（按登录方式分）信息架构不同，不强行拉齐，
> 新增了一个 Android 专属键 `login.tab_register`。另新增 Android 专属键
> `settings.language.footer_call_only`（语言页脚注，因为本端还没做全量迁移，措辞与 iOS 的
> `settings.language.footer` 不同义）。`LoginError.friendly()` 改吃注入的 `Strings` 接口而不是直接吃
> `Context`——本仓 JVM 单测没接 Robolectric，真 `Context.getString` 在纯 JUnit 里会因为 `android.jar`
> 是桩实现直接抛异常，`LoginErrorTest.kt` 已按新签名改写并核对文案表当前值。
> `./scripts/test.sh` 全量 **891/891 绿**；`IMServer` 的 `node scripts/i18n/gen-i18n.mjs --check`
> 与 `check-i18n.mjs`/`i18n.test.mjs` 全绿（新增的两个 Android 专属键按预期报"暂无端上引用" warn，
> 不是 error）。**范围收窄**（用户已确认这轮只做这些）：其余 281 个含中文字面量的 `.kt` 文件仍未迁移，
> 量级与 iOS 当年的 P2 相当，留作后续多轮任务；`check-i18n` 的 `sources` 漂移扫描也还没接 Android
> （`R.string.foo_bar` 下划线转回点号键需要额外映射，留到全量迁移时做）。


# current_task 归档（只读）

> `current_task.md` 是**一屏活快照**，超出的历史焦点块移到这里，**不再回流**。
> 更细的过程见 `git log`。首次归档：2026-09-08（当时 current_task.md 已涨到 458 行）。

## 历史焦点（新 → 旧）

> ⬇ 以下一块 2026-09-27 从活快照原样转入（im-rtc 音视频 SDK 2.0.0→2.1.0 三端版本升级），被通话记录 cancel 文案细化顶下。

> **im-rtc 音视频 SDK 2.0.0 → 2.1.0（三端同步，2026-09-27）**：`gradle/libs.versions.toml` 的
> `imrtc` 版本号改为 `2.1.0`（`imrtc-uikit`/`imrtc-webrtc` 都走这个 `version.ref`，唯一改动点）；
> JitPack 侧 `com.github.BLiYing.im-rtc-android` 的 `2.1.0` tag 已存在，`./scripts/test.sh` 验证过
> 能正常解析下载（`~/.gradle/caches` 里已落 2.1.0 的 aar/module/pom）并编译通过。同批联动改了
> iOS（`IMProgram`，SPM `im-rtc-ios` exactVersion）与 Web（`im-web`，`im-rtc-call-engine`/
> `im-rtc-call-uikit-react` npm 依赖）。`./scripts/test.sh` 全量 **886/886 绿**。
> 未做真机验证通话功能本身（SDK 内部行为改动未知，只验证了版本号解析与编译）。

> ⬇ 以下一块 2026-09-27 从活快照原样转入（三项用户反馈：系统通知会话详情页 / 日历「最早」跳转 / 群成员搜索调研）。

> **三项用户反馈处理完（2026-09-24）**：
>
> 1. **系统通知会话「聊天信息」页对齐 iOS/Web**：用户报 tab 控件和备注名不该出现。根因是
>    `ChatDetailScreen.kt` 的备注名卡/设置卡/页签条只受 `galleryOnly` 门控，没接 `isSystemPeer`——
>    顶部操作排/更多菜单早就经 `DetailActions.pillsFor/moreFor` 收窄了，正文三块没跟上。参照 Web
>    `DetailPanel.tsx` 的 `isSystemPeer`/`showDetailBody` 分支：新增 `isSystemPeer` 入参，备注名/
>    设置/页签整段隐藏，换成一段说明卡（"这是官方通知会话，用于发送登录提醒、账号安全等系统事件。
>    你不能回复此会话。"）；`ChatDetailHost.kt` 传入 `DetailActions.isSystemPeer(conv.peerUid)`。
>    `./scripts/test.sh` 886/886 绿；真机（`GMGY7XF6LBJB6PFU`）验证：系统通知会话「聊天信息」页
>    只剩头像/名字 + 「更多」+ 说明卡，备注名/设置卡/相册-文件-链接页签条均已不见。
> 2. **日历圆点 + 「最早」跳转**：
>    - **圆点变多不是 Android bug**——iOS `searchCalTapped` 请求 730 天日历窗口，超过服务端
>      `MaxCalendarSpan`（约 400 天）硬上限，请求恒被拒绝、静默回退成"仅本地打点"，iOS 的圆点
>      从来没真正包含过服务端补的历史；Android 用 390 天（刻意卡在限内）所以服务端合并总能成功，
>      画出的反而是更完整正确的点。**这是 iOS 端的欠账，需另行找 iOS 端修，本端不用往回改**。
>    - **「最早」点击没反应：真实 bug，已修**——`pickEarliest` 此前直接 `onLocate(1L,...)`，走的是
>      "定位到具体某条"的通用路径，把服务端 `anchor_found=false` 当"消息真没了"直接拒答；但
>      conv_seq=1 常常不是自己能看见的消息（系统事件/入群前历史），服务端答 `anchor_found=false`
>      但仍带回"我能看见的最早一段"，通用路径误判成失败。新增 `ChatLocator.locateEarliest()`
>      （镜像 iOS `requestServerWindowAnchor:isJump:earliest:`，忽略 `anchor_found`）：本地已握最早
>      则直接开窗；没有则问服务端要一窗，**落库后重查本地最早、不看 anchor_found**，再开窗；离线/
>      超时退化到本地已知最早并明确提示"网络未连接，已跳到已下载的最早一条"（`ChatWindows` 新增
>      两条文案常量）。`./scripts/test.sh` 886/886 绿；真机（`GMGY7XF6LBJB6PFU`，"20000人大群"，
>      conv_seq=1 是系统事件）验证：点「最早」能看到 `window_resp` 往返，落到真正的会话最早附近。
> 3. **群成员搜索 / 日历消息搜索分页现状**——只调研未改代码（用户明确要求先不动）：
>    - **群成员搜索是真实、未登记的功能缺口**：`GroupApi.members(convId, cursor, q, limit)` 早支持
>      `q` 关键字分页搜索，但群资料页"成员"tab 从没调用带 `q` 的版本（只有 `MentionComposerState`/
>      `RtcInviteProvider` 两处用了）——大群里成员 tab 没有搜索入口，只能滚动翻页找人；iOS 有专门的
>      `IMGroupMemberSearchViewController`。已记入下一步 0d。
>    - **日历不是分页缺口**：本地打点无界查全部历史，服务端固定开约 390 天窗口，两端都是"固定窗口"
>      设计，不是"分页翻页"，属合理取舍、非缺陷，不需要新 TODO。

> **搜索日历圆点标记补上 + 上一批（09-23）真机回归清单走完（2026-09-25）**：用户报「搜索时日历点开，
> 有消息的日期下方缺圆点，iOS 有」——查 `ChatCalendarDialog.kt` 头注释，此前是刻意决定：Material3
> `DatePicker`（1.3.x）没有逐日装饰的公开钩子，做圆点等于整块自绘一份日历，此前判定"圆点是锦上添花，
> 核心正确性已经做对"没做。这次改判：自绘月历网格替换 `DatePicker`——新增
> `MessageDao.activeLocalDayStarts`（镜像 iOS `activeLocalDayStartsInConv:utcOffsetMs:`，公式与
> `ChatCalendar.dayStartMs` 逐字一致）；`ChatCalendarState.kt` 打开弹层时本地打点恒查一遍（离线/有缺口
> 也能画部分点），有缺口且在线时再并入服务端打点；`ChatCalendarDialog.kt` 整个换成 `IMCalendarGrid`
> 自绘（月份翻页 + 周日起头 7 列网格 + 选中圈 + 打点小圆点），查表 key 与跳转坐标共用同一份
> `ChatCalendar.dayStartMs(noon, offset)`，三处不会错位。`./scripts/test.sh` 886 例绿；真机
> （`libeyond群`）验证：星期对齐正确（9 月 1 日落在"二"列）、圆点位置对（5/10/11/13/16/17/20/21/23 号）、
> 选中态圆点仍可见、点圆点日期正确跳转到那天、月份翻页正常。
>
> 随后按用户要求把 09-23 那批"没装机"的真机回归清单（详情页宫格门控点击下载、文件行图标位/进度环、
> 收藏页页签与长按菜单、收藏转发到会话后收端封面/尺寸、长按「收藏」后收藏页出现、从收藏发送的勾选/
> 底栏/回滚/返回键分层）逐条在真机走了一遍，**全部通过**。唯一发现的边界情况（非本轮改动引入，记入
> 已知坑）：合并转发记录里的名片 → 资料页 →「发消息」，`ChatHost.kt` 把 `onOpenUser` 接到同一份
> `openUser` 状态，「发消息」只 `onCloseUser()` 关资料层，退回到记录页而非直接落回聊天——名片指向的人
> 恰好就是当前会话对方时不明显（多按一次返回就到了），指向别人时会更明显（发消息形同无效，因为
> `ChatHost` 绑死单一 `convId`，没法就地换会话）。

> ⬇ 以下一块 2026-09-25 从活快照原样转入（启动图标/通知头像/新建群聊/扫码页面对齐 iOS）。

> **App 启动图标 + 系统通知头像 logo + 新建群聊页面 + 扫码页面，四项对齐 iOS（2026-09-23，同日第二轮；
> 纯客户端、后端零改动；`./scripts/test.sh` 886 例绿；四项均已装真机 `GMGY7XF6LBJB6PFU` 截图验证**）：
> 用户按顺序报了 4 条。
>
> ① **App 启动图标**：此前是占位矢量（绿底白气泡，注释写着"正式图标待 UI 定稿后替换"），换成与 iOS
> `AppIcon.appiconset/IMAppIcon.png` 同源的深蓝底彩色 logo——生成 `mipmap-{m,h,xh,xxh,xxxh}dpi/
> ic_launcher_foreground.png`（背景色 `#000317` 采样自源图，居中留白避免被自适应图标遮罩裁边），
> `ic_launcher.xml`/`_round.xml` 的 foreground/monochrome 改指向新 PNG，删掉旧占位矢量。真机桌面截图确认。
>
> ② **系统通知会话头像**：`IMAvatar`（`ui/components/Avatar.kt`）此前没有 uid==system 特判，
> 会跟其他账号一样落网络请求/首字母兜底；新增分支复用既有 `DetailActions.isSystemPeer`，直接显示
> 新增的 `res/drawable-nodpi/im_system_logo.png`（同一份 logo 源，来自 `im-web/public/im-logo.png`），
> 不发网络请求、不落首字母兜底——契约对齐 iOS `LaunchLogo`/Web `/im-logo.png`。真机会话列表截图确认。
>
> ③ **新建群聊页面**：此前是单页「名字框 + 好友勾选列表」，缺头像圈、群名字数上限/计数、自动预填、
> 搜索、A–Z 索引——都是 iOS `IMGroupCreateViewController` 有而本端缺的，且此前未登记进 SYMMETRY。
> 新增 `data/GroupNameDefault.kt`（移植 iOS `IMGroupNameDefault.m` 的 rune 计数/截断/默认群名拼接，
> +7 例单测先红后绿）；`GroupApi.create()` 补 `avatar_url`；`CreateGroupScreen.kt`/`CreateGroupHost.kt`
> 加头像圈上传、n/30 计数、搜索框、复用 `ContactSection`/`ContactIndexBar` 做索引、已选人数副标题、
> 按已选成员+本人昵称自动预填群名（手改过不再覆盖）。**刻意未对齐**：iOS 建群分两步（先选人页、再头像/
> 群名页），本端保留单页——拆两步改动面和回归风险都更大，体验差异对用户不明显，理由写在 `CreateGroupHost.kt`
> 头注释。真机走通「＋→新建群聊→勾好友（预填+截断正确）→建群→进新群聊」全流程。
>
> ④ **扫码页面**：`QrScanHost.kt` 本就是较完整的 iOS `IMQRScannerViewController` 移植（L 形取景框/
> 手电筒/相册识码一图多码候选/权限拒绝引导都已对齐），本次揪出两处真差：a) 顶栏标题此前用
> `Row+SpaceBetween` 只摆了左右两颗按钮，没放"扫一扫"标题，导致标题不存在（不是没居中，是压根没画）——
> 改 `Box+Alignment` 三点定位；b) 取景框此前是静止的，iOS `startScanLineAnimation` 有一条 2.2s 循环
> 上下平移的蓝色扫描线（`#5CC7FF`），本端漏了，补上 `rememberInfiniteTransition` 版本。真机截图确认
> 标题居中、扫描线动画在跑、手电筒图标能切换亮灭态（相机取景本身是纯黑——设备镜头当时朝向暗处，
> 不是代码问题，手电筒切换生效证明相机管线是活的）。**刻意未对齐**：iOS 扫码页与「我的二维码」是
> 同屏两个页签，本端「我的二维码」是独立入口——不重复做同一功能，见 `docs/UI_PARITY_IOS.md`。
>
> **另外核实**：用户同时问的「图片/视频转发逻辑对齐」（原第 2 条）——审计后确认转发交互链路
> （长按→菜单→选目标→单选/多选→发送→已转发回显）已经与 iOS 完全对齐，**未改代码**；唯一差异是
> iOS 整页 push、Android 卡片式 `IMCardSheet`（实测视觉已铺满全屏），判定为既有平台设计差异非 bug。

> ⬇ 以下一块 2026-09-23 从活快照原样转入（会话列表副标题 + 转发提及 + 日历/来自筛选）。

> **会话列表副标题 + 图片/视频转发提及 + 会话内搜索日历/来自筛选，三项对齐 iOS（2026-09-23；纯客户端、
> 后端零改动；`./scripts/test.sh` 879 例绿，新测试均先看红过；**真机 `GMGY7XF6LBJB6PFU` 已回归日历/来自
> 两项，抓到并修了 2 处只有真机才炸的 bug，见下**）**：用户按顺序报了 5 条，1/2 两条
> 核实后确认现状已对，未改代码（宫格门控图片有进度环/暂停/重试比 iOS 丰富，用户拍板保留；九宫格翻页
> 装的是旧包，装最新包后确认无问题）。3/4/5 三条落地：
>
> ⚠️ **真机抓到的 2 处 bug（单测全绿但没测出来，Room 生成的真实 SQL 本仓没有任何测试跑过）**：
> ① `ChatCalendarDialog` 的「最早/今天」快捷行被 `DatePicker` 整个盖住、连无障碍树都摸不到——
> 根因是 Material3 `DatePickerDialog` 内部用 `Box` 装 `content()`，多个直接子项会互相叠放而不是纵向
> 排列（`content: ColumnScope.() -> Unit` 只是给了 `Modifier.weight()` 用的接收者类型，不代表真的按
> Column 布局），显式套一层 `Column` 修复；② `MessageDao.search()` 的 SQL 里，关键词 LIKE 那组 `OR` 
> 子句原来是**无条件 AND** 的——纯「来自」筛选（空关键词）时 `:like=''` 会让整组 OR 恒假，「来自」
> 恒 0 命中，补一条 `:like = '' OR (...)` 短路分支。两处都已修复、重装真机复验通过（「最早」跳到群
> 创建那条、「来自」筛选出 2/2 命中并跳转、清除胶囊无崩溃）。
>
> ⚠️ **真机继续验的 3 项结果（同日第二轮）**：① 撤回实时刷新——群聊/单聊各自撤回一条自测通过，
> 列表行不重进就跟着换成"你撤回了一条消息"；**对方撤回的两种文案（"XX/对方撤回了一条消息"）没测到**，
> 单设备单账号模拟不出"对方"视角，留着等第二台设备/账号；② 非 UTC+8 时区的日历换算——把设备时区切到
> `America/Los_Angeles`（UTC-7）复验，"今天"指示器正确显示本地日期（22 号而非 UTC+8 的 23 号）、
> 「今天」快捷跳转精确落在本地日期分界线上、昨天/今天分组随时区重算，**换算是对的**；③ 转发带 @ 到群——
> **真机 UI 没跑通**：图片选择器是系统多级页面（选图→分享目标选择→疑似另一条转发链路），盲点坐标几次
> 踩偏（意外把测试字符串当消息发出、意外打开系统文件选择器），继续磨真机 UI 投入产出比不划算，改用文本
> 消息里插入 @ 提及验证了"提及渲染成可点高亮 + 点开对方资料页"这条基础设施是通的；`mentions`/
> `mentionSpans` 随转发存活这条**只有单测覆盖**（`ForwardAttributesTest.kt` 两个新例，红绿都验证过），
> 没有"转发到另一群、别人视角点开被 @ 的名字"这一步的真机实测——如实记录，不算已完成。
>
> ⚠️ **"来自: 用户4984" 命中「群通话已结束」——查证不是 bug**：通话记录消息设计上"发起人 = 消息
> 发送者"（不是系统消息），iOS `searchConvSeqsInConv:keyword:fromUID:` 的「来自」SQL 同样不排除通话
> 记录，只排撤回与 `conv_seq<=0`——两端行为一致，是既有设计，不是这次对齐引入的偏差。如果要把通话记录
> 从「来自」筛选里去掉，是一个产品层面的新决定，需要 iOS/Android 两端一起改，不在这次对齐范围内。
>
> ⚠️ **iOS 日历 730 天那个 bug 已代码核实为真**：`IMChatViewController+Search.m` 请求
> `toMs - 730*24*3600*1000`（近两年），服务端 `MaxCalendarSpan=400*24h`，超限直接拒绝；iOS 收到失败后
> 静默回退本地打点，用户感知不到。这是 iOS 既有 bug，与本次 Android 对齐无关，Android 未照抄（改用 390 天）。
>
> ③ **会话列表副标题**：`ConversationEntity` 加 `lastFrom`/`lastFromNickname`/`lastRecalled` 三列
> （Room v9→v10 migration），新写 `data/ConversationPreview.kt` 在**渲染时现算**（不再是写库那一刻烤死的
> `lastContent` 直接显示）——群聊文本/媒体一律带"昵称: "前缀（自己发的显"我"，系统消息与 `lastFrom` 为空的
> 不加，对齐 iOS `lastPreviewTextForSelfUID:` + 列表 cell 的"who 解析不出来就不包前缀"退化路径）；本地收到
> `msg_op RECALL` 时 `MessageRepository.applyMsgOp` 顺带把命中的会话行 `lastRecalled` 置 true，列表跟着
> 实时换成"你/XX/对方撤回了一条消息"（此前撤回后列表停在撤回前的原文，不刷新）；`ConversationRow` 补上
> 单聊已读双勾 ✓/✓✓（`peerReadSeq >= lastConvSeq`，此前这个字段存了但没画）。
>
> ④ **图片/视频转发**：审计发现 `Forward.attributesOf` 漏带图说（caption）里的 @ 提及——`mentions`/
> `mentionSpans`，此前只带 poster/media_w/media_h/duration/waveform 五项。已补，**只在图片/视频上带**
> （对齐 iOS `forwardAttributesForMessage:stripCaption:` 的 `isMedia` 判据，纯文本消息本就不转发提及）、
> 不带 `mentionAll`。⚠️ **归档/收藏两个转发入口恒不带**——`ConvMediaItem`/收藏条目服务端本就不回带
> `mentionSpans`，与已知的 `waveform` 缺口同一类结构性限制，不是这次漏改（SYMMETRY 已记）。顺手订正了
> `Forward.kt` 里一句过时注释（`SendMsgData` 早就有 `waveform` 字段，注释还写"协议没有"）。
>
> ⑤ **会话内搜索 📅 日历跳转 + 👤 来自筛选**（`UI_PARITY_IOS.md` 里挂了很久的 🔴，本次一并收口）：
> 新增 `data/ChatCalendar.kt`（本地日分桶公式逐字镜像后端 `ConvDayBuckets`）+ `ConversationsApi.calendar()`
> （对接既有的 `GET /conversations/{id}/calendar`，⚠️ **没有照抄 iOS 的"近两年"跨度**——那会撞服务端
> `MaxCalendarSpan`≈400 天的上限被拒，iOS 那个请求实际上一直失败，是 iOS 侧的既有欠账，本端改用 390 天）；
> `ui/ChatCalendarState.kt` 三态判据复用既有 `ChatSearch.pickSource`（本地齐全只查本地／有缺口+在线问服务端／
> 离线降级一次性提示）；UI 用 Material3 `DatePicker`（不画"有消息的天"装饰点——那只是锦上添花，核心是
> "跳对不跳错"这条正确性，已经做对）；「最早」直接 `locate(1)`、「今天」查不到就退到会话最新一条并如实
> 说退了。「来自」筛选：本地 DAO `search()` 加 `fromUid` 参数（空词+有筛选时仍成立，"与"关系）、服务端
> `searchMessages(from=)` 参数原来就在但从没传过非空值；候选 = 本会话已发过消息的去重发件人（不是群成员表，
> `MessageDao.distinctSenders`），面板贴在命中导航条上方。**均为纯客户端改动**，服务端两个接口都已就绪。

> **群聊信息页 / 群管理页与 iOS 对齐，共四批（2026-09-22；纯客户端为主、第四批服务端零改动；`./scripts/test.sh` 852 例绿；未真机）**：
> 用户报告「群资料/群管理与 iOS 差得多」四点，先审计（全文对比 iOS `IMChatDetailViewController`/`IMGroupManageViewController`
> 与本端 `GroupInfoScreen`/`GroupInfoHost`/`GroupManageScreen` 等全部相关文件，结论与仍开着的口子见
> [`docs/UI_PARITY_IOS.md`](docs/UI_PARITY_IOS.md) §2/§3 新增行）。
> **第一批**（三项真 bug/体验落差）：① 单聊「备注名」行此前先跳整页 `UserProfileHost` 才能编辑，现改页内弹窗直接编辑
> （`RemarkEditDialog`，与用户资料页共用）；② 群成员长按（禁言/解除禁言/设撤管理员/转让群主/移出群聊）**执行后成员
> 列表不刷新**（角色徽标、🔇 标记、被移出的人都要退出重进才更新）——对齐 iOS 每个动作后都重拉一次，`GroupInfoHost.runManage`
> 现在统一重拉成员首页；顺带修了禁言徽标判据（`!= 0L` → `GroupPermissions.isMuteActive`，过期时间戳误判为禁言中）；
> ③ 群管理页群名称/简介/公告三行现在右侧直接预览当前值。
> **第二批**（用户从审计清单里选了这三项，跳过群二维码/邀请链接）：④ 群资料页新增 Settings 卡——置顶聊天/消息免打扰
> （复用会话设置接口，新增 `ConversationsApi.settings` 对称 GET）、我在本群的昵称（接回此前的孤儿 API
> `GroupApi.setMyNickname`）、群备注（新增 `ConversationsApi.setRemark`，`PUT /conversations/{id}/remark`——后端早有、
> 本端一直没调），状态持有者拆进 `GroupInfoSettings.kt`（贴 600 行硬闸，拆法见 CODING_STYLE §7②）；
> ⑤ 群公告/群简介从合并卡片拆成两个独立行，各自非空才显示、摘要 3 行、点开弹 `GroupTextViewDialog` 看全文。
> **第三批**（用户随后要求补齐前两批留下的两个口子，2026-09-22 同日；`test.sh` 845 例绿；未真机）：
> ⑥ 群简介/群公告编辑框的 `multiline` 参数此前是死代码——`IMTextPrompt` 从未把它接到底层 `IMTextField`，
> 200/500 字的编辑框实际一直是单行框；`IMTextField` 补 `singleLine` 开关（多行 3~8 行）修复，同时给
> `IMTextPrompt` 加 `clearActionText`，群公告用它对齐 iOS 的独立「撤下公告」按钮（此前只能靠清空文本框）。
> 字数上限/计数器本就已对齐（30/200/500），容器仍保留弹窗、不做 iOS 那种全屏专属页（功能已对齐，体量不值当）。
> ⑦ **群二维码/群邀请链接入口补齐**：`QrApi` 新增 `groupQR`/`resetGroupQR`（对接现成的
> `GET/POST /api/v1/groups/{id}/qr[/reset]`），`QrCardScreen` 从「只服务个人名片码」泛化为名片码/群码共用
> （`title`/`subtitle`/`hint`/`onReset` 全参数化），新增 `GroupQrCardHost` 复用个人码那套亮度提升/存相册/
> 分享/复制链接/重置二次确认；群资料页 Settings 卡新增两行，门控用 `GroupPermissions.canInvite`（与
> 「邀请好友入群」卡片同一份判据，对齐 iOS `inviteEntriesVisible`）。**仅接「出示」这一半**——扫码识别/
> 点击邀请链接后的接收方解析加群流程（iOS `IMQRResultRouter`/`IMGroupJoinPreviewViewController`）需要相机
> 权限 + App Links 深链接入，工作量显著更大，明确未接，留作下一批。
> 为不撞 `GroupInfoHost.kt` 600 行硬闸，顺手把 `loadMore` 挪到 `GroupMembersPaging.kt`、语音页签发送者名
> 逻辑挪到 `data/SenderNames.kt` 的 `groupVoiceSenderNameOf`（594/600，留了 6 行余量，下次加东西前建议先规划
> 再拆一块，比如把治理三页 Pick/Bans/Admins 收进 `GroupGovernanceHost.kt`）。
> `/code-review` 复查无 correctness 级问题，按其建议补了 `GroupInfoNavTest`/`SenderNamesTest` 两条用例
> （新增测试先红后绿验证过）、修正 `groupVoiceSenderNameOf` 的 `myUid` 类型（`String?` 保持与被替换的内联
> lambda 逐字等价）、统一了群码副标题文案（"位成员"→"人"，与详情页头部一致）。
> **已知差异（低优先级，未处理）**：iOS 点击「群二维码/群邀请链接」行时会二次判权限、不满足直接吐司拦截，
> 本端只有行级门控——权限过期的边界情况会先进页面再看到服务端 403 报错，不算 bug（服务端仍是唯一权威闸门）。
> 详见 `UI_PARITY_IOS.md`。
>
> **第四批：扫码/点链接加群——接收方这一半补齐（2026-09-22 同日；纯客户端、`./scripts/test.sh` 852 例绿，
> 新测试均先看红过；已提交 `2853a52`、未真机）**：服务端 QRCODE P0/G3 早就全量落地（`/qr/resolve`、`POST /groups/join`
> 等接口 2026-08-13 起就在），此前只有 Android 客户端没接扫码/点链接这一侧。全文核对 iOS 真正生效路径
> （`IMQRResultRouter.m`/`IMQRScannerViewController.m`/`IMGroupJoinPreviewViewController.m`/`IMQRModels.m`）后发现
> **iOS 也没有 OS 级深链接（App Links）**——它只在 App 内已经打开的链接（聊天气泡、群资料/收藏里点开的链接）
> 里拦截本站邀请链接，不是靠系统把外部浏览器打开的链接拉起 App；范围据此收敛，不用碰 AndroidManifest 的
> deep link 配置。落地：① `QrApi.resolve()` 手动解 `{kind,data}`（新增 `QrUserCard`/`QrGroupCard`/`QrResolved`
> 密封类），`GroupApi.join(token, hello)` 接 `POST /groups/join`；② `data/QrActions.kt` 纯映射函数（relation/
> joinable/reason → 按钮态，对齐 iOS `IMQRModels.m`/Web `qr.ts` 同一张判据表，全部单测覆盖）；③ `data/WebLinks.kt`
> 新增 `isOwnInviteLink`（host+端口匹配 + dev 回环例外 + 路径命中 `/q/u|g/`，对齐 iOS `routeInviteLinkIfOwn:`）；
> ④ 新增 `ui/QrRouteHost.kt`：挂在 `MainScreen` 内部（不是更外层的 `AppRoot`）——它要改 `openConv` 才能进群聊/
> 单聊，那份状态是 `MainScreen` 的私有变量。**覆盖**外层 `WebLinkHost` 提供的 `LocalOpenLink`：点一条链接先判
> 是不是本站邀请链接，是就 `resolve`+路由，不是就退回原来那份（真正的浏览器打开）；另提供新 CompositionLocal
> `LocalOpenQrScan` 给会话列表 ＋ 菜单的「扫一扫」用（此前是 `toast = "扫一扫还没做"` 的占位）；⑤ `ui/QrScanHost.kt`：
> CameraX 出帧 + zxing-core（`QRCodeReader`，出示码那半已引入的同一个依赖，未叠 ML Kit）解码，只吃 Y 平面、
> 手动处理 rowStride/pixelStride 避免部分机型图像被拉斜；新增 `CAMERA` 权限 + `uses-feature required=false`；
> ⑥ `ui/GroupJoinPreviewHost.kt`+`screens/GroupJoinPreviewScreen.kt`：对齐 `IMGroupJoinPreviewViewController`
> （头像/人数/邀请人/简介 + 需审批时附言框 + 按准入态变文案/可用态的主按钮）；提交回调**先把预览页从组合里摘掉、
> 再异步发 join 请求**（对齐 iOS `submitTapped` 的 pop 在前、回调在后——本页自己的协程作用域会在摘掉那一刻被
> 取消，逻辑放在 `QrRouteHost` 持有的外层 scope 里）。**仍未接**：相册选图识码、一图多码候选、扫码登录（QR P1，
> 命中即提示不支持）。`/code-review` 复查抓到一条高严重度：关闭扫码页后 CameraX 从未 `unbindAll()`
> （单 Activity 架构下 `bindToLifecycle` 挂的是 Activity 级生命周期，Compose 把页面摘出树不会自动解绑）——
> 相机占用指示灯不灭、持续耗电，已修（`DisposableEffect` 里补 `provider?.unbindAll()`）；顺带修了一条中等：
> 从「去设置开启」跳系统设置页回来后权限状态不会自动刷新，加了 `LifecycleEventObserver` 在 `ON_RESUME`
> 重查一次。测试补了两个纯文案函数的遗漏覆盖。`test.sh` **854 例绿**。
>
> **第五批：接着补齐第四批留下的三项（2026-09-22 同日；纯客户端、`./scripts/test.sh` 860 例绿，
> 新测试均先看红过——除一处如实记录「没能造出红」，见下；未提交、未真机）**：
> ⑧ **相册选图识码**：`ui/QrScanHost.kt` 加「从相册选择」（`PickVisualMedia`，不需要相机权限，两种权限态
> 都露出，对齐 iOS 即便相机被拒也留着这条路）→ `decodeQrImage` 读 bounds 后按 `inSampleSize` 降采样
> （目标边长 2000px，避免大图直接摊平成 IntArray OOM）→ 摘像素喂给新文件 `data/QrImageDecode.kt`
> （纯逻辑，入参是 ARGB 像素数组不是 `Bitmap`，本仓没有 Robolectric 但照样能单测——`QrEncode` 那半同一个
> 理由；用 zxing `QRCodeMultiReader`，未叠 ML Kit）。
> ⑨ **一图多码候选**：0/1/N 三态——识别不到提示、一枚直接当结果、多枚弹 `ActionSheet`（复用「收藏发送」
> 那批已有的组件），候选摘要用新函数 `data/QrActions.kt#qrScanLabelFor`（本站码按路径前缀标 名片/群/
> 登录码，外来码给域名或文本首段，对齐 iOS `labelForRaw:`），按包围盒面积降序排（面积大的通常是用户想扫
> 的主码，对齐 iOS `IMQRImage` 按 `CIFeature.bounds` 排序）。
> ⑩ **扫码登录（QR P1）手机侧**：`QrApi` 新增 `loginScan`/`loginConfirm`/`loginReject` 接 `/qr/login/
> {scan,confirm,reject}`；`QrResolved.Login` 从占位 `data object` 改成带 `ticket` 的 `data class`；新增
> `ui/QrLoginConfirmHost.kt`+`screens/QrLoginConfirmScreen.kt`（设备/IP/位置/扫码时间四行信息卡 + 红色安全
> 提示条 + 确认/拒绝两按钮，对齐 `IMQRLoginConfirmViewController`）；`QrRouteHost` 的 `Login` 分支从
> 「提示不支持」改成真正 `loginScan` 再开确认页。
> `/code-review` 两轮：高严重度——相册 I/O（`openInputStream` 对 content URI 可抛
> `FileNotFoundException`/`SecurityException`）此前没包 try/catch，会让协程直接崩掉整个 App 而不是走
> 「没识别到」这条路，已修（对齐已有 `AvatarPrepare.decodeSampled` 同一处理）；中高——相机识别（后台
> `executor` 线程）与相册识别（主线程协程）共用同一个 `handled` 守卫，`mutableStateOf` 的读写不是原子的，
> 理论上能让两条路径都判定自己是第一个、各调一次 `onResult`（重复路由/双开预览页）——改用
> `AtomicBoolean.compareAndSet`；低——候选面积排序、`qrScanLabelFor` 漏了登录码 `/q/l/` 分支，均已补。
> **验证纪律的诚实记录**：面积排序那处临时去掉排序代码单测仍然绿——`QRCodeMultiReader` 内部按模块尺寸
> 聚类，实测输出本就已经是大码在前，没能真正造出「红」；显式排序留着是不依赖 zxing 未文档化的内部实现
> 顺序，单测锁的是**输出契约**，不是这行代码有没有生效，已在代码注释里如实记这一条。
> **仍不做**：App Links 深链接（iOS 也没有，两端此处本就同构，非缺口）。至此扫码/点链接加群这条线全部
> 补齐。

> **im-rtc 通话接入（2026-09-19，联调期；单聊 1v1 + 群通话；单测绿、未提交、未真机）**：
> SDK 走本机 Maven（先在 `../im-rtc/im-rtc-android` 跑 `./gradlew publishToMavenLocal`，`settings.gradle.kts` 只对该 group 开 `mavenLocal()`），
> 版本坐标在 `libs.versions.toml` 的 `imrtc`。**票用调试密钥在本机签**（`rtc/RtcCall.signToken` 是票的唯一来源，以后换后台接口只改这里）。
> 配置写 `local.properties`（已忽略）：`rtc.wsUrl` / `rtc.appId` / `rtc.keyId` / `rtc.debugSecret`，缺项则入口提示、其余功能不受影响。
> 生命周期：`AppRoot` 进主界面 `RtcCall.start`、回登录页 `stop`（Restoring 不动，Activity 重建不挂在途通话；同账号重复 start 是空操作）。
> 入口：单聊详情页语音 / 视频 pill；群资料页新增语音 / 视频 pill → 选成员（`PickPurpose.Call`，最多 8 人）→ `placeGroup`。附件面板「音视频」不接（后面会去掉）。
> ⚠️ 服务端地址联调时是 Mac 的局域网 IP（`ws://<Mac IP>:8787/v1/ws`），换网络要改 `rtc.wsUrl` 重新打包；名字与头像由 `rtc/RtcProfileResolver` 注入（按 uid 取名片：备注 → 昵称 → @句柄，未缓存时先显示 uid、取到再重画；单测绿、未真机）。
> 未做：设置页「后台接口 / 调试」开关（等 IMServer 换票接口）、IMServer 侧换票、群成员超一页时选人页只列已加载的。

> **收藏页 + 长按「收藏」+ 详情页下载示意复用聊天页组件 + 从收藏发送（2026-09-17 第二、三批；纯客户端、后端零改动；
> `./scripts/test.sh` 807 例绿，新测试均先看红过；⚠️ 用户要求**不装真机**，布局/手势未实测；未提交）**。
> 逐条状态见 `../IMServer/docs/CLIENT_PARITY.md` 顶部「2026-09-17 第二批 / 第三批」，SYMMETRY 新登记 3 行。
>
> ① **详情页宫格 / 文件行的门控外观 = 聊天页那几个组件**：宫格 `AlbumTileGate` + `TileDurationChip` + `VideoPlayBadge`
>    （与聊天页相册格同一对），文件行图标位 `FileGateSlot(side = 36dp)`（与文件气泡同一个），副行 `DownloadLabels.archiveFileLine`
>    （照 iOS `IMDetailFileCell`）。旧 `DownloadBadge` 与 `fileHint()` 已删。放行判据 `DownloadPolicy.archiveTileUngated`：
>    自己发的 / 图片且策略放行 → 直接显示，**失效不豁免**；门控格点一下 = 下载，不打开（iOS 铁律①）。
>    ⚠️ 这条**更正**了上一批「iOS 宫格直接按 URL 加载」的说法——iOS 是按策略放行，出厂默认图片恒自动所以看着像直出。
> ② **收藏列表页**（「我 ▸ 收藏消息」= `ui/FavoritesHost.kt` + `ui/screens/FavoritesScreen.kt`，判据 `data/Favorites.kt`）：
>    七签只列存在者；媒体/文件/语音/链接**直接复用 `ArchiveRows.kt` 的行**（收藏 → `ConvMediaItem`，key = 收藏 id，
>    下载态按 URL 与聊天页共享——iOS「合成 `IMMessageModel` 喂共用编排器」的对应物）；名片/聊天记录复用 `CardBubbles.kt` 卡片内容。
>    签内搜索、分页、来自X、长按 转发/复制/取消下载/删除、点开查看器/文件/浏览器/记录页/资料页/全文阅读页。
> ③ **长按菜单「收藏」**（`MessageAction.Favorite`，判据 = 多选栏的 `SelectionActions.favoritable`），执行挂宿主 scope。
> ④ **附件面板「从收藏发送」**（第三批）= `FavoritesHost(onPicked = …)` 的选择模式，由 `ChatPickerLayers` 盖在聊天页上
>    （返回键层 `ChatOverlays.Layer.FavoritePicker`）。判据 `data/FavoritePick.kt`（上限 9、取消永远允许、按列表顺序发）；
>    勾选框 `PickCheckButton`（行尾槽 `trailing` / 宫格 `picked`，复用多选圈 `SelectionCheck`），点行/点格仍是打开；
>    发送 `ForwardSend.kt` 的 `sendFavoritesTo`（同一个 `MessageService.forward`，失效媒体跳过并写进回执）。
>    ⚠️ 收藏发出去前 `FavoritesHost.toMessages` 先补齐来源名（好友表 / 名片），解析不出写「未命名用户」——
>    此前名字没回来就发送会把对方内部 uid 写进「转发自」（复查抓出，长按转发同一条路）。


> ⬇ 以下两块 2026-09-17 从活快照原样转入（详情页划不动真因 + 七条用户报告）。

> **「聊天信息 / 群聊信息页划不动、很卡」真因找到并修掉 ✅（2026-09-17；`./scripts/test.sh` 781 例绿 +
> 真机仪器测试 2/2 绿，已先看它红过；**用户真机手测通过**）**。上一轮 ② 的结论「实测能滚、是空页错觉」**是错的**：
> - **根因**：`MainScreen` 给覆盖页包的触摸屏蔽层在 **Main 阶段 consume 全部事件**。列表的拖动检测在位移
>   未过 touch slop 时会再等 Final 阶段查"有没有被别人消费"——被屏蔽层吞了就判手势被抢、整次拖动作废。
>   所以**甩得快（首帧过 slop）能滚，慢推 / 按住再划纹丝不动**，头部卡片上、宫格上一样。上一轮真机测是快甩，没撞上。
>   修法：屏蔽层只占命中测试、不消费（`ui/components/TouchShield.kt`），下层聊天页仍点不到（测试覆盖）。
> - **顺带收的卡顿**：① 链接 / 波形 / 发送者名三份本地扫描**只在对应页签订阅**且扫描移出主线程
>   （`rememberLocalScan`；此前详情页一开就挂 3 个 500 行 Room 查询，任何会话来消息都在主线程重扫 URL 正则）；
>   ② 两页头部从「一个巨型 item」拆成多个 item；③ 归档格长按矩形改为长按时才算（此前每格每帧 `boundsInWindow`）。
>
> **七条用户报告 + 详情页各页签对齐 iOS ✅（2026-09-17；纯客户端、后端零改动；`./scripts/test.sh` 781 例绿，
> 逐条真机验过）**。⚠️ **有四条的根因与报告时的猜测不同**，按实测改的：
>
> ① **「媒体库/详情页宫格看不到图」**——真因不是滚动：宫格未就绪时喂给 Coil 的是 `gate.model`（门控未
>    就绪即 `null`），于是整页只剩磨砂底 + 一排 ↓，看着像页面死了。iOS 两处宫格
>    （`IMConversationMediaViewController` / `IMDetailMediaContainerCell` 里的 `IMMediaTileCell`）
>    **都是直接按 URL 加载缩略，门控只作盖在上面的状态层**（`autoPrefetchEnabled = NO` 管的是
>    "不自动整包预取原件"，不是"不显示这张图"）。已改成按地址直出；**聊天气泡那侧仍守门控**。
>    列数 **4 → 3**（原注释声称"与 iOS 同为 4"是错的），媒体库标题改 **「图片与视频」**（逐字取 iOS）。
> ② **「详情页划不动 / 有时能划」**——当时判为①造成的空页错觉，**判错了**，真因见本节顶部（触摸屏蔽层）。顺手删掉 `AlbumBubble` 里一处没有任何读取方的每帧状态写。
> ③ **「转发的图片消息看不到」**——转发本身正常（服务端落库带 `media_w/h`/`thumb`/`file_size`/`forward_from`）。
>    真因是**自己转发出去的图在自己这一侧被门控挡成空盒**：转发透传的是原图 URL，本机并没有那些字节。
>    门控是挡"别人发来的、我还没决定要不要下"的，对自己发出去的内容没有意义 → **`mine` 一律不门控**
>    （单图 `MediaContent.mine` + 宫格 `AlbumTileView`）。
> ④ **「复制图片后长按输入框没反应」**——**两个根因**：(a) `ClipData.newUri` 只声明图片 MIME、**不含
>    `text/plain`**，而 Compose `BasicTextField` 的"能不能粘贴"走 `ClipboardManager.hasText()` → 菜单里
>    根本不出现「粘贴」；(b) 更要命的是**复制协程挂在长按菜单自己的 `rememberCoroutineScope()` 上**，
>    菜单一关就取消，真机日志是 `image_copy_failed {err=LeftCompositionCancellationException}`
>    ——图片压根没进剪贴板。已改：剪贴项同时带 URI + 纯文本两种表示；`ChatMessageMenu` / `ArchiveActionsHost`
>    改由**宿主传 scope**（同 `ArchiveViewer.kt` 文件头那条 ⚠️），顺带救了「仅删除自己」同一条线。
> ⑤ **「视频下完点播放只有声音没画面」**——`AndroidView` 没有 `update` 块：`player` 是 `remember(absolute)` 的，
>    下完切本地那一刻换了实例，而 `PlayerView` 还挂着**已被 onDispose release 的旧 player**（没有输出 surface
>    → 有声无画，退出重进才好）。补 `update = { it.player = player }`。
> ⑥ **查看器**：顶部改**两行**（会话名 17/semibold + `i / N` 13/次要灰），逐条对齐 iOS
>    `IMMediaPagerViewController` 的 `IMLiquidNavigationBar`；图片补**磨砂占位**（原图现拉那几秒原先是纯黑，
>    像图没了，iOS 是 `showThumbPlaceholder`）。**翻页与远端加载实测本来就正常**，未下载的图也能看。
> ⑦ **各页签对齐 `IMChatDetailViewController`**：语音行改三行（发送者名 / **真波形**（复用聊天页
>    `VoiceContent`，同 iOS 复用 `IMVoiceMiniPlayerView`）/ 完整年月日时分）；文件行、链接行同改三行；
>    时间一律改 `TimeFormat.fileDateTime`（同 iOS `IMFormatFileDateTime`）；空态文案逐字改成
>    「暂无媒体 / 暂无文件 / 暂无语音 / 暂无链接」。**波形与发送者名服务端归档接口都不回带**，
>    由本地消息表按 `conv_seq` / `uid` 兜底（`rememberVoiceWaveforms` / `rememberLocalSenderNames`）——
>    顺带**修掉了「从语音页签转发出去的语音丢波形」那条老限制**。
>
> **真机逐条验过**（user1002 真机 ↔ 服务端库核对）：宫格 3 列真图 / 语音行波形+1:34+完整时间 /
> 查看器两行标题 + 21→19 翻页 / 转发落库字段齐 + 自己这侧可见 / 复制→粘贴出现粘贴条与缩略图 /
> 原视频下完胶囊消失且播放有画面（00:09 在走）。

> ⬇ 以下几块 2026-09-16 从活快照原样转入（第二～四批用户报告、聊天页三条、隐私与安全、数据和存储）。

> **第四批用户报告（Android 部分）✅ 2026-09-15（用户自测通过，已提交）**：① 名片 / 聊天记录卡时间并进脚注行（`CardBubbles.kt` 的 `CardFooter`）；
> ③ 链接预览图贴卡片上 / 左 / 右三边（`LinkPreviewCard.kt`）；④ 带圆钮的标题栏与二级页同高（`TopBarCircleButton` 只按 24 高参与测量，UI_SPEC §4.5）。

> **第三批用户报告（Android 部分）✅ 2026-09-15（用户复测通过，已提交；test.sh 674 条全绿、新测试均变异验红）**，逐条见 IMServer `docs/CLIENT_PARITY.md` 顶部：
> ① **冷启动/登录先闪「还没有会话」**：列表拿 `emptyList()` 当库初值 → 改 null + `data/ConversationListPhase.kt`（本地空且服务端拉成过也说没有才画；
>    `MessageService.listedConversations`）；② 索引尺照 iOS 系统那条改观感（`ContactIndexBar`）；④ 通讯录右上角圆形「添加朋友」钮
>    （`TopBarCircleButton`）+ 页标题「添加朋友」；⑤ **iOS 改密 → 本端半分钟才下线**：OkHttp 收到服务端关闭帧不自动回帧、`onClosed` 不来，
>    等 25s ping 才发现 → `IMSocketManager.Listener.onClosing` 回 1000（`ServerCloseKickTest` 本地 WS 服务端复现，修前超时、修后 1.5s）；
> ⑥ 消息页/通讯录标题居中（`IMTopBar`），消息页文字「我」换 ＋ 菜单（`ui/ChatsHost.kt` + `ChatsPage`；「添加朋友」「建群」状态抽成
>    `AddFriendHost` / `CreateGroupHost`，通讯录同用），底栏蓝点口径改 `data/TabUnread.kt`（补上免打扰里被 @，删掉 DAO 那条 SQL）。

> **第二批用户报告（Android 部分）✅ 2026-09-15（未提交；662 条单测全绿、变异验红、代码复查无正确性问题；模拟器实测未完成**——
> im_test 冷启动后 systemui / system / IM 连续 ANR、输入丢字，放弃）。逐条见 IMServer `docs/CLIENT_PARITY.md` 顶部：
> ① 「新的朋友」角标 `c.danger` → `c.unreadBadge`，并补高/最小宽 `d.unreadBadgeHeight` + 居中（同 `UnreadBadge`）；
> ② **改昵称后老消息仍显旧名**（iOS/Web 的 bug，本端普通群本不中招）：发送者名链统一为「备注 > 成员表 > 本窗最新快照 >
>    本条快照 > 好友名」（`data/SenderNames.kt` + `SenderNamesTest`，调用点 `ChatRowView.senderNameOf`）——原先好友昵称压过群昵称、
>    超级群非好友落老快照；会话开着时末条昵称对不上成员表 → 重拉群资料（`ui/MemberNameRefresh.kt`，5s 节流）。
> 顺带：`ChatScreen.kt` 598 行、`ChatHost.kt` 576 行，贴近 600 门禁，再加东西先拆。`ONLY=<类> ./scripts/test.sh` 在多模块下会被
> `:media-picker` 报 "No tests found" 中止（过滤没限定 `:app`），跑单类用 `./gradlew :app:testDebugUnitTest --tests`。

> **用户报的三条 ✅ 已改、未提交、未真机手测**（2026-09-15，差异登记 `docs/UI_PARITY_IOS.md` §3.5 / §4）：
> ① **文件文不显示文字**——图说判据挂在「贴边媒体」上，文件气泡不贴边，字整段不画。判据抽到
> `data/BubbleCaption.kt`（`Bubbles.kt` 用它），聊天记录详情页文件项同漏、一并补；
> ② **二级页底部 Tab 栏一直显示**——「通讯录」「我」的二级页在底栏上方的内容区原地切换。改成各 Host 根页经
> `ui/TabRoot.kt` 插槽自己画底栏（判据 `data/PushNav.kt` 的 depth；页面枚举 `ContactsPage`/`MePage` 移到 data 带深度）；
> ③ **没有 push 转场**——新增 `ui/components/PushTransition.kt`（AnimatedContent；退场页换一个脱离 Activity 的返回键分发器
> + Initial 阶段吞触摸，防连按返回被吞、防点到滑走中的页）与 `PushBase`（聊天页被详情盖住时只让开、不出组合）。
> 单测 `BubbleCaptionTest`(5) / `PushNavTest`(6)，三处变异验红。
>
> **「我 ▸ 隐私与安全」✅ 已实现，未真机手测**（2026-09-11，对齐 iOS `IMPrivacySecurityViewController` 三页，
> 差异登记 `docs/UI_PARITY_IOS.md` §4.9）：容器页五组（活行只有「已屏蔽的用户」「修改密码」，其余四组灰置占位）/
> 已屏蔽的用户（计数、左滑取消屏蔽、空态三层、刷新失败保留旧内容）/ 修改密码（眼睛切换、本地校验、按业务码红字 + 描红）。
> 判据在 `data/PrivacySecurity.kt` 与 `data/ChangePasswordRules.kt`，持有者 `ui/PrivacySecurityHost.kt`。
>
> **做法上非显然的点**：① **改密应答里的 `refresh_token` 必须接住**——那是续期凭据唯一的轮换点，服务端这一刻已作废旧的；
> 接不住的话本机在 access token 下次过期时被登出。故 `IMClient.changePassword` 整段 `NonCancellable`、Host 挂 `client.scope`；
> ② 参数错是 **100001**，iOS 写成了 100002（限流码），本端不照抄、有单测钉住；
> ③ 按钮「三个框都填了就亮」取 Web 口径——iOS「校验全过才亮」让那几句原因提示永远显示不出来。
>
> **上一轮「数据和存储」✅ 已实现、未真机手测**（三层设置页 + `capabilities_update` 同步；真机首测撞的「保存失败」已修：
> PUT body 多套了一层 `settings`，且 `encodeDefaults=false` 会把 true 默认值省掉被 Go 解成 false）。
>
> 更早的「聊天页第三轮九条」仍待真机手测（清单在 `docs/UI_PARITY_IOS.md` §4.8）。

> **`/code-review --fix` 打回四条 ✅ 2026-09-09**（在 IMServer 会话里跑的那一轮，顺带扫到本仓
> 未推送的 6 个 commit）。一条严重：
> **遗留 `msg_op` 事件行的一次性收敛「取 500 条、却删全部」**——`legacyMsgOpRows(limit=500)`
> 只取一批并应用，紧接着 `DELETE ... WHERE contentType='msg_op'` 把这批之外**从未被应用**的
> 行一起抹掉。11 万条的大群里 500 这个上限一次够不着，那些撤回/编辑/置顶/删除就**永久丢失**，
> 而日志 `deleted` 还虚高。改成按批循环、**逐条应用完立刻删**（中途崩了下次还能重新取到），
> 并把 `deleteLegacyMsgOpRows` 这个整删的 DAO 方法**删掉**、在原处注明为什么不能有它。
>
> 另三条：① 点 ↓ 的 `pendingScrollToBottom` 会永久挂着（本来就在尾窗时 `rows.size` 不变，
> 消费它的 `LaunchedEffect` 不跑），之后一条新消息到达会被当成"刚点过 ↓"一把甩到底、
> 还绕过 `shouldAutoScroll`——**先按"待办加保质期"处理（滚完 1 秒清掉），是止血不是根治**，
> 根治该让换窗与滚动请求各自带一次性 token；② `WindowRequester` 的「先订阅再发帧」实际没做到
> （`scope.launch` 只是排队，scope 没指定 dispatcher），`replay=0` 的 SharedFlow 对无订阅者那一发
> 直接丢弃 → 定位等满 8 秒误报「需要联网加载」，改为发帧前等 `subscriptionCount > 0`；
> ③ `requestSync` 的 KDoc 挂错函数、还引用了不存在的 `windowResults`。
>
> `./scripts/test.sh` 全绿（476 例 / 71 个测试类）。**四处均未上真机验证。**

> **群 @提及全套（M4-8）✅ 2026-09-09 —— 本端此前只有一半**
>
> 会话列表的「[有人@我]」红字早就在了，但点进会话**既看不出哪句 @ 了我、也 @ 不回去**，
> 那半条形同虚设。这轮把另外两条补齐：输入栏内联 @成员面板 + 气泡内 @高亮且可点。
> 判据逐条抄 iOS `IMChatViewController+Mention.m` / `IMChatMessageLogic.m` /
> `IMBubbleCell.attributedContent:...spans:` 与 im-web `src/mention.ts`，
> 清单见 `docs/UI_PARITY_IOS.md` §4.6，协议 `IMServer/docs/PROTOCOL.md` §4.1。
>
> **三层**：`data/Mention.kt`（纯判据，27 例单测）→ 协议与落库（DB v7→v8）→ UI。
>
> **三个非显然的判断**（都写进了代码注释）：
> ① **待发行也要存片段**：ack 只回 seq 与时间戳，不回带 `mention_spans`。不存的结果是
>   「自己发的 @ 在自己这一侧不高亮」而对端一切正常——与 forwardFrom/groupId/媒体元数据/
>   thumb 同一族坑，**这是第六次**，所以一并进了 `AckCarryOver.CARRIED`（那条反射守卫
>   验证过：漏登记就红）。
> ② **`mentions` 单独存一列，不从片段反推**：群里两人重名时片段只有一段、只链一个 uid，
>   反推等于「重连补发后重名那位悄悄收不到提醒」。`mention_all` 可以反推（有空 uid 段即是）。
> ③ **点击用 `LinkAnnotation.Clickable` 而不是 `ClickableText`**：后者已废弃，且它自己吃掉
>   tap 手势，气泡长按菜单会跟着失灵。真机专门验了长按仍在。
>
> **面板候选走服务端 `?q=` 分页，不在本地成员表里过滤**——超级群不下发成员表，
> 本地过滤在那里恒空，而那正是最需要 @ 的场景。
>
> **变异验证抓出我自己写的两条假绿**（都已修，删掉实现即红）：
> 「不间断空格」那条我把 Kotlin 与 Java 的 `isWhitespace` 语义记反了（Kotlin 认 U+00A0，
> Java 不认），多写的 `|| isSpaceChar` 是空操作、注释还说反了；用例里 caret 取 7 而串长 6，
> 越界时 `activeQuery` 恒回 null，等于没测。「长名优先」那条靠的是 token 边界，
> 删掉排序照样绿——真正吃到它的是**名字带空格**的情形（`小美` vs `小美 丽`）。
>
> **顺带修一个既有 bug**：`resendInFlight`（重连补发）只传 fileName/fileSize/caption，
> forwardFrom / groupId / 媒体元数据全丢；`resend`（点红❗重试）那条路是全的，这条不是。
>
> **顺带拆分**：`MessageService` 触 600 红线，把 typing/watch/msg_op/receipt 四个
> 「只发帧不落库」的小帧上行平移到 `MessageSignals.kt`（扩展函数，行为未改）。
> 与 `MessageWindowQueries` 那次的差别：调用点在 `ui` 包，扩展函数不自动可见，要补 import。
>
> **真机端到端验过**（模拟器 / 群「1001创建测试群」）：打 `@` 弹面板且键盘不收 →
> 键入 `3472` 实时过滤 → 选中回填 `@用户3472 ` 并关面板 → 服务端库里
> `mentions=["1010147977"]`、`mention_spans=[{"offset":0,"length":7,...}]`
> （`@用户3472` 正好 7 个 UTF-16 码元，且未被服务端安全校验丢弃）→ 气泡里 `@用户3472`
> 蓝色、`kaihui` 常规色 → 点它进对方资料页 → 长按同一气泡菜单照常弹出。
>
> **没验**：普通成员看不到「@所有人」（要第二个账号；判据有单测且取自服务端 `my_role`）；
> 超级群里老消息不高亮那条降级（要一条没有 `mention_spans` 的老消息）。
> **没做**：「@我的消息」聚合入口（`GET …/mentions`，**三端都欠**，不是本端单独欠的）。
>
> **这一轮还欠两件收尾**（见「下一步」第 0 条）：`CLIENT_PARITY.md` 的两行状态没更新、
> `/code-review` 没跑。

> **消息多选（M4-3 的另一半）2026-09-09 —— 代码完成，⚠️ 真机未验**
>
> 长按菜单里「多选」此前是直接缺项（5/8）。判据层 `data/ChatSelection.kt`（8 例，变异 3 轮全红），
> UI 是顶栏「取消 / 已选择 N 条」+ 行左勾选圈 + 底部动作栏（转发/删除两格）。
>
> **照抄 iOS 那条用线上 bug 换来的教训**（`IMChatSelectionState.h` 类注释）：勾选态**按
> `conv_seq` 记、且连消息实体一起存**。iOS 原先记在表格行选中里，向上翻页 `reloadData` 清空、
> `prepend` 又让行下标平移，于是「勾两条 → 上滚拉历史 → 再勾一条，前两条静默消失」。
> 本端列表同样是窗口化的，所以判据层函数**签名里拿不到窗口/行号/任何列表状态**。
> 顺带删掉 `Forward.toggleCapped`（`Set<Long>`，零调用方）——它正是"只存 seq"那种会丢消息的形状。
>
> 动作栏只有两格：iOS 那侧还有收藏与举报，本端对应功能都没做，**不画只会弹「还没做」的死按钮**。
>
> **⚠️ 真机一条都没验**（模拟器在宿主负载 25 时反复 ANR，最后 uiautomator 的桥都挂了）。
> 要验的清单在 `docs/UI_PARITY_IOS.md` §4.7 末尾，其中**最要紧的是「上翻拉历史后勾选不丢」**
> ——那正是 iOS 踩过、本端最可能重演的一条。

> **M4-7 收口：让"下载下来的东西真的被用上" ✅ 2026-09-08（真机断网验过）**
>
> 上一步把门控接上之后冒出一批同形状的不一致：**文件已经下到本地了，
> 消费它的那一侧还在照远端地址拉**。本轮全部收口：
>
> - **查看器 / 播放器 / 存相册**一律优先本地文件。此前点 ↓ 把 10MB 视频下下来、
>   点开播放又从网络流一遍，门控白做。真机验法：**断开媒体主机后仍能播**（已验）。
> - **详情页归档与媒体库不自动预取**（`autoPrefetch = false`，对齐 iOS 的
>   `autoPrefetchEnabled = NO`）：那两处是翻历史，一屏几十条，自动下会静默拉走几百 MB。
> - **文件就绪后点一下打开**（复制进 `cache/share` → FileProvider → `ACTION_VIEW`）。
>   `file_paths.xml` 只白名单了 `share/`，注释里写着"要交出去的先复制进来"，照这条走，
>   **不放开 `files-path`**。此前文件气泡完全不可点——下下来了也没办法看。
> - **详情页「文件」页签补门控**；文件行点开不再丢进图片查看器（一个 PDF 会被
>   `ZoomableImage` 当图片渲染成空白，是条死路）。气泡与详情行的状态文案收进
>   `DownloadPhase.fileHint()` 共用。
> - **自己发的东西不再显「未下载」**：整包上传那条路发完即 `MediaCache.adopt`。
>
> 修掉三个自己埋的坑：
> ① `MediaUrl.absolute` 把 `content://` 拼成 `http://host/content://…`——判据此前散在
>   调用点（查看器/播放器有、宫格没有），**散在调用点就一定会漏一处**，已收进函数并单测；
> ② `ConvMediaHost`（只被群详情调）没传 `isGroup`，群会话一路按**单聊档**判门控。
>   连带把 `ConvMediaScreen`/`FileRow`/`ArchiveTile` 的 `isGroup` 默认值**删掉**——
>   给默认值就是给下一个人留同一个坑；
> ③ `MediaDownloader` 的 `jobs` 是普通 HashMap（主线程起、后台线程 remove），
>   `_states` 是读-改-写。已改 `ConcurrentHashMap` + 锁 + `update`。
>
> 416 例 / 66 类绿；新增三组测试各变异验红。

---

> **真机连内网开发机登不上 ✅ 2026-09-07（夜）—— 不是地址问题，是明文流量策略**。
>
> 现象：OPPO（Android 15）填 `192.168.1.12:8080`（**iOS 同地址能连**），登录报
> 「网络连接失败，请检查服务器地址」。
>
> 真因：`res/xml/network_security_config.xml` 的明文白名单只有
> `10.0.2.2 / localhost / 127.0.0.1`，内网 IP 不在里面 → OkHttp 抛
> `UnknownServiceException`（`CLEARTEXT ... not permitted by network security policy`）。
> **请求根本没上路**：没有 DNS、没有 TCP，服务端日志一行都没有。
> iOS 那边是 ATS，与这套是两回事，所以「iOS 能连」反而误导。
>
> 两处改动，第二处更值钱：
> ① **debug 变体单独一份策略**（`app/src/debug/res/xml/`，`<base-config cleartextTrafficPermitted="true">`），
>    release 仍用 `src/main` 那份严格的，PROTOCOL §0.1 原样成立。
>    **不往白名单加 IP**：白名单不支持网段，而开发机 IP 由 DHCP 分配、换个 WiFi 就变，
>    每人每次都要改文件重装，还容易漏到生产。
> ② **报错不再撒谎**：`ApiException.isCleartextBlocked` 把这一情形与普通「连不上」分开，
>    文案改成「系统拦截了明文 HTTP 连接（地址没写错）」。
>    原文案让人去反复核对一个**完全正确**的地址——这才是这次真正卡住人的东西。
>    顺带把 `friendlyMessage` 从 `AppRoot.kt` 抽成 `ui/LoginError.kt` 以便测试。
>
> `LoginErrorTest` 4 例，两处变异各自精确变红（明文那条排到 isTransport 之后 → 永远走不到；
> `isCleartextBlocked` 不要求 isTransport → 业务失败被误认）。真机验过：同一地址现在登录成功、
> WS 连上、同步正常。全量 **263 例 / 43 类**绿。

> **修「失败消息永久钉底」✅ 2026-09-07（夜）—— 与 iOS 2026-08-05 同一个坑的第三端重演**。
>
> 现象：user1001 与「光辉岁月」的聊天页，底部永远挂着一条红❗的失败媒体消息，
> 后来的消息全排在它上面，滚到底只看到那条旧的失败件。
>
> 根因不是排序算法错，是**两路没合流**：`buildChatRows` 把待发那一路
> **整段接在已确认之后**，注释还写着「待发消息恒在末尾——它们还没有服务端时间戳，
> 用本地 createdAt，天然就是最新的」。**那个假设对失败的消息不成立**：
> 16:34 发失败的那条，在 17:11 的消息到达后就不是最新的了。
> 等价于 iOS 当年那句「conv_seq=0 一律垫底」——`IMDatabase.m` 的注释原话是
> **「从『临时垫底』变成『永久钉底』」**。
>
> 改法：抽出 `data/MessageOrder.kt` 作为本端**唯一**的显示序定义
> （`timestamp` 主排；同毫秒时 `conv_seq=0` 视为 **+∞** 垫底——**垫底只在同一毫秒内成立**），
> `buildChatRows` 改为两路合流后一次排序。相册聚簇仍只并「相邻且同类」的成员
> （已 ack 的和还在发的会挨着，但它们是 Album / PendingAlbum 两种行，混并没有意义）。
>
> `docs/SYMMETRY.md` 补了第三端登记（此前只有 iOS 与 im-web 两条，本端就漏在这个空当里）。
> 新增 `MessageOrderTest` 7 例：**修复前先看它红**（`失败的待发消息不再永久钉底` FAILED），
> 再对比较器做两处变异（conv_seq=0 排最前 / conv_seq 主排）各自精确变红。
> 全量 **259 例 / 42 类**绿。

> **完整 ➕ 面板 ✅ 2026-09-07（夜）**——2×3 六项，清单与顺序逐条对齐 iOS
> `attachItems`（照片/拍摄/音视频/收藏/个人名片/文件），几何同值（面板 236、格子 56）。
> **顺序是跨端契约**：用户靠位置记住「文件在右下角」，两端顺序不同就是两套肌肉记忆，
> `AttachItemsTest` 钉死。面板与键盘互斥（展开收键盘、点输入框收面板）。
>
> 六项里 **4 项接了真实功能**：照片（`:media-picker`）、拍摄（系统相机 + 既有 FileProvider，
> 走压缩同口径）、文件（`OpenDocument` → 分片上传，不预筛类型——白名单在服务端，
> 端上预筛只会让用户「明明有这个文件却选不中」）、个人名片（选好友 → `contact` 卡片）。
> **音视频**与 iOS 一样是占位（整个功能三端都没做）；**收藏**本端没有收藏能力（无 API、无页面）。
> 未实现的项**照样列出来**——删掉会让三端面板长得不一样，比点进去看到「还没做」更困惑。
>
> **名片这条撞在隐私红线上，且真机实证了它**：好友列表里显示的是备注
> 「张曼玉1002-朝辞白帝彩云间」，而发出去的卡片里是 `"n":"用户1002"`（公开名）。
> 抽出 `DisplayName.ofFriend`（显示，备注优先）/ `publicNameOfFriend`（发送，绝不含备注）
> 两个函数 + `FriendNameTest`。iOS 与 im-web 各为此出过一次线上事故。
>
> **真机验过**：面板渲染与位置、个人名片（服务端 `content` 是公开名）、
> 相机（Google Camera 拉起 → 拍 → ✓ → 服务端 1536×2048 / 216KB，正是压缩后长边 2048）。
> **文件那条只验到「DocumentsUI 拉起来了」**——DocumentsUI 对 adb 合成点击没反应（第二次撞见），
> 选中并发送这一步**需要人手点一次**。
>
> 顺手补了两处：相机临时原片此前只在注释里说「拍完即删」而**实际没删**（每拍一张漏 3~5MB），
> 改成下次拍照时清 10 分钟前的（发送是异步的，发完立刻删会和上传抢文件）；
> `ChatScreen.kt` 到 586/600 行，把行模型 + `buildChatRows` 拆进 `ChatRows.kt`（拆的是
> 「消息列表长什么样」这件纯逻辑，渲染留在原处）。

> **相册选择器独立成 `:media-picker` 模块 + 四条限制全部补完 ✅ 2026-09-07（夜，真机验过）**。
>
> **模块边界是单向的：app → media-picker，反向禁止。** 所以模块**不认识** `IMTheme`、
> 也**不认识** `IMLog`——主题走 `MediaPickerSkin`、日志走 `MediaPickerLog` 两个接缝由 app 注入
> （接线只在 `ui/MediaPickerBridge.kt` 一个文件里，换掉选择器实现改这一个文件就够）。
> 模块只吃 `compose.foundation` + `ui`，**刻意不依赖 material3**：接入方用什么主题体系都行。
>
> 四条原「刻意没做」逐条补完：
> ① **压缩 + 原图开关**：`MediaCompressor` 长边 ≤2048 / JPEG 0.8（照抄 iOS `IMMediaPicker`），
>    两趟解码（一趟解 8000×6000 就是 183MB 位图必 OOM）、EXIF 方向落到像素上
>    （重编码会丢 EXIF，漏了就是「相册里正的、发出去躺倒」）。压过的一律改名 `.jpg`。
> ② **预览大图**：`ZoomableImage`（双指缩放 / 拖动 / 双击 / 边界钳制）+ 左右翻页。
>    **这是本工程第一份可缩放查看器**，导出给聊天页将来复用，别再搓第二份。
> ③ **视频**：`UploadApi.uploadStream` 走服务端已有的分片接口（init → chunk → complete），
>    峰值内存 = 一片 8MB、与文件大小无关。抽首帧当 `poster` 上传（协议 §4.1：
>    解不了 HEVC 的端只能靠这张封面）。补齐 `media_w/media_h/duration/poster` 四个协议字段。
> ④ **2000 张上限**：改成「相册列表全量归纳（轻列一次扫描）+ 格子分页」。
>    原来的做法会让只装旧照片的相册**整个不出现**。Android 11 起必须走 Bundle 参数分页，
>    老写法（`sortOrder` 里夹 LIMIT）在新系统上直接抛——两条实现都留着。
>
> **真机逐项验过**（Pixel 2 XL / Android 11）：宫格出图带视频时长角标、编号顺序、取消顺延、
> 原图体积、长按预览、双击放大、左右翻页、发图（压缩后 480×480 13KB）、
> 发视频（10.4MB 分 2 片 → `chunked_done` → 服务端 1080×586 / 27351ms / poster 齐全）、
> 分页（临时把页大小调成 4：offset 0/4/8/12 → 4/4/4/0，到底即停不空转）。
>
> **真机抓到 4 个 bug，单测一个都抓不到**：
> ① `Authorization: Bearer ${it}` —— 字符串模板写成了字面量，服务端回 `100101 token 无效`；
> ② 手势：两个 `pointerInput` 里 `detectTransformGestures` 无条件消费事件，
>    把双击和 pager 横滑全吃掉了。改成自写手势循环：**双指恒归我、单指只在放大态归我**；
> ③ 待发媒体气泡按文本画，屏幕上出现一条写着 `content://media/...` 的绿气泡
>    （多图那条路早有 `AlbumBubble` 兜着，所以只有**单张**会露出来，而单张通常一闪而过——
>    **只有发失败时才一直挂着**）。补了 `PendingMediaBubble`；
> ④ 视频时长角标画在右下角，和时间胶囊叠住。协议 §4.1 明写是**左上角**，已改。
>
> **ack 不回带的字段这是第三次踩**（forwardFrom → groupId → 媒体元数据），
> 每次都只在**发送者自己那一侧**坏、对端正常，所以自查极难发现。抽成
> `AckCarryOver` + 一条**反射闸**：两个实体新增同名字段却没在 `CARRIED` 里做决定就变红。
> 已变异验证（给两个实体各加一个 `thumb` 字段 → 立刻红）。
>
> **门禁也跟着改了**：`check-file-size.sh` / `check-logging.sh` 原本只扫 `app/src/main`，
> 把代码搬进新模块就等于绕过检查；`test.sh` 原本写死 `:app:testDebugUnitTest`，
> 模块里的测试类会**一条都不跑而输出照样是绿的**。三个脚本都改成扫全部模块，
> 日志红线另加「注释里提到不算违规」（改完自己变异验过一次）。
>
> 测试 **246 例 / 39 个类**全绿（本批新增 ~30）。变异验证 9 处，其中 2 处**没红**并查明了原因：
> `ZoomBounds` 的 `scale<=1` 早退是死代码（`coerceAtLeast(0)` 已覆盖）、
> `sizeLabel` 的 1023B 是测试真空档（已补断言）。

> **自建相册多选页 ✅ 2026-09-07（晚，真机端到端验过）**——用户拍板「**最低要支持 Android 11**」，
> 这一句直接推翻了上一轮的选图方案。
>
> **实测数据（Pixel 2 XL / Android 11）**：`build.version.extensions.r = 0`、
> `ACTION_PICK_IMAGES` 无任何 handler、Play 服务 26.32.68（是新的）。
> 系统 Photo Picker 的回填靠**系统更新**下发的 SDK extension，不随 Play 服务走，
> 这台 2020 年 EOL 的机器永远等不到 → androidx `PickMultipleVisualMedia` 必然落到
> DocumentsUI 文件浏览器。**上一轮我说「Photo Picker 免权限、体验好」，那只在 Android 13+ 成立**，
> 在本项目的最低版本上不成立。
>
> 于是自建 `ui/screens/MediaPickerScreen.kt`（4 列宫格 / 编号多选 / 相册切换 / 发送(n)），
> 权限走 `MediaPermission`（三个版本段三个权限名），**被拒时降级回系统选择器**——
> 读相册是敏感权限，用户完全可能拒绝，而「拒绝 = 发不了图」不能接受。
>
> **真机逐项验过**：授权 → 宫格出图（7 张 4 列）；选 3 张编号 1/2/3；取消中间一张后面顺延成 2；
> 发送 → 服务端 `im_message` 三条共享 `alb-d732ec91…`、收端渲染成 `rowPattern(3)=[1,2]` 宫格；
> `pm revoke` 后再点 ➕ → 拒绝 → 前台变成 `documentsui/PickActivity`（降级路径生效）。
> 验完已把权限恢复授予。
>
> **刻意不做**（免得被当成漏做）：没有「原图/压缩」开关——本端压缩本身还是 TODO，
> 放个不起作用的开关比没有更糟；没有预览大图——那需要可缩放查看器，本端还没有，
> 单搓一个会变成第二份实现。视频也没放开：`sendMedia` 是整包字节上传、无分片，视频进来会 OOM。
>
> **顺带查到 im-web 一条缺口**：`Composer.tsx` 的 `<input type="file">` 没有 `multiple`，
> 所以 Web 能**渲染**宫格却发不出宫格。已改 `CLIENT_PARITY` 那行的 Web 列为 🚧（不在本次范围）。

> **多选发图 + 待发也聚簇 ✅ 2026-09-07（晚）**——≥2 张共享 `alb-<uuid>` 的 `group_id`
> （1 张不带，与 iOS 同）；**待发消息也带 groupId 并聚簇**（Room 迁移 v3→v4）——
> iOS 是「选完秒上屏」直接成宫格，只在确认消息上聚簇的话，用户会看见 N 张图先各自排一列、
> 收到 ack 后再"啪"地拼成宫格。ack 回包不带 `group_id`，要像 `forwardFrom` 一样从待发行里取，
> 漏了这行则一组图在自己这侧收到 ack 后会从宫格散回单张（对端仍是宫格），比不聚簇更怪。

> **群管理写操作落地 ✅ 2026-09-07（晚）**——第 ③ 项的第一块。此前本端群管理**完全只读**。
> 补齐 `GroupApi` 全部 10 个写接口（改群资料/公告/全员禁言/单独禁言/移除成员/设撤管理员/
> 转让群主/解除拉黑/治理开关/审批入群），群资料页加管理卡（群名称/群简介/群公告/全员禁言）
> 与成员长按菜单（设撤管理员/禁言/转让/移出）。
>
> **权限判据全部收在 `GroupPermissions` 纯函数**：这几条规则会同时出现在成员长按菜单、
> 群资料页入口、成员详情页三处，散着写必然分叉——分叉的表现是「按钮亮着但点了报 300204」
> 或反过来「有权限却看不到入口」，两种都很难自查。核心是一条等级序 + 「须严格高于对方」
> （不严格的话一个群里的管理员可以互相清场）。
>
> 实体机端到端验过：管理卡渲染、编辑框（带字数上限）、`PUT /api/v1/groups/{id}` 200。

> **「我」Tab 五件事全部落地 ✅ 2026-09-07（晚）—— 独立 worktree `feat/android-me-tab`**。
> 按 iOS `IMSettingsViewController` 及其 push 出去的几页对齐：
> ① **入口列表**逐行照抄 iOS 的三组（收藏/通话/设备/文件夹/名片 · 通知/隐私/存储/外观/省电/语言 · 退出登录），
>    未实现的行**照样列出**并落到「还没做」提示——删掉会让三端「我」页长得不一样；
> ② **已登录设备**：列表（本机置顶「当前」不可点 / 其他设备进详情 / 底部退出其他所有设备）+ 详情 + 二次确认踢下线；
> ③ **登录用户信息**：头部大头像 + 昵称 + 「手机号 · @句柄」，回退链止于昵称，**末级绝不是 uid**；
> ④ **左上角二维码**：`GET /qr/me` + zxing-core 出码，进页提亮 / 存相册 / 系统分享 / 复制链接 / 重置二次确认；
> ⑤ **右上角编辑**：只读 ↔ 编辑双态（对齐 iOS 2026-08-30 的双态改造），改昵称/手机/标签/句柄/头像。
>
> **真机实测（Pixel 2 XL, Android 11）逐项过**：入口列表与占位提示、设备列表·详情·踢下线（服务端确认）、
> 二维码出码·存相册（`Pictures/IM/` 里查到文件）·分享面板、改昵称往返、选头像→上传→预览→取消丢弃。
> **未实测**（会破坏用户现有状态，刻意不碰）：退出其他所有设备、重置二维码、改用户名；
> Android 9 及更早的存相册分支（本机是 11，走不到）。
>
> **实测抓到一条三仓级别的坑**：`HttpClient` 对无体 POST 传了 null body，
> **OkHttp 当场抛 `IllegalArgumentException`，请求根本没上路**——表现是「点了没反应」，
> 服务端日志里连一行都没有，最难查的那种。`/logout`、`/devices/{sid}/revoke`、
> `/devices/revoke-others`、`/qr/me/reset` 四处全中，其中 **`/logout` 此前一直在静默失败**
> （退出登录只清了本地，服务端设备会话根本没吊销）。已修 + 配 `RequestBodyForTest`
> （反向也要守：GET 带体 OkHttp 同样抛，所以不能一律发 `{}`）。
>
> **单测 176 例全绿**（本批新增 32：`DeviceDisplay` 13 / `ProfileEdit` 6 / `QrEncode` 10 含 zxing Decoder 回环 / `HttpClient` 3 / `runCatchingCancellable` 3）。
> 六条变异验证：statusLine 不过滤空段、没有 current 时谎称「其他设备」、每次保存都改名、二维码黑白取反、丢 UTF-8 hint、吞掉 CancellationException——**都红了**。
> 唯一没红的那条如实记在测试注释里：矩阵转置抓不到（转置=镜像，zxing 与真实扫码器都容错）。

> **消息气泡类型补齐（第一批）✅ 2026-09-07（下午）**。用户指出两件事并给了优先级：
> ① 长按菜单不该是底部弹窗；② 各类消息气泡先按 iOS 做齐；③ 然后打通会话/单聊/群聊/详情/群管理。
>
> **已做**：长按菜单改成 iOS `UIContextMenu` 形态（背景压暗 + 气泡原位高亮 + 菜单贴着气泡）；
> **名片卡 / 合并转发卡**（此前显示裸 JSON）；**语音真波形**（此前是假条纹）+ 修气泡最小宽 96→160；
> **链接富预览卡**（接 `GET /api/v1/link-preview`，进程内缓存，抓不到安静退化成纯链接）。
>
> **气泡类型第二批 ✅**：相册宫格（`group_id` 聚簇 + 布局照抄 `IMAlbumRowPattern`）、
> 图说整体化（媒体贴气泡边、有图说时只圆上角、时间胶囊浮在图上）。
> **宫格未做视觉验证**——测试库里没有多图同组的历史数据，判据靠 7 条聚簇单测钉住。

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


---

# 归档：2026-09-08 之前的「当前焦点」块（从 current_task.md 裁下）

## 当前焦点

> **聊天/会话界面对 iOS 拉齐 ✅ 2026-09-08（Pixel 2 XL 真机逐项验过）** ——
> 用户列了 8 条，1~7 已逐条处理；差异与欠账全部登记进
> [docs/UI_PARITY_IOS.md](docs/UI_PARITY_IOS.md)。
>
> **过程中挖出一个存在已久的真 bug：引用回复从来没生效过。**
> 上行协议要的是嵌套对象 `reply_to: {conv_seq}`，本端发的是扁平的 `reply_to_conv_seq`
> ——那是**下行**字段名。服务端读 `data.ReplyTo.ConvSeq` 读不到、**静默忽略**：
> 菜单能点、引用条能显示、消息也发得出去，就是不带引用（服务端库里恒为 0）。
> 修完还有第二层：**自己这一侧仍不显示**——`ack` 只回 5 个字段，服务端冻结的
> `reply_snapshot` 回不来（对端正常、自己看不到）。这是「ack 不回带」那一族的**第五次**。
> 改成三档：冻结快照 > 本地原消息现算 > 「原消息」，判据是 `replyToConvSeq>0` 而非"有没有快照"。
> 补了 `SendMsgWireTest` 直接钉 JSON（上下行同名不同形是这类 bug 的温床）。
>
> 逐条：
> - **② 通讯录标题压状态栏**：这一页漏了 `systemBarsPadding`。Tab 根维持大标题左对齐（同 iOS）。
> - **③ 会话列表长按**改成原位菜单（iOS 是 `UIContextMenu`，不是底部弹窗），
>   条目/顺序/文案逐字对齐 `conversationActionsFor:`，每项配图标。
> - **④ 消息长按**四处：菜单开着时原气泡整行 `alpha=0`（消除"重叠感"，iOS 靠
>   `UITargetedPreview` 自动藏）；预览层不再吞点击（点气泡旁空白现在能关）；
>   长按从外层容器挪到**每一格**（格子自己的 clickable 会吃掉 down 事件——
>   这就是"九宫格不支持长按"的原因）；菜单加图标 + 两档删除收成子菜单。
>   真机又抓到预览一律画 Bubble、长按宫格会重绘成一张大图，已改成按行的真实形态画。
> - **⑤ 系统消息**：胶囊（同 iOS `_pill`）+ `sys_segments` 分段 + 名字琥珀色可点进资料页。
>   Room v5→v6 落库分段（不落的话重进会话就退回"显真实昵称、不可点"）。
> - **⑥ 引用**：见上。引用块按 iOS 重做（竖条 + 群聊两行式 + 类型图标 + 13sp 快照）；
>   输入栏引用条此前显示的是 `/uploads/...` 路径，已抽 `replyPreviewOf` 与冻结快照同口径。
> - **⑦ 文件图标**：iOS 那 22 张是 SVG，而 Android 的 vector drawable **不支持 `<text>`**，
>   角标（PDF/W/X/`{ }`/`</>`）全是文字元素、直接转会丢光。按同样的外形（path 逐字取自 SVG）、
>   同样的渐变色、同样的角标在 Compose 里重画；扩展名清单逐条照抄 iOS。
> - **① 各类消息卡片**：逐项核过，名片卡/聊天记录卡/图文的结构与数值**早就对齐**，
>   只差脚注前那枚小图标（没有它两种卡的底部一模一样），已补。
>
> 全量 **347 例 / 56 类**绿。新增 `SysSegments` 6 例、`FileTypeIcons` 7 例、
> `LinkScan` 11 例、`ReplyPreview` 6 例、`QuoteSnapshot` 5 例、`SendMsgWire` 3 例。

> **对着 iOS 修两页 + 建 UI 差异档 ✅ 2026-09-08（Pixel 2 XL 真机验过）** ——
> 用户指出「聊天信息页和群管理页 UI 和 iOS 完全不一样」。复查属实：不是细节偏差，
> 是**页面结构不同**。
>
> **新增 [docs/UI_PARITY_IOS.md](docs/UI_PARITY_IOS.md)**：Android ↔ iOS 界面差异登记，
> 每条带判定（🔴欠账 / 🟡平台限制 / 🟢刻意差异）**与理由**。
> 不写理由的条目等于没记——下一个人只会把它当欠账再做一遍。
>
> **① 「水滴」头部效果的结论**（用户问能不能做）：iOS 是 `IMDropletHeaderMorph` +
> Telegram 原始素材 `UserAvatarMask.tgs`，用 Lottie 当遮罩把头像**吸进灵动岛**。
> **形变那半能做也该做**（缩放/名字迁进标题栏/松手吸附，🔴 欠账）；
> **遮罩那半不做**（🟡）：Android 没有灵动岛，挖孔位置/形状/尺寸随机型变，
> Pixel 2 XL 干脆没有挖孔——照搬会在屏幕顶部凭空多出一团与任何硬件都对不上的黑色。
> 技术上可行（`lottie-compose` + `BlendMode.DstIn`），所以这是**产品判断不是技术限制**。
>
> **② 群管理页**逐条对齐 iOS `IMGroupManageViewController` 的六个分区：
> 群头像头部 / 资料（群名称·简介·公告）/ 加入与发言 / 成员权限 / 治理 / 管理员 / 群主，
> 每行带图标、每节带 footer。**图标用 Lucide 近义图标**（SF Symbol 在 Android 上不存在）——
> 要对齐的是「每行都有一个能一眼认出的图标」，不是同一张图。
>
> **③ 聊天信息页**：归档从"一行入口 push 出一整页"改成**详情页内联页签**
> （媒体/文件/语音/链接），头部改成 100pt 居中大头像 + 名字 + @句柄，
> 补上 iOS 的 Info 区（备注名/用户名）与「查找聊天记录」「清空聊天记录」两行。
>
> **④ 用户列的欠账清了大半**：黑名单页、管理员管理页（群主可增删/管理员只读）、
> 邀请入群（入口按 `canInvite` 显隐）、转让群主（选人 → 红色二次确认）、语音段、链接段。
> 四处选人共用一个 `PickListScreen`。
>
> **链接段服务端不覆盖**（链接不是独立 `content_type`、没有可索引的列），与 iOS 一样扫本地文本。
> 抽出纯函数 `LinkScan`（口径同 iOS `IMFirstURLInText`：混排文本也算）。
> **单测当场抓到一个真错**：第一版按"截到下一个空格"取 URL，而中文根本不打空格——
> 「去 https://x.com，然后」会把「，然后」一起吞进去。改成按 URL 合法字符集截断。
>
> **真机发现并修**：整页替换做导航时，**返回后滚动位置全丢**
> （从 2000 人成员列表点进一个人再返回会弹回顶部）。两个 Host 都加
> `SaveableStateHolder` 按页保存——iOS 的 push/pop 天然保住这些，本端得自己兜。
>
> 全量 **321 例 / 51 类**绿（新增 `LinkScanTest` 11 例）。

> **标题栏统一 + 群管理拆页 + 单聊详情页 ✅ 2026-09-08（Pixel 2 XL 真机验过）** ——
> 用户三点反馈，逐条落地；第 ③ 项到此清完。
>
> **① 标题栏收成一个 `IMTopBar`**。此前它已存在，但聊天页/群资料/资料页/审批页/找人/
> 新的朋友/建群**各自手搓了一份 Row**——这正是"统一"要收的账。扩成：居中标题 17sp semibold +
> 副标题 13sp（对齐 iOS `IMLiquidNavigationBar` 的 17/13 与 `.center`），右侧支持文字动作或
> **圆形会话头像**。规格先落 `../IMServer/docs/UI_SPEC.md` **§4.5** 再改代码。
> **左右两侧固定占位、不用 `weight` 撑**：靠 weight 撑，标题会随左右内容长度左右漂——
> 右边从「发送」变「发送(3)」标题就跟着挪，翻页时肉眼可见地抖。
> 右侧会话头像**如实标注为「Android 先行」**：iOS 的 `actionCircular` 只用于通用图标动作，
> 并没有把会话头像放进标题栏，不伪装成对齐。
>
> **② 群管理从群详情拆出**。此前开关组、群名编辑、待审申请全挤在群详情里，一页十几个可点的
> 东西、其中一半普通成员根本看不见——**同一个页面在两种身份下长得完全不同**。
> 现在详情页只负责"看"（头部/公告简介/聊天媒体/成员/退群）+ 一行「群管理」入口，
> 管理页负责"改"。对齐 iOS：`IMChatDetailViewController` 的「群管理」行 push 出
> `IMGroupManageViewController`；im-web 的 `GroupManagePanel` 同为详情抽屉的二级视图。
>
> **③ 详情页入口**。此前只能点标题进，而"标题可以点"界面上没有任何提示，等于没有入口。
> 现在右上角那个会话头像就是入口（标题仍可点，与 iOS 一致）。
>
> **④ 单聊详情页**（第 ③ 项最后一块）。此前单聊只有「用户资料页」——那回答的是
> "这个人是谁"，而"这段对话怎么设置"没有地方放。新增 `ChatDetailScreen`：
> 头部（点进用户资料）/ 聊天媒体 / 置顶 / 免打扰。会话设置是**整体替换三项**，
> 改一项要把另外两项原样带回（与群治理开关组同一个坑）。
>
> **⑤ 会话媒体归档**（M4.5-3）：接 `GET /conversations/{id}/media`，4 列宫格 +「文件」段，
> 游标分页、在途守卫、按 `conv_seq` 去重。**过滤全在服务端**（撤回/为所有人删除/仅为我删除/
> `history_visible` 下界），端上不再判一遍——判据分叉的话，这一页会出现聊天页里看不到的消息。
> **没有「链接」这一格**：链接不是独立的 `content_type`，服务端没有可索引的列
> （`internal/conversation/media.go` 开头写明这是本接口不覆盖的一格），与其放个永远空的 Tab 不如不放。
> **群聊与单聊共用同一个 `ConvMediaHost`**：分两份的代价不是重复代码，是分页语义会分叉。
>
> **返回键的账，这次是结构性地还的**。一天之内漏了三次（媒体查看器 / 待审申请 / 群管理），
> 每次表现都一样：**按返回直接退出整个 App**。不再逐个补 `BackHandler`（那只会有第四次），
> 改成**按当前页一处派发的 `when` + 枚举**（`GroupInfoPage` / `ChatDetailPage`）：
> 枚举加一页 `when` 就编译不过。加「聊天媒体」那一页时**当场验证了这个设计**——
> 编译器直接指着漏掉的分支报错。
>
> **顺手修两处自己留的**：待审列表那条此前是 `return` 的，导致审批完的 toast 根本不显示
> （四个页面改成互斥分支、都不 return）；归档里的查看器摆着**点了没反应的转发按钮**，
> 改成 `onForward` 可空、没接就不画。
>
> **真机逐项验过**（Pixel 2 XL / Android 11）：标题居中 + 副标题「41 分钟前在线」+ 右上角
> 会话头像；点头像进详情；群详情只剩头部/大群说明/聊天媒体/群管理/成员；群管理三组齐全；
> 单聊详情四行；媒体归档 4 列宫格（视频带播放钮与时长）与「文件」段；从归档点开查看器、
> 保存到相册成功（`Movies/IM/ecexport…mp4` 10.4MB）；返回链逐级正确。
>
> 全量 **310 例 / 50 类**绿（新增 `GroupInfoNavTest` 5 例 + `ChatDetailNavTest` 4 例，
> 三条变异各自变红）。

> **群治理开关组 + 入群申请审批 ✅ 2026-09-08（Pixel 2 XL / Android 11 真机验过）** ——
> 第 ③ 项的第三、四块，两块同属群资料页的管理面，一起做。
>
> **先修了两条反着的判据**（接开关 UI 时才发现）：`perm_invite` / `perm_edit_info`
> 的语义是**「仅管理员可…」，`true` = 收紧**，本端读成了「允许成员…」，于是
> `canInvite` / `canEditInfo` **两条都反**——开了「仅管理员可邀请」反而放行普通成员，
> 没开时普通成员反而被挡。更糟的是**单测把错的语义钉住了**：那个测试自己叫
> 「开了「仅管理员可邀请」后普通成员不能邀」，断言写的却是 `assertTrue`，名字与断言互相矛盾，
> 一直是绿的。`canEditInfo` 的开关值当时还从**调用方**传，调用点直接把 `iAmManager`
> 当成了这个参数——等于这个开关根本没接上。现在直接从 `GroupInfo` 里取，堵掉传错的可能。
>
> **`GroupInfo` 补了三个缺字段**（`join_approval` / `perm_edit_info` / `perm_pin`）。
> 这不是"顺手补全"：`PUT /settings` 是**整体替换**，读不回来的那几个，
> 在下一次改任意一项时会被当成 `false` 写回去，等于**悄悄替群主关掉他设过的开关**，
> 而界面上只是某个开关变灰了，没人会怀疑是刚才那一下点的。
> 翻转逻辑收进纯函数 `GroupSettings.toggled`（翻一个、其余四个原样带回）。
>
> **开关文案与 im-web `GroupManagePanel.tsx` 逐字一致**并有测试钉住——
> 同一个开关两端叫法不同，用户看到的就是两套规则。
>
> **入群申请审批**（G3）：`GroupApi.joinRequests()` 新增（**数组在 `requests`**，
> 本仓群相关三个接口三个不同的数组名：`members` / `items` / `requests`）。
> 列表分**待处理 / 已处理**两段——只显待处理的话，审批完那一下列表会空掉，
> 看着像操作没生效，而这一步不可撤销。
>
> **真机端到端验过**（造了一条真实申请）：
> 拨「进群确认」→ 服务端库 `join_approval` 0→1 且**另外四个仍是 1**（整体替换纪律的直接证据）；
> 用一次性账号 `tmpverify1` 凭群码申请 → 服务端返 `300210` 落 pending →
> 群资料页出现绿色「1 待处理」角标 → 列表显示昵称 + 验证消息 + 同意/拒绝 →
> 点「拒绝」→ 服务端 `rejected`、待处理段变「暂无待审批的入群申请」、已处理段出现「已拒绝」→
> 把「进群确认」拨回关闭，服务端回到 `0|1|1|1|1` 原状。
>
> **真机撞出一个我自己刚引入的 bug**：在申请列表里按返回**直接退出整个 App**。
> 我把群资料页的 `BackHandler` 关掉给列表让位，却忘了给列表自己加一个——
> 正是 `ChatOverlays` 注释里写的「每加一个覆盖层都没人想起返回键」，隔了一天又踩一次。
> 已改成本页自接返回并复验。
>
> **没做 / 留痕**：① 「同意」那条路**没有端到端点过**（与「拒绝」共用同一个
> `reviewJoinRequest`，只差 payload 里 `approve`/`reject` 一个字符串），因为点同意会真把人拉进群、
> 撤销又要产生入群/移出两条系统消息；② 验证时**在本地库新建了账号 `tmpverify1`**
> （`user_id 7846751005`）并留下一条 `rejected` 入群申请记录——都是新增、没动既有数据，
> 要清掉随时说；③ 黑名单页、邀请入群、管理员管理页仍未做（im-web 那三块都有）。
>
> `GroupSettingsTest` 6 例 + `GroupPermissionsTest` 补 1 例。变异验证 4 处全红
> （toggled 不带回现值 / 分组漏一个 key / 文案改反 / of() 漏读一个字段），
> 另把纠偏后的判据还原成旧的反向实现，确认**新测试真的抓得住**。全量 **300 例 / 48 类**绿。

> **群成员列表点进个人资料页 ✅ 2026-09-08（真机验过）** —— 第 ③ 项的第二块。
> 此前 `GroupInfoHost` 的 `onOpenMember` 是空 TODO，**点成员完全没反应**，
> 是那四块里唯一用户能直接撞见的死路。
>
> **没新写页**：本端早有 `UserProfileHost`/`UserProfileScreen`（单聊详情走的就是它），
> 缺的只是接线。两步推导抽成纯函数 `data/MemberProfile.kt`，因为这两步各踩着一个具体的错：
> - **`GroupMember.displayName` 优先取群昵称**。直接拿它当资料卡的 `nickname`，就是把
>   「他在这个群里叫什么」显示成「他的昵称」——别人改一下自己的群昵称，你看到的他的资料页跟着变。
>   种子必须用 `m.nickname`（全局昵称）。
> - **关系与备注不给种子就会闪**：进页先渲染成「陌生人 + 昵称」，拉到名片后跳成「好友 + 备注」。
>   为此把 `MainScreen` 的 `knownRelations`（uid→status）换成 `knownFriends`（uid→整行），
>   备注才有得取。
>
> **点到自己头上单独一档**（`MemberProfile.RELATION_SELF`）：不判的话会给自己显示一个
> 「加好友」按钮。这一档同时把「备注名」行也隐掉（本就只对好友显示）。
>
> **整页替换而不是叠一层**：`GroupInfoHost` 的内容不在自己的 `Box` 里，父布局是谁由调用方决定，
> 叠出来可能是竖排而不是覆盖。替换还顺带让群资料页的滚动位置与成员分页游标原样留着
> （那些 `remember` 都在早退点之上，没被跳过）。返回键用 `enabled = memberProfile == null` 让位。
>
> **真机验过**（OPPO PKD130）四种情况：2000 人大群点成员 → 资料页（服务端确认 `user4836`
> 确实是 `accepted`，压测建了 2006 个好友）；点**自己** → 无任何关系按钮、无备注名行；
> 点好友 → 备注名行 + 发消息 + 删除好友；「发消息」→ 直接进与该成员的单聊。
>
> `MemberProfileTest` 8 例，四条变异（种子用 displayName / 备注不做种子 / 去掉自己那一档 /
> 不判 uid 非空）**各自精确变红**。全量 **293 例 / 47 类**绿。

> **存相册 + 转发入口 + 上传进度 ✅ 2026-09-08（真机验过）** —— 媒体收尾里挑了最便宜、
> 每天碰得到的两条先做（缓存与转码按投入产出比往后放，理由见「已知坑」）。
>
> **① 保存到相册**：查看器右下角一排（转发 + 下载，位置同 iOS `setupCommonControls` 的
> `_downloadButton`——用户靠位置形成肌肉记忆，两端摆得不一样就是两套）。
> **流式拷进 MediaStore**，不先读进 `ByteArray`：一段几百 MB 的视频整包进内存就是 OOM
> （发送侧为此走了分片，存的时候当然也不能倒回去）。图片落 `Pictures/IM`、视频落 `Movies/IM`。
> 与 `ImageExport` **不是复用而是另一条路**：那边吃已在内存里的 `Bitmap`（二维码），
> 这边吃一个地址、要先把字节弄到手；共用的只有 MediaStore 那套 Q 前后分支和相册子目录名。
> 失败时**必须把占位行删掉**——不删就在相册里留一条 0 字节、点开黑屏的条目，
> 用户不会认为「保存失败」，只会认为「这个 App 存出来的东西是坏的」。
>
> **② 上传进度**（`UploadProgress`，clientMsgId → 百分比）：**只活在内存里，不落库**——
> 上传本来就不跨进程续传，把百分比写进 Room 只会在冷启动时显示一条永远停在 43% 的幽灵进度。
> 只覆盖**分片**那条路（视频/文件）；图片走整包上传、压缩后几百 KB，接了只会闪。
> 百分比**向下取整绝不四舍五入**：99.9% 显示成 100% 而气泡还压着暗底，看着就是卡死。
>
> **③ 顺手补掉两个同族缺口**：
> - **待发文件气泡**（`PendingFileBubble`）：文件待发行此前走文本分支，屏幕上是一条绿气泡写着
>   `content://com.android.providers…`。与 2026-09-07 修过的图片/视频那条是**同一个坑**，
>   当时只补了媒体两种。两个待发气泡一起搬进新文件 `ui/screens/PendingBubbles.kt`。
> - **`createPending` 不落 `fileName`/`fileSize`/`caption`**：`resend()` 是从待发行里读这些字段的，
>   行里没有就等于「重发一次文件名和大小就没了」；文件待发气泡也正因此显示不出名字。
>   与 forwardFrom → groupId → 媒体元数据**同一个坑，这是第四次**。
> - `AlbumBubble` 里残留的 `Text("▶")` 文字字形改 Lucide 图标（OPPO ColorOS 用彩色 emoji 字体
>   渲染，会变成橙色方块；VideoPlayer 里刚修过同一条）。
>
> **真机逐项验过**（OPPO PKD130 / Android 15）：
> 存视频 → `Movies/IM/PXL_…LS.mp4` 34.7MB，**md5 `b75da1d5…` 与服务端原件逐字节一致**，
> 服务端的 `req-<id>__` 前缀正确剥掉；转发 → 查看器先关、选择页干净打开无残留黑底；
> 上传进度 → 一段 **404MB** 视频分 ~51 片，进度环 0→35%→100% 全程在，传完干净消失、
> 气泡变成带 5:30 角标的正式视频；文件进度条同样走通（两次发送均 `upload_complete`）。
>
> **补了一处「第一片传完前的空窗」**：`prepare` 之后到第一次 `onProgress` 回调之间没有进度，
> 气泡显示的是播放钮、看着像已经发好了。改成**开传即置 0%**。
>
> **一处没查清、如实记**：第一次发文件时，气泡在上传中途显示过红❗（服务端日志显示那次上传
> 是完整成功的，消息最终也带 ✓ 落地）。第二次发同一文件、14 次逐秒抓图**没能复现**，
> 未找到根因。注意这不是本轮引入的：`failed` 判据没动，我只是让这个状态**被看见了**
> （此前它挂在一条 `content://` 文本气泡旁边）。下次撞见先抓 Room 的 pending 行。
>
> 全量 **285 例 / 46 类**绿（新增 18 例）。变异验证 7 处，**6 处精确变红**，
> 1 处没红并查明原因：`report` 里手写的「同值短路」是死代码，`StateFlow` 本身按 `equals`
> 合并——已删守卫、留测试（钉的是行为不是实现）。
>
> 另修 `../IMServer/docs/CLIENT_PARITY.md` 一处**列错位**：上一轮把 Android 的查看器说明
> 写进了 Desktop 列。

> **媒体查看器：视频可播、图片可缩放 ✅ 2026-09-08（真机验过）** —— 你批准顺序里的第 3 步，
> 也是我上一轮自己捅的缺口（刚让视频能发，点开却没反应）。
>
> Media3/ExoPlayer。**控件自绘**（`PlayerView` 只借 Surface 与 `resizeMode`，`useController=false`）：
> 自带控制条有自己一套 Material 配色，与 `IMTheme` 令牌对不上、也不跟随聊天主题色；
> iOS 侧同样是手搓 `_playButton`/`_scrubber`/`_timeLabel`。
>
> 对齐 iOS 的两条：**封面先显、点了才播**（`IMMediaViewerViewController` 的 `_started` 门控，
> CLIENT_PARITY 任务3 记作「封面待点不自动播」）；**离开就停**（`DisposableEffect` 释放 +
> 跟随生命周期 onStop 暂停，对应 iOS `viewDidDisappear` 里的 pause）。
>
> **图片那半复用 `:media-picker` 的 `ZoomableImage`**，没另写一份。为此把该模块的
> `LocalPickerImageLoader` 默认值从 `error(...)` 改成回落 Coil 单例——**导出一个只能在自家内部跑的
> 组件等于没导出**，这是上一轮埋的坑。
>
> **顺手修掉两个既有问题**：
> ① **返回键会直接退出整个聊天页**：`ChatHost` 原本只有一个无条件的 `BackHandler(onBack)`，
>    覆盖层（转发/选图/选联系人/查看器）是逐个加上去的，每加一个都没人想起返回键。
>    改成按 `ChatOverlays.Layer` 逐层关，层序 = 渲染顺序，有测试钉着。
> ② **控件用 "⏸"/"▶" 文字字形**：OPPO ColorOS 用彩色 emoji 字体渲染，暂停键成了橙色方块。
>    改用 Lucide 图标。
>
> **真机抓到一个只在冷缓存下出现的 bug**：`prepare()` 在组合期（`remember` 里）就调了，
> IDLE→BUFFERING→READY 可能在监听器挂上**之前**就走完——那些回调收不到，于是 `buffering`
> 恒为 false，缓冲期间显示的是大播放钮，看着像刚才那下没点上，**再点一次反而暂停**。
> 改法：挂监听前先读一次当前状态。实测点播放后 6 秒仍是播放钮 + `0:00/0:00`，修后正常转圈。
>
> 真机逐项验过：点视频进查看器、封面不自动播、播放/暂停/进度/时长、播放中重新缓冲显示转圈、
> 返回只关查看器不退会话、宫格逐格点开。全量 **267 例 / 44 类**绿。

---

# 归档：2026-09-08 收口时从 current_task.md 裁下的「当前焦点」历史块
（共 5 块，原样搬运，未做删改）

> **M4-7 第二步：下载状态机 + 门控 + 引用缩略/跳转 + 老消息补种 ✅ 2026-09-08（真机验过全链路）**
>
> **① 下载门控**（四处接入：图片气泡 / 视频 / 文件气泡 / 宫格逐格 / 详情页宫格）。
> 本端此前**没有下载这个概念**——Coil 见到 URL 就把原件拉下来。
> - `DownloadPolicy`：判据逐条对齐 iOS/Web。**图片的 `max_bytes=0` 是"无门槛恒自动"不是"关闭"**；
>   视频/文件大小未知也保守判否。**本端按真实网络类型选档**——Web 恒用 Wi-Fi 档是它的限制不是契约。
> - `DownloadState` 五态；**暂停与未下载分开**，**失效是终态不给重试**（给了就是每点一次拉一次 404）。
> - `MediaCache`：URL 的 SHA-1 作文件名；**写 `.part` 下完才改名**。
> - 生效的关键：**未就绪时喂给 Coil 的 model 是 null 而不是远端地址**——
>   给远端地址等于 Coil 照样拉，门控就成了纯装饰。
> - 真机验过：208MB 的包与 6.6MB 的表都停在「未下载」，3.5KB 的 log 自动下完；
>   点一下 → 环 + ⏸ +「下载中」→ 完成后徽标消失。
>
> **② 引用块真缩略 + 点引用块跳原消息**。两件事其实是**同一次本地反查**：
> 引用快照是发送时冻结的一串**文字**，既不带 thumb 也不带位置。抽了 `originalOf` /
> `rowIndexOfSeq`（宫格里的某一格也找得到——被引用的往往正是宫格里那一张）。
> 跳转用**瞬时滚动**（长列表上动画会滚很久，看着像卡住）+ 1.2s 高亮；
> **不在已加载窗口时如实提示**，不滚到一个错的位置。
>
> **③ 老消息补种缩略**（`ThumbBackfill`）。此前记的是"协议明写不做回溯，永远没有占位"——
> 那句话管的是**服务端不回填**，不等于收端只能空着：**原图在本地已经有了之后自己算一张**存进本地库，
> 下次进会话就有磨砂占位。只补本机、不上行、**绝不为补一张缩略去联网**（那正是门控要挡的）。
> 每条只试一次——这段挂在"消息列表每次变化"上，不记账就是每帧读一遍磁盘。
>
> 406 例 / 63 类绿。`DownloadPolicy` 三处变异、引用反查两处变异都单独红过。
> `Bubbles.kt` 触 603/600，把引用那一族拆进 `QuoteBlock.kt`。

> **M4-7 第一步：`thumb` 磨砂占位全链路 ✅ 2026-09-08（真机验过两端）** ——
> 原图到位之前不再是一块空底，而是消息里内嵌的 ~20px 缩略放大 + 模糊
> （Telegram 的 stripped thumbnail；iOS `IMMediaPlaceholder` / Web `.gate-blur`）。
>
> **发送侧也做了**——只做接收侧的话，本端发出去的图在**对端**仍然没有占位。
> 图片的缩略由**真正要发出去的那份字节**生成（压缩会改尺寸/朝向，拿原图算的占位
> 与收端看到的图对不上）；视频取封面首帧（同 iOS）。
>
> 链路：`TinyThumb`（纯逻辑：20px / q40 / 不放大小图 / 超 3800 字符宁可不带）
> → `ThumbEncode`（发送侧编码，先只读头拿尺寸再按 `inSampleSize` 解，别为缩成 20px
> 把几千万像素整张解进内存）→ 协议上下行 → Room v6→v7 **两张表**加列
> → `AckCarryOver` 登记 → `FrostedThumb`（渲染 + LRU 缓存）。
> 接入四处：图片气泡 / 视频封面 / 宫格逐格 / 详情页媒体宫格。
>
> **磨砂不用 `Modifier.blur`**：那是 `RenderEffect`，**API 31 才有、31 以下静默无效**
> （minSdk 26，手上的 Pixel 2 XL 就是 API 30——真机看不到效果而编译毫无提示）。
> 照 iOS 的做法在位图上算：20px 放大到 48 见方的代理图 + 三趟盒糊（≈高斯 sigma 4），
> 微秒级且与 API 无关。**边缘要钳制**，越界当透明黑会让四周发暗（已单测 + 变异验证）。
>
> 顺带补一条：`GET /conversations/{id}/media` **一直在下发 thumb**
> （`internal/conversation/media.go`），本端此前没解析。
>
> 374 例 / 58 类绿。`TinyThumb` 11 例，三处变异都红过；`AckCarryOverTest` 的反射守卫
> 也确认会因为漏登记 thumb 而红。
> 真机验法：断开媒体主机 + 清 Coil 图片缓存 → 显磨砂；恢复 → 显原图。

> **宫格逐格上传进度 ✅ 2026-09-08（Pixel 2 XL 实测：视频那格显 0%…环）** ——
> 用户报「九宫格视频和图片同时混发时，没有看到进度条」。属实：进度环只画在
> **单条**待发气泡上（`PendingMediaBubble`），宫格的格子只压一层暗底，
> 于是一段几十上百 MB 的视频在格子里传几分钟，屏幕上一点进度都没有。
> iOS 的 `IMAlbumTileView` 是逐格有环的。已补：
> - 分片上传（视频/大文件）有百分比 → 环 + 数字；
> - 整包上传（图片，压缩后几百 KB、没有进度回调）→ 转圈的；
> - **失败的格子不再转圈**（会一直转下去，用户以为还在传），改红❗；
> - 环的大小按格子分档（`AlbumLayout.ringSize`，已单测 + 变异验证）：
>   3 列宫格的格子只有 79dp，44dp 的环会占掉大半格还压住时长角标。
>
> **同时核了「下载/门控/磨砂」这一族：本端整族没做**（`CLIENT_PARITY.md` 的 M4-7
> 那几行 Android 列全是 ⬜）。iOS/Web 都有：`thumb` 磨砂占位、下载状态机
> （未下载↓/下载中环+⏸/失败↻）、账号级自动下载策略、失效 ⊘ 占位。
> 本端现在**没有下载概念**——Coil 见到 URL 就直接拉原图。
> 观感差异登记在 [docs/UI_PARITY_IOS.md](docs/UI_PARITY_IOS.md) **§3.6**。

> **多图发送全程都是宫格 ✅ 2026-09-08（Pixel 2 XL 实测）** ——
> 用户报「同时勾选多个图片或视频时，发送时不是九宫格形态，发送完成才变成九宫格」。
> **两个独立成因，都修了**：
>
> 1. **聚簇不跨「已确认 / 待发」**。此前是两种行（`Album` / `PendingAlbum`），
>    注释还写着「混并没有意义」——那是错的：一批图**不会同时 ack**，
>    「第 1 张已确认、第 2/3 张还在传」是必经的中间态，不许混并的结果就是
>    发送全程散成「一张普通图 + 一个两格宫格」。改成一种 `Album`，
>    成员是 `AlbumMember.Sent / Sending`，**压暗底由每一格自己带**。
>    行 key 改用 `group_id`（整个生命周期不变；用「首格身份 + 格数」会每 ack 一次就变一次）。
> 2. **待发行是逐个、且在压缩/抽帧之后才落库**。压一张几百毫秒到几秒，
>    于是气泡一张一张往外冒，而宫格要 ≥2 条同组待发行才成形。
>    改成**先把整批行一次性落库**（`createMediaPending`），再逐个压缩/上传/发帧。
>
> 连带处理三件事：① 元数据（宽高/时长/封面）此刻还不知道，算出来后必须
> `updatePendingMedia` 回写，**否则 resend 丢字段**（这一族的第五次）；
> ② 行更早存在 → 重连补发会把「正文还是本地 uri」的行判为残留标失败，
> 而它其实**正在上传**，故 `MediaSendPipeline` 记一份在传集合，补发跳过它们；
> ③ 读不出字节/视频大小为 0 时要 `markMediaFailed`，否则那一格永远转圈。
>
> `MessageService` 因此触 626/600 门禁，把整条媒体发送链路拆成
> `MediaSendPipeline.kt`（拆的边界是「一条媒体从选中到发出去」，两条上传路径
> 必须待在一起互相盯着）。
>
> 全量 **360 例 / 57 类**绿。混合聚簇那条**先红过**（改前 `assertEquals(1, r.size)` 失败）。
> 另把这条不变式登进 `../IMServer/docs/SYMMETRY.md`：im-web 天然满足（待发与已确认
> 同为一条 `ChatMessage`），本端因为把它们做成两种行才踩到——
> **「对称的是不变式，不是代码形状」的正例**。

> **对着 iOS 逐页拉齐（第三轮）✅ 2026-09-08（Pixel 2 XL 真机逐项验过）**
> 用户列 7 条 + 一个「为什么会漂这么远」的复盘问题。7 条全做完，复盘写进
> [docs/UI_PARITY_IOS.md](docs/UI_PARITY_IOS.md) **§5**（那一节讲的是**做法**，不是这几页）。
>
> - **① 长按浮起**：预览此前只是"在原位重绘一份一模一样的"——对自己发的消息
>   （无头像列、与原位逐像素重合）看上去就是**什么都没发生**。补了真正的抬起动画
>   （spring 0.94→1.02 + 投影）。URL 消息不整体浮起是另一回事：预览没传
>   `loadLinkPreview`，富预览卡整个不画。**根治**：抽 `ChatRowView` + `ChatRowStyle`，
>   预览与列表**共用同一段渲染**——这一族已栽两次（宫格画成大图、链接卡缺失）。
> - **② 九宫格单格浮起**：每格自记 `boundsInWindow`，长按传**那一格**的矩形与
>   **那一条**消息（此前恒传 `msgs.first()`，撤回/引用会作用到第一张上），
>   只把那一格 `alpha=0`。`MessageContextMenu` 改为按 anchor 左上角 + 宽度定位，
>   不再假定"anchor 一定是整行"。
> - **③ 详情页头部操作排**：本端**整排都不存在**。补 `DetailActionBar` +
>   `DetailActions`（纯函数 + 10 例单测，判据逐条抄 iOS `actionPillSpecs` / `moreTapped:`）。
>   顺带落地了「更多」里的清空聊天记录（**只清本机**）/ 拉黑 / 举报（`POST /reports`）/
>   删除好友 / 退群 / 解散群，新增 `GroupApi.dissolve`、`ContactApi.report`、
>   `MessageRepository.clearConversation`。
> - **④ 页签选中态**：iOS 是「底轨 + 药丸，选中与未选中同为主文字色、只差字重」；
>   本端是「12% 主色底 + 主色字」，深色下几乎看不出选了哪个。已照抄。
> - **⑤ 换群头像**：群名/简介/公告都能改，唯独头像没有任何入口。补相机圈 +
>   「设置新头像」（同 iOS `IMGroupAvatarHeader`），走 `POST /avatar` → `PUT /groups/{id}`
>   （整体替换，名字与简介原样带回）。
> - **⑥ 通讯录入口**：iOS 是四条（群聊/新的朋友/公众号/服务号，各自底色），
>   本端只有两条且第二条是「发起群聊」——那是**动作**不是入口。已对齐，
>   新增 `GroupListScreen`（副标题是**群主**不是人数：`GET /groups` 的 `Summary`
>   根本不下发 `member_count`，照详情页写就恒显「0 人」）。
>   点好友行改成**先进资料页**（此前直接进聊天，违反三端统一口径）。
> - **⑦ 差异档里能做的**：清空聊天记录、举报、推荐给朋友、拉黑/删好友已随 ③ 落地。
>
> 全量 **358 例 / 57 类**绿。`DetailActionsTest` 变异验证过（掐掉"非好友早退"
> 与"取消拉黑不标红"两处，3 条当场红）。
>
> **同时改了 `../IMServer/docs/SYMMETRY.md`**：把本端 UI 页面与判据层登进对称登记表，
> 提交期会提醒「iOS 对应页有没有本端没画的块」——这次三整块缺失一次也没被任何门禁拦住。

## 2026-09-09 —— UI_PARITY 三块 + 会话内搜索（从 current_task.md 移入）

> **会话内搜索（`docs/UI_PARITY_IOS.md` 里剩下的 🔴 之一）✅ 2026-09-09 —— 顺带补上「跨窗定位」**
>
> 详情页那颗「搜索」pill 此前点了弹「还没做」。现在：pill → 回聊天页进搜索态，
> **顶栏换搜索框、底栏换命中导航条**（`第 N / M 条` + ▲更旧 ▼更新），命中词在气泡里
> 用 `accentSoft` 标底（不硬编码黄，三端同一条），默认停在**最新一条命中**。
> 结构照 iOS `IMChatViewController+Search.m`，判据照 `SEARCH_DESIGN.md §4`。
>
> **这一块真正的难点不是搜索框，是两件容易静默错的事：**
>
> ① **"整会话问题"不能拿渲染窗口回答**。本端聊天页只渲染「最近 N 条」，
>    顺手拿 `messages` 过滤一遍是最自然的写法，也正是 im-web 栽过的那一跤
>    （3 万条的群里只命中 98 条 = 窗口条数，界面照常、结果是错的）。
>    所以搜索**查库**（新增 `MessageDao.search`），`ChatSearchController` 从签名上就拿不到渲染窗口。
>    命中口径三处对齐：DAO 的 SQL ↔ 纯函数 `ChatSearch.matches` ↔ 后端 `SearchConvMessages`
>    （text 的 content / 任意 caption / file_name 子串；媒体的 content 是 URL，不参与）。
>
> ② **命中多半不在渲染窗口里**，只滚列表必然落空。新增 `ChatLocator`：先查库算出
>    「要把窗口撑到多少条才盖得住这一条」，撑完再让 `ChatScreen` 滚过去 + 高亮 1.2s。
>    **有上限（5000 条）**——13 万条整窗构造对象会把聊天页渲染成空白（DAO 注释里记着这次实测），
>    超了就如实说跳不过去，不假装跳了。引用块跳转也改走这条路，**顺带从"窗口内才可点"
>    升级成能跨窗**（此前翻不到那么早的原消息连点都点不了）。
>
> 分流照 `OFFLINE_BACKLOG_DESIGN §4.9` 三态（`ChatSearch.pickSource`）：本地齐全走本地 /
> 有缺口且在线问服务端 G4 / 有缺口且离线给本地结果**并把「离线：仅搜索已下载的消息」显出来**
> ——文案与 im-web `DEGRADED_SEARCH_NOTICE` 逐字一致。命中被单页上限截断时计数补 `+`
> （本地 500 / 服务端 50），不悄悄显示成"总共就这些"。
> 「本地齐不齐」本端有自己的算法（游标连续推进，`syncedConvSeq >= lastConvSeq`），
> **对齐的是"有没有缺口"这个不变式，不是 im-web 那套区间清单**；另外补了一档
> `localCount == 0`——「清空聊天记录」刻意保留游标，不判这一档清空后会一本正经地回"无匹配"。
>
> **没做（都在 SEARCH_DESIGN 自己的 P1 里）**：📅 日历按日期跳转、👤「来自某人」发件人过滤
> （服务端 `?from=` 与本端接口都留着，缺的是成员下拉那层 UI）、翻更多页命中、
> 「@我的消息」聚合（服务端有 `/mentions`，**三端都没接**）。
>
> **`/code-review` 打回 6 条，全修了**（这一轮它又是最大的发现渠道）：
> ① 撑窗口**零余量**——算出来的条数是"目标恰好成为最旧一条"，期间只要再落库一条消息
>   跳转就静默落空且没有任何提示；已加一页余量 + 3s 超时兜底（唯一无反馈的分支补上了）。
> ② `onGrowWindow` 是绝对赋值且比较的是**过期快照**，并发上翻时会把窗口缩回去 →
>   翻页保位条件再不成立 → 「滚到顶加载更早」被在途标志永久卡死。改成
>   `currentWindow()` 取当下值 + 唯一写入点取 `maxOf`（不变式下沉，不靠调用方自觉）。
> ③ 取数与"默认跳最新"焊在一个 effect 里，一次断线重连就把用户翻到第 5 条的位置抢回最新那条；
>   已按 im-web `searchSigRef` 的口径拆成两个 effect，换数据源只重取不重跳，
>   并按 `conv_seq` 保住当前选中项。
> ④ **「清空聊天记录」之后的口径与 im-web 反了**：我原本判"有缺口"→在线时跑去服务端，
>   把用户刚亲手清掉的消息整整齐齐搜回来。已对齐 im-web：仍算齐全、如实回「无匹配」。
> ⑤ 截断标志用**过滤后**的条数反推，漏一条 `+` 就没了 —— 改由查询层给（`LocalSearchPage`）。
> ⑥ 引用块指向自己删掉的消息时提示"需要联网加载"，把原因归给了网络 —— 拆成两档文案。
>
> **真机验过**（Pixel 2 XL / API 30，11 万条大群）：命中数与服务端库逐条相符、
> 跨窗跳转 + 居中高亮、超上限如实提示、返回键分层。**真机顺手撞出两个自己的坑**：
> 命中行原本顶到视口**顶端**（`CHAT_UX §3.1` 要的是居中，已补 `centerItem`）；
> 拒绝提示原本走吐司，而搜索态下键盘占着下半屏，**吐司整个落在键盘背后**——
> 改写进搜索条上方那一行，并顺手给 `IMToast` 补了 `imePadding`（这是全局的，别的页面也受益）。
>
> 437 例 / 67 类绿；`ChatSearchTest` 16 例，六处判据各做了一次变异验红（分流 / 转义 /
> 命中口径 / 高亮位置 / 截断补 `+` / 清空后齐全判定）。
> **没验**：离线降级那一档（要断网造本地缺口）、服务端兜底那条路（本机会话本地都是齐的）。

> **归档长按菜单 + 定位到聊天 ✅ 2026-09-09（`UI_PARITY_IOS.md` 的 🔴 之二）**
>
> 上一条把「跳到指定 conv_seq」这层地基打好之后，这一条就只剩接线了。菜单项与顺序逐条抄
> iOS `contentMenuConfigForMessage:`（转发 → 定位到聊天 → [取消下载] → 删除两档），
> 判据抽成 `data/ArchiveActions.kt`（12 例单测，六处变异各验红一次），接线收在
> `ui/ArchiveActionsHost.kt`——**单聊内联页签与群资料独立归档页共用同一份**，
> 与 iOS 那侧"四个页签 + 宫格全汇到一个菜单构造器"同构。
>
> 顺带收了三处重复：① 「两档删除收进子菜单」这条规则原本只在气泡菜单里，现在是泛型的
> `buildMenuWithDeleteSubmenu`，气泡与归档共用；② 转发的串行发送与 `forwardFrom` 口径抽成
> `forwardMessages`（聊天页与归档共用，各写一遍迟早分叉成"某个入口转出去的消息少了转发自"）；
> ③ 「关掉覆盖页、回聊天页顺带做一件事」原本是个布尔，现在是 `ChatArm`（开搜索 / 定位互斥）。
>
> 体量：`ChatHost` 590→548（长按菜单整块移进 `MessageMenuItems.kt`），
> `GroupInfoHost` 599→573（成员长按菜单移进 `GroupMemberMenu.kt`，它已经撞过一次 600 硬闸）。
>
> 448 例 / 68 类绿。模拟器验过：长按宫格弹菜单、非自己发的只有单档删除、「定位到聊天」
> 关页并滚到那条、「转发」开出选择页且返回键只关它。**没验**：删除两档的实际执行、
> 「取消下载」（要造下载中状态）。

> **群详情改内联页签 ✅ 2026-09-09（`UI_PARITY_IOS.md` 的 🔴 之三）**
>
> 群资料页此前是「一长串成员 + 一行『聊天媒体』跳出去一整页」，而单聊那侧早就是内联页签——
> 同一件事在两种会话里长得完全不一样。现在群侧也是**成员 / 媒体 / 文件 / 语音 / 链接**五格，
> 成员只是群聊多出来的那一格（`DetailTabs.visible(isGroup)` 本来就这么定义的，只是没人用）。
>
> **两页长得一样最可靠的保证不是各写一遍对着改，是同一段代码画的**：
> 页签内容抽成 `DetailArchive.archiveTab`（`LazyListScope` 扩展），取数抽成
> `rememberConvArchive` + `rememberLinkMessages`，长按菜单本来就已经是共用的
> `ArchiveActionsHost`。整页的 `ConvMediaScreen` 与 `ConvMediaHost` 随之删掉——
> 那一版只有「图片与视频 / 文件」两格，没有语音与链接，空态文案还另起一套。
> 群侧因此**白捡了语音与链接两格**。
>
> 体量：`GroupInfoHost` 一度涨到 616 撞破 600 硬闸，把管理项编辑框移进
> `GroupInfoDialogs.GroupManagePrompts` 之后回到 574。
>
> 448 例 / 68 类绿。模拟器验过：群资料五格页签、默认停在「成员」、「媒体」内联出宫格、
> 「链接」出脚注与链接行（这两格改造前群侧根本没有）。

> **按锚点开窗 `window_req` ✅ 2026-09-09（`MESSAGE_WINDOW_DESIGN` 的 Android 那一期）**
>
> 服务端与 iOS/Web 早在 8-31/9-01 就做完了（W1–W3），Android 列一直是 ⬜。
> 上午那版跳转是「把『最近 N 条』的 N 撑大到盖住目标」，必须带 5000 条上限
>（13 万条整窗构造对象会把聊天页渲染成空白），上限之外只能如实说跳不过去。现在换成锚点窗：
> **不论目标多早，取的都是它前后各一页**。实测在 11 万条的大群里跳到 `conv_seq 7`（会话开头）一次到位。
>
> 窗口只有两态（`ChatWindow`）：`Tail`（贴最新，新消息会进来）/ `Anchored`（钉在一段闭区间，
> 新消息不进来——用户正在看历史）。边界用**显示序坐标**（timestamp 主排、同毫秒按 conv_seq），
> 必须与 DAO 的 `ORDER BY` 逐字对应，否则在"同毫秒多条"那一小段上会多取或少取几行。
>
> **三条容易漏的**，两条 iOS/Web 都栽过：
> ① **窗口停在历史时必须亮出「回到最新」**——跳转不产生滚动事件，而且跳过去的那一段常常整屏
>   放得下，连"离底很远"的兜底都轮不到。判据抽成 `ChatWindows.showsJumpToLatest` 并单测钉住。
> ② **「回到最新」不能在点击回调里直接贴底**：换窗是异步的，那时 rows 还是旧那一窗，
>   滚过去只会落在旧窗末尾（真机撞见：从会话开头点 ↓，落在半空中）。改成记待办、等新一窗到了再滚。
> ③ **自己发消息要拉回尾窗**，否则停在历史时发出去的那条看不见，用户以为没发出去
>   （im-web 2026-09-05 修过同一条）。
>
> `window_resp` **只落库、不推进同步游标**：窗口取数是一次性快照，推进游标会让 sync 以为
> 这一段已经覆盖过了（`sync_req` 的 `covered_conv_seq` 是同步正确性的核心，不能动）。
>
> 体量：`MessageService` 与 `ChatScreen` 各撞了一次 600 硬闸，分别拆出 `WindowRequester`
> 与 `ChatComposer`（都是逐行平移）。顺带删掉了"撑窗口"那一套的死代码
>（`windowNeededFor` / `countAtOrAfter` / `MAX_LOCATE_WINDOW` 等）。
>
> 456 例 / 69 类绿；`ChatWindowTest` 8 例，三处判据变异验红。
> **没验**：`window_req` 那条路径本身——两台设备的本地库都是齐的，`windowAround` 恒有结果，
> 走不到问服务端那一支。

> **`msg_op` 离线收敛 ✅ 2026-09-09（真机撞见的既有 bug，比看上去贵）**
>
> 上一轮在 11 万条的大群里撞见聊天页冒出一条 `{"op":"delete","conv_id":…}` 的裸 JSON 气泡。
> **裸 JSON 只是症状**：真正的病是本端**从不应用 `msg_op` 事件行**——也就是
> 离线期间别人做的撤回 / 编辑 / 置顶 / 为所有人删除，重连后本端一律不生效。
> 那条事件行存在的全部意义（PROTOCOL §6.7「离线收敛」）就是给错过实时帧的端补课，
> 本端却把它当成了一条聊天消息落库并渲染。
>
> 对端 im-web 在 `sdk/imSdk.ts` 的 `processIncoming` 开头就有这两个分支，本端一直没有——
> 典型的「一条路改对了、对称兄弟没跟」。判据抽成 `IncomingRule` 三态（5 例单测，两处变异验红）：
> `msg_op` 事件行 → 应用效果、不落库；`deleted_at > 0` → 物理移除；其余照常。
> **顺序不能反**：事件行自己也可能带 `deleted_at`，反过来判会把「删除事件」当成「被删的消息」
> 扔掉，那次删除就永远不被应用。
>
> 改口径只管得住以后的，已经躺在库里的那些要补课：`convergeLegacyMsgOpRows`
> **先应用效果、再删行**（反了等于把那几次操作永久丢掉）。
>
> **两个自己踩的坑**：① 一开始挂在 `onConnected` 上——WS 常常先连上、会话才恢复，
> 那时 `ownerProvider()` 还是空的，整个回调早退（实测 `ws_connected {uid=-}`）；
> 改挂到「账号就绪」（`MainScreen` 的 `LaunchedEffect(owner)` → `IMClient.convergeLegacyDataOnce`）。
> ② 那条按 `contentType` 的查询**没有索引**，每次启动扫 20 多万行，首屏卡了约 90 秒
>（两次 `Long db operation`）；改成 SharedPreferences 一次性标记。
>
> 461 例 / 70 类绿。模拟器实测：收敛跑过一次并落日志、大群尾部的裸 JSON 气泡消失。

> 更早的已完成块已移入 [current_task.archive.md](current_task.archive.md)（只读归档）。

## 2026-09-09 —— 通讯录 A–Z 索引尺 + 好友行左滑（从 current_task.md 移入）

> **通讯录 A–Z 索引尺 + 好友行左滑（`docs/UI_PARITY_IOS.md` ③，两条 🔴）✅ 2026-09-09**
>
> 好友列表原来是一条平铺的名单，2000 人只能一路滑。现在按显示名（备注优先）的拼音首字母
> 分组，右侧一条自绘 A–Z 索引尺（按下/拖动即跳组，跳组给一次 `CLOCK_TICK` 轻触感），
> 好友行左滑露出 `[删除, 拉黑/解除拉黑]`。判据逐条抄 iOS `IMContactSectionIndex`
> 与 `trailingSwipeActionsConfigurationForRowAtIndexPath:`，清单见 `docs/UI_PARITY_IOS.md` §4.5.1。
>
> **首字母刻意不与 iOS 同实现**：iOS 用 `CFStringTransform`，Android 没有等价物
> （`android.icu.text.Transliterator` 要 API 29，且在跑单测的桌面 JVM 上不存在——写了等于判据没法测）。
> 本端用 `Collator(Locale.CHINA)` 比 26 个边界字。**要一致的是「按拼音首字母分组」这条不变式，
> 不是同一段实现**；所以单测钉规则（多音姓氏表、`#` 三种情况、组间/组内排序、空组不显示），
> 「哪个汉字归哪个字母」由真机核对。
>
> **真机（模拟器 im_test / API 36 / user1001 / 2014 个好友）抓出三条，桌面单测一条都测不出来**：
> ① 组头的 LazyColumn key 写成了字面量（`$` 被转义），所有组头共用一个 key，
>   **画到第二组当场崩**（`Key ... was already used`）——LazyColumn 测量期才抛，单测碰不到。
> ② **「阿强」既不在 A 组也不在任何组，直接掉进 `#`**：桌面 JVM 上「阿」≥ 边界字「啊」，
>   Android 的 ICU 上却相反。判据补成「排在第一个边界字之前的汉字仍归 A」。
>   这一档在桌面 JVM 上**走不到**（扫遍 U+4E00–U+9FFF 没有汉字排在「啊」之前），
>   所以比较抽成参数注入（`initialByBoundaries`）——不然那条断言永远绿，正是本仓那条
>   「没红过的断言不算数」。变异验证过：把 `?: "A"` 改回 `?: OTHER` 当场红。
> ③ **2000 人量级会卡到 ANR**：排序键原本写在 comparator 里，`Collator` 于是**每次比较
>   都重建一个 CollationKey**（2014 人 ≈ 4 万多次）。改成先算完键再排 + 首字母按首字缓存，
>   桌面 JVM 实测 98ms → 14ms、取首字母 38ms → 0.9ms。
>
> **真机逐条验过**：A–Z 分组显示、索引尺点跳（A/L 精确落到组头；Z/# 触底夹紧是列表本来的行为）、
> 拖动连续跳组、左滑露出两格且**删除在最外侧**（同 iOS 数组第一个贴边）、
> 拉黑→行显「已拉黑」且服务端 `blocked=true`、解除拉黑、删除**二次确认**后
> 服务端好友数 2014→2013 且 A 组整组消失（空组不显示，实时成立）、2013 行快速滑动不掉帧。
>
> **`/code-review` 打回 8 条，全修了**（§4.5.1 有逐条表）：分割线被 `Box` 叠到行首、
> 「拉黑」格借用半透明令牌导致白字看不见（iOS 那侧是不透明 systemGray）、
> 滑开的行仍可点且多行能同时敞开、`Collator` 单例的线程约束没写、
> 三处裸 `runCatching` 吞 `CancellationException`、`reload()` 空 catch 静默吃掉所有失败、
> 索引尺 `+1` 偏移没被单测钉住、重音拉丁掉进 `#`。
> **顺带删掉一段死代码**：组内排序照抄 iOS 多了一档大小写兜底，变异验证发现删了没测试变红
> ——查证后确认它永远走不到（本端 CollationKey 是 TERTIARY 强度，本来就区分大小写）。
> 这正是「照抄代码形状而不是不变式」的样子。
>
> **欠账**：选好友页没跟（iOS 那侧通讯录与选好友页共用同一套分桶，本端 `CreateGroupScreen`
> 仍是平铺；`ContactSection` 是数据层，复用零成本）；CJK 扩展 B 及以后仍归 `#`；
> 分组仍在组合期主线程算（真机 2013 人不掉帧，挪后台要先解 `Collator` 线程安全）。
>
> **删除加了二次确认，iOS 没有**：删好友不可撤销，而左滑+点一下只有两个手势；
> im-web 与本端资料页的删好友都有确认，三处里两处有，缺的那处更像 iOS 的疏漏。

---

# 归档于 2026-10-01（从活快照「当前焦点」移入较早批次：均已完成/已提交，待办仍以「下一步」「已知坑」为准）

> **2026-09-30 撤回 / 删除后收回通知（Android 侧）**：设计 `../IMServer/docs/design/PUSH_M5_DESIGN.md` §3.4。
> `fcm/FcmNotifications.kt`（新）：展示通知时把 `conv_seq` 记进 extras；收到服务端 `type=retract` 的 FCM
> （`FcmMessagingService`）或在线时 `msg_op` 撤回/删除落库（`MessageRepository.applyMsgOp`，实时与 sync 同一入口）
> 都调 `retract()`——**只有通知栏里挂着的恰好是被收回的那条才取消**（同一会话只留最新一条通知）。
> 纯判据 `shouldCancel` + `FcmNotificationsTest`，`FcmPayload` 多解一个 `retract`。
> 「点通知定位到具体消息」不做：通知指的永远是最新一条，进会话本来就看得到。

> **M5 批次 2：FCM 离线推送接入 🚧（2026-09-30，工作区改动，未提交/未合并，未做真机验证——等
> 用户拿到真实 `google-services.json`）**：`../IMServer/docs/design/PUSH_M5_DESIGN.md` 原定 Android
> 走「后台保持连接」（文档 §6，尚未开工），**父任务简报拍板改走 FCM**（服务端 `internal/push` 正在
> 并行加 FCM sender，协议形状已定死，本文档 §6 与 `../IMServer/docs/design/PUSH_M5_DESIGN.md` 尚未
> 同步这个改动，留给协调方）。
> - **Gradle**：`build.gradle.kts`/`app/build.gradle.kts` 加 `google-services` 插件（**按
>   `app/google-services.json` 是否存在条件 apply**，没有文件时跳过插件、纯靠 `implementation`
>   声明的 firebase-messaging 依赖照常编译）+ Firebase BoM 34.19.0 + `firebase-messaging`
>   （主包已含 Kotlin 扩展，`-ktx` 独立产物已停更，不再单独取）+ google-services 插件 4.5.0。
>   **已实测**：本机没有 `google-services.json` 时 `./scripts/test.sh` 全绿（见下）。
> - **`fcm/FcmMessagingService.kt`**（新目录 `fcm/`，避免与既有 `data/PushNav.kt`/
>   `ui/components/PushTransition.kt` 的"页面 push 转场"命名撞车）：`onNewToken` 转发给
>   `IMClient.fcmTokenStore`；`onMessageReceived` 只读 `data` payload（不用 `notification` 字段，
>   App 被杀死也要能自定义处理）、纯函数 `fcm/FcmPayload.kt` 解析、`NotificationCompat` 建系统通知，
>   点击带 `conv_id`/`title` 经 `MainActivity` 落进 `data/NotificationRoute.kt`。**不在客户端重复判定
>   该不该提醒**——服务端已经跑过 `alertDecision` 才会推。
> - **令牌上报时序**（`data/FcmTokenStore.kt` + `sdk/api/PushTokenApi.kt`）：`FcmTokenSync.decide`
>   纯函数（没会话 Defer / 同值 Skip / 否则 Put），`FcmTokenStore` 编排——没登录时先记 `pending`，
>   `IMClient` 在 `socket.state==Connected` 时补 `onSessionReady()` + 主动取一次当前 token
>   （对齐 iOS `IMProgram` 踩过的"`onNewToken` 早于登录"时序坑）。`logout()`/`sessionEnded` 只
>   `forget()` 本地状态，**不调服务端 delete**——正常退出登录服务端按会话联删。
> - **点通知跳转会话**（`data/NotificationRoute.kt`）：设计意图参照 iOS `IMPendingNotificationRoute`
>   （不照抄实现）——只有 `ui/MainScreen.kt` 真把会话摆上屏幕（本地已有 or 用 `conversationStubFor`/
>   `groupConversationStubFor` 现造占位会话，与扫码加群等入口同一手法）才 `consume`，账号未就绪时
>   靠 Compose 因 `owner` 变化自然重跑，不用自己起重试定时器。`MainActivity` 改 `launchMode=
>   singleTask` + `onNewIntent`，避免热启动点通知叠出第二个 Activity 实例。
> - **设置页开关**：`ui/NotificationSettingsHost.kt`/`NotificationSettingsScreen.kt`「锁屏与后台
>   通知」组里把原先的占位行「显示通知」换成做实的「接收离线推送」开关（i18n 串
>   `notif_system_receive_push` 此前已经预备好、只是没接上——发现它明确对齐 iOS 侧 `PUSH_M5_DESIGN.md`
>   §5 的两行布局）；「通知权限」行也已做实（行序同 iOS：权限在上、开关在下）——右值已开启/未开启，
>   已开启点了跳系统的应用通知设置页，未开启先申请运行时权限、系统不再弹框时补「去设置」提示框，
>   回前台重查状态；纯判据在 `data/NotificationPermission.kt`（`NotificationPermissionTest` 6 例）。
>   脚注换成新串 `notif_system_footer_android`，旧的「推送通知正在开发中」串已从 i18n 源删除。
>   **未在真机验证的一条**：「未开启」那一路（申请/提示框）——这台 OPPO 上 adb 撤不掉权限，只验了
>   「已开启 → 跳系统设置」。
> - **测试**：新增 `NotificationRouteTest`（7 例）、`FcmTokenStoreTest`（12 例，纯判据 + 编排）、
>   `FcmPayloadTest`（5 例，data payload 解析）；`PushTokenApi`/`FcmMessagingService`/`FcmToken` 三处
>   直接碰 HTTP/Android系统/Firebase SDK，未补测试（同 `DevicesApi` 等既有薄封装类的既有口径）。
> - **`./scripts/test.sh` 1084/1084 绿**（153 个测试类，较批次 1 完成时 +3 类）。
> - **`app_state` 上行帧已补齐（2026-09-30，协调方后续接的）**：`sdk/protocol/Envelope.kt`
>   `FrameType.APP_STATE` + `sdk/protocol/Messages.kt` `AppStateData`；`data/AppStateReport.kt`
>   （`IMSocketManager.reportAppState(foreground)` 扩展函数）在两处调用——`MainActivity` 的
>   `AppActive.current` 赋值点即时发一次（同 iOS `sceneDidEnterBackground`/`sceneWillEnterForeground`
>   直接调用的方式，未额外引入响应式订阅）；`IMClient` 里 `socket.state` 变 `Connected`
>   （新连接/断线重连）时补发一次**当前实际状态**——不假设新连接一律前台，同 iOS
>   `sendAppStateAfterHandshake` 当初补的同一个坑（覆盖"App 已经在后台、连接才恢复"的场景）。
>   未连接时 `send` 返回 false 静默丢帧，协议允许（§6.12：丢了只退化成 60 秒心跳超时前不推）。
>   `EnvelopeTest` 补了线格式契约测试钉住 `"foreground"`/`"background"` 字面量不被手滑改错。
>   `PUSH_M5_DESIGN.md`/`CLIENT_PARITY.md` 已同步（协调方做的）。
> - **仍未做/已知限制**：① 没有 `google-services.json`——真实收发推送、真实 token 上报全部未验证，
>   等用户提供后需要真机复核；② 没接 Android 13+ `POST_NOTIFICATIONS`
>   运行时权限请求流程（manifest 已声明权限，`NotificationManager.notify()` 没权限时官方行为是
>   静默不弹，不崩，但用户不会被引导去开）；③ `conv_seq` 只解析不用于"打开会话跳到那条消息"；
>   ④ 没有做「App 前台时点开某会话清空该会话系统通知」这类更细联动（`setAutoCancel` 保证点开即消，
>   仅此而已）。

> **M5 批次 1：账号级通知设置 ✅（2026-09-30，工作区改动，未提交/未合并，未做真机验证）**：
> `../IMServer/docs/PROTOCOL.md` §6.13/§11 + `../IMServer/docs/design/PUSH_M5_DESIGN.md` §3.3——
> 私聊/群聊 `{enabled,preview,sound}` 与 `badge.include_muted` 三项挪到账号级、多端同步；
> 应用内三项（声音/振动/横幅）与桌面音量仍是每台设备本地。**本批不含推送令牌/前台服务**（那是
> M5 批次 2，后端 IMServer 与其余端正在并行做，本仓未跟）。
> - **同步/迁移纯函数** `data/AccountNotifySettingsSync.kt`：`AccountNotifySettingsSync.decide`
>   （GET 应答 `exists=false`→迁移本地上去 / `exists=true`→按版本号覆盖本地 / 版本过期→丢弃）+
>   `onTrigger`（冷启动/重连：本地上次 PUT 失败过的"脏"标记→先补 PUT 不发 GET；否则正常 GET）。
> - **编排** `AccountNotifySettingsStore`（同文件）：`start`（登录/连上时调）、`onPushed`（收
>   `notify_settings_update` 版本号，去重后重拉）、`save`（设置页编辑三项之一→本地立刻生效 + PUT，
>   **失败不回滚**、标脏，下次 `start` 补），`forget`（退出登录：本地三项也退回默认，否则下一个
>   账号在这台设备上先看见上一个人的通知设置——同 `downloadSettingsStore.forget()`/
>   `InAppBannerStore.dismiss()` 那条跨账号泄露纪律）。
> - **JSON 编解码** `sdk/api/NotifySettingsApi.kt`：`NotifySettingsWire`（纯函数，`badge.include_muted`
>   snake_case、`sound` 编成 wire 字符串、未知值/缺字段各自回落默认）+ `NotifySettingsApi`
>   （`GET/PUT /api/v1/notify-settings`，解析失败回退 `exists=false` 让上层走迁移分支，不抛）。
> - **协议**：`FrameType.NOTIFY_SETTINGS_UPDATE`（`notify_settings_update`）+ `NotifySettingsUpdateData`
>   （`sdk/protocol/`）；`MessageService` 新增 `notifySettingsUpdates: SharedFlow<Long>`（与
>   `capabilityUpdates` 是两条独立版本序列，不混用）。
> - **接线**：`IMClient` 新增 `accountNotifySettingsStore`（`fetch/put` 走 `NotifySettingsApi`，
>   `localFields/applyLocal` 读写既有 `NotificationSettingsStore` 的 `private/group/badge` 三项，
>   `data/NotificationSettings.kt` 新增 `accountFields()`/`withAccountFields()` 这对提取/回填扩展）；
>   `socket.state==Connected` 与 `messages.notifySettingsUpdates` 各挂一条协程，`logout()`/
>   `sessionEnded` 都调 `forget()`。`AppRoot.kt` 进主界面后 `LaunchedEffect(Unit)` 拉一次（同
>   `refreshDownloadSettings` 的位置与理由）。`NotificationSettingsHost.kt` 的私聊/群聊/角标三项
>   编辑改走 `client.accountNotifySettingsStore.save(...)`（新 `updateAccount` helper），「重置」
>   在本地 `NotificationSettingsStore.reset()` 之外补一次 `updateAccount(DEFAULT)` 把默认值 PUT 上去；
>   应用内三项（声音/振动/预览）不变，仍是 `NotificationSettingsStore.update` 直连。
> - **同一逻辑的另外两端**：iOS `IMNotificationSettings`、Web `notifySettings.ts`（本仓代码注释里
>   已各处标注）；`../IMServer/docs/SYMMETRY.md` 尚未登记这一条（IMServer 那侧还在并行实现，登记
>   由协调方补，本仓未动 IMServer/SYMMETRY.md）。
> - `./scripts/test.sh` **1062/1062 绿**（150 个测试类；新增 `AccountNotifySettingsSyncTest`
>   7 例、`AccountNotifySettingsStoreTest` 8 例、`NotifySettingsWireTest` 6 例，三类判据——迁移
>   exists 分支、版本号新旧、脏了补 PUT——均临时改坏实现确认先变红过，见下方 mutation 记录）：
>   ① `decide` 的 `!response.exists` 取反 → 4/7 红；② `start` 忽略 `dirty` 恒 `Refresh` → 2/8 红
>   （正是「脏了补 PUT」两例）；③ `include_muted` 改回 `includeMuted` → 2/6 红（snake_case 与往返
>   两例）。三处均已改回，最终整仓 1062/1062 绿。
> - **未做真机验证**：真实连后端跑一遍迁移（`exists=false` 首次 PUT）、换设备登录看是否覆盖、
>   两台设备互改验证 `notify_settings_update` 推送与去重、断网时编辑验证「不回滚+标脏+重连补 PUT」
>   的真实观感——均只过了编译与 JVM 单测（纯逻辑，无 Compose 时序风险，但网络时序历来建议真机复核）。
>   **后端 `GET/PUT /api/v1/notify-settings` 与 `notify_settings_update` 推送由 IMServer 侧并行实现**
>   （本次未见 `../IMServer/docs/SYMMETRY.md` 登记，落地后建议核对协议字段与本仓 `NotifySettingsWire`
>   逐字一致）。

> **通知与提示音 P1 第二批 ✅（2026-09-29，`feature/notif-p1b` 分支，从 main 切出，未合入 main，
> 未做真机验证）**：`../IMServer/docs/design/NOTIFICATIONS_P1_DESIGN.md` §4/§5——定时免打扰。
> 后端已上线 `mute_until`（PROTOCOL §6.10），本轮只接客户端。详细改动清单、判定表、已知缺口见
> `docs/UI_PARITY_IOS.md` §4.14（唯一来源，这里只记要点，不重复）。
> - **判定** `data/MuteState.kt`（`isMutedNow`/`untilLabel`，共用向量 `conformance/mute_state.json`）
>   + **时长映射** `data/MuteDuration.kt`（1 小时/8 小时/1 天/7 天/永久）。**所有**读 `muted` 的地方
>   （会话列表铃铛/`strongAlert`、`TabUnread.count`、`IncomingAlert`、`NotificationExceptions`、
>   `Forward.exceptionPickable`、`ChatDetailHost`/`Screen`、群资料页 `GroupInfoSettings`）全部换成
>   `isMutedNow`。
> - **UI**：`ui/components/MuteDurationSheet.kt`（复用既有 `ActionSheet` 样式）+
>   `ui/components/MuteTick.kt`（`rememberMuteTick`，到期精确定时器 + 前台回来兜底刷新，不发请求）。
>   三个入口：会话列表左滑/长按（`ui/MainScreen.kt`）、聊天信息页「消息免打扰」行
>   （`ui/ChatDetailHost.kt` + `ui/screens/ChatDetailScreen.kt`，开关→带右值的行 + 脚注）、
>   「添加例外」选择页选完会话后弹时长菜单（`ui/NotificationSettingsHost.kt`）。
> - **存储**：`ConversationEntity` 加 `muteUntil`，Room `IMDatabase` v12→v13（`MIGRATION_12_13`）；
>   `ConversationsApi`（`ConversationSummary`/`ConversationSettings`/`updateSettings` 新增可选
>   `muteUntil` 参数）、`ConvUpdateData`、`MessageRepository` 列表同步与 `applyConvUpdate` 同步跟进。
> - **已知缺口（刻意，非疏漏）**：群资料页「消息免打扰」仍是原有的纯开关，未接时长菜单/右值文案
>   （任务给定的三个入口清单不含它），只把读点换成了 `isMutedNow`。
> - `./scripts/test.sh` **1038/1038 绿**（146 个测试类；新增 `MuteStateTest`/`MuteDurationTest`
>   + `ForwardTest`/`NotificationExceptionsTest`/`TabUnreadTest` 补充定时免打扰到期用例，均先看红
>   一次——临时改坏 `MuteState.isMutedNow`/`Forward.exceptionPickable` 确认变红过）。
> - **未做真机验证**：时长菜单三个入口的样式、到期后铃铛/角标/例外列表是否真的自动刷新、另一端
>   `conv_update` 同步、定时免打扰期间改置顶/标未读/群备注 `mute_until` 是否不变——均只过了编译与
>   JVM 单测，Compose 定时器/手势时序历来测不到（`CODING_STYLE.md` §八）。

> **通知与提示音 P1 第一批 ✅（2026-09-29，`feature/notif-p1a` 分支，未合入 main，未做真机验证）**：
> `../IMServer/docs/design/NOTIFICATIONS_P1_DESIGN.md` 第一批——应用内横幅 + 「添加例外」，
> 后端零改动，定时免打扰（第二批）本轮未动。
> - **`alertDecision.banner`**：`data/AlertDecision.kt` 从「P0 恒 false」改为移动端分支
>   `banner = settings.inApp.preview`（资格同 sound/vibrate，不受节流影响）。共用向量
>   `alert_decision.json` **32 条**全绿（`app/src/test/resources/` 拷贝与 `IMServer/docs/conformance/`
>   源逐字节一致，`resourceMatchesSourceOfTruthWhenPresent` 测试钉住这一点）。
> - **应用内横幅新组件**：`ui/components/InAppBanner.kt`（`InAppBannerHost`，挂 `ui/MainScreen.kt`
>   根 `Box` 最上层——不是 `AppRoot`，因为点横幅要用只存在于 `MainScreen` 的 `openConv` 导航状态）
>   + `data/InAppBanner.kt`（`BannerContent`/`BannerFormat`/`InAppBannerStore`，纯数据层）。
>   `data/IncomingAlert.kt` 在 `result.banner` 为真时调 `InAppBannerStore.show`。滑入 250ms/
>   4 秒自动收/按住暂停计时（松手接着倒计时，不是重算满 4 秒）/上滑收起/点击进会话同一条路径
>   （`MainScreen` 按 convId 在 `conversations` 里查会话）后自身收起/新横幅原地换内容重开计时/
>   打开该会话自动收起（`LaunchedEffect(openConv)` 调 `dismissIfShowing`）/`animationsEnabled`
>   关时无位移。头像/标题/正文口径与会话列表行、`ConversationPreview.of` 同源，不另写一套。
> - **主页「应用内预览」真开关**：`NotificationSettingsScreen.kt` 占位行换 `IMSwitchRow`，
>   组脚注换 `notif_in_app_preview_footer`。
> - **「添加例外」**：`NotificationTypeScreen.kt` 例外组改常驻（撤销 P0 commit `82c1687`），
>   组首绿色圆形 ＋「添加例外」行；点击复用 `ForwardPickerScreen.kt`（新增
>   `filter`/`title`/`footer`/`emptyText`/`allowMulti`/`confirmSingleTap` 可选参数，原转发调用点
>   零改动、行为逐字不变），单选、点一行直接免打扰（第一批=永久）并关闭。过滤纯函数
>   `data/Forward.kt#exceptionPickable`（这一页类型 + 未免打扰 + 非系统通知会话），与既有
>   `pickable` 并列。选择页是 `IMCardSheet`，自带 `BackHandler`，返回手势天然可用。
> - **新增令牌**：`ui/theme/Tokens.kt` 加 `radiusBanner = 12.dp`。
> - `./scripts/test.sh` **1022/1022 绿**（144 个测试类；新增/改动测试：`AlertDecisionTest`
>   随 32 条向量、`ForwardTest` 新增 3 例 `exceptionPickable`、新文件 `InAppBannerTest` 7 例
>   `BannerFormat`/`InAppBannerStore`，均先临时改坏实现确认变红过）。
> - **未做真机验证**（Compose 手势/动画时序历来测不到，见 `CODING_STYLE.md` §八）：横幅出现/
>   点击进会话/上滑收起/4 秒自动收/连发多条原地换内容/按住暂停计时/进入该会话自动收起/预览关
>   显示「新消息」/「动画」关无位移/通话中不出；「添加例外」选择页选中后另一端 `conv_update`
>   同步、`pinned_at`/`marked_unread` 是否原样带回。**deviation**：横幅正文的群聊"昵称:"前缀
>   解析用 `nameOf = { null }`（数据层没有 Compose 好友表可查），会退回服务端昵称快照/uid，
>   不带本地备注——`ConversationPreview.of` 既有退化路径，非新引入的缺口。`docs/UI_PARITY_IOS.md`
>   已加 §4.13。
> - **第二批（定时免打扰）已完成**，见上方新条目；Web 标签页角标不属本仓（属 im-web 仓）。
> - **自审补的一处 bug**：横幅手势 `pointerInput(Unit)` 的 key 恒为 Unit，第一版直接把每次新横幅的
>   `onOpen`/`onDismiss` 传进去——手势协程只在首次组合时启动、不随新横幅重启，点被替换后的横幅会
>   打开上一条横幅的会话（同 `PassThroughTap.kt` 类注释记的坑）。已用 `rememberUpdatedState` 补上
>   （提交 `8bb30dd`）。
> - **`/code-review medium` 抓出并已修的 3 条（2026-09-29）**：
>   ① `IMClient.logout()` 没清 `InAppBannerStore`——它是进程级单例，账号 A 来消息挂着横幅时退出
>   登录、账号 B 在同一进程登进来会先看见 A 的会话标题/头像/摘要（跨账号泄露）；`logout()` 补一行
>   `InAppBannerStore.dismiss()`，与它旁边 `downloadSettingsStore.forget()` 同一条纪律（账号级状态
>   必须清）。
>   ② `NotificationSettingsHost.kt` 的 `conversations` 用 `collectAsState(initial = emptyList())`，
>   「添加例外」选择页在本地库还没回第一份时会抢答"没有可添加的会话"（闪一下空态）——改成
>   `initial = null`（同 `MainScreen.kt` 既有判据），`conversations == null` 时不传 `emptyText`。
>   ③ `InAppBannerHost` 的 `onOpen` 原先点了就无条件 `dismiss()` 再回调，若 `MainScreen` 那份
>   `conversations` 流还没收敛到刚建好的会话（横幅弹出只看本地 `ConversationEntity` 是否已入库，
>   两处时机不保证一致），会是一次死点击——横幅消失但没跳转。改成 `onOpen: (String) -> Boolean`，
>   查不到就不收起，用户能再点一次；`MainScreen` 顺手把查表从 `firstOrNull` 改成
>   `remember(conversations) { associateBy { it.convId } }`（同一条 review 顺手指出的 O(n)→O(1)，
>   与 `Forward.kt`/`FavoritesHost`/`CallHistoryHost` 既有手法一致）。
>   **未采纳的 2 条**（有意保留，非疏漏）：④「应用内通知」组脚注从通用文案换成
>   `notif_in_app_preview_footer` 后不再解释 sound/vibrate 两个开关——这是设计文档 §1.3 原文
>   明确指定的替换（"组脚注换成 notif.in_app.preview_footer"），不是本端自选；⑤ `muteAsException`
>   失败只写日志不 toast——与同文件里的 `unmute`、`MainScreen.kt` 的 `ConversationMenu`（pin/mute/
>   markRead 走同一个 `runCatching` 套路、甚至**不落日志**）是同一个仓库级既有模式，单独给新代码
>   补 toast 会造成新旧行为不一致，留给专门收口这类静默失败的后续任务一起做。

> **设置 ▸ 外观 ✅ 对照 iOS 全量落地（2026-09-29，已合入 main；OPPO PKD130 真机验过）**：
> 四卡片逐行照抄 `IMAppearanceViewController`——14 主题 + 横向主题条 + 主题/壁纸网格（真实聊天缩略图）、
> 夜间模式开关 + 跟随系统/浅色/深色、字号/信息框圆角滑块（拖动即生效、取消还原）、动画开关、四选一应用图标。
> 偏好 `data/AppearancePrefs.kt`（纯函数）+ `AppearanceStore.kt`（SharedPreferences + StateFlow），配色
> `ui/theme/ChatPalette.kt`（逐值抄 iOS），聊天壁纸 `ui/components/ChatWallpaper.kt`（此前 Android 聊天区是纯色），
> 图标 = 清单四个 activity-alias + `ui/AppIconSwitcher.kt`（**退后台才切**：前台停用启动 alias 会被 ColorOS
> 当场结束任务，真机踩到后改的）。`./scripts/test.sh` 999/999 绿（新增 `AppearancePrefsTest`/`ChatPaletteTest`
> 13 例，均先看红过）。真机验了：主题实时变色、聊天页壁纸、字号拖动 + 取消、强制浅色 + 系统栏翻色、图标切换、还原、
> 两个网格页。差异登记 `docs/UI_PARITY_IOS.md` §4.11。**没验**：纯色/渐变壁纸在聊天页的观感、OPPO 以外桌面的图标刷新。

> **设置 ▸ 通知与提示音 P0 ✅（2026-09-29，已合入 main；真机验过设置页三页/持久化/重置/取消免打扰，实时来消息响铃未验）**：
> `../IMServer/docs/design/NOTIFICATIONS_DESIGN.md` P0 落地——`MeScreen` 组二「通知」行从
> `onComingSoon` 换成真正的 `ui/NotificationSettingsHost.kt`（内部 `NotificationPage` 自成一条
> push 链：主页 → 私聊/群聊子页 → 提示音选择页，不占用外层 `MePage` 深度）。
> - **模型/存储**：`data/NotificationSettings.kt`（纯模型 + `NotificationSettingsCodec` 逐键回落默认值）
>   + `data/NotificationSettingsStore.kt`（SharedPreferences `im_notifications` + StateFlow，镜像
>   `LanguageStore`，设备本地退出登录不清）。
> - **判定**：`data/AlertDecision.kt` 三端共用 `alertDecision` 纯函数，mobile/desktop/browser
>   三个平台分支全部实现（虽然本端只在移动端调用），单测 `AlertDecisionTest` 直接读
>   `IMServer/docs/conformance/alert_decision.json` 30 条向量全过。
> - **播放**：`sdk/AlertPlayer.kt`（`SoundPool` + `USAGE_NOTIFICATION_COMMUNICATION_INSTANT`，查
>   `AudioManager.ringerMode`：非 NORMAL 不响、`SILENT` 连振动也停——比设计文档字面更保守一档，
>   本端解读；`Vibrator` 40ms 一次性振动，`VIBRATE` 权限已加清单）。
> - **接线**：`data/IncomingAlert.kt`（新文件，避免 `MessageService.kt` 破 600 行）挂在 `NEW_MSG`
>   分支——**只有这一条实时路径**会调判定，sync/window 补拉不经过。`viewingConv`/`appActive` 用
>   `data/ViewingConv.kt`/`data/AppActive.kt` 两个极简全局标记（`MainScreen`/`MainActivity` 写，
>   `data/` 层只读，不反向依赖 UI）；`inCall` 读新增的 `RtcCall.inCall: StateFlow<Boolean>`
>   （挂在 `onCallReceived`/`onCallBegin` → 置真，`onCallEnd`/`stop()` → 置假）。
> - **角标**：`data/TabUnread.kt` 加 `includeMuted` 入参（默认 false = 现行口径），`MainScreen.kt`
>   接「角标计数 ▸ 包含免打扰会话」。`TabUnreadTest` 补 3 例。
> - **已知限制**：① 本地还没有这个会话行的第一条消息不提醒（宁可漏一条也不猜 `muted`/会话类型，
>   见 `IncomingAlert` 类注释）；② `inCall` 只有「响铃/接通→挂断」粗粒度，不追踪更细子状态；
>   ③ Android 不出「桌面通知」那组（`NotificationSettings.desktop` 字段只为让 `AlertDecision`
>   单测覆盖桌面/浏览器向量，不接 UI、不持久化）。
> - `./scripts/test.sh` **999/999 绿**（141 个测试类，新增 13 例：`AlertDecisionTest` 2 例读
>   30 条向量、`NotificationSettingsTest` 5 例、`NotificationExceptionsTest` 3 例、`TabUnreadTest`
>   补 3 例；四组新测试均先见红一次——临时改坏 `AlertDecision.decide`/`NotificationSettingsCodec.decode`/
>   `TabUnread.count` 确认变红，再改回来）。**真机验证 ✅（OPPO PKD130，user1001）**：主页/私聊子页/
>   群聊子页/提示音选择页四屏截图与设计稿一致；开关持久化（`shared_prefs/im_notifications.xml` 逐次核对）；
>   提示音选择即试听（logcat 见 `AudioTrack` 创建，确认真的出声）；重置弹二次确认、确认后全部恢复默认；
>   免打扰群聊「libeyond群」正确出现在群聊例外列表，左滑「取消免打扰」后立即从列表消失、会话列表的
>   免打扰铃铛图标同步消失（`PUT settings` 往返成功，`pinned_at`/`marked_unread` 原样带回）；全程
>   logcat 无 crash/FATAL。**未测**：`includeMuted` 对角标计数的真机可见效果（没有现成的"免打扰+未读"
>   会话可复现）、来消息时的完整响铃/振动链路（需要第二台设备/账号发消息触发 `IncomingAlert`，本轮
>   只验证了设置页与试听两条路径）。缺失 i18n key：无（49 个 `notif_*` key 已够用）。
>   `docs/UI_PARITY_IOS.md` 已加 §4.12。

> **六条用户报告第 6 项：消息列表滚动条 ✅ 三端全部收口（2026-09-29）**：Android 本端早已有
> `ChatScroll.kt` 的 `chatScrollbar`（Canvas 直绘）；Web 新补了常驻可见滑块（同思路的纯函数 +
> DOM 直绘，见 im-web 仓 `9e531a4`）；iOS 实测原生 `UITableView` 指示器滑动时本就清晰可见，
> 只是瞬态（iOS 全系统统一的滚动条行为，做成常驻反而破坏平台一致性），未改代码。**至此六条
> 用户报告全部完成**（①②在更早的会话里做完，③④⑤⑥见本节及下方历史条目）。

> **加号面板图标立体感优化 ✅（2026-09-29，用户反馈"图标好丑"，真机验证 OPPO PKD130）**：
> `AttachCell` 圆钮原先用 `c.pageBackground` 纯色块贴着面板背景（`c.surface`），两层灰度太接近
> 显得扁平。征求方向后走「保留单色、加立体感」（未引入每项一个颜色——`UI_COLOR.md` 是严格的
> 语义化单色令牌体系，全 app 没有这个先例）：背景改 `c.surfaceElevated`，补 `shadow(1.dp,
> RoundedCornerShape(12.dp))`（同 `CallHistorySegment` 选中态那颗立体药丸的手法）；图标
> 26→28dp、色调 `textSecondary`→`textPrimary` 补对比度（底变亮后次要色线条太淡）。iOS
> `IMChatViewController+Media.m` 同批改（`surfaceElevated` + 轻阴影）。`./scripts/test.sh`
> 986/986 绿。**真机验证 ✅**：截图确认方块与面板背景可辨、有明显阴影。

> **六条用户报告第 5 项：加号面板去掉音视频占位 ✅（2026-09-29，真机验证，OPPO PKD130）**：
> `AttachItems.Kind.AudioVideo` 一直是打不通的占位——点了只弹"音视频通话还没做"的吐司，而呼叫/
> 视频早已在聊天详情页（`ChatDetailHost`/`showsMessagePill` 那条链路）真正接通，面板这颗反而
> 误导用户以为是另一条独立的路。删掉整条：`AttachItems.kt` 的枚举项与 `ALL` 条目、
> `AttachPanel.kt` 的图标映射、`ChatHost.kt` 的 tap 分支、两份 `i18n_strings.xml` 的
> `chat_attach_av`/`chat_attach_audio_video_unimplemented`。面板从 6 项变 5 项（2×3 → 3+2），
> `AttachPanel.kt` 本就有"末行不足补空位"的逻辑（避免 `FillEqually` 把末行拉伸变形），不用改；
> iOS 同一处**原先没有**，已照 Android 这套思路给 `IMChatViewController+Media.m` 的
> `buildAttachPanel` 补上（末行不足 3 个时补透明 `UIView` 占位）。iOS `attachItems` 同步删掉
> "av" 条目，末尾兜底分支从 `im_showComingSoon` 改成 `NSAssert`（五个已知 id 全部真实接通，
> 走到兜底说明加了新项忘记接实现，不该再是"还没做"的话术）。**Web 本就没有这颗占位**
> （`useMediaSend.ts` 的 `attachItems` 只有"图片或视频"/"文件"两项），此项只涉及 iOS/Android。
> Android `./scripts/test.sh` 986/986 绿（`AttachItemsTest` 改断言，未加新用例）；iOS
> `./scripts/test.sh` 561/561 绿。**真机/模拟器验证 ✅**：Android 真机截图确认面板 5 项、
> 布局对齐（个人名片/文件仍卡在左侧两列，非拉伸铺满）；iOS 用一次性 XCUITest 截图核对同一件事，
> 通过后已删除脚本。

> **六条用户报告第 4 项：正在输入过期不清除 ✅（2026-09-29，真机验证，OPPO PKD130 user1001 ↔ Web user1002）**：
> 根因与会话列表绿点是同一类坑——`ChatPresence.kt` 的「正在输入」判定挂在 `rememberChatSubtitle` 里
> 那颗 30s 一次的粗粒度 `tick`（`Presence.TICK_MS`，给在线态心跳用的）上重算，而 typing TTL 只有 5s
> （`PresenceStore.TYPING_TTL_MS`）：一条 typing 帧到期后，最坏要等下一次 30s 心跳才会被发现"已过期"，
> 表现为"正在输入"赖着不消失、长达十几到三十秒（用户报）。**不能简单把 typing 也挂到同一颗 tick 上**
> （粒度对不上，治标不治本），照 iOS `IMChatViewController+Socket.m` 的
> `cancelPreviousPerformRequestsWithTarget` + `performSelector:afterDelay:3.0` 思路——每条 typing 帧
> 到时精确掐表、新帧一来自动顶掉旧表——补一颗独立的精确定时器：`typingNow` 状态 + `LaunchedEffect
> (typingExpiry)`（key 换成新到期时间即自动取消旧协程重新 `delay` 到点），到点才把 `typingNow` 更新为
> 真实当前时间，逼 `typingIn(convId, maxOf(tick, typingNow))` 精确到点重算并清除。原有 30s `tick` 仍留
> 给在线态用（粒度对在线态够）。`./scripts/test.sh` 986/986 绿（未加新单测：纯 Compose 定时器时序，
> 同 `ChatScroll.kt`/`ChatPresence.kt` 既有先例一样不易脱离 Compose 单测）。**真机验证 ✅**：Web 端
> user1002 在与 user1001 的 1v1 里敲字（不发送），Android 侧「正在输入」几秒内准时消失、不再滞留，
> 反复两轮均一致。

> **`/code-review` 抓出：会话列表绿点没有心跳重算 ✅（2026-09-29，紧接上一条一起改的，编译/单测已过，
> 未做多分钟真机蹲守验证）**：↓N 角标那次提交命中 SYMMETRY「页面结构对 iOS」，按约定跑了
> `/code-review`，抓到 `ChatsHost.kt` 的 `onlineOf` 只在**调用那一刻**算一次
> `Presence.display(..., System.currentTimeMillis())`——租约到期是纯粹的时间流逝、不触发任何回调，
> 不主动重算的话用户静止不动时绿点会**永远**停在「在线」（`Presence.kt` 文档注释原话："这是本模型
> 成立的前提，不是可选优化"，`rememberChatSubtitle` 早就照办了，这里当初漏了）。照 `ChatPresence.kt`
> 同一套心跳补上：`tick` 状态 + `LaunchedEffect(Unit)` 循环 `delay(Presence.TICK_MS)`；顺手把
> `onlineOf` 从裸 lambda 改成 `remember(presenceMap, tick)`，让它的引用只在这两个真正依赖变了时才变
> （之前每次 `ChatsHost` 因吐司/菜单锚点等无关状态重组，这个 lambda 都是新实例，白白让下游每一行
> 跟着判"变了"）。**未做的**：`presenceMap` 仍是整张表，任一好友上下线都会让整个会话列表重组一遍——
> 真要按 uid 拆分订阅是更大的改动（群成员表那种量级才值得），本仓会话列表体量目前不构成问题，
> 留作已知的可优化项，不在本轮做。`./scripts/test.sh` 986/986 绿。**验证边界**：装到真机确认列表
> 正常渲染、无回归（光辉岁月绿点仍在），但"某好友租约到期后绿点是否真的消失"是分钟级的时间验证，
> 本轮没蹲等，逻辑与已验证过的 `ChatPresence.kt` 心跳同构，视为同等可信。

> **六条用户报告第 3 项：↓N 跳转按钮补角标 ✅（2026-09-29，真机验证，OPPO PKD130，user1001↔user1002）**：
> 「消息到底收新消息即时显示/角标未读」拆开看，本端三块里两块**本就已经做对**：①已贴底时收到新消息
> 自动跟到底（`ChatEntry.shouldAutoScroll`，2026-09-28 已修）；②「回到最新」↓ 按钮本身早就有
> （`ChatWindows.showsJumpToLatest` + `ChatScreen.kt` 的悬浮圆钮）。**真正缺的只有一块**：按钮上
> 从来没有过角标数字（`CHAT_UX.md` §7 明确写了"计数 N"，对比 iOS `jumpBadge`/Web `unreadBelow.ts`
> 才发现本端这颗按钮是个哑按钮，之前误以为"↓N"整体没做，读代码后才定位到具体缺口只是角标）。
> - **口径**：新增 `ChatScreen.kt` 里的 `pendingReadSeq`（随"可见即读"上报实时推进，与画分割线用的
>   冻结 `readSeq` 是两个变量，同 iOS `pendingReadSeq` vs 入会话快照的区分）；角标数 = 当前**已加载窗口**
>   内、`convSeq > pendingReadSeq` 且非本人发的行数（`rows.count{ (it as? ChatRow.Confirmed)?.msg... }`）。
> - **已知简化**（刻意，非疏漏）：本端**未接入离线积压区间清单**（`OFFLINE_BACKLOG_DESIGN.md` C1~C6
>   均未做，见下方旧条目），没有 `head`/覆盖索引可查，角标只能数已加载窗口内的——历史很深、窗口没
>   覆盖到最新时会偏小。iOS/Web 在同样查不到 head 时也是退回"数本地"这一条兜底（`windowUnreadBelowCount`/
>   `loadedBelow`），口径与它们的兜底一致，不是本端独有的降级；C1 做了之后再补精确值。角标格式沿用
>   会话列表的 `99+` 封顶（未跟 iOS/Web 新近改的 `1.2K/1.2M` 精确格式，那份格式依赖同一套 head/区间清单）。
> - `./scripts/test.sh` **986/986 绿**（未加新单测：过滤逻辑直接挂在 Compose `rows`/`ChatRow` 上，
>   同文件里"可见即读"那处 maxSeq 计算也是同样内联写法，未抽纯函数，跟随既有先例）。
> - **真机验证 ✅**：user1002 用 `IMServer/scripts/send.sh` 连发消息，贴底时验证①自动贴底正确、
>   离底后验证②角标数字与实发条数逐次对上、③点击跳转贴底后角标正确消失、④退出会话后会话列表
>   预览文本/排序/未读角标均正确（已读位点被"可见即读"推进，列表侧无残留未读）。
> - **④⑤⑥ 已做**（正在输入过期清除、加号面板去掉音视频入口、消息列表滚动条，均见上方新条目）。
>   **六条用户报告至此全部完成。**

> **开始第 3 项前用户追加两条修复，均已真机验证（2026-09-29，OPPO PKD130，user1001）**：
> - **① 侧滑手势返回不回上一页**：根因是 4 个 Host 只把 `onBack` 接给顶部返回箭头，没有
>   `BackHandler(onBack = onBack)`——系统返回手势/按钮没有可拦截的回调，直接落到 Activity
>   默认行为（退出/回桌面），不是"回到上一页"。`CallHistoryHost.kt`（本轮新写的代码，同批自己
>   带出的缺口）+ `LanguageHost.kt`/`AddFriendHost.kt`/`CreateGroupHost.kt`（三个既有旧缺口，
>   同一个 grep 扫描 `ui/*Host.kt` 找 `onBack` 参数缺 `BackHandler` 一次性挖出）。全仓再无同类缺口。
> - **② 会话列表头像没有在线态绿点，iOS 有**：数据链路本就齐（`GET /conversations` 的
>   `peer_presence`/`peer_online_until`/`peer_last_seen` 早就解析进 `PresenceStore`，`presence`
>   帧也早在无条件更新它——`MessageService.kt`/`MessageRepository.kt` 都不用动），缺的只是
>   `ConversationListScreen.kt` 从没读过它。新增 `onlineOf: (String) -> Boolean` 参数（同
>   `localNameOf` 的传法，`ChatsHost.kt` 用 `client.presence.presence` + `Presence.display(...)`
>   算），单聊行头像右下角叠 12dp 绿点（`c.online`，2dp 边框挖空=行背景色，同 iOS `_onlineDot`
>   尺寸）。不额外发 `watch`——下线态本就靠下次刷新收敛，两端行为一致（iOS 同款注释）。
> `./scripts/test.sh` 986/986。

> **群聊六条用户报告，逐项排查中（2026-09-29，先做第 1/2 项，第 3-6 项未动）**：
> - **① @提及点击跳资料页**：主链路（`mention_spans`→Room→`chatBodyText`→
>   `LinkAnnotation.Clickable`→`onOpenUser`）本就接通，写了真机插桩测试
>   `androidTest/.../MentionTapConflictTest.kt` 排除了「气泡长按手势吞掉内层点击」这个假设
>   （**OPPO PKD130 真机跑绿**）。顺手挖到并修了一个真实缺口：`Mention.segmentByNames`
>   （无 `mention_spans` 的老路兜底）此前 uid 恒为空串（"老路本就点不动"），与 iOS
>   `IMBubbleCell` 的老路（现查当前群成员表、**可点**）不对齐——已按 iOS 口径把
>   `List<String>` 改成 `Map<String,String>`（显示名→uid）全链路穿透
>   （`ChatHost`→`ChatScreen`→`ChatRowStyle`→`Bubble`→`chatBodyText`）。
> - **② 群聊接收端头像点击跳资料页**：确认是真实缺口——`IMAvatar` 之前完全没挂点击手势，已补
>   `onAvatarTap` 接 `onOpenUser(uid)`（`Bubbles.kt`/`AlbumBubble.kt`/`ChatRowView.kt`）。
> - **①②追加·用户第二轮反馈「资料页看不到呼叫/视频按钮」——确认是真实的架构缺口，已修**：
>   `onOpenUser`（@提及/头像/系统消息里的名字共用同一入口）原先无论如何都开
>   `UserProfileHost`（简化版：仅「发消息/加好友/删除」，连好友也没有呼叫/视频/搜索/更多）。
>   而 `DetailActions.kt` 里其实早就设计好了 `showsMessagePill` 这个开关（文档原话："从外部
>   入口（**群成员行**/通讯录/找人）进来时为 true"），却从没在这条路上真正用过——
>   `ChatDetailHost.kt` 旧代码甚至写死注释"从通讯录/群成员进来的那条路走的是
>   UserProfileHost，不经这里"。已改线：群成员资料页现在开的是 `ChatDetailHost`
>   （与单聊自己的「聊天信息」同一个页面，呼叫/视频/搜索/更多齐全）+ `showsMessagePill=true`
>   （多一条「消息」pill），用 `client.conversationStubFor(uid,...)` 现造一个会话壳
>   （这个函数本就是为"没聊过也能直接进"设计的，`ContactsHost`/`QrRouteHost`/`GroupInfoHost`
>   等多处已在用）。链路：`ChatHost` 新增 `onOpenChat` 参数 → `MainScreen.kt`
>   `onOpenChat = { stub -> openConv = stub }`（点「消息」pill 换会话，同
>   `GroupInfoHost` 现成的那条）→ `ChatPickerLayers.kt` 把 `UserProfileHost` 换成
>   `ChatDetailHost`（用 `memberNames`/`memberAvatars` 兜底首帧头像/昵称，不必等联网）→
>   `ChatDetailHost.kt` 的 `showsMessagePill`/`onOpenChat` 从写死值改成参数，
>   `DetailAction.Message` 分支从 `Unit` 改成真正调 `onOpenChat(conv)`。
>   顺手改这条路时也**修掉一个隐藏的死按钮**：换之前 `onSendMessage = { onCloseUser() }`——
>   陌生 uid（非当前会话对方）点「发消息」只是关掉浮层，压根没打开任何聊天。
>   `ChatHost.kt` 因为加参数顶到体量红线（606>600），已把两处新增文档注释压缩，
>   回落 599 行（WARN，未 FAIL）。`./scripts/test.sh` **969/969 绿**（3 轮全绿，含体量门禁）。
>   **真机验证 ✅ 已通过（2026-09-29，OPPO PKD130，user1001）**：@提及跳转、头像点击跳转均正确
>   打开资料页且呼叫/视频/搜索/更多齐全；「消息」pill 正确换到与该用户的单聊（含群成员场景）。
> - **③④⑤⑥ 已做**（详见本节最上方条目）：↓N 角标已补，自动贴底与滚动条其实此前已完整；正在
>   输入过期清除的粗粒度心跳坑也已修；加号面板音视频占位已删（iOS 同步）；消息列表滚动条三端
>   收口（Android 已有、Web 新补、iOS 原生已够用）。**六条用户报告全部完成。**

> **im-rtc 换票：从调试密钥迁移到 IMServer 真实换票接口 ✅（2026-09-28，本端已完成——三端全部完成）**：
> 此前 `RtcCall.signToken`（同步、`IMDebugTokenGenerator` 本地签调试票）改成调 IMServer
> `POST /api/v1/rtc/token`：
> - `signToken` 改成 `private suspend fun`，调新增的 `sdk/api/RtcApi.kt#fetchToken`。`HttpClient`
>   默认 `authenticated=true`，会自动带上当前 IM 会话的 Bearer token——本端**不用**像 iOS/Web 那样
>   手动取/传 token 字符串，直接 `http.call("POST", "/api/v1/rtc/token", ...)` 就够了。
> - `start`/`onTokenWillExpire`（SDK 回调，非协程上下文）都经 `RtcCall` 自建的
>   `CoroutineScope(SupervisorJob() + Dispatchers.Main)` 发起换票；两处都加了 `generation` 判定
>   （换票是异步的，回来时可能已经 `stop` 过——切账号/登出，同已有的 `HostListener.stale` 同一思路）。
> - 换票结果 → token 的判定逻辑抽成包级函数 `rtcTokenFrom(result: Result<RtcTokenResult>)`
>   （**不放 `object RtcCall` 内**：`RtcCall` 的类初始化含 `Handler(Looper.getMainLooper())`，
>   纯 JVM 单测碰它任何成员都会抛异常——第一版把这个纯函数写进 object 内，`RtcCallTest` 4 个用例
>   全红，改成包级函数后才过；已把这条教训写进 `../IMServer/docs/SYMMETRY.md`）。
> - `RtcConfig` 精简为只剩 `wsUrl`（去掉 `appId`/`keyId`/`secret`）；`local.properties`/
>   `app/build.gradle.kts` 的 `RTC_APP_ID`/`RTC_KEY_ID`/`RTC_DEBUG_SECRET` 三个 `buildConfigField`
>   一并删除。
> - `IMClient` 新增 `val rtc = RtcApi(http)`；`AppRoot.kt` 的 `RtcCall.start(...)` 调用点传
>   `rtcApi = client.rtc`。
> - 新增测试 `RtcCallTest.kt`（4 例，覆盖成功/空 token/业务异常/传输异常四条路径，变异验红过）；
>   `RtcConfigTest.kt` 同步精简（字段从四项减到一项）。这个项目对网络层（`XxxApi` 类）本身没有
>   mock 基础设施覆盖的先例（`ProfileApi`/`AuthApi` 等既有类同样没测），未额外引入。
> - `./scripts/test.sh` 全量 **969/969 绿**（新增 8 例）。
> - **真机实测 ✅ 已通过**（用户确认）：`adb install` 装到已连接的真机（OnePlus PKD130）后起 App
>   无崩溃，通话流程验证通过——三端（Web/iOS/Android）换票迁移**全部完成且全部真机/浏览器联调过**。

> **转场没接的几处：`GroupInfoHost` 的 `Media` 分支已落地 ✅（2026-09-28，未做真机验证）**：
> `ChatDetailHost` 的 3 个分支（Detail/Profile/Media）已在上两轮全部接完。这一轮照抄同一套「open
> 标志/data 分离」模型，只动 `GroupInfoHost` 的 `Media`（`viewing: ConvMediaItem?`）这一支——它与
> `ChatDetailHost` 那支是同一个坑：拆成 `viewingOpen: Boolean` + `viewingData: ConvMediaItem?`，
> 关闭（`onClose`/`onLocateInChat`/`BackHandler` 的 Media 分支）只翻 `viewingOpen = false`，
> `viewingData` 留着不清（下次 `onOpenArchive` 才覆盖）。**与 `ChatDetailHost` 不同的一点**：
> `GroupInfoHost` 的 if/else-if 链有 9 个分支，不是 3 个，没有把全部 9 支都改成 `when` 吃
> `PushTransition` 冻结的 `state`（那是「下一步 2」第 4 步的活，范围大得多）——本轮只把
> `Media`/`Detail` 这一对相邻的 `else if`/`else` 换成内部套一层
> `PushTransition(targetState = innerPage, depthOf = {...})`（`innerPage` 只在 `Media`/`Detail`
> 两值间取值，没有改 `GroupInfoPage` 枚举本身，`depthOf` 就地写成 lambda，没有像 `ChatDetailPage`
> 那样加 `depth` 字段——9 个分支还没全接，加了也只对得上这一对）；其余 8 个分支（Pick/Bans/Admins/
> JoinRequests/MemberProfile/MemberSearch/Manage/Qr）原样是整页替换，完全没动。**副作用**：为了让
> `Media`/`Detail` 在 if-else 链里相邻好嵌套，把 `managing`/`qrCardAsLink` 两个分支的判断顺序挪到了
> `Media` 前面（原顺序 MemberSearch→Media→Manage→Qr→Detail，现在 MemberSearch→Manage→Qr→
> Media/Detail）——核对过这几个标志位在运行时互斥（`viewing` 只在 Detail 页的 `onOpenArchive` 里
> 置位，`managing`/`qrCardAsLink` 为真时不可能同时置位 `viewing`），改变判断顺序不影响实际行为。
> `GroupInfoHost.kt` 574→**587 行**（还有 13 行余量，比 `Media` 这轮之前更紧，下一块动它前先看着点）。
> 没加新单测——改动是渲染结构重排 + 状态拆分，不是新的纯函数逻辑（`GroupInfoNav`/`GroupInfoPage`
> 本身没变，既有 `GroupInfoNavTest` 原样覆盖）。`./scripts/test.sh` **965/965 绿**。**未做真机验证**：
> 查看器进出是否真的滑动、`viewingData` 保留旧值有没有意外副作用，以及 `managing`/`qrCardAsLink`
> 判断顺序调整后这两条路径本身（与本轮改动其实无关，但顺手挪了判断顺序，值得真机点一遍确认没引入
> 回归）。`GroupInfoHost` 剩下的 8 个分支（`else if` 链上真正复杂的部分）仍是拆分方案里最大的一块，
> 留给后续，一个分支一个分支来。根因诊断与三种导航形态的完整拆分方案见「下一步 2」。

> **离线积压 C2：sync 带 max_gap 闸门 ✅（2026-09-28，只做了 Android 审计建议的第一小块，未做真机验证）**：
> `../IMServer/docs/design/OFFLINE_BACKLOG_DESIGN.md` §4.11.1 审计发现 Android C1~C6 全未启动、
> 且明确"建议先做这一条"——服务端 `max_gap`/`too_long`/`head_conv_seq` 早已上线（`internal/protocol/
> envelope.go`），但 Android 的 `sync_req` 从不带 `max_gap`，等于对服务端说"不限深度"，超级群重连
> 也会把积压整段抄完（`IncomingRule.kt` 头注释记着 2026-09-09 在 11 万条大群真机撞见过）。本轮只做
> 这一条：`SyncCursorItem` 加 `maxGap`（可空 Long，对齐 Go `*int64` 指针语义）、`SyncConversation`
> 加 `tooLong`/`headConvSeq`；`requestSync` 每条游标恒发 `SyncDefaults.MAX_GAP=400`（含 `has_more`
> 续页，续页此前会漏发）；`applySync` 收到 `too_long` 时留痕一条日志（游标本就因为 `covered_conv_seq`
> 原样等于 `since` 而不会推进，`advanceCursor`/`SyncCursorRule` 早已保证这点，不用额外分支）。
> `EnvelopeTest.kt` 新增 `SyncGapTest` 4 例（编码省略/保留 `max_gap`、解码 `too_long`、老服务端无此
> 字段时退化默认值），先临时撤掉这两个字段确认真的编译失败过（`git stash` 验红）。`./scripts/test.sh`
> **964/964 绿**。**范围边界（刻意不做，留给后续）**：① 不区分超级群——本该给超级群发
> `max_gap=0`（永远不自动补），但那需要本地先知道"这个会话是不是超级群"，`ConversationEntity`
> 目前不落这一列，做了也只是一半（还得管迁移/回填），归进 C1 一起做；② 收到 `too_long`后**没有任何
> 后续动作**——本地没有区间清单（C1 未做），只是"不再无限追平"，不产生"按需开窗补"的下一步，缺口
> 目前对用户不可见（不影响正确性，只是体验上"这个会话消息好像还没到最新"要等下次能补齐的 sync 才
> 追上，或用户自己下滑触发本地已有的按需开窗）；③ C1（区间清单）/C3（进会话锚点开窗）/C4（↓/实时/
> conv_bump 处理）/C5（sync 路径回 delivered）/C6（八项分流除已接的搜索/查看器翻页外其余几项）**均未
> 动**，见「下一步 1」的拆分清单。**未做真机验证**：改动只影响重连时的 sync 请求形状，需要真机连接
> →断网/杀连接→重连，抓包或看日志确认 `max_gap` 真的发出去了、大群重连不再整段追平。

> **群资料页「成员」tab 补搜索入口 ✅（2026-09-28，大群专用，未做真机验证）**：`GroupApi.members()`
> 早已支持服务端 `?q=` 分页搜索，本轮接上。判据 `data/GroupMemberSearch.kt`（阈值/去抖/续拉/去重，
> 逐条对齐 iOS `IMGroupMemberSearchViewController` 顶部那组 C 函数，有单测）；新页面
> `ui/GroupMemberSearchHost.kt`（首页去抖走 `LaunchedEffect` 换 key 自动取消在途请求；续页请求跑在
> `rememberCoroutineScope()` 上不受 `query` 变化牵连，故仍需 iOS `_searchToken` 的等价物
> `searchGen` 代次令牌判过期——动手时一度以为能全靠 `LaunchedEffect` 躲开手记 token，
> `/code-review` 抓出续页这条路躲不开、旧词迟到的翻页响应会污染新词已显示的结果，已修 `12bf333`）
> + `ui/screens/GroupMemberSearchScreen.kt`（复用 `IMSearchField`/`MemberRow`）。`/code-review` 顺带
> 抓出第二处竞态并同批修：在途守卫原设在 `fetch()` 内部（协程真正跑起来才生效），大列表里同一帧
> 多行触发 `onLoadMore` 会一起穿过守卫、打出重复请求——改成 `requestLoadMore()` 里同步设置。
> 群总人数 > 50 时「成员」tab 顶部才出现入口行，恒走服务端过滤不做本地过滤（超级群本地只有已翻到
> 的那几页，本地过滤会悄悄搜错）。**顺手做的拆分**：`GroupInfoHost.kt` 已贴到 600 行硬闸，把
> 成员分页那簇状态（`members/cursor/hasMore/loading` + 四处调用点）抽成 `ui/GroupMembersState.kt`
> （CODING_STYLE §7①），才腾出线程加新页面，改完降到 574 行。新增 `GroupInfoPage.MemberSearch`
> 枚举页（返回键走既有"一处派发"机制，`GroupInfoNavTest` 补两条层级断言）。
> **已知简化**：① 未做搜索结果命中高亮（iOS 有，这里判定为纯装饰，跳过）；② 从搜索结果点开一个人的
> 资料页、又退回来时，搜索词与结果会清空（因为这条路复用 `GroupInfoHost` 顶层"整页替换"导航，
> 资料页与搜索页互斥、不共存于同一份组合状态里；iOS 用 push/pop 天然不丢，本端要接住得让搜索页
> 常驻组合树、资料页浮在它上面，超出本轮范围，留作后续小优化）。`./scripts/test.sh` **960/960 绿**
> （新增 `GroupMemberSearchTest` 7 例 + `GroupInfoNavTest` 补 1 例，均先见红过）。**未做真机验证**：
> 没有现成的 50+ 人测试群，也没有 Android 端的屏幕自动化工具可用，只验证了编译与 JVM 单测；
> 入口显隐、搜索/翻页/选人整条链路需要用户真机跑一遍。

> **设置 ▸ 最近通话页面 ✅（2026-09-29，`feature/call-history` 已合入 main，三端真机/模拟器/浏览器均验证过）**：
> `../IMServer/docs/design/CALL_HISTORY_DESIGN.md` 落地——「我」页早已有的「最近通话」占位行
> （`MeScreen.kt` 第一组，绿色电话图标）换成真正导航，调 im-rtc SDK 的 `IMCallEngine.fetchCallHistory`
> 拉自己的通话历史，纯客户端功能，不经过 IMServer。
> - **导航**：沿用本仓「无 NavHost，`MePage` 枚举 + `PushTransition`」的既有套路（同 `Favorites`）——
>   `data/PushNav.kt` 的 `MePage` 加 `CallHistory(1)`；`MeHost.kt` 的 `when(p)` 加一支接
>   `CallHistoryHost`；`MeScreen.kt` 加 `onOpenCallHistory: () -> Unit` 参数，「最近通话」行的
>   `onClick` 从 `{ onComingSoon(recentCalls) }` 换成 `onOpenCallHistory`，行本身（文案/图标/颜色/
>   位置）未动。
> - **SDK 接线**：`rtc/RtcCall.kt` 新增 `fetchCallHistory(limit, cursor, onResult)`（透传给
>   `engine.fetchCallHistory`，引擎未起时按既有 `unavailableReason()` 文案回退失败）与
>   `onCallEnded: (() -> Unit)?` 回调（`HostListener` 新增 `onCallEnd` override，双方都触发，
>   不分角色——区别于只对主叫触发的 `onCallSummary`/`onCallRecord`，用于「留在本页时通话结束要
>   重拉首页」，设计文档 §3）。
> - **纯逻辑** `data/CallHistory.kt`（新文件，有单测）：未接判定（我是被叫且 `durationSec==0`）、
>   1v1 对方 uid 与群通话人数（逐字对齐 im-rtc Demo `HistoryScreen.peerText` 的判据，不重新发明）、
>   翻页去重、按天分组（`labelOf` 注入避免 `data/` 反向依赖 `ui.components.TimeFormat`）、「未接」
>   tab 自动续页的判据（`shouldAutoContinue`）。**reason 文案复用 `CallRecord.render`**（聊天气泡
>   通话记录消息同一套判定），不新造一套；**红色与否不看它**——未接判定是本页自己的简单两字段规则
>   （设计文档 §0.7 把两件事分开管，行为上可能与 `CallRecord.Tone.Missed` 不完全重合，是设计使然）。
> - **接线层** `ui/CallHistoryHost.kt`（`CallHistoryList` 状态类，同 `FavoritesHost.FavoriteList`
>   同一形状：`generation` 代次令牌作废在途旧请求，`callEnded` 回调重拉首页也走同一份 `reload`）：
>   身份解析走会话表优先（1v1 备注>标题，群用群会话标题），查不到的 1v1 再补拉一次名片（同
>   `FavoritesHost.sourceOf` 的两段式）；**未复用 `RtcProfileResolver`**——那是喂给通话中界面
>   （Kit）的解析器，生命周期绑一通电话，这里要「历史列表批量查名」，直接读会话表更直接。
>   点单聊行 = 按 `mediaType` 直接调 `RtcCall.placeSingle`，不弹确认；点群聊行 = 跳转
>   `chatGroupId` 对应群会话（本地没有就用 `client.groupConversationStubFor` 造一个壳）。
> - **UI** `ui/screens/CallHistoryScreen.kt`：`SegTabBar`/`Hint`/`LoadMore`（复用 `DetailArchive.kt`
>   既有组件，同 `FavoritesScreen` 套路）；行用 `Lucide.ArrowUpRight`/`ArrowDownLeft` 方向箭头 +
>   `Lucide.Phone`/`Video` 类型图标 + `IMAvatar`（1v1）/自绘 `Lucide.Users` 渐变底（群通话，
>   通话参与者 ≠ 群成员，不用群头像）；未接来电整行 `IMTheme.colors.danger`。
> - **string 新增**（`values`/`values-en` 两份 `i18n_strings.xml`，均已加）：`call_history_tab_all`/
>   `_tab_missed`/`_empty`/`_group_voice`/`_group_video`/`_group_summary`；页面标题/空态图标/
>   401 文案复用既有 key（`ios_settings_row_recent_calls`/`common_load_failed`/
>   `common_tap_to_retry`/`rtc_error_not_started`），未新增重复 key。
> - 新增 `CallHistoryTest.kt`（17 例：未接判定、1v1 对方 uid、群人数、翻页去重、未接自动续页判据、
>   按天分组），均覆盖设计文档 §6 测试点里能脱离 Compose 布局单测的部分。`./scripts/test.sh`
>   全量 **986/986 绿**（体量门禁/日志红线/编译/单测四步全过，新文件均远低于 600 行硬闸）。
> - **没做 / 已知限制**：删除、长按菜单、未接数量角标——按设计文档 v1 范围明确不做，也没有为它们
>   预留接口；「全部/未接」的自动续页与端上过滤是设计文档 §3.5 明说的一期凑合方案，服务端过滤参数
>   二期才加。
> - **真机验证 ✅ 已通过（2026-09-29，OPPO PKD130，user1001；同批 iOS 模拟器/libeyond、Web 浏览器/
>   user1001 也过）**：通话记录列表按天分组、来去电箭头、未接标红、「全部/未接」筛选、点击行发起
>   回拨均正常；未覆盖到滚动分页时序、`callEnded` 重拉首页画面表现、群通话行跳转会话——这几条留给
>   后续有多测试账号互相拨打攒出真实分页数据后再核对。
> - **验收修复（2026-09-29，真机 user1001 已验，未提交）**：「全部/未接」改成等宽铺满的
>   `CallHistorySegment`（原复用 `SegTabBar` 按内容宽排，两段时右侧空一大块）；群行改用群会话真实头像
>   （`avatarOf` 取 `groupConvById[chatGroupId].avatarUrl`，`IMAvatar` seed 用群会话 id，同会话列表），
>   删 `GroupCallAvatar`；行内时间改 `TimeFormat.bubbleTime`（`HH:mm`，日期交给分组头）；箭头次要色、
>   名字 16 SemiBold、分组头 Bold，按 UX 稿对齐。`./scripts/test.sh` 986/986。
