#!/usr/bin/env bash
# check-file-size.sh —— 防巨文件体检：单文件行数超阈值即失败（im-android / Kotlin + Compose）。
#
#   用法（仓库根目录）：  ./scripts/check-file-size.sh
#   接入：pre-commit / scripts/test.sh 第 1 步；退出码 1 = 有文件超预算。
#
# 为什么是 600 而不是别的数：与 im-web 同阈值。Compose 的 @Composable 函数与 React 组件同构
# ——都是「一个文件一块界面」，膨胀方式也一样（状态、回调、子视图往一个文件里堆）。
# iOS(1500) / Go(1200) 阈值更高是因为 ObjC 的 .m 与 Go 的包级文件天然更长。
#
# 超标的正确处理是**拆分**，不是放宽阈值（详见 CODING_STYLE.md §7）：
#   ① 有自己状态/生命周期的一簇逻辑 → ViewModel 或 `remember*` 状态持有者
#   ② 一整块 UI（面板/弹窗/查看器）  → 独立 @Composable 文件，数据与动作经参数注入
#   ③ 纯逻辑（无 Compose、无 Android）→ 普通 .kt 纯函数 + 单测
set -u

MAX_LINES=${MAX_LINES:-600}
WARN_RATIO=${WARN_RATIO:-80}

# 历史欠账（已超 MAX_LINES、待拆分）。值 = 当前行数 + 少量余量，**只准降不准升**。
# 目前为空——新仓，别让它长出第一条。
grandfather_limit() {
  case "$1" in
    *) echo "" ;;
  esac
}

cd "$(dirname "$0")/.." || { echo "无法定位仓库根目录"; exit 2; }

fail=0
warn=0

echo "== 单文件行数体检（默认上限 ${MAX_LINES}；不含 *Test.kt）=="
while IFS= read -r f; do
  lines=$(wc -l < "$f" | tr -d ' ')
  gf=$(grandfather_limit "$f")
  if [ -n "$gf" ]; then limit=$gf; tag=" [欠账·待拆]"; else limit=$MAX_LINES; tag=""; fi
  if [ "$lines" -gt "$limit" ]; then
    echo "  ✗ FAIL  ${f}  ${lines} 行 > ${limit}${tag}"
    fail=1
  else
    warn_at=$(( limit * WARN_RATIO / 100 ))
    if [ "$lines" -ge "$warn_at" ]; then
      echo "  ⚠ WARN  ${f}  ${lines} 行（≥ ${warn_at}，接近上限 ${limit}）${tag}"
      warn=1
    fi
  fi
done < <(find app/src/main -type f -name "*.kt" | sort)

echo ""
if [ "$fail" -ne 0 ]; then
  echo "结果：✗ 有文件超预算——请拆分（抽状态持有者 / 抽 @Composable / 抽纯函数 + 单测），见 CODING_STYLE.md §7，别放宽阈值。"
  exit 1
fi
[ "$warn" -ne 0 ] && echo "结果：✓ 通过（有 WARN——尽早规划拆分，勿等触顶）。" || echo "结果：✓ 全部通过。"
exit 0
