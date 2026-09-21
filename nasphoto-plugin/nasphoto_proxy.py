#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
NasPhoto 回收站 —— API 本地代理（默认 127.0.0.1:19996）

## 为什么需要这个进程

NAS 的 `/plugin` 通用分支走 `fcgi-cgi` + `/usr/bin/plugin.cgi`，**那一层会做认证**：
浏览器/电脑直连 `.../nasphoto/api.cgi` 得到的是 `403 Forbidden`
（前端 `JSON.parse("403 Forbidden")` 会报 "Unexpected non-whitespace character
after JSON at position 4"，也就是"读取失败"）。

这台 NAS 上所有自定义插件都靠同一招绕开：**一条本地小 HTTP 服务 + 一条 nginx
`location` 劫持**（见 /etc/nginx/conf.d/luci/00-custom-plugins.conf）：

| 插件      | 端口  | 进程                        |
|-----------|-------|-----------------------------|
| sysctl    | 19997 | /data/sysctl/sysctl_proxy.py |
| clash     | 19998 | /data/clash/clash_proxy.py   |
| dockermgr | 19999 | /data/clash/docker_proxy.py  |
| nasphoto  | 19996 | 本文件                       |

## 设计原则（2026-09-19 `N3` 后更新）

**业务逻辑只保留一份 —— 在 `<核心目录>/np_core.py` 里。**
本进程是**传输层 / HTTP 壳**，只负责「HTTP 请求 → 核心调用 → HTTP 响应」。
两条入口共用同一份核心，不会有两份实现漂移：

| 入口 | 壳 | 怎么调核心 |
|---|---|---|
| 浏览器 / App H5（经 nginx 劫持） | 本进程 | **进程内直接 `import`**（默认） |
| App 内 via `plugin.cgi` | `nasphoto.cgi`（sh） | `exec np_core.py --cgi`（子进程） |

⚠️ 注意后两条入口都通向**同一个核心文件**。原版是「proxy 起子进程调 shell CGI」，
现在默认改成进程内调用 —— 省掉每次请求一次 Python 启动，也是 Docker 壳的前提
（容器里没有 nginx / fcgi-cgi，那条路压根不存在）。

`NP_ENGINE=subprocess` 可切回「调 CGI」的老形态，用于 A/B 对照或应急回退。

