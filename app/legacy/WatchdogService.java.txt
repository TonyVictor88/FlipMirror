package com.flip.mirror;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

/**
 * 镜像看门狗 —— 跑在【独立进程 :wd】里。
 *
 * <h3>为什么必须独立进程</h3>
 * 主进程被系统回收（内存紧张 / 手动强停 / 「清除全部」）时，同进程的任何守护都会一起死，
 * 什么也救不回来。放在独立进程里，主进程死了它还在，才能把镜像重新拉起来。
 *
 * <h3>怎么判断"该不该有镜像"</h3>
 * {@link MirrorActivity} 在镜像启动时往 files 目录写一个标记文件，点 ✕ 退出时删掉。
 * 看门狗只认这个文件 —— 用户主动退出后不会自动弹回来。
 *
 * <h3>怎么拉起</h3>
 * 用 shell(uid 2000) 执行 `am start` 指定 display 1 拉起 MainActivity，并带 auto_start，
 * 由它走一遍授权 + 拉镜像页（这条路径是实测唯一可靠的落屏方式）。
 */
public class WatchdogService extends Service {

    private static final String TAG = "FlipMirrorWatchdog";
    private static final String CHANNEL = "flipmirror_wd";
    private static final int NOTI_ID = 2002;
    private static final long INTERVAL = 4000;

    private android.os.Handler mHandler;
    private boolean mTicking = false;

    @Override
    public void onCreate() {
        super.onCreate();
        startForegroundWithNotification();
        mHandler = new android.os.Handler(android.os.Looper.getMainLooper());
        mTicking = true;
        mHandler.postDelayed(mTick, INTERVAL);
        Log.i(TAG, "watchdog started");
    }

    private void startForegroundWithNotification() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null && nm.getNotificationChannel(CHANNEL) == null) {
                NotificationChannel ch = new NotificationChannel(
                        CHANNEL, "镜像守护", NotificationManager.IMPORTANCE_MIN);
                ch.setShowBadge(false);
                nm.createNotificationChannel(ch);
            }
        }
        Notification n = new Notification.Builder(this, Build.VERSION.SDK_INT >= 26 ? CHANNEL : null)
                .setContentTitle("FlipMirror 守护中")
                .setContentText("镜像异常退出会自动恢复")
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTI_ID, n,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTI_ID, n);
        }
    }

    private final Runnable mTick = new Runnable() {
        @Override
        public void run() {
            if (!mTicking) {
                return;
            }
            try {
                boolean wanted = new java.io.File(getFilesDir(), MirrorActivity.FLAG_FILE).exists();
                if (wanted) {
                    // 一条 shell 搞定"查 + 拉起"，避免每 4 秒解析 dumpsys
                    // 🔴 判据必须是「是否在前台」，不能只判「是否存在」：
                    //    关屏再亮屏后镜像页往往**还在**（只是被 SubHome 顶掉到后台），
                    //    用存在性判断会认为一切正常、什么也不做（实测踩过）。
                    String cmd = "if ! dumpsys activity activities 2>/dev/null "
                            + "| grep -q 'topResumedActivity=.*com.flip.mirror/.MirrorActivity'; "
                            + "then am start --display 1 -n com.flip.mirror/.MainActivity --ez auto_start true; fi";
                    exec(cmd);
                }
            } catch (Throwable t) {
                Log.w(TAG, "tick failed: " + t.getMessage());
            }
            if (mTicking) {
                mHandler.postDelayed(this, INTERVAL);
            }
        }
    };

    private void exec(String cmd) {
        Shell.execSync(cmd);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        mTicking = false;
        if (mHandler != null) {
            mHandler.removeCallbacks(mTick);
        }
        super.onDestroy();
    }
}
