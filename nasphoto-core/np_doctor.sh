#!/bin/sh
#
# np_doctor.sh —— 配置发现的**壳包装**（把探测结果取出来给人 / 给别的壳用）
#
# ⚠️ 探测逻辑**不在这个文件里**，在 `np_doctor.py`。原因（架构文档 §4.3 的推论）：
#    把探测写成 shell，等于同一套规则有第二份实现 —— 两份一定会漂移，
#    而漂移的表现形式是「doctor 报的值 ≠ 运行时实际用的值」，最坏的那种静默失效。
#    所以这个壳只做一件事：**找到 python，然后 exec**。
#
# 用法：
#     np_doctor.sh                # 人类可读报告
#     np_doctor.sh --env          # 可直接 eval 的 NP_* 行
#     np_doctor.sh --json         # 机器可读
#
#   例（裸装壳的安装脚本）：
#     eval "$(/data/nasphoto/core/np_doctor.sh --env)"
#
set -u

CORE_DIR="${NP_CORE_DIR:-/data/nasphoto/core}"

# 找 python3：不依赖 $PATH —— systemd / 定时任务里的 $PATH 可能极简
PY="${NP_PYTHON:-}"
if [ -z "$PY" ]; then
    for p in /usr/bin/python3 /usr/local/bin/python3 /bin/python3; do
        if [ -x "$p" ]; then PY="$p"; break; fi
    done
fi
if [ -z "$PY" ]; then
    PY="$(command -v python3 2>/dev/null || true)"
fi
if [ -z "$PY" ]; then
    echo "np_doctor: 找不到 python3" >&2
    exit 127
fi

if [ ! -f "$CORE_DIR/np_doctor.py" ]; then
    echo "np_doctor: 核心目录里没有 np_doctor.py（CORE_DIR=$CORE_DIR）" >&2
    echo "           请设置 NP_CORE_DIR 到核心文件所在目录" >&2
    exit 2
fi

exec "$PY" "$CORE_DIR/np_doctor.py" "$@"
