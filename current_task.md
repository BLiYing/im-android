# Current Task — im-android（Kotlin + Compose 客户端）

> **活快照**：只记当前状态，**就地覆盖、不追加**。逐功能×端状态以
> `../IMServer/docs/CLIENT_PARITY.md` 的 Android 列为唯一来源；历史流水见 `git log`
> 与 `current_task.archive.md`（只读归档）。
> 工程规范见 `CLAUDE.md` 与 `CODING_STYLE.md`。

## 当前焦点

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

## 下一步

**0. @提及这一轮的两件收尾（先做完再开新的）**：
   - `../IMServer/docs/CLIENT_PARITY.md` 的 **M4-8 两行**（气泡内 @高亮可点 / 群 @提及面板）
     Android 列还是 ⬜，要改成 ✅ 并写上落地日期与欠账。
     **那个仓在 worktree 之外**，且并行会话多——改前先 pull --rebase，改完立刻提交，
     别留着未提交的改动（上一轮 CLIENT_PARITY 就是这么被别的会话 add -A 裹走的）。
   - **跑 `/code-review`**：本轮命中 SYMMETRY 的 `*mention*` 与 `ui/screens/*Screen.kt` 两条，
     改动横跨协议 / DB 迁移 / UI 三层。上一轮它打回 8 条，这轮改动更大。
   - 当前在 worktree `.claude/worktrees/android-next`（分支 `worktree-android-next`），
     `56a1709` / `1f4a32f` 两笔**还没合回 main**。合回时会一并带上
     `.gitignore` 里新加的 `.claude/worktrees/`（在那之前，主 checkout 里的 worktree 目录
     仍是未跟踪文件，可能被别的会话 add -A 扫进去）。

**0'. 按 [docs/UI_PARITY_IOS.md](docs/UI_PARITY_IOS.md) 里剩下的 🔴 排**：
   ① ~~会话内搜索~~ ✅ 2026-09-09（见「当前焦点」）。**剩下两块**：📅 日历按日期跳转、
      👤「来自某人」发件人过滤（SEARCH_DESIGN 定的 P1）；
   ② ~~群聊详情也改成内联页签~~ ✅ 2026-09-09；
   ③ ~~通讯录好友拼音 A–Z 分组 + 右侧索引尺 + 好友行左滑~~ ✅ 2026-09-09（详见 archive）；
   ④ 水滴头部的**形变那半**（缩放 / 名字迁进标题栏 / 松手吸附，遮罩不做）；
   ③′ ~~按锚点开窗 window_req~~ ✅ 2026-09-09（`MESSAGE_WINDOW_DESIGN` 的 Android 那一期）；
   ⑤ ~~归档内长按菜单 + 定位到聊天~~ ✅ 2026-09-09（见 `docs/UI_PARITY_IOS.md` §2.2）；
      ⑥ ~~引用块内真缩略图~~ ✅；
   ⑦ 语音页签内播放（要接聊天页那套单例播放器）；⑧ 「名片」页签；
   ⑨ 复制的适用范围（iOS 还能复制图片字节与图说，本端只有文本）；
   ⑩ 长按菜单里 iOS 有而本端没有的项（转文字/收藏/置顶/编辑/多选/翻译/举报）。

**0. 群成员头像图 URL 缺失**：`showsSenderAvatar` 挂的是首字母色块（取色三端同源，颜色对），
   但没有头像图——群成员头像无本地缓存。要接得先做 `POST /users/batch` 解析器（CLIENT_PARITY 有这行）。
**0-. 多选（M4-3 的另一半）**：长按菜单的「多选」本轮**刻意没加**——不带着只会弹
   「还没做」的死菜单项交付。`Forward.toggleCapped` 与 100 条上限已就位并测过，
   缺的是多选态 UI + 底部批量栏。
**0a. 合并转发（chat_record 一张卡片）本端没做**：现在只有逐条转发。
   合并转发要一整套卡片渲染 + 匿名 sender key（im-web `useForward.ts` 那套），是独立一块。

按 `../IMServer/docs/CLIENT_PARITY.md` 追 iOS，优先级从高到低：

1. **转发 / 多选 / 收藏**（M4-3、M4-4）：长按菜单现在缺这三项，是 iOS 用得最多的一批。
2. **消息编辑**（M4-5）：`msg_op op=edit` 协议侧已接，缺 UI。
3. **会话详情页**（媒体/文件/链接归档，M4.5-3）。
4. **设置页逐项**（外观/字号/主题偏好等 9 项）：「我」页入口列表已经把位置占好了，
   点进去是「还没做」；`IMAppearance` 令牌层已就位，缺持久化与界面。
   接外观时一并解掉 `values-night` 只跟系统深色导致的启动闪烁（见已知坑）。
6. **@提及**（`mention_spans`，M4-8）：协议字段已在 DTO 里，缺高亮与 @面板。
7. **扫一扫 / 群码 / 扫码登录**（QR P0 的其余部分 + P1）：出码那半边已经做了，
   **读码**这半边需要相机权限与 ML Kit/CameraX，工作量单独一档。
   另欠**头像裁切页**——现在是自动居中方裁，竖幅全身照会裁掉头（iOS 有 `IMAvatarCropViewController`）。
