package com.flip.mirror;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 入口页：申请录屏授权，然后把镜像页拉到外屏。
 *
 * <h3>这一页为什么这么绕</h3>
 * 内屏面板已物理拆除，但 App 默认会被系统放到 display 0（那块看不见的屏）上。
 * 所以本页必须保证两件事：
 * <ol>
 *   <li><b>自己要在外屏</b>：发现不在 display 1，就用 shell 执行 am start 指定 display 1 重开一份自己；</li>
 *   <li><b>镜像页也要在外屏</b>：拿到授权后同样用 shell 拉起
 *       （Activity 内部的 setLaunchDisplayId 实测会被系统拽回 display 0）。</li>
 * </ol>
 *
 * 前提条件（AndroidManifest，否则 shell 拉起会被拒或复用旧实例）：
 * exported=true、launchMode=standard。
 *
 * 另外：wm size / wm density / device_state 必须在拉起镜像页**之前**设好 ——
 * 镜像页运行期间改这些会触发配置变更，Activity 被系统重建后落回 display 0，
 * 画面变成 748x588（内屏高 654 减控件条 66）那块黑。
 */
public class MainActivity extends Activity {

    private static final String TAG = "FlipMirror";
    private static final int REQ_PROJECTION = 1001;

    /** 防止"重开自己"变成死循环。 */
    private static boolean sRelaunchTried = false;

    private MediaProjectionManager mPm;
    private TextView mStatus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        float d = getResources().getDisplayMetrics().density;
        int pad = (int) (16 * d);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(0xFF101010);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("FlipMirror");
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(22);
        root.addView(title);

        mStatus = new TextView(this);
        mStatus.setText("点下面的按钮开始镜像");
        mStatus.setTextColor(0xFFAAAAAA);
        mStatus.setTextSize(13);
        mStatus.setPadding(0, pad, 0, pad);
        root.addView(mStatus);

        // 主操作按钮：外屏小屏，做大做高
        // 🔴 启动前自检：通过 Termux 的 RUN_COMMAND 接口执行 flip-prep.sh
        //    （自己设置双屏并发 + 拉起 Shizuku）。App 本身 targetSdk 34 无法执行
        //    私有目录里的二进制（noexec），所以借 Termux（targetSdk 28）的手。
        Button selfCheck = new Button(this);
        selfCheck.setText("① 启动前自检（双屏 + Shizuku）");
        selfCheck.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        selfCheck.setOnClickListener(v -> runSelfCheck());
        root.addView(selfCheck);

        Button go = new Button(this);
        go.setText("开始镜像到外屏");
        go.setTextSize(20);
        go.setAllCaps(false);
        LinearLayout.LayoutParams goLp = new LinearLayout.LayoutParams(
                Math.round(300 * d), Math.round(72 * d));
        goLp.setMargins(0, pad, 0, Math.round(pad * 1.5f));
        go.setLayoutParams(goLp);
        go.setOnClickListener(v -> requestProjection());
        root.addView(go);

        Button settings = new Button(this);
        settings.setText("设置");
        settings.setAllCaps(false);
        settings.setLayoutParams(new LinearLayout.LayoutParams(
                Math.round(300 * d), Math.round(44 * d)));
        settings.setOnClickListener(v ->
                startActivity(new Intent(MainActivity.this, SettingsActivity.class)));
        root.addView(settings);

        setContentView(root);

