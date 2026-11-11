package com.flip.mirror;

import android.app.Activity;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * 镜像页：全屏 SurfaceView 显示内屏画面，叠加自绘控件条，并把触摸反向注入回内屏。
 *
 * 数据流：
 *   内屏(display 0)
 *     → MediaProjection
 *     → VirtualDisplay(outputSurface = 本页 SurfaceView 的 Surface)  ← 关键：不编码
 *     → 外屏(display 1) 显示
 *
 *   触摸：本页 onTouchEvent → TouchInjector.send() → 注入回 display 0
 */
public class MirrorActivity extends Activity implements SurfaceHolder.Callback {

    private static final String TAG = "FlipMirror";

    /** 悬浮层要求退出镜像时发的广播。 */
    public static final String ACTION_EXIT = "com.flip.mirror.action.EXIT";

    /**
     * "当前应该有镜像"的标记文件名（放在 files 目录）。
     * 镜像启动时创建、点 ✕ 退出时删除；独立进程的看门狗只认它，
     * 所以用户主动退出后不会被自动弹回来。
     */
    public static final String FLAG_FILE = "mirror_active.flag";

    private android.content.BroadcastReceiver mExitReceiver;

    /** 镜像是否正在最前（供悬浮控件层判断按键该注入哪块屏）。 */
    private static volatile boolean sResumed = false;

    public static boolean isMirrorResumed() {
        return sResumed;
    }

    /**
     * 授权结果的进程内传递通道。
     *
     * 为什么不走 Intent extras：MirrorActivity 现在由 shell 用
     * {@code am start --display 1} 拉起（这是唯一实测可靠的落屏方式），
     * 命令行传不了 Parcelable 的 resultData，所以在同一个进程里用静态字段交接。
     */
    public static volatile int sResultCode = 0;
    public static volatile Intent sResultData = null;

    /** 当前持有 projection 的实例（防同一令牌被两份实例重复使用）。 */
    private static MirrorActivity sOwner = null;
    /** 已被新实例接管：旧实例的回调应尽快收尾，不再抢回控制权。 */
    private boolean mTakeover = false;
    /** 授权失败后是否已自动重试过一次（防死循环）。 */
    private boolean mRetried = false;

    /** 镜像引擎是否真的在跑（悬浮控件条靠它判断自己该不该活着）。 */
    private static volatile boolean sMirroring = false;

    public static boolean isMirroring() {
        return sMirroring;
    }

    /** 🔒 内容区起始 Y（物理像素）—— 已标定，勿改。 */
    private static final int CONTENT_TOP_PX = 66;
    /** 🔒 四边圆角半径（物理像素）—— 已标定，勿改。 */
    private static final int CORNER_R_PX = 48;

    public static final String EXTRA_RESULT_CODE = "result_code";
    public static final String EXTRA_RESULT_DATA = "result_data";

    private SurfaceView mSurface;
    /** 四角圆角遮罩（overlay 模式下需要重设边距，所以提成成员）。 */
    private android.view.View mCornerMask;
    private ControlBar mBar;
    private TextView mHint;
    /** 控件条是否仍画在本 Activity 里（true = 悬浮层不可用，走降级方案） */
    private boolean mBarInActivity = false;

    private MirrorEngine mEngine;
    private TouchInjector mInjector;
    private MediaProjection mProjection;

