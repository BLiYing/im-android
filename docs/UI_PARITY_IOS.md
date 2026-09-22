# Android ↔ iOS 界面差异登记

> **这份表只记「本端与 iOS 长得不一样的地方」**，且每一条都要写清楚**为什么**：
> 是欠账（该补）、是平台限制（补不了）、还是刻意差异（不要来回改）。
>
> 尺寸/字号/时间格式这类**三端共用的数值基准**不在这里，在
> [`../IMServer/docs/UI_SPEC.md`](../../IMServer/docs/UI_SPEC.md)；逐功能×端的完成度在
> [`../IMServer/docs/CLIENT_PARITY.md`](../../IMServer/docs/CLIENT_PARITY.md)。
> **这份表只管"观感与结构"**。
>
> **新做/大改一页之前先读 §5**（复盘：为什么会漂）——那一节讲的是做法，不是这几页。
>
> 建表原因（2026-09-08）：用户指出「聊天信息页和群管理页 UI 和 iOS 完全不一样」。
> 复查属实——不是细节偏差，是**页面结构不同**。修的过程中发现"哪些能对齐、哪些对不齐"
> 需要一个固定的地方记，否则下次又要把 iOS 源码重读一遍。

## 0. 判定标准

| 标记 | 含义 | 该怎么办 |
|---|---|---|
| 🔴 欠账 | 能对齐但还没做 | 排期补 |
| 🟡 平台限制 | Android 上做不了 / 做了也不对 | **不要试图硬做**，理由写在下面 |
| 🟢 刻意差异 | 平台惯例不同，对齐反而更差 | **不要来回改** |

---

## 1. 「水滴」头部形变（Telegram PeerInfoHeaderNode）

iOS 实现：`Common/IMDropletHeaderMorph.m` + `IMTelegramAvatarMaskView.swift`。
滚动时大圆头像缩小上移，同时一层 **171pt 的 Lottie 遮罩**（Telegram 原始素材
`UserAvatarMask.tgs`）把头像"吸进灵动岛"，配合顶部 blur+gradient 覆盖层。

| 组成部分 | Android | 判定 |
|---|---|---|
| 头像随滚动缩小/上移（`avatarScale` 1→0.55、`avatarOffset` 17·tcf） | 能做 | 🔴 欠账 |
| 名字/副标题迁移进标题栏当 title/subtitle | 能做 | 🔴 欠账 |
| 松手临界吸附（`snapTargetForOffset`，纯函数） | 能做 | 🔴 欠账 |
| **171pt Lottie 遮罩「吸进灵动岛」** | **不做** | 🟡 平台限制 |

**为什么遮罩那一层不做**：这个效果的意义在于**把头像吸进灵动岛**——它假设屏幕顶部中央
有一块固定尺寸、固定位置的黑色药丸。Android 没有灵动岛：挖孔的位置（居中/左上/右上）、
形状（圆/胶囊）、尺寸都随机型变，`Pixel 2 XL` 干脆没有挖孔。照搬的结果是屏幕顶部凭空多出
一团与任何硬件都对不上的黑色，不是"没那么好看"，是**错的**。

技术上并非不可行（`lottie-compose` 能渲染同一份 `.tgs`，Compose 用
`graphicsLayer(compositingStrategy = Offscreen)` + `BlendMode.DstIn` 可以拿它当遮罩），
所以这条是**产品判断不是技术限制**——写在这里免得下次又有人问"能不能做"。

**结论**：形变（缩放/迁移/吸附）该补，遮罩不补。

---

## 2. 会话详情页（聊天信息）

iOS：`Modules/Detail/IMChatDetailViewController.m`（+`Header`/`Actions`/`Peer`/`About` 四个分类）。
**单聊与群聊共用同一个 VC**，`UITableViewStyleInsetGrouped`，分区布局见 `sectionLayout`：

```
Header（300pt 大头部：水滴头像 + 名字 + 副标题 + pills 页签）
Info      —— 仅单聊：备注名 / 用户名
About     —— 仅群聊：群简介 / 群公告 等
Settings  —— 置顶聊天 / 消息免打扰 / 我在本群的昵称 / 群备注 / 群二维码 / 群邀请链接 / 群管理
Tabs      —— 内联页签：成员 / 媒体 / 文件 / 语音 / 链接 / 名片
```

| 项 | iOS | Android | 判定 |
|---|---|---|---|
| 单聊 / 群聊是否同一页 | 同一个 VC，按 `isGroup` 切分区 | **两个 Screen**（`ChatDetailScreen` / `GroupInfoScreen`），但**页签那一大套已共用**（`DetailArchive.archiveTab` + `rememberConvArchive`） | 🟢 刻意差异 |
| 归档的位置 | **内联页签**（详情页内切 tab） | 已改为内联页签（单聊）| ✅ 已对齐 |
| 大头像头部（100pt 居中 + 名字 + 副标题） | 有 | 有（无形变，见 §1） | ✅ 结构对齐 |
| 单聊 Info 区（备注名 / 用户名） | 有 | 有 | ✅ |
| 「备注名」行点开的编辑交互 | 页内 `UIAlertController` 弹窗，不跳页（`editRemark`） | 已改（2026-09-22）：`ChatDetailScreen` 的「备注名」行以前先跳整页 `UserProfileHost`、要在那页里再点一次才弹出编辑框，现改为详情页内直接弹 `RemarkEditDialog`（与用户资料页共用同一个弹窗组件，两处各自持有状态） | ✅ |
| **群资料 Settings 区**（置顶聊天 / 消息免打扰 / 我在本群的昵称 / 群备注 / 群二维码 / 群邀请链接） | 六行都在（部分是单聊详情页的对应行） | **整段缺失**：`GroupInfoScreen`/`GroupInfoHost` 没有这一区；置顶/免打扰只能从会话列表长按菜单操作，群资料页内没有入口；「我在本群的昵称」「群备注」两行完全没做（`GroupApi.setMyNickname` 是孤儿 API，无任何 UI 调用点；`ConversationsApi` 只读群备注、没有 setter）；群二维码/邀请链接入口也没有（`QrCardScreen` 只挂在「我的」页签，没有群卡片模式） | 🔴 欠账 |
| 群聊 About 区（群简介 / 群公告） | 两个独立行，各自 tap 展开只读全屏页（`IMGroupTextViewController`） | `GroupInfoScreen` 合并成一张卡片纯文本展示，不能展开看全文（公告最长 500 字，长公告会被卡片挤住） | 🔴 欠账 |
| 群成员长按（禁言/解除禁言 · 设/撤管理员 · 转让群主 · 移出群聊）执行后列表即时刷新 | 有（每个动作后都调 `loadGroupInfo`，含成员分页重置） | 已改（2026-09-22）：此前 `GroupInfoHost.runManage` 只刷 `info`、不刷 `members`——四项动作后角色徽标/🔇不更新、被移出的成员还留在列表里，都要退出重进才看得到新状态。现 `runManage` 成功/失败后统一重拉成员首页，对齐 iOS 语义。顺带修了 `MemberRow` 的禁言徽标判据（`m.muteUntil != 0L` → `GroupPermissions.isMuteActive(m.muteUntil)`：过期的历史禁言时间戳此前会被误显示成「仍在禁言」） | ✅ |
| **群聊详情**也用内联页签 | 有（成员也是一个 tab） | 已改（2026-09-09）：成员 / 媒体 / 文件 / 语音 / 链接五格，**页签内容与单聊那侧是同一段渲染**（`DetailArchive.archiveTab`）。「聊天媒体」入口行与整页 `ConvMediaScreen` 一并删掉 | ✅ |
| 页签选中态 | 底轨 + 药丸；**选中/未选中同为主文字色，只差字重**（`IMLiquidSegmentedControl`） | 同（2026-09-08 前是「12% 主色底 + 主色字」，深色下几乎看不出选中） | ✅ |
| 归档内长按菜单 / 定位到聊天 | 有 | 已补（2026-09-09，见 §2.2） | ✅ |
| 语音页签内播放 | 有 | **不可点**（播放要接聊天页那套单例播放器，否则会同时响两处） | 🔴 欠账 |
| 归档的数据来源 | **本地已加载的消息**（`IMChatDetailTabs message:matchesKind:`） | **服务端接口** `GET /conversations/{id}/media` | 🟢 刻意差异 |
| 「链接」页签 | 有（本地扫文本，`IMFirstURLInText`） | 有（同样本地扫文本——服务端不覆盖这一格） | ✅ 已对齐 |
| 「名片」页签 | 有 | 没有 | 🔴 欠账 |
| **头部操作排**（大头像下一排 pills） | 有（`actionPillSpecs`：加好友 / 消息 / 呼叫 / 视频 / 搜索 / 更多） | 已补（`DetailActionBar` + `DetailActions`，2026-09-08） | ✅ |
| 「更多」菜单 | Telegram 式锚点 popover（`IMPopoverCard`） | Material `DropdownMenu`（锚在「更多」上） | 🟢 刻意差异 |
| 「清空聊天记录」 | 有（**只清本机**） | 已补（`MessageRepository.clearConversation`，同口径） | ✅ |
| 「查找聊天记录」 | 有（pill「搜索」进聊天页搜索态） | 已补（2026-09-09）：pill → 回聊天页进搜索态，顶栏换搜索框、底栏换命中导航条（计数 + ▲▼）、命中词高亮、跳转跨窗。**缺 📅 日历与 👤「来自某人」**（见 §2.1） | 🟡 部分 |
| 「推荐给朋友」/「拉黑」/「举报」/「删除好友」 | 在「更多」里 | 已补（举报走 `POST /reports`，`target_type=user`） | ✅ |
| 「呼叫」「视频」 | 占位吐司「即将上线」 | 同 | ✅ |
| 大头像头部 | 有（水滴） | 见 §1 |

**为什么不合并成一页**：iOS 合并是有代价的——`IMChatDetailViewController.m` 光主文件就 1438 行，
外加四个分类共 6000 行，`if (self.isGroup)` 遍布其中。它合并得起，是因为两边共用了
**页签那一大套**（复用价值高）。本端的群详情还带着成员分页/群管理入口等群专有物，
合并只会得到一个塞满 `if (isGroup)` 的大文件。**页签那一层已经共用**——2026-09-09 起共用的不只是"打开同一页"，而是**同一段渲染与同一份取数**
（`archiveTab` / `rememberConvArchive` / `ArchiveActionsHost`）：两页长得一样最可靠的保证不是
各写一遍对着改，是同一段代码画的。共用的是有复用价值的那部分，这是本表的判断。

