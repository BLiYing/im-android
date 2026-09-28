# Current Task — im-android（Kotlin + Compose 客户端）

> **活快照**：只记当前状态，**就地覆盖、不追加**。逐功能×端状态以
> `../IMServer/docs/CLIENT_PARITY.md` 的 Android 列为唯一来源；历史流水见 `git log`
> 与 `current_task.archive.md`（只读归档）。
> 工程规范见 `CLAUDE.md` 与 `CODING_STYLE.md`。

## 当前焦点

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

## 下一步

0d. **群资料页「成员」tab 搜索入口的真机验证**（2026-09-28 已实现，见「当前焦点」，未测）：入口显隐
   门槛（>50 人）、搜索/去抖/翻页、选中后跳资料页整条链路，需要一个 50+ 人的测试群。
0b. **上几批仍欠的真机回归**（这两轮没动到、也没回归）：归档查看器「更多」五项、合并转发记录页内翻页、
   长按预览里点图/点链接只关菜单、以及 2026-09-15/16 那两批的清单（见 `current_task.archive.md` 顶部）。
0c. **本批（2026-09-23）真机回归**——📅/👤/撤回实时刷新/非 UTC+8 时区换算均已测过（见 archive），
   仍欠：**转发带 @ 图片到群里，"别人视角"点开被 @ 的名字、有没有收到强提醒**——真机 UI 这条链路没跑通
   （系统图片选择器多级页面盲点坐标屡次踩偏），也没有第二台设备/账号可以扮演"收端"；只验证到
   "提及渲染可点 + 点了跳资料页"这条基础设施是通的，`mentions` 随转发存活只有单测覆盖。
   另外**"对方撤回"的两种文案**（"XX/对方撤回了一条消息"）同样因为单设备单账号测不出来，只测了"自己撤回"。
1. **Android 离线积压（`../IMServer/docs/design/OFFLINE_BACKLOG_DESIGN.md` §4.11.1 / §5 B3a）**：
   C2（sync 带 `max_gap`）最小切片已做，见「当前焦点」；范围明显比单条任务大，剩下按设计文档原有
   C1/C3/C4/C5/C6 分期，一次做一块：
   - **C2 收尾**（本轮的直接延伸，比新开一个 C 项小）：`too_long` 时的真机验证（见「当前焦点」）；
     超级群 `max_gap=0` 需要先给 `ConversationEntity` 加 `isSuper` 列（迁移）并在拉群资料时回填。
   - **C1 区间清单**：新 Room 表 `conv_range_local(owner_uid, conv_id, lo, hi)` + 纯函数（合并相邻区间、
     查询"某段是否齐全"，参照 Web `ranges.ts` 的判据，有单测）；写消息与扩区间同一事务（I1 不变量）。
     C1 是后面几项的地基，建议下一块就做它。
   - **C3 进会话 / 滚动**：`ui/ChatHost.kt` 目前固定本地 `ChatWindow.Tail`，`window_req` 只用于引用跳转/
     搜索命中——要接锚点开窗（未读首条不在本地尾部时定位不到）与上滚时查区间清单再决定问本地还是服务端。
   - **C4 ↓ / 跳号 / `conv_bump`**：`conv_bump` 目前在 `MessageService.kt` 落进忽略分支，超级群会话行
     收不到刷新；↓ 只换本地 Tail，没有"最新一页齐不齐"的判据。
   - **C5 `delivered` 回执**：`MessageService.kt` 只在 `NEW_MSG` 分支回，sync/window 路径一条都不回——
     离线补拉回来的消息，对端看不到「已送达」直到被已读覆盖。
   - **C6 八项分流剩余**：查看器翻页（已接）缺"离线降级提示"；日历/置顶判定未接；其余几项有缺口时
     静默给本地残缺答案。
   **别照抄 Web/iOS 的现成补丁**——设计文档 §4.11.1 原话："Android 进会话根本不走锚点开窗，那条调用点
   不存在"，得从头按 §4 的设计走，不是抄一个 diff。