    private boolean mStarted = false;
    private int mPendingResultCode = 0;
    private Intent mPendingResultData;
    private boolean mProjectionTaken = false;  // resultData 只能消费一次
    /** 用户主动退出（点「退出」/长按控件条）——为 true 时不再自动拉回前台 */
    private boolean mExiting = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 🔴 必须落在外屏(display 1)：onActivityResult 里的 setLaunchDisplayId 不总生效，
        //    实测会被落到 display 0（已拆掉的内屏），外屏就只剩壁纸。
        try {
            android.hardware.display.DisplayManager dm =
                    (android.hardware.display.DisplayManager) getSystemService(DISPLAY_SERVICE);
            android.view.Display d1 = dm == null ? null : dm.getDisplay(1);
            if (d1 != null && (getDisplay() == null || getDisplay().getDisplayId() != 1)) {
                java.lang.reflect.Method m = android.app.Activity.class
                        .getMethod("setDisplay", android.view.Display.class);
                m.invoke(this, d1);
                Log.i(TAG, "moved to display 1");
            }
        } catch (Throwable t) {
            Log.w(TAG, "move to display 1 failed: " + t.getMessage());
        }
        // 说明：画面已经改成挂在【外屏悬浮层】上（见 attachOverlay），
        // 不再依赖本 Activity 落在哪块屏 —— 系统把任务拽到 display 0 也不影响。

        // 🔴 方向：实测这块副屏**不接受 App 的 setRequestedOrientation**
        // （试过 LANDSCAPE / REVERSE_PORTRAIT，screencap 画面完全不变，说明系统锁定了副屏旋转）。
        // 参照物是 SubHome：它的帧缓冲就是用户看到的样子（内容 y=66~720），所以这里不需要转，
        // 只要把「顶部 66px 让给控件条、内容从 66 铺到 720」对齐即可。

        // 🔴 关键：让窗口覆盖到挖孔区（否则系统切掉顶部 66px，SurfaceView 只能拿到 748x654）
        getWindow().setFlags(android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        android.view.WindowManager.LayoutParams winLp = getWindow().getAttributes();
        winLp.layoutInDisplayCutoutMode =
                android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        getWindow().setAttributes(winLp);
        // Android 11+ 还要关掉“decor 适配系统栏”，才是真全屏
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
        }
        getWindow().getDecorView().setSystemUiVisibility(
                android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
                        | android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);

        int resultCode = getIntent().getIntExtra(EXTRA_RESULT_CODE, 0);
        Intent resultData = getIntent().getParcelableExtra(EXTRA_RESULT_DATA, Intent.class);
        if (resultCode == 0 || resultData == null) {
            // 走 shell 拉起时没有 extras，从静态通道取
            resultCode = sResultCode;
            resultData = sResultData;
            // 一次性消费：留着只会让下一次启动拿到已失效的令牌
            sResultCode = 0;
            sResultData = null;
        }
        if (resultCode == 0 || resultData == null) {
            // 被 auto-bring-back 拉回时会用 SINGLE_TOP 复用实例，不会走到这里；
            // 真的没有授权才退出
            Log.e(TAG, "missing projection grant");
            finish();
            return;
        }

        // 同一时间只允许一个"活跃"镜像实例：多份实例共用同一份 resultData 时，
        // 第二次 createVirtualDisplay 必定抛 SecurityException。
        //
        // 但**不能一见到旧实例就退出**：关屏后旧实例只是 paused，
        // 它的 surface 已被销毁（日志里的 surfaceDestroyed / 镜像已停止），
        // 此时亮屏恢复会创建新实例 —— 若直接 finish，就会来回踢皮球、永远回不到镜像。
        // 所以：旧实例存在就**接管**（清掉 sOwner），而不是退出。
        if (sOwner != null && sOwner != this) {
            Log.i(TAG, "taking over mirror from previous instance");
            try {
                sOwner.mTakeover = true;
            } catch (Throwable ignored) { }
            sOwner = null;
        }
        sOwner = this;

        mPendingResultCode = resultCode;
        mPendingResultData = resultData;

        // 🔴 顺序很关键：先把 mediaProjection 类型的前台服务拉起来，
        // 系统才允许 getMediaProjection()；否则 system_server 直接拒绝。
        try {
            startForegroundService(new Intent(this, MirrorService.class));
            Log.i(TAG, "MirrorService started");
        } catch (Exception e) {
            Log.e(TAG, "startForegroundService failed", e);
        }