### 2.1 会话内搜索（2026-09-09 落地并**真机验过**，缺两块）

iOS：`Modules/Chat/IMChatViewController+Search.m`（741 行）+ 状态袋 `IMChatSearchState`。
入口是详情页头部操作排的「搜索」pill，进去之后**聊天页顶栏变搜索框、底部出命中导航条**。
设计与三端口径见 [`../../IMServer/docs/design/SEARCH_DESIGN.md`](../../IMServer/docs/design/SEARCH_DESIGN.md) §4。

| 项 | iOS | Android | 判定 |
|---|---|---|---|
| 入口（详情页「搜索」pill） | 有 | 有（单聊详情 + 群资料两处） | ✅ |
| 顶栏换搜索框 | `IMLiquidNavigationBar` searchMode | 纯色搜索栏（不做液态玻璃，见 §4） | 🟢 刻意差异 |
| 底部命中导航条（计数 + ▲▼） | `buildSearchNavBar` + `buildCountPill` | 同（`ChatSearchNavBar`） | ✅ |
| 默认跳「最新一条命中」 | 有 | 有 | ✅ |
| 命中词高亮 | `applySearchHighlight:`（accent-soft 底） | 同（`highlightedText`，同样走 `accentSoft` 不硬编码黄） | ✅ |
| 命中行**居中滚动** + 1.2s 高亮 | `ScrollPositionMiddle` | 同（`centerItem`：Compose 没有对应参数，先顶到顶端再补半屏偏移） | ✅ |
| 跳不了时的说法放哪 | 吐司 | **写在搜索条上方那一行**，不用吐司——搜索态下键盘占着下半屏，吐司恰好落在键盘背后（真机撞见）。顺带给 `IMToast` 补了 `imePadding` | 🟢 本端更好 |
| 命中口径（text content / caption / file_name 子串） | 有 | 有（`ChatSearch.matches` + DAO 同一条判据） | ✅ |
| **搜的是整个会话不是渲染窗口** | 查 `IMDatabase` | 查库（`MessageDao.search`），**刻意不接收渲染窗口** | ✅ |
| 本地齐全走本地 / 有缺口在线问服务端 / 离线降级并标注 | `IMPickConvQuerySource` | 同（`ChatSearch.pickSource`，文案与 im-web 逐字一致） | ✅ |
| 命中被单页上限截断时计数补 `+` | 有 | 有（本地 500 / 服务端 50） | ✅ |
| **跳到窗口外的命中** | 按锚点开窗（`jumpToConvSeq:`） | 同（2026-09-09 改为锚点开窗，见 §2.3） | ✅ |
| 📅 按日期跳转 / 日历 | 有（`IMChatDateJumpViewController` + 服务端 `/calendar`） | **没有** | 🔴 欠账 |
| 👤「来自某人」发件人过滤 | 有（SEARCH_DESIGN §4.1） | **没有**（服务端 `?from=` 与本端接口都留着，缺的是成员下拉那层 UI） | 🔴 欠账 |
| 「@我的消息」聚合 | **iOS 也没有**（服务端 `/mentions` 三端都没接） | 没有 | ✅ 三端一致地欠着 |

### 2.3 按锚点开窗（2026-09-09，`MESSAGE_WINDOW_DESIGN` 的 Android 那一期）

服务端、iOS、Web 早就有这一套（W1–W3，2026-08-31 / 09-01），Android 列一直是 ⬜。本轮补上。

| 项 | iOS / Web | Android | 判定 |
|---|---|---|---|
| 渲染窗口是**单一连续区间**，跳转即换锚点重开 | 有 | 同（`ChatWindow.Tail` / `Anchored`） | ✅ |
| 所有定位入口收敛到一个函数 | `jumpToConvSeq:` / `locateInChat` | `ChatLocator`（引用块 / 搜索命中 / 归档「定位到聊天」三处共用） | ✅ |
| 目标不在本地 → `window_req` 问服务端 | 有 | 有（`WindowRequester`），**但本机验不到**：两台设备的本地库都是齐的，走不到这条分支 | 🟡 已实现未实测 |
| `anchor_found=false` 才报「原消息已被删除」 | 有 | 同（本地齐全找不到，或服务端明说没有，才这么说） | ✅ |
| 窗口停在历史时亮出「回到最新」 | 有 | 有（`ChatWindows.showsJumpToLatest`，单测钉住） | ✅ |
| 自己发消息拉回最新 | 有 | 有（发送即换回尾窗） | ✅ |
| 收到 `window_resp` **不推进同步游标** | 有 | 同（只落库；推进游标会让 sync 以为这一段覆盖过了） | ✅ |

**为什么值得单独做一轮**：上午那版是「把『最近 N 条』的 N 撑大到盖住目标」，必须带一个 5000 条的
上限（13 万条整窗构造对象会把聊天页渲染成空白），上限之外只能如实说跳不过去。锚点窗没有这个问题
——不论目标多早，取的都是它前后各一页。**实测**：11 万条的大群里跳到 `conv_seq 7`（会话开头）一次到位。

---

### 2.2 归档长按菜单（2026-09-09 落地并验过）

iOS：`IMChatDetailViewController` 的 `contentMenuConfigForMessage:`——那一侧**唯一**的菜单真源，
四个逐行页签（文件/语音/链接/名片）与媒体宫格共用它。本端照抄这一条：
判据在纯函数 `data/ArchiveActions.kt`（有单测），接线在 `ui/ArchiveActionsHost.kt`，
**单聊内联页签与群资料独立归档页共用同一份**。

| 项 | iOS | Android | 判定 |
|---|---|---|---|
| 菜单项与顺序（转发 → 定位到聊天 → [取消下载] → 删除） | 有 | 同 | ✅ |
| `conv_seq <= 0` 直接不弹菜单 | 有 | 同 | ✅ |
| 「取消下载」仅在下载中/已暂停时出现 | 有 | 同 | ✅ |
| 删除两档（自己发的 or 群管理员 → 子菜单「仅删除自己 / 为所有人删除」；否则单项） | 有 | 同（`canDeleteForEveryone` 判据逐字对齐） | ✅ |
| 「定位到聊天」 | pop 回聊天页 + `jumpToConvSeq:` | 关掉详情页 + `ChatArm(locateSeq)` 交给聊天页（`ChatLocator` 撑窗口再滚，见 §2.1） | 🟢 手段不同、语义一致 |
| 长按预览（原位重绘那一项） | `UITargetedPreview` | **不做**：宫格那一格是张远端图，重绘要再拉一次；行本来就在原位看得见。只压暗背景 + 菜单贴着它弹 | 🟢 刻意差异 |
| 语音页签内播放 | 有（复用聊天页单例 `IMVoicePlayer sharedPlayer`） | **仍没有**（长按可定位/转发/删除，脚注已如实写明） | 🔴 欠账 |

**验过**（模拟器 API 36 / user1001）：长按宫格弹菜单（转发 / 定位到聊天 / 删除，非自己发的消息
只有单档删除——与 `canDeleteForEveryone` 一致）；「定位到聊天」关掉详情页并滚到那条；
「转发」开出转发选择页，返回键只关掉它、回到详情页。**没验**：删除两档的实际执行、
「取消下载」（要造一个下载中的状态）、群资料那一侧的入口（与单聊共用同一份接线，只是入口不同）。

---

**真机验了哪些**（Pixel 2 XL / API 30，11 万条的「20000人大群」，本地库已同步齐全）：
群资料页「搜索」pill → 聊天页搜索态；输入即搜（`hits=21`，与服务端库里同一关键词的条数**逐条相符**）；
默认跳最新一条命中且**居中 + 高亮**；命中在渲染窗口外时窗口从 200 撑到约 1750 条后跳达；
▲ 翻到比 5000 条上限还早的那条时**如实提示**且不乱滚；换关键词后提示自动清掉；
返回键先退搜索态、再退会话。**没验**：离线降级那一档（要断网构造本地缺口）、服务端兜底那条路
（本机这些会话本地都是齐的，走不到 `QuerySource.Server`）。

**日历与「来自某人」为什么这轮不做**：SEARCH_DESIGN 自己把它们定在 **P1**
（§8「P0 先把词命中导航跑顺」）。日历还要接服务端 `/conversations/{id}/calendar`
与一整页月历，「来自某人」要复用群成员列表做下拉，各是独立一块。

---

**归档数据来源为什么不跟 iOS**：iOS 从本地已加载消息里筛，好处是零请求、离线可用，
坏处是**只看得到已加载的那一段**（大群里往上翻不到的媒体就不在归档里）。
服务端接口是全量的、且已经处理好可见性过滤（撤回 / 为所有人删除 / 仅为我删除 /
`history_visible` 下界）。本端选服务端，是因为本端从一开始就有渲染窗口有界化
（`WINDOW_PAGE`），本地消息更不完整。**但「链接」这一格服务端不覆盖**
（链接不是独立 `content_type`，没有可索引的列），那一格只能扫本地文本——
于是这一格天然与 iOS 同口径、也天然只覆盖已加载部分，界面上要说清楚。

---

## 3. 群管理页

iOS：`Modules/Detail/IMGroupManageViewController.m`，`InsetGrouped` 表 + 群头像头部，
六个分区（`IMManageSection`）：

```
Profile     （无标题）群名称 / 简介 / 群公告        footer：简介与公告展示给全体成员…
加入与发言   进群确认 / 全员禁言                    footer：进群确认…全员禁言…
成员权限     仅管理员可邀请 / 可改群资料 / 可置顶消息 / 新成员仅可见入群后历史   footer：…
治理         待审入群申请 / 黑名单
管理员       管理员（右侧显示人数）                  footer：管理员可审批入群…
群主         转让群组（**红色**，仅群主可见）        footer：转让后你将立即变为普通成员…
```

每行**带 SF Symbol 图标**（`IMGroupManageRowIcon`）：`tag.fill` / `text.alignleft` /
`megaphone.fill` / `lock.shield` / `mic.slash` / `person.badge.plus` /
`square.and.pencil` / `pin` / `clock.arrow.circlepath` /
`person.crop.circle.badge.checkmark` / `nosign` / `person.badge.shield.checkmark` / `crown.fill`。

