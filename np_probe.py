#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
NasPhoto 探针驱动器：串行执行一组 `am start --es <key> <val>`，抓回 logcat 并打印。

为什么需要它（三条都是踩过的）：
  · 本机 Bash 环境缺 coreutils（`ls` 都没有），直接拼 shell 命令容易踩空；
  · `am start` 在 Activity 已前台时走 `onNewIntent`、**不会重跑 onCreate**，
    探针会被静默忽略 —— 所以每一步都必须先 `force-stop`；
  · logcat 是 UTF-8，而 PowerShell 默认按 GBK 解码控制台输出 → 中文全是乱码，
    所以固定在这里做 decode，不依赖终端的编码设置。

用法：
  python np_probe.py --step "cand 1"
  python np_probe.py --step "restorePolicy 3" --step "ageRestored 4" --step "cand 1"
  python np_probe.py --step "trash list" --out result.txt
"""
import argparse
import subprocess
import sys
import time

ADB = r"E:\platform-tools\adb.exe"
PKG = "cn.dsr213.nasphoto"
ACT = PKG + "/.ui.MainActivity"


def adb(*args, timeout=90):
    p = subprocess.run(
        [ADB] + list(args), stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
        timeout=timeout,
    )
    return p.stdout.decode("utf-8", "replace")


def one_step(key, val, wait):
    """跑一个探针：清缓冲 → 杀进程 → am start → 等 → 抓日志。

    ⚠️ 必须带重试：`adb install -r` 之后**紧接着**发 `am start`，intent 会被静默丢掉
    （AMS 还没准备好接管刚被替换的包），表现是 logcat 抓回空 —— 看起来像"探针没输出"，
    很容易被误读成"代码有 bug / App 崩了"。实测踩过一次。
    """
    args = ("shell", "am", "start", "-n", ACT, "--es", key, val)
    for attempt in (1, 2):
        adb("logcat", "-c")
        adb("shell", "am", "force-stop", PKG)
        time.sleep(1)
        adb(*args)
        time.sleep(wait if attempt == 1 else wait + 4)
        out = adb("logcat", "-d", "-s", "NasPhotoProbe:I")
        if "NasPhotoProbe" in out:
            return out
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--step", action="append", default=[],
                    help='形如 "cand 1"（key + 值）；可给多次，按顺序执行')
    ap.add_argument("--wait", type=float, default=6.0, help="每步等待秒数")
    ap.add_argument("--out", default=None, help="同时写入这个文件（utf-8）")
    a = ap.parse_args()
    if not a.step:
        ap.error("至少要给一个 --step")

    buf = []
    for s in a.step:
        key, _, val = s.partition(" ")
        val = val.strip() or "1"
        buf.append("=" * 60)
        buf.append("STEP  --es %s %s" % (key, val))
        buf.append("=" * 60)
        try:
            out = one_step(key, val, a.wait)
        except Exception as e:                       # 探针挂了也要看得见
            out = "!! 探针执行异常：%r" % (e,)
        buf.append(out.rstrip())
        buf.append("")
        # 边跑边刷，避免最后一步失败把前面的结果一起丢掉
        print("\n".join(buf), flush=True)

    text = "\n".join(buf)
    if a.out:
        with open(a.out, "w", encoding="utf-8") as f:
            f.write(text)
    return 0


if __name__ == "__main__":
    sys.exit(main())
