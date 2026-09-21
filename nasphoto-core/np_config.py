#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""np_config.py —— NasPhoto 配置取值层（核心侧唯一的「取值」实现）

## 边界（《核心壳分离架构》§2）

**核心只认 `NP_*` 环境变量**；未注入时用一组「最后手段」的回落值兜底。
核心**不做文件系统扫描** —— 扫描是「探测」，属壳（`np_doctor.py`）的职责。
这条分界线是刻意的：**探测只有一份实现**，否则 doctor 报出来的值
与运行时实际用的值会各走各的（那正是本项目反复吃过的「静默漂移」）。

## 唯一的例外：属主从数据反推

`owner_uid/gid` 由 `stat(归档根)` 得出，而不是从环境变量或硬编码来。
理由：它不是「宿主策略」，而是**从数据本身读出来的事实**（这份归档属于谁），
换任何宿主都成立。⚠️ 两个反例已实测踩过，禁止改回去：

  · `os.getuid()` → snap_guard 由 systemd 以 **root** 拉起 ⇒ 恒为 0
  · 子卷根 `stat` → 实测 uid=900(`nassys`)，与归档根的 37771 **不是一回事**

## 回落值的定位

⚠️ 下面 `LEGACY_*` **不是配置，是「没配置时别炸」的保底**，且全部等于
改造前的写死值 —— 保留它们的唯一目的，是保证 `N1` 去写死化的**行为零变化**。
`np_doctor` 会实测这些值与探测结果是否一致（架构文档 §12）。
"""

import os
import stat as _stat

# ---------------------------------------------------------------- 环境变量名
# 跨壳统一，勿各自发明（壳只负责「把值注入进来」，不负责定义名字）

ENV_ARCHIVE = "NP_ARCHIVE"          # 归档根
ENV_CACHE = "NP_CACHE"              # 缩略图缓存目录
ENV_SNAP_BASE = "NP_SNAP_BASE"      # snap_guard 的状态/日志/锁目录
ENV_CORE_DIR = "NP_CORE_DIR"        # 核心文件所在目录（壳用来找 np_core）
ENV_OWNER_UID = "NP_OWNER_UID"      # 属主收敛目标（不给则由 stat 归档根得出）
ENV_OWNER_GID = "NP_OWNER_GID"
ENV_DIR_MODE = "NP_DIR_MODE"        # 三位八进制，如 `775`
ENV_FILE_MODE = "NP_FILE_MODE"
ENV_SNAP_PREFIX = "NP_SNAP_PREFIX"  # 快照名前缀（**实例隔离**：两个实例共用一块盘时必须区分）
ENV_SNAP_INNER = "NP_SNAP_INNER"    # 归档根在子卷里的相对路径（不给则由后端探测）

# ---------------------------------------------------------------- 数据契约
# 与宿主无关 ⇒ 归核心，不是可配置项

TRASH_DIRNAME = "_回收站"
CONFIG_NAME = ".trash_config.json"

DEFAULT_KEEP_DAYS = 7
KEEP_DAYS_MIN = 1
KEEP_DAYS_MAX = 3650

# ---------------------------------------------------------------- 最后手段（非配置）

LEGACY_ARCHIVE = "/nas/pool0/u133630987/data/WorkSpace/NasPhoto归档"
LEGACY_CACHE = "/data/clash/plugins/nasphoto/cache"
LEGACY_SNAP_BASE = "/data/nasphoto_snap"
LEGACY_OWNER = (37771, 37771)       # 归档根尚未创建时的保底（= u133630987）

DEFAULT_CORE_DIR = "/data/nasphoto/core"

DEFAULT_DIR_MODE = 0o775
DEFAULT_FILE_MODE = 0o664


# ---------------------------------------------------------------- 小工具

def _env(name, default=""):
    v = os.environ.get(name)
    return v if v not in (None, "") else default


def _env_mode(name, default):
    """权限位：环境变量给三位八进制字符串（如 `775`），解析失败则回落。"""
    v = os.environ.get(name)
    try:
        return int(v, 8) if v else default
    except (TypeError, ValueError):
        return default


def which(name):
    """
    找一个可执行文件的绝对路径。

    ⚠️ 不用 `shutil.which` —— 它只看 `$PATH`，而 snap_guard 由 systemd 拉起时
    `$PATH` 可能是极简的（不含 `/usr/bin`）。这里显式兜一圈常见目录，
    与既有 shell 里 `command -v` 的容错程度对齐。
    """
    dirs = []
    for d in (_env("PATH") or "").split(":"):
        if d:
            dirs.append(d)
    for d in ("/usr/bin", "/usr/local/bin", "/bin", "/sbin", "/usr/sbin"):
        if d not in dirs:
            dirs.append(d)
    for d in dirs:
        p = os.path.join(d, name)
        if os.path.isfile(p) and os.access(p, os.X_OK):
            return p
    return None


# ---------------------------------------------------------------- Config

class Config(object):
    """
    一份取值快照。**进程内取一次即可**（壳拉起进程时注入什么就是什么）。

    ⚠️ 刻意不做「自动重载」：归档根在运行期变化属于壳该重启的事，
       自动重探会在归档短暂不可达（盘没挂）时悄悄换到别的目录 —— 那比停住更糟。
    """

    __slots__ = ("archive", "cache", "snap_base", "trash", "cfg_path",
                 "owner_uid", "owner_gid", "dir_mode", "file_mode",
                 "convert", "ffmpeg", "source", "notes")

    def __init__(self):
        self.notes = []                                   # 给人看的口径说明

        self.archive = self._take(ENV_ARCHIVE, LEGACY_ARCHIVE, "归档根")
        self.cache = self._take(ENV_CACHE, LEGACY_CACHE, "缓存目录")
        self.snap_base = self._take(ENV_SNAP_BASE, LEGACY_SNAP_BASE, "状态目录")

        self.trash = os.path.join(self.archive, TRASH_DIRNAME)
        self.cfg_path = os.path.join(self.trash, CONFIG_NAME)

        self.owner_uid, self.owner_gid = self._owner()
        self.dir_mode = _env_mode(ENV_DIR_MODE, DEFAULT_DIR_MODE)
        self.file_mode = _env_mode(ENV_FILE_MODE, DEFAULT_FILE_MODE)

        self.convert = which("convert")
        self.ffmpeg = which("ffmpeg")
        if not self.convert:
            self.notes.append("未找到 convert ⇒ 图片缩略图降级为占位图")
        if not self.ffmpeg:
            self.notes.append("未找到 ffmpeg ⇒ 视频缩略图降级为占位图")

    # -------------------------------------------------- 内部

    def _take(self, env_name, legacy, label):
        v = os.environ.get(env_name)
        if v:
            return v
        self.notes.append("%s 未由壳注入（%s），回落最后手段值" % (label, env_name))
        return legacy

    def _owner(self):
        """
        属主 = 归档根的属主（从数据反推，见模块头注释）。

        优先级：环境变量 → `stat(归档根)` → 保底值。
        `stat` 失败（归档根还没建 / 盘没挂）时**不猜**，用保底值 ——
        此时反正也没有文件要 chown。
        """
        u, g = os.environ.get(ENV_OWNER_UID), os.environ.get(ENV_OWNER_GID)
        if u and g:
            try:
                return int(u), int(g)
            except ValueError:
                self.notes.append("%s/%s 不是整数，忽略" % (ENV_OWNER_UID, ENV_OWNER_GID))
        try:
            st = os.stat(self.archive)
            return st.st_uid, st.st_gid
        except OSError:
            return LEGACY_OWNER

    # -------------------------------------------------- 对外

    @property
    def archive_exists(self):
        return os.path.isdir(self.archive)

    @property
    def level(self):
        """
        取值来源，用于日志与 doctor：
          `env`    —— 壳注入了（目标形态 ✅）
          `legacy` —— 回落值（**过渡形态**，换宿主要当心）
        """
        if os.environ.get(ENV_ARCHIVE):
            return "env"
        return "legacy"

    def can_convert(self, kind):
        """该类型能否做缩略图（缺工具时如实降级，不假装成功）。"""
        if kind == "image":
            return bool(self.convert)
        if kind == "video":
            return bool(self.ffmpeg)
        return False

    def describe(self):
        return {
            "archive": self.archive,
            "cache": self.cache,
            "snap_base": self.snap_base,
            "owner": "%d:%d" % (self.owner_uid, self.owner_gid),
            "dir_mode": oct(self.dir_mode),
            "file_mode": oct(self.file_mode),
            "convert": self.convert or "(缺失)",
            "ffmpeg": self.ffmpeg or "(缺失)",
            "level": self.level,
        }


_CACHED = None


def load(refresh=False):
    """进程内取一次；`refresh=True` 用于调试与 doctor。"""
    global _CACHED
    if _CACHED is None or refresh:
        _CACHED = Config()
    return _CACHED


def core_dir():
    """核心目录（壳用来定位 np_core / np_config）。"""
    return _env(ENV_CORE_DIR, DEFAULT_CORE_DIR)


def ensure_core_on_path():
    """
    把核心目录塞进 `sys.path`，让壳能 `import np_config` / `import np_core`。

    壳自己调一次即可（proxy / snap_guard 在文件头调用）。
    `np_core.py` 以脚本方式直接跑时不需要 —— 那会儿 `sys.path[0]` 已经是核心目录。
    """
    import sys
    d = core_dir()
    if d and d not in sys.path and os.path.isdir(d):
        sys.path.insert(0, d)
    return d


def has_snapshot_marker(path):
    """`path` 看起来像 NasPhoto 归档根吗（供 np_doctor 探测用）。"""
    try:
        return _stat.S_ISDIR(os.stat(os.path.join(path, TRASH_DIRNAME)).st_mode)
    except OSError:
        return False
