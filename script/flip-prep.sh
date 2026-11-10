#!/data/data/com.termux/files/usr/bin/sh
# FlipMirror 启动前自检（由 FlipMirror 通过 RUN_COMMAND Intent 调用）
# 只做两件事：打开双屏并发 + 拉起 Shizuku。幂等，可重复调用。
PREFIX=/data/data/com.termux/files/usr
export LD_LIBRARY_PATH=$PREFIX/lib
export HOME=/data/data/com.termux/files/home
export TMPDIR=$PREFIX/tmp
LOG=$HOME/flip-prep.log
ADB="$PREFIX/bin/adb -s 127.0.0.1:5555"

echo "=== prep $(date) ===" >> $LOG

# adb server + 自连（已授权，重启后依然有效）
$PREFIX/bin/adb start-server >> $LOG 2>&1
sleep 1
$PREFIX/bin/adb connect 127.0.0.1:5555 >> $LOG 2>&1
sleep 2

# 双屏并发
$ADB shell "cmd device_state state 4" >> $LOG 2>&1
echo "state=$( $ADB shell dumpsys device_state | grep -oE "name='[A-Z_]+'" | head -1 )" >> $LOG

# Shizuku 服务端
$ADB shell "/data/local/tmp/shizuku_starter" >> $LOG 2>&1
sleep 2
echo "shizuku=$( $ADB shell "ps -A | grep -c shizuku_server" )" >> $LOG

echo "prep done $(date)" >> $LOG
