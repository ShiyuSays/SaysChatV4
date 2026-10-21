@echo off
rem ============================================================
rem  SaysChatV4 build script (Windows)
rem  Output: dist\SaysChatV4-Server.jar
rem          dist\SaysChatV4-Desktop.jar
rem  Requires: JDK 8 or newer (javac + jar on PATH)
rem ============================================================
setlocal
cd /d "%~dp0"

where javac >nul 2>nul
if errorlevel 1 (
    echo [ERROR] javac not found. Please install JDK 8+ and add it to PATH.
    exit /b 1
)

if exist build rmdir /s /q build
if not exist dist mkdir dist
mkdir build\classes build\manifest
del /q dist\SaysChatV4-Server.jar dist\SaysChatV4-Desktop.jar 2>nul

echo.
echo ===== Building SaysChatV4 server =====
dir /s /b src\common\*.java src\server\*.java > build\sources.txt
javac -encoding UTF-8 -source 8 -target 8 -Xlint:-options -d build\classes @build\sources.txt
if errorlevel 1 (
    echo [ERROR] Server compile failed
    exit /b 1
)
>  build\manifest\MANIFEST.MF echo Manifest-Version: 1.0
>> build\manifest\MANIFEST.MF echo Main-Class: sayschat.server.SaysChatServer
>> build\manifest\MANIFEST.MF echo Implementation-Title: SaysChatV4 Server
>> build\manifest\MANIFEST.MF echo Implementation-Version: 4.0
>> build\manifest\MANIFEST.MF echo.
jar cfm dist\SaysChatV4-Server.jar build\manifest\MANIFEST.MF -C build\classes .
if errorlevel 1 exit /b 1
echo Generated dist\SaysChatV4-Server.jar

echo.
echo ===== Building SaysChatV4 desktop client =====
rmdir /s /q build\classes
mkdir build\classes
dir /s /b src\common\*.java src\desktop\*.java > build\sources.txt
javac -encoding UTF-8 -source 8 -target 8 -Xlint:-options -d build\classes @build\sources.txt
if errorlevel 1 (
    echo [ERROR] Desktop compile failed
    exit /b 1
)
>  build\manifest\MANIFEST.MF echo Manifest-Version: 1.0
>> build\manifest\MANIFEST.MF echo Main-Class: sayschat.desktop.SaysChatDesktop
>> build\manifest\MANIFEST.MF echo Implementation-Title: SaysChatV4 Desktop
>> build\manifest\MANIFEST.MF echo Implementation-Version: 4.0
>> build\manifest\MANIFEST.MF echo.
jar cfm dist\SaysChatV4-Desktop.jar build\manifest\MANIFEST.MF -C build\classes .
if errorlevel 1 exit /b 1
echo Generated dist\SaysChatV4-Desktop.jar

echo.
echo ===== Build finished =====
echo   Server:       dist\SaysChatV4-Server.jar
echo   Desktop:      dist\SaysChatV4-Desktop.jar
echo   Android APK:  run build-apk.bat (requires Android SDK + JDK 17 for build-tools 34)
endlocal
