#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
NasPhoto 构建 + 安装 一条龙（本工作区自包含工具链）。

为什么要有这个脚本：
  · 本机的 Bash 环境缺 coreutils（`ls` / `which` 都没有），`cd` 也被 shim 打断，
    直接用 shell 拼命令很容易踩空；
  · 构建要指定 JAVA_HOME（JDK 17）和 cwd，参数一多就容易写错；
  · dex 阶段偶发 `graph.bin 拒绝访问`，需要自动清 desugar_graph 缓存重试。

用法：
  python np_build.py               # 只构建 debug APK
  python np_build.py --install     # 构建 + adb install -r
  python np_build.py --install --probe "askUi remote"   # 再拉起探针
"""
import argparse
import os
import re
import shutil
import subprocess
import sys

ROOT = os.path.dirname(os.path.abspath(__file__))
APP = os.path.join(ROOT, "nasphoto")
GRADLE = os.path.join(ROOT, "tools", "gradle-8.9", "bin", "gradle.bat")
JDK = r"C:\program files\java\jdk-17.0.1.12-hotspot"
ADB = r"E:\platform-tools\adb.exe"
APK = os.path.join(APP, "app", "build", "outputs", "apk", "debug", "app-debug.apk")
PKG = "cn.dsr213.nasphoto"


def run(cmd, cwd, env=None, timeout=None):
    print("+", " ".join(cmd), flush=True)
    p = subprocess.run(
        cmd, cwd=cwd, env=env, timeout=timeout,
        stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
    )
    out = p.stdout.decode("utf-8", "replace")
    print(out, flush=True)
    return p.returncode, out


def wipe_desugar_cache():
    """dex 阶段偶发 `graph.bin 拒绝访问`（Windows 文件锁）→ 删掉缓存重来。"""
    d = os.path.join(APP, "app", "build", "intermediates", "desugar_graph")
    if os.path.isdir(d):
        shutil.rmtree(d, ignore_errors=True)
        print("· 已清理 desugar_graph 缓存", flush=True)


def build():
    env = dict(os.environ)
    env["JAVA_HOME"] = JDK
    cmd = [GRADLE, "assembleDebug", "--no-daemon", "-q"]
    for attempt in (1, 2):
        code, out = run(cmd, cwd=APP, env=env)
        if code == 0:
            return 0, out
        if "graph.bin" in out or "拒绝访问" in out:
            wipe_desugar_cache()
            continue
        return code, out
    return 1, out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--install", action="store_true")
    ap.add_argument("--probe", default=None, help='如 "askUi remote"')
    ap.add_argument("--logs", default=None, help="拉取日志的秒数，如 5")
    a = ap.parse_args()

    code, out = build()
    if code != 0:
        print("❌ 构建失败", file=sys.stderr)
        sys.exit(code)
    print("✅ 构建成功：", APK, flush=True)

    if not a.install:
        return
    run([ADB, "install", "-r", APK], cwd=ROOT)

    # ⚠️ `adb install -r` 会把 SYSTEM_ALERT_WINDOW 这个 appop 重置掉 ——
    #    重置后确认卡片会静默降级成通知，肉眼看不出"悬浮卡片没弹"。
    #    只 `appops set <pkg> ... allow` 在 HyperOS 上**不生效**（包级 op 被写成
    #    ignore 之后压住了 uid 模式），必须先把它清回 default，再设 uid 模式。
    ops = [
        [ADB, "shell", "appops", "set", PKG, "SYSTEM_ALERT_WINDOW", "default"],
        [ADB, "shell", "appops", "set", "--uid", PKG, "SYSTEM_ALERT_WINDOW", "allow"],
    ]
    for c in ops:
        run(c, cwd=ROOT)
    run([ADB, "shell", "appops", "get", PKG, "SYSTEM_ALERT_WINDOW"], cwd=ROOT)

    if a.probe:
        key, _, val = a.probe.partition(" ")
        # ⚠️ am start 不会重跑 onCreate，探针会被静默忽略 → 必须先 force-stop
        run([ADB, "shell", "am", "force-stop", PKG], cwd=ROOT)
        args = [ADB, "shell", "am", "start", "-n", "%s/.ui.MainActivity" % PKG]
        if val:
            args += ["--es", key, val]
        else:
            args += ["--es", key, "1"]
        run(args, cwd=ROOT)

    if a.logs:
        run([ADB, "logcat", "-d", "-s", "NasPhotoProbe:I", "NasPhotoDel:I"],
            cwd=ROOT)


if __name__ == "__main__":
    main()
