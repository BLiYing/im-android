#!/usr/bin/env bash
# test.sh —— 本仓**唯一**测试入口（对齐 IMServer / IMProgram 的 ./scripts/test.sh）。
#
#   ./scripts/test.sh              # 全量：体量门禁 + 日志红线 + 编译 + 单测
#   BUILD_ONLY=1 ./scripts/test.sh # 只编译，不跑单测
#   ONLY=EnvelopeTest ./scripts/test.sh
#
# 声明「完成」前必须跑绿这个，并在回复里贴输出。别再手拼 gradlew 命令行。
set -u

cd "$(dirname "$0")/.." || exit 2

# --- JAVA_HOME 兜底 ------------------------------------------------------------
# 本机（2026-09-07）的 JAVA_HOME 被设成了 Homebrew 的未替换占位符 `@@HOMEBREW_JAVA@@`，
# 裸跑 gradle 会直接报 "JAVA_HOME is set to an invalid directory"。
# 这里自愈：JAVA_HOME 无效就按优先级挑一个真实 JDK 17+。
if [ ! -x "${JAVA_HOME:-/nonexistent}/bin/java" ]; then
  for candidate in \
      "$(/usr/libexec/java_home -v 17 2>/dev/null)" \
      "/usr/local/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home" \
      "/Applications/Android Studio.app/Contents/jbr/Contents/Home" ; do
    if [ -n "$candidate" ] && [ -x "$candidate/bin/java" ]; then
      export JAVA_HOME="$candidate"
      echo "ℹ JAVA_HOME 无效，已自动切到：$JAVA_HOME"
      break
    fi
  done
fi
if [ ! -x "${JAVA_HOME:-/nonexistent}/bin/java" ]; then
  echo "✗ 找不到可用的 JDK 17+。装一个（brew install openjdk@17）或手动 export JAVA_HOME。"
  exit 2
fi

fail=0
step() { echo ""; echo "===== $* ====="; }

# --- 1. 体量门禁（先跑，最快，失败即停止浪费编译时间）---
step "1/4 体量门禁"
./scripts/check-file-size.sh || fail=1

# --- 2. 日志红线 ---
step "2/4 日志红线"
./scripts/check-logging.sh || fail=1

[ "$fail" -ne 0 ] && { echo ""; echo "✗ 静态门禁未过，未进入编译。"; exit 1; }

# --- 3. 编译 ---
step "3/4 编译 debug"
./gradlew :app:assembleDebug --console=plain || { echo "✗ 编译失败"; exit 1; }

# --- 4. 单测 ---
if [ "${BUILD_ONLY:-0}" = "1" ]; then
  echo ""
  echo "✓ BUILD_ONLY=1：已跳过单测。"
  exit 0
fi

step "4/4 单测"
# **不写 :app:** —— 裸任务名会在所有模块上跑。2026-09-07 拆出 :media-picker 后
# 这里若还写死 :app:，模块里的 6 个测试类会一条都不跑，而输出照样是绿的。
if [ -n "${ONLY:-}" ]; then
  ./gradlew testDebugUnitTest --console=plain --tests "*${ONLY}*" || { echo "✗ 单测失败"; exit 1; }
else
  ./gradlew testDebugUnitTest --console=plain || { echo "✗ 单测失败"; exit 1; }
fi

# Gradle 默认不打印用例数——从 XML 报告里数出来，免得"绿了但一条没跑"。
#
# ⚠️ 这里有个**坑，2026-09-07 踩过**：XML 目录是增量的。某次运行失败后 Gradle 只写了
# 跑到失败点为止的那几个类；随后修好再跑，如果测试任务是 UP-TO-DATE 就**不重写目录**，
# 于是按残留 XML 求和会**少报**（那次把 182 报成了 141，连报了四次）。
# 所以下面同时数一遍源码里的测试类，两边对不上就明说——**宁可吵，也不要报一个假数字**。
python3 - <<'PY' 2>/dev/null || true
import glob, re
t = f = e = s = 0
files = glob.glob('*/build/test-results/testDebugUnitTest/*.xml')
for p in files:
    h = open(p).read(2000)
    t += int(re.search(r'tests="(\d+)"', h).group(1))
    f += int(re.search(r'failures="(\d+)"', h).group(1))
    e += int(re.search(r'errors="(\d+)"', h).group(1))
    s += int(re.search(r'skipped="(\d+)"', h).group(1))
# 数**类声明**不是文件——有三个文件里各声明了两个测试类（EnvelopeTest / WakeActionTest /
# MessageActionsTest），按文件数比会永远误报。
src = []
for path in glob.glob('*/src/test/**/*.kt', recursive=True):
    src += re.findall(r'^class\s+\w+Test\b', open(path).read(), re.M)
print(f"\n用例 {t} · 失败 {f} · 错误 {e} · 跳过 {s} （{len(files)} 个测试类）")
if t == 0:
    print("⚠ 一条用例都没跑到——检查 --tests 过滤或源集配置。")
elif len(files) != len(src):
    print(f"⚠ 报告里 {len(files)} 个测试类，源码里 {len(src)} 个——**这个用例数不可信**。")
    print("  多半是上一次失败留下的残留报告 + 本次 UP-TO-DATE 没重写。")
    print("  跑 `rm -rf */build/test-results && ./scripts/test.sh` 拿真实数字。")
PY

echo ""
echo "✓ 全部通过。"
