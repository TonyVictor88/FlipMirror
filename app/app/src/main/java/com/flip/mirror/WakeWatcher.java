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
        // 不在这里判断"要不要、怎么做" —— 全部交给唯一的对账函数。
        // 之前这里各写一套判断，和闹钟/入口的判断打架，关屏问题反复复发。
        KeepAliveReceiver.schedule(ctx);
        MirrorState.reconcile(ctx, "event:" + action);
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
