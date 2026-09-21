#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""snap_backends.py —— 快照后端抽象（`N4`）

## 为什么要这一层

`snap_guard` 里真正通用的只有**算法**：定期打快照 → 比对发现消失的文件 →
按回收站契约搬回（带熔断）。绑死小米的只有**三样**：

| 原写死项 | 归属 |
|---|---|
| `SUBVOLS = [("pa0","/nas/mnt/pa0/u133630987",…), ("pa1",…)]` | ❌ 壳（双盘 cfs union 布局） |
| `SNAP_INNER = "data/WorkSpace/NasPhoto归档"` | ❌ 壳（归档相对子卷根的位置） |
| `btrfs subvolume snapshot/delete` | 🔎 **可泛化** —— Btrfs 是通用文件系统 |

⇒ 把这三样收进后端，算法一行不用改。

## 判据（架构文档 §2）

- **核心** = 换宿主一个字节都不用改 ⇒ 「发现可快照单元」「打/删/列快照」这套**接口与流程**
- **壳** = 每换宿主必重写 ⇒ 「本机是哪块盘、哪个子卷、什么布局」⇒ **由探测得出**

## ⚠️ 降级必须可见（§6.3）

`NullBackend` 存在的意义不是「优雅降级」，而是**把「没有保护」这件事说出来**。
历史教训：特殊权限被撤销时**无回调无广播**，只写日志 = 用户永远不知道。
所以后端必须暴露 `available` / `reason`，并由 `snap_guard` 写进 `capability.json`、
最终显示在插件页上。

## 证据级别

