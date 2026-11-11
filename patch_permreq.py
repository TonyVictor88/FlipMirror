import io

p = 'H:/AI PG/FlipMirror/app/app/src/main/java/com/flip/mirror/MainActivity.java'
s = io.open(p, encoding='utf-8').read()

if 'ensureShizukuPermission' not in s:
    anchor = '        super.onCreate(savedInstanceState);'
    assert s.count(anchor) == 1, 'onCreate anchor'
    s = s.replace(anchor, anchor + '''
        ensureShizukuPermission();''', 1)

    method = '''    /**
     * 主动申请 Shizuku 授权一次。
     *
     * 必须在界面上弹一次框：Shizuku 13.x 的授权记录存在**服务端**，
     * 单靠 adb {@code pm grant} 只改了 Android 运行时权限，服务端仍然判为拒绝
     * （实测：pm grant 显示 granted=true，但 checkSelfPermission() 返回 -1）。
     * 用户点一次「允许」之后才会持久化，重装/换签名后需要重新点。
     */
    private void ensureShizukuPermission() {
        try {
            if (!rikka.shizuku.Shizuku.pingBinder()) {
                Log.i(TAG, "shizuku server not running, skip permission request");
                return;
            }
            int granted = rikka.shizuku.Shizuku.checkSelfPermission();
            Log.i(TAG, "shizuku permission state = " + granted);
            if (granted != 0 && !sPermRequested) {
                sPermRequested = true;
                rikka.shizuku.Shizuku.requestPermission(1001);
                Log.i(TAG, "requested shizuku permission");
            }
        } catch (Throwable t) {
            Log.w(TAG, "ensureShizukuPermission failed: " + t);
        }
    }

'''
    s = s.replace('    @Override\n    protected void onCreate', method + '    @Override\n    protected void onCreate', 1)

    if 'sPermRequested' not in s.split('private void ensureShizukuPermission')[0]:
        s = s.replace('    private static volatile boolean sCoverInstanceAlive = false;',
                      '    private static volatile boolean sCoverInstanceAlive = false;\n    /** Shizuku 授权请求只弹一次 */\n    private static boolean sPermRequested = false;', 1)

    io.open(p, 'w', encoding='utf-8').write(s)
    print('permission request added')
else:
    print('already patched')
