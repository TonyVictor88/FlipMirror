package com.flip.mirror;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

/**
 * 「投屏到外屏」桌面入口 —— 一个只做转发的空 Activity。
 *
 * <h3>为什么需要它</h3>
 * 快捷设置磁贴在外屏上用不了（外屏下拉面板受限，进不了"编辑快捷面板"）。
 * 所以改成：桌面上放一个独立图标，点一下 → 直接开始投屏，不需要再点大按钮。
 *
 * <h3>怎么工作</h3>
 * 自己什么都不显示，拉起 {@link MainActivity} 并带 {@code auto_start=true}，
 * 然后立刻 finish。MainActivity 会自己挪到外屏、走录屏授权、拉起镜像页。
 */
public class QuickMirrorActivity extends Activity {

    private static final String TAG = "FlipMirror";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.i(TAG, "QuickMirrorActivity: launching mirror directly");
        // 🔴 必须用 shell 直接指定 display 1 拉起，不能 startActivity。
        //    用 startActivity 时系统会先把它放到 display 0，再由 MainActivity 自重开到外屏；
        //    这条路径会产生多份实例（实测任务栈 sz=5），互相抢录屏授权，
        //    首次进入经常失败、要等一次亮屏恢复才正常。
        //    而亮屏恢复走的就是 "am start --display 1"，单实例、稳定。
        new Thread(() -> {
            boolean ok = Shell.execSync(
                    "am start --display 1 -n com.flip.mirror/.MainActivity --ez auto_start true");
            if (!ok) {
                Log.w(TAG, "quick start via shell failed: " + Shell.lastError);
                // 兜底：退回普通启动（落屏可能不准，但至少能进去）
                runOnUiThread(() -> {
                    Intent it = new Intent(this, MainActivity.class);
                    it.putExtra("auto_start", true);
                    it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                    try {
                        startActivity(it);
                    } catch (Throwable t) {
                        Log.w(TAG, "fallback start failed: " + t.getMessage());
                    }
                });
            }
        }, "quick-start").start();

        finish();
    }
}
