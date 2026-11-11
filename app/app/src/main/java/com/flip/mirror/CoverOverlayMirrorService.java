package com.flip.mirror;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.Log;

import java.nio.ByteBuffer;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

/**
 * 兜底通道：**用悬浮层直接把内屏画面画到外屏**，不依赖 root / Shizuku。
 *
 * <h3>为什么需要它</h3>
 * 主通道（{@link MirrorActivity}）依赖 {@code am start --display 1} 把 Activity 送到外屏，
 * 这必须有 shell 权限。一旦 root / Shizuku 不可用，主通道就完全用不了，
 * 外屏只剩 SubHome，等于没法用。
 *
 * <h3>本通道凭什么不需要权限</h3>
 * <ul>
 *   <li>MediaProjection 只需要用户点一次「开始录制」，不需 root</li>
 *   <li>{@code TYPE_APPLICATION_OVERLAY} 只需要 SYSTEM_ALERT_WINDOW（用户授权一次）</li>
 *   <li>{@code createDisplayContext(display 1)} 是公开 API</li>
 * </ul>
 * 三者叠加，就能在**没有任何 shell 能力**的情况下把画面显示到外屏。
 *
 * <h3>代价</h3>
 * 触摸注入仍然需要 shell（{@code InputManager.injectInputEvent}），
 * 所以本通道只保证"看得见"，触摸不可用时会明确提示。
 */
public class CoverOverlayMirrorService extends Service {

    private static final String TAG = "FlipOverlayFallback";
    private static final String CH_ID = "flip_overlay";
    private static final int NOTI_ID = 3001;

    /** 外屏内容区尺寸（与主通道同口径） */
    private static final int CONTENT_W = 748;
    private static final int CONTENT_H = 654;
    /** 顶部黑带（状态栏位置）—— 写死，不参与任何比例换算 */
    private static final int TOP_BAND = 66;

    /**
     * 控件条几何——全部写死。
     *
     * 之前这些值是按 density / 屏宽比例算的，换一档 DPI 或换一个构造路径就会漂，
     * 表现为"控件条不见了 / 跑到别处"。外屏物理尺寸固定 748×720，直接写死最稳。
     */
    private static final int BAR_HEIGHT = 66;      // 正好等于顶部黑带
    private static final int BAR_GRAVITY = Gravity.TOP | Gravity.END;

    /** 圆角卡片：四周内缩（露出黑边）与圆角半径，全部写死 */
    private static final int EDGE_INSET = 8;
    private static final float CARD_RADIUS = 48f;

    // 录屏授权从 Activity 传进来（同进程静态传递）
    private static int sResultCode = 0;
    private static Intent sResultData = null;

    private MediaProjection mProjection;
    private VirtualDisplay mVirtualDisplay;
    private ImageReader mReader;
    private HandlerThread mThread;

    private WindowManager mWm;
    private WindowManager.LayoutParams mWmParams;
    private MirrorCanvasView mView;
    private ControlBar mBar;
    private android.widget.FrameLayout mRoot;
    private Context mDcCtx;
    private Handler mBarWatchdog;

    private int mSrcW = 1080, mSrcH = 1920;   // 内屏实际像素（只用于等比缩放）

    /**
     * 录屏会话是否还活着。
     *
     * 关屏时系统会主动 stop 掉 MediaProjection（日志：projection stopped by system），
     * 画面就冻在最后一帧。所以亮屏时必须重新申请一次。
     */
    private volatile boolean mProjectionAlive = false;

    /** 服务启动时刻：启动过程中点屏会触发 SCREEN_ON，必须屏蔽自愈否则会拉起第二份。 */
    private volatile long mStartAt = 0;

    /** 正在退出：看门狗不得再把控件条补回来。 */
    private volatile boolean mShuttingDown = false;

    /** ✕ 二次确认：第一次点的时间戳。 */
    private volatile long mExitArmedAt = 0;

    /** 悬浮层当前处于"隐藏"状态（窗口还在，只是不可见/不可触）。 */
    private volatile boolean mHidden = false;

    /**
     * 服务是否已经在跑。
     *
     * 用户会从**镜像画面里**的桌面再点一次「投屏到外屏」图标（那个图标在镜像里看得见），
     * 结果起了第二份服务 → 两个全屏悬浮窗叠在一起，画面直接花掉（实测窗口 1→2）。
     * 所以入口必须先问一句：已经在跑就别再起。
     */
    private static volatile boolean sRunning = false;

    /**
     * 用户主动点了 ✕ 退出。
     *
     * 服务是 START_STICKY：stopSelf() 之后系统会把它拉回来，
     * 于是"退出"看起来没生效（实测：点 ✕ 后服务数仍为 1），
     * 再点图标就变成两份服务叠在一起、画面花掉。
     * 用这个标记区分"被系统杀"和"用户要退"。
     */
    private static volatile boolean sUserExited = false;

