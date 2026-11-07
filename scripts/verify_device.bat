@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion

rem 真机验证：安装 APK -> 启动 -> 抓崩溃/NoSuchMethod 日志
rem 用法：scripts\verify_device.bat [apk路径]
rem 依赖：adb 在 PATH 里；或设置 ADB=C:\path\to\adb.exe

set "ROOT=%~dp0.."
set "APK=%~1"
if "%APK%"=="" set "APK=%ROOT%\dist\yuedu_pro_app_1.0.26.110803-arm64.apk"

set "ADB=adb"
if not "%ADB_PATH%"=="" set "ADB=%ADB_PATH%"
where %ADB% >nul 2>nul
if errorlevel 1 (
  echo [x] 找不到 adb，请把 platform-tools 加进 PATH，或设置 ADB_PATH
  exit /b 1
)

if not exist "%APK%" (
  echo [x] 找不到 APK: %APK%
  exit /b 1
)

echo [1/5] 设备列表
%ADB% devices
%ADB% wait-for-device

echo [2/5] 设备信息
%ADB% shell getprop ro.build.version.sdk
%ADB% shell getprop ro.build.version.release
%ADB% shell getprop ro.product.model
%ADB% shell getprop ro.product.cpu.abi

echo [3/5] 安装（会覆盖同名同签名版本）
%ADB% install -r "%APK%"
if errorlevel 1 (
  echo [x] 安装失败：检查是否装了同包名但签名不同的旧包，先 adb uninstall io.legado.pro
  exit /b 1
)

echo [4/5] 启动
%ADB% logcat -c
%ADB% shell monkey -p io.legado.pro -c android.intent.category.LAUNCHER 1
timeout /t 8 >nul

echo [5/5] 关键日志（崩溃 / 缺方法 / 缺类）
%ADB% logcat -d -v time ^
  -s AndroidRuntime:E ActivityManager:I ^
  | findstr /i "io.legado AndroidRuntime FATAL NoSuchMethod NoClassDef Exception"

echo.
echo 如果上面出现 FATAL EXCEPTION 或 NoSuchMethodError，把整段贴出来即可定位。
echo 完整日志：%ADB% logcat -d ^> device-log.txt
endlocal