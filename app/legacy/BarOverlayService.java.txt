package com.flip.mirror;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import android.view.ContextThemeWrapper;
import android.view.Display;
import android.view.Gravity;
import android.view.WindowManager;

/**
 * 控件条悬浮层。
 *
 * <h3>为什么必须做成悬浮层</h3>
 * 原来控件条是 MirrorActivity 的一部分。一旦外屏被别的东西盖住（比如滑出多任务，
 * display 1 上是 launcher 的 RecentsActivity），MirrorActivity 就不可见了，
 * 控件条跟着消失 —— 用户就"退不出去"了。
 *
 * <p>用 {@code TYPE_APPLICATION_OVERLAY} 把控件条挂到系统窗口层，它就能一直浮在最上面，
 * 不管下面是什么 App。</p>
 *
 * <h3>按键注入到哪块屏</h3>
 * <ul>
 *   <li>镜像正在最前（{@link MirrorActivity#isMirrorResumed()}）→ 注入 display 0，控制内屏；</li>
 *   <li>否则说明外屏被系统界面占了（多任务/桌面）→ 注入 display 1，先把自己那边收掉。</li>
 * </ul>
 */
public class BarOverlayService extends Service {

    private static final String TAG = "FlipMirrorBar";
    private static final String CHANNEL = "flipmirror_bar";
    private static final int NOTI_ID = 2001;

    private WindowManager mWm;

    /**
     * 亮屏广播接收器（跑在主进程里）。
     *
     * 为什么放在主进程、而不是交给 :wd 看门狗：
     * 实测 :wd 那个进程里 Shizuku 通道不可用（shell 一直 failed），
     * 而主进程同一时刻能正常执行 shell。所以恢复动作必须在主进程做。
     */
    private android.content.BroadcastReceiver mScreenOnReceiver;

    private ControlBar mBar;
    private final TouchInjectorImpl mInjector = new TouchInjectorImpl();

    @Override
    public void onCreate() {
        super.onCreate();
        startForegroundWithNotification();
        mInjector.init();
        showBar();
    }

