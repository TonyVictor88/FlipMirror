#!/bin/bash
ADB="/h/AI PG/FlipMirror/tools/pt34/platform-tools/adb.exe"
D=4630947181303254916
export MSYS_NO_PATHCONV=1

shot() { "$ADB" shell "screencap -d $D -p /sdcard/_m.png" >/dev/null 2>&1; "$ADB" shell "stat -c %s /sdcard/_m.png" 2>/dev/null | tr -d ''; }
win()  { "$ADB" shell "dumpsys window windows 2>/dev/null|grep -E '^  Window #'|grep -c 'com.flip.mirror'" | tr -d '\r'; }
fg1()  { "$ADB" shell "dumpsys activity activities 2>/dev/null|grep topResumedActivity|tail -1" | sed 's/.*u0 //' | cut -c1-40 | tr -d '\r'; }
cls()  { local n=$1; if [ "$n" -lt 60000 ]; then echo "入口页"; elif [ "$n" -lt 120000 ]; then echo "其他页"; elif [ "$n" -gt 840000 ]; then echo "壁纸空"; else echo "镜像/首页"; fi; }

cell() {
  local name="$1" pre="$2" act="$3" wait="$4"
  "$ADB" shell "am force-stop com.flip.mirror" >/dev/null 2>&1; sleep 2
  "$ADB" shell "$pre" >/dev/null 2>&1; sleep 3
  "$ADB" shell "input keyevent 224" >/dev/null 2>&1; sleep 1
  "$ADB" shell "$act" >/dev/null 2>&1
  sleep "$wait"
  local w=$(win) b=$(shot) f=$(fg1)
  printf "%-22s 窗=%-2s %-7s %7sB  %s\n" "$name" "$w" "$(cls $b)" "$b" "$f"
}

echo "格子                   窗  类型      字节      前台"
echo "────────────────────────────────────────────────────────────"
cell "state1→点图标"     "cmd device_state state 1" "am start -n com.flip.mirror/.MainActivity" 22
cell "state4→点图标"     "cmd device_state state 4" "am start -n com.flip.mirror/.MainActivity" 22
cell "state1→点两次"     "cmd device_state state 1" "am start -n com.flip.mirror/.MainActivity; sleep 7; am start -n com.flip.mirror/.MainActivity" 22
