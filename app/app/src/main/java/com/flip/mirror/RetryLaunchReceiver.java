package com.flip.mirror;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.util.Log;

/**
 * 一次性"补点"闹钟。
 *
 * <h3>为什么用闹钟而不是线程</h3>
 * 单屏态（device_state=1）下第一次点图标时，我们把屏幕切到双屏并发，
 * 这次配置变更会把 MainActivity 连同它的后台线程一起干掉，
 * 于是"申请录屏"那一步永远不会执行（矩阵实测：窗=0，只剩壁纸）。
 *
 * 而用户手动点**第二次**就一定成功（state 已经是 4）。
 *
 * 所以做法是：第一次只负责切双屏 + 排一个 3 秒后的闹钟，然后自己退出；
 * 闹钟到点由**系统**重新拉起 Activity —— 此时 state 已是 4，就走通了。
 * 用闹钟而不是 Thread，是因为线程会随 Activity 一起死，闹钟不会。
 */
public class RetryLaunchReceiver extends BroadcastReceiver {

    private static final String TAG = "FlipRetry";
    private static final int REQ = 0x5F21;

    @Override
    public void onReceive(Context ctx, Intent intent) {
        Log.i(TAG, "retry launch now");
        try {
            Intent it = new Intent(ctx, MainActivity.class);
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            it.putExtra("retry", true);
            ctx.startActivity(it);
        } catch (Throwable t) {
            Log.w(TAG, "retry failed: " + t);
        }
    }

    /** 排一次（3 秒后）。 */
    public static void schedule(Context ctx, long delayMs) {
        try {
            AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            if (am == null) {
                return;
            }
            am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + delayMs, pending(ctx));
            Log.i(TAG, "retry scheduled in " + delayMs + "ms");
        } catch (Throwable t) {
            Log.w(TAG, "schedule failed: " + t);
        }
    }

    private static PendingIntent pending(Context ctx) {
        Intent it = new Intent(ctx, RetryLaunchReceiver.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (android.os.Build.VERSION.SDK_INT >= 23) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getBroadcast(ctx, REQ, it, flags);
    }
}
