import io

p = 'H:/AI PG/FlipMirror/app/app/src/main/java/com/flip/mirror/BarOverlayService.java'
s = io.open(p, encoding='utf-8').read()

field = '''    private WindowManager mWm;

    /**
     * 亮屏广播接收器（跑在主进程里）。
     *
     * 为什么放在主进程、而不是交给 :wd 看门狗：
     * 实测 :wd 那个进程里 Shizuku 通道不可用（shell 一直 failed），
     * 而主进程同一时刻能正常执行 shell。所以恢复动作必须在主进程做。
     */
    private android.content.BroadcastReceiver mScreenOnReceiver;
'''
if 'mScreenOnReceiver' not in s:
    s = s.replace('    private WindowManager mWm;', field, 1)

if 'registerScreenOnReceiver();' not in s:
    s = s.replace('''        startForegroundWithNotification();
        showBar();''', '''        startForegroundWithNotification();
        showBar();
        registerScreenOnReceiver();''', 1)

method = '''    /**
     * 监听亮屏：如果"应该有镜像"（标记文件在）但镜像已被 SubHome 顶掉，
     * 就用 shell 重新拉起镜像页。
     */
    private void registerScreenOnReceiver() {
        try {
            mScreenOnReceiver = new android.content.BroadcastReceiver() {
                @Override
                public void onReceive(android.content.Context c, Intent i) {
                    if (i == null || !Intent.ACTION_SCREEN_ON.equals(i.getAction())) {
                        return;
                    }
                    Log.i(TAG, "screen on -> check mirror");
                    new Thread(() -> {
                        try {
                            if (!new java.io.File(getFilesDir(), MirrorActivity.FLAG_FILE).exists()) {
                                Log.i(TAG, "screen on: flag absent, user exited, do nothing");
                                return;
                            }
                            Log.i(TAG, "screen on: relaunching mirror");
                            Shell.execSync("am start --display 1 -n com.flip.mirror/.MainActivity"
                                    + " --ez auto_start true");
                        } catch (Throwable t) {
                            Log.w(TAG, "screen-on relaunch failed: " + t.getMessage());
                        }
                    }, "screen-on-relaunch").start();
                }
            };
            android.content.IntentFilter f =
                    new android.content.IntentFilter(Intent.ACTION_SCREEN_ON);
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(mScreenOnReceiver, f,
                        android.content.Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(mScreenOnReceiver, f);
            }
            Log.i(TAG, "screen-on receiver registered");
        } catch (Throwable t) {
            Log.w(TAG, "register screen-on receiver failed: " + t);
        }
    }

'''
if 'registerScreenOnReceiver() {' not in s:
    s = s.replace('''    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {''', method + '''    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {''', 1)

if 'unregisterReceiver(mScreenOnReceiver)' not in s:
    s = s.replace('''    @Override
    public void onDestroy() {''', '''    @Override
    public void onDestroy() {
        try {
            if (mScreenOnReceiver != null) {
                unregisterReceiver(mScreenOnReceiver);
            }
        } catch (Throwable ignored) { }''', 1)

io.open(p, 'w', encoding='utf-8').write(s)
print('BarOverlayService patched')
