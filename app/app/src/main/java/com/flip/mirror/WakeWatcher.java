package com.flip.mirror;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

/**
 * 事件驱动的看门狗 —— **静态注册**在 manifest 里，靠系统投递，不依赖任何常驻进程。
 *
 * <p>和 {@link KeepAliveReceiver}（闹钟轮询）互补：
 * <ul>
 *   <li>本类：亮屏/开机/解锁 <b>立刻</b> 响应，零延迟</li>
 *   <li>闹钟：兜底，防止手机上没有这些事件的时间窗里服务被偷偷回收</li>
 * </ul>
 *
 * <p>关键点：接收器写在 manifest 而不是在服务里动态注册。
 * 服务被三星回收后，动态注册的接收器跟着没了 —— 这正是之前"关屏回来乱套"的原因。
 */
public class WakeWatcher extends BroadcastReceiver {

    private static final String TAG = "FlipWakeWatcher";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        Log.i(TAG, "event: " + action);
        if (action == null) {
            return;
        }

        boolean should;
        try {
            SharedPreferences sp = ctx.getSharedPreferences(
                    KeepAliveReceiver.PREFS, Context.MODE_PRIVATE);
            should = sp.getBoolean(KeepAliveReceiver.KEY_SHOULD_MIRROR, false);
        } catch (Throwable t) {
            should = false;
        }

        if (!should) {
            Log.i(TAG, "should_mirror=false, ignore " + action);
            return;
        }

        // 每次事件都续一次闹钟：保证兜底不会因为长时间没事件而失效
        try {
            KeepAliveReceiver.schedule(ctx);
        } catch (Throwable ignored) { }

        if (Intent.ACTION_SCREEN_ON.equals(action)
                || Intent.ACTION_USER_PRESENT.equals(action)
                || Intent.ACTION_BOOT_COMPLETED.equals(action)) {
            if (!KeepAliveReceiver.isServiceAlive(ctx)) {
                Log.w(TAG, "service gone on " + action + " -> relaunch");
                relaunch(ctx);
            } else {
                Log.i(TAG, "service alive on " + action);
            }
        }
    }

    private void relaunch(Context ctx) {
        try {
            Intent it = new Intent(ctx, MainActivity.class);
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            it.putExtra("auto_start", true);
            it.putExtra("force_overlay", true);
            ctx.startActivity(it);
        } catch (Throwable t) {
            Log.w(TAG, "relaunch failed: " + t);
        }
    }
}