| 实现 | 级别 |
|---|---|
| `BtrfsBackend` | ✅ **本机实测**（发现 + 打/删快照全链路跑过） |
| `ZfsBackend` | 🔎 **推断** —— 命令语义通用，**未在 TrueNAS 等机型实测** |
| `NullBackend` | ✅ 逻辑平凡 |
"""

import os
import subprocess
import time

SNAPDIR_NAME = ".np_snap"           # 快照存放目录（建在**文件系统根**上，不在被快照的子卷内）
DEFAULT_PREFIX = "npguard_"


def sh(cmd, timeout=300):
    """跑一条命令，返回 `(rc, stdout+stderr)`。**不抛异常** —— 探测阶段要能容错。"""
    try:
        p = subprocess.run(cmd, capture_output=True, text=True,
                           timeout=timeout, errors="replace")
        return p.returncode, (p.stdout or "") + (p.stderr or "")
    except subprocess.TimeoutExpired:
        return 124, "timeout after %ss: %s" % (timeout, " ".join(cmd))
    except Exception as e:                       # noqa: BLE001 —— 探测不能因此炸掉
        return 125, "exec failed: %r" % (e,)


class SnapUnit(object):
    """一个「可打快照的单元」= 一个子卷 / 数据集。"""

    __slots__ = ("label", "root", "snapdir", "inner")

    def __init__(self, label, root, snapdir, inner):
        self.label = label          # 稳定标识（进 state.json，**改名 = 丢基线**）
        self.root = root            # 被快照的那个目录
        self.snapdir = snapdir      # 快照存放目录
        self.inner = inner          # 归档根在这里面的相对路径

    def __repr__(self):
        return "SnapUnit(%s, %s, inner=%s)" % (self.label, self.root, self.inner)


# ---------------------------------------------------------------- 后端基类

class SnapBackend(object):
    name = "null"
    available = False
    reason = ""

    def __init__(self, prefix=DEFAULT_PREFIX):
        self.units = []
        self.inner = None
        self.notes = []
        # ⚠️ 前缀必须是**实例属性**，不能是模块常量：
        #    两个实例共用一块 btrfs 盘时（例如生产 + 影子、或换宿主的过渡期），
        #    「清孤儿快照」会互相把对方的快照删掉 —— 而快照就是安全网本身。
        #    可通过 `NP_SNAP_PREFIX` 隔离。
        self.prefix = prefix

    # ---- 能力 ----
    def capability(self):
        return {
            "backend": self.name,
            "available": bool(self.available),
            "reason": self.reason,
            "units": [{"label": u.label, "root": u.root} for u in self.units],
            "inner": self.inner,
            "checkedAt": int(time.time()),
        }

    # ---- 接口（子类实现）----
    def snap_path(self, unit, name):
        """快照内容**在文件系统里可见的位置**（比对阶段要读它）。"""
        raise NotImplementedError

    def snapshot(self, unit, name):
        """打一个只读快照。返回 `(ok, msg)`。"""
        raise NotImplementedError

    def delete(self, unit, name):
        """删一个快照。返回 `(ok, msg)`。"""
        raise NotImplementedError

    def list_snapshots(self, unit):
        """列出**本工具建的**快照名（只认前缀，绝不碰别人的快照）。"""
        raise NotImplementedError


# ---------------------------------------------------------------- Btrfs

def _btrfs_mounts():
    """从 `/proc/mounts` 取 btrfs 挂载点。"""
    out = []
    try:
        with open("/proc/mounts", encoding="utf-8", errors="replace") as f:
            for line in f:
                parts = line.split()
                if len(parts) >= 3 and parts[2] == "btrfs":
                    # /proc/mounts 用 \\040 转义空格
                    out.append(parts[1].replace("\\040", " "))
    except OSError:
        pass
    return out


def _find_inner(root, arc):
    """
    归档根 `arc` 在 `root` 里面的相对路径（＝原 `SNAP_INNER` 的泛化）。

    做法是**后缀探测**（从最长的后缀开始试）：小米布局下，归档根是
    `/nas/pool0/<身份>/data/WorkSpace/NasPhoto归档`，而子卷是 `/nas/mnt/paN/<身份>`，
    两者只共享后半截 ⇒ 剥掉前面的段，试哪一段在子卷里真实存在。

    返回 None = 这个 root 跟归档根没关系（**不是**空串）。
    """
    parts = [p for p in arc.split("/") if p]
    for i in range(len(parts)):
        tail = "/".join(parts[i:])
        if tail and os.path.isdir(os.path.join(root, tail)):
            return tail
    if os.path.realpath(root) == os.path.realpath(arc):
        return ""                       # 归档根就是子卷根（inner 为空是合法的）
    return None


class BtrfsBackend(SnapBackend):
    name = "btrfs"

    @classmethod
    def detect(cls, arc, prefix=DEFAULT_PREFIX):
        """
        找出本机上「能通过 btrfs 快照保护这个归档根」的所有单元。

        判定一个候选目录 `<mount>/<name>` 成立，需同时满足：
          ① `<mount>/<name>/<归档根的后缀>` 真实存在（说明它确实装着归档）
          ② `btrfs subvolume show <mount>/<name>` 成功（说明它**是子卷**，
             快照才有意义 —— 普通目录在 btrfs 上仍可快照，但那会连整卷一起拍）
        ⚠️ 顺序不能反：先做①（纯 `isdir`，免费）再做②（每个要起一次进程）。
        """
        self = cls(prefix)
        units = []
        for mp in _btrfs_mounts():
            try:
                names = sorted(os.listdir(mp))
            except OSError:
                continue
            for n in names:
                if n.startswith(".") or n == SNAPDIR_NAME:
                    continue
                root = os.path.join(mp, n)
                if os.path.islink(root) or not os.path.isdir(root):
                    continue
                inner = _find_inner(root, arc)
                if inner is None:
                    continue
                rc, _out = sh(["btrfs", "subvolume", "show", root], timeout=30)
                if rc != 0:
                    continue
                units.append(SnapUnit(label=os.path.basename(mp),
                                      root=root,
                                      snapdir=os.path.join(mp, SNAPDIR_NAME),
                                      inner=inner))

        if not units:
            self.reason = ("没找到装着归档根的 btrfs 子卷（归档根：%s）"
                           "⇒ 本机不支持快照兜底" % arc)
            return self

        # 所有单元必须对 inner 有共识，否则「归档在子卷里的位置」是歧义的
        groups = {}
        for u in units:
            groups.setdefault(u.inner, []).append(u)
        inner, keep = max(groups.items(), key=lambda kv: len(kv[1]))
        if len(groups) > 1:
            self.notes.append(
                "inner 取值不一致（%s），采用多数值 %r，丢弃 %d 个单元"
                % (", ".join(repr(k) for k in groups), inner,
                   len(units) - len(keep)))

        self.units = sorted(keep, key=lambda u: u.label)
        self.inner = inner
        self.available = True
        return self

    def snap_path(self, unit, name):
        return os.path.join(unit.snapdir, name)

    def snapshot(self, unit, name):
        os.makedirs(unit.snapdir, exist_ok=True)
        dst = os.path.join(unit.snapdir, name)
        if os.path.exists(dst):
            return True, "already exists"
        t0 = time.time()
        rc, out = sh(["btrfs", "subvolume", "snapshot", "-r", unit.root, dst],
                     timeout=300)
        if rc != 0:
            return False, "rc=%d %s" % (rc, out.strip()[:200])
        return True, "%.2fs" % (time.time() - t0)

    def delete(self, unit, name):
        dst = os.path.join(unit.snapdir, name)
        if not os.path.isdir(dst):
            return True, "not present"
        rc, out = sh(["btrfs", "subvolume", "delete", dst], timeout=300)
        if rc != 0:
            return False, "rc=%d %s" % (rc, out.strip()[:200])
        return True, "ok"

    def list_snapshots(self, unit):
        try:
            return sorted(n for n in os.listdir(unit.snapdir)
                          if n.startswith(self.prefix)
                          and os.path.isdir(os.path.join(unit.snapdir, n)))
        except OSError:
            return []


# ---------------------------------------------------------------- ZFS

class ZfsBackend(SnapBackend):
    name = "zfs"

    @classmethod
    def detect(cls, arc, prefix=DEFAULT_PREFIX):
        self = cls(prefix)
        rc, out = sh(["zfs", "list", "-H", "-o", "name,mountpoint", "-t",
                      "filesystem"], timeout=60)
        if rc != 0:
            self.reason = "本机没有 zfs（`zfs list` 失败）⇒ 本机不支持快照兜底"
            return self

        best = None
        for line in out.splitlines():
            parts = line.split("\t")
            if len(parts) < 2:
                continue
            ds, mp = parts[0], parts[1]
            if mp in ("", "none", "-", "legacy"):
                continue
            mp = mp.rstrip("/") or "/"
            if arc == mp or arc.startswith(mp + "/"):
                if best is None or len(mp) > len(best[1]):
                    best = (ds, mp)
        if best is None:
            self.reason = "zfs 在线，但没有挂载点覆盖归档根 %s" % arc
            return self

        ds, mp = best
        inner = os.path.relpath(arc, mp)
        self.units = [SnapUnit(label=ds.replace("/", "_"), root=mp,
                               snapdir=None, inner="" if inner == "." else inner)]
        self.inner = self.units[0].inner
        self.available = True
        self.notes.append("🔎 ZFS 路径为本机之外推断实现，未经实机验证")
        return self

    def snap_path(self, unit, name):
        # ZFS 快照通过魔术目录可见（无需挂载）
        return os.path.join(unit.root, ".zfs", "snapshot", name)

    def snapshot(self, unit, name):
        rc, out = sh(["zfs", "snapshot", "%s@%s" % (unit.label, name)], timeout=300)
        if rc != 0:
            return False, "rc=%d %s" % (rc, out.strip()[:200])
        return True, "ok"

    def delete(self, unit, name):
        rc, out = sh(["zfs", "destroy", "%s@%s" % (unit.label, name)], timeout=300)
        if rc != 0:
            return False, "rc=%d %s" % (rc, out.strip()[:200])
        return True, "ok"

    def list_snapshots(self, unit):
        rc, out = sh(["zfs", "list", "-H", "-o", "name", "-t", "snapshot",
                      "-r", unit.label], timeout=60)
        if rc != 0:
            return []
        names = []
        for line in out.splitlines():
            if "@" not in line:
                continue
            snap = line.split("@", 1)[1]
            if snap.startswith(self.prefix):
                names.append(snap)
        return sorted(names)


# ---------------------------------------------------------------- Null

class NullBackend(SnapBackend):
    name = "null"

    def __init__(self, reason):
        SnapBackend.__init__(self)
        self.reason = reason
        self.available = False


# ---------------------------------------------------------------- 探测入口

def detect(arc, prefix=DEFAULT_PREFIX):
    """
    按 btrfs → zfs → null 的顺序探测，返回一个后端实例。

    ⚠️ 返回 Null 时**不要静默**：调用方必须把 `capability()` 暴露出去
    （`snap_guard` 写 `capability.json`，插件页据此显示降级提示）。
    """
    for cls_, label in ((BtrfsBackend, "btrfs"), (ZfsBackend, "zfs")):
        try:
            be = cls_.detect(arc, prefix=prefix)
        except Exception as e:                       # noqa: BLE001
            be = cls_()
            be.reason = "%s 探测异常：%r" % (label, e)
        if be.available:
            return be
        last = be
    return NullBackend(
        "本机没有可用的快照后端（btrfs / zfs 都没探到）⇒ **快照兜底不可用，"
        "电脑端删除的文件无法恢复**。上次探测原因：%s" % (last.reason or "无"))