| 项 | Android | 判定 |
|---|---|---|
| 分区划分与顺序 | 已对齐（2026-09-08） | ✅ |
| 开关文案 | 已逐字对齐（也与 im-web 一致） | ✅ |
| 群头像头部 | 已补 | ✅ |
| **换群头像**（相机圈 + 「设置新头像」） | 已补（2026-09-08；此前群名/简介/公告都能改，唯独头像没入口） | ✅ |
| 行图标 | 已补（Lucide 近义图标，非 SF Symbol） | 🟢 刻意差异 |
| 每节 footer 说明 | 已补 | ✅ |
| 黑名单 / 管理员 / 转让群组 三节 | 已补 | ✅ |
| 群名称 / 群简介 / 群公告三行**右侧直接预览当前值**（`cell.detailTextLabel`） | 已补（2026-09-22）：此前 `ChevronRow` 不传 `value`，编辑之前完全看不到已填了什么——现三行都带预览，公告/简介按单行省略号截断（最长 500 字，换行折成空格） | ✅ |
| 群简介/群公告编辑页 | iOS 是全屏专属编辑页 `IMGroupTextEditViewController`（带 `n/200` 实时计数、公告独有「撤下」按钮）；Android 是内联对话框 `IMTextPrompt`，无字数计数、公告撤下靠清空文本框 | 🔴 欠账（体验落差中等，非本轮范围） |

**图标为什么不逐一对上**：SF Symbol 是 Apple 私有字体，Android 上不存在。本端用 Lucide 里
语义最近的一枚（`lock.shield`→`ShieldCheck`、`mic.slash`→`MicOff`、`nosign`→`Ban`、
`crown.fill`→`Crown`…）。**要对齐的是"每行都有一个能一眼认出的图标"这件事**，不是同一张图。

---

## 3.5 消息气泡与聊天页

| 项 | iOS | Android | 判定 |
|---|---|---|---|
| 系统消息 | 胶囊（圆角 11 / `datePillBg` / 白字 12）+ 名字段琥珀半粗可点<br>`IMSystemCell` | 同（`SystemNote` + `SysSegments`） | ✅ |
| 引用块 | 竖条 + 群聊两行式（被引用者昵称独占一行）+ 类型图标 + 灰字快照<br>`IMBubbleCell` | 同（`QuoteBlock`） | ✅ |
| 引用块内**真缩略图** | 引用图片/视频时内嵌 24×24 真缩略图（异步） | 已补（2026-09-08）：**本地反查原消息**拿 `thumb` 画磨砂缩略——引用快照是发送时冻结的一串文字，本身不带图 | ✅ |
| 点引用块**跳转原消息** | 有 | 已补（瞬时滚动 + 居中 + 1.2s 高亮）。2026-09-09 起走**锚点开窗**（与搜索命中、归档定位共用 `ChatLocator`），不论多早都跳得到；真的不在了才如实提示 | ✅ |
| 文件类型图标 | 22 张 SVG（`FileType_*.imageset`）+ `IMFileTypeIconForName` | **Compose 重画同一套**（`FileTypeIcon`） | 🟡 见下 |
| 长按菜单 | `UIContextMenu`：原位、每项带 SF Symbol、删除是 inline submenu | 同（`MessageContextMenu`，Lucide 图标） | ✅ |
| 长按时原气泡 | `UITargetedPreview` 自动藏原视图 | 手动把那一行 `alpha=0` | ✅ 语义一致 |
| 长按**抬起**动画 | `UIContextMenu` 自带弹起 | 已补（spring 0.94→1.02 + 投影） | ✅ |
| 长按九宫格 | 浮起**手指按住的那一格** | 已补（每格自带 rect / 只隐那一格 / 菜单作用于那一条） | ✅ |
| 长按带链接的文本 | 整个气泡（含富预览卡）一起浮起 | 已补（预览与列表共用 `ChatRowView`） | ✅ |
| 「转文字 / 收藏 / 置顶 / 编辑 / 多选 / 翻译 / 举报」菜单项 | 有 | 没有（对应功能本端都还没做） | 🔴 欠账 |
| 名片卡 / 聊天记录卡 | 头像 44 / 间距 10 / 分隔线 / 11pt 脚注**带图标** | 同（脚注图标 2026-09-08 补） | ✅ |
| 图文消息（媒体 + caption） | 媒体贴气泡边、只圆上角、时间胶囊浮在图上 | 同 | ✅ |
| 文件文（文件 + caption） | 文件卡在上、caption 在下（`_fileCaption` 距文件行 6），时间在最下面靠右 | 已补（2026-09-15，**未真机**）：此前图说判据挂在「贴边媒体」上，文件文的字整段不画。判据抽到 `data/BubbleCaption.kt`；聊天记录详情页的文件项同漏、一并补 | ✅ 待真机 |
| 复制的适用范围 | 文本 / 已发出的图片（复制字节）/ 有图说的消息（复制图说） | 已补齐（2026-09-16，**未真机**）：判据 `copyKindOf`——**caption 压过一切**（带图说的图片复制那段文字，不是图）/ 图片复制图片 / 视频·文件复制链接 / 纯文本复制文本，三档文案「已复制 / 已复制图片 / 已复制链接」同 iOS。长按菜单与查看器「更多」共用这一份。**两处刻意不跟**：voice 不给（iOS 那支走兜底复制 `content`，是一段相对路径，对用户没意义）、系统消息不给。**仅存 OS 级差异**：安卓没有位图剪贴板，复制图片放的是 FileProvider 的 `content://`，粘进纯文本框是一段 URI（粘回本 App 已能还原成图） | ✅ 待真机 |
| 粘贴图片到输入框 | 剪贴板里的图直接粘进输入框，起一排可撤销的缩略图（`appendPastedImage:` + `refreshPasteBar`），随发送一起发 | 已补（2026-09-16，**未真机**）：此前在本 App 里粘不上——安卓剪贴板里放的是 `content://`，而 Compose 的 `BasicTextField` 只收纯文本，系统把它 `coerceToText` 成一行字符串插进正文，用户看到的是一段 URI。现由 `data/PasteImage.kt` 把它从正文里认出来摘掉、转成待发图挂在输入栏上方（`ui/PasteImageBar.kt`）。**发送键与 iOS 同，就是输入栏那颗**（`Composer.extraSendable`：正文有字**或**粘贴条上挂着图就能发）——早先它自带过一颗，是因为那颗的可用态只看正文、而 `ChatScreen.kt` 顶着 600 行硬闸不便加参数；2026-09-16 把滚动时序拆进 `ChatScroll.kt`（598→520）后就归位了。**仅存差异**：只认本地 `content://`，http/https 原样留在正文里（那是用户真的在发链接） | ✅ 待真机 |
| 正文里的链接 | 蓝字下划线（`IMURLRangesInText`），点开走应用内浏览器（`openLink:` → SFSafariViewController） | 已补（2026-09-16，**未真机**）：此前正文**从不识别链接**，不高亮也点不动。区间正则逐字照抄 iOS（`LinkDetect.urlRanges`，SYMMETRY 已登记）；点击走 `LinkAnnotation.Clickable`，与 @提及同一手段，长按菜单不受影响；图说与 iOS 同样不识别 | ✅ 待真机 |
| 链接预览卡可点 | `IMLinkPreviewView.onTap` → `openLink:` | 已补（2026-09-16，**未真机**）：`passThroughTap`，长按照常弹气泡菜单 | ✅ 待真机 |
| 应用内浏览器 | SFSafariViewController（聊天页、详情「链接」页签、聊天记录文件、收藏共用） | 新增 `WebViewScreen`（宿主 `WebLinkHost`，经 `LocalOpenLink` 取用，从底部推上来）：标题 + 站点名、加载进度条、返回键先在网页里后退、⋯ 菜单（刷新 / 复制链接 / 用浏览器打开）、加载失败给重试与「用浏览器打开」。**本站邀请链接不走原生加群流程**（iOS `routeInviteLinkIfOwn`，本端扫码读码那半边还没做） | 🟡 部分 |
| 宫格里视频格的播放角标 | `IMAlbumTileView._playBadge`：就绪才显，上传 / 失败 / 门控期让位 | 已补（2026-09-16，**未真机**）：与单条视频气泡共用 `VideoPlayBadge`，宫格里小一号（32dp） | ✅ 待真机 |
| 媒体查看器的控件 | 点画面播放 / 暂停；底部「时间 · 进度条 · 倍速」在按钮排上方 14；右下角「更多 / 媒体 / 下载」，更多 = 下载 / 定位 / 收藏 / 复制 / 转发 / 删除 | 已补（2026-09-16，**未真机**）：同位同序，收藏要求已确认且未撤回；「媒体」进**会话媒体库页**（2026-09-16 修：此前跳的是「聊天信息」详情页并落在媒体页签上，用户点「媒体」却进了设置页；现复用同一宿主的 `galleryOnly` 形态——收起头像头部 / 信息卡 / 页签条，标题＝会话名，对齐 iOS `IMConversationMediaViewController`。**刻意不另起一页**：归档取数、长按菜单、查看器、转发选择页那整套接线都在宿主里，另写一份必然分叉，`ConvMediaScreen` 已经因此被并掉过一次）；**左右翻页已补**——聊天页入口本地库打底 + 翻到最旧向服务端续拉，归档入口用页签已拉到的那几页，记录页在那份快照内部翻（判据 `data/MediaTimeline.kt`）；**「复制」已补**（`ui/CopyImageAction.kt`）——与 iOS 语义有差：本端复制的是图片文件的 `content://`（经 FileProvider），不是位图字节，粘进纯文本框会是一段 URI；**「查看原视频」胶囊已补**（判据 `data/OriginalVideo.kt`，文案「查看原视频 · 12.3MB」/「下载中 42%」/「下载失败，点击重试」，下完切本地播放——2026-09-16 修：此前下完胶囊不消失、也不切本地，根因是查看器从不因下载状态重组，`hasLocal` 与播放器用的本地文件都停在下载开始前的答案；现只收「已就绪地址集合」这一个派生值（`OriginalVideo.readyUrls` + `distinctUntilChanged`），它在下载完成那一刻才变一次，**进度帧不触发重组**），但**本端没有断点续传**（iOS 有） | 🟡 部分 |

**文件类型图标为什么是重画不是转换**：iOS 那 22 张是 SVG，而 Android 的 vector drawable
**不支持 `<text>`**——那些图的角标（PDF / W / X / `{ }` / `</>`）全是文字元素，直接转会丢光。
于是按同样的页面外形（path 逐字取自 SVG）、同样的渐变色、同样的角标在 Compose 里画。
扩展名 → 类型的清单**逐条照抄 iOS**：两端认的类型不一样，同一个文件在两端就是两种图标。

