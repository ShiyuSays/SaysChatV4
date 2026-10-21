#!/usr/bin/env bash
# ============================================================
#  SaysChatV4 服务器停止脚本（Linux，配合 start-server-daemon.sh）
#  用法: ./stop-server.sh
# ============================================================
set -e
cd "$(dirname "$0")"

if [ ! -f server.pid ]; then
    echo "没有找到 server.pid，服务器可能未通过 start-server-daemon.sh 启动。"
    exit 1
fi

PID=$(cat server.pid 2>/dev/null | tr -dc '0-9')
if [ -z "$PID" ]; then
    echo "server.pid 内容无效，已清理。"
    rm -f server.pid
    exit 1
fi

if ! kill -0 "$PID" 2>/dev/null; then
    echo "进程 $PID 不存在，已清理 server.pid。"
    rm -f server.pid
    exit 0
fi

CMD=$(ps -p "$PID" -o args= 2>/dev/null || true)
case "$CMD" in
    *SaysChatV4-Server.jar*) ;;
    *)
        echo "PID $PID 不是 SaysChatV4 服务器进程，拒绝结束。"
        echo "进程命令: $CMD"
        rm -f server.pid
        exit 1
        ;;
esac

echo "正在停止 SaysChatV4 服务器（PID $PID）..."
kill "$PID" 2>/dev/null || true
for _ in $(seq 1 20); do
    if ! kill -0 "$PID" 2>/dev/null; then
        echo "服务器已停止。"
        rm -f server.pid
        exit 0
    fi
    sleep 0.5
done

echo "进程未在 10 秒内退出，强制结束。"
kill -9 "$PID" 2>/dev/null || true
rm -f server.pid
echo "已强制停止。"