        // ⚠️ 一律用 dp 布局，不要写死像素。
        // 外屏在某些情况下会被系统放进 compat sandbox（实测 Requested 1496x1440 + scale 0.5），
        // 此时 App 的坐标空间不是 748x720，写死 px 会让内容跑到一半就断掉、底部留大片黑边。
        // 31dp ≈ 340dpi 下的 66px —— 与「正常外屏」的顶部黑条高度一致（实测 SubHome 内容从 y=66 起）。
        // 🔴 几何一律按「物理像素 × 缩放比」换算，不用 dp。
        // 原因：外屏分辨率被 wm size 改过、且系统可能把 App 放进 compat sandbox，
        // dp→px 的换算会随状态变化，每次都算出不同结果（这就是之前反复差几像素的根源）。
        float appW = getResources().getDisplayMetrics().widthPixels;
        final float scale = appW / 748f;                     // App 坐标 / 物理坐标
        // 🔒 已与正常外屏逐像素标定（SubHome 参照：内容行 66..719、角部延伸 32/24/17/12/6/2）
        //    —— 写死，不再从设置读，避免误改后又要重新标定。
        final int barPx = Math.round(CONTENT_TOP_PX * scale);
        final float cornerR = CORNER_R_PX * scale;   // 仅 Activity 窗口模式用；overlay 下由 mask 自算

