#!/bin/bash
# FlipMirror 一键装包 + 恢复权限（每次 install -r 都会冲掉无障碍服务，必须跟着补）
# 用法: ./install.sh
set -u

ADB="/h/AI PG/FlipMirror/tools/pt34/platform-tools/adb.exe"
APK="/h/AI PG/lq/workspace/_flip5/flipmirror/FlipMirror/app/build/outputs/apk/debug/app-debug.apk"
PKG="com.flip.mirror"
A11Y="com.flip.mirror/com.flip.mirror.GestureBlockService"

echo "[1/5] 安装 APK"
"$ADB" install -r "$APK" | tail -1

echo "[2/5] 允许受限设置（侧载 App 的无障碍开关靠它放行）"
"$ADB" shell "appops set $PKG ACCESS_RESTRICTED_SETTINGS allow"

echo "[3/5] 预授权 MediaProjection（免掉每次手点「共享整个屏幕」）"
"$ADB" shell "appops set $PKG PROJECT_MEDIA allow"

echo "[4/5] 确保无障碍服务是关的（开了会毁触控：触摸探索会让点击变成双击）"
A=$("$ADB" shell "settings get secure enabled_accessibility_services" | tr -d '\r')
C=$(echo "$A" | tr ':' '\n' | grep -v "$PKG" | paste -sd: -)
"$ADB" shell "settings put secure enabled_accessibility_services '${C}'"

echo "[5/5] 复查"
"$ADB" shell "settings get secure enabled_accessibility_services" | tr -d '\r' | tr ':' '\n'
echo "--- 绑定引用数（>0 表示已生效）---"
"$ADB" shell "dumpsys accessibility 2>/dev/null | grep -c '$PKG'"