8. **推送**（M5）：依赖 FCM，iOS 侧也还没做。

## 已知坑 / 限制

- **视频不转码**：iOS 会转 720p H.264 再发（`IMMediaPicker`），本端原样直传。
  好处是快且无损，坏处是 iPhone 拍的 HEVC 在部分收端只能看封面点不开——封面有了，播放没解决。
  真要转码得上 MediaCodec，是独立一块。
- **分片上传不跨进程续传**：`upload_id` 只活在这次调用里，杀进程要重传。
  服务端 `/upload/{id}/status` 是支持续传的，缺的是把 `upload_id` 落库。
- **上传进度只到「整条」粒度**：相册宫格里**每格**的环形进度（iOS 有）没做；
  图片走整包上传，没有进度也不打算加（压缩后几百 KB，接了只会闪）。
- **视频没有本地缓存**：每次打开查看器都重新下载整段（本端未配 `SimpleCache`）。
  iOS 有 `IMOriginalVideoCache`。**刻意往后放**：收益只是重复打开同一段省流量，
  而代价是 `SimpleCache` 同一目录只能被一个实例持有（多进程直接抛）+ 要自己定配额与清理策略——
  四条媒体欠账里投入产出比最差的一条。
- **查看器只能看单条**：iOS/Web 还能在会话媒体时间线上左右翻页（CLIENT_PARITY 任务3），
  那要先有「会话媒体列表」这套数据，是独立一块。保存/转发已接，**删除**入口仍没有。
- **存相册的 Q 以前分支没实测**：本机是 Android 15，走的是免权限的分区存储那条；
  `WRITE_EXTERNAL_STORAGE` 那条（Android 9 及更早）只有代码没跑过。
- **媒体失效没有本地登记**：iOS 有 `IMMediaExpiryRegistry`，存之前就能拦下「已被服务端清理」的
  地址并说「该文件已失效」。本端没有，只会走到 HTTP 失败、报一句「保存失败」。
- **选图器的预览页仍不播视频**：只显首帧 + 时长 + 「预览不播放」。
  查看器已经能播了，这里要不要接同一个播放器是个产品选择（微信的选图预览也不播）。
- **相册分页只在页大小调成 4 时验过**：真实 PAGE=300、本机只有 12 张素材，
  日常走不到续页路径。逻辑有 `PageMergeTest` 覆盖，查询层（Bundle 参数 vs 老写法）只验了 Android 11。

- **`CLIENT_PARITY.md` 的 Android 列已补齐**（2026-09-07），「我」页相关四行同日更新。
  填法保守——不确定的一律往低了填，所以可能低估、不会高估。
- **布局验证靠像素测量，不是靠肉眼**：`adb exec-out screencap` 出图后用 PIL 扫，
  比对 `UI_SPEC.md` 的规格值。本轮就靠它纠正了一次目测误判。**但这是手工的**，
  没有自动化——真正的回归还得靠 Robolectric/Compose 或截图基线，两者都还没做。
- **没有 instrumented 测试**：`androidTest` 依赖配好了但一个用例没写。
  Compose 的布局/滚动/输入法这类只能靠模拟器手测，目前全靠人肉截图核对。
- **媒体只做了图片**：视频/语音/文件只能**看**不能**发**，也不能播放/下载。
  语音气泡的波形是等高条纹占位（`waveform` 字段还没解析）。
- **图片不压缩**：只在端上挡 20MB，超了直接不发。iOS/Web 都会先压。
- **消息编辑、多选、收藏、@提及、扫码未做**（转发已做，合并转发未做）。
- **归档里语音仍不能播**：要接聊天页那套单例播放器（iOS 是复用 `IMVoicePlayer sharedPlayer`），
  否则会同时响两处。长按已经可以定位/转发/删除，脚注也如实写明了。
- **会话内搜索只到 P0**：词命中导航有了，📅 日历与 👤「来自某人」没有；命中只取一页
  （本地 500 / 服务端 50），更多时靠计数补 `+` 如实告知，**翻更多页三端都没做**。
- **`window_req` 那条分支没实测**：本地库齐全时走不到它（`windowAround` 恒有结果）。
  要验得先造一个"本地有缺口"的会话（清库后只同步一半）。
- **一次性数据订正靠 SharedPreferences 标记**（`SessionStore.msgOpConverged`）：
  历史遗留 `msg_op` 行的收敛要按 `contentType` 扫全表，而 `message` 表上没有这一列的索引。
  20 多万行的库上每次启动扫一遍会让首屏卡住（实测约 90 秒，两次 `Long db operation`）。
  **以后再加这种一次性订正，一律配一次性标记，别为它加只用一次的索引。**
- **`GroupInfoHost.kt` 已 599 行**（上限 600）：下一个动它的人先拆再加。
- **「我」页 11 个入口里只接通了 3 个**（设备 / 资料 / 二维码），其余 8 个点了是「还没做」提示。
  主题/字号/壁纸等外观偏好的令牌层在（`IMAppearance`），但没有持久化也没有界面。
- **头像没有裁切页**：选图后自动居中方裁 512×512 再传，竖幅全身照可能裁掉头。
- **`values-night`** 见下条。
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
