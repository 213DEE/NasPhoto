#!/bin/sh
#
# nasphoto.cgi —— NasPhoto 小米插件壳
#
# ## 这个文件现在只做两件事
#
#   ① **静态文件**：把 index.html / 图标 / css 递给 plugin.cgi 的转发者
#   ② **API 转发**：`exec` 到核心 `np_core.py --cgi`，自己不实现任何业务
#
# 业务逻辑（list / thumb / restore / purge / config / capability）**全部在核心**，
# 见 `<核心目录>/np_core.py`。289 行 shell 的业务实现已于 2026-09-19 提出去（`N3`）。
#
# ## 为什么还留着 shell
#
# plugin.cgi 执行插件脚本走的是 exec + shebang。原壳是 `#!/bin/sh`，实测可用；
# 「Python shebang 能否被它 exec」是**推断未实测**（架构文档附录 A）。
# 这个壳只是一层转发，没有理由为此冒险 ⇒ 保持 `#!/bin/sh`。
# 换宿主时（Docker / 裸装）根本不用这个文件，那是另一种壳。
#
# ## 入口约定（由 plugin.cgi 转发）
#
#   任何 /133630987/nasphoto/{file} 都会执行本脚本，靠环境变量区分：
#     FILE_URI      —— 请求的目标（`api.cgi?...` 表示走 API）
#     FILE_REQ      —— 本地静态文件路径
#     QUERY_STRING  —— 查询串
#     REQUEST_METHOD
#
# ## 数据契约（App 与插件共用，勿单方面改）
#
#   归档根 /_回收站/<YYYY-MM-DD>/<原相对路径>
#     · 日期目录 = 删除日期，剥掉它即得原位 → 还原就是一次 rename
#     · 路径里带 `_` 前缀的目录不参与 App 的 NAS→手机 扫描
#   归档根 /_回收站/.trash_config.json    {"keepDays":7,"updatedAt":...}
#
set -u

# plugin.cgi 转发过来时这些一定存在，但直接调试时可能没有，给个默认值免得 `set -u` 报错
QUERY_STRING="${QUERY_STRING:-}"
REQUEST_METHOD="${REQUEST_METHOD:-GET}"
FILE_URI="${FILE_URI:-}"
FILE_REQ="${FILE_REQ:-}"

# 核心目录：壳通过 NP_CORE_DIR 指路（Docker 挂载点不同时改这个）
CORE_DIR="${NP_CORE_DIR:-/data/nasphoto/core}"

file_uri="$FILE_URI"
case "$file_uri" in *\?*) file_uri="${file_uri%%\?*}" ;; esac

# ---------------------------------------------------------------- API → 核心
if [ "$file_uri" = "api.cgi" ]; then
    # 找 python3：不依赖 $PATH —— 不同转发路径下 $PATH 未必一致
    PY="${NP_PYTHON:-}"
    if [ -z "$PY" ]; then
        for p in /usr/bin/python3 /usr/local/bin/python3 /bin/python3; do
            if [ -x "$p" ]; then PY="$p"; break; fi
        done
    fi
    if [ -z "$PY" ]; then
        PY="$(command -v python3 2>/dev/null || true)"
    fi

    if [ -z "$PY" ] || [ ! -f "$CORE_DIR/np_core.py" ]; then
        # ⚠️ 必须照常打头输出 Content-type：代理靠「空行」找头/体分界，
        #    缺了它整个 body 会被当成响应头，表现为「页面什么都看不到、也查不出原因」。
        printf 'Content-type: application/json\r\n\n'
        printf '{"error":"core not found","coreDir":"%s"}' "$CORE_DIR"
        exit 0
    fi

    # `exec` 让核心直接接管本进程的 stdin/stdout —— 中间不再套一层，
    # 也就不会有「多一层转义 = 多一处静默出错」的机会。
    exec "$PY" "$CORE_DIR/np_core.py" --cgi
fi

# ---------------------------------------------------------------- 静态文件（壳职责）
file_req="$FILE_REQ"
case "$file_req" in *\?*) file_req="${file_req%%\?*}" ;; esac

if [ -f "$file_req" ]; then
    ext="${file_req##*.}"
    case "$ext" in
        jpg|jpeg) mime="image/jpeg" ;;
        png) mime="image/png" ;;
        ico) mime="image/x-icon" ;;
        css) mime="text/css" ;;
        js) mime="application/javascript" ;;
        html|htm) mime="text/html; charset=utf-8" ;;
        json) mime="application/json" ;;
        txt) mime="text/plain" ;;
        *) mime="application/octet-stream" ;;
    esac
    echo -e "Content-type: $mime\r\n"
    cat "$file_req"
else
    echo -e "Status: 404 Not Found\r\nContent-type: text/html\r\n\r\n"
    echo "<html><body><h1>404 Not Found</h1></body></html>"
fi
