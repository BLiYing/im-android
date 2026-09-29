# Current Task — im-android（Kotlin + Compose 客户端）

> **活快照**：只记当前状态，**就地覆盖、不追加**。逐功能×端状态以
> `../IMServer/docs/CLIENT_PARITY.md` 的 Android 列为唯一来源；历史流水见 `git log`
> 与 `current_task.archive.md`（只读归档）。
> 工程规范见 `CLAUDE.md` 与 `CODING_STYLE.md`。

## 当前焦点

> **设置 ▸ 通知与提示音 P0 ✅（2026-09-29，`feature/notifications` 分支，未提交，未真机验证）**：
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

## 下一步

0e. **设置 ▸ 最近通话页面的剩余验证**（基础展示/筛选/回拨已于 2026-09-29 真机验证过，见「当前焦点」）：
   滚动分页时序、「未接」tab 过滤后自动连续翻页的实际观感、`callEnded` 到达时重拉首页的画面表现、
   群聊行点击跳转会话是否正确、空状态/401/网络错误三态的文案与重试——需要至少两个测试账号互相拨打
   几通电话（含群通话）攒出足够多真实通话记录再验。
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
