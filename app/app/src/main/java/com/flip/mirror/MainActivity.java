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

    /** 防止"重开自己"变成死循环（每次进程生命周期最多 2 次）。 */
    private static int sRelaunchAttempts = 0;

    /** 外屏实例是否已经起来（同进程共享，用于确认后关掉 display 0 上的旧实例）。 */
    private static volatile boolean sCoverInstanceAlive = false;
    /** Shizuku 授权请求只弹一次 */
    private static boolean sPermRequested = false;

    private MediaProjectionManager mPm;
    private TextView mStatus;

    /**
     * 主动申请 Shizuku 授权一次。
     *
     * 必须在界面上弹一次框：Shizuku 13.x 的授权记录存在**服务端**，
     * 单靠 adb {@code pm grant} 只改了 Android 运行时权限，服务端仍然判为拒绝
     * （实测：pm grant 显示 granted=true，但 checkSelfPermission() 返回 -1）。
     * 用户点一次「允许」之后才会持久化，重装/换签名后需要重新点。
     */
    private void ensureShizukuPermission() {
        try {
            if (!rikka.shizuku.Shizuku.pingBinder()) {
                Log.i(TAG, "shizuku server not running, skip permission request");
                return;
            }
            int granted = rikka.shizuku.Shizuku.checkSelfPermission();
            Log.i(TAG, "shizuku permission state = " + granted);
            if (granted != 0 && !sPermRequested) {
                sPermRequested = true;
                rikka.shizuku.Shizuku.requestPermission(1001);
                Log.i(TAG, "requested shizuku permission");
            }
        } catch (Throwable t) {
            Log.w(TAG, "ensureShizukuPermission failed: " + t);
        }
    }

    /** 启动后 6 秒内若服务没起来，补一次申请（最多一次）。 */
    private void armStartupCheck(final int resultCode, final android.content.Intent data) {
        getWindow().getDecorView().postDelayed(new Runnable() {
            @Override
            public void run() {
                if (CoverOverlayMirrorService.isRunning()) {
                    Log.i(TAG, "startup check: service alive, finish");
                    finish();
                    return;
                }
                Log.w(TAG, "startup check: not running, retry once");
                CoverOverlayMirrorService.start(MainActivity.this, resultCode, data);
                getWindow().getDecorView().postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        Log.i(TAG, "startup check done, finish");
                        finish();
                    }
                }, 3000);
            }
        }, 6000);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 已移除：以前"Activity 重建后自动申请录屏"。现在入口页不再切双屏，
        // 重建也不该发生；留着它会导致"打开 App 又自动跳进镜像"（用户明确不要）。
        // 一进来就把外屏点亮：退出后 device_state 回到 1，外屏是关的，
        // 这时点图标看到的是一片黑（实测：要退回外屏再点一次才亮）。
        // 入口页不等用户按按钮，自己先把屏点亮。
        // 不再试图把入口页搬到外屏：单屏态下 Activity 落在 d1 会失败并循环，
        // 最终一个窗口都不剩（矩阵实测 窗=0）。
        // 直接在这个实例里申请录屏 —— 画面由悬浮层服务画到 d1，与入口页在哪无关。
        // 打开 App 什么都不做，只显示入口页（用户 2026-11-11 20:42 要求）。
        // ⚠️ 这里**不能**顺手切双屏：切屏会触发配置变更把本页顶掉，
        // 用户看到的是外屏首页/壁纸。双屏由服务在拿到录屏授权后再置。
        if (getIntent() != null && getIntent().getBooleanExtra("auto_start", false)) {
            // 看门狗/恢复路径拉起：把"用户已退出"状态复位，允许重新开始
            CoverOverlayMirrorService.clearUserExited();
            MirrorState.setDesired(this, true);
        }
        // 幂等：镜像已经在跑时，再点「FlipMirror」图标应当什么都不做。
        // 之前会打开入口页并把正在运行的悬浮层顶掉（实测窗口 1->2，外屏只剩壁纸）。
        // 只有带 auto_start（看门狗/亮屏恢复）时才继续走流程。
        // 判据用 isShowing()：真正显示着镜像时才忽略这次点击。
        // 用 isRunning 会因"✕ 只是隐藏"而恒真；用 desired 会因进程被杀后残留而误判。
        if (CoverOverlayMirrorService.isShowing()
                && (getIntent() == null
                    || !getIntent().getBooleanExtra("auto_start", false))) {
            Log.i(TAG, "already mirroring, ignore FlipMirror launch");
            finish();
            return;
        }
        // 没在投屏时，从任意图标进来都直接开始 —— 用户点图标就是想投屏，
        // 不是想看我这个设置页（之前点「FlipMirror」只弹设置页，看着像"投不上"）。
        if (!CoverOverlayMirrorService.isShowing()
                && (getIntent() == null
                    || !getIntent().getBooleanExtra("auto_start", false))) {
            // 用户要求：点图标只进入口页，不自动开始投屏（2026-11-11 20:42 再次确认）。
            // 同时把 desired 清掉：否则看门狗（亮屏事件 / 10 分钟闹钟）看到 desired=true
            // 会把镜像重新拉起来 —— 表现就是"一打开 App 又跳进镜像"。
            MirrorState.setDesired(this, false);
            KeepAliveReceiver.cancel(this);
            Log.i(TAG, "entry page only, wait for user to press start");
        }
        ensureShizukuPermission();

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
        // 已移除：onCreate 里切双屏会立刻触发配置变更（Activity 重建、入口页被顶掉），
        // 而且用户明确要求"打开 App 不要自动跳进镜像"。双屏交给服务在授权后置。

        mPm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);

        // 看门狗拉起时带 auto_start=true：不等人点按钮，直接把流程走完
        if (getIntent() != null && getIntent().getBooleanExtra("auto_start", false)) {
            new android.os.Handler(android.os.Looper.getMainLooper())
                    .postDelayed(this::requestProjection, 900);
        }

        if (getDisplay() != null && getDisplay().getDisplayId() == 1) {
            sCoverInstanceAlive = true;
        }

        // 🔴 兜底/悬浮层模式下**绝不能**把自己搬到外屏：
        //    投屏镜像的是发起授权请求的那个屏，入口页在外屏 → 镜像外屏自己
        //    → 画面无限递归（实测：满屏重复的控件条）。
        boolean forceOverlay = getIntent() != null
                && getIntent().getBooleanExtra("force_overlay", false);
        // 统一入口：不管从「FlipMirror」还是「投屏到外屏」进来，
        // 一律留在当前屏 + 走悬浮层通道。
        // 之前保留"搬到 display 1"的老路径，两个入口行为不一致，
        // 同时开还会互相打架（实测窗口数堆到 6）。
        Log.i(TAG, "entry: stay on current display, use overlay channel");
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
     * 确保自己在 display 1（外屏）。
     *
     * <p>唯一可靠的方式是 shell 执行 {@code am start --display 1}，但**必须带
     * {@code -f 0x18000000}**（FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_MULTIPLE_TASK）：
     * 否则当 display 0 上已经有本 Activity 的 task 时，系统会直接复用那个旧 task，
     * 新窗口照样落在 display 0 —— 表现就是"点开 App 什么都没出现"（2026-11-10 实测）。</p>
     *
     * <p>命令里先跑 {@code cmd device_state state 4} 点亮外屏并发，再拉 Activity。
     * 拉起来的新实例带 {@code cover=true}，它自己不会再重复搬运。</p>
     */
    private void tryMoveToDisplay1() {
        int cur = getDisplay() != null ? getDisplay().getDisplayId() : -1;
        if (cur == 1) {
            return;
        }
        if (sRelaunchAttempts >= 2) {
            Log.w(TAG, "give up moving to display 1 (attempts=" + sRelaunchAttempts + ")");
            return;
        }
        sRelaunchAttempts++;
        // 清掉上一次遗留的"外屏已在"标记：否则第二次点图标时旧标记还是 true，
        // 会在新实例还没起来时就立刻把 display 0 这个实例关掉 —— 万一这次搬运失败，
        // 用户就什么都没有了（2026-11-10 实测：第二次点击 1ms 内就误判为已成功）。
        sCoverInstanceAlive = false;
        // 重开自己时必须把 auto_start 一起带过去：
        // 否则落到外屏的那个新实例不知道要自动开始，一键投屏就断了（实测踩过）。
        boolean auto = getIntent() != null && getIntent().getBooleanExtra("auto_start", false);
        final String cmd = "cmd device_state state 4; sleep 1; "
                + "am start --display 1 -f 0x18000000 -n com.flip.mirror/.MainActivity"
                + " --ez cover true"
                + (auto ? " --ez auto_start true" : "");
        new Thread(() -> {
            for (int i = 0; i < 3; i++) {
                if (Shell.execSync(cmd)) {
                    Log.i(TAG, "relaunched self on display 1: " + cmd);
                    // 旧实例不能留：-f 0x18000000 是强制新建 task，旧 task 会一直挂在
                    // display 0 上，下次点图标系统又复用那个旧 task（回到原 bug），task 也越堆越多。
                    // 但也不能无脑 finish：只在外屏实例真的起来之后才关掉自己：
                    // 否则万一没搬成功，用户会看到"点了没反应的死 App"。
                    for (int w = 0; w < 15 && !sCoverInstanceAlive; w++) {
                        try {
                            Thread.sleep(200);
                        } catch (InterruptedException ignored) {
                        }
                    }
                    if (sCoverInstanceAlive) {
                        Log.i(TAG, "cover instance alive, finishing display-0 instance");
                        runOnUiThread(MainActivity.this::finish);
                    } else {
                        Log.w(TAG, "cover instance never came up, keep this instance");
                    }
                    return;
                }
                Log.w(TAG, "relaunch attempt " + (i + 1) + " failed: " + Shell.lastError);
                try {
                    Thread.sleep(1500);
                } catch (InterruptedException ignored) {
                }
            }
        }, "relaunch-d1").start();
    }

    /** 申请录屏授权（appops 已预授权，系统页会自动放行）。 */
    private void requestProjection() {
        // 先把双屏并发打开：外屏(state OFF)不亮的话，录屏授权对话框没有地方显示，
        // 流程会卡在 requestProjection 之后一动不动（实测：日志停在 on display 0，画面全黑）。
        // 顺序不能反：先申请录屏，再切双屏。
        // 先切屏会触发配置变更、销毁本 Activity，挂在上面的请求就丢了。
        // 切双屏交给 Service 在拿到授权之后做。
        // 🔴 只有落在外屏的那个实例才能发起：本页发现不在 display 1 时会用 shell 重开一份自己，
        //    旧实例若继续申请，就会拿到第二份录屏授权、拉起第二个镜像实例，
        //    两个实例抢同一块屏幕 → 第二个报「录屏授权已失效」并把画面踢掉（实测踩过）。
        // 不再要求必须在外屏：悬浮层方案里入口页留在内屏即可，
        // 画面由 Service 画到 display 1。留着这个守卫会导致内屏上永远不申请录屏。
        int disp = getDisplay() != null ? getDisplay().getDisplayId() : -1;
        Log.i(TAG, "requestProjection on display " + disp);
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
            // 显式绑定默认屏（内屏 display 0）来录屏。
            // 默认行为的镜像目标跟随发起请求的 Activity 所在屏；
            // 从外屏图标点进来时 Activity 在外屏 -> 镜像外屏自己 -> 画面无限递归。
            // MediaProjectionConfig.createConfigForDefaultDisplay() 是 API 34 公开接口，
            // 强制录的是 display 0，与入口页在哪无关。
            Intent intent;
            if (android.os.Build.VERSION.SDK_INT >= 34) {
                try {
                    intent = mPm.createScreenCaptureIntent(
                            android.media.projection.MediaProjectionConfig
                                    .createConfigForDefaultDisplay());
                    Log.i(TAG, "capture config = default display (0)");
                } catch (Throwable t) {
                    Log.w(TAG, "MediaProjectionConfig failed, fallback: " + t);
                    intent = mPm.createScreenCaptureIntent();
                }
            } else {
                intent = mPm.createScreenCaptureIntent();
            }
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

        // 兜底：没有 root 也没有 Shizuku 时，主通道（shell am start 到外屏）根本走不了。
        // 此时改用悬浮层直接把画面画到外屏 —— 那条路只需要录屏授权 + 悬浮窗权限。
        // 单一通道：一律用悬浮层渲染。
        // 之前分"主通道（MirrorActivity）/ 兜底（悬浮层）"，结果关屏恢复时两条都在跑，
        // 窗口列表里同时出现 3 个悬浮窗 + 1 个 MirrorActivity，画面直接重影。
        if (true) {
            Log.i(TAG, "no shell channel -> overlay fallback");
            mStatus.setText("未检测到 Shizuku，改用兜底通道（可看不可触）");
            CoverOverlayMirrorService.start(this, resultCode, data);
            // 不立刻 finish：留一个 6 秒观察窗。切双屏的配置变更可能打断服务启动，
            // 观察到没起来就补一次申请 —— 这就是那 10~20% 失败率的兜底。
            armStartupCheck(resultCode, data);
            return;
        }

        // 授权结果走静态通道交给镜像页（shell 拉起的 Activity 传不了 Parcelable）
        MirrorActivity.sResultCode = resultCode;
        MirrorActivity.sResultData = data;

        // 顺序：先设好双屏并发 + 内屏分辨率/密度，再拉起镜像页
        SettingsStore st = new SettingsStore(this);
        final String prep =
                "cmd device_state state 4; sleep 1.2; "
                        + "wm size " + st.innerWidth() + "x" + st.innerHeight() + "; "
                        + "wm density " + st.innerDpi();
        final String cmd = "am start --display 1 -f 0x18000000 -n com.flip.mirror/.MirrorActivity";

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
