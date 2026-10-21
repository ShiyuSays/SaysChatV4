@echo off
rem ============================================================
rem  SaysChatV4 Android APK build (Windows, without Gradle)
rem  Requires:
rem    - Android SDK with build-tools and platforms/android-*
rem    - JDK 8+ for javac; build-tools 31+ need JDK 17 for d8/apksigner
rem  Usage:
rem    set ANDROID_SDK_ROOT=C:\path\to\android-sdk
rem    build-apk.bat
rem  Output: dist\SaysChatV4.apk (debug signed, installable)
rem ============================================================
setlocal EnableDelayedExpansion
cd /d "%~dp0"

if not defined ANDROID_SDK_ROOT if defined ANDROID_HOME set "ANDROID_SDK_ROOT=%ANDROID_HOME%"
if not defined ANDROID_SDK_ROOT if exist "%LOCALAPPDATA%\Android\Sdk" set "ANDROID_SDK_ROOT=%LOCALAPPDATA%\Android\Sdk"
if not defined ANDROID_SDK_ROOT (
    echo [ERROR] Android SDK not found.
    echo         Set ANDROID_SDK_ROOT to your Android SDK folder.
    exit /b 1
)
if not exist "%ANDROID_SDK_ROOT%" (
    echo [ERROR] ANDROID_SDK_ROOT does not exist: %ANDROID_SDK_ROOT%
    exit /b 1
)

set "BT="
for /f "delims=" %%d in ('dir /b /ad /o-n "%ANDROID_SDK_ROOT%\build-tools" 2^>nul') do if not defined BT set "BT=%ANDROID_SDK_ROOT%\build-tools\%%d"
set "PF="
for /f "delims=" %%d in ('dir /b /ad /o-n "%ANDROID_SDK_ROOT%\platforms" 2^>nul ^| findstr /b "android-"') do if not defined PF set "PF=%ANDROID_SDK_ROOT%\platforms\%%d"
if not defined BT (
    echo [ERROR] No build-tools found under %ANDROID_SDK_ROOT%\build-tools
    exit /b 1
)
if not defined PF (
    echo [ERROR] No platforms\android-* found under %ANDROID_SDK_ROOT%\platforms
    exit /b 1
)
for %%t in (aapt2.exe zipalign.exe apksigner.bat d8.bat) do (
    if not exist "%BT%\%%t" (
        echo [ERROR] Missing build tool: %BT%\%%t
        exit /b 1
    )
)
where javac >nul 2>nul
if errorlevel 1 (
    echo [ERROR] javac not found. Install JDK 8+ and add it to PATH.
    exit /b 1
)

echo Using build-tools: %BT%
echo Using platform:    %PF%

rem build-tools 31+ 的 d8 / apksigner 需要 JDK 11+；优先使用 JDK 17。
set "JDK17="
if defined JAVA17_HOME if exist "%JAVA17_HOME%\bin\java.exe" set "JDK17=%JAVA17_HOME%"
if not defined JDK17 if exist "C:\Program Files\Java\jdk-17\bin\java.exe" set "JDK17=C:\Program Files\Java\jdk-17"
if not defined JDK17 if exist "C:\Program Files\Java\jdk-21\bin\java.exe" set "JDK17=C:\Program Files\Java\jdk-21"
if not defined JDK17 if exist "C:\Program Files\Java\jdk-25\bin\java.exe" set "JDK17=C:\Program Files\Java\jdk-25"

if exist build-apk rmdir /s /q build-apk
mkdir build-apk\gen build-apk\classes build-apk\dex
if not exist dist mkdir dist
del /q dist\SaysChatV4.apk dist\SaysChatV4-unsigned.apk dist\SaysChatV4-aligned.apk 2>nul

echo.
echo [1/5] Compiling Android resources...
"%BT%\aapt2.exe" compile --dir android\app\src\main\res -o build-apk\res.zip
if errorlevel 1 exit /b 1
"%BT%\aapt2.exe" link -I "%PF%\android.jar" ^
    --manifest android\app\src\main\AndroidManifest.xml ^
    --min-sdk-version 21 --target-sdk-version 30 ^
    --version-code 4 --version-name 4.0 ^
    --java build-apk\gen -o dist\SaysChatV4-unsigned.apk build-apk\res.zip
if errorlevel 1 exit /b 1

echo [2/5] Compiling Java sources...
dir /s /b android\app\src\main\java\*.java src\common\*.java build-apk\gen\*.java > build-apk\sources.txt
javac -encoding UTF-8 -source 8 -target 8 -Xlint:-options ^
    -bootclasspath "%PF%\android.jar" -classpath "%PF%\android.jar" ^
    -d build-apk\classes @build-apk\sources.txt
if errorlevel 1 exit /b 1

echo [3/5] Converting bytecode to dex...
dir /s /b build-apk\classes\*.class > build-apk\classes.txt
if defined JDK17 (
    set "JAVA_HOME=%JDK17%"
) else (
    echo [WARN] JDK 17 not found; if d8 fails, set JAVA17_HOME to a JDK 17+ folder.
)
call "%BT%\d8.bat" --release --lib "%PF%\android.jar" --min-api 21 --output build-apk\dex @build-apk\classes.txt
set "D8EXIT=%ERRORLEVEL%"
set "JAVA_HOME="
if not "%D8EXIT%"=="0" exit /b %D8EXIT%

echo [4/5] Packaging + aligning...
powershell -NoProfile -ExecutionPolicy Bypass -Command ^
  "Add-Type -AssemblyName System.IO.Compression.FileSystem; $apk=(Resolve-Path 'dist\SaysChatV4-unsigned.apk').Path; $dex=(Resolve-Path 'build-apk\dex\classes.dex').Path; $z=[System.IO.Compression.ZipFile]::Open($apk,'Update'); try { $old=$z.GetEntry('classes.dex'); if ($old) { $old.Delete() }; [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($z,$dex,'classes.dex') | Out-Null } finally { $z.Dispose() }"
if errorlevel 1 exit /b 1
"%BT%\zipalign.exe" -f 4 dist\SaysChatV4-unsigned.apk dist\SaysChatV4-aligned.apk
if errorlevel 1 exit /b 1

echo [5/5] Signing APK...
set "KEYSTORE=android\signing\debug.keystore"
if not exist "%KEYSTORE%" (
    if not exist android\signing mkdir android\signing
    keytool -genkeypair -keystore "%KEYSTORE%" -storetype JKS -storepass android -keypass android ^
        -alias androiddebugkey -dname "CN=Android Debug,O=Android,C=US" ^
        -keyalg RSA -keysize 2048 -validity 10000
    if errorlevel 1 exit /b 1
)
if defined JDK17 set "JAVA_HOME=%JDK17%"
call "%BT%\apksigner.bat" sign --ks "%KEYSTORE%" ^
    --ks-pass pass:android --key-pass pass:android ^
    --out dist\SaysChatV4.apk dist\SaysChatV4-aligned.apk
set "SIGNEXIT=%ERRORLEVEL%"
if not "%SIGNEXIT%"=="0" (
    set "JAVA_HOME="
    exit /b %SIGNEXIT%
)
call "%BT%\apksigner.bat" verify dist\SaysChatV4.apk
set "VERIFYEXIT=%ERRORLEVEL%"
set "JAVA_HOME="
if not "%VERIFYEXIT%"=="0" exit /b %VERIFYEXIT%

del /q dist\SaysChatV4-unsigned.apk dist\SaysChatV4-aligned.apk dist\SaysChatV4.apk.idsig 2>nul
echo.
echo Build finished: dist\SaysChatV4.apk
endlocal
