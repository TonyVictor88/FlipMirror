import io

base = 'H:/AI PG/FlipMirror/app/app/src/main/java/com/flip/mirror/'

# 1) MirrorActivity：不再启动 :wd 看门狗（那个进程里 Shizuku 不可用，只会重复触发）
p1 = base + 'MirrorActivity.java'
s1 = io.open(p1, encoding='utf-8').read()
old1 = '''                startForegroundService(new Intent(this, WatchdogService.class));'''
new1 = '''                // ⚠️ 不再启动 :wd 看门狗：实测该独立进程里 Shizuku 通道不可用（shell 一直 failed），
                //    它只会每隔 4 秒重复发起一次恢复，和主进程的亮屏监听抢同一个录屏授权，
                //    导致第二个实例报「录屏授权已失效」并把画面踢掉。
                //    恢复统一交给 MirrorService 的亮屏监听。'''
if old1 in s1:
    s1 = s1.replace(old1, new1, 1)
    io.open(p1, 'w', encoding='utf-8').write(s1)
    print('1 ok: watchdog start removed')
else:
    print('1 skip: anchor not found')

# 2) MirrorService：加重入保护 + 去抖（2 分钟内只恢复一次，且同时只允许一个恢复线程）
p2 = base + 'MirrorService.java'
s2 = io.open(p2, encoding='utf-8').read()

if 'sLastRelaunch' not in s2:
    s2 = s2.replace('    private android.content.BroadcastReceiver mScreenOn;',
'''    private android.content.BroadcastReceiver mScreenOn;

    /** 去抖：避免同一时刻发起多次恢复（多次会抢同一个录屏授权）。 */
    private static long sLastRelaunch = 0L;
    private static boolean sRelaunching = false;''', 1)

old2 = '''                            Log.i(TAG, "screen on: relaunching mirror");'''
new2 = '''                            long now = android.os.SystemClock.elapsedRealtime();
                            if (sRelaunching || now - sLastRelaunch < 60000L) {
                                Log.i(TAG, "screen on: relaunch skipped (debounce)");
                                return;
                            }
                            sRelaunching = true;
                            sLastRelaunch = now;
                            Log.i(TAG, "screen on: relaunching mirror");'''
if old2 in s2:
    s2 = s2.replace(old2, new2, 1)

old3 = '''                            Shell.execSync("am start --display 1 -n com.flip.mirror/.MainActivity"
                                    + " --ez auto_start true");
                        } catch (Throwable t) {
                            Log.w(TAG, "screen-on relaunch failed: " + t);
                        }'''
new3 = '''                            Shell.execSync("am start --display 1 -n com.flip.mirror/.MainActivity"
                                    + " --ez auto_start true");
                        } catch (Throwable t) {
                            Log.w(TAG, "screen-on relaunch failed: " + t);
                        } finally {
                            new android.os.Handler(android.os.Looper.getMainLooper())
                                    .postDelayed(() -> sRelaunching = false, 12000);
                        }'''
if old3 in s2:
    s2 = s2.replace(old3, new3, 1)

io.open(p2, 'w', encoding='utf-8').write(s2)
print('2 ok: debounce added')
