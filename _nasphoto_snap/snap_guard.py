#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
snap_guard.py —— NasPhoto 归档「电脑端删除」保护

背景
----
归档在 /nas/pool0/u133630987/data/WorkSpace/NasPhoto归档（cfs fuse union，
两块 btrfs 盘 pa0/pa1 各存一部分）。手机 App 删除照片时会 MOVE 进
<归档根>/_回收站/<日期>/<原相对路径>，App 的回收站页面直接扫目录，所以任何
程序只要按这个约定放文件，手机端就能看到并一键还原。

但从电脑（SMB / WebDAV / 资源管理器）删除 = 直接 unlink，没有任何保护，
而 dsync 是「镜像」不是「备份」→ 两块盘同时没。

本脚本用文件系统快照兜住：定期给装着归档的子卷打只读快照，比对
「上一轮快照」与「本轮快照」，把在两轮之间消失的文件从旧快照里
复制进 _回收站/<今天>/<原相对路径>。

为什么是「旧快照 vs 新快照」而不是「旧快照 vs 实时状态」
----
实时状态在比对过程中会变，存在竞态。两个快照都是冻结的时间点，比对是原子的。
且：任何发生在「打新快照之前」的删除都会被本轮捕获（旧有新无）；
发生在「打新快照之后」的删除会被下一轮捕获（本轮快照里有）。
⇒ 无遗漏窗口，最坏延迟 = 一个轮询间隔。

安全设计
----
1. 绝不动原位 —— 只从只读快照往回收站复制，归档里的东西我们不碰。
2. 搬进回收站是可逆的（手机 App 可一键还原），误判代价低。
3. 幂等 —— 回收站里已有该原相对路径的文件就跳过（手机 App 正常删除
   的文件已经在回收站里了，不该被重复搬一次）。
4. 硬闸 —— 单轮超过 MAX_FILES / MAX_BYTES 就停搬，防磁盘被塞满。
5. 排除 _回收站 —— 否则「清空回收站」会被判成删除，导致回收站套娃。
6. chown 到**归档根自己的属主** + 664/775 —— cfs 不认 root 权限，root 建的
   目录 WebDAV 以普通用户身份写不进，App 还原时会 403/500。

## 核心 / 壳边界（N4 之后）

| 部分 | 归属 |
|---|---|
| 比对算法 · 熔断 · 回收站契约 · 幂等 · 权限收敛流程 | ✅ **核心**（本文件） |
| 「本机哪些子卷装着归档」「快照放哪」「怎么打/删快照」 | ❌ **壳** ⇒ `snap_backends.py` 探测 |
| 路径 / uid / 权限位取值 | ❌ **壳** ⇒ `np_config` 注入 |

⚠️ 配置取值不再写在本文件里 —— 全部走 `np_config`（`NP_*` 环境变量 + 回落）。