启动由 /data/clash/restore.sh 负责（开机自愈）。
"""

import http.server
import json
import os
import subprocess
import time
import urllib.parse

# ---------------------------------------------------------------- 核心目录

# 核心（np_config / np_core）由壳通过 NP_CORE_DIR 指路；默认安装点如下。
CORE_DIR = os.environ.get("NP_CORE_DIR") or "/data/nasphoto/core"
import sys                                          # noqa: E402

if os.path.isdir(CORE_DIR) and CORE_DIR not in sys.path:
    sys.path.insert(0, CORE_DIR)

import np_config                                    # noqa: E402
import np_core                                      # noqa: E402

# ---------------------------------------------------------------- 配置（由「壳」注入）
#
# 本进程是**传输层**，不持有路径 / 端口 / 身份常量：
# 壳通过环境变量注入，未注入时回落到本机现值（去写死化的行为零变化保证）。
#
# ⚠️ 回落值不是目标形态，只是过渡。目标形态下三种壳各自注入：
#    Docker 壳 → docker run --network host -e NP_PORT=… -e NP_CORE_DIR=…
#    裸装壳    → systemd unit 的 Environment=
#    小米壳    → restore.sh 里的导出
# ⇒ 见《核心壳分离架构》§3.1 第 3~5 处、§5 四壳矩阵。
#
# ⚠️⚠️ 实测（2026-09-18）：本文件部署在 `/data/clash/nasphoto_proxy.py`，而 CGI 与
#    proxy.log 在 `/data/clash/plugins/nasphoto/` —— **两者不同目录**。
#    ⇒ 不要用 `os.path.dirname(__file__)` 去推 CGI 路径，推出来是 `/data/clash/nasphoto.cgi`，
#      服务会直接起不来。**这条是踩过的坑，别再"优化"回去。**
_base = os.environ.get("NP_HOME") or "/data/clash/plugins/nasphoto"

PORT = int(os.environ.get("NP_PORT") or 19996)

# 转发给 CGI 的环境变量：仅 `NP_ENGINE=subprocess` 时用到。
# 注：`nasphoto.cgi` 自身**并不消费** $USER（grep 全文无引用），它只是照着
# plugin.cgi 的转发环境模仿出来的。所以给一个 NP_USER 覆盖口、不额外做探测 ——
# `os.getuid()` 探到的是「**谁跑起了 proxy**」（实测是 root），
# 而不是「归档属于谁」（u133630987），两者不等价，猜错反而更糟。
USER = os.environ.get("NP_USER") or "u133630987"
URI_PREFIX = os.environ.get("NP_URI_PREFIX") or "/133630987/nasphoto"

# 调用引擎：`inline`（默认，进程内）| `subprocess`（起 CGI，老形态）
ENGINE = (os.environ.get("NP_ENGINE") or "inline").strip().lower()
CGI = os.environ.get("NP_CGI") or os.path.join(_base, "nasphoto.cgi")

# 子进程引擎下缩略图首次生成要跑 convert / ffmpeg，给足时间（命中缓存时是毫秒级）
TIMEOUT = 300

# 异常日志（正常情况下是空的；出问题时是排查的第一手材料）
LOG = os.environ.get("NP_LOG") or os.path.join(_base, "proxy.log")
LOG_MAX = 256 * 1024

# 让核心的日志落到同一个文件（核心只认 NP_LOG，不认本文件的 LOG 变量）
os.environ.setdefault("NP_LOG", LOG)

CFG = np_config.load()


def log(msg):
    try:
        if os.path.exists(LOG) and os.path.getsize(LOG) > LOG_MAX:
            os.replace(LOG, LOG + ".1")          # 滚动一份就够了
        with open(LOG, "a", encoding="utf-8") as f:
            f.write("[%s] %s\n" % (time.strftime("%Y-%m-%d %H:%M:%S"), msg))
    except Exception:
        pass


def split_cgi(raw):
    """
    把 CGI 输出拆成 (headers, body)。

    CGI 的头/体分隔符在不同写法下有三种形态，都要认：
      `\\r\\n\\r\\n`（标准）、`\\r\\n\\n`（shell 里 `echo -e "...\\r\\n"` 的产物）、`\\n\\n`。

    返回 (headers_bytes, status_int, content_type, body_bytes)。
    """
    best = None
    for sep in (b"\r\n\r\n", b"\r\n\n", b"\n\n"):
        i = raw.find(sep)
        if i >= 0 and (best is None or i < best[0]):
            best = (i, sep)

    if best is None:
        # 没有头 —— 不该发生。容错：整体当 body，按 JSON 处理并记一笔日志，
        # 免得一个手滑就变成"页面什么都看不到、也查不出原因"。
        log("CGI 输出缺少头部分隔符，已按 JSON 兜底（前 120 字节：%r）" % raw[:120])
        return b"", 200, "application/json; charset=utf-8", raw

    i, sep = best
    head = raw[:i].replace(b"\r\n", b"\n").decode("utf-8", "replace")
    body = raw[i + len(sep):]

    status = 200
    ctype = "application/octet-stream"
    for line in head.split("\n"):
        if ":" not in line:
            continue
        k, v = line.split(":", 1)
        k = k.strip().lower()
        v = v.strip()
        if k == "status":                       # 形如 "404 Not Found"
            tok = v.split(" ", 1)[0]
            if tok.isdigit():
                status = int(tok)
        elif k == "content-type":
            ctype = v
    return head.encode(), status, ctype, body


def _qbytes(path):
    """
    从请求行里取查询串的**原始字节**。

    `BaseHTTPRequestHandler` 用 iso-8859-1 解请求行 ⇒ 逐字节还原用 latin-1 编码
    才是无损的（用 utf-8 会把非 ASCII 的裸字节弄坏）。百分号编码本来就是 ASCII，
    两种情况都对。
    """
    qs = urllib.parse.urlparse(path).query
    try:
        return qs.encode("latin-1")
    except UnicodeEncodeError:
        return qs.encode("utf-8", "surrogateescape")


class Handler(http.server.BaseHTTPRequestHandler):

    server_version = "NasPhotoProxy/2.0"

    # ------------------------------------------------------------ 内部
    def _call_core(self, body):
        """进程内调核心。返回 (headers_bytes, status, ctype, body_bytes)。"""
        reply = np_core.dispatch(CFG, query=_qbytes(self.path),
                                 method=self.command, body=body)
        ctype = "application/octet-stream"
        for k, v in reply.headers:
            if k.lower() == "content-type":
                ctype = v
                break
        return b"", reply.status, ctype, reply.body

    def _call_subprocess(self, body):
        """老形态：起 `nasphoto.cgi` 子进程，解析它的 CGI 输出（A/B 对照用）。"""
        qs = urllib.parse.urlparse(self.path).query
        env = dict(os.environ)
        env.update({
            "REQUEST_METHOD": self.command,
            "QUERY_STRING": qs,
            "FILE_URI": "api.cgi?" + qs,
            "REQUEST_URI": URI_PREFIX + "/api.cgi?" + qs,
            "USER": USER,
            "CONTENT_TYPE": self.headers.get("Content-Type") or "text/plain;charset=utf-8",
            "CONTENT_LENGTH": str(len(body)),
            "NP_CORE_DIR": CORE_DIR,
        })
        try:
            p = subprocess.run(
                [CGI], input=body, capture_output=True, timeout=TIMEOUT, env=env
            )
        except subprocess.TimeoutExpired:
            log("CGI 超时（%ds）：%s?%s" % (TIMEOUT, self.command, qs))
            return (b"", 504, "application/json; charset=utf-8",
                    json.dumps({"ok": 0, "failed": 0, "error": "CGI 超时"}).encode())
        if p.stderr.strip():
            # shell 的告警/报错是最有用的线索，务必留下
            log("CGI stderr（%s?%s）：%s" % (self.command, qs,
                                            p.stderr.decode("utf-8", "replace")[:400]))
        return split_cgi(p.stdout)

    def _call(self, body):
        if ENGINE == "subprocess":
            return self._call_subprocess(body)
        try:
            return self._call_core(body)
        except Exception as e:                       # noqa: BLE001
            # 进程内调用的代价：核心抛异常会打到 HTTP 层。
            # 必须**兜住并如实报 500**，否则一个坏输入就能让整个服务不再响应。
            import traceback
            log("核心异常：%s?%s\n%s"
                % (self.command, urllib.parse.urlparse(self.path).query,
                   traceback.format_exc()))
            return (b"", 500, "application/json; charset=utf-8",
                    json.dumps({"ok": 0, "failed": 0, "error": repr(e)},
                               ensure_ascii=False).encode())

    def _respond(self, headers, status, ctype, body):
        try:
            self.send_response(status)
            self.send_header("Content-Type", ctype)
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            self.wfile.write(body)
        except (BrokenPipeError, ConnectionResetError):
            pass                                # 浏览器提前断开（切页/取消下载），不是错误

    # ------------------------------------------------------------ 路由
    def do_GET(self):
        body = b""
        self._respond(*self._call(body))

    def do_POST(self):
        n = int(self.headers.get("Content-Length") or 0)
        body = self.rfile.read(n) if n > 0 else b""
        self._respond(*self._call(body))

    def do_OPTIONS(self):
        self.send_response(204)
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type")
        self.end_headers()

    def log_message(self, *a):
        pass                                    # 别把访问日志刷到 stdout


def main():
    # 把「取值来源」记一笔：回落值意味着这台机器**还没被壳配置过**，
    # 换宿主时这会是最先要看的线索（但正常机器上不该刷屏，只记一次）。
    log("启动：engine=%s port=%d coreDir=%s backend=%s"
        % (ENGINE, PORT, CORE_DIR, getattr(np_config, "__file__", "?")))
    for n in CFG.notes:
        log("config: " + n)
    http.server.HTTPServer(("127.0.0.1", PORT), Handler).serve_forever()


if __name__ == "__main__":
    main()
