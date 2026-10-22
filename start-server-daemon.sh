#!/usr/bin/env bash
# ============================================================
#  SaysChatV4.1.0 服务器后台启动脚本（Linux）
#  用法: ./start-server-daemon.sh
#  停止: ./stop-server.sh
#  日志: logs/server.log（首次启动的连接密码也在日志中）
# ============================================================
set -e
cd "$(dirname "$0")"
umask 077

JAR="dist/SaysChatV4.1.0-Server.jar"
[ -f "$JAR" ] || JAR="SaysChatV4.1.0-Server.jar"
if [ ! -f "$JAR" ]; then
    echo "错误: 找不到服务器 jar，请先运行 ./build.sh 构建。"
    exit 1
fi

if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    JAVA="$JAVA_HOME/bin/java"
elif command -v java >/dev/null 2>&1; then
    JAVA="java"
else
    echo "错误: 未找到 java，请安装 JDK/JRE 8+。"
    exit 1
fi

if [ -f server.pid ]; then
    OLD_PID=$(cat server.pid 2>/dev/null || true)
    if [ -n "$OLD_PID" ] && kill -0 "$OLD_PID" 2>/dev/null; then
        echo "服务器似乎已在运行，PID: $OLD_PID"
        echo "如需重启，请先执行 ./stop-server.sh"
        exit 1
    fi
    rm -f server.pid
fi

mkdir -p logs
{
    echo ""
    echo "===== $(date '+%Y-%m-%d %H:%M:%S') 启动 SaysChatV4.1.0 ====="
} >> logs/server.log

nohup "$JAVA" -Dfile.encoding=UTF-8 -jar "$JAR" >> logs/server.log 2>&1 &
PID=$!
echo "$PID" > server.pid

sleep 1
if kill -0 "$PID" 2>/dev/null; then
    echo "服务器已在后台启动，PID: $PID"
    echo "日志: logs/server.log"
    echo "查看连接密码: grep '^password=' sayschat-server.conf"
    echo "停止服务器: ./stop-server.sh"
else
    echo "启动失败，请查看 logs/server.log"
    rm -f server.pid
    exit 1
fi