        // 根布局
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);

        mSurface = new SurfaceView(this);
        mSurface.getHolder().addCallback(this);
        // 必须用 OPAQUE：MediaProjection 直出的帧是不透明 RGBA，
        // 用 TRANSLUCENT 会导致 SurfaceView 合成层拿不到内容（表现为黑屏）。
        mSurface.getHolder().setFormat(PixelFormat.OPAQUE);
        // 铺满控件条以下的全部区域（宽度 MATCH_PARENT，高度 MATCH_PARENT + topMargin）
        FrameLayout.LayoutParams svLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        svLp.gravity = Gravity.TOP | Gravity.START;
        svLp.topMargin = barPx;
        svLp.bottomMargin = 0;   // 参照图实测内容到 y=719，不留白
        // 🔴 四边圆角：外屏正常状态是一整个圆角矩形（dumpsys: RoundedCorners radius=49px @748 宽）。
        // ⚠️ SurfaceView 是独立图层，setClipToOutline 裁不到它（实测无效），
        //    所以改在它上面盖一层遮罩：把四个方角涂黑，视觉上就是圆角。
        // ⚠️ 半径必须按【宽度比例】算，不能按 dp：App 可能被放进 compat sandbox（480dpi + 0.5 缩放），
        //    dp 换算出来的半径会缩水（23dp → 实际只有 34px）。
        root.addView(mSurface, svLp);

        mCornerMask = new android.view.View(this) {
            private final android.graphics.Paint pt =
                    new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            private final android.graphics.Path path = new android.graphics.Path();
            { pt.setColor(0xFF000000); }
            @Override
            protected void onDraw(android.graphics.Canvas c) {
                int w = getWidth(), h = getHeight();
                // 🔴 半径按**自身实际宽度**算（48/748 = 0.064），
                //    不依赖外部 scale —— overlay 与 Activity 的坐标空间可能不同
                //    （compat sandbox 下 Activity 是 1496 逻辑、overlay 是 748 物理），
                //    用外部算好的值会差一倍，表现为圆角跑到画面内。
                float r = w * 0.064f;
                path.reset();
                path.addRect(0, 0, w, h, android.graphics.Path.Direction.CW);
                path.addRoundRect(0, 0, w, h, r, r, android.graphics.Path.Direction.CW);
                path.setFillType(android.graphics.Path.FillType.EVEN_ODD);
                c.drawPath(path, pt);
            }
        };
        root.addView(mCornerMask, new FrameLayout.LayoutParams(svLp));

        // 通用间距
        float density = getResources().getDisplayMetrics().density;
        int m = (int) (8 * density);

        // 控件条浮在上层
        mBar = new ControlBar(this);
        FrameLayout.LayoutParams barLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.END);   // 控件条放右上角
        barLp.setMargins(0, 0, 0, 0);
        barLp.height = barPx;
        // 优先用系统悬浮层（能浮在 Recents/桌面上方，退不出去的问题才能根治）；
        // 没有悬浮窗权限时退回"把控件条放在 Acitivity 里"的老方案。
        boolean overlayOk = android.provider.Settings.canDrawOverlays(this);
        if (overlayOk) {
            try {
                startForegroundService(new Intent(this, BarOverlayService.class));
            } catch (Throwable t) {
                Log.w(TAG, "start overlay bar failed", t);
                root.addView(mBar, barLp);
                mBarInActivity = true;
            }
        } else {
            root.addView(mBar, barLp);
            mBarInActivity = true;
        }

        // 顶部提示（可自动消失）
        mHint = new TextView(this);
        mHint.setText("镜像运行中 · 双击可退出");
        mHint.setTextColor(0xCCFFFFFF);
        mHint.setTextSize(11);
        mHint.setBackgroundColor(0x66000000);
        FrameLayout.LayoutParams hintLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.START);
        hintLp.setMargins(m, m, m, m);
        root.addView(mHint, hintLp);

        // 🔴 关键改动：画面不再画在本 Activity 的窗口里，而是挂到【外屏(display 1)的系统悬浮层】。
        //    原因：系统的录屏授权页会强制落到 display 0（已拆掉的内屏），把整个任务拽过去，
        //    Activity 在 display 0 上就是不可见的 —— 用户看到"点了没反应"。
        //    挂到悬浮层后，Activity 在哪块屏都无所谓，画面固定出现在外屏。
        // 🔴 画面挂到外屏的系统悬浮层（不再依赖 Activity 窗口）。
        //    之前那次"overlay 全黑"的真因是 window 用了 TRANSLUCENT（见 attachOverlay 注释），
        //    改用 OPAQUE 后实测 surface 正常（748x622 有帧）。
        //    挂 overlay 的意义：SubHome 是 type=home 的 Task，普通 Activity 永远压不过它；
        //    但 overlay 不参与 Activity 栈竞争，关屏/亮屏/SubHome 抢前台都盖不住。
        attachOverlay(root);

        // 🔴 防全面屏手势退出：把四边都声明为"手势排除区"（API 29+）。
        // 否则从屏幕边缘滑动会被系统当成返回/回桌面，直接把壳退掉。
        android.view.View decor = getWindow().getDecorView();
        decor.post(() -> {
            try {
                int w = decor.getWidth(), h = decor.getHeight();
                // 整个窗口都排除（系统对每方向有个 200dp 上限，这里给满）
                java.util.List<android.graphics.Rect> ex = new java.util.ArrayList<>();
                ex.add(new android.graphics.Rect(0, 0, w, h));
                decor.setSystemGestureExclusionRects(ex);
                Log.i(TAG, "gesture exclusion set: " + w + "x" + h + " edges=40px");
            } catch (Throwable t) {
                Log.w(TAG, "setSystemGestureExclusionRects failed: " + t.getMessage());
            }
        });


        // 悬浮控件条点「退出」→ 这里收尾（不用 force-stop，避免把 SubHome 也顶掉）
        mExitReceiver = new android.content.BroadcastReceiver() {
            @Override
            public void onReceive(android.content.Context c, Intent i) {
                exitApp();
            }
        };
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            registerReceiver(mExitReceiver, new android.content.IntentFilter(ACTION_EXIT),
                    android.content.Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(mExitReceiver, new android.content.IntentFilter(ACTION_EXIT));
        }
        mEngine = new MirrorEngineImpl(this);
        mInjector = new TouchInjectorImpl();

        // 🔴 A: 启动自检 —— 用 Shizuku 确保「双屏模式 + 内屏分辨率」就位
        mInjector.init();                 // 先拿 Shizuku
        ensureDeviceState();

        // 🔴 B: 锁定任务，防止全面屏手势/Home 键退出
        setShowWhenLocked(true);
        setTurnScreenOn(true);
        // ⚠️ 不再调用 startLockTask()：
        //    App 不是 Device Owner（设备有账号，设不了），startLockTask 会弹出
        //    「此屏幕会固定显示…不用了/知道了」的确认框，每次启动都挡住流程。
        //    防手势退出已经由 GestureBlockService（无障碍）负责。



        // 兜底退出：长按控件条 2 秒
        mBar.setOnLongClickListener(v -> { exitApp(); return true; });

        if (mBarInActivity) {
        mBar.setListener(new ControlBar.Listener() {
            @Override public void onBack()    { injectKey(KeyEvent.KEYCODE_BACK); }
            @Override public void onHome()    { injectKey(KeyEvent.KEYCODE_HOME); }
            @Override public void onRecents() { injectKey(KeyEvent.KEYCODE_APP_SWITCH); }
            @Override public void onExit()    { exitApp(); }
            @Override public void onCollapse(){ /* 由 ControlBar 内部处理收起动画 */ }
            @Override public void onExpand()  { /* 同上 */ }
        });
        }
    }

    private WindowManager mOverlayWm;
    private android.view.View mOverlayView;

    /** 把根布局挂到外屏的系统悬浮层；失败则退回普通 setContentView。 */
    private void attachOverlay(android.view.View root) {
        try {
            android.hardware.display.DisplayManager dm =
                    (android.hardware.display.DisplayManager) getSystemService(DISPLAY_SERVICE);
            android.view.Display d1 = dm == null ? null : dm.getDisplay(1);
            if (d1 == null) {
                throw new IllegalStateException("display 1 不可用");
            }
            android.content.Context dCtx = createDisplayContext(d1);
            mOverlayWm = (WindowManager) dCtx.getSystemService(WINDOW_SERVICE);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    // 🔴 必须是 OPAQUE：之前用 TRANSLUCENT 导致 SurfaceView 在 overlay 里全黑
                    //    （window 层透明 + Surface 层不透明，合成结果拿不到画面）。
                    //    实测 OPAQUE 下 surface 正常回调、画面正常显示。
                    PixelFormat.OPAQUE);
            lp.gravity = Gravity.TOP | Gravity.START;
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                lp.layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            }
            // 🔴 overlay 的坐标空间和 Activity 可能不同（compat sandbox）：
            //    Activity 是 1496 逻辑像素、overlay 是 748 物理像素。
            //    onCreate 里按 getResources() 算出的 barPx=132 在 overlay 里会被当成
            //    物理像素 → 顶部黑带高一倍、圆角遮罩整体下移。这里按 display context 重算。
            float ow = dCtx.getResources().getDisplayMetrics().widthPixels;
            float oscale = (ow > 0 ? ow : 748f) / 748f;
            int obarPx = Math.round(CONTENT_TOP_PX * oscale);
            Log.i(TAG, "overlay metrics w=" + ow + " scale=" + oscale + " barPx=" + obarPx);
            if (mSurface != null && mSurface.getLayoutParams() instanceof FrameLayout.LayoutParams) {
                FrameLayout.LayoutParams l2 = (FrameLayout.LayoutParams) mSurface.getLayoutParams();
                l2.topMargin = obarPx;
                mSurface.setLayoutParams(l2);
            }
            if (mCornerMask != null && mCornerMask.getLayoutParams() instanceof FrameLayout.LayoutParams) {
                FrameLayout.LayoutParams l3 = (FrameLayout.LayoutParams) mCornerMask.getLayoutParams();
                l3.topMargin = obarPx;
                mCornerMask.setLayoutParams(l3);
            }

            // overlay 窗口要自己接触摸，再转发注入内屏
            root.setOnTouchListener((v, e) -> handleMirrorTouch(e));
            mOverlayWm.addView(root, lp);
            mOverlayView = root;
            Log.i(TAG, "mirror view attached as overlay on display 1 (OPAQUE, touch enabled)");
        } catch (Throwable t) {
            Log.w(TAG, "attachOverlay failed, fallback to activity window: " + t.getMessage());
            setContentView(root);
        }
    }

    private void detachOverlay() {
        try {
            if (mOverlayWm != null && mOverlayView != null) {
                mOverlayWm.removeView(mOverlayView);
            }
        } catch (Throwable ignored) { }
        mOverlayWm = null;
        mOverlayView = null;
    }

    /** 悬浮层里的触摸：换算成 SurfaceView 内部坐标后注入内屏。 */
    private boolean handleMirrorTouch(MotionEvent e) {
        if (mInjector == null || mSurface == null) {
            return false;
        }
        float x = e.getX() - mSurface.getLeft();
        float y = e.getY() - mSurface.getTop();
        if (y < 0 || x < 0 || x >= mSurface.getWidth() || y >= mSurface.getHeight()) {
            return false;
        }
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_MOVE:
            case MotionEvent.ACTION_UP:
                mInjector.send(x, y, e.getActionMasked());
                return true;
            default:
                return true;
        }
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        Log.i(TAG, "surfaceCreated engineRunning=" + (mEngine != null && mEngine.isRunning()));
        // 延迟到前台服务就绪（首次启动需要）
        mSurface.postDelayed(() -> {
            // 🔴 引擎还活着（关屏后没停）→ 只换绑 surface，**不重新授权**
            if (mEngine != null && mEngine.isRunning()) {
                boolean ok = mEngine.setSurface(holder.getSurface());
                Log.i(TAG, "rebind surface on revive = " + ok);
                if (ok) {
                    mStarted = true;
                    sMirroring = true;
                    if (mInjector != null) {
                        int[] inner = innerSize();
                        mInjector.setGeometry(holder.getSurfaceFrame().width(),
                                holder.getSurfaceFrame().height(), inner[0], inner[1]);
                    }
                    if (mHint != null) mHint.setVisibility(android.view.View.GONE);
                    return;
                }
                Log.w(TAG, "rebind failed, fall back to full restart");
            }
            mStarted = false;
            tryStartMirror(holder);
        }, 600);
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        Log.i(TAG, "surfaceChanged surface=" + width + "x" + height
                + " viewTop=" + (mSurface == null ? -1 : mSurface.getTop())
                + " viewSize=" + (mSurface == null ? -1 : mSurface.getWidth())
                + "x" + (mSurface == null ? -1 : mSurface.getHeight())
                + " densityDpi=" + getResources().getDisplayMetrics().densityDpi);
        if (mEngine != null && mEngine.isRunning()) {
            mEngine.resize(width, height);
        }
        // 注意：surface 重建后不能再用旧 projection 建 VirtualDisplay，
        // 必须重新走一次授权（Android 限制），所以这里不自动重启。
        // 源 = 内屏当前逻辑尺寸（wm size），目标 = 当前 Surface 尺寸
        // 内屏尺寸运行时读取，不写死：既支持以后改分辨率，也避免 wm size 未生效时算错
        if (mInjector != null) {
            int[] inner = innerSize();
            mInjector.setGeometry(width, height, inner[0], inner[1]);
        }
    }

    /** 写/删"应该有镜像"的标记，并启停独立进程的看门狗。 */
    private void markMirrorActive(boolean active) {
        try {
            java.io.File f = new java.io.File(getFilesDir(), FLAG_FILE);
            if (active) {
                if (!f.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    f.createNewFile();
                }
                // ⚠️ 不再启动 :wd 看门狗：实测该独立进程里 Shizuku 通道不可用（shell 一直 failed），
                //    它只会每隔 4 秒重复发起一次恢复，和主进程的亮屏监听抢同一个录屏授权，
                //    导致第二个实例报「录屏授权已失效」并把画面踢掉。
                //    恢复统一交给 MirrorService 的亮屏监听。
            } else {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
                stopService(new Intent(this, WatchdogService.class));
            }
        } catch (Throwable t) {
            Log.w(TAG, "markMirrorActive failed: " + t.getMessage());
        }
    }

    /** 读内屏（display 0）当前逻辑尺寸；读不到就退回 748x626。 */
    private int[] innerSize() {
        try {
            android.hardware.display.DisplayManager dm =
                    (android.hardware.display.DisplayManager) getSystemService(DISPLAY_SERVICE);
            android.view.Display d = dm == null ? null : dm.getDisplay(android.view.Display.DEFAULT_DISPLAY);
            if (d != null) {
                android.util.DisplayMetrics metrics = new android.util.DisplayMetrics();
                d.getRealMetrics(metrics);
                if (metrics.widthPixels > 0 && metrics.heightPixels > 0) {
                    return new int[]{metrics.widthPixels, metrics.heightPixels};
                }
            }
        } catch (Throwable ignored) { }
        return new int[]{748, 654};
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        // 🔴 关键：这里**不能** stop 引擎。
        //    关屏会销毁 surface，若跟着 stop，VirtualDisplay 就没了，
        //    而 Android 14 起同一授权令牌只能建一次 VD → 亮屏后必须重新授权（用户要手点）。
        //    正确做法：只摘掉输出，VD 继续活着，亮屏时 setSurface 换绑即可。
        Log.i(TAG, "surfaceDestroyed — detach surface only, keep VirtualDisplay alive");
        if (mEngine != null && mEngine.isRunning()) {
            mEngine.setSurface(null);
        }
        // 不置 mStarted / sMirroring = false：引擎仍在跑，看门狗不该据此关掉双屏
    }

    private void tryStartMirror(SurfaceHolder holder) {
        if (mStarted) return;
        if (holder.getSurface() == null || !holder.getSurface().isValid()) return;
        if (mProjection == null && !mProjectionTaken) {
            mProjectionTaken = true;
            MediaProjectionManager pm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            try {
                mProjection = pm.getMediaProjection(mPendingResultCode, mPendingResultData);
                Log.i(TAG, "getMediaProjection ok = " + (mProjection != null));
            } catch (Exception e) {
                Log.e(TAG, "getMediaProjection failed", e);
                mHint.setText("授权失败：" + e.getMessage());
                return;
            }
            if (mProjection == null && !mProjectionTaken) {
            mProjectionTaken = true; mHint.setText("projection 为空"); return; }
            mProjection.registerCallback(new MediaProjection.Callback() {
                @Override public void onStop() { Log.w(TAG, "projection stopped"); mStarted = false; }
            }, null);
        }
        if (mInjector != null && !mInjector.init()) {
            Log.w(TAG, "TouchInjector init failed — 触摸功能不可用（需 root 或 uinput 权限）");
        }
        int w = holder.getSurfaceFrame().width();
        int h = holder.getSurfaceFrame().height();
        if (w <= 0 || h <= 0) return;
        boolean ok;
        try {
            ok = mEngine.start(mProjection, holder.getSurface(), w, h);
            if (ok) {
                markMirrorActive(true);
            }
        } catch (MirrorEngineImpl.MirrorException e) {
            // 授权残留是最常见的一次性故障：静态通道里还是上一轮用过的 resultData，
            // 系统会拒绝复用。处理办法不是让用户手动重来，而是自动重新申请一次干净授权。
            if (!mRetried) {
                mRetried = true;
                Log.i(TAG, "stale projection -> auto retry with fresh grant");
                sMirroring = false;
                Shell.exec("am start --display 1 -n com.flip.mirror/.MainActivity --ez auto_start true");
                finish();
                return;
            }
            // 🔴 必须捕获：这是 RuntimeException，不接就会把进程崩掉，
            //    用户看到的现象是"点了开始镜像就卡住"（2026-11-10 实测崩溃：
            //    SecurityException: Don't re-use the resultData ...）
            Log.e(TAG, "engine start failed: " + e.getMessage(), e);
            sMirroring = false;
            mHint.setVisibility(android.view.View.VISIBLE);
            mHint.setText("镜像启动失败：" + e.getMessage() + "（请点 ⌄ 退出后重试）");
            return;
        } catch (Throwable t) {
            Log.e(TAG, "engine start crashed", t);
            sMirroring = false;
            mHint.setVisibility(android.view.View.VISIBLE);
            mHint.setText("镜像启动异常：" + t.getMessage());
            return;
        }
        sMirroring = ok;
        Log.i(TAG, "engine start = " + ok + " (" + w + "x" + h + ")");
        mStarted = ok;
        if (ok) {
            mHint.postDelayed(() -> mHint.setVisibility(android.view.View.GONE), 3000);
        } else {
            mHint.setText("镜像启动失败（查看 logcat: FlipMirror）");
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (mInjector == null || mSurface == null) return false;
        // 🔴 关键：把 Activity 坐标换算成 SurfaceView 内部坐标。
        // SurfaceView 有 topMargin=94（让出顶部控件条），不减去偏移会让注入点整体下偏 94px。
        float x = e.getX() - mSurface.getLeft();
        float y = e.getY() - mSurface.getTop();
        // 落在控件条区域（y<0）的触摸交给控件条自己处理，不注入内屏
        if (y < 0 || x < 0 || x >= mSurface.getWidth() || y >= mSurface.getHeight()) {
            return false;
        }
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_MOVE:
            case MotionEvent.ACTION_UP:
                mInjector.send(x, y, e.getActionMasked());
                return true;
            default:
                return true;
        }
    }

    /** 控件条触发的按键：走 Shizuku 注入到内屏（display 0）。 */
    private void injectKey(int keyCode) {
        runShell("input -d 0 keyevent " + keyCode);
        Log.i(TAG, "injectKey: " + keyCode);
    }

    /** A: 用 Shizuku 确保双屏模式 + 内屏分辨率就位（失败不阻塞）。 */
    private void ensureDeviceState() {
        new Thread(() -> {
            try {
                // ⚠️ 这里只做兜底：正常情况下 MainActivity 在拉起本页之前已经把
                //    device_state / wm size / wm density 设好了。
                //    在本页运行期间再改 wm size/density 会触发配置变更 → Activity 重建 →
                //    重建后落回 display 0（内屏），画面变成 748x588 全黑（实测踩过）。
                SettingsStore st = new SettingsStore(MirrorActivity.this);
                runShell("cmd device_state state 4");
                // 分辨率/密度只在"确实是默认值"时才补设，避免无谓的重建
                runShell("wm size " + st.innerWidth() + "x" + st.innerHeight());
                runShell("wm density " + st.innerDpi());
                Log.i(TAG, "ensureDeviceState done");
            } catch (Throwable t) {
                Log.w(TAG, "ensureDeviceState failed: " + t.getMessage());
            }
        }, "ensure-state").start();
    }

    /** 通过统一 Shell 层执行（优先 root，其次 Shizuku）。 */
    private void runShell(String cmd) {
        Shell.execSync(cmd);
        Log.i(TAG, "shell: " + cmd);
    }

    /** 退出：解除锁定并结束。 */
    private void exitApp() {
        Log.i(TAG, "exitApp called");
        mExiting = true;
        markMirrorActive(false);
        try { stopLockTask(); } catch (Throwable ignored) { }
        finishAndRemoveTask();
    }

    // 说明：底部上滑是系统保留手势（HOME），无障碍 onGesture 消费不到，也无法真正拦死。
    // 之前加过「掉出前台 300ms 自动拉回」，实际用起来反而更烦，已按 V 要求撤掉。
    // 控制内屏统一走右上角控件条的 返回 / 主页 / 多任务（注入 display 0）。

    @Override
    protected void onResume() {
        super.onResume();
        sResumed = true;
    }

    @Override
    protected void onPause() {
        super.onPause();
        sResumed = false;
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        detachOverlay();
        if (mEngine != null) mEngine.stop();
        mExiting = true;
        sMirroring = false;
        if (sOwner == this) {
            sOwner = null;
        }
        try {
            if (mExitReceiver != null) {
                unregisterReceiver(mExitReceiver);
                mExitReceiver = null;
            }
        } catch (Throwable ignored) { }
        if (mInjector != null) mInjector.release();
        if (mProjection != null) mProjection.stop();
    }
}
