import io

p = 'H:/AI PG/FlipMirror/app/app/src/main/java/com/flip/mirror/MirrorService.java'
s = io.open(p, encoding='utf-8').read()

if 'mScreenOn' not in s:
    s = s.replace('''    private static final int NOTI_ID = 1001;''',
'''    private static final int NOTI_ID = 1001;

    /**
     * 亮屏监听：把被 SubHome 顶掉的镜像拉回来。
     *
     * 为什么挂在 MirrorService 而不是 BarOverlayService／:wd 看门狗：
     * - BarOverlayService 实测加完控件条后会自己消失（服务列表里查不到），监听跟着丢；
     * - :wd 独立进程里 Shizuku 通道不可用（shell 一直 failed），拉了也白拉；
     * - MirrorService 是 mediaProjection 类型的前台服务，镜像期间必然存活，且在主进程。
     */
    private android.content.BroadcastReceiver mScreenOn;''', 1)

if 'registerReceiver(mScreenOn' not in s:
    s = s.replace('''    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {''',
'''    @Override
    public void onCreate2() { }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {''', 1)

# 在 onCreate 结尾注册（放在 startForeground 之后）
if 'setupScreenOnReceiver' not in s:
    s = s.replace('''        super.onCreate();''', '''        super.onCreate();
        setupScreenOnReceiver();''', 1)

    marker = '''    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {'''
    body = '''    /**
     * 亮屏 → 若"应该有镜像"（标记文件在）且镜像不在前台，就重新拉起。
     * 用户点过 ✕ 退出会删掉标记文件，所以不会自己弹回来。
     */
    private void setupScreenOnReceiver() {
        try {
            mScreenOn = new android.content.BroadcastReceiver() {
                @Override
                public void onReceive(android.content.Context c, Intent i) {
                    if (i == null || !Intent.ACTION_SCREEN_ON.equals(i.getAction())) {
                        return;
                    }
                    new Thread(() -> {
                        try {
                            java.io.File flag = new java.io.File(getFilesDir(), MirrorActivity.FLAG_FILE);
                            if (!flag.exists()) {
                                Log.i(TAG, "screen on: no flag, user exited, skip");
                                return;
                            }
                            Log.i(TAG, "screen on: relaunching mirror");
                            Shell.execSync("am start --display 1 -n com.flip.mirror/.MainActivity"
                                    + " --ez auto_start true");
                        } catch (Throwable t) {
                            Log.w(TAG, "screen-on relaunch failed: " + t);
                        }
                    }, "screen-on").start();
                }
            };
            android.content.IntentFilter f =
                    new android.content.IntentFilter(Intent.ACTION_SCREEN_ON);
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                registerReceiver(mScreenOn, f, android.content.Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(mScreenOn, f);
            }
            Log.i(TAG, "MirrorService: screen-on receiver registered");
        } catch (Throwable t) {
            Log.w(TAG, "screen-on receiver register failed: " + t);
        }
    }

'''
    s = s.replace(marker, body + marker, 1)

io.open(p, 'w', encoding='utf-8').write(s)
print('MirrorService patched')
