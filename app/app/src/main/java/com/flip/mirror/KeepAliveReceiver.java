package com.flip.mirror;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.util.Log;

/**
 * 看门狗闹钟：**系统级**、杀进程也杀不掉。
 *
 * <h3>为什么需要它</h3>
 * 之前把"关屏后自动恢复"写在 {@link CoverOverlayMirrorService} 里，靠 SCREEN_ON 广播触发。
 * 但三星在关屏时会把那个前台服务回收掉（实测服务列表里直接消失），
 * 服务没了，广播就没人收 —— 恢复链断掉，用户看到的就是"关屏回来乱套"。
 *
 * <h3>怎么工作</h3>
 * 闹钟每 15 秒响一次，由系统投递（不依赖任何进程存活）。响的时候：
 * <ol>
 *   <li>先把自己再排一次（自续期，永不停止）</li>
 *   <li>查"应该正在投屏"的标记（SharedPreferences，落盘）</li>
 *   <li>查悬浮层服务是否真的活着</li>
 *   <li>不满足就拉起入口页重新走流程</li>
 * </ol>
 * 用户点 ✕ 退出时标记会被清掉，闹钟什么都不做。
 */
public class KeepAliveReceiver extends BroadcastReceiver {

    private static final String TAG = "FlipKeepAlive";
    private static final long INTERVAL_MS = 15_000L;
    private static final int REQ = 0x5F10;

    /** 「用户期望正在投屏」标记：服务启动写 true，点 ✕ 退出写 false。 */
    public static final String PREFS = "flipmirror_settings";
    public static final String KEY_SHOULD_MIRROR = "should_mirror";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        try {
            schedule(ctx);   // 先自续期，保证不会因为下面抛异常而断掉
        } catch (Throwable ignored) { }

        try {
            SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            if (!sp.getBoolean(KEY_SHOULD_MIRROR, false)) {
                Log.i(TAG, "should_mirror=false, idle");
                return;
            }
            if (isServiceAlive(ctx)) {
                Log.i(TAG, "service alive, ok");
                return;
            }
            Log.w(TAG, "service gone -> relaunch entry");
            Intent it = new Intent(ctx, MainActivity.class);
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            it.putExtra("auto_start", true);
            it.putExtra("force_overlay", true);
            ctx.startActivity(it);
        } catch (Throwable t) {
            Log.w(TAG, "tick failed: " + t);
        }
    }

    private boolean isServiceAlive(Context ctx) {
        try {
            android.app.ActivityManager am =
                    (android.app.ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) {
                return false;
            }
            for (android.app.ActivityManager.RunningServiceInfo info
                    : am.getRunningServices(Integer.MAX_VALUE)) {
                if (info.service != null && ctx.getPackageName().equals(info.service.getPackageName())
                        && CoverOverlayMirrorService.class.getName()
                                .equals(info.service.getClassName())) {
                    return true;
                }
            }
        } catch (Throwable ignored) { }
        return false;
    }

    /** 排下一次检查（每次只排一次，靠自身续期，避免被系统批量对齐到一分钟以上）。 */
    public static void schedule(Context ctx) {
        try {
            AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            if (am == null) {
                return;
            }
            PendingIntent pi = pendingIntent(ctx);
            if (am != null) {
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        SystemClock.elapsedRealtime() + INTERVAL_MS, pi);
            }
        } catch (Throwable t) {
            Log.w(TAG, "schedule failed: " + t);
        }
    }

    /** 取消检查（用户点 ✕ 退出时调用）。 */
    public static void cancel(Context ctx) {
        try {
            AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            if (am != null) {
                am.cancel(pendingIntent(ctx));
            }
        } catch (Throwable ignored) { }
    }

    private static PendingIntent pendingIntent(Context ctx) {
        Intent it = new Intent(ctx, KeepAliveReceiver.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (android.os.Build.VERSION.SDK_INT >= 23) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getBroadcast(ctx, REQ, it, flags);
    }
}