---

## 3.6 媒体的进度与占位（上传 / 下载 / 磨砂）

**逐功能完成度以 [`CLIENT_PARITY.md`](../../IMServer/docs/CLIENT_PARITY.md) 的 Android 列为准**
（M4-7 那一整族目前全是 ⬜）。这里只记**观感与结构**上的差别。

| 项 | iOS | Android | 判定 |
|---|---|---|---|
| 单条待发媒体的上传进度 | 左上角进度胶囊（`_progressWrap`，与时长角标互斥） | 居中进度环 + 百分比 | 🟢 刻意差异（位置不同，信息一致） |
| **宫格逐格**上传进度 | `IMAlbumTileView` 逐格有环 | 已补（2026-09-08；此前只压一层暗底，混发图片和视频时那段视频传几分钟屏幕上一点进度都没有） | ✅ |
| 图片的上传进度 | 分片上传，有百分比 | **整包上传，没有回调** → 宫格里给转圈的 | 🟡 见下 |
| 宫格里发失败的那一格 | ↻ 重试 | 红❗（点击重发挂在格子上） | 🟢 刻意差异 |
| **下载进度环 / 门控五态**（未下载 ↓+尺寸 / 下载中 环+⏸ / 暂停 / 失败 ↻ / 失效 ⊘） | 有（`IMMediaDownloadCoordinator` 统一编排，气泡·宫格·文件·详情四处接入） | 已补（2026-09-08）：`MediaDownloader` 统一编排，五处接入（图片气泡 / 视频 / 文件气泡 / 宫格逐格 / 详情页宫格与文件行）。**无断点续传**（暂停后重来），iOS 有 | 🟡 部分 |
| **下载完的东西真的被用上** | 查看器放 `IMOriginalVideoCache` 里的本地原件 | 已补（2026-09-08）：查看器/播放器/存相册一律优先本地文件。真机验过**断网仍能播** | ✅ |
| 详情页/媒体库**不自动预取** | `autoPrefetchEnabled = NO`——翻历史不该静默拉走几百 MB | 已补（`rememberGate(autoPrefetch = false)`） | ✅ |
| 文件「就绪」态点一下**打开文件** | `IMFilePreviewPresenter` + QuickLook | 已补（复制进 `cache/share` → FileProvider → `ACTION_VIEW`；**不放开 `files-path`**，理由见 `file_paths.xml`）。没有能打开的应用时如实说 | 🟢 刻意差异（系统选择器 vs QuickLook） |
| 自己发出去的东西不该显「未下载」 | 发送成功即 `adoptFileAtPath:` 进缓存 | 已补（整包上传那条路 `MediaCache.adopt`）。**分片上传（视频/大文件）没 adopt**——字节从没同时在内存里，为此复制一份几百 MB 不划算，那种自己发的大件仍会显 ↓ | 🟡 部分 |
| 自动下载按**真实网络类型**选档 | 有 | 有（Web 恒用 Wi-Fi 档是它分不清网络的限制，本端**不照抄那个限制**） | 🟢 本端更好 |
| **磨砂占位**（`thumb` 极小模糊缩略 → 高斯模糊） | 有（`IMMediaPlaceholder`，气泡·详情宫格·引用缩略三处共用） | 已补（2026-09-08）：**发送侧生成 + 落库 + 接收侧渲染**，图片气泡 / 视频封面 / 宫格逐格 / 详情页媒体宫格四处。**引用缩略仍没有**（引用块只有类型图标，见上面那条 🔴） | ✅ |
| 磨砂的实现手段 | Core Image 高斯（sigma 4） | **在位图上算三趟盒糊**，不是 `Modifier.blur` | 🟡 见下 |
| 自动下载**策略**（两网络 × 三类型 × 单聊/群聊 + 大小上限） | 有 | 已补（`DownloadPolicy`，判据逐条对齐；登录后拉一次，进程内缓存） | ✅ |
| 自动下载**设置页**（三层 InsetGrouped + 快捷档位滑块） | 有 | **没有**——策略只能用服务端下发的值，端上改不了 | 🔴 欠账 |
| 媒体已失效（服务端已清理）的 ⊘ 占位 | 有（`IMMediaExpiryRegistry` + 四处接入） | 已补（下载路径 404/410 → ⊘ +「文件已失效」+ 不再回源）。**失效标记只在内存**，重启即忘（iOS 同为内存态，Web 落了 localStorage） | 🟡 部分 |
| 老消息没有 `thumb` | 协议明写服务端不回溯 | **端上补种**（`ThumbBackfill`）：原图已在本地时自己算一张存进本地库，下次进会话就有磨砂占位。只补本机、不上行、不为补它联网 | 🟢 本端更好 |

**图片为什么没有上传百分比**：本端图片走整包上传（压缩后几百 KB），`UploadApi.upload`
没有进度回调；iOS 是一律走分片上传器所以天然有。改成分片只为了一个百分比不划算——
几百 KB 在一次往返里就传完了，百分比只会闪一下。宫格里给一个**转圈**的：
"在传但不知道传到哪"也是信息，比只有一层暗底强。

**一条自己踩的坑（2026-09-08）**：宫格逐格的磨砂与门控**改了两次才真的生效**——
第一次的编辑因为匹配串对不上**静默没落地**，而编译、单测、门禁全绿，
上一版提交信息与本表里都写着"已接入"。真机上的表现是
「文件气泡有门控徽标、宫格却在偷偷下原图」。教训与 §5 那条同源：
**缺失的东西不会报错**——改完要按渲染点逐个 grep 确认，别信"我刚才改过了"。

**磨砂为什么不用 `Modifier.blur`**：那是 `RenderEffect`，**API 31 才有，31 以下静默无效**——
本仓 minSdk 26，手上这台 Pixel 2 XL 是 API 30，真机上根本看不到效果，而编译与预览都不报任何问题。
所以照 iOS 的做法在位图上算：把 ~20px 缩略放大到 48 见方的代理图，再跑三趟盒式模糊（≈高斯 sigma 4）。
48×48 三趟是微秒级，且与 API 版本无关。**边缘要钳制**——把越界当透明黑再除以整窗会让四周发暗
（iOS 用 `clampToExtent`、Web 用 `transform: scale(1.15)` 把发暗的边挤出可视区，三端手段不同、要的是同一条）。

**下载那一族为什么是一整块**：iOS 的 `IMMediaDownloadCoordinator` 不是一个进度条，
而是「门控（要不要自动下），状态机（未下载/下载中/暂停/失败/就绪），本地落盘，
失效登记，设置页」五件事。本端现在是**没有下载概念**——Coil 见到 URL 就拉。
补它要连着服务端已有的 `/download-settings` 一起做，是独立一档，不是顺手加个环。

---

## 4. 标题栏

见 [`../IMServer/docs/UI_SPEC.md`](../../IMServer/docs/UI_SPEC.md) §4.5（三端共用基准，不在本表重复）。
本端额外一条：

| 项 | iOS | Android | 判定 |
|---|---|---|---|
| 液态玻璃标题栏 + 滚动形变 | `IMLiquidNavigationBar`（iOS 26 原生观感） | 平面标题栏 | 🟢 刻意差异 |
| 进入 / 退出页面的转场 | `UINavigationController` push：新页从右进、旧页左移让开；pop 反向 | 已补（2026-09-15，**未真机**）：`ui/components/PushTransition.kt`（300ms、视差 30%、退场页隔离返回键与触摸），接在 Tab 根↔聊天、聊天↔详情（`PushBase` 让开不出组合）、通讯录/「我」二级页、设备详情、隐私与安全、数据和存储三层。**没接**：群资料 / 聊天信息内部子页、用户资料内部、聊天记录逐层、查看器（它们的内容读的是已置空的状态，套上会半路变白，要先改成按转场状态渲染） | 🟡 部分 |
| 根页标题（消息 / 通讯录） | 注入液态栏，标题**居中**；会话列表的连接态走副标题 | 已改（2026-09-15，**未真机**）：两页改用 `IMTopBar`，连接中放副标题。此前是左对齐的 headlineSmall 大标题（用户报「标题不居中」） | ✅ 待真机 |
| 根页右上角动作 | 圆形钮：会话列表 `plus` → 锚点菜单「扫一扫 / 新建群聊 / 添加好友」（`plusTapped:`）；通讯录 `person.badge.plus` → 添加朋友 | 已改（2026-09-15，**未真机**）：`TopBarCircleButton`（UI_SPEC §4.5）。会话列表去掉文字「我」换 ＋ 菜单（`ui/ChatsHost.kt`，两页在本 Tab 内 push、建群后直接进新群）；通讯录主色裸放大镜换 ＋人。**扫一扫本端没做**，入口在、点了提示 | 🟡 扫一扫未做 |
| 二级页的底部 Tab 栏 | `hidesBottomBarWhenPushed`：push 出去就收起 | 已修（2026-09-15，**未真机**）：此前「通讯录」「我」的二级页在 Tab 内容区原地切换，底栏一直挂着。现只在根页画（`TabRoot` 插槽，判据 `PushNav.showsTabBar`），且跟着根页一起滑走 | ✅ 待真机 |

**为什么不做液态玻璃**：那是 iOS 26 的系统级材质（含实时折射与镜面高光）。
Compose 里手搓只能得到"半透明 + 模糊"的形似神不似版本，比干净的平面栏更糟。
Android 有自己的 Material 语汇，**平台观感不同不是欠账**。

---

## 4.5 通讯录 / 群列表

iOS：`Modules/Contacts/IMContactsViewController.m` 的 `entries` + `entryColors`，
`Modules/Group/IMGroupListViewController.m`。

