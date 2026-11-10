package com.flip.mirror;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * 开机自启：尝试自动拉起镜像页。
 *
 * 注意：MediaProjection 授权无法持久化（每次开机会失效），
 * 所以这里只做"提醒式启动"——拉起 MainActivity 让用户点一下。
 */
public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "FlipMirror";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        Log.i(TAG, "boot completed, launching MainActivity");
        try {
            Intent i = new Intent(context, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(i);
        } catch (Exception e) {
            Log.e(TAG, "boot launch failed", e);
        }
    }
}
