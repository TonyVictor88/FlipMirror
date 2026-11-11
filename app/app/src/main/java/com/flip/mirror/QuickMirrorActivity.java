package com.flip.mirror;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

/**
 * 「投屏到外屏」桌面入口 —— 只做转发的空 Activity。
 *
 * <h3>为什么需要它</h3>
 * 快捷设置磁贴在外屏上用不了（外屏下拉面板受限，进不了"编辑快捷面板"），
 * 所以桌面上放一个独立图标，点一下直接开始投屏。
 *
 * <h3>关键：入口页必须钉在内屏（display 0）</h3>
 * MediaProjection 镜像的是**发起授权请求的那个屏**。
 * 从外屏图标点进来时，新建的 Activity 会继承外屏的 display，
 * 于是投屏去拍外屏自己 → 画面无限递归（满屏重复的控件条）。
 * 所以这里用 shell 显式 {@code --display 0} 拉起 MainActivity，
 * 画面由 {@link CoverOverlayMirrorService} 画到外屏，与入口页在哪个屏无关。
 */
public class QuickMirrorActivity extends Activity {

    private static final String TAG = "FlipMirror";

    /** 问 ActivityManager：本进程的服务是否真的在运行（跨进程、不受静态量与残留文件影响）。 */
    private boolean isServiceAlive() {
        try {
            android.app.ActivityManager am =
                    (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
            if (am == null) {
                return false;
            }
            for (android.app.ActivityManager.RunningServiceInfo info
                    : am.getRunningServices(Integer.MAX_VALUE)) {
                if (info.service != null && getPackageName().equals(info.service.getPackageName())
                        && CoverOverlayMirrorService.class.getName()
                                .equals(info.service.getClassName())) {
                    return true;
                }
            }
        } catch (Throwable ignored) { }
        return false;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 幂等：镜像已经在跑就别再起一份（用户会从镜像画面里点这个图标）
        // 判"是否已在跑"必须问系统，不能只看静态量或标记文件：
        //  - 静态量在进程重建后失效（会误判没在跑 → 起第二份）
        //  - 标记文件在进程被杀后残留（会误判在跑 → 点了什么都不发生，实测踩过）
        boolean running = CoverOverlayMirrorService.isRunning() || isServiceAlive();
        if (running) {
            Log.i(TAG, "already mirroring, ignore this launch");
            finish();
            return;
        }
        Log.i(TAG, "QuickMirrorActivity: launching mirror on display 0");

        final String cmd = "am start --display 0 -f 0x18000000 -n com.flip.mirror/.MainActivity"
                + " --ez auto_start true --ez force_overlay true";

        new Thread(() -> {
            boolean ok = Shell.execSync(cmd);
            Log.i(TAG, "quick start on display 0 -> " + ok + " : " + cmd);
            if (!ok) {
                // 没有 shell 时退回普通启动：可能继承外屏 display（画面会递归），
                // 但至少能进到界面，用户还能手点。
                runOnUiThread(() -> {
                    Intent it = new Intent(this, MainActivity.class);
                    it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                    it.putExtra("auto_start", true);
                    it.putExtra("force_overlay", true);
                    try {
                        startActivity(it);
                    } catch (Throwable t) {
                        Log.w(TAG, "fallback start failed: " + t.getMessage());
                    }
                });
            }
        }, "quick-start-d0").start();

        finish();
    }
}