    /** 看门狗拉起前调用：清掉"用户已退出"，否则 onStartCommand 会立刻自我停止。 */
    public static void clearUserExited() {
        sUserExited = false;
    }

    public static boolean isRunning() {
        return sRunning;
    }

    /**
     * 关屏保活用的唤醒锁。
     *
     * 抄的是阿田的做法（反编译它的 APK 看到 {@code Atian:ExternalDisplayWakeLock}）：
     * 三星在关屏时会把前台服务一起回收（实测 dumpsys services 归 0），
     * 持一把 PARTIAL 锁能显著降低被回收概率，恢复链才接得上。
     */
    private android.os.PowerManager.WakeLock mWakeLock;
    private android.content.BroadcastReceiver mScreenOn;

    /** 由 Activity 调用：带着录屏授权启动兜底渲染。 */
    public static void start(Context ctx, int resultCode, Intent data) {
        sUserExited = false;
        sResultCode = resultCode;
        sResultData = data;
        Intent it = new Intent(ctx, CoverOverlayMirrorService.class);
        if (Build.VERSION.SDK_INT >= 26) {
            ctx.startForegroundService(it);
        } else {
            ctx.startService(it);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        sRunning = true;
        mStartAt = android.os.SystemClock.elapsedRealtime();
        // 落盘标记：告诉看门狗"用户期望正在投屏"。进程被杀也不丢。
        try {
            MirrorState.setDesired(this, true);
            KeepAliveReceiver.schedule(this);
        } catch (Throwable ignored) { }
        mShuttingDown = false;
        sUserExited = false;
        try {
            new java.io.File(getFilesDir(), "mirror_running.flag").createNewFile();
        } catch (Throwable ignored) { }
        startForegroundWithNotification();
        acquireWakeLock();
        showOverlay();
        startBarWatchdogOnce();
        registerScreenOnRecovery();
    }

    private void startForegroundWithNotification() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null && nm.getNotificationChannel(CH_ID) == null) {
                NotificationChannel ch = new NotificationChannel(
                        CH_ID, "外屏镜像（兜底）", NotificationManager.IMPORTANCE_LOW);
                ch.setShowBadge(false);
                nm.createNotificationChannel(ch);
            }
        }
        Notification n = new Notification.Builder(this,
                Build.VERSION.SDK_INT >= 26 ? CH_ID : null)
                .setContentTitle("FlipMirror")
                .setContentText("外屏镜像运行中（兜底通道）")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTI_ID, n,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTI_ID, n);
        }
    }

    /**
     * 亮屏 → 若录屏会话已被系统掐掉，就自动重新申请一次。
     *
     * 不需要人点：装机时已经 {@code appops set PROJECT_MEDIA allow}，
     * 所以 {@code createScreenCaptureIntent} 会直接返回 RESULT_OK（实测无弹窗）。
     *
     * 拉起的是 MainActivity（不是直接要授权）：拿授权必须走 Activity 的
     * startActivityForResult，而入口页会用 force_overlay 直接转到本服务。
     * 入口页落在哪个屏都无所谓 —— 画面是我们自己在 display 1 上画的。
     */
    /** 添加控件条（几何写死）。可重复调用：已存在就先移除再加，保证只有一份。 */
    private void addControlBar() {
        try {
            if (mBar != null && mRoot != null) {
                try {
                    mRoot.removeView(mBar);
                } catch (Throwable ignored) { }
                mBar = null;
            }
            if (mDcCtx == null) {
                DisplayManager dm = (DisplayManager) getSystemService(DISPLAY_SERVICE);
                Display d1 = dm == null ? null : dm.getDisplay(1);
                if (d1 == null || mWm == null) {
                    return;
                }
                mDcCtx = createDisplayContext(d1);
            }
            if (mWm == null) {
                return;
            }
            mBar = new ControlBar(new android.view.ContextThemeWrapper(
                    mDcCtx, android.R.style.Theme_DeviceDefault));
            mBar.setCollapseButtonAsExit(true);
            mBar.setListener(new ControlBar.Listener() {
                @Override public void onBack()    { Shell.exec("input -d 0 keyevent 4"); }
                @Override public void onHome()    { Shell.exec("input -d 0 keyevent 3"); }
                @Override public void onRecents() {
                    // KEYCODE_APP_SWITCH(187) 在三星手势导航下是**无效键**（实测按下无任何变化）。
                    // 改为直接前置 quickstep 的 RecentsActivity，实测能起多任务视图。
                    Shell.exec("am start --display 0 -f 0x10000000 --activity-clear-top "
                            + "-n com.sec.android.app.launcher/com.android.quickstep.RecentsActivity");
                }
                @Override public void onExit()    {
                    // 二次确认：悬浮层是全屏单窗口，右上角很窄，点 App 图标时容易误触。
                    // 第一次点只提示，3 秒内再点才真退出（实测误触导致"投屏自己丢了"）。
                    long now = android.os.SystemClock.elapsedRealtime();
                    if (now - mExitArmedAt > 3000) {
                        mExitArmedAt = now;
                        Log.i(TAG, "exit armed, tap again within 3s");
                        // Toast 从应用上下文发出会跑到内屏，外屏看不见。
                        // 改成在悬浮层画布上画，保证显示在外屏。
                        if (mView != null) {
                            mView.showExitHint(3000);
                        }
                        return;
                    }
                    shutdown();
                }
                @Override public void onCollapse() { }
                @Override public void onExpand()   { }
            });
            mBar.useAsOverlay();

            if (mRoot == null) {
                Log.w(TAG, "root not ready, skip bar");
                return;
            }
            android.widget.FrameLayout.LayoutParams blp =
                    new android.widget.FrameLayout.LayoutParams(
                            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                            BAR_HEIGHT, BAR_GRAVITY);
            mRoot.addView(mBar, blp);
            try {
                mBar.bringToFront();
            } catch (Throwable ignored) { }
            Log.i(TAG, "control bar added into root (height " + BAR_HEIGHT + "px)");
        } catch (Throwable t) {
            Log.w(TAG, "addControlBar failed: " + t);
        }
    }

    /**
     * 自愈：每 3 秒确认控件条窗口还在，掉了就补。
     * 用户要求"写死再也不要动" —— 那就不靠任何一次性初始化，掉了自动回来。
     */
    private void startBarWatchdogOnce() {
        if (mBarWatchdog != null) {
            return;   // 已经在跑，不重复起
        }
        startBarWatchdog();
    }

    private void startBarWatchdog() {
        mBarWatchdog = new Handler(getMainLooper());
        mBarWatchdog.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    if (mShuttingDown) {
                        return;
                    }
                    if (mWm != null && (mBar == null || !mBar.isAttachedToWindow())) {
                        Log.w(TAG, "control bar missing, re-adding");
                        addControlBar();
                    } else if (mBar != null) {
                        // 每拍都提一次层级，防止被后加的全屏窗盖住
                        try {
                            mBar.bringToFront();
                        } catch (Throwable ignored) { }
                    }
                } catch (Throwable ignored) { }
                mBarWatchdog.postDelayed(this, 3000);
            }
        }, 3000);
        Log.i(TAG, "bar watchdog started");
    }

    /** 重新读取内屏真实像素（改过 wm size 之后必须重读）。 */
    private void refreshSrcSize() {
        try {
            DisplayManager dm = (DisplayManager) getSystemService(DISPLAY_SERVICE);
            Display d0 = dm == null ? null : dm.getDisplay(Display.DEFAULT_DISPLAY);
            if (d0 == null) {
                return;
            }
            android.util.DisplayMetrics m = new android.util.DisplayMetrics();
            d0.getRealMetrics(m);
            if (m.widthPixels > 0 && m.heightPixels > 0) {
                mSrcW = m.widthPixels;
                mSrcH = m.heightPixels;
                Log.i(TAG, "inner size now " + mSrcW + "x" + mSrcH);
            }
        } catch (Throwable ignored) { }
    }

    private void acquireWakeLock() {
        try {
            android.os.PowerManager pm =
                    (android.os.PowerManager) getSystemService(POWER_SERVICE);
            if (pm == null) {
                return;
            }
            mWakeLock = pm.newWakeLock(
                    android.os.PowerManager.PARTIAL_WAKE_LOCK,
                    "FlipMirror:ExternalDisplayWakeLock");
            mWakeLock.setReferenceCounted(false);
            mWakeLock.acquire();
            Log.i(TAG, "wake lock acquired");
        } catch (Throwable t) {
            Log.w(TAG, "acquire wake lock failed: " + t);
        }
    }

    private void registerScreenOnRecovery() {
        try {
            mScreenOn = new android.content.BroadcastReceiver() {
                @Override
                public void onReceive(android.content.Context c, Intent i) {
                    if (i == null || !Intent.ACTION_SCREEN_ON.equals(i.getAction())) {
                        return;
                    }
                    // 启动后 15 秒内忽略：这段时间是我们自己刚把屏点亮的，
                    // 自愈再拉一次会起第二份服务（实测窗口 1->2、黑屏）。
                    if (android.os.SystemClock.elapsedRealtime() - mStartAt < 15000) {
                        Log.i(TAG, "screen on during startup, skip recovery");
                        return;
                    }
                    // 外屏必须每次亮屏都重新点亮：关屏会撤掉双屏并发，
                    // 与外屏当前显示什么无关（录屏还活着也一样会黑）。
                    if (Shell.available()) {
                        boolean ok = Shell.execSync("cmd device_state state 4");
                        Log.i(TAG, "screen on: device_state 4 -> " + ok);
                    } else {
                        Log.w(TAG, "screen on: no shell, cover display stays off");
                    }
                    if (mProjectionAlive) {
                        Log.i(TAG, "screen on: projection alive, only display re-enabled");
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

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 已经在跑（退出后隐藏着）：直接显示回来，不重建窗口、不重新申请录屏
        if (mRoot != null && sRunning) {
            showAgain();
            if (!mProjectionAlive) {
                startProjection();
            }
            return START_STICKY;
        }
        if (sUserExited) {
            Log.i(TAG, "user exited earlier, stay stopped");
            stopSelf();
            return START_NOT_STICKY;
        }
        startProjection();
        // 关屏时三星会把前台服务杀掉（实测服务数归 0）。用 STICKY 让系统拉回来，
        // 拉回来时没有授权数据 → 上面的分支会自动重新申请。
        return START_STICKY;
    }

    /* ------------------------------------------------------------ 悬浮层 */

    /**
     * 按设置把整个界面上下翻转。
     * 用 View 的 180° 旋转而不是改布局：旋转同时作用于绘制与触摸命中，
     * 画面、控件条、提示条会一起翻过去，坐标换算不用另写一套。
     */
    private void applyFlipIfNeeded() {
        try {
            if (mRoot == null) {
                return;
            }
            boolean flip = new SettingsStore(this).flip180();
            mRoot.setRotation(flip ? 180f : 0f);
            Log.i(TAG, "flip180 = " + flip);
        } catch (Throwable t) {
            Log.w(TAG, "applyFlip failed: " + t);
        }
    }

    private void showOverlay() {
        // 重入锁：已经在显示就直接返回。
        // 启动阶段这个方法会被调用多次（服务被创建多次 / 恢复路径），
        // 每次重建都会让屏幕闪一下（实测：窗口数 1→2，中间露黑底 3.8KB 帧）。
        if (mRoot != null && mRoot.isAttachedToWindow()) {
            Log.i(TAG, "overlay already shown, skip rebuild");
            return;
        }
        try {
            DisplayManager dm = (DisplayManager) getSystemService(DISPLAY_SERVICE);
            Display d1 = dm == null ? null : dm.getDisplay(1);
            if (d1 == null) {
                Log.w(TAG, "display 1 not found, fallback unavailable");
                stopSelf();
                return;
            }
            Context dCtx = createDisplayContext(d1);
            mDcCtx = dCtx;
            mWm = (WindowManager) dCtx.getSystemService(WINDOW_SERVICE);
            if (mWm == null) {
                stopSelf();
                return;
            }

            // 内屏实际像素，用来等比缩放（不改 wm size，因为那需要 shell）
            DisplayManager dm0 = (DisplayManager) getSystemService(DISPLAY_SERVICE);
            Display d0 = dm0 == null ? null : dm0.getDisplay(Display.DEFAULT_DISPLAY);
            if (d0 != null) {
                android.util.DisplayMetrics m = new android.util.DisplayMetrics();
                d0.getRealMetrics(m);
                if (m.widthPixels > 0 && m.heightPixels > 0) {
                    mSrcW = m.widthPixels;
                    mSrcH = m.heightPixels;
                }
            }

            mView = new MirrorCanvasView(dCtx);

            mWmParams = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    Build.VERSION.SDK_INT >= 26
                            ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                            : WindowManager.LayoutParams.TYPE_PHONE,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                            | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                    PixelFormat.TRANSLUCENT);
            mWmParams.gravity = Gravity.TOP | Gravity.START;
            if (Build.VERSION.SDK_INT >= 28) {
                mWmParams.layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            }
            // 🔴 单窗口方案：画面和控件条放进同一个悬浮窗。
            //    之前是两个独立悬浮窗，内容窗是 fill×fill，只要它比控件条晚加/后聚焦，
            //    就会盖住控件条 —— 点上去的事件落到内容层、被当成触摸注入到内屏，
            //    表现就是"右上角四个键很难按到"（实测）。单窗口没有层级竞争。
            // 去重：上一次的根容器若还在，先摘掉，避免叠出多个全屏窗
            if (mRoot != null && mWm != null) {
                try {
                    mWm.removeView(mRoot);
                } catch (Throwable ignored) { }
                mRoot = null;
            }
            mRoot = new android.widget.FrameLayout(dCtx);
            mRoot.setClipChildren(false);
            mRoot.addView(mView, new android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT));
            // 先透明挂上：启动过程中会改 wm size / 点亮外屏，画面会重排几下，
            // 直接可见就会"来回跳"（实测）。等第一帧到了再显示。
            mRoot.setAlpha(0f);
            applyFlipIfNeeded();
            mWm.addView(mRoot, mWmParams);

            addControlBar();

            Log.i(TAG, "overlay mirror shown on display 1 (src=" + mSrcW + "x" + mSrcH + ")");
        } catch (Throwable t) {
            Log.e(TAG, "showOverlay failed", t);
            stopSelf();
        }
    }

    /* ------------------------------------------------------------ 录屏 */

    private void startProjection() {
        // 🔴 重入保护：关屏恢复会再次调用本方法。旧的 VirtualDisplay / ImageReader
        //    不释放的话，两路帧会都往同一个 View 上画，画面叠影（实测状态栏出现两份）。
        releaseProjection();
        if (sResultData == null) {
            // 被系统杀掉后重启（START_STICKY）时，静态通道里的授权早就没了。
            // 与其停掉，不如自己把入口页拉起来重新取一次授权（免弹窗，appops 已放行）。
            Log.w(TAG, "no projection data, re-acquire via MainActivity");
            try {
                Intent it = new Intent(this, MainActivity.class);
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                it.putExtra("auto_start", true);
                it.putExtra("force_overlay", true);
                startActivity(it);
            } catch (Throwable t) {
                Log.w(TAG, "re-acquire failed: " + t);
            }
            return;
        }
        try {
            MediaProjectionManager mpm =
                    (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            mProjection = mpm.getMediaProjection(sResultCode, sResultData);
            if (mProjection == null) {
                Log.w(TAG, "getMediaProjection returned null");
                stopSelf();
                return;
            }

            // 🔴 先把内屏调成与外屏内容区同比例（748:654 ≈ 1.144），否则画面必然出现黑边：
            //    投屏是按内屏真实像素来的，比例不一致时怎么缩放都填不满。
            if (Shell.available()) {
                SettingsStore st = new SettingsStore(this);
                String prep = "cmd device_state state 4; sleep 0.8; wm size "
                        + st.innerWidth() + "x" + st.innerHeight()
                        + "; wm density " + st.innerDpi();
                boolean ok = Shell.execSync(prep);
                Log.i(TAG, "prepare inner display -> " + ok);
                try {
                    Thread.sleep(1200);
                } catch (InterruptedException ignored) { }
                refreshSrcSize();
            }

            // 采集分辨率减半，降低内存与拷贝开销；显示时再放大到内容区
            int capW = Math.max(1, mSrcW / 2);
            int capH = Math.max(1, mSrcH / 2);
            mReader = ImageReader.newInstance(capW, capH, PixelFormat.RGBA_8888, 2);

            mThread = new HandlerThread("flip-overlay-capture");
            mThread.start();
            Handler h = new Handler(mThread.getLooper());

            mReader.setOnImageAvailableListener(reader -> {
                Image img = null;
                try {
                    img = reader.acquireLatestImage();
                    if (img == null) {
                        return;
                    }
                    Bitmap bmp = Bitmap.createBitmap(capW, capH, Bitmap.Config.ARGB_8888);
                    Image.Plane plane = img.getPlanes()[0];
                    ByteBuffer buf = plane.getBuffer();
                    buf.rewind();

                    // 🔴 必须处理 rowStride：ImageReader 的每行在 buffer 里往往带填充，
                    //    rowStride != width×pixelStride。直接 copyPixelsFromBuffer 会当成
                    //    紧凑排布读，画面就横向拉丝（实测整屏斜纹）。
                    int rowStride = plane.getRowStride();
                    int pixelStride = plane.getPixelStride();
                    int rowBytes = capW * pixelStride;
                    if (pixelStride == 4 && rowStride == rowBytes) {
                        bmp.copyPixelsFromBuffer(buf);
                    } else {
                        byte[] dst = new byte[capW * capH * 4];
                        byte[] row = new byte[rowBytes];
                        for (int y = 0; y < capH; y++) {
                            buf.position(y * rowStride);
                            buf.get(row, 0, rowBytes);
                            System.arraycopy(row, 0, dst, y * capW * 4, rowBytes);
                        }
                        bmp.copyPixelsFromBuffer(ByteBuffer.wrap(dst));
                    }
                    final Bitmap out = bmp;
                    new Handler(getMainLooper()).post(() -> {
                        if (mView != null) {
                            mView.setBitmap(out);
                        }
                    });
                } catch (Throwable t) {
                    Log.w(TAG, "frame dropped: " + t.getMessage());
                } finally {
                    if (img != null) {
                        img.close();
                    }
                }
            }, h);

            // 🔴 Android 14+ 强制要求：createVirtualDisplay 之前必须先 registerCallback，
            //    否则抛 IllegalStateException: Must register a callback before starting capture。
            mProjection.registerCallback(new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    Log.i(TAG, "projection stopped by system");
                    mProjectionAlive = false;
                }
            }, new Handler(getMainLooper()));

            // 🔴 必须用 VirtualDisplayConfig 显式指定"镜像 display 0（内屏）"。
            //    只用 VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR 时，镜像的是系统"默认屏"；
            //    而 device_state 4（双屏并发）下默认屏会被切到外屏，
            //    结果把外屏自己拍进去 → 画面无限递归嵌套（实测踩过：满屏自己的控件条）。
            mVirtualDisplay = createVdMirroringInner(capW, capH, h);

            mProjectionAlive = true;
            Log.i(TAG, "projection started " + capW + "x" + capH);
        } catch (Throwable t) {
            Log.e(TAG, "startProjection failed", t);
        }
    }

    /**
     * 创建「明确镜像内屏(display 0)」的虚拟屏。
     *
     * 只用 {@code VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR} 时镜像的是系统"默认屏"；
     * 双屏并发（device_state 4）下默认屏会被切到外屏，于是把外屏自己拍进去，
     * 画面就成了无限递归嵌套（实测：满屏都是我们自己的控件条）。
     *
     * {@code VirtualDisplayConfig.Builder#setDisplayIdToMirror} 是隐藏 API，
     * 所以整条链走反射；任何一步失败就退回旧调用（能显示，但可能递归）。
     */
    private VirtualDisplay createVdMirroringInner(int capW, int capH, Handler h) {
        try {
            Class<?> builderCls = Class.forName("android.hardware.display.VirtualDisplayConfig$Builder");
            Object builder = builderCls
                    .getConstructor(String.class, int.class, int.class, int.class)
                    .newInstance("flip-overlay", capW, capH,
                            getResources().getDisplayMetrics().densityDpi);
            builderCls.getMethod("setFlags", int.class)
                    .invoke(builder, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR);
            builderCls.getMethod("setSurface", android.view.Surface.class)
                    .invoke(builder, mReader.getSurface());

            boolean pinned = false;
            try {
                builderCls.getMethod("setDisplayIdToMirror", int.class)
                        .invoke(builder, Display.DEFAULT_DISPLAY);
                pinned = true;
            } catch (Throwable t) {
                Log.w(TAG, "setDisplayIdToMirror unavailable: " + t);
            }

            Object cfg = builderCls.getMethod("build").invoke(builder);
            Class<?> cfgCls = Class.forName("android.hardware.display.VirtualDisplayConfig");

            java.lang.reflect.Method m = MediaProjection.class.getMethod(
                    "createVirtualDisplay", cfgCls, MediaProjection.Callback.class, Handler.class);
            Object vd = m.invoke(mProjection, cfg, null, h);
            Log.i(TAG, "VD created, pinned to display 0 = " + pinned);
            return (VirtualDisplay) vd;
        } catch (Throwable t) {
            Log.w(TAG, "pinned VD failed, fallback to AUTO_MIRROR: " + t);
        }
        return mProjection.createVirtualDisplay(
                "flip-overlay", capW, capH,
                getResources().getDisplayMetrics().densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                mReader.getSurface(), null, h);
    }

    /** 释放上一次的采集资源（虚拟屏 / ImageReader / 线程）。 */
    private void releaseProjection() {
        try {
            if (mVirtualDisplay != null) {
                mVirtualDisplay.release();
                mVirtualDisplay = null;
            }
            if (mReader != null) {
                mReader.setOnImageAvailableListener(null, null);
                mReader.close();
                mReader = null;
            }
            if (mThread != null) {
                mThread.quitSafely();
                mThread = null;
            }
        } catch (Throwable ignored) { }
    }

    /** 把已存在的悬浮层重新显示出来（退出后再进入走这条，不重建窗口）。 */
    private void showAgain() {
        mHidden = false;
        try {
            if (mRoot != null && mWm != null) {
                applyFlipIfNeeded();
                mRoot.setAlpha(1f);
                mWmParams.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
                mWm.updateViewLayout(mRoot, mWmParams);
                Log.i(TAG, "mirror shown again (reused window)");
            }
        } catch (Throwable t) {
            Log.w(TAG, "showAgain failed: " + t);
        }
        try {
            MirrorState.setDesired(this, true);
            KeepAliveReceiver.schedule(this);
        } catch (Throwable ignored) { }
        startBarWatchdogOnce();
    }

    private void shutdown() {
        // 🔴 退出 = **隐藏**，不是拆除。
        // 拆除全屏悬浮层时，系统会把 SubHome 从绘制列表里摘掉；
        // 之后再请回来它是"resumed 但不绘制"，外屏永远停在壁纸（今天试了三种办法都不行）。
        // 隐藏则不碰窗口栈，SubHome 一直在下面活着，立刻可见。
        mHidden = true;
        sUserExited = false;          // 允许下次直接再显示
        try {
            MirrorState.setDesired(this, false);
            KeepAliveReceiver.cancel(this);
        } catch (Throwable ignored) { }

        try {
            if (mRoot != null && mWm != null) {
                mRoot.setAlpha(0f);
                mWmParams.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
                mWm.updateViewLayout(mRoot, mWmParams);
            }
        } catch (Throwable t) {
            Log.w(TAG, "hide failed: " + t);
        }
        // 不 stopSelf、不 removeView —— 服务与窗口都留着，下次秒回
        // 隐藏后还要催一次 SubHome：它被全屏层盖住期间会停止绘制，
        // 只把层藏起来它不会自己重画（实测露出来还是壁纸）。发 HOME 让它恢复。
        try {
            new Thread(() -> {
                // 实测：只隐藏、只发 HOME 都恢复不了 SubHome（它被全屏层盖过后不再绘制）。
                // 唯一验证过的组合是"退回单屏(state 1)让系统自己收掉外屏"，
                // 用户按电源时由系统重新拉起 SubHome —— 早上就是这样工作的。
                // 用户的长按电源绑在阿田的 PowerAssistActivity 上，那个能正确把外屏首页拉回来。
                // ✕ 直接复用它 —— 与其自己猜怎么恢复 SubHome，不如走用户已经验证过的那条路。
                // 不再回 state 1：一退回单屏，外屏被系统收掉，下次点图标又难落屏。
                // 保持双屏并发，只让阿田把外屏首页拉回来 —— 实测这条能立刻回首页，
                // 而且下次点图标能正常进（0.9.3 验证过的那条路）。
                // 矩阵里唯一没试过的组合：先退回单屏 + 阿田拉回首页（首页能正确渲染），
                // 然后立刻恢复双屏 —— 这样首页留着，display 1 也在，
                // 下次点图标既能落到外屏、又有栈顶可依。
                // 结论（今天验证到底）：外屏首页 SubHome **只在单屏模式(state 1)下存在**。
                // 一旦回到双屏并发(state 4)，外屏就是块"空地"，只有壁纸。
                // 所以退出只能停在 state 1 —— 首页正常；要再投屏时由入口页重新置 4。
                boolean ok = Shell.execSync(
                        "cmd device_state state 1; sleep 0.4; "
                        + "am start --display 1 -n com.tianweixing.atianwidget/.PowerAssistActivity");
                Log.i(TAG, "hide: home on cover -> " + ok);
            }, "hide-home").start();
        } catch (Throwable ignored) { }
        Log.i(TAG, "mirror hidden (window kept)");
    }

    @Override
    public void onDestroy() {
        // 🔴 必须移除悬浮 View：服务被系统回收后 View 仍挂在窗口上，
        //    下次启动会再叠一层 —— 画面出现重影/两份状态栏（实测踩过）。
        try {
            if (mBar != null && mRoot != null) {
                mRoot.removeView(mBar);
            }
        } catch (Throwable ignored) { }
        try {
            if (mRoot != null && mWm != null) {
                mWm.removeView(mRoot);
                mRoot = null;
            }
        } catch (Throwable ignored) { }
        mBar = null;
        mView = null;
        try {
            if (mVirtualDisplay != null) {
                mVirtualDisplay.release();
            }
            if (mReader != null) {
                mReader.close();
            }
            if (mProjection != null) {
                mProjection.stop();
            }
            if (mThread != null) {
                mThread.quitSafely();
            }
        } catch (Throwable ignored) { }
        try {
            if (mScreenOn != null) {
                unregisterReceiver(mScreenOn);
            }
        } catch (Throwable ignored) { }
        try {
            if (mWakeLock != null && mWakeLock.isHeld()) {
                mWakeLock.release();
            }
        } catch (Throwable ignored) { }
        mWakeLock = null;
        try {
            new java.io.File(getFilesDir(), "mirror_running.flag").delete();
        } catch (Throwable ignored) { }
        mProjectionAlive = false;
        sRunning = false;
        sResultCode = 0;
        sResultData = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /* ------------------------------------------------------------ 画面 */

    /**
     * 负责画：顶部黑带 + 内容区（等比缩放、圆角裁剪 + 触摸转发）。
     */
    private class MirrorCanvasView extends View {

        private final Paint mPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final Paint mBlack = new Paint();
        private final Paint mHintBg = new Paint();
        private final Paint mHintText = new Paint();
        private final Paint mHintTextCenter = new Paint();

        /** 退出提示的显示截止时刻（0 = 不显示）。 */
        private long mExitHintUntil = 0;

        /** 是否有一次触摸手势正在进行（保证 DOWN/UP 成对，防内屏指针卡住）。 */
        private boolean mGestureActive = false;
        private final Path mClip = new Path();
        private Bitmap mBmp;
        private final Rect mDst = new Rect();
        private final Rect mCardRect = new Rect();

        /** 右上角圆角开关（每秒刷新一次，避免每帧读偏好）。 */
        private boolean mShowTrCorner = true;
        private long mCornerReadAt = 0;

        /** 触摸注入器全进程共享一份：每次建层都 new 一个会重复连 Shizuku UserService
         *  （实测一次启动连了 5 次），既慢又浪费。 */
        private static TouchInjector sTouch;
        private TouchInjector mTouch;

        MirrorCanvasView(Context c) {
            super(c);
            mBlack.setColor(Color.BLACK);
            mHintBg.setColor(0xCC000000);
            mHintText.setColor(0xFFFFC24B);
            mHintText.setTextSize(26f);
            mHintText.setAntiAlias(true);
            mHintTextCenter.setTextSize(34f);
            mHintTextCenter.setColor(0xFFFFC24B);
            mHintTextCenter.setAntiAlias(true);
            mHintTextCenter.setTextAlign(android.graphics.Paint.Align.CENTER);
            mBlack.setStyle(Paint.Style.FILL);
            if (sTouch == null) {
                sTouch = new TouchInjectorImpl();
            }
            mTouch = sTouch;
            mTouch.init();
            // 本视图已经把手势坐标换算成"内屏像素坐标"，这里让注入器做恒等映射，
            // 避免再乘一次比例（双重换算 → 点击位置整体偏掉）
            mTouch.setGeometry(mSrcW, mSrcH, mSrcW, mSrcH);
        }

        void showExitHint(long ms) {
            mExitHintUntil = android.os.SystemClock.elapsedRealtime() + ms;
            postInvalidate();
        }

        void setBitmap(Bitmap b) {
            // 只有"未隐藏"时才自动显形。否则每帧都会把 alpha 拉回 1，
            // 把 shutdown() 的隐藏覆盖掉（实测：日志说 hidden，画面照旧）。
            if (!mHidden && mRoot != null && mRoot.getAlpha() < 1f) {
                mRoot.setAlpha(1f);
                Log.i(TAG, "first frame, overlay visible");
            }
            Bitmap old = mBmp;
            mBmp = b;
            invalidate();
            if (old != null && old != b && !old.isRecycled()) {
                old.recycle();
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int w = getWidth();
            int h = getHeight();

            // 整窗铺黑：顶部黑带 + 需要遮掉的角都靠它
            canvas.drawRect(0, 0, w, h, mBlack);


            if (mBmp == null) {
                return;
            }

            // 内容铺满到边（不留内缩）：物理屏左/下/右下本来就带圆角，
            // 只有"右上角"在顶部黑带与画面交界处会露出直角，需要单独补。
            float availW = w;
            float availH = h - TOP_BAND;
            float scale = Math.max(availW / mBmp.getWidth(), availH / mBmp.getHeight());
            int dw = Math.round(mBmp.getWidth() * scale);
            int dh = Math.round(mBmp.getHeight() * scale);
            int left = Math.round((availW - dw) / 2f);
            int top = TOP_BAND + Math.round((availH - dh) / 2f);
            mDst.set(left, top, left + dw, top + dh);
            mSrcRect.set(0, 0, mBmp.getWidth(), mBmp.getHeight());

            // 只把**右上角**裁成圆角，其余三角保持直角（跟随物理屏弧线）。
            // radii 顺序：左上x,y · 右上x,y · 右下x,y · 左下x,y
            // 开关：每秒最多读一次偏好，关掉就四角全直角
            long now = android.os.SystemClock.elapsedRealtime();
            if (now - mCornerReadAt > 1000) {
                mCornerReadAt = now;
                try {
                    mShowTrCorner = new SettingsStore(getContext()).cornerMaskOn();
                } catch (Throwable ignored) { }
            }
            float trR = mShowTrCorner ? CARD_RADIUS : 0f;
            float[] radii = new float[]{0f, 0f, trR, trR, 0f, 0f, 0f, 0f};
            mClip.reset();
            mClip.addRoundRect(new RectF(0, TOP_BAND, w, h), radii, Path.Direction.CW);
            canvas.save();
            canvas.clipPath(mClip);
            canvas.drawBitmap(mBmp, mSrcRect, mDst, mPaint);
            canvas.restore();
        
            // 退出二次确认提示：必须画在画面之上（Toast 会跑到内屏，看不见）
            if (mExitHintUntil > 0) {
                if (android.os.SystemClock.elapsedRealtime() < mExitHintUntil) {
                    canvas.drawRect(0, TOP_BAND, w, TOP_BAND + 78, mHintBg);
                    canvas.drawText("再点一次退出", w / 2f, TOP_BAND + 50, mHintTextCenter);
                    postInvalidateDelayed(150);
                } else {
                    mExitHintUntil = 0;
                }
            }
        }

        private final Rect mSrcRect = new Rect();

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (mBmp == null) {
                return true;
            }
            // 画面坐标 → 内屏坐标
            // 注意：手指滑出画面区**不能直接 return** —— 那样 ACTION_UP 会丢，
            // 内屏认为手指一直按着，之后所有点击/滑动都被并进同一次手势
            // （表现：点不动、滑不动）。越界就把坐标夹到边界，事件照发。
            float vx = e.getX() - mDst.left;
            float vy = e.getY() - mDst.top;
            vx = Math.max(0f, Math.min(vx, mDst.width()));
            vy = Math.max(0f, Math.min(vy, mDst.height()));
            float sx = vx * mSrcW / (float) mDst.width();
            float sy = vy * mSrcH / (float) mDst.height();

            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    mGestureActive = true;
                    mTouch.send(sx, sy, MotionEvent.ACTION_DOWN);
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (mGestureActive) {
                        mTouch.send(sx, sy, MotionEvent.ACTION_MOVE);
                    }
                    break;
                case MotionEvent.ACTION_POINTER_DOWN:
                case MotionEvent.ACTION_POINTER_UP:
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (mGestureActive) {
                        mGestureActive = false;
                        mTouch.send(sx, sy, MotionEvent.ACTION_UP);
                    }
                    break;
                default:
                    break;
            }
            return true;
        }
    }
}