| 项 | iOS | Android | 判定 |
|---|---|---|---|
| 顶部入口 | 群聊(绿) / 新的朋友(青) / 公众号(橙) / 服务号(蓝) | 已对齐（2026-09-08；此前只有两条，且第二条是「发起群聊」——那是**动作**不是入口） | ✅ |
| 入口图标底色 | 逐条不同（靠颜色区分） | 同（**不跟主题主色走**，全刷 accent 就退回"四个一样的绿圈"） | ✅ |
| 建群入口 | 在群列表页右上角 `+`；会话列表 ＋ 菜单「新建群聊」 | 同（会话列表那条 2026-09-15 补；两处共用 `ui/CreateGroupHost.kt`） | ✅ |
| 右上角「添加朋友」 | `person.badge.plus` 圆钮 → `IMUserSearchViewController`，标题「添加朋友」 | 同（2026-09-15；此前主色放大镜、页标题「找人」；与会话列表 ＋ 菜单共用 `ui/AddFriendHost.kt`） | ✅ |
| 点好友行 | 进**资料页**（`openPeerDetail:`） | 已改（此前直接进聊天，违反三端统一的微信式口径） | ✅ |
| 好友按拼音 A–Z 分组 + 右侧索引尺 | 有（`IMContactSectionIndex`，含多音姓氏表） | 已补（2026-09-09，见 §4.5.1；真机核对过分组/跳组/2000 人滑动） | 🟡 部分（首字母来源不同） |
| 好友行左滑：删除 / 拉黑 | 有 | 已补（2026-09-09，见 §4.5.1；真机核对过左滑/拉黑/解除/删除确认） | ✅ |
| 群列表副标题 | 「我是群主」/「群主 X」 | 同（**不是人数**——`GET /groups` 的 `Summary` 根本不下发 `member_count`） | ✅ |
| 公众号 / 服务号 | 占位吐司 | 同 | ✅ |

### 4.5.1 好友分组 / 索引尺 / 左滑（2026-09-09）

iOS：`Modules/Contacts/IMContactSectionIndex.{h,m}`（纯数据类，通讯录与选好友页共用分桶层）
+ `IMContactsViewController` 的 `sectionIndexTitlesForTableView:` 与
`trailingSwipeActionsConfigurationForRowAtIndexPath:`。**动手前抄下来的结构清单**：

| 判据 | iOS | Android | 判定 |
|---|---|---|---|
| 分组键 = 显示名（备注优先）首字的拼音首字母 | 有 | 同 | ✅ |
| **多音姓氏覆盖表**（曾Z 仇Q 单S 解X 查Z 区O 乐Y 翟Z 覃Q 秘B） | 10 条，命中即覆盖拼音结果 | **逐条照抄这 10 条** | ✅ |
| 归 `#` 的三种情况：名字空 / 转拼音为空 / 首字母不在 A–Z | 有（数字、emoji、俄日文都进 `#`） | 同 | ✅ |
| 组间排序：A–Z 升序，`#` **恒排最后** | 有 | 同 | ✅ |
| 组内排序：全串拼音升序，**保留音节间空格**（空格 ASCII 低 → 姓在前：li < lin < liu）；拼音相同再按显示名不区分大小写 | 有 | 同 | ✅ |
| **空组不显示**（只建出现过的桶，不预置 26 个字母） | 有 | 同 | ✅ |
| 无好友时索引尺整条隐藏 | `titles` 空则 `sectionIndexTitles` 回 nil | 同 | ✅ |
| 顶部四个入口**不参与**索引尺 | 靠 `+1` 偏移绕过 | 索引尺只映射字母组 | ✅ |
| 索引尺控件 | 系统 `sectionIndexTitles`（无触感反馈）：主色字母、无底色、固定行高整条竖直居中 | **自绘**（Compose 没有对应控件）：右侧字母条，按下/拖动即跳组。**观感 2026-09-15 照系统那条改**：主色 11sp 半粗、无底色、16dp 行高竖直居中（放不下按可用高度收缩）；此前 SpaceEvenly 铺满整列 + 按下铺灰底（用户报「太丑」） | 🟢 手段不同 |
| 左滑动作与顺序：`[删除, 拉黑/解除拉黑]` | trailing only；删除 destructive 红；拉黑随 `blocked` 切标题与颜色（解除拉黑=绿 / 拉黑=灰） | 同 | ✅ |
| 顶部入口区不可左滑 | `isFriendSection:` 总闸 | 入口不在可滑的行里 | ✅ |
| 拉黑不解绑，被拉黑好友仍在列表、副标题带「已拉黑」 | 有 | 同 | ✅ |
| **删除好友的二次确认** | **没有**（左滑「删除」直接发请求） | **有** | 🟢 本端更好 |

**首字母为什么不是同一套实现**：iOS 用系统的 `CFStringTransform(kCFStringTransformMandarinLatin)`。
Android 上没有等价物——`android.icu.text.Transliterator` 要 API 29 而本仓 `minSdk 26`，
而且它在单测的桌面 JVM 上根本不存在，写了等于**判据没法测**。本端改用
`java.text.Collator(Locale.CHINA)` 与 26 个边界字比较取首字母：JVM 与 Android 上它都走
拼音序排序，得到的是同一条不变式（**按拼音首字母分组**），不是同一段实现。
**因此单测钉的是规则**（覆盖表、`#` 的三种情况、组间/组内排序、空组不显示），
"某个汉字属于哪个字母"这一步靠真机核对——`SYMMETRY.md` 那条：要一致的是不变式，不是代码形状。

**真机上抓到的三条**（2026-09-09，模拟器 `im_test` / user1001 / 2014 个好友，桌面单测一条都测不出来）：

1. **组头的 LazyColumn key 被写成了字面量**——`item(key = "h-${'$'}{g.key}")` 里 `$` 被转义，
   所有组头共用同一个 key，画到**第二组当场崩**（`Key ... was already used`）。
   单测测不到：它是 LazyColumn 测量期才抛的。现在写成 `"h-" + g.key`。
2. **「阿强」既不在 A 组也不在任何组，直接掉进 `#`**。桌面 JVM 上「阿」≥ 边界字「啊」，
   Android 的 ICU 上却**相反**，比到头一个边界都没命中。判据补成「排在第一个边界字之前的汉字仍归 A」
   （拼音序里 a 是最小的声母；collator 不认识的生僻字实测排在**最后**，不会被误收进 A）。
   这一档在桌面 JVM 上**走不到**（扫遍 U+4E00–U+9FFF 没有汉字排在「啊」之前），
   所以比较被抽成参数注入（`ContactSection.initialByBoundaries`），否则断言永远绿。
3. **分组在 2000 人量级上会卡到 ANR**。原来把 `Collator` 的排序键写在 comparator 里，
   于是**每次比较都重建一个 CollationKey**：2014 人 ≈ 4 万多次。
   改成先算完键再排（decorate-sort-undecorate）+ 首字母按**首字**缓存，
   桌面 JVM 实测 98ms → 14ms、取首字母 38ms → 0.9ms。

**`/code-review` 又打回一轮，修了 8 条**（这一轮它仍是最大的发现渠道）：

| # | 问题 | 修法 |
|---|---|---|
| 1 | 好友行的**分割线跑到了行首**：`FriendRow` 顶层发的是 `Row` + `Box(分割线)` 两个兄弟，原先是 LazyColumn item 的直接子节点（沿主轴依次摆），包进 `SwipeActionRow` 的 `Box` 后**互相重叠** | 内容容器 `Box` → `Column` |
| 2 | **「拉黑」那格白字看不见**：借用了 `neutralControl`（禁用态填充，浅色 α≈18%），白字压上去对比度约 1.2:1；iOS 那侧是**不透明**的 `systemGray` | 新增语义令牌 `swipeNeutral` / `swipePositive`（对齐 systemGray / systemGreen） |
| 3 | 滑开的行**仍可点**，点了进资料页而不是收起；且**多行可同时敞开** | 「当前敞着哪一行」上提到 `ContactsScreen`，`SwipeActionRow` 改成受控。②**由调用方在 onClick 里判**——内容自带的 `clickable` 是子节点，Main 传递自下而上，父层拦不住；改 Initial 能拦但会把拖动一并吃掉（真机验过拦指针那版**照样进了资料页**） |
| 4 | `Collator` 单例非线程安全，而 `sectionCache` 用 `ConcurrentHashMap`，护栏与实际约束互相矛盾 | KDoc 写明「只在单线程调用」+ 说明 CHM 只是图 `getOrPut` 省锁 |
| 5 | 三处新增的裸 `runCatching` 会吞 `CancellationException` | 换 `runCatchingCancellable`（本仓已备好） |
| 6 | `reload()` 是空 `catch`（注释说"只记日志"，一行日志都没有），而它是拉黑/解除/删除后刷新 UI 的**唯一路径**——弱网下动作成功、reload 静默失败，用户看到吐司却看不到变化 | 补 `IMLog.w("contacts_reload_failed")` |
| 7 | 索引尺的 `+1` 偏移是判据里唯一没被单测钉住的一条，也是最会漂的（以后在字母组前多插一个 item 就整体错位，且编译绿、单测绿，只有真机点 B 跳到 A 的最后一行） | 抽成纯函数 `ContactSection.groupStartIndices` + 单测（含 `leadingItems=2` 的反例） |
| 8 | 重音拉丁（`Émile`）掉进 `#`——iOS 的 `pinyinForName:` 起手就 `kCFStringTransformStripDiacritics`；`isHan` 也只认基本区 | 先剥重音再判拉丁；`isHan` 补上扩展 A 与兼容汉字 |

**顺带删掉一段死代码**：组内排序原本还有一档 `.thenBy(CASE_INSENSITIVE_ORDER)`（照抄 iOS）。
变异验证发现删掉它**没有任何测试变红**——查证后确认它**永远走不到**：iOS 那侧的排序键是一串
小写拼音，"bob"/"Bob" 会撞成同一个键；本端的 `CollationKey` 是 TERTIARY 强度，本来就区分大小写
（实测 `key(bob) != key(Bob)`），而真撞上时 `CASE_INSENSITIVE_ORDER` 恰好也回 0。
**这正是「照抄代码形状而不是不变式」的样子**——删了，测试改成钉实际次序（变异验证过会红）。

**已知差异 / 欠账**（不是 bug，但别当成 ✅）：
- **CJK 扩展 B 及以后**（代理对）仍归 `#`：`sectionKeyOf` 取的 `firstOrNull()` 只是高代理项。
- **选好友页没跟**：iOS 的 `IMContactSectionIndex.h` 明写「通讯录页与选好友页共用」，
  本端 `CreateGroupScreen` 仍是平铺。新抽的 `ContactSection` 是数据层，复用零成本，欠的是接。
- **分组仍在组合期主线程算**：优化后桌面 JVM 14ms、真机 2013 人滑动实测不掉帧，
  故没有挪去后台线程（挪了要先解掉上面那条 `Collator` 线程安全，且首帧会闪一下空列表）。

