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
    /** 顶部黑带（状态栏位置） */
    private static final int TOP_BAND = 66;

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

    private int mSrcW = 1080, mSrcH = 1920;   // 内屏实际像素（只用于等比缩放）

    /**
     * 录屏会话是否还活着。
     *
     * 关屏时系统会主动 stop 掉 MediaProjection（日志：projection stopped by system），
     * 画面就冻在最后一帧。所以亮屏时必须重新申请一次。
     */
    private volatile boolean mProjectionAlive = false;

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
        startForegroundWithNotification();
        acquireWakeLock();
        showOverlay();
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
        startProjection();
        // 关屏时三星会把前台服务杀掉（实测服务数归 0）。用 STICKY 让系统拉回来，
        // 拉回来时没有授权数据 → 上面的分支会自动重新申请。
        return START_STICKY;
    }

    /* ------------------------------------------------------------ 悬浮层 */

    private void showOverlay() {
        try {
            DisplayManager dm = (DisplayManager) getSystemService(DISPLAY_SERVICE);
            Display d1 = dm == null ? null : dm.getDisplay(1);
            if (d1 == null) {
                Log.w(TAG, "display 1 not found, fallback unavailable");
                stopSelf();
                return;
            }
            Context dCtx = createDisplayContext(d1);
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
            mWm.addView(mView, mWmParams);

            // 控件条（浮在画面之上）
            mBar = new ControlBar(new android.view.ContextThemeWrapper(
                    dCtx, android.R.style.Theme_DeviceDefault));
            mBar.setCollapseButtonAsExit(true);
            mBar.setListener(new ControlBar.Listener() {
                @Override public void onBack()    { Shell.exec("input -d 0 keyevent 4"); }
                @Override public void onHome()    { Shell.exec("input -d 0 keyevent 3"); }
                @Override public void onRecents() { Shell.exec("input -d 0 keyevent 187"); }
                @Override public void onExit()    { shutdown(); }
                @Override public void onCollapse() { }
                @Override public void onExpand()   { }
            });

            WindowManager.LayoutParams blp = new WindowManager.LayoutParams();
            blp.type = mWmParams.type;
            blp.format = PixelFormat.TRANSLUCENT;
            blp.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                    | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
            blp.gravity = Gravity.TOP | Gravity.END;
            blp.width = WindowManager.LayoutParams.WRAP_CONTENT;
            blp.height = Math.max(1, Math.round(TOP_BAND
                    * (dCtx.getResources().getDisplayMetrics().density / (340f / 160f))));
            mBar.useAsOverlay();
            mWm.addView(mBar, blp);

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

    private void shutdown() {
        try {
            if (mBar != null && mWm != null) {
                mWm.removeView(mBar);
            }
        } catch (Throwable ignored) { }
        try {
            if (mView != null && mWm != null) {
                mWm.removeView(mView);
            }
        } catch (Throwable ignored) { }
        stopSelf();
    }

    @Override
    public void onDestroy() {
        // 🔴 必须移除悬浮 View：服务被系统回收后 View 仍挂在窗口上，
        //    下次启动会再叠一层 —— 画面出现重影/两份状态栏（实测踩过）。
        try {
            if (mBar != null && mWm != null) {
                mWm.removeView(mBar);
            }
        } catch (Throwable ignored) { }
        try {
            if (mView != null && mWm != null) {
                mWm.removeView(mView);
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
        mProjectionAlive = false;
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
        private final Path mClip = new Path();
        private Bitmap mBmp;
        private final Rect mDst = new Rect();

        private final TouchInjector mTouch = new TouchInjectorImpl();

        MirrorCanvasView(Context c) {
            super(c);
            mBlack.setColor(Color.BLACK);
            mHintBg.setColor(0xCC000000);
            mHintText.setColor(0xFFFFC24B);
            mHintText.setTextSize(26f);
            mHintText.setAntiAlias(true);
            mBlack.setStyle(Paint.Style.FILL);
            mTouch.init();
            // 本视图已经把手势坐标换算成"内屏像素坐标"，这里让注入器做恒等映射，
            // 避免再乘一次比例（双重换算 → 点击位置整体偏掉）
            mTouch.setGeometry(mSrcW, mSrcH, mSrcW, mSrcH);
        }

        void setBitmap(Bitmap b) {
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

            // 顶部黑带
            canvas.drawRect(0, 0, w, TOP_BAND, mBlack);

            if (mBmp == null) {
                return;
            }

            // 内容区：**铺满裁切**（取较大的缩放比），宁可裁边也不留透明边。
            // 之前用 Math.min（等比适应）会在比例不一致时露出底下的壁纸，看着像"没撑满"。
            float availW = w;
            float availH = h - TOP_BAND;
            float scale = Math.max(availW / mBmp.getWidth(), availH / mBmp.getHeight());
            int dw = Math.round(mBmp.getWidth() * scale);
            int dh = Math.round(mBmp.getHeight() * scale);
            int left = Math.round((availW - dw) / 2f);
            int top = TOP_BAND + Math.round((availH - dh) / 2f);

            mDst.set(left, top, left + dw, top + dh);
            mSrcRect.set(0, 0, mBmp.getWidth(), mBmp.getHeight());

            // 整块窗口裁圆角：物理外屏 r=49px（dumpsys RoundedCorners 实测），
            // 只裁内容矩形的话，四角会露出底下的壁纸，也是"遮罩不对"的来源之一。
            float r = Math.min(w, h) * 0.068f;
            mClip.reset();
            mClip.addRoundRect(new RectF(0, 0, w, h), r, r, Path.Direction.CW);
            canvas.save();
            canvas.clipPath(mClip);
            canvas.drawBitmap(mBmp, mSrcRect, mDst, mPaint);
            canvas.restore();

            // 触摸不可用时明确写出来：注入需要 shell（InputManager.injectInputEvent），
            // 兜底通道只保证看得见，不保证点得动。用户必须知道这一点。
            if (!Shell.available()) {
                float ty = h - 14;
                canvas.drawRect(0, ty - 34, w, h, mHintBg);
                canvas.drawText("兜底模式：可看不可触（Shizuku 未运行）", 16, ty - 8, mHintText);
            }
        }

        private final Rect mSrcRect = new Rect();

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (mBmp == null) {
                return true;
            }
            // 画面坐标 → 内屏坐标
            float vx = e.getX() - mDst.left;
            float vy = e.getY() - mDst.top;
            if (vx < 0 || vy < 0 || vx > mDst.width() || vy > mDst.height()) {
                return true;
            }
            float sx = vx * mSrcW / (float) mDst.width();
            float sy = vy * mSrcH / (float) mDst.height();

            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    mTouch.send(sx, sy, MotionEvent.ACTION_DOWN);
                    break;
                case MotionEvent.ACTION_MOVE:
                    mTouch.send(sx, sy, MotionEvent.ACTION_MOVE);
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    mTouch.send(sx, sy, MotionEvent.ACTION_UP);
                    break;
                default:
                    break;
            }
            return true;
        }
    }
}
