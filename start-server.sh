#!/usr/bin/env bash
# ============================================================
#  SaysChatV4.1.0 服务器启动脚本（Linux，前台运行）
#  用法: ./start-server.sh
#  停止: Ctrl+C，或在控制台输入 /stop
#  首次启动会在当前目录生成 sayschat-server.conf（端口 + 连接密码）
# ============================================================
set -e
cd "$(dirname "$0")"
umask 077

JAR="dist/SaysChatV4.1.0-Server.jar"
[ -f "$JAR" ] || JAR="SaysChatV4.1.0-Server.jar"
if [ ! -f "$JAR" ]; then
    echo "错误: 找不到服务器 jar，请先运行 ./build.sh 构建，或把 jar 放到当前目录。"
    exit 1
fi

if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    JAVA="$JAVA_HOME/bin/java"
elif command -v java >/dev/null 2>&1; then
    JAVA="java"
else
    echo "错误: 未找到 java，请安装 JDK/JRE 8+（例如 apt install openjdk-8-jre-headless）。"
    exit 1
fi

echo "启动 SaysChatV4.1.0 服务器（jar: $JAR）..."
echo "首次启动会在当前目录生成 sayschat-server.conf；连接密码也会打印在控制台。"
exec "$JAVA" -Dfile.encoding=UTF-8 -jar "$JAR"