**删除好友为什么加二次确认**：删好友不可撤销，而左滑 + 点一下只有两个手势。
iOS 那侧是唯一没有确认的——im-web 的删除好友（资料卡「更多」）是有二次确认的，
本端的资料页删好友也一直有。三处里两处有确认，缺的那一处更像是 iOS 的疏漏而不是刻意。

### 4.6 群 @提及（M4-8，2026-09-09）

iOS：`Modules/Chat/IMChatViewController+Mention.m` + `IMMentionPickerViewController`（内联形态）
+ `IMChatMessageLogic.m` 的三个纯函数 + `IMBubbleCell.attributedContent:...spans:`。
Web：`src/mention.ts` + `Composer.tsx` + `components/messageText.tsx`。
协议见 `IMServer/docs/PROTOCOL.md` §4.1。

| 判据 | iOS | Android | 判定 |
|---|---|---|---|
| 偏移单位是 **UTF-16 码元**（对应 TG `messageEntityMentionName`） | `NSString` 索引 | Kotlin `String` 索引，同源零换算 | ✅ |
| 片段**覆盖整个 token（含前导 `@`）**，`text[offset]` 必是 `@` | 有 | 同 | ✅ |
| token 边界：后面紧跟空白或结尾；**长名优先** | `IMChatTextContainsMentionToken` | 同（`Mention.containsToken`） | ✅ |
| **有片段走片段、没有才回落按昵称扫文本** | `attributedContent:...spans:` | 同（`chatBodyText`） | ✅ |
| 片段与本地文本对不上 → **逐段丢弃**，一段不剩才降级 | `IMChatValidMentionSpans` | 同（`Mention.validSpans`） | ✅ |
| 发送时按**文本现状**复核收件人（删掉 token 就不再 @ 他） | `resolvedMentionsInText:` | 同（`MentionComposer.resolve`） | ✅ |
| 同名两人**都**收到提醒，而片段只链其中一个 | 有 | 同（并且**待发行单独存 `mentions`**，不从片段反推——反推会让重连补发后重名那位悄悄收不到） | ✅ |
| 「所有人」在片段里**覆盖**同名成员（空 uid 只在 `mention_all` 时合法） | 有 | 同 | ✅ |
| `@所有人` **仅群主/管理员**出入口（越权 300204） | 有 | 同（`Mention.canMentionAll` + `GroupInfo.myRole`） | ✅ |
| `@所有人` 只高亮**不可点** | uid 为空不挂点击 | 同 | ✅ |
| 面板：输入栏**上方内联**，不弹 sheet、**不抢键盘** | child VC 贴 replyBar.top | 同（`Composer` 的 `above` 槽） | ✅ |
| 面板候选**走服务端 `?q=` 分页**，不在本地成员表里过滤 | 有 | 同——超级群不下发成员表，本地过滤在那里恒空 | ✅ |
| 点气泡里的 `@某人` → 进他的资料页 | TextKit 反查 | `LinkAnnotation.Clickable`（**不是** `ClickableText`：那个吃掉 tap，气泡长按菜单会跟着失灵，真机验过长按仍在） | 🟢 手段不同 |
| 半角 `@` 与全角 `＠` 都触发；回填一律半角 | 有 | 同 | ✅ |
| 「@我的消息」聚合（`GET …/mentions`） | **没有** | 没有 | ⬜ 三端都欠 |

**真机验过**（模拟器 / 群「1001创建测试群」）：打 `@` 弹面板且键盘不收 → 键入 `3472` 实时过滤到一人
→ 选中回填 `@用户3472 ` 并关面板 → 发出后服务端库里
`mentions=["1010147977"]`、`mention_spans=[{"offset":0,"length":7,...}]`（`@用户3472` 正好 7 个
UTF-16 码元，且未被服务端的安全校验丢弃）→ 气泡里 `@用户3472` 蓝色、`kaihui` 常规色
→ 点它进了对方资料页 → **长按同一个气泡菜单照常弹出**。
修完下面那 6 条后又验了两条：**整条正文就是一段提及**（`@用户4836`，服务端 `conv_seq 64`）
现在高亮且点得动；群主打 `bob@gmail.com` 面板不再冒出来。

**`/code-review` 打回 6 条，全修了**（另外顺带补了一条 iOS 有而本端漏的）：

| # | 问题 | 修法 |
|---|---|---|
| 1 | **整条正文就是一段提及时不高亮也点不动**：快速通道写的是 `segs.size <= 1`，而 `@小明`（选完人直接发）也只有一段。真机那次验的是「@用户3472 kaihui」，两段，正好绕过了这个洞 | 判据改成「既没有提及、也没有命中」才走快速通道 |
| 2 | **谁收到提醒与片段会给出不同答案**：`resolveMentions` 逐个候选独立跑 `containsToken`，片段那边却长名优先。群里有「Li」和「Li Ming」时，`@Li Ming 开会` 会给 Li 发一条**穿透免打扰的错误强提醒** | 两者共用同一个按位置扫描的内核 `scanHits`；命中的名字再映射回**所有**同名 uid（「同名两人都收到」那条不变式不受影响） |
| 3 | **面板卡住**：`showsMentionAllRow` 少了「过滤词为空」这一档，群主打 `bob@gmail.com` 时成员搜不到、却因为有权限而恒真，240dp 的面板盖住消息列表 | 对齐 iOS 的 `showsMentionAllRow`（**有权限 且 过滤词为空**） |
| 4 | **每个字符一次 `GET …/members?q=`**：`@zhangsan` 打完发 9 次，而这条路正是为超级群准备的 | 去抖 300ms（与 iOS `searchRemoteMembers` 同值） |
| 5 | **「正在输入」回归**：`input` 从 `String` 改成 `TextFieldValue` 后，光标移动也会走 `onInputChange`，对端看到一次凭空的「正在输入…」 | 只在正文真的变了时才上报 |
| 6 | **双 `@`**：`GroupMember.displayName` 没昵称时回落成 `@username`，直接拼成 `@@bob`；更糟的是候选表存 `@bob`、文本里是 `@bob`，`containsToken` 找 `@@bob` 找不到，**这条提及会静默丢失** | 新增 `Mention.tokenLabel` 归一化一次，候选表与文本用同一个标签 |
| +1 | **候选里有我自己**（reviewer 没提，读 iOS 时发现的）：iOS 两处入口都剔了 `![m.userID isEqualToString:me]` | 同样剔除 |

**其中 2、6 是三端共有的**——iOS 的 `resolvedMentionsInText:` 同样逐个候选判定、
`pickMentionInsert:` 同样直接拼 `displayName`，im-web 的 `resolveMentions` 亦然。
本端改对了，**iOS/Web 是欠账**：这两条属于「谁收到强提醒」这一类不变式，
三端给出不同答案时用户看到的是"有人莫名被 @ 了"或"@ 了却没人收到"，
按 `SYMMETRY.md` 该拉齐（要一致的是不变式，不是代码形状）。

**没验**：普通成员看不到「@所有人」那一行（要第二个账号；判据有单测且取自服务端 `my_role`）；
超级群里老消息不高亮那条降级——库里 `conv_seq 57` 那条 `@用户4807 上的方式分` 正好是
**有 `mentions` 无 `mention_spans`** 的老数据，可以拿它验回落那条路。

### 4.7 消息多选（M4-3 的另一半，2026-09-09）

iOS：`Modules/Chat/IMChatSelectionState.{h,m}` + `IMChatViewController+Selection.m`。
Web：`src/messageContent.ts`（`selectableInMultiSelect` / `SELECT_MAX` / 批量举报谓词）+ `src/selection.ts`。

| 判据 | iOS | Android | 判定 |
|---|---|---|---|
| **勾选态按 `conv_seq` 记，不按行号/表格状态** | `selectedModels`（key=conv_seq） | 同（`Map<Long, MessageEntity>`） | ✅ |
| **连消息实体一起存**（勾过的会被窗口裁出内存） | 存 model 不只存 seq | 同 | ✅ |
| 可勾判据：`convSeq>0 && 未撤回 && 非系统消息` | `isSelectableMessage:` | 同（`ChatSelection.selectable`） | ✅ |
| 一次上限 100，**一道闸管住转发/收藏/举报** | `kIMSelectionMaxCount` | 同（`Forward.MAX_SELECTION`，两处同源有单测钉住） | ✅ |
| 超限**吐司说明**，不静默吞掉点击 | `allowSelectingMore:` | 同（`toggle` 回 null → 调用方吐司） | ✅ |
| 取消勾选**永远允许**（选满了也能改） | 有 | 同 | ✅ |
| 导出按 `conv_seq` **升序**（会话时序） | `IMChatSelectedMessages()` 纯函数 | 同（`ChatSelection.ordered`） | ✅ |
| 进多选**默认勾上触发的那条** | `enterSelectionWithMessage:` | 同 | ✅ |
| 标题「已选择 N 条」/「选择消息」 | 有 | 同（`ChatSelection.titleOf`） | ✅ |
| 0 选中时动作钮**置灰禁用**（不弹「请先选择」） | 有 | 同 | ✅ |
| 多选期间**隐藏输入栏**、底部换动作栏 | 有 | 同（`Composer` 整个不画） | ✅ |
| 不可勾的行**不画勾选圈** | `canEditRowAtIndexPath` 回 NO | 同（画等宽占位保持左缘对齐） | ✅ |
| 转发前滤掉转不出去的，**少发几条要如实说** | 先数一次再发 | 同（`forwardPick` 回提示） | ✅ |
| 返回键分层：多选 → 搜索 → 离开会话 | 导航栏「取消」 | 同 + 返回键（Android 特有） | 🟢 本端多一条 |
| 动作栏格数与顺序 | 转发 / 举报 / 收藏 / 删除 **4 格**，36pt 圆钮等距 | 同（`ChatSelectionUi.kt` 的 `SelectionBar`，2026-09-10） | ✅ |
| 批量删除 | 只给「仅为我删除」 | 同（走 REST `/messages/hide`，带二次确认） | ✅ |
| 批量举报：非空 + 不含我 + 同一个人才可点；**不可点时置灰不隐藏**，点灰钮说原因 | `reportableSenderForMessages:` / `reportHintTapped:` | 同（`SelectionActions.reportableSender` / `reportBlockedHint`，一次 POST 带 `target_seqs`；成功退出多选、失败留在多选） | ✅ 2026-09-10 |
| 转发先问「逐条转发 / 合并转发」 | 有 | 同（`ChatSelectionActions.kt`） | ✅ 2026-09-10 |
| 逐条转发跳过失效媒体并如实说；同一相册选 ≥2 张重新分组 | 有 | 同（`SelectionActions.isExpiredMedia` / `regroupAlbums`，每个目标会话一个新 `alb-` id） | ✅ 2026-09-10 |
| 单条转发（长按菜单 / 查看器 / 详情归档）撞上失效媒体**直接拦下**，提示「该视频已失效，无法转发」 | `presentForwardPickerForMessage:` 一处拦三入口 | 同（`SelectionActionsController.forwardOne` + `ArchiveActionsHost`，同一判据；code-reviewer 2026-09-10 查出单条入口漏拦后补） | ✅ 2026-09-10 |
| 合并转发（多条 → 一张卡片）：名字只用公开名、`u` 是匿名序号 s1/s2 | `mergedForwardJSONForMessages:` | 同（`SelectionActions.encodeRecord`，隐私几条有单测钉住） | ✅ 2026-09-10 |
| 批量收藏（跳过撤回/系统/空内容，点下即退出多选） | 有 | 同（`FavoriteApi` + `SelectionActions.favoritable`）；**收藏列表页还没有** | 🟡 只有「加」 |
| 相册宫格逐格勾选 | 有（整组全选 + 逐格） | **没有**——宫格整体不参与多选 | ⬜ 欠账 |

