#!/usr/bin/env bash
#
# 移动端冒烟（T3，../IMServer/docs/design/E2E_REGRESSION_DESIGN.md §6）：装上新包后在已连接的真机 / 模拟器上把核心链路走一遍。
#   ① 服务端造数据：固定对端 smokepeer 给设备账号发一条带时间戳的 ping（../IMServer/scripts/smoke/mobile-smoke.mjs）
#   ② 装 debug 包，scripts/ui_smoke_driver.py 经 adb 操作真 App（按无障碍树里的文字找元素）：收到 ping → 点进去 → 发 pong
#      （不用 instrumentation：Compose 测试规则会换掉 App 的协程调度，真 App 有处布局会因此崩；
#       也不用 connectedAndroidTest：它跑完会卸载 App，真机上的登录态和服务器地址会被一起清掉）
#   ③ 回到服务端核对：对端真的收到了 pong
# 设备上的 App 须已登录 SMOKE_USER 并连着开发后端；本机脚本访问后端用 SMOKE_API（默认 http://localhost:8080）。
#
# 用法：
#   ./scripts/ui-smoke.sh                       # 设备账号默认 user1001
#   SMOKE_USER=user1002 ./scripts/ui-smoke.sh
#   ANDROID_SERIAL=<序列号> ./scripts/ui-smoke.sh # 连了多台设备时指定
set -euo pipefail
cd "$(dirname "$0")/.."

if [[ "${JAVA_HOME:-}" != /* ]] || [ ! -x "${JAVA_HOME:-/nonexistent}/bin/java" ]; then
    JAVA_HOME="$(/usr/libexec/java_home -v 17)"; export JAVA_HOME
fi
SMOKE_USER="${SMOKE_USER:-user1001}"
SEED="../IMServer/scripts/smoke/mobile-smoke.mjs"
SHOTS="build/ui-smoke"

adb get-state >/dev/null 2>&1 || { echo "✗ 没有连接的设备（adb devices）"; exit 1; }

echo "[1/4] 装 debug 包"
./gradlew -q installDebug

echo "[2/4] 造数据（对端 smokepeer → ${SMOKE_USER}）"
SEEDED="$(node "$SEED" seed "$SMOKE_USER")"
field() { printf '%s' "$SEEDED" | python3 -c "import json,sys;print(json.load(sys.stdin)['$1'])"; }
PEER="$(field peer)"; PING="$(field ping)"; PONG="$(field pong)"
echo "  ping：${PING}"

echo "[3/4] 设备上操作 App（截图在 ${SHOTS}/）"
rm -rf "$SHOTS"; mkdir -p "$SHOTS"
python3 scripts/ui_smoke_driver.py "$PEER" "$PING" "$PONG" "$SHOTS"

echo "[4/4] 服务端核对"
node "$SEED" verify "$SMOKE_USER" "$PONG"
echo "✅ Android 冒烟通过"
