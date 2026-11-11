package com.flip.mirror;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

/**
 * 投屏状态的**唯一真相 + 唯一对账函数**。
 *
 * <h3>为什么要有这个类</h3>
 * 之前"是否该投屏"散在三处：静态变量（进程死就丢）、落盘标记、服务是否存在。
 * 每修一处就和另一处失步，结果关屏/退出/首次点击的问题轮流复发（2026-11-11 下午
 * 连发 12 个版本都在这个坑里打转）。
 *
 * <h3>现在的规则</h3>
 * <ol>
 *   <li>唯一真相：SharedPreferences 里的 {@code desired_mirror}（落盘，进程死不掉）</li>
 *   <li>唯一动作：{@link #reconcile} —— 任何事件（亮屏/解锁/开机/闹钟/入口启动）都只调它</li>
 *   <li>它只做"让现实追上期望"，不关心是谁触发的、之前发生过什么</li>
 * </ol>
 * 这样任意顺序、任意进程死亡，都会收敛到同一结果。
 */
public final class MirrorState {

    private static final String TAG = "FlipMirrorState";

    public static final String PREFS = "flipmirror_settings";
    public static final String KEY_DESIRED = "desired_mirror";

    private MirrorState() { }

    /* ------------------------------------------------------------ 期望 */

    public static void setDesired(Context ctx, boolean on) {
        try {
            prefs(ctx).edit().putBoolean(KEY_DESIRED, on).apply();
            Log.i(TAG, "desired_mirror = " + on);
        } catch (Throwable ignored) { }
    }

    public static boolean desired(Context ctx) {
        try {
            return prefs(ctx).getBoolean(KEY_DESIRED, false);
        } catch (Throwable t) {
            return false;
        }
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /* ------------------------------------------------------------ 对账 */

    /**
     * 让现实追上期望。幂等：重复调用无副作用。
     *
     * @param reason 触发原因，只用于日志
     */
    public static void reconcile(Context ctx, String reason) {
        boolean want = desired(ctx);
        Log.i(TAG, "reconcile(" + reason + ") desired=" + want);
        if (!want) {
            return;   // 用户已退出，什么都不做
        }

        // ① 外屏必须亮着：关屏会撤掉双屏并发，不亮就什么都看不见
        if (Shell.available()) {
            boolean ok = Shell.execSync("cmd device_state state 4");
            Log.i(TAG, "reconcile: device_state 4 -> " + ok);
        }

        // ② 悬浮层服务必须在
        if (isServiceAlive(ctx)) {
            Log.i(TAG, "reconcile: service alive, nothing else to do");
            return;
        }
        Log.w(TAG, "reconcile: service gone -> relaunch entry");

        // ③ 不在就拉起入口页重新走流程（带 auto_start，入口页会直接申请录屏）
        try {
            Intent it = new Intent(ctx, MainActivity.class);
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            it.putExtra("auto_start", true);
            it.putExtra("force_overlay", true);
            ctx.startActivity(it);
        } catch (Throwable t) {
            Log.w(TAG, "reconcile: relaunch failed: " + t);
        }
    }

    public static boolean isServiceAlive(Context ctx) {
        try {
            ActivityManager am =
                    (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) {
                return false;
            }
            for (ActivityManager.RunningServiceInfo info
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
}