**iOS 那条用线上 bug 换来的教训，本端从一开始就照抄了**（`IMChatSelectionState.h` 的类注释）：
勾选态原先记在 `UITableView` 的行选中里，向上翻页时 `reloadData` 清空选中、`prepend` 又让行下标
整体平移，于是「勾两条 → 上滚拉历史 → 再勾一条，前两条静默消失」。本端列表同样是窗口化的
（尾窗 200 / 锚点窗前后各 100），所以判据层的函数**签名里拿不到窗口、行号或任何列表状态**
——拿不到就没法退回去按行号记。

**⚠️ 未真机验证**：本轮只跑到单测与编译（511 例绿、体量闸过）。真机那一段没做完——
模拟器在宿主负载 25 时反复 ANR，最后连 uiautomator 的 accessibility 桥都返回 `null root node`。
按本仓规矩（滚动/翻页/动画必须真机看），**这一块在真机验过之前不算完**，要验的至少有：
长按进多选、勾选圈只出现在可勾的行、上翻拉历史后勾选不丢（那正是 iOS 踩过的那条）、
超限吐司、批量转发与批量删除、返回键分层。

---

## 4.8 聊天页显示设计稿（2026-09-10，**逐消息类型的唯一尺寸来源**）

> 📐 **[CHAT_UI_SKETCH.html](../../IMServer/docs/design/sketches/CHAT_UI_SKETCH.html)**
> —— 从 iOS 反推的聊天页设计稿：按 pt 1:1 画出每种消息类型的样子，逐项标字号/色值/间距，
> 每个数字都带 iOS 出处符号。**Android 复刻照这份，不要再"看着差不多"。**

### 为什么补这一份

用户 2026-09-10 手测报回 17 条差异。复盘下来根因是**四条**，前两条是要害：

1. **只有容器级基准，没有逐消息类型的规格。** `UI_SPEC.md` 定的是气泡最大宽、圆角、内边距、
   头像列、日期胶囊、输入栏——**全是外壳**。而 17 条里有 13 条落在外壳**里面**：宫格首格、
   引用条竖线、连续消息的名字、角色标签、图文内边距、卡片可点性……这些从来没有一个地方写过基准，
   于是每个人写的时候都只能"看着差不多"。**没有基准，就没有"不一致"可言。**
2. **验证方式是「看着对不对」，不是「和 iOS 比对不对」。** 此前的真机验证全是功能性的
   （能不能发出去、点了跳不跳、崩不崩）。**缺失的东西不会在截图里报错**：少一条竖线、
   宫格首格大一圈、连发三条显示三次名字，页面看着都干净自洽。§5 在 2026-09-08 已经写过一次
   同样的教训（"三整块本端根本没画"），但当时的结论只停在"页面结构"，没有下沉到消息类型。
3. **交互细节从来没进过任何清单。** 初始定位、键盘顶起、发送后回底、点空白收键盘、返回保持位置
   ——这些是"聊天页之所以是聊天页"的东西，iOS 散在 `+Scroll.m`/`+Position.m`/`+Compose.m` 里，
   本端是遇到一个补一个。没清单就没人知道少了什么。
4. **按「功能行」推进，粒度太粗。** `CLIENT_PARITY` 里「caption 图说」是一行，勾了 🚧 就过去了；
   它底下"图片齐边、圆角只圆上角、时间胶囊不移位"三件事没有各自的位置。
   粒度粗到一定程度，进度表就会**掩盖**差异。

### 十七条手测差异

| # | 现象 | 设计稿 | iOS 判据一句话 | 状态 |
|---|---|---|---|---|
| 1 | 多选勾选跨翻页不丢 | §9.9 | — | ✅ 已验正常 |
| 2 | 进群先显历史再滑到底 | §9.1 | 数据 init 同步落地 + 首次布局即定位 + `animated:NO` 迭代精确贴底 | ✅ 2026-09-10（两路数据都读到前列表留空；组合期 `requestScrollToItem` + ≤6 轮贴底） |
| 3 | 最后气泡与输入栏无间隔 | §1 | `contentInset.bottom` 恒 0，间距是 cell 自带的 **3pt** | ✅ 2026-09-10（令牌 `chatListPaddingBottom`，实测 8px≈3dp） |
| 4 | 键盘不顶起列表 | §9.3 | 列表**高度被压缩**（非加 inset）；变化前判 `wasNearBottom`，是则变化后重新贴底 | ✅ 2026-09-10（`KeepBottomOnResize`；键盘/➕面板/搜索栏同一入口） |
| 5 | 发送后不回到最新 | §9.4 | 强制瞬时贴底 + **0.5s ↓N 抑制窗口**；看历史时先换回尾窗 | ✅ 2026-09-10（挂在出箱回显上；抑制窗 1s，见下「欠账」） |
| 6 | 点空白不收键盘 | §9.5 | tap 挂 tableView、`cancelsTouchesInView=NO`；**先定位命中再收键盘**（顺序是判据） | ✅ 2026-09-10（面板开着只收面板、这一下不传给气泡） |
| 7 | 宫格首格大小不一致 + 闪跳 | §4 §3.1 | 不是"首格恒大"：**每行列数决定格宽**，1 列的行高固定 150；行高只由张数决定故不跳版 | 🔴 |
| 8 | 转发弹窗不可下滑/标题不居中/未过滤系统通知 | §9.8 | pageSheet 可下滑；标题**「转发到」**居中；剔除 `IMIsSystemUserID` 的单聊 | ✅ 2026-09-16 待真机（新组件 `IMCardSheet` 卡片式弹层：顶上留一截、遮罩、下拉 / 点遮罩 / 返回键关，列表在顶时继续下拉也跟手；另补 iOS 的默认单选确认 + 「多选」切换 + 搜索框；判据 `Forward.pickable`） |
| 9 | 群资料页底部多一个退群按钮 | §10 | 退群**只在「更多」里出现一次**，文案「退出群组」 | ✅ 2026-09-10（`GroupInfoScreen` 底部那组按钮删掉，只留「更多」里的） |
| 10 | 从详情返回后跳到最底部 | §9.2 | iOS **没有 save/restore**，靠一次性标志位「不去动它」 | ✅ 2026-09-10（详情页改为盖在常驻聊天页上，聊天页不再被销毁重建） |
| 11 | 转发没有合并转发选项 | §8.1 | 能力本身未做（`CLIENT_PARITY` M4-6 那行） | ✅ 2026-09-10（多选→「逐条 / 合并」，见 §4.7；长按单条转发不问，iOS 同） |
| 12 | 引用条两条竖线 | §2.1 | iOS 引用块**不是容器**，竖线是每行行首的 `▏` 字符，两行拼成**一条** | ✅ 2026-09-10（`QuoteBlock` 的 `QuoteLine` 逐行画 2dp 竖线，首行只圆上角、末行只圆下角，名字行与摘要行拼成一条） |
| 13 | 长按文件名无反应 | §5 §9.6 | 长按挂在**气泡根视图**上，UILabel 默认不吃触摸 | ✅ 2026-09-10（文件行与下载徽标改用 `passThroughTap`：只在抬手时消费，按下与长按照常落到气泡根；文件名改中间省略 `MiddleEllipsisText`） |
| 14 | 引用预览条样式差异 + 点击不跳 | §9.7 | 高 54、竖条宽 3、槽 36×36、点条**跳原消息**并高亮一闪 | ✅ 2026-09-10（`ReplyBar.kt`：竖条 3、槽 36、标题「回复 X」走 `ReplyNames.replyBarTitle`；点条走引用块同一条定位；下滑 24dp 或 ✕ 取消；0.2s 出入场。实高 52，差 iOS 2pt） |
| 15 | 连发多条每条都显名字 + 无角色标签 | §1.1 | **首条显名、末条显头像**；角色标签 11pt medium / 高 16 / 圆角 4 | ✅ 2026-09-10（`showsSenderName` + `SenderRun.badgeOf`：群成员表角色优先，超级群拿不到成员表时退回消息上的 `from_role`；名字口径 备注 > 群昵称 > 消息昵称；引用块里的名字不再退回原始 uid） |
| 16 | 图文图片距气泡左边有边距 | §3 | **缩略图本身就是气泡**，齐边；只有 caption 文字内缩 10 | ✅ 2026-09-10（图/视频与带图说的气泡宽度统一取 `rememberMediaDisplaySize`，媒体齐边，图说内缩 10） |
| 17 | 聊天记录卡片不可点 | §8.1 | 可点 → push 独立的「聊天记录」详情页 | ✅ 2026-09-10（`ChatRecordScreen` + `ChatRecordLayer`：可逐层嵌套、返回键逐层弹；图/视频进查看器且不带转发、名片进资料页、文件只对 http(s) 交浏览器；坏数据不下钻） |

### 第三轮手测九条（2026-09-10）

上一轮六组复测只有「回复条轻点跳到原消息」没过，同时报回下面九条。**全部只到单测 + 编译**，没上真机。

