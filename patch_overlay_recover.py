import io

p = 'H:/AI PG/FlipMirror/app/app/src/main/java/com/flip/mirror/CoverOverlayMirrorService.java'
s = io.open(p, encoding='utf-8').read()

# 1) 字段：投影存活标记 + 亮屏接收器
if 'mProjectionAlive' not in s:
    s = s.replace('    private int mSrcW = 1080, mSrcH = 1920;   // 内屏实际像素（只用于等比缩放）',
'''    private int mSrcW = 1080, mSrcH = 1920;   // 内屏实际像素（只用于等比缩放）

    /**
     * 录屏会话是否还活着。
     *
     * 关屏时系统会主动 stop 掉 MediaProjection（日志：projection stopped by system），
     * 画面就冻在最后一帧。所以亮屏时必须重新申请一次。
     */
    private volatile boolean mProjectionAlive = false;
    private android.content.BroadcastReceiver mScreenOn;''', 1)

# 2) onCreate 注册亮屏恢复
if 'registerScreenOnRecovery' not in s:
    s = s.replace('''        startForegroundWithNotification();
        showOverlay();''',
'''        startForegroundWithNotification();
        showOverlay();
        registerScreenOnRecovery();''', 1)

# 3) 方法体
method = '''    /**
     * 亮屏 → 若录屏会话已被系统掐掉，就自动重新申请一次。
     *
     * 不需要人点：装机时已经 {@code appops set PROJECT_MEDIA allow}，
     * 所以 {@code createScreenCaptureIntent} 会直接返回 RESULT_OK（实测无弹窗）。
     *
     * 拉起的是 MainActivity（不是直接要授权）：拿授权必须走 Activity 的
     * startActivityForResult，而入口页会用 force_overlay 直接转到本服务。
     * 入口页落在哪个屏都无所谓 —— 画面是我们自己在 display 1 上画的。
     */
    private void registerScreenOnRecovery() {
        try {
            mScreenOn = new android.content.BroadcastReceiver() {
                @Override
                public void onReceive(android.content.Context c, Intent i) {
                    if (i == null || !Intent.ACTION_SCREEN_ON.equals(i.getAction())) {
                        return;
                    }
                    if (mProjectionAlive) {
                        Log.i(TAG, "screen on: projection still alive, nothing to do");
                        return;
                    }
                    Log.i(TAG, "screen on: projection dead, re-acquiring");
                    try {
                        Intent it = new Intent(CoverOverlayMirrorService.this, MainActivity.class);
                        it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                        it.putExtra("auto_start", true);
                        it.putExtra("force_overlay", true);
                        startActivity(it);
                    } catch (Throwable t) {
                        Log.w(TAG, "re-acquire failed: " + t);
                    }
                }
            };
            android.content.IntentFilter f =
                    new android.content.IntentFilter(Intent.ACTION_SCREEN_ON);
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(mScreenOn, f, android.content.Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(mScreenOn, f);
            }
            Log.i(TAG, "screen-on recovery registered");
        } catch (Throwable t) {
            Log.w(TAG, "register screen-on recovery failed: " + t);
        }
    }

'''
if 'registerScreenOnRecovery() {' not in s:
    s = s.replace('    @Override\n    public int onStartCommand', method + '    @Override\n    public int onStartCommand', 1)

# 4) 标记存活 / 已停止
s = s.replace('''                public void onStop() {
                    Log.i(TAG, "projection stopped by system");
                }''',
'''                public void onStop() {
                    Log.i(TAG, "projection stopped by system");
                    mProjectionAlive = false;
                }''', 1)

s = s.replace('''            Log.i(TAG, "projection started " + capW + "x" + capH);''',
'''            mProjectionAlive = true;
            Log.i(TAG, "projection started " + capW + "x" + capH);''', 1)

# 5) onDestroy 反注册
s = s.replace('''        sResultCode = 0;
        sResultData = null;
        super.onDestroy();''',
'''        try {
            if (mScreenOn != null) {
                unregisterReceiver(mScreenOn);
            }
        } catch (Throwable ignored) { }
        mProjectionAlive = false;
        sResultCode = 0;
        sResultData = null;
        super.onDestroy();''', 1)

io.open(p, 'w', encoding='utf-8').write(s)
print('screen-on recovery added')