    private void startForegroundWithNotification() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null && nm.getNotificationChannel(CHANNEL) == null) {
                NotificationChannel ch = new NotificationChannel(
                        CHANNEL, "控件条", NotificationManager.IMPORTANCE_MIN);
                ch.setShowBadge(false);
                nm.createNotificationChannel(ch);
            }
        }
        Notification n = new Notification.Builder(this, Build.VERSION.SDK_INT >= 26 ? CHANNEL : null)
                .setContentTitle("FlipMirror 控件条")
                .setContentText("悬浮在外屏顶部")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 34) {
            // Android 14 起必须显式声明前台服务类型；悬浮窗类用 specialUse
            startForeground(NOTI_ID, n,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTI_ID, n);
        }
    }

    private void showBar() {
        try {
            DisplayManager dm = (DisplayManager) getSystemService(DISPLAY_SERVICE);
            Display d1 = dm == null ? null : dm.getDisplay(1);
            if (d1 == null) {
                Log.w(TAG, "display 1 not found");
                return;
            }
            // 用 display 1 的 context 建 WindowManager，悬浮窗才挂到外屏
            Context displayCtx = createDisplayContext(d1);
            mWm = (WindowManager) displayCtx.getSystemService(WINDOW_SERVICE);
            if (mWm == null) {
                return;
            }

            Context themed = new ContextThemeWrapper(displayCtx, android.R.style.Theme_DeviceDefault);
            mBar = new ControlBar(themed);
            // 外屏上没有别的退出入口 → 第 4 个键直接当"退出回外屏首页"
            mBar.setCollapseButtonAsExit(true);
            // 去掉自带的 MARGIN_DP 边距，否则深色底板会溢出到画面内容区
            mBar.useAsOverlay();
            mBar.setListener(new ControlBar.Listener() {
                @Override public void onBack()    { injectKey(android.view.KeyEvent.KEYCODE_BACK); }
                @Override public void onHome()    { injectKey(android.view.KeyEvent.KEYCODE_HOME); }
                @Override public void onRecents() { injectKey(android.view.KeyEvent.KEYCODE_APP_SWITCH); }
                @Override public void onExit()    { exitToCoverHome(); }
                @Override public void onCollapse() { }
                @Override public void onExpand()   { }
            });

            WindowManager.LayoutParams lp = new WindowManager.LayoutParams();
            lp.type = Build.VERSION.SDK_INT >= 26
                    ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    : WindowManager.LayoutParams.TYPE_PHONE;
            lp.format = PixelFormat.TRANSLUCENT;
            lp.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                    | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
            lp.gravity = Gravity.TOP | Gravity.END;
            lp.width = WindowManager.LayoutParams.WRAP_CONTENT;
            // 🔴 高度必须锁在「顶部黑带」以内：
            //    黑带 = 66 逻辑像素（748 基准），在 compat 空间要按密度比放大。
            //    若用 WRAP_CONTENT，四个图标的黑色圆底会往下多出几像素、压到投屏画面。
            // 用「显示上下文里的宽度 / 748」算比例，比密度比更直接可靠
            // （服务自身的 resources 密度是物理 480dpi，不是 compat 缩放后的值，用它会算大）
            int appWidth = createDisplayContext(d1).getResources()
                    .getDisplayMetrics().widthPixels;
            float scale = appWidth > 0 ? appWidth / 748f : 1f;
            int bandPx = Math.max(1, Math.round(66f * scale));
            lp.height = bandPx;
            Log.i(TAG, "bar height locked to band = " + bandPx + "px (scale=" + scale + ")");
            lp.x = 0;
            lp.y = 0;
            if (Build.VERSION.SDK_INT >= 28) {
                lp.layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            }
            // 长按整条控件条 = 退出镜像、回到原本的外屏首页（SubHome）
            mBar.setOnLongClickListener(v -> {
                exitToCoverHome();
                return true;
            });
            mWm.addView(mBar, lp);
            Log.i(TAG, "bar overlay added on display 1");
        } catch (Throwable t) {
            Log.e(TAG, "showBar failed", t);
        }
    }

    /** 镜像在最前 → 控制内屏（display 0）；否则先把外屏上的系统界面收掉（display 1）。 */
    private void injectKey(int keyCode) {
        int target = MirrorActivity.isMirrorResumed() ? 0 : 1;
        runShell("input -d " + target + " keyevent " + keyCode);
        Log.i(TAG, "inject key " + keyCode + " -> display " + target);
    }

    /**
     * 退出镜像并回到「原本的外屏首页」。
     *
     * 顺序很关键：
     *   1) 先给 display 1 发 HOME —— 实测这就是 SubHome（com.android.systemui/.subscreen.SubHomeActivity）；
     *   2) 再 force-stop 自己（会带走镜像 Activity 和本悬浮层）。
     * 反过来做的话，HOME 时镜像还在，会被镜像接走。
     */
    private void exitToCoverHome() {
        // 顺序（实测确定）：
        //   1) 广播让镜像自己 finish（不能用 force-stop，也不能 am start SubHomeActivity ——
        //      后者会拉起一个【新实例】，widget 绑不上，外屏只剩壁纸）；
        //   2) 等它真的退出后，再给 display 1 发 HOME —— 回到的是【原本那个 SubHome 实例】，时钟图标都在；
        //   3) 最后关掉自己（控件条消失）。
        try {
            Intent it = new Intent(MirrorActivity.ACTION_EXIT);
            it.setPackage(getPackageName());
            sendBroadcast(it);
        } catch (Throwable ignored) { }

        // 🔴 关键一步：镜像时设备在 state 4（双屏并发），这个状态下 SubHome 的 widget 绑不上，
        //    回到外屏会只剩壁纸。切回 state 1（只开外屏）widget 就正常了 —— 实测这是无缝的核心。
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                runShell("cmd device_state state 1");
            }
        }, 350);

        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                runShell("input -d 1 keyevent " + android.view.KeyEvent.KEYCODE_HOME);
            }
        }, 700);

        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                stopSelf();
            }
        }, 1000);
    }

    private void runShell(String cmd) {
        Shell.execSync(cmd);
    }

    /**
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

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startWatchdog();
        // ⚠️ 不能用 START_STICKY：镜像关了之后系统还会把服务拉起来，
        //    表现就是"软件没开，右上角控件条却还在"（2026-11-10 实测）。
        return START_NOT_STICKY;
    }

    /** 镜像不在了就把自己关掉，保证控件条只在镜像运行时存在。 */
    private void startWatchdog() {
        if (mWatchdog != null) {
            return;
        }
        mWatchdog = new android.os.Handler(android.os.Looper.getMainLooper());
        mWatchdog.postDelayed(mWatchdogTick, 2000);
    }

    private android.os.Handler mWatchdog;
    private int mDeadCount = 0;

    private final Runnable mWatchdogTick = new Runnable() {
        @Override
        public void run() {
            if (!MirrorActivity.isMirroring()) {
                mDeadCount++;
                if (mDeadCount >= 2) {
                    // 🔴 分两种情况，不能一律收回 state 1：
                    //   · 用户主动退出（flag 已删）→ 收回 state 1，退场；
                    //   · 只是关屏/被顶导致临时不可用（flag 还在）→ **保留 state 4 和控件条**，
                    //     否则内屏一关，亮屏后就再也没有画面可投，只能重新点开始镜像。
                    boolean userExited = !new java.io.File(getFilesDir(),
                            MirrorActivity.FLAG_FILE).exists();
                    if (userExited) {
                        Log.i(TAG, "watchdog: user exited -> restore device_state 1");
                        runShell("cmd device_state state 1");
                        stopSelf();
                        return;
                    } else {
                        Log.i(TAG, "watchdog: mirror temporarily down, KEEP device_state 4 + bar");
                        mDeadCount = 0;   // 重置，继续观察，等镜像自己回来
                    }
                }
            } else {
                mDeadCount = 0;
            }
            if (mWatchdog != null) {
                mWatchdog.postDelayed(this, 2000);
            }
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        try {
            if (mScreenOnReceiver != null) {
                unregisterReceiver(mScreenOnReceiver);
            }
        } catch (Throwable ignored) { }
        if (mWatchdog != null) {
            mWatchdog.removeCallbacks(mWatchdogTick);
            mWatchdog = null;
        }
        try {
            if (mWm != null && mBar != null) {
                mWm.removeView(mBar);
            }
        } catch (Throwable ignored) { }
        mWm = null;
        mBar = null;
        super.onDestroy();
    }
}
