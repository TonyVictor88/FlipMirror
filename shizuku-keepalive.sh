#!/bin/sh
ADB="/h/AI PG/FlipMirror/tools/pt34/platform-tools/adb.exe"
LOG="/h/AI PG/FlipMirror/shizuku-keepalive.log"
export MSYS_NO_PATHCONV=1
echo "[$(date '+%H:%M:%S')] keepalive started" >> "$LOG"
while true; do
  ST=$("$ADB" get-state 2>/dev/null | tr -d '\r')
  if [ "$ST" = "device" ]; then
    P=$("$ADB" shell "pidof shizuku_server" 2>/dev/null | tr -d '\r\n ')
    if [ -z "$P" ]; then
      echo "[$(date '+%H:%M:%S')] shizuku down -> restarting" >> "$LOG"
      "$ADB" shell "/data/local/tmp/shizuku_starter" >> "$LOG" 2>&1
      sleep 3
    fi
  fi
  sleep 6
done
