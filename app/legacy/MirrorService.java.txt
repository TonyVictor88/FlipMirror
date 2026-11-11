package com.flip.mirror;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

/**
 * 前台服务：目前只用于在镜像运行时保活（避免被 LMKD 杀掉）。
 * 真正持有 MediaProjection 的是 MirrorActivity（因为需要 SurfaceView）。
 */
public class MirrorService extends Service {

    private static final String TAG = "FlipMirror";
    private static final String CH_ID = "flip_mirror";
    private static final int NOTI_ID = 1001;

    /**
     * 亮屏监听：把被 SubHome 顶掉的镜像拉回来。
     *
     * 为什么挂在 MirrorService 而不是 BarOverlayService／:wd 看门狗：
     * - BarOverlayService 实测加完控件条后会自己消失（服务列表里查不到），监听跟着丢；
     * - :wd 独立进程里 Shizuku 通道不可用（shell 一直 failed），拉了也白拉；
     * - MirrorService 是 mediaProjection 类型的前台服务，镜像期间必然存活，且在主进程。
     */
    private android.content.BroadcastReceiver mScreenOn;

    /** 去抖：避免同一时刻发起多次恢复（多次会抢同一个录屏授权）。 */
    private static long sLastRelaunch = 0L;
    private static boolean sRelaunching = false;

    @Override
    public void onCreate() {
        super.onCreate();
        // ⚠️ 亮屏恢复已停用：恢复统一由 CoverOverlayMirrorService 负责。
        //    两处都恢复会把 MirrorActivity 通道也拉起来，和悬浮层叠在一起（重影）。
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            NotificationChannel ch = new NotificationChannel(CH_ID, "镜像运行中",
                    NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        Notification n = new Notification.Builder(this, CH_ID)
                .setContentTitle("FlipMirror")
                .setContentText("镜像运行中")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setOngoing(true)
                .build();
        // 🔴 Android 14+：必须显式声明 mediaProjection 类型的前台服务，
        // 否则 getMediaProjection() 会抛 SecurityException。
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTI_ID, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTI_ID, n);
        }
        Log.i(TAG, "service started");
    }

    /**
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
                            long now = android.os.SystemClock.elapsedRealtime();
                            if (sRelaunching || now - sLastRelaunch < 60000L) {
                                Log.i(TAG, "screen on: relaunch skipped (debounce)");
                                return;
                            }
                            sRelaunching = true;
                            sLastRelaunch = now;
                            // 延后 2.5s：亮屏瞬间系统自己也在恢复前台（SubHome），
                            // 抢在它前面启动会被它盖掉（实测 SubHome 最终占住前台）。
                            try {
                                Thread.sleep(2500);
                            } catch (InterruptedException ignored) { }
                            // 🔴 关键补丁：关屏期间系统会把 device_state 从 4 打回 OPENED(3)，
                            //    外屏就此熄灭 —— 此时 am start 成功也看不见画面（实测：镜像还在跑、
                            //    MirrorRoot 仍是 Display 0，但外屏 OFF 所以全黑）。
                            //    所以必须先恢复双屏并发，等外屏真的亮起来再拉起界面。
                            Log.i(TAG, "screen on: restoring device_state 4");
                            Shell.execSync("cmd device_state state 4");
                            try {
                                Thread.sleep(1200);
                            } catch (InterruptedException ignored) { }

                            Log.i(TAG, "screen on: relaunching mirror");
                            Shell.execSync("am start --display 1 -n com.flip.mirror/.MainActivity"
                                    + " --ez auto_start true");
                        } catch (Throwable t) {
                            Log.w(TAG, "screen-on relaunch failed: " + t);
                        } finally {
                            new android.os.Handler(android.os.Looper.getMainLooper())
                                    .postDelayed(() -> sRelaunching = false, 12000);
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

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
