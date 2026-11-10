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
 * 外屏右上角控件条（系统悬浮层）。
 *
 * <h3>为什么是悬浮层</h3>
 * 外屏跑的是原生 App（模拟外接屏用法）。App 一全屏，桌面上的导航键就被盖住了，
 * 用户在里面出不来。挂成 {@code TYPE_APPLICATION_OVERLAY} 之后它永远浮在最上层。
 *
 * <h3>四个键</h3>
 * <ul>
 *   <li>‹ 返回 —— {@code input -d 1 keyevent 4}</li>
 *   <li>○ 桌面 —— 把我们的外屏桌面拉回前台</li>
 *   <li>▢ 最近 —— {@code input -d 1 keyevent 187}</li>
 *   <li>✕ 退出 —— 回三星原生的 SubHome（外屏首页）</li>
 * </ul>
 */
public class CoverNavService extends Service {

    private static final String TAG = "FlipCoverNav";
    private static final String CHANNEL = "flipcover_nav";
    private static final int NOTI_ID = 2003;

    /**
     * 圆角遮罩：一块铺满的、**不可触摸**的窗口，只在自己四个角画黑色四分之一圆，
     * 把外屏物理圆角之外的画面遮掉（原生方案没有 Activity 窗口可加 outline，
     * 所以用悬浮层来补这层遮罩）。
     */
    private void addCornerMask(Context dCtx) {
        try {
            CornerMaskView mask = new CornerMaskView(dCtx);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    Build.VERSION.SDK_INT >= 26
                            ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                            : WindowManager.LayoutParams.TYPE_PHONE,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            if (Build.VERSION.SDK_INT >= 28) {
                lp.layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            }
            mWm.addView(mask, lp);
            mMask = mask;
            Log.i(TAG, "corner mask added");
        } catch (Throwable t) {
            Log.w(TAG, "addCornerMask failed: " + t.getMessage());
        }
    }

    /** 只画四个角的黑色圆弧，其余透明。 */
    private static class CornerMaskView extends android.view.View {
        private final android.graphics.Paint mPaint = new android.graphics.Paint();
        private final android.graphics.Path mPath = new android.graphics.Path();

        CornerMaskView(Context c) {
            super(c);
            mPaint.setColor(0xFF000000);
            mPaint.setStyle(android.graphics.Paint.Style.FILL);
            mPaint.setAntiAlias(true);
            setWillNotDraw(false);
        }

        @Override
        protected void onDraw(android.graphics.Canvas canvas) {
            super.onDraw(canvas);
            float w = getWidth(), h = getHeight();
            // 与镜像方案同一口径：半径 ≈ 屏宽 6.4%（748 宽 → 48px）
            float r = w * 0.064f;

            // 左上
            mPath.reset();
            mPath.moveTo(0, 0);
            mPath.lineTo(r, 0);
            mPath.quadTo(0, 0, 0, r);
            mPath.close();
            canvas.drawPath(mPath, mPaint);

            // 右上
            mPath.reset();
            mPath.moveTo(w, 0);
            mPath.lineTo(w - r, 0);
            mPath.quadTo(w, 0, w, r);
            mPath.close();
            canvas.drawPath(mPath, mPaint);

            // 左下
            mPath.reset();
            mPath.moveTo(0, h);
            mPath.lineTo(0, h - r);
            mPath.quadTo(0, h, r, h);
            mPath.close();
            canvas.drawPath(mPath, mPaint);

            // 右下
            mPath.reset();
            mPath.moveTo(w, h);
            mPath.lineTo(w, h - r);
            mPath.quadTo(w, h, w - r, h);
            mPath.close();
            canvas.drawPath(mPath, mPaint);
        }
    }

    private WindowManager mWm;
    private ControlBar mBar;
    private android.view.View mMask;

    @Override
    public void onCreate() {
        super.onCreate();
        startForegroundWithNotification();
        showBar();
    }

    private void startForegroundWithNotification() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null && nm.getNotificationChannel(CHANNEL) == null) {
                NotificationChannel ch = new NotificationChannel(
                        CHANNEL, "外屏控件", NotificationManager.IMPORTANCE_MIN);
                ch.setShowBadge(false);
                nm.createNotificationChannel(ch);
            }
        }
        Notification n = new Notification.Builder(this,
                Build.VERSION.SDK_INT >= 26 ? CHANNEL : null)
                .setContentTitle("FlipMirror 外屏控件")
                .setContentText("右上角悬浮控件条")
                .setSmallIcon(android.R.drawable.ic_menu_sort_by_size)
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 34) {
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
            Context dCtx = createDisplayContext(d1);
            mWm = (WindowManager) dCtx.getSystemService(WINDOW_SERVICE);
            if (mWm == null) {
                return;
            }
            Context themed = new ContextThemeWrapper(dCtx, android.R.style.Theme_DeviceDefault);

            // 先盖圆角遮罩（不可触摸），再加控件条 —— 顺序决定层级：后加的在上
            addCornerMask(dCtx);

            mBar = new ControlBar(themed);
            mBar.setCollapseButtonAsExit(true);

            mBar.setListener(new ControlBar.Listener() {
                @Override public void onBack() {
                    Shell.exec("input -d 1 keyevent 4");
                }
                @Override public void onHome() {
                    // 回我们自己的外屏桌面（不是三星 SubHome）
                    Shell.exec("am start --display 1 -n com.flip.mirror/.CoverLauncherActivity");
                }
                @Override public void onRecents() {
                    Shell.exec("input -d 1 keyevent 187");
                }
                @Override public void onExit() {
                    // 退出外屏桌面：回三星原生 SubHome，并关掉本控件条
                    Shell.exec("am start --display 1 -n com.android.systemui/.subscreen.SubHomeActivity");
                    new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                            () -> stopSelf(), 700);
                }
                @Override public void onCollapse() { }
                @Override public void onExpand() { }
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
            lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
            if (Build.VERSION.SDK_INT >= 28) {
                lp.layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            }
            mWm.addView(mBar, lp);
            Log.i(TAG, "cover nav bar added at top-right of display 1");
        } catch (Throwable t) {
            Log.e(TAG, "showBar failed", t);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        try {
            if (mWm != null && mBar != null) {
                mWm.removeView(mBar);
            }
            if (mWm != null && mMask != null) {
                mWm.removeView(mMask);
            }
        } catch (Throwable ignored) { }
        mWm = null;
        mBar = null;
        super.onDestroy();
    }
}
