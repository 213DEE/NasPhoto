#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""np_doctor.py —— 配置发现（**探测层**，属壳）

## 为什么不把探测写进核心

《核心壳分离架构》§4.3 立过一条：**不要把写死常量换成「另一组我手写的默认值」**，
那只是把硬编码换个地方。同理也**不能有两份探测实现** —— 否则 doctor 报出来的值
与运行时实际用的值会各走各的（本项目反复吃过的「静默漂移」）。

⇒ 分工：
    · `np_config.py`（核心）—— 只**取值**：`NP_*` → 回落。**不扫文件系统**。
    · 本文件（壳）—— 只**探测**：扫盘、试命令，输出 `NP_*`。
    · `np_doctor.sh`（壳）—— 只**包装**：找到 python 再 exec 本文件。

## 用法

    np_doctor.sh                 # 人类可读报告
    np_doctor.sh --env           # 可直接 `eval` 的 NP_* 行（给壳用）
    np_doctor.sh --json          # 机器可读

    # 壳用法示例（裸装壳的 install.sh）：
    eval "$(np_doctor.sh --env)"  &&  echo "$NP_ARCHIVE"

⚠️ 探测是**只读**的：不改任何文件、不打快照、不写 state。
"""

import glob
import json
import os
import sys
import time

# 本文件与 np_config / snap_backends 同处核心目录 ⇒ 优先按 NP_CORE_DIR 找，
# 找不到就退回自身所在目录（直接 `python3 /path/to/np_doctor.py` 时的情形）。
for _d in (os.environ.get("NP_CORE_DIR"), os.path.dirname(os.path.abspath(__file__))):
    if _d and os.path.isdir(_d) and _d not in sys.path:
        sys.path.insert(0, _d)

import np_config                                     # noqa: E402
import snap_backends                                 # noqa: E402

# 归档根扫描根（**不是**路径常量，是「在哪些地方找」）。换宿主可用
# `NP_SCAN_ROOTS=/mnt/disk*:/volume*/photos` 覆盖（冒号分隔的 glob）。
ENV_SCAN_ROOTS = "NP_SCAN_ROOTS"
DEFAULT_SCAN_ROOTS = "/nas/pool*"

# WorkSpace 在归档根路径里的位置（小米布局）。候选路径只在这里面找。
_WS_MARKERS = ("data/WorkSpace", "WorkSpace")


# ---------------------------------------------------------------- 探测：归档根

def _score(path):
    """
    像不像 NasPhoto 归档根。分越高越像。

    判据从强到弱：
      3 —— 里面有 `_回收站`（**最强证据**：这是 NasPhoto 才有的目录）
      2 —— 目录名以「归档」结尾或以 NasPhoto 开头
    """
    if np_config.has_snapshot_marker(path):
        return 3
    name = os.path.basename(path.rstrip("/"))
    if name.endswith("归档") or name.startswith("NasPhoto"):
        return 2
    return 0


def _candidates(roots):
    out = []
    seen = set()
    for pat in roots:
        for pool in sorted(glob.glob(pat)):
            if not os.path.isdir(pool):
                continue
            try:
                users = sorted(os.listdir(pool))
            except OSError:
                continue
            for user in users:
                if user.startswith("."):
                    continue
                for marker in _WS_MARKERS:
                    ws = os.path.join(pool, user, marker)
                    if not os.path.isdir(ws):
                        continue
                    try:
                        names = sorted(os.listdir(ws))
                    except OSError:
                        continue
                    for n in names:
                        p = os.path.join(ws, n)
                        if p in seen or n.startswith(".") or not os.path.isdir(p):
                            continue
                        s = _score(p)
                        if s:
                            seen.add(p)
                            out.append((s, p))
    return out


def probe_archive(env=None):
    """
    归档根：环境变量 → 扫描 → 最后手段。

    返回 `(path, source, note)`。source ∈ `env` / `scan` / `legacy` / `ambiguous`。

    ⚠️ **同分不猜**。多个候选打分相同时返回 ambiguous 并让壳去配置 ——
    猜错归档根在这个项目里是有前科的（曾把「字符串不一致」演成「整库 461 条被删」）。
    """
    env = os.environ if env is None else env
    v = env.get(np_config.ENV_ARCHIVE)
    if v:
        return v, "env", ""

    roots = (env.get(ENV_SCAN_ROOTS) or DEFAULT_SCAN_ROOTS).split(":")
    roots = [r for r in roots if r]
    cands = _candidates(roots)
    if not cands:
        return (np_config.LEGACY_ARCHIVE, "legacy",
                "扫描 %s 没找到候选 ⇒ 回落最后手段值" % ",".join(roots))

    cands.sort(key=lambda x: (-x[0], x[1]))
    if len(cands) > 1 and cands[0][0] == cands[1][0]:
        return (np_config.LEGACY_ARCHIVE, "ambiguous",
                "有 %d 个同分候选（%s）⇒ **不猜**，请用 NP_ARCHIVE 显式指定"
                % (len(cands), ", ".join(p for _s, p in cands[:3])))
    return cands[0][1], "scan", ""


# ---------------------------------------------------------------- 其余探测

def probe_owner(archive):
    """运行身份 = 归档根的属主。见 np_config 文件头（两种「更合理」的取法都是坑）。"""
    try:
        st = os.stat(archive)
        return st.st_uid, st.st_gid, ""
    except OSError as e:
        return (np_config.LEGACY_OWNER[0], np_config.LEGACY_OWNER[1],
                "归档根 stat 失败（%s）⇒ 回落保底值" % (e.strerror or e,))


def probe_tools():
    return {"convert": np_config.which("convert"),
            "ffmpeg": np_config.which("ffmpeg"),
            "btrfs": np_config.which("btrfs"),
            "zfs": np_config.which("zfs"),
            "python3": np_config.which("python3")}


def probe_snapshot(archive):
    be = snap_backends.detect(archive)
    return be


def report():
    archive, src, note = probe_archive()
    uid, gid, own_note = probe_owner(archive)
    tools = probe_tools()
    be = probe_snapshot(archive)

    return {
        "probedAt": int(time.time()),
        "archive": {"path": archive, "source": src, "note": note,
                    "exists": os.path.isdir(archive),
                    "hasTrash": np_config.has_snapshot_marker(archive)},
        "owner": {"uid": uid, "gid": gid, "note": own_note},
        "tools": tools,
        "snapshot": be.capability(),
        "snapshotNotes": be.notes,
        "matchesLegacy": {
            "archive": archive == np_config.LEGACY_ARCHIVE,
            "owner": (uid, gid) == np_config.LEGACY_OWNER,
        },
    }


# ---------------------------------------------------------------- 输出

def _fmt(r):
    L = []
    a = r["archive"]
    L.append("== NasPhoto 配置探测 ==")
    L.append("")
    L.append("归档根 : %s" % a["path"])
    L.append("        来源=%s%s%s" % (
        a["source"],
        "  ✅ 与改造前的写死值一致" if r["matchesLegacy"]["archive"] else
        "  ⚠️ 与改造前的写死值**不同**（换宿主了？）",
        ("  ⚠️ " + a["note"]) if a["note"] else ""))
    L.append("        存在=%s 含回收站=%s" % (a["exists"], a["hasTrash"]))
    L.append("")
    o = r["owner"]
    L.append("运行身份: uid=%s gid=%s%s%s" % (
        o["uid"], o["gid"],
        "  ✅ 与改造前一致" if r["matchesLegacy"]["owner"] else "  ⚠️ 与改造前不同",
        ("  ⚠️ " + o["note"]) if o["note"] else ""))
    L.append("")
    t = r["tools"]
    L.append("工具   : convert=%s" % (t["convert"] or "⛔ 缺失（图片缩略图降级）"))
    L.append("         ffmpeg =%s" % (t["ffmpeg"] or "⛔ 缺失（视频缩略图降级）"))
    L.append("         btrfs  =%s   zfs=%s" % (t["btrfs"] or "-", t["zfs"] or "-"))
    L.append("")
    s = r["snapshot"]
    if s["available"]:
        L.append("快照   : %s（可用）" % s["backend"])
        for u in s["units"]:
            L.append("         %-4s %s" % (u["label"], u["root"]))
        L.append("         inner=%s" % s["inner"])
    else:
        L.append("快照   : ⛔ %s —— %s" % (s["backend"], s["reason"]))
        L.append("         ⚠️ **电脑端删除没有任何保护**，界面上必须显示出来")
    for n in r["snapshotNotes"]:
        L.append("         备注：%s" % n)
    L.append("")
    L.append("⇒ 壳可把以上值注入：np_doctor.sh --env")
    return "\n".join(L)


def _env_lines(r):
    """输出可直接 `eval` 的 NP_* 行。值一律单引号包裹（防空格/中文）。"""

    def q(s):
        return "'" + str(s).replace("'", "'\\''") + "'"

    out = [
        "%s=%s" % (np_config.ENV_ARCHIVE, q(r["archive"]["path"])),
        "%s=%s" % (np_config.ENV_OWNER_UID, q(r["owner"]["uid"])),
        "%s=%s" % (np_config.ENV_OWNER_GID, q(r["owner"]["gid"])),
    ]
    return "\n".join(out)


def main(argv):
    mode = "report"
    if "--env" in argv:
        mode = "env"
    elif "--json" in argv:
        mode = "json"

    r = report()
    if mode == "json":
        print(json.dumps(r, ensure_ascii=False, indent=2))
    elif mode == "env":
        print(_env_lines(r))
    else:
        print(_fmt(r))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