2. **转场没接的几处**（`docs/UI_PARITY_IOS.md` §4 第 316 行；2026-09-28 勘察 + 拆分，范围超出单条
   任务，按下面顺序分块推进——第 1 块已落地，见「当前焦点」）：
   - **根因**：`ui/components/PushTransition.kt` 要求退场页在 ~300ms 滑出动画期间，内容仍按
     `AnimatedContent` **冻结住的 `state` 参数**渲染；但「没接」的几处目前是"关闭即把数据变量置空"
     （如 `ChatDetailHost` 的 `onClose = { viewing = null }`），退场那一刻数据已经没了，分支渲染出
     空白——套上去就是文档说的"半路变白"，不是简单包一层就行。
   - **实际是三种不同形态，不是一次改法通吃**：
     ① **互斥枚举页 + 关闭置空数据**的 host（`GroupInfoHost` 9 个分支；`ChatDetailHost` 的 `Media`/
     `viewing` 那支）：改法 = 把"是否打开"和"显示什么数据"拆成两个变量，关闭只翻布尔、数据留着
     （下次打开才覆盖），`when` 改成吃 `PushTransition` 传入的冻结 `state`，不直接读外部活变量。
     ② `ChatHost` 的 `Set<Layer>` 覆盖层栈（`ChatOverlays.kt`：Viewer/UserProfile/**ChatRecord
     可嵌套压栈**/FriendPicker/MediaPicker/FavoritePicker/Forward/ContextMenu）：连接口都对不上——
     `PushTransition` 吃单一 `S` + `depthOf`，`ChatRecord` 是任意深度的栈，要么扩展 `PushTransition`
     支持栈式深度、要么给它单独一套转场，工作量明显更大，且"要不要真做 iOS 式滑动"更像产品判断，
     不是纯技术判断——动手前应该先问用户，不能假定文档写着就该做。
     ③ **"用户资料内部"不是独立第三类**：核实过 `UserProfileHost`/`UserProfileScreen` 自己没有内部
     子页导航（`RemarkEditDialog` 是弹窗，不需要滑动转场），这一条实际指的是 `ContactsHost`/
     `ChatDetailHost`/`GroupInfoHost` 各自"进用户资料页"那条边，并入①。
   - **建议顺序（从最小最安全开始）**：
     1. ✅ **已做（2026-09-28）**：`ChatDetailHost` 的 `Detail↔Profile`——没有数据置空问题，不改
        数据模型，只重排渲染结构。
     2. ✅ **已做（2026-09-28，见「当前焦点」）**：`ChatDetailHost` 的 `Media`——按①的"open 标志/
        data 分离、关闭不清 data"模型改造（`viewingOpen`/`viewingData`），三分支合并进同一个
        `PushTransition`。`ChatDetailHost` 这个 host 的转场至此**全部接完**。
     3. `GroupInfoHost` 的 9 个分支（Pick/Bans/Admins/JoinRequests/MemberProfile/MemberSearch/
        Media/Manage/Qr，注：`GroupInfoPage` 目前 9 个值，`Detail` 不算转场目标）：同样是①的模型
        改造，但分支数是 `ChatDetailHost` 的 3 倍——一个分支一个分支来，别一次性全改。
        - ✅ **已做（2026-09-28，见「当前焦点」）**：`Media`——照抄 `ChatDetailHost` 的
          `viewingOpen`/`viewingData` 写法，只把这一支单独套了一层 `PushTransition`（没有把 9 个
          分支统一进一个大 `when`，那是下面第 4 步的活）。`GroupInfoHost.kt` 574→**587 行**，
          离 600 硬闸只剩 13 行——下一个分支动手前先看这个数字，必要时先拆文件再接。
        - 剩下 8 个（Pick/Bans/Admins/JoinRequests/MemberProfile/MemberSearch/Manage/Qr）未动，
          都是「关闭即整页替换 + 置空数据」的同一种坑，逐支来；哪支先做没有强制顺序，挑数据置空
          最明显、改动最小的那支（参照这轮 `Media` 与上两轮 `ChatDetailHost` 的判据）。
     4. 把 `GroupInfoHost` 全部 9 个分支真正统一进**一个** `PushTransition(targetState = page,
        depthOf = { it.depth })`（`page: GroupInfoPage` 走完整枚举、加 `depth` 字段，同
        `ChatDetailPage` 的写法）——**在第 3 步的 9 个分支逐个改完「开/关不清数据」之前，不要
        提前做这步**，否则等于把还没改好的分支也塞进转场，退场画面照样半路变白。
     5. `ChatHost` 覆盖层栈（②）：动手前先跟用户确认要不要做，范围最大且是产品判断。
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