| # | 现象 | 做法 | 状态 |
|---|---|---|---|
| 1 | 回复条（输入框上方「正在回复」）轻点，键盘收起时不跳 | 根因在居中补偿：旧写法假设目标已被 `scrollToItem` 顶到视口顶端，列表尾部被夹住时照样往回滚半屏，把目标推出屏幕下沿。改为按它**此刻的真实偏移**算（`ChatScroll.centerDeltaPx`）；跳转后一小段时间里跟底与翻页补偿让路（`ChatScrollMarks.locatingUntil`） | ✅ 待真机 |
| 2 | 接收端宫格没和其他消息左对齐，发送者头像不显示 | 宫格行走和普通气泡同一条头像列 / 左缘（`ChatRowView` `AlbumBubble` `SenderHeader`） | ✅ 待真机 |
| 3 | 多选底栏只有转发 / 删除 | 四钮 + 逐条/合并转发 + 批量举报 + 批量收藏，逐条见 §4.7 | ✅ 待真机 |
| 4 | 发送端引用块不显被引用人名、文字没靠左 | 发送端引用块与接收端同一套名字行，文字左对齐（`Bubbles`） | ✅ 待真机 |
| 5–7 | 未下载的图片 / 视频 / 文件各状态与 iOS 不一致 | 文案与图标逐条照 iOS 收进 `data/DownloadLabels.kt`（`DownloadLabelsTest`），画的那半在 `ui/components/GateOverlays.kt`。**一处刻意差异**：本端暂停会丢半截文件（无续传），暂停显「已暂停」而非 iOS 的「已下 / 总」 | ✅ 待真机 |
| 8 | 未下载视频点空白也能打开；未下载文件露出类型图标 | 未就绪时只有下载钮可点；文件图标位在就绪前不画类型 logo（`MediaBubbles`） | ✅ 待真机 |
| 9 | 群资料页底部退出那组按钮多余 | 删掉，「更多」里已有 | ✅ |

### 顺带纠正三处「照着补反而错」的地方

- 相册的 **「+N」蒙层 iOS 没有实现**（上限靠发送端 `selectionLimit=9` 封顶），别照着补。
- 聊天记录卡的 **「共 N 条」iOS 不存在**，脚注是固定文案「聊天记录」。
- 群资料页那个带底部「退出群聊」按钮的 `IMGroupInfoViewController` 是**死代码**（全工程无人 push），
  别照着它复刻。

## 4.9 隐私与安全（2026-09-11，**未真机手测**）

iOS：`Modules/Me/IMPrivacySecurityViewController.m`（`buildGroups`）→ `Modules/Contacts/IMBlockedListViewController.m`
/ `Modules/Me/IMChangePasswordViewController.m`；设计 `../IMServer/docs/design/PRIVACY_SECURITY_DESIGN.md`。
Android：`ui/PrivacySecurityHost.kt` + 三个 `*Screen.kt`，判据在 `data/PrivacySecurity.kt` / `data/ChangePasswordRules.kt`。
**动手前抄下来的结构清单**：

- **容器页五组**：A（无组头）已屏蔽的用户（右值 = 人数，0 不显）/ 修改密码，组尾「已屏蔽的用户不能给你发消息，也看不到你的资料。」；
  B 账号保护：两步验证「关闭」/ 通行密钥「关闭」/ 邮箱登录；C 会话隐私：自动删除消息「关闭」；
  D 谁能看到：手机号码 / 上次上线 / 头像 / 个人简介 / 生日；E 数据：清除所有对话 / 导出我的数据。
  B–E 灰置：标题降一档、右值再降一档，**图标全彩、chevron 保留**，点了只提示开发中。
- **已屏蔽的用户**：顶部说明（空态时连说明一起藏）；行 = 44 头像 + 显示名 + @句柄；**左滑红色「取消屏蔽」、不二次确认**；
  **点行无动作**（设计稿写了进资料页，iOS 没做）；空态三层（72 禁止图标 / 20 半粗大标题 / 小字）；刷新失败保留旧内容。
- **修改密码**：旧密码一组 + 组尾（会下线其它设备）；新密码 + 确认一组；每框右侧眼睛；
  失败红字 + 对应框描红、**输入不清空**、一改动就清红字；小字「新密码至少 6 位，与旧密码不同。」；
  成功返回上一页 + 吐司「✓ 密码已修改，其它设备已下线」；**成功应答里轮换出的续期凭据必须就地替换**。

| 项 | iOS | Android | 判定 |
|---|---|---|---|
| 容器页分组 / 文案 / 占位右值 / 图标底色 | 如上 | 同（`PrivacySecurityTest` 逐行钉） | ✅ |
| 图标 | SF Symbols | lucide，与 Web `PrivacySecurityPanel` 同一套选择 | 🟢 图标库不同 |
| 占位行点击提示 | 「X（开发中）」 | 「「X」还没做」 | 🟢 沿用本端「我」页口径，本端内一致优先 |
| 占位行无障碍提示「即将上线」 | 有（`accessibilityHint`） | 无 | 🔴 本端无障碍统一补 |
| 取消屏蔽失败 | 系统 alert | 吐司 | 🟢 本端错误反馈一律吐司 |
| 黑名单首次就没拉到 | 静默（只剩说明） | 说明下显一行红字 | 🟢 空白不说原因更差 |
| 改密码按钮何时亮 | 本地校验**全过**才亮 | 三个框**都填了**就亮，点了再说原因（Web 口径） | 🟢 iOS 那几句原因提示永远轮不到显示 |
| 新密码超 72 字节 | 不挡，服务端回 100001 + 英文文案 | 本地挡 | 🟢 |
| 「参数错」业务码 | 硬编码 **100002**（限流码）→「密码强度不足」分支走不到 | 按 **100001** | 🟢 iOS 的 bug，**别照抄**（iOS 未改） |
| 会话过期 / 未知错误 | 吐司 | 同 | ✅ |
| 提交途中返回上一页 | 回调仍把新凭据写进单例 | 挂 `client.scope` + `NonCancellable`，同样接得住 | ✅ |

---

---

## 4.10 「消息」Tab 根页（2026-09-15，**未真机手测**）

iOS：`Modules/Conversation/IMConversationListViewController.m`（`plusTapped:` / `emptyLabel` / `refreshListIndicators`）、
`App/IMMainTabBarController.m`。标题栏与右上角动作见 §4 那两行。

| 项 | iOS | Android | 判定 |
|---|---|---|---|
| 页面标题与 Tab 名 | 「消息」（2026-09-15 由「会话」改） | 「消息」 | ✅ |
| 冷启动 / 登录时的空态 | 首登缓存为空时同样会先闪「还没有会话」，同日一并修：服务端拉成过一次（`serverListed`）才画 | 已修：`data/ConversationListPhase.kt`，本地库初值 null；本地空 + 服务端也说没有才画。多一条「服务端说有、库还没回写」也不画（Room 失效通知异步） | ✅ 待真机 |
| 空态文案 | 「还没有会话，点右上角 ＋ 新建群聊或添加好友」 | 同（此前「在另一个端给这个账号发条消息试试」） | ✅ |
| 「消息」Tab 未读蓝点 | 2026-09-15 前**没有**；现 `IMTabUnreadCount`，`badgeValue` 空串画点，离屏靠常驻订阅节流刷新 | 一直有；口径改成三端同一个 `data/TabUnread.kt`（此前 SQL 漏了免打扰里被 @ 的那条） | ✅ |
| ＋ 菜单「扫一扫」 | 全屏取景 + 相册识别 | **没做**（读码半边未接），点了提示 | 🔴 |
| 从 ＋「新建群聊」建成之后 | 回会话列表并直接进新群（`startNewGroup`） | 同（`ChatsHost` 的 `onCreated`） | ✅ |

---

## 5. 为什么会漂这么远（2026-09-08 复盘）

用户问：「不是严格按照 iOS UI 来参照吗，为什么差这么多？」——这一节是答案，
写在这里是因为**下一页仍会这样漂**，除非改的是做法而不是这几页。

**四个成因，按影响从大到小：**

1. **把 iOS 当"参考"读，没当"规格"读。** 每一页我都是从功能出发实现的
   （"群详情要能看成员、能退群、能进管理"），写完再瞄一眼 iOS 对个大概。
   功能对得上，**结构对不上**——而用户看到的正是结构。这次少掉的三样
   （详情页头部操作排、通讯录四个入口、群头像入口）全是"功能清单里没有、
   iOS 页面上有"的东西。
2. **三端只有数值基准，没有结构基准。** `UI_SPEC.md` 管尺寸/字号/时间格式，
   所以这些**从没出过大错**；而"这一页有哪几块、每块有哪几行、哪些人看得见哪一条"
   没有任何一处写着，于是每一页各漂各的。**有基准的地方不漂，没基准的地方全漂**——
   这不是巧合。
3. **自查的对照物是"我记得的 iOS"，不是 iOS。** 我确实每次都真机截图核对，
   但对照的是脑子里的印象。**缺失的东西不会在截图里报错**：一整排按钮不存在时，
   页面看起来是干净的、自洽的、没有任何异常。
4. **对称登记表不覆盖 UI 结构。** `SYMMETRY.md` 记的是逻辑不变式（排序口径、
   窗口边界、设备展示口径），UI 结构不在其中，所以 `pre-commit` 一次也没提醒过。

**改法（已落地，不是口号）：**

- **动手前先抄结构清单**：新做/大改一页，**先**在本表里把 iOS 那一页的分区、行、
  可见性判据抄成一张表，**再**写代码，写完逐行打勾。本次的
  `data/DetailActions.kt` 就是这个形态。
- **可见性判据一律抽成纯函数 + 单测**（"谁看得见哪个按钮"）。这是最容易漏、
  也最容易验的一类：`DetailActionsTest` 的每一条都对应 iOS `actionPillSpecs`
  里一句写了理由的注释，抄错一条测试当场红。
- **已把本端 UI 页面登进 `../IMServer/docs/SYMMETRY.md`**，命中即在提交期提醒
  「iOS 对应页有没有本端没画的块」。
- 🔴 条目**保留不删**（见维护规则）：这份表现在既是差异登记，也是待办队列。

---

## 6. 维护规则

- **改动只要让本端与 iOS 的观感/结构产生差别，就在这里记一条**，并选一个判定标记。
- 🔴 补完后改 ✅ 并保留那一行（记录"曾经差过"比删掉有用——同一处容易反复）。
- 🟡 / 🟢 的条目**带着理由**，不写理由的条目等于没记：下一个人只会把它当欠账再做一遍。
- 引用 iOS 代码写**文件路径 + 符号名，不写行号**（重构会改烂，见 `CLAUDE.md`）。
