# -*- coding: utf-8 -*-
"""
ADB 代理脚本（Windows版）
作用：让远程服务器可以间接调用你电脑上的 ADB，从而分析虎牙直播卡顿日志
使用：
  1. 安装 Python 3.8+（从 https://www.python.org 下载，勾选 Add to PATH）
  2. 修改下方 ADB_PATH 为你雷电模拟器的 adb.exe 路径
  3. 双击运行本脚本，或在 PowerShell 中：python adb_proxy.py
  4. 用 ngrok 把 8765 端口暴露到公网，把公网 URL 发给技术人员
"""

import subprocess
import threading
import time
import sys
import os
import re
from http.server import HTTPServer, BaseHTTPRequestHandler
import urllib.parse

# ============== 请根据你的实际情况修改这部分 ==============

# 雷电模拟器安装目录下的 adb.exe 完整路径
# 常见路径：
#   C:\leidian\LDPlayer9\adb.exe        (雷电9)
#   C:\leidian\LDPlayer4\adb.exe        (雷电4)
#   C:\dnplayer2\adb.exe                 (旧版雷电)
#   如果你用 Android Studio：
#   C:\\Users\\你的用户名\\AppData\\Local\\Android\\Sdk\\platform-tools\\adb.exe
ADB_PATH = r"C:\leidian\LDPlayer9\adb.exe"

# 雷电模拟器端口（常见：7555 / 7557 / 7559 / 5555）
# 脚本启动时会自动扫描这些端口，成功的那个就是对的
LD_PORTS = [7555, 7557, 7559, 5555, 5557]

# 本地监听端口（ngrok需要转发这个端口）
LOCAL_PORT = 8765

# 允许执行的ADB命令白名单（安全考虑，防止任意命令执行）
ALLOWED_CMD_PREFIXES = [
    "devices",
    "connect",
    "kill-server",
    "start-server",
    "version",
    "get-state",
    "shell dumpsys window",
    "shell ps",
    "shell cat /proc/",
    "logcat -d",          # 一次性抓取历史日志
    "logcat -c",          # 清空日志
    "shell input",
    "install",
    "uninstall",
    "shell am start",
    "shell am force-stop",
    "shell pm list packages",
]

# =========================================================

# 校验adb.exe是否存在
if not os.path.exists(ADB_PATH):
    print(f"[错误] ADB路径不存在: {ADB_PATH}")
    print("请打开本脚本，修改 ADB_PATH 为你雷电模拟器目录下的 adb.exe")
    for guess in [
        r"C:\leidian\LDPlayer9\adb.exe",
        r"C:\leidian\LDPlayer4\adb.exe",
        r"C:\dnplayer2\adb.exe",
    ]:
        if os.path.exists(guess):
            print(f"  (提示: 检测到这里可能有: {guess})")
    input("按回车键退出...")
    sys.exit(1)


def run_adb(cmd: str, timeout: int = 30, check_allowed: bool = True) -> str:
    """执行ADB命令并返回输出（带白名单校验）"""
    # 安全白名单检查
    if check_allowed:
        cmd_clean = cmd.strip()
        allowed = False
        for prefix in ALLOWED_CMD_PREFIXES:
            if cmd_clean.startswith(prefix):
                allowed = True
                break
        if not allowed:
            return f"[安全拦截] 该ADB命令不在白名单中: {cmd}"

    full_cmd = f'"{ADB_PATH}" {cmd}'
    try:
        result = subprocess.run(
            full_cmd,
            shell=True,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=timeout,
        )
        out = result.stdout or ""
        err = result.stderr or ""
        combined = out + ("\n" if out and err else "") + err
        return combined if combined else f"[命令返回空，exit={result.returncode}]"
    except subprocess.TimeoutExpired:
        return f"[超时] 命令执行超过 {timeout} 秒"
    except Exception as e:
        return f"[执行异常] {type(e).__name__}: {e}"


