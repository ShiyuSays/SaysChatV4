#!/bin/bash
# ============================================================
#  SaysChatV4.1.0 构建脚本 (Linux / macOS)
#  产出: dist/SaysChatV4.1.0-Server.jar   服务器
#        dist/SaysChatV4.1.0-Desktop.jar 电脑客户端
#  要求: JDK 8 或更高版本（javac + jar）
# ============================================================
set -e
cd "$(dirname "$0")"

if ! command -v javac >/dev/null 2>&1; then
    echo "[错误] 未找到 javac，请安装 JDK 8 或更高版本"
    exit 1
fi

rm -rf build
mkdir -p build/classes build/manifest dist
rm -f dist/SaysChatV4.1.0-Server.jar dist/SaysChatV4.1.0-Desktop.jar

echo ""
echo "===== 编译 SaysChatV4.1.0 服务器端 ====="
find src/common src/server -name '*.java' -print > build/sources.txt
javac -encoding UTF-8 -source 8 -target 8 -Xlint:-options \
    -d build/classes @build/sources.txt
printf 'Manifest-Version: 1.0\nMain-Class: sayschat.server.SaysChatServer\nImplementation-Title: SaysChatV4.1.0 Server\nImplementation-Version: 4.1.0\n\n' \
    > build/manifest/MANIFEST.MF
jar cfm dist/SaysChatV4.1.0-Server.jar build/manifest/MANIFEST.MF -C build/classes .
echo "已生成 dist/SaysChatV4.1.0-Server.jar"

echo ""
echo "===== 编译 SaysChatV4.1.0 电脑客户端 ====="
rm -rf build/classes
mkdir -p build/classes
find src/common src/desktop -name '*.java' -print > build/sources.txt
javac -encoding UTF-8 -source 8 -target 8 -Xlint:-options \
    -d build/classes @build/sources.txt
printf 'Manifest-Version: 1.0\nMain-Class: sayschat.desktop.SaysChatDesktop\nImplementation-Title: SaysChatV4.1.0 Desktop\nImplementation-Version: 4.1.0\n\n' \
    > build/manifest/MANIFEST.MF
jar cfm dist/SaysChatV4.1.0-Desktop.jar build/manifest/MANIFEST.MF -C build/classes .
echo "已生成 dist/SaysChatV4.1.0-Desktop.jar"

chmod +x start-server.sh start-server-daemon.sh stop-server.sh build-apk.sh 2>/dev/null || true

echo ""
echo "===== 构建完成 ====="
echo "  服务器:       dist/SaysChatV4.1.0-Server.jar"
echo "  电脑客户端:   dist/SaysChatV4.1.0-Desktop.jar"
echo "  安卓客户端:   运行 ./build-apk.sh，或使用 dist/SaysChatV4.1.0.apk（若已提供）"
