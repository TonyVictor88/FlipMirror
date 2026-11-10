@echo off
REM ============================================================
REM  FlipMirror boot helper  (pure ASCII, safe for .bat)
REM
REM  Why this is needed:
REM    This phone's "root" is a user-space exploit that only exists
REM    AFTER the exploit app (com.rootmyf7310.app) is launched.
REM    At cold boot there is no su, so nothing root-based can run.
REM    Shizuku (ADB mode) is the only shell channel we can get, and
REM    it has to be started once per boot from a PC over adb.
REM
REM  What it does:
REM    1) wait for the phone to show up on adb
REM    2) start shizuku_server (ADB mode, no root needed)
REM    3) switch to dual-screen state and launch the cover desktop
REM
REM  Usage: double-click after the phone boots (or put a shortcut
REM         in shell:startup so it runs automatically at logon).
REM ============================================================

set ADB="H:\AI PG\FlipMirror\tools\pt34\platform-tools\adb.exe"

echo [1/3] waiting for device...
%ADB% wait-for-device

echo [2/3] starting shizuku server...
%ADB% shell /data/local/tmp/shizuku_starter

timeout /t 5 /nobreak >nul

echo [3/3] launching cover desktop...
%ADB% shell cmd device_state state 4
timeout /t 2 /nobreak >nul
%ADB% shell am start --display 1 -n com.flip.mirror/.CoverLauncherActivity

echo.
echo Done. Check the cover screen.
pause