def auto_connect_ld():
    """启动时自动扫描并连接雷电模拟器端口"""
    print("[自动连接] 正在扫描雷电模拟器端口...")
    run_adb("kill-server", check_allowed=False)
    time.sleep(1)
    run_adb("start-server", check_allowed=False)
    time.sleep(1)

    for port in LD_PORTS:
        target = f"127.0.0.1:{port}"
        print(f"  尝试 {target} ...", end=" ", flush=True)
        out = run_adb(f"connect {target}", check_allowed=False)
        if "connected" in out.lower() or "cannot" not in out.lower():
            time.sleep(0.5)
            dev = run_adb("devices", check_allowed=False)
            if target in dev and "offline" not in dev:
                print(f"✅ 成功！")
                return target, port
        print(f"❌")

    print("\n[警告] 未能自动连接到雷电模拟器，请确认：")
    print("  1. 雷电模拟器已经启动")
    print("  2. 已开启 开发者选项 -> USB调试")
    print("  3. ADB_PATH 设置正确")
    return None, None


class ADBProxyHandler(BaseHTTPRequestHandler):
    """HTTP代理处理类"""

    def log_message(self, format, *args):
        """屏蔽默认HTTP日志，避免刷屏"""
        pass

    def _send(self, text: str, status: int = 200):
        self.send_response(status)
        self.send_header("Content-Type", "text/plain; charset=utf-8")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Access-Control-Allow-Origin", "*")
        self.end_headers()
        if isinstance(text, str):
            text = text.encode("utf-8", errors="replace")
        self.wfile.write(text)

    def do_GET(self):
        parsed = urllib.parse.urlparse(self.path)
        qs = urllib.parse.parse_qs(parsed.query)

        # 健康检查
        if parsed.path in ("/", "/health", "/ping"):
            dev = run_adb("devices", check_allowed=False)
            self._send(
                f"ADB代理运行中 ✅\n\n"
                f"ADB路径: {ADB_PATH}\n"
                f"设备列表:\n{dev}\n\n"
                f"使用方法：\n"
                f"  /?cmd=devices                 查看设备\n"
                f"  /?cmd=logcat+-d+-s+TVPlayerManager:H  一次性抓日志\n"
                f"  /?cmd=logcat+-c              清空日志缓冲区"
            )
            return

        cmd = qs.get("cmd", [""])[0]
        if not cmd:
            self._send("[错误] 缺少参数 cmd，例：/?cmd=devices", 400)
            return

        # URL解码一次（因为 + 和 %20 需要还原）
        cmd = urllib.parse.unquote_plus(cmd)
        t = int(qs.get("timeout", ["60"])[0])
        print(f"[调用] adb {cmd}")
        result = run_adb(cmd, timeout=min(t, 180))
        self._send(result)


def banner():
    border = "=" * 60
    print()
    print(border)
    print("       🟢 ADB 代理服务已启动  ")
    print(border)
    print(f"  本地访问: http://127.0.0.1:{LOCAL_PORT}/")
    print(f"  测试页面: http://127.0.0.1:{LOCAL_PORT}/?cmd=devices")
    print()
    print("  📌 下一步：用 ngrok 把端口 {LOCAL_PORT} 暴露到公网")
    print("     1) 下载 ngrok: https://ngrok.com/download")
    print("     2) 运行: ngrok http {LOCAL_PORT}")
    print("     3) 把 'Forwarding' 那一行的 https://xxx.ngrok-free.app")
    print("        复制发给技术人员")
    print(border)
    print("  按 Ctrl+C 退出")
    print()


if __name__ == "__main__":
    # 自动连接雷电
    target, port = auto_connect_ld()
    if target:
        print(f"\n[自动连接成功] {target}")

    banner()

    try:
        server = HTTPServer(("0.0.0.0", LOCAL_PORT), ADBProxyHandler)
        server.serve_forever()
    except OSError as e:
        if "Address already in use" in str(e):
            print(f"[错误] 端口 {LOCAL_PORT} 被占用，关闭占用程序或修改 LOCAL_PORT")
        else:
            raise
    except KeyboardInterrupt:
        print("\n👋 已退出")
