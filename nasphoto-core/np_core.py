#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""np_core.py —— NasPhoto 回收站核心（业务逻辑，宿主无关）

## 定位

**核心 = 换一台宿主，一个字节都不用改的部分。** 本文件不含：
路径常量 · 端口 · uid · 进程托管 · 框架名 · 任何 HTTP 服务器。
配置一律从 `np_config.load()` 取（壳通过 `NP_*` 注入）。

## 数据契约（与 App 共用，勿单方面改）

    归档根 /_回收站/<YYYY-MM-DD>/<原相对路径>
      · 日期目录 = 删除日期，剥掉它即得原位 → 还原就是一次 rename
      · `_` 前缀目录不参与 App 的 NAS→手机 扫描
    归档根 /_回收站/.trash_config.json    {"keepDays":7,"updatedAt":...}

## 调用方式

    # Python 壳（proxy / Docker / 裸装）
    from np_core import dispatch
    reply = dispatch(cfg, query=b"action=list", method="GET", body=b"")

    # CGI 壳（nasphoto.cgi = `#!/bin/sh` + exec 本文件）
    python3 np_core.py --cgi        # 从 QUERY_STRING / REQUEST_METHOD / stdin 读输入

## ⚠️ 本文件是「重写」不是「重构」

下列行为逐条对齐自原 `nasphoto.cgi`（269 行 shell），**每一条都是踩坑换来的**，
改任何一条之前先想清楚后果：

| 行为 | 漏掉的后果 |
|---|---|
| `restore`/`purge` 的 body 按行切分，**末行没有换行符也要处理** | 最后一条记录被静默丢掉 |
| 缩略图临时文件必须带**正确扩展名**（`.tmp.<pid>.jpg`） | `convert`/`ffmpeg` **静默失败**，表现为「缩略图没了」 |
| 缩略图缓存 key = `md5(rel 的原始字节)`，**不带换行** | 缓存永远不命中 |
| `restore` 撞名时**加后缀**而不是 `mv -f` 覆盖 | 用一次恢复毁掉一份新备份落地的同名文件 |
| 每行 rel 要先 `tr -d '\r'` | CRLF 客户端（Windows/部分 H5）全部失败 |
| CGI 模式必须打头输出 `Content-type` | 代理找不到头/体分界，整个 body 被当响应头 |

### 有意为之的两处「行为变更」（不是回归，见架构文档 §12.3）

1. **扩展名匹配改为大小写不敏感**：原 shell 的 `case "$1" in *.jpg|...)` 区分大小写，
   导致 `.HEIC`/`.JPG`/`.MOV` 全被判成 `other`。实测归档里 **61 个**、
   回收站里 **3 个**文件命中这个缺陷。
   ⚠️ **缩略图字节不变**（实测 `convert` 无 HEIC decode delegate ⇒ 两种分类都落到灰底兜底）。
2. **CGI 错误响应体不再带前导换行**：原 shell `echo -e "...\r\n\r\n"` 之后
   还会多输出一个裸 `\n`，使 body 以 `\n` 开头。属笔误，去掉。