        // 🔴 试验：不依赖 shell 直接请求 device_state = 4（双屏并发）。
        //    依据 AOSP：top-focused activity 调 requestState 不需要 CONTROL_DEVICE_STATE 权限。
        //    成功的话就不需要 Shizuku 来完成这一步了。
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            boolean ok = DeviceStateRequester.request(this, DeviceStateRequester.STATE_CONCURRENT);
            android.util.Log.i("FlipMirror", "DeviceStateRequester.request(4) = " + ok);
        }

        mPm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);

        // 看门狗拉起时带 auto_start=true：不等人点按钮，直接把流程走完
        if (getIntent() != null && getIntent().getBooleanExtra("auto_start", false)) {
            new android.os.Handler(android.os.Looper.getMainLooper())
                    .postDelayed(this::requestProjection, 900);
        }

        tryMoveToDisplay1();
        reportDisplay();
    }

    @Override
    protected void onResume() {
        super.onResume();
        reportDisplay();
    }

    /** 打印 / 显示当前所在 display（外屏应为 1）。 */
    private void reportDisplay() {
        int displayId = getDisplay() != null ? getDisplay().getDisplayId() : -1;
        if (mStatus != null) {
            mStatus.setText("当前 display = " + displayId + "\n点按钮开始镜像");
        }
        Log.i(TAG, "MainActivity running on display " + displayId);
    }

    /**
     * 确保自己在 display 1（外屏）。唯一可靠方式是 shell 执行 am start 指定 display，
     * 且要求 exported=true + launchMode=standard。
     */
    private void tryMoveToDisplay1() {
        int cur = getDisplay() != null ? getDisplay().getDisplayId() : -1;
        if (cur == 1 || sRelaunchTried) {
            return;
        }
        sRelaunchTried = true;
        // 重开自己时必须把 auto_start 一起带过去：
        // 否则落到外屏的那个新实例不知道要自动开始，一键投屏就断了（实测踩过）。
        boolean auto = getIntent() != null && getIntent().getBooleanExtra("auto_start", false);
        final String cmd = "am start --display 1 -n com.flip.mirror/.MainActivity"
                + (auto ? " --ez auto_start true" : "");
        new Thread(() -> {
            if (Shell.execSync(cmd)) {
                Log.i(TAG, "relaunch self on display 1 via shell");
            } else {
                Log.w(TAG, "shell relaunch failed: " + Shell.lastError);
            }
        }, "relaunch-d1").start();
    }

    /** 申请录屏授权（appops 已预授权，系统页会自动放行）。 */
    private void requestProjection() {
        // 🔴 只有落在外屏的那个实例才能发起：本页发现不在 display 1 时会用 shell 重开一份自己，
        //    旧实例若继续申请，就会拿到第二份录屏授权、拉起第二个镜像实例，
        //    两个实例抢同一块屏幕 → 第二个报「录屏授权已失效」并把画面踢掉（实测踩过）。
        int disp = getDisplay() != null ? getDisplay().getDisplayId() : -1;
        if (disp != 1) {
            Log.i(TAG, "requestProjection skipped on display " + disp);
            return;
        }
        if (mPm == null) {
            mStatus.setText("MediaProjectionManager 不可用");
            return;
        }
        // 先拉起前台服务：系统授权页会把我们压到后台，
        // 三星的 Freecess 会冻结后台 App，冻结后 onActivityResult 永远回不来。
        try {
            startForegroundService(new Intent(this, MirrorService.class));
        } catch (Throwable t) {
            Log.w(TAG, "pre-start foreground service failed", t);
        }
        try {
            Intent intent = mPm.createScreenCaptureIntent();
            startActivityForResult(intent, REQ_PROJECTION);
        } catch (Exception e) {
            Log.e(TAG, "createScreenCaptureIntent failed", e);
            mStatus.setText("申请录屏授权失败：" + e.getMessage());
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PROJECTION) {
            return;
        }
        if (resultCode != RESULT_OK || data == null) {
            mStatus.setText("用户拒绝了录屏授权");
            return;
        }
        mStatus.setText("授权成功，正在打开镜像页…");
        Log.i(TAG, "projection granted");

        // 授权结果走静态通道交给镜像页（shell 拉起的 Activity 传不了 Parcelable）
        MirrorActivity.sResultCode = resultCode;
        MirrorActivity.sResultData = data;

        // 顺序：先设好双屏并发 + 内屏分辨率/密度，再拉起镜像页
        SettingsStore st = new SettingsStore(this);
        final String prep =
                "cmd device_state state 4; sleep 1.2; "
                        + "wm size " + st.innerWidth() + "x" + st.innerHeight() + "; "
                        + "wm density " + st.innerDpi();
        final String cmd = "am start --display 1 -n com.flip.mirror/.MirrorActivity";

        new Thread(() -> {
            if (Shell.execSync(prep)) {
                Log.i(TAG, "prepared device state: " + prep);
            } else {
                Log.w(TAG, "prepare failed: " + Shell.lastError);
            }
            try {
                Thread.sleep(1800);
            } catch (InterruptedException ignored) { }

            if (Shell.execSync(cmd)) {
                Log.i(TAG, "launched MirrorActivity via shell: " + cmd);
                // 拉完就退场：入口页留在任务栈里会越堆越多（实测一次启动 sz=5），
                // 而且它盖在镜像页下面会影响返回与顶层判断。
                runOnUiThread(() -> new android.os.Handler(android.os.Looper.getMainLooper())
                        .postDelayed(MainActivity.this::finish, 2500));
                return;
            }
            Log.w(TAG, "shell launch failed: " + Shell.lastError);

            // 兜底：直接用 Activity 拉起（落屏可能不准，但至少有画面）
            runOnUiThread(() -> {
                Intent it = new Intent(MainActivity.this, MirrorActivity.class);
                it.putExtra(MirrorActivity.EXTRA_RESULT_CODE, resultCode);
                it.putExtra(MirrorActivity.EXTRA_RESULT_DATA, data);
                try {
                    ActivityOptions opts = ActivityOptions.makeBasic();
                    opts.setLaunchDisplayId(1);
                    startActivity(it, opts.toBundle());
                } catch (Exception e2) {
                    startActivity(it);
                }
            });
        }, "launch-mirror").start();
    }

    /**
     * 启动前自检：让 Termux 执行 flip-prep.sh。
     *
     * <p>为什么绕道 Termux：本 App 的 targetSdk=34，Android 10+ 对其私有目录施加
     * noexec，无法直接执行 adb 二进制（实测 "./adb: not executable: 64-bit ELF file"）。
     * Termux 的 targetSdk=28 不受此限，且它已经有授权过的 adb 自连通道
     * （127.0.0.1:5555，adb_keys 跨重启保留）。</p>
     */
    private void runSelfCheck() {
        if (mStatus != null) mStatus.setText("自检中…（约 10 秒）");
        try {
            android.content.Intent i = new android.content.Intent();
            i.setClassName("com.termux", "com.termux.app.RunCommandService");
            i.setAction("com.termux.RUN_COMMAND");
            i.putExtra("com.termux.RUN_COMMAND_PATH",
                    "/data/data/com.termux/files/usr/bin/sh");
            i.putExtra("com.termux.RUN_COMMAND_ARGUMENTS",
                    new String[]{"/data/data/com.termux/files/home/flip-prep.sh"});
            i.putExtra("com.termux.RUN_COMMAND_WORKDIR",
                    "/data/data/com.termux/files/home");
            i.putExtra("com.termux.RUN_COMMAND_BACKGROUND", true);
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                startForegroundService(i);
            } else {
                startService(i);
            }
            android.util.Log.i("FlipMirror", "self-check intent sent");
            new android.os.Handler(getMainLooper()).postDelayed(() -> {
                if (mStatus != null) {
                    mStatus.setText("自检已下发。若 10 秒后仍异常，请打开 Termux 看 "
                            + "flip-prep.log");
                }
            }, 10000);
        } catch (Throwable t) {
            android.util.Log.e("FlipMirror", "self-check failed", t);
            if (mStatus != null) mStatus.setText("自检失败：" + t.getMessage());
        }
    }
}