用法
----
python3 snap_guard.py --once            跑一轮（打快照 + 比对 + 搬运）
python3 snap_guard.py --dry-run         只报告要搬什么，不动任何东西
python3 snap_guard.py --daemon          常驻，按 --interval 循环
python3 snap_guard.py --status          打印当前状态
python3 snap_guard.py --reset           清掉本脚本的快照与 state（不碰回收站）
"""

import argparse
import fcntl
import json
import os
import shutil
import subprocess
import sys
import time
import traceback

# NAS 的 locale 可能是 C，明确用 UTF-8 输出，避免中文写日志时炸掉
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

# ---------------------------------------------------------------- 核心目录

# 核心（np_config / snap_backends）由壳通过 NP_CORE_DIR 指路；默认安装点如下。
sys.path.insert(0, os.environ.get("NP_CORE_DIR") or "/data/nasphoto/core")
import np_config                                    # noqa: E402
import snap_backends                                # noqa: E402

# ---------------------------------------------------------------- 配置（由「壳」注入）
#
# 取值一律走 np_config（核心侧唯一的取值实现）：NP_* 环境变量 → 探测 → 回落。
# 本文件**不再持有**任何路径 / uid / 权限位常量。

cfg = np_config.load()

BASE = cfg.snap_base
STATE_F = os.path.join(BASE, "state.json")
LOG_F = os.path.join(BASE, "guard.log")
LOCK_F = os.path.join(BASE, "guard.lock")
CAPABILITY_F = os.path.join(BASE, "capability.json")

ARC = cfg.archive
TRASH_DIRNAME = np_config.TRASH_DIRNAME
CONFIG_NAME = np_config.CONFIG_NAME

# ⚠️ 属主收敛目标 = **归档根自己的属主**，不是进程自身 uid。
#    这两者不等价，取错会出事：
#      · 本脚本由 systemd 以 root 拉起 ⇒ `os.getuid()` 恒为 0；
#      · 取成 root ⇒ cfs 不认 root 权限 ⇒ App 还原时 403/500（安全设计第 6 条）。
#    ⚠️ 也**不能**取子卷根的属主 —— 实测那里是 uid=900(nassys)，
#       与归档根的 uid=37771(u133630987) 不是一回事。
#    实测等价性（2026-09-18）：归档根 stat = 37771/37771，与原先硬编码完全一致。
OWNER_UID, OWNER_GID = cfg.owner_uid, cfg.owner_gid
DIR_MODE, FILE_MODE = cfg.dir_mode, cfg.file_mode

# ---------------------------------------------------------------- 快照后端（N4）

# 快照名前缀：**实例隔离用**。默认 `npguard_`（与历史一致 ⇒ 现有基线不丢）。
# ⚠️ 两个实例共用一块盘时（生产 + 影子 / 换宿主过渡期）必须给不同前缀，
#    否则「清孤儿快照」会把对方的快照删掉 —— 而快照就是安全网本身。
SNAP_PREFIX = os.environ.get("NP_SNAP_PREFIX") or snap_backends.DEFAULT_PREFIX
BACKEND = snap_backends.detect(ARC, prefix=SNAP_PREFIX)
UNITS = BACKEND.units
# `inner` = 归档根在子卷里的相对路径（≈ 原写死的 SNAP_INNER）。
# 由后端**探测**得出（后缀匹配），不再是写死的字符串。
SNAP_INNER = os.environ.get("NP_SNAP_INNER") or (BACKEND.inner or "")


def unit_of(label):
    for u in UNITS:
        if u.label == label:
            return u
    return None


# 硬闸：单轮搬运上限（防磁盘被塞满）。正常使用远达不到。
MAX_FILES = 500
MAX_BYTES = 20 * 1024 * 1024 * 1024     # 20 GiB
# 软告警：单轮消失比例超过这个值就记 WARN（便于事后察觉异常），但仍然搬
WARN_RATIO = 0.5

LOG_MAX_LINES = 3000

# ---------------------------------------------------------------- 工具


def log(level, msg):
    ts = time.strftime("%Y-%m-%d %H:%M:%S")
    line = "[%s] %-5s %s" % (ts, level, msg)
    print(line, flush=True)
    try:
        os.makedirs(BASE, exist_ok=True)
        with open(LOG_F, "a", encoding="utf-8") as f:
            f.write(line + "\n")
        _rotate_log()
    except Exception:
        pass


def _rotate_log():
    try:
        if os.path.getsize(LOG_F) < 4 * 1024 * 1024:
            return
        with open(LOG_F, encoding="utf-8", errors="replace") as f:
            lines = f.readlines()
        with open(LOG_F, "w", encoding="utf-8") as f:
            f.writelines(lines[-LOG_MAX_LINES:])
    except Exception:
        pass


def sh(cmd, timeout=120):
    """返回 (rc, out+err)。不抛异常。"""
    try:
        p = subprocess.run(cmd, capture_output=True, text=True,
                           timeout=timeout, errors="replace")
        return p.returncode, (p.stdout or "") + (p.stderr or "")
    except subprocess.TimeoutExpired:
        return 124, "timeout after %ss: %s" % (timeout, " ".join(cmd))
    except Exception as e:
        return 125, "exec failed: %r" % (e,)


def read_state():
    try:
        with open(STATE_F, encoding="utf-8") as f:
            return json.load(f)
    except Exception:
        return {}


def write_state(st):
    try:
        os.makedirs(BASE, exist_ok=True)
        tmp = STATE_F + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(st, f, ensure_ascii=False, indent=1)
        os.replace(tmp, STATE_F)
    except Exception as e:
        log("ERROR", "写 state 失败: %r" % (e,))


# ---------------------------------------------------------------- 能力上报
#
# ⚠️ 「降级必须可见」（安全设计之外的第 7 条，来自破坏性操作五条纪律）。
#    快照不可用时只写日志 = 用户永远不知道「电脑端删除能不能恢复」。
#    ⇒ 落一份 capability.json，插件页通过 np_core 的 `capability` action 读它。


def write_capability():
    cap = BACKEND.capability()
    cap["inner"] = SNAP_INNER
    cap["archive"] = ARC
    if not BACKEND.available:
        # 给前端一句能直接显示给用户的话（不含内部细节）
        cap["message"] = ("本机不支持快照兜底，从电脑（共享/网盘）删除的文件无法恢复；"
                          "手机 App 删除的仍可在回收站还原。")
    try:
        os.makedirs(BASE, exist_ok=True)
        tmp = CAPABILITY_F + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(cap, f, ensure_ascii=False, indent=1)
        os.replace(tmp, CAPABILITY_F)
    except Exception as e:
        log("WARN", "写 capability 失败: %r" % (e,))
    return cap


def check_backend():
    """后端不可用 ⇒ 报出来并停手（**不要**假装跑了一轮）。"""
    if BACKEND.available:
        return True
    log("ERROR", "快照后端不可用：%s" % BACKEND.reason)
    log("ERROR", "⇒ 本轮不做任何事。电脑端删除目前**没有任何保护**，请处理上面的原因。")
    write_capability()
    return False


# ---------------------------------------------------------------- 快照操作（全部委托后端）


def snapshots_of(unit):
    return BACKEND.list_snapshots(unit)


def new_snap_name():
    """
    本轮快照名。

    ⚠️⚠️ **不能只用秒级时间戳**（原实现 `SNAP_PREFIX + str(int(time.time()))` 的缺陷，
    2026-09-19 由合成测试实测抓到）：

      同一秒内跑两轮 ⇒ 第二轮的新快照名与「上一轮基线」**撞名** ⇒
      `snapshot()` 走 `already exists` 直接返回成功 ⇒ 新旧指向**同一个快照** ⇒
      比对恒为「无变化」（那一轮的删除**全被漏掉**），
      紧接着 `_rotate_and_state` 又把刚记录为基线的那个快照删掉 ⇒
      下一轮只能「重建基线」⇒ **那次删除永久丢失**。

    触发场景很现实：开机建基线后立刻手动 `--once`，或守护刚跑完一轮你又跑一次。

    ⇒ 对策：撞名就加序号，保证每轮拿到的是**真正的新快照**。
    """
    base = SNAP_PREFIX + str(int(time.time()))
    name = base
    n = 1
    while any(os.path.exists(BACKEND.snap_path(u, name)) for u in UNITS):
        n += 1
        name = "%s-%d" % (base, n)
    return name


def drop_snapshot(snaps, keep=()):
    """
    删除 snaps（形如 {"pa0": name, ...}）里记录的快照。

    `keep` = 本轮新基线的名字集合。⚠️ **绝不能删掉刚成为基线的快照** ——
    删了 state 就指向一个不存在的快照，下一轮只能重建基线，白白丢掉一轮的检测。
    """
    keep = set(keep or ())
    for label, name in (snaps or {}).items():
        if name in keep:
            log("WARN", "保留快照 %s/%s —— 它同时是本轮新基线，删掉会丢一整轮检测"
                % (label, name))
            continue
        u = unit_of(label)
        if not u:
            continue
        ok, msg = BACKEND.delete(u, name)
        if not ok:
            log("WARN", "丢弃快照 %s/%s 失败: %s" % (label, name, msg))


def scan_files(root):
    """
    遍历 root，返回 {相对路径: size}。
    排除 _回收站（第一层目录名）与 .trash_config.json。
    root 不存在时返回 None（读不到 != 空，必须区分）。
    """
    if not os.path.isdir(root):
        return None
    out = {}
    try:
        for dirpath, dirnames, filenames in os.walk(root):
            rel_dir = os.path.relpath(dirpath, root)
            rel_dir = "" if rel_dir == "." else rel_dir
            if rel_dir == "":
                # 归档根的第一层：剔除 _回收站
                dirnames[:] = [d for d in dirnames if d != TRASH_DIRNAME]
            for fn in filenames:
                rel = os.path.join(rel_dir, fn) if rel_dir else fn
                rel = rel.replace(os.sep, "/")
                try:
                    sz = os.path.getsize(os.path.join(dirpath, fn))
                except OSError:
                    continue
                out[rel] = sz
    except Exception as e:
        log("ERROR", "遍历 %s 失败: %r" % (root, e))
        return None
    return out


def snapshot_files(unit, snapname):
    """读某个快照里的归档文件集合。返回 {rel: size} 或 None。

    ⚠️ 快照内容在文件系统里的位置**由后端决定**（btrfs 在 .np_snap/<名>，
    zfs 在 .zfs/snapshot/<名>）⇒ 不写死路径拼接。
    """
    return scan_files(os.path.join(BACKEND.snap_path(unit, snapname), SNAP_INNER))


def union_files(per_disk):
    """
    per_disk: {"pa0": {rel:size} or None, "pa1": {...}}
    返回 (merged, ok)。任一盘读不到 -> ok=False（本轮放弃比对，保守）。
    """
    merged = {}
    ok = True
    for disk, files in per_disk.items():
        if files is None:
            ok = False
            continue
        for rel, sz in files.items():
            # 两盘同名时取较大的（union 语义不保证，但取大更保守）
            if rel not in merged or sz > merged[rel]:
                merged[rel] = sz
    return merged, ok


def trash_index():
    """
    扫描 _回收站，返回 {原相对路径}。
    约定：<归档根>/_回收站/<YYYY-MM-DD>/<原相对路径>
    只认日期目录这一层，其余（如 .trash_config.json）忽略。
    """
    idx = set()
    trash_root = os.path.join(ARC, TRASH_DIRNAME)
    if not os.path.isdir(trash_root):
        return idx
    try:
        for date in os.listdir(trash_root):
            if not _is_date(date):
                continue
            dpath = os.path.join(trash_root, date)
            if not os.path.isdir(dpath):
                continue
            for dirpath, _dirnames, filenames in os.walk(dpath):
                rel_dir = os.path.relpath(dirpath, dpath)
                rel_dir = "" if rel_dir == "." else rel_dir
                for fn in filenames:
                    rel = os.path.join(rel_dir, fn) if rel_dir else fn
                    idx.add(rel.replace(os.sep, "/"))
    except Exception as e:
        log("ERROR", "扫回收站失败: %r" % (e,))
    return idx


def _is_date(s):
    if len(s) != 10 or s[4] != "-" or s[7] != "-":
        return False
    return s.replace("-", "").isdigit()


def chown_tree(path):
    """递归 chown 到归档根属主 + 收敛权限。cfs 不认 root 权限，必须做。"""
    try:
        os.chown(path, OWNER_UID, OWNER_GID)
        os.chmod(path, DIR_MODE)
    except OSError:
        pass
    for dirpath, dirnames, filenames in os.walk(path):
        for d in dirnames:
            p = os.path.join(dirpath, d)
            try:
                os.chown(p, OWNER_UID, OWNER_GID)
                os.chmod(p, DIR_MODE)
            except OSError:
                pass
        for f in filenames:
            p = os.path.join(dirpath, f)
            try:
                os.chown(p, OWNER_UID, OWNER_GID)
                os.chmod(p, FILE_MODE)
            except OSError:
                pass


def restore_one(unit, snapname, rel, date):
    """
    从快照把 rel 复制到 <归档根>/_回收站/<date>/<rel>。
    先写 .part 再 rename（同目录内），避免留下半截文件。
    """
    src = os.path.join(BACKEND.snap_path(unit, snapname), SNAP_INNER, rel)
    if not os.path.isfile(src):
        return False, "快照里没有这个文件"
    dst = os.path.join(ARC, TRASH_DIRNAME, date, rel)
    dst_dir = os.path.dirname(dst)
    try:
        os.makedirs(dst_dir, exist_ok=True)
    except Exception as e:
        return False, "建目录失败: %r" % (e,)
    tmp = dst + ".part"
    try:
        shutil.copy2(src, tmp)
        os.replace(tmp, dst)
    except Exception as e:
        try:
            os.unlink(tmp)
        except OSError:
            pass
        return False, "复制失败: %r" % (e,)
    return True, "ok"


# ---------------------------------------------------------------- 主流程


def one_round(dry_run=False, verbose=False):
    os.makedirs(BASE, exist_ok=True)

    # ---- 0. 后端可用性（不可用则明确停手，且让用户看得见）
    if not check_backend():
        return {}

    st = read_state()
    old = st.get("snaps") or {}

    # ---- 1. 打新快照（所有单元都要成功，否则本轮不比对）
    new_name = new_snap_name()
    new = {}
    all_ok = True
    for u in UNITS:
        if not os.path.isdir(u.root):
            log("WARN", "%s 子卷不存在（盘没挂？）: %s" % (u.label, u.root))
            all_ok = False
            continue
        ok, msg = BACKEND.snapshot(u, new_name)
        if ok:
            new[u.label] = new_name
        else:
            log("ERROR", "%s 打快照失败: %s" % (u.label, msg))
            all_ok = False

    if not all_ok:
        log("WARN", "有盘打快照失败 → 本轮跳过比对（保守，等下一轮）")
        return new

    # ---- 2. 首次运行：只建基线，不搬
    if not old:
        if not dry_run:
            st["snaps"] = new
            st["ts"] = int(time.time())
            write_state(st)
        log("INFO", "%s首次建立基线快照 %s（本轮不搬运）"
            % ("[dry-run] 预演：" if dry_run else "", new_name))
        return new

    # ---- 2b. 旧基线快照丢了（被误删 / 盘重置）→ 重建基线，否则会永久卡死
    missing = []
    for label in old:
        u = unit_of(label)
        if u and not os.path.isdir(BACKEND.snap_path(u, old[label])):
            missing.append(label)
    if missing:
        log("WARN", "旧基线快照缺失（%s）→ 重建基线，本轮不搬运" % ",".join(missing))
        if not dry_run:
            drop_snapshot(old)
            st["snaps"] = new
            st["ts"] = int(time.time())
            write_state(st)
        return new

    # ---- 3. 读旧、新两个快照的文件集合
    old_per, new_per = {}, {}
    for u in UNITS:
        if u.label not in old or u.label not in new:
            continue
        old_per[u.label] = snapshot_files(u, old[u.label])
        new_per[u.label] = snapshot_files(u, new[u.label])

    old_files, ok_old = union_files(old_per)
    new_files, ok_new = union_files(new_per)
    if not ok_old or not ok_new:
        log("WARN", "有快照读不到 → 本轮跳过比对")
        return new

    if verbose:
        log("INFO", "旧快照 %d 文件 / 新快照 %d 文件" % (len(old_files), len(new_files)))

    # ---- 4. 求差：旧有新无 = 两轮之间消失的
    gone = {rel: sz for rel, sz in old_files.items() if rel not in new_files}
    if not gone:
        _rotate_and_state(st, new, old, dry_run=dry_run)
        if verbose:
            log("INFO", "无变化")
        return new

    total_old = len(old_files)
    ratio = len(gone) / float(total_old) if total_old else 0.0
    total_bytes = sum(gone.values())

    log("INFO", "检测到 %d 个文件消失（占旧快照 %.1f%%，共 %.1f MB）"
        % (len(gone), ratio * 100, total_bytes / 1048576.0))

    if ratio >= WARN_RATIO:
        log("WARN", "消失比例达 %.0f%% —— 若不是你主动大批删除，可能是归档目录被"
                    "改名/移走，请检查" % (ratio * 100))

    # ---- 5. 硬闸
    if len(gone) > MAX_FILES or total_bytes > MAX_BYTES:
        log("ERROR", "超过单轮上限（%d 文件 / %.1f GB）→ 本轮停搬，请人工确认"
            % (MAX_FILES, MAX_BYTES / 1073741824.0))
        return new

    # ---- 6. 逐条搬
    idx = trash_index()
    date = time.strftime("%Y-%m-%d")
    moved = skipped = failed = 0
    for rel, sz in sorted(gone.items()):
        if rel in idx:
            skipped += 1
            if verbose:
                log("INFO", "  跳过 %s（回收站里已有，多半是手机 App 删的）" % rel)
            continue
        if os.path.exists(os.path.join(ARC, rel)):
            skipped += 1
            log("WARN", "  跳过 %s（原位仍在？不覆盖）" % rel)
            continue
        if dry_run:
            log("INFO", "  [dry-run] 会搬 %s (%.1f MB)" % (rel, sz / 1048576.0))
            moved += 1
            continue
        # 找到它在哪个单元的旧快照里
        done = False
        for u in UNITS:
            if u.label not in old:
                continue
            ok, msg = restore_one(u, old[u.label], rel, date)
            if ok:
                moved += 1
                done = True
                log("INFO", "↩ 抢救 %s (%.1f MB) → 回收站/%s/"
                    % (rel, sz / 1048576.0, date))
                break
        if not done:
            failed += 1
            log("ERROR", "  搬运失败 %s" % rel)

    # ---- 7. 权限收敛（cfs 不认 root 权限，漏了 App 会 403/500）
    if not dry_run and moved:
        chown_tree(os.path.join(ARC, TRASH_DIRNAME, date))

    log("INFO", "本轮结果：抢救 %d · 跳过 %d · 失败 %d" % (moved, skipped, failed))

    # ---- 8. 轮转快照
    _rotate_and_state(st, new, old, dry_run=dry_run)
    return new


def run_round(dry_run=False, verbose=False):
    """
    包一层，解决两件事：

    ① dry-run 打完临时快照、比对完之后必须把它删掉，否则每跑一次预演就多留一个
       快照（基线不轮转 = 只进不出）。

    ② ⚠️ 锁的粒度必须是「一轮」而不是「整个进程」。守护是死循环，如果它在
       main() 里持锁到进程结束，那么「想立刻生效就跑一次 --once」这条推荐用法
       会永远报「已有实例在运行」—— 文档里写的东西却做不到，比没写更糟。
    """
    lock_fd = os.open(LOCK_F, os.O_CREAT | os.O_RDWR, 0o644)
    try:
        fcntl.flock(lock_fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except OSError:
        log("WARN", "另一实例正在跑一轮，本次跳过（避免两个进程同时动快照）")
        os.close(lock_fd)
        return
    try:
        leftover = one_round(dry_run=dry_run, verbose=verbose) or {}
        if dry_run and leftover:
            drop_snapshot(leftover)
            log("INFO", "dry-run 收尾：临时快照已清理，现场零变化")
    finally:
        fcntl.flock(lock_fd, fcntl.LOCK_UN)
        os.close(lock_fd)


def with_lock(fn):
    """给 --reset 这类一次性动作包锁。"""
    lock_fd = os.open(LOCK_F, os.O_CREAT | os.O_RDWR, 0o644)
    try:
        fcntl.flock(lock_fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except OSError:
        print("已有实例在运行（守护或另一个 --once），请稍后再试")
        os.close(lock_fd)
        return 1
    try:
        return fn()
    finally:
        fcntl.flock(lock_fd, fcntl.LOCK_UN)
        os.close(lock_fd)


def _rotate_and_state(st, new, old, dry_run=False):
    if dry_run:
        return
    # ⚠️ 顺序要紧：**先算出要保留谁，再删**。`keep` 里是本轮新基线 ——
    #    撞名场景下老的名字可能正好等于新基线，先删就把基线删没了（见 new_snap_name）。
    keep = set((new or {}).values())
    drop_snapshot(old, keep=keep)
    st["snaps"] = new
    st["ts"] = int(time.time())
    write_state(st)
    # 兜底：清掉任何不在 state 里的本工具快照（只认自己的前缀，不碰别人的）
    for u in UNITS:
        for n in snapshots_of(u):
            if n in keep:
                continue
            ok, msg = BACKEND.delete(u, n)
            log("INFO" if ok else "WARN", "清孤儿快照 %s: %s" % (n, msg))


def cleanup_orphans(keep_name=None):
    """清掉本工具遗留的孤儿快照（只认本工具前缀）。"""
    kept = 0
    removed = 0
    for u in UNITS:
        for n in snapshots_of(u):
            if keep_name and n == keep_name:
                kept += 1
                continue
            ok, msg = BACKEND.delete(u, n)
            if ok:
                removed += 1
            else:
                log("WARN", "清孤儿快照失败 %s: %s" % (n, msg))
    return removed, kept


def cmd_status():
    st = read_state()
    print("== snap_guard 状态 ==")
    print("归档根 : %s" % ARC)

    # ---- 快照后端（N4 新增：一眼看出「到底有没有保护」）
    if BACKEND.available:
        print("后端   : %s（可用）" % BACKEND.name)
        for u in UNITS:
            print("  %-4s %s  →  %s" % (u.label, u.root, u.snapdir))
        print("inner  : %s" % (SNAP_INNER or "(子卷根)"))
    else:
        print("后端   : ⛔ 不可用 —— %s" % BACKEND.reason)
        print("         ⚠️ 电脑端删除**没有任何保护**")
    for n in BACKEND.notes:
        print("备注   : %s" % n)

    print("state  : %s" % json.dumps(st, ensure_ascii=False))
    # ⚠️ 两件事别搞混：
    #    ① flock 里「文件存在」≠「锁被持有」（文件建了就一直在）；
    #    ② 锁的粒度是「一轮」，守护绝大部分时间在 sleep 空档 ⇒ **「锁空闲」≠「守护没跑」**。
    #    真正判断守护在不在，看 systemd。
    # unit 名可用 NP_SNAP_UNIT 覆盖 —— 影子实例查生产 unit 会给出误导性的读数。
    unit = os.environ.get("NP_SNAP_UNIT") or "nasphoto-snapguard"
    rc, svc_out = sh(["systemctl", "is-active", unit])
    print("服务   : %s（systemd: %s）" % (svc_out.strip() or "unknown", unit))

    held = False
    if os.path.exists(LOCK_F):
        try:
            fd = os.open(LOCK_F, os.O_RDWR)
            try:
                fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
                fcntl.flock(fd, fcntl.LOCK_UN)
            except OSError:
                held = True
            finally:
                os.close(fd)
        except OSError:
            pass
    print("锁     : %s" % ("某一轮正在执行" if held else "空闲（守护多半在轮询间隔里）"))
    cur = (st.get("snaps") or {})
    print("当前快照: %s" % (json.dumps(cur, ensure_ascii=False) if cur else "无"))
    for u in UNITS:
        print("  %s %s -> %s" % (u.label, u.snapdir, snapshots_of(u) or "无"))
    files = scan_files(ARC)
    print("归档   : %s" % ("读不到！" if files is None
                           else "%d 文件 / %.1f MB" % (len(files), sum(files.values()) / 1048576.0)))
    print("回收站 : %d 条（原相对路径）" % len(trash_index()))
    if os.path.isfile(LOG_F):
        print("\n== 日志末尾 ==")
        with open(LOG_F, encoding="utf-8", errors="replace") as f:
            lines = f.readlines()
        for ln in lines[-20:]:
            print("  " + ln.rstrip())


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--once", action="store_true", help="跑一轮")
    ap.add_argument("--dry-run", action="store_true", help="只报告，不动文件")
    ap.add_argument("--daemon", action="store_true", help="常驻循环")
    ap.add_argument("--status", action="store_true")
    ap.add_argument("--reset", action="store_true", help="清本脚本的快照与 state")
    ap.add_argument("--interval", type=int, default=60, help="常驻轮询间隔秒")
    ap.add_argument("-v", "--verbose", action="store_true")
    a = ap.parse_args()

    os.makedirs(BASE, exist_ok=True)
    write_capability()          # 能力先落盘：即便这轮什么都不做，前端也能看到状态

    if a.status:
        cmd_status()
        return 0

    # ⚠️ 这里刻意不拿全局锁 —— 守护是死循环，持锁到进程结束的话，
    #    手动 --once 就永远报"已有实例在运行"。锁下沉到 run_round（粒度 = 一轮）。
    if a.reset:
        def _do_reset():
            removed, kept = cleanup_orphans(None)
            if os.path.isfile(STATE_F):
                os.unlink(STATE_F)
            log("INFO", "--reset 完成：删快照 %d（保留 %d），state 已清" % (removed, kept))
            return 0
        return with_lock(_do_reset)

    if a.daemon:
        log("INFO", "常驻启动，间隔 %ds · 后端=%s · 单元=%s"
            % (a.interval, BACKEND.name,
               ",".join(u.label for u in UNITS) or "(无)"))
        if not BACKEND.available:
            log("ERROR", "后端不可用：%s" % BACKEND.reason)
        while True:
            try:
                run_round(dry_run=False, verbose=a.verbose)
            except Exception:
                log("ERROR", "本轮异常:\n" + traceback.format_exc())
            time.sleep(a.interval)

    run_round(dry_run=a.dry_run, verbose=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
