@echo off
REM ============================================================
REM  shizuku-keepalive.bat  (pure ASCII)
REM
REM  Why: on this phone the Shizuku server process keeps dying.
REM       When it dies every shell-based feature breaks silently:
REM       touch injection, launching the mirror activity, wm size,
REM       device_state ... the user only sees "nothing happens".
REM
REM  What: poll every 8 seconds. If shizuku_server is gone, run the
REM        starter again. Logs one line per restart.
REM
REM  Note: this must run on the PC (it needs adb). Start it at logon
REM        via Task Scheduler, or just double-click it.
REM ============================================================

set ADB="H:\AI PG\FlipMirror\tools\pt34\platform-tools\adb.exe"
set LOG="H:\AI PG\FlipMirror\shizuku-keepalive.log"

echo [%date% %time%] keepalive started >> %LOG%

:loop
%ADB% get-state 2>nul | findstr /c:"device" >nul
if errorlevel 1 (
    REM phone not connected, wait quietly
    timeout /t 5 /nobreak >nul
    goto loop
)

%ADB% shell "pidof shizuku_server" 2>nul | findstr /r "[0-9]" >nul
if errorlevel 1 (
    echo [%date% %time%] shizuku down, restarting >> %LOG%
    %ADB% shell /data/local/tmp/shizuku_starter >> %LOG% 2>&1
    timeout /t 3 /nobreak >nul
)

timeout /t 8 /nobreak >nul
goto loop
