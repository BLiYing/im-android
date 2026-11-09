#!/usr/bin/env python3
"""移动端冒烟（T3）的 Android 设备驱动：从 Mac 经 adb 操作**真 App**，按无障碍树里的文字找元素。

为什么不用 instrumentation（Compose 测试规则）：它会把 App 的协程调度换成测试调度器，真 App 里有处布局会被
重入测量而崩（`performMeasureAndLayout called during measure layout`），测出来的不是用户跑的那个 App。
这里完全黑盒：`uiautomator dump` 取界面树 → 按 text / content-desc 找节点 → `input tap` / `input text`。

用法（由 scripts/ui-smoke.sh 调）：ui_smoke_driver.py <对端昵称> <ping> <pong> <截图目录>
"""
import re
import subprocess
import sys
import time

PKG = "com.libeyond.imandroid"
NODE = re.compile(r"<node [^>]*>")
ATTR = re.compile(r'(\w[\w-]*)="([^"]*)"')


def adb(*args: str) -> str:
    return subprocess.run(["adb", *args], capture_output=True, text=True, check=False).stdout


def nodes() -> list[dict]:
    adb("shell", "uiautomator", "dump", "/sdcard/im-smoke.xml")
    xml = adb("shell", "cat", "/sdcard/im-smoke.xml")
    out = []
    for raw in NODE.findall(xml):
        a = dict(ATTR.findall(raw))
        m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", a.get("bounds", ""))
        if m:
            x1, y1, x2, y2 = map(int, m.groups())
            a["center"] = ((x1 + x2) // 2, (y1 + y2) // 2)
            out.append(a)
    return out


def find(pred, timeout: float, what: str, shots: str) -> dict:
    deadline = time.time() + timeout
    while time.time() < deadline:
        for n in nodes():
            if pred(n):
                return n
        time.sleep(1.0)
    shot(shots, f"找不到-{what}")
    sys.exit(f"✗ {timeout:.0f} 秒内没找到：{what}")


def tap(n: dict) -> None:
    adb("shell", "input", "tap", str(n["center"][0]), str(n["center"][1]))


def shot(dir_: str, name: str) -> None:
    with open(f"{dir_}/{name}.png", "wb") as f:
        f.write(subprocess.run(["adb", "exec-out", "screencap", "-p"], capture_output=True, check=False).stdout)


def main() -> None:
    peer, ping, pong, shots = sys.argv[1:5]
    has_text = lambda s: (lambda n: s in n.get("text", ""))

    # 冷启动到首页（不清数据：登录态与服务器地址都保留）。
    adb("shell", "am", "force-stop", PKG)
    adb("shell", "monkey", "-p", PKG, "-c", "android.intent.category.LAUNCHER", "1")

    # ① 收：会话列表出现对端刚发的 ping。
    find(has_text(ping), 30, f"会话列表里的「{ping}」", shots)
    shot(shots, "S01-会话列表收到ping")
    tap(find(lambda n: n.get("text") == peer, 5, f"会话「{peer}」", shots))
    find(has_text(ping), 10, "聊天页里的 ping 气泡", shots)

    # ② 发：点输入框、输入 pong、点「发送」。input text 里空格要写成 %s。
    tap(find(lambda n: "EditText" in n.get("class", ""), 10, "输入框", shots))
    adb("shell", "input", "text", pong.replace(" ", "%s"))
    tap(find(lambda n: n.get("content-desc") == "发送", 5, "发送按钮", shots))

    # ③ 自己这边出现 pong 气泡（不是输入框里的那份）；服务端是否真收到由脚本事后核对。
    find(lambda n: pong in n.get("text", "") and "EditText" not in n.get("class", ""), 10, "pong 气泡", shots)
    time.sleep(2)  # 留时间给 ack 往返
    shot(shots, "S02-pong已发出")
    print("  设备上：收到 ping、发出 pong")


if __name__ == "__main__":
    main()