"""

import hashlib
import json
import os
import re
import shutil
import stat as _stat
import subprocess
import sys
import time

import np_config
from np_config import (CONFIG_NAME, DEFAULT_KEEP_DAYS, TRASH_DIRNAME,  # noqa: F401
                       KEEP_DAYS_MAX, KEEP_DAYS_MIN)

# ---------------------------------------------------------------- 分类

IMAGE_EXT = frozenset(
    "jpg jpeg png webp gif bmp heic heif avif".split())
VIDEO_EXT = frozenset(
    "mp4 mov mkv avi 3gp webm m4v".split())

# 缩略图参数（原 shell 里写死的常量，属核心：与宿主无关）
THUMB_SIZE = 360
THUMB_QUALITY = 82
THUMB_BG = "#2a2a2e"
THUMB_FG = "#7a7a85"
THUMB_LABEL_POINTSIZE = 40
VIDEO_SEEK = "1"                     # 抽帧位置（秒）


def kind_of(name):
    """
    按扩展名分类。**大小写不敏感**（见模块头「有意为之的行为变更」第 1 条）。

    原 shell 版区分大小写：`.HEIC` → `other`。实测归档里 61 个大写扩展名文件，
    回收站里 3 个 —— 这是真缺陷，不是风格问题。
    """
    i = name.rfind(".")
    if i < 0:
        return "other"
    ext = name[i + 1:].lower()
    if ext in IMAGE_EXT:
        return "image"
    if ext in VIDEO_EXT:
        return "video"
    return "other"


# ---------------------------------------------------------------- Reply

class Reply(object):
    """
    一次调用的结果。**不是 HTTP 对象** —— 它只是「状态 + 内容类型 + 字节」，
    由各壳自行序列化：

      · CGI 壳（`nasphoto.cgi`）→ 见文件末尾 `to_cgi()`
      · HTTP 壳（`nasphoto_proxy.py`）→ 直接写进响应
      · Docker / 裸装壳 → 同上
    """

    __slots__ = ("status", "headers", "body")

    def __init__(self, body=b"", status=200, ctype="application/json",
                 extra=()):
        self.status = status
        self.headers = [("Content-type", ctype)] + list(extra)
        self.body = body


def json_reply(obj, ctype="application/json"):
    # ⚠️ `separators` 必须显式给：默认会插空格，与原 shell 的 printf 输出不一致
    body = json.dumps(obj, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    return Reply(body=body, ctype=ctype)


def text_reply(text, status, ctype="text/plain"):
    return Reply(body=(text + "\n").encode("utf-8"), status=status, ctype=ctype)


# ---------------------------------------------------------------- 查询串

def _unquote(raw):
    """
    还原 shell 的 `urldecode`：**先** `+`→空格，**再** 百分号解码。

    顺序不能反：`%2B` 该还原成字面 `+`（因为 `+`→空格 已经发生过了），
    反过来会把 `%2B` 变成空格。返回 bytes —— 文件名可能是任意字节，
    不能假设 UTF-8（后续用 `surrogateescape` 交给文件系统）。
    """
    from urllib.parse import unquote_to_bytes
    return unquote_to_bytes(raw.replace(b"+", b" "))


def parse_params(query):
    """`name=value&…` → {name: bytes}。**同名取第一个**（对齐 shell 的 `head -1`）。"""
    out = {}
    for part in query.split(b"&"):
        if b"=" in part:
            k, v = part.split(b"=", 1)
            out.setdefault(k, v)
    return out


def _param(params, name):
    """
    取一个已解码的参数（bytes → str，用 surrogateescape 保真）。

    ⚠️ `parse_params` 的键是 **bytes**（查询串在解码前就是字节）——
       传 str 名字进来必须转一下，否则 `dict.get` 恒为 None，
       表现为**所有 action 都变成 unknown**（这个 bug 真发生过一次）。
    """
    key = name.encode("ascii") if isinstance(name, str) else name
    raw = params.get(key)
    if raw is None:
        return ""
    return _unquote(raw).decode("utf-8", "surrogateescape")


def _qbytes():
    """环境变量里的 `QUERY_STRING` 的原始字节。"""
    try:
        return os.environb.get(b"QUERY_STRING") or b""
    except AttributeError:                      # 极少数平台没有 environb
        return (os.environ.get("QUERY_STRING") or "").encode("utf-8", "surrogateescape")


# ---------------------------------------------------------------- 路径安全

def safe_rel(rel):
    """
    拒绝路径穿越：rel 必须是 TRASH 内的相对路径。

    原 shell 是三条 `case` 判断，逐条保留语义（含「空串拒绝」）。
    ⚠️ 这是**用户可控输入**的唯一边界，改动前先想清楚。
    """
    if rel == "":
        return False
    if rel.startswith("/"):
        return False
    if ".." in rel:
        return False
    return True


def _fs(rel):
    """
    路径片段。**刻意是个恒等函数** —— 它标出「这个字符串可能含 surrogateescape
    字节（来自 URL 解码的非 UTF-8 文件名）」，而 Python 的 `os.*` 本来就能直接吃
    这种字符串。留着它是为了在调用点一眼看出哪些路径是**用户可控**的。
    """
    return rel


# ---------------------------------------------------------------- 保留天数

def keep_days(cfg):
    """
    读 `.trash_config.json` 的 `keepDays`；读不到就回默认值。

    与原 shell 的 `sed -n 's/.*"keepDays"...'` 等价，但**逐条列出**：
      · 文件不存在 → 默认
      · 没有 keepDays 字段 → 默认
      · 值不是数字 → 默认
    """
    try:
        with open(cfg.cfg_path, encoding="utf-8", errors="replace") as f:
            raw = f.read()
    except OSError:
        return DEFAULT_KEEP_DAYS
    m = re.search(r'"keepDays"\s*:\s*([0-9]+)', raw)
    return int(m.group(1)) if m else DEFAULT_KEEP_DAYS


def _atomic_write(path, data):
    """先写 `.tmp` 再 replace —— 避免半截文件被读到（原 shell 的 `>` 是覆盖写）。"""
    tmp = path + ".tmp"
    with open(tmp, "wb") as f:
        f.write(data)
    os.replace(tmp, path)


def set_keep_days(cfg, body):
    """
    从 POST body 里提出保留天数并落盘。

    原 shell：`tr -dc '0-9' | head -c 4`（只留数字、最多 4 位），空则 7，夹到 1..3650。
    ✅ **顺带修掉一个潜在缺陷**：原版把 `0030` 原样写进 JSON（`{"keepDays":0030}`），
       而 `0030` 是**非法 JSON**（前导零）—— 前端 `JSON.parse` 会炸。
       UI 只发规范数字所以没被触发过；这里统一 `int()` 归一化。
    """
    digits = re.sub(rb"[^0-9]", b"", body)[:4]
    v = int(digits) if digits else DEFAULT_KEEP_DAYS
    v = max(KEEP_DAYS_MIN, min(KEEP_DAYS_MAX, v))
    try:
        os.makedirs(cfg.trash, exist_ok=True)
        _atomic_write(cfg.cfg_path,
                      ('{"keepDays":%d,"updatedAt":%d}' % (v, int(time.time()))).encode())
    except OSError as e:
        _log(cfg, "写 %s 失败：%r" % (cfg.cfg_path, e))
    return v


# ---------------------------------------------------------------- 遍历回收站

def _iter_trash_files(trash):
    """
    产出 `(date_dir, rel, abspath)`。

    对齐 `find "$TRASH" -mindepth 2 -type f`：
      · mindepth 2 ⇒ 跳过直接挂在回收站根下的文件（没有日期层，剥不出原位）
      · `-type f`  ⇒ **只认常规文件**，符号链接 / FIFO 都不算（用 lstat 判断）
    """
    try:
        root = os.fsdecode(trash)
        for dirpath, dirnames, filenames in os.walk(root):
            rel_dir = os.path.relpath(dirpath, root)
            if rel_dir == ".":
                rel_dir = ""
            if rel_dir == "":
                # 深度 1 ⇒ 不满足 mindepth 2
                for fn in filenames:
                    pass                                    # 明确忽略，勿"优化"掉
                continue
            for fn in filenames:
                full = os.path.join(dirpath, fn)
                try:
                    if not _stat.S_ISREG(os.lstat(full).st_mode):
                        continue
                except OSError:
                    continue
                rel = (rel_dir + "/" + fn) if rel_dir else fn
                yield rel.split("/", 1)[0], rel, full
    except OSError as e:
        _log(None, "遍历回收站失败：%r" % (e,))


def _sort_key(item):
    """稳定排序：原 shell 是 readdir 顺序（不确定）。这里按 (日期, 相对路径)。"""
    return (item["date"], item["rel"])


# ---------------------------------------------------------------- action: list

def action_list(cfg, now=None):
    now = int(time.time()) if now is None else int(now)
    kd = keep_days(cfg)

    items = []
    for date, rel, full in _iter_trash_files(cfg.trash):
        orig = rel.split("/", 1)[1] if "/" in rel else rel
        name = orig.rsplit("/", 1)[-1]
        # 跳过隐藏文件（`.trash_config.json` 之类）
        if name.startswith("."):
            continue

        delts = _date_to_ts(date)
        if delts is None:
            delts = now                                   # 目录名不是日期 → 当作今天删的
        # 原式：((delts + kd*86400 - now + 86399) / 86400)，shell 是截断除法；
        # Python `//` 对正数一致，负数会被夹到 0，结果相同。
        left = (delts + kd * 86400 - now + 86399) // 86400
        if left < 0:
            left = 0

        try:
            size = os.path.getsize(full)
        except OSError:
            size = 0

        items.append({
            "rel": rel,
            "name": name,
            "orig": orig,
            "date": date,
            "size": size,
            "left": left,
            "kind": kind_of(name),
        })

    items.sort(key=_sort_key)
    return json_reply({"keepDays": kd, "now": now, "items": items},
                      ctype="application/json; charset=utf-8")


def _date_to_ts(s):
    """`YYYY-MM-DD` → 本地零点时间戳；不是日期则 None。"""
    try:
        return int(time.mktime(time.strptime(s, "%Y-%m-%d")))
    except (ValueError, OverflowError):
        return None


# ---------------------------------------------------------------- action: thumb

def cache_key(rel):
    """
    缓存 key = `md5(rel 的原始字节)`。

    ⚠️ 原 shell 是 `printf '%s' "$rel" | md5sum` —— **不留换行**。
    加一个 `\\n` 会让所有历史缓存全部失效（表现为"缓存好像没生效"）。
    基线工具 `np_baseline.py` 也算同一个 key，改这里要同步改它。
    """
    return hashlib.md5(rel.encode("utf-8", "surrogateescape")).hexdigest()


def _run(cmd, timeout=180):
    try:
        p = subprocess.run(cmd, capture_output=True, timeout=timeout)
        return p.returncode, (p.stderr or b"").decode("utf-8", "replace")
    except subprocess.TimeoutExpired:
        return 124, "timeout after %ss" % timeout
    except OSError as e:
        return 125, "exec failed: %r" % (e,)


def _make_thumb(cfg, full, kind, tmp):
    """
    生成缩略图到 `tmp`。返回 (ok, note)。

    ⚠️ `tmp` 必须以 `.jpg` 结尾 —— `convert` / `ffmpeg` 都靠**后缀**推断输出格式，
    用 `...jpg.<pid>` 会让 ffmpeg 直接失败，而且是**静默**失败（stderr 被吞）。
    """
    f = _fs(full)

    if kind == "image" and cfg.convert:
        rc, err = _run([cfg.convert, f + "[0]", "-auto-orient",
                        "-thumbnail", "%dx%d^" % (THUMB_SIZE, THUMB_SIZE),
                        "-gravity", "center",
                        "-extent", "%dx%d" % (THUMB_SIZE, THUMB_SIZE),
                        "-strip", "-quality", str(THUMB_QUALITY), tmp])
        if rc != 0:
            return False, "convert rc=%d %s" % (rc, err.strip()[:160])

    elif kind == "video" and cfg.ffmpeg:
        vf = ("scale=%d:%d:force_original_aspect_ratio=decrease,"
              "pad=%d:%d:(ow-iw)/2:(oh-ih)/2:color=0x2a2a2e"
              % (THUMB_SIZE, THUMB_SIZE, THUMB_SIZE, THUMB_SIZE))
        rc, err = _run([cfg.ffmpeg, "-v", "error", "-ss", VIDEO_SEEK, "-i", f,
                        "-frames:v", "1", "-vf", vf, "-y", tmp])
        if rc != 0:
            return False, "ffmpeg rc=%d %s" % (rc, err.strip()[:160])

    else:
        # 工具缺失（已由 cfg.notes 报出）或类型无法处理 → 直接落占位图
        return False, "no tool for kind=%s" % kind

    if os.path.isfile(tmp) and os.path.getsize(tmp) > 0:
        return True, "ok"
    return False, "empty output"


def _placeholder(cfg, ext, tmp):
    """
    兜底灰底占位图：生成不了的（HEIC/杜比/工具缺失）给一张，
    前端至少能看到「有这个文件」。

    ⚠️ 实测 `convert` 在这台机器上**报 `unable to read font`**，
    所以底图是纯灰块、没有文字 —— 与原 shell 版**完全一致**（md5 逐字节相同）。
    保留 `-annotate` 是为了字体可用的机器上能显示扩展名。
    """
    if not cfg.convert:
        return False
    label = (ext or "").upper()[:8]
    rc, _err = _run([cfg.convert, "-size", "%dx%d" % (THUMB_SIZE, THUMB_SIZE),
                     "xc:" + THUMB_BG, "-gravity", "center",
                     "-pointsize", str(THUMB_LABEL_POINTSIZE),
                     "-fill", THUMB_FG, "-annotate", "0", label, tmp])
    return rc == 0 and os.path.isfile(tmp) and os.path.getsize(tmp) > 0


def action_thumb(cfg, params):
    rel = _param(params, "rel")
    if not safe_rel(rel):
        return text_reply("bad rel", 400)

    full = os.path.join(_fs(cfg.trash), _fs(rel))
    if not os.path.isfile(full):
        return text_reply("not found", 404)

    try:
        os.makedirs(cfg.cache, exist_ok=True)
    except OSError as e:
        _log(cfg, "建缓存目录失败：%r" % (e,))

    cf = os.path.join(cfg.cache, cache_key(rel) + ".jpg")
    if not os.path.isfile(cf):
        # ⚠️ 扩展名必须在最后（见 _make_thumb 注释）
        tmp = os.path.join(cfg.cache, ".tmp.%d.jpg" % os.getpid())
        ext = rel.rsplit(".", 1)[-1] if "." in rel else ""
        kind = kind_of(rel)
        ok, note = _make_thumb(cfg, full, kind, tmp)
        if not ok:
            if kind in ("image", "video"):
                _log(cfg, "缩略图降级为占位图 %s：%s" % (rel, note))
            _placeholder(cfg, ext, tmp)
        if os.path.isfile(tmp) and os.path.getsize(tmp) > 0:
            try:
                os.replace(tmp, cf)
            except OSError as e:
                _log(cfg, "写缓存失败 %s：%r" % (cf, e))
        try:
            os.unlink(tmp)
        except OSError:
            pass

    if os.path.isfile(cf):
        with open(cf, "rb") as f:
            body = f.read()
        return Reply(body=body, ctype="image/jpeg",
                     extra=[("Cache-Control", "max-age=86400")])
    return text_reply("thumb failed", 500)


# ---------------------------------------------------------------- action: restore / purge

def _split_lines(body):
    """
    body 按行切分，**末行没有换行符也要保留**。

    ⚠️ 这是原 shell 里那条 `while IFS= read -r rel || [ -n "$rel" ]` 的等价物。
    漏掉它 ⇒ 前端 `join('\\n')` 送来的**最后一条记录被静默丢掉**。
    """
    parts = body.split(b"\n")
    if parts and parts[-1] == b"":
        parts.pop()                     # 结尾的换行符不产生空记录
    return parts


def _clean_lines(body):
    """产出清洗过的 rel（str）。对齐 shell 的 `tr -d '\\r'` + 空行跳过。"""
    for raw in _split_lines(body):
        rel = raw.replace(b"\r", b"").decode("utf-8", "surrogateescape")
        if rel == "":
            continue
        yield rel


def _remove_empty_date_dirs(trash):
    """清掉空的日期目录（原 shell 用 `find -maxdepth 1 -type d -empty -delete`）。"""
    try:
        names = os.listdir(trash)
    except OSError:
        return
    for n in names:
        p = os.path.join(trash, n)
        try:
            if _stat.S_ISDIR(os.lstat(p).st_mode) and not os.listdir(p):
                os.rmdir(p)
        except OSError:
            pass


def _collision_name(orig, stamp):
    """
    目标位置已有同名文件时，给**恢复的**文件加后缀。

    ⚠️ 不能直接 `mv -f` 覆盖：删除之后可能又备份了同名的新文件，
    直接覆盖等于**用一次恢复毁掉一份新数据**。两边都保住。
    逐条对齐 shell 的 `${bn%.*}` / `${bn##*.}` 语义（含 `".bashrc"` 这种边角）。
    """
    if "/" in orig:
        od, bn = orig.rsplit("/", 1)
    else:
        od, bn = "", orig
    i = bn.rfind(".")
    base = bn[:i] if i >= 0 else bn
    ext = bn[i + 1:] if i >= 0 else bn
    if base == bn:
        nb = "%s_还原_%s" % (bn, stamp)
    else:
        nb = "%s_还原_%s.%s" % (base, stamp, ext)
    return (od + "/" + nb) if od else nb


def _move(src, dst):
    """先试 rename（同文件系统、原子），跨设备再退回复制 + 删除。"""
    try:
        os.replace(src, dst)
        return True
    except OSError:
        pass
    try:
        shutil.move(src, dst)
        return True
    except (OSError, shutil.Error):
        return False


def _stamp():
    return time.strftime("%Y%m%d-%H%M%S")


def action_restore(cfg, body):
    ok = bad = 0
    stamp = _stamp()
    for rel in _clean_lines(body):
        if not safe_rel(rel):
            bad += 1
            continue
        orig = rel.split("/", 1)[1] if "/" in rel else rel
        src = os.path.join(_fs(cfg.trash), _fs(rel))
        dst = os.path.join(_fs(cfg.archive), _fs(orig))
        if not os.path.isfile(src):
            bad += 1
            continue
        try:
            os.makedirs(os.path.dirname(dst) or ".", exist_ok=True)
        except OSError:
            pass
        if os.path.exists(dst):
            dst = os.path.join(_fs(cfg.archive), _fs(_collision_name(orig, stamp)))
        if _move(src, dst):
            ok += 1
        else:
            bad += 1
    _remove_empty_date_dirs(cfg.trash)
    return json_reply({"ok": ok, "failed": bad})


def action_purge(cfg, body):
    ok = bad = 0
    for rel in _clean_lines(body):
        if not safe_rel(rel):
            bad += 1
            continue
        f = os.path.join(_fs(cfg.trash), _fs(rel))
        if not os.path.isfile(f):
            bad += 1
            continue
        # 顺手把缓存里的缩略图删掉（否则缓存会随回收站一起无限长大）
        try:
            os.unlink(os.path.join(cfg.cache, cache_key(rel) + ".jpg"))
        except OSError:
            pass
        try:
            os.unlink(f)
            ok += 1
        except OSError:
            bad += 1
    _remove_empty_date_dirs(cfg.trash)
    return json_reply({"ok": ok, "failed": bad})


# ---------------------------------------------------------------- action: config

def action_config(cfg, method, body):
    if method == "POST":
        set_keep_days(cfg, body)
    return json_reply({"keepDays": keep_days(cfg)})


# ---------------------------------------------------------------- action: capability

CAPABILITY_NAME = "capability.json"


def action_capability(cfg):
    """
    后端能力（供前端显示「降级提示」）。

    ⚠️ 存在理由：**降级必须可见**。快照兜底不可用时如果只写日志，
    用户会一直以为「电脑端删除也能恢复」—— 那比没有这个功能更危险。
    见架构文档 §6.3。
    """
    path = os.path.join(cfg.snap_base, CAPABILITY_NAME)
    try:
        with open(path, encoding="utf-8") as f:
            return json_reply(json.load(f))
    except (OSError, ValueError):
        # 状态文件还没生成（snap_guard 没跑过）⇒ 「未知」，不是「不可用」。
        # 前端只在明确 `available:false` 时报警，避免误报。
        return json_reply({"available": None, "backend": "unknown",
                           "reason": "snap_guard 尚未报告能力（可能未部署）"})


# ---------------------------------------------------------------- 分发

ACTIONS = ("list", "thumb", "restore", "purge", "config", "capability")


def dispatch(cfg, query=b"", method="GET", body=b""):
    """把一次请求变成 `Reply`。壳只需提供「查询串 / 方法 / body」三样东西。"""
    params = parse_params(query)
    action = _param(params, "action") if b"action=" in query else ""

    if action == "list":
        return action_list(cfg)
    if action == "thumb":
        return action_thumb(cfg, params)
    if action == "restore":
        return action_restore(cfg, body)
    if action == "purge":
        return action_purge(cfg, body)
    if action == "config":
        return action_config(cfg, method, body)
    if action == "capability":
        return action_capability(cfg)
    return json_reply({"error": "unknown action", "action": action})


# ---------------------------------------------------------------- 日志

LOG_MAX = 256 * 1024


def _log(cfg, msg):
    """
    核心日志。走 `NP_LOG`（壳注入），没给就静默 —— 核心不该自己发明路径。
    ⚠️ 但**降级必须可见**这条要求：调用方（壳）应把 `cfg.notes` 与降级信息
       也接到自己的日志里（proxy 已经这么做）。
    """
    path = os.environ.get("NP_LOG") or ""
    if not path:
        return
    try:
        if os.path.exists(path) and os.path.getsize(path) > LOG_MAX:
            os.replace(path, path + ".1")
        with open(path, "a", encoding="utf-8") as f:
            f.write("[%s] core %s\n" % (time.strftime("%Y-%m-%d %H:%M:%S"), msg))
    except OSError:
        pass


# ================================================================ 适配层
#
# ⚠️ 下面**不是核心**，是「CGI 壳」的序列化适配 —— 只有 `nasphoto.cgi`
#    （小米插件壳）会用到。Docker / 裸装 / HTTP 壳**完全不用**这段。
#    刻意放在文件末尾并用这条注释隔开，免得被当成核心逻辑去改。

def to_cgi(reply):
    """
    `Reply` → CGI 标准输出的字节。

    逐字节对齐原 shell 的形态（`echo -e "H: v\\r\\n"` + `\\n` + body）：
      · **非 200 时先输出一行 `Status: <码> <原因>`** —— CGI 消费者（含本项目
        自己的 `nasphoto_proxy.py`）就是靠这一行取 HTTP 状态码的，
        漏了它所有错误响应都会变成 200（⚠️ 这个 bug 真漏过一次）
      · 头行以 `\\r\\n` 分隔，最后一行后面也有 `\\r\\n`
      · 之后是一个裸 `\\n`（shell 里 `echo` 自带的那一个）
      · 非 200 时头部分多一个空行（原 shell 写的是 `\\r\\n\\r\\n`）

    ⚠️ 这个「多一个裸 \\n」看着别扭，但**必须保留**：`split_cgi()` 依赖它找
       头/体分界。去掉就得同时改代理的解析。
    """
    out = bytearray()
    if reply.status != 200:
        try:
            from http import HTTPStatus
            phrase = HTTPStatus(reply.status).phrase
        except ValueError:
            phrase = ""
        out += ("Status: %d %s\r\n" % (reply.status, phrase)).encode("utf-8")
    for k, v in reply.headers:
        out += ("%s: %s\r\n" % (k, v)).encode("utf-8")
    if reply.status != 200:
        out += b"\r\n"
    out += b"\n"
    out += reply.body
    return bytes(out)


def main(argv):
    if "--cgi" not in argv:
        sys.stderr.write(
            "np_core 是模块，不是命令行工具。\n"
            "  CGI 壳用法：python3 np_core.py --cgi\n"
            "  配置探测：  python3 np_doctor.py\n")
        return 2

    cfg = np_config.load()
    for n in cfg.notes:
        _log(cfg, "config: " + n)

    method = os.environ.get("REQUEST_METHOD") or "GET"
    body = sys.stdin.buffer.read() if method == "POST" else b""
    reply = dispatch(cfg, query=_qbytes(), method=method, body=body)

    sys.stdout.buffer.write(to_cgi(reply))
    sys.stdout.buffer.flush()
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
