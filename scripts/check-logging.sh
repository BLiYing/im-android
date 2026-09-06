#!/usr/bin/env bash
# check-logging.sh —— 日志红线自查（../IMServer/docs/LOGGING.md §7.1）。
#
# 业务代码禁止直接用 android.util.Log / println / System.out，一律走 sdk/logging/IMLog.kt。
# IMLog.kt 自身是唯一落点，故排除。
#
# 为什么要有这道机械检查：Go 后端曾累计 54 处违规无人察觉，因为有兼容桥接兜底、
# "看起来没坏"。本端不加桥接，再加一道 grep，别等攒到 54 处。
set -u
cd "$(dirname "$0")/.." || exit 2

hits=$(grep -rnE "android\.util\.Log|(^|[^.[:alnum:]_])println\(|System\.out" \
        app/src/main/kotlin --include="*.kt" 2>/dev/null | grep -v "sdk/logging/IMLog.kt" || true)

if [ -n "$hits" ]; then
  echo "✗ 日志红线：发现绕过 IMLog 的直接打印（应为 0 条）"
  echo "$hits" | sed 's/^/  /'
  echo ""
  echo "  改法：private val log = IMLog.tag(\"IM.Xxx\") 然后 log.i(\"event_name\", \"k\" to v)"
  echo "  规则见 ../IMServer/docs/LOGGING.md §7.1"
  exit 1
fi
echo "✓ 日志红线：0 处直接打印"
exit 0
