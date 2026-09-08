# Android ↔ iOS 界面差异登记

> **这份表只记「本端与 iOS 长得不一样的地方」**，且每一条都要写清楚**为什么**：
> 是欠账（该补）、是平台限制（补不了）、还是刻意差异（不要来回改）。
>
> 尺寸/字号/时间格式这类**三端共用的数值基准**不在这里，在
> [`../IMServer/docs/UI_SPEC.md`](../../IMServer/docs/UI_SPEC.md)；逐功能×端的完成度在
> [`../IMServer/docs/CLIENT_PARITY.md`](../../IMServer/docs/CLIENT_PARITY.md)。
> **这份表只管"观感与结构"**。
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
| 单聊 / 群聊是否同一页 | 同一个 VC，按 `isGroup` 切分区 | **两个 Screen**（`ChatDetailScreen` / `GroupInfoScreen`） | 🟢 刻意差异 |
| 归档的位置 | **内联页签**（详情页内切 tab） | 2026-09-08 已改为内联页签 | ✅ 已对齐 |
| 归档的数据来源 | **本地已加载的消息**（`IMChatDetailTabs message:matchesKind:`） | **服务端接口** `GET /conversations/{id}/media` | 🟢 刻意差异 |
| 「链接」页签 | 有（本地扫文本，`IMFirstURLInText`） | 有（同样本地扫文本——服务端不覆盖这一格） | ✅ 已对齐 |
| 「名片」页签 | 有 | 🔴 欠账 |
| 大头像头部 | 有（水滴） | 见 §1 |

**为什么不合并成一页**：iOS 合并是有代价的——`IMChatDetailViewController.m` 光主文件就 1438 行，
外加四个分类共 6000 行，`if (self.isGroup)` 遍布其中。它合并得起，是因为两边共用了
**页签那一大套**（复用价值高）。本端的群详情还带着成员分页/群管理入口等群专有物，
合并只会得到一个塞满 `if (isGroup)` 的大文件。**页签那一层已经共用**
（`ConvMediaHost`），共用的是有复用价值的那部分，这是本表的判断。

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
| 行图标 | 已补（Lucide 近义图标，非 SF Symbol） | 🟢 刻意差异 |
| 每节 footer 说明 | 已补 | ✅ |
| 黑名单 / 管理员 / 转让群组 三节 | 已补 | ✅ |

**图标为什么不逐一对上**：SF Symbol 是 Apple 私有字体，Android 上不存在。本端用 Lucide 里
语义最近的一枚（`lock.shield`→`ShieldCheck`、`mic.slash`→`MicOff`、`nosign`→`Ban`、
`crown.fill`→`Crown`…）。**要对齐的是"每行都有一个能一眼认出的图标"这件事**，不是同一张图。

---

## 4. 标题栏

见 [`../IMServer/docs/UI_SPEC.md`](../../IMServer/docs/UI_SPEC.md) §4.5（三端共用基准，不在本表重复）。
本端额外一条：

| 项 | iOS | Android | 判定 |
|---|---|---|---|
| 液态玻璃标题栏 + 滚动形变 | `IMLiquidNavigationBar`（iOS 26 原生观感） | 平面标题栏 | 🟢 刻意差异 |

**为什么不做液态玻璃**：那是 iOS 26 的系统级材质（含实时折射与镜面高光）。
Compose 里手搓只能得到"半透明 + 模糊"的形似神不似版本，比干净的平面栏更糟。
Android 有自己的 Material 语汇，**平台观感不同不是欠账**。

---

## 5. 维护规则

- **改动只要让本端与 iOS 的观感/结构产生差别，就在这里记一条**，并选一个判定标记。
- 🔴 补完后改 ✅ 并保留那一行（记录"曾经差过"比删掉有用——同一处容易反复）。
- 🟡 / 🟢 的条目**带着理由**，不写理由的条目等于没记：下一个人只会把它当欠账再做一遍。
- 引用 iOS 代码写**文件路径 + 符号名，不写行号**（重构会改烂，见 `CLAUDE.md`）。
