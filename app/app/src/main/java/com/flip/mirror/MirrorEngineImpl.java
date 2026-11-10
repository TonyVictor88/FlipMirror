package com.flip.mirror;

import android.content.Context;
import android.content.res.Resources;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.projection.MediaProjection;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.Surface;

/**
 * {@link MirrorEngine} 的默认实现：内屏（display 0）画面 **零编码** 直出到外屏 SurfaceView。
 *
 * <h3>为什么不会花屏</h3>
 * <pre>
 *   内屏合成结果 (SurfaceFlinger)
 *        └─ VirtualDisplay (AUTO_MIRROR)
 *              └─ outputSurface = SurfaceView.getHolder().getSurface()   ← 直通
 *                    └─ 外屏显示
 * </pre>
 * 全链路只有 GPU 合成 / 显示，**没有 ImageReader、没有 MediaCodec、没有编码-解码往返**，
 * 因此不存在花屏、不存在关键帧等待，延迟主要来自一次 display 合成（通常 16~33ms）。
 * 任何"先把画面读成 Image 再送编码器"的做法都会引入撕裂/花屏，这里坚决不走。
 *
 * <h3>Android 14（API 34）的两个硬限制</h3>
 * <ol>
 *   <li>createVirtualDisplay 之前**必须**先 {@code projection.registerCallback(...)}，否则抛
 *       {@link IllegalStateException}。</li>
 *   <li>一个 MediaProjection 授权令牌**只能创建一次** VirtualDisplay；再次创建抛
 *       {@link SecurityException}。所以 {@link #resize(int, int)} 必须用
 *       {@link VirtualDisplay#resize(int, int, int)} 原地改尺寸，而**不能** stop 后重新 start。</li>
 * </ol>
 */
public class MirrorEngineImpl implements MirrorEngine {

    private static final String TAG = "MirrorEngine";

    /** 自定义异常原因：系统/用户回收了投屏授权（令牌失效、被 Android 14 拒绝）。 */
    public static final int REASON_SECURITY = 1;
    /** 自定义异常原因：MediaProjection 状态非法（已 stop、未注册 callback 等）。 */
    public static final int REASON_STATE = 2;

    /**
     * 引擎自定义异常：上层捕获后可直接把 {@link #getMessage()} 显示给用户
     * （例如 Snackbar / Toast），并据 {@link #reason} 决定是否重新申请录屏授权。
     */
    public static class MirrorException extends RuntimeException {

        private final int reason;

        public MirrorException(int reason, String message, Throwable cause) {
            super(message, cause);
            this.reason = reason;
        }

        public MirrorException(int reason, String message) {
            this(reason, message, null);
        }

        /** @return {@link #REASON_SECURITY} 或 {@link #REASON_STATE} */
        public int getReason() {
            return reason;
        }
    }

    /** 镜像被动结束（用户从系统 UI 停止投屏、或授权被回收）时的回调，供 UI 收起 SurfaceView。 */
    public interface OnStoppedListener {
        void onMirrorStopped(int reason, String message);
    }

    /** 只有取 DisplayManager 时需要，可为 null（退化为 Resources.getSystem()）。 */
    private final Context appContext;

    /** MediaProjection 的 callback 需要 Handler；统一挂在主线程，避免回调线程不确定。 */
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // ---- 运行时状态（全部由 this 锁保护）----
    private MediaProjection projection;
    private MediaProjection.Callback projectionCallback;
    private VirtualDisplay virtualDisplay;
    private Surface targetSurface;
    private int width;
    private int height;
    private int densityDpi;

    // ---- 对外可见的易变状态 ----
    private volatile OnStoppedListener stoppedListener;
    private volatile String lastError;

    public MirrorEngineImpl() {
        this(null);
    }

    public MirrorEngineImpl(Context context) {
        // 只保留 application context，避免持有 Activity 造成泄漏；允许为 null。
        this.appContext = (context == null) ? null : context.getApplicationContext();
    }

    // ======================================================================
    //  MirrorEngine
    // ======================================================================

    @Override
    public synchronized boolean start(MediaProjection projection, Surface targetSurface,
                                      int width, int height) {
        lastError = null;

        // 1) 参数校验：这三种情况属于"调用方姿势不对"，按接口约定返回 false（原因写进 lastError）。
        if (projection == null) {
            lastError = "MediaProjection 为空：请先申请录屏授权";
            return false;
        }
        if (targetSurface == null || !targetSurface.isValid()) {
            lastError = "目标 Surface 无效或已被销毁，请等 surfaceCreated 后再 start";
            return false;
        }
        if (width <= 0 || height <= 0) {
            lastError = "目标尺寸非法：" + width + "x" + height;
            return false;
        }

        // 2) 已在运行则先释放旧的 VirtualDisplay + callback（同一实例重复 start 是安全的）。
        teardownLocked();

        // 3) 保存状态，按"与源屏一致的 dp 布局"换算密度，避免被镜像的 App 因尺寸变小而重新布局。
        this.projection = projection;
        this.targetSurface = targetSurface;
        this.width = width;
        this.height = height;
        this.densityDpi = resolveDensityLocked(width);

        // 4) Android 14 强制要求：createVirtualDisplay 之前必须先注册 callback。
        try {
            registerCallbackLocked();
        } catch (MirrorException e) {
            teardownLocked(); // 不留半初始化状态
            throw e;
        }

        // 5) 创建 VirtualDisplay。outputSurface 就是 SurfaceView 的 Surface，中间不夹任何东西。
        try {
            virtualDisplay = projection.createVirtualDisplay(
                    "flip-mirror",
                    width,
                    height,
                    densityDpi,
                    // 只用 AUTO_MIRROR。加上 PUBLIC 会把虚显放进共享 layerStack，
                    // 画面不一定落到我们给的 outputSurface 上（实测表现为黑屏）。
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    targetSurface,
                    null,   // callback：不需要逐帧回调，少一层中转
                    mainHandler);
        } catch (SecurityException e) {
            // 授权令牌被拒：授权过期、被系统回收，或该 MediaProjection 已经创建过一次 VirtualDisplay。
            teardownLocked();
            throw new MirrorException(REASON_SECURITY,
                    "录屏授权已失效或被系统拒绝，请重新申请屏幕录制权限", e);
        } catch (IllegalStateException e) {
            // MediaProjection 已经 stop()，或 createVirtualDisplay 前置条件不满足。
            teardownLocked();
            throw new MirrorException(REASON_STATE,
                    "MediaProjection 状态异常（可能已被停止），请重新申请屏幕录制权限", e);
        } catch (RuntimeException e) {
            // 其他厂商 ROM 的怪异常：释放半成品状态，把原因交给 upper layer 展示。
            Log.w(TAG, "createVirtualDisplay 失败", e);
            teardownLocked();
            lastError = "创建虚拟屏幕失败：" + e.getMessage();
            return false;
        }

        // 6) 兜底校验：创建过程中 Surface 被销毁（例如 Activity 旋转/回收），直接失败。
        if (virtualDisplay == null || !targetSurface.isValid()) {
            teardownLocked();
            lastError = "Surface 在创建虚拟屏幕过程中失效，请重建 SurfaceView";
            return false;
        }

        Log.i(TAG, "镜像已启动: " + width + "x" + height + " dpi=" + densityDpi);
        return true;
    }

    @Override
    public synchronized void stop() {
        teardownLocked();
        Log.i(TAG, "镜像已停止");
    }

    @Override
    public synchronized boolean isRunning() {
        return virtualDisplay != null && projection != null;
    }

    /**
     * 尺寸变化（例如折叠/展开、外屏窗口 resize）。
     *
     * <p>注意：这里**不做** stop + start。Android 14 起同一个 MediaProjection 令牌只允许创建一次
     * VirtualDisplay，重建会抛 {@link SecurityException}；正确做法是原地
     * {@link VirtualDisplay#resize(int, int, int)}，Surface 保持不变，画面不会闪断。
     */
    @Override
    public synchronized boolean resize(int width, int height) {
        lastError = null;

        if (virtualDisplay == null || projection == null) {
            lastError = "镜像未运行，无法 resize；请重新 start";
            return false;
        }
        if (width <= 0 || height <= 0) {
            lastError = "目标尺寸非法：" + width + "x" + height;
            return false;
        }
        if (width == this.width && height == this.height) {
            return true; // 尺寸没变，直接成功，避免无意义的 resize 引起闪烁
        }
        if (targetSurface != null && !targetSurface.isValid()) {
            lastError = "目标 Surface 已销毁，请重建 SurfaceView 后重新 start";
            return false;
        }

        int newDensity = resolveDensityLocked(width);
        try {
            virtualDisplay.resize(width, height, newDensity);
            this.width = width;
            this.height = height;
            this.densityDpi = newDensity;
            Log.i(TAG, "resize 成功: " + width + "x" + height + " dpi=" + newDensity);
            return true;
        } catch (RuntimeException e) {
            // 极少数 ROM 上 resize 失败（display 已进入 released 状态）。
            Log.w(TAG, "VirtualDisplay.resize 失败", e);
            lastError = "调整镜像尺寸失败：" + e.getMessage();
            return false;
        }
    }

    // ======================================================================
    //  扩展 API（上层用得上，但不属于接口契约）
    // ======================================================================

    /** @return 最近一次失败原因（中文，可直接展示）；成功后为 null。 */
    public String getLastError() {
        return lastError;
    }

    /**
     * 只替换输出 Surface，**不释放** VirtualDisplay。
     *
     * <p>用途：SurfaceView 被销毁/重建（Activity 重建、内外屏切换、折叠状态变化）时，如果先
     * {@link #stop()} 再 {@link #start(MediaProjection, Surface, int, int)}，在 Android 14 上会因"同一授权令牌只能
     * 创建一次 VirtualDisplay"而抛 {@link SecurityException}。正确姿势：
     * <pre>
     *   surfaceDestroyed → engine.setSurface(null)   // 摘掉输出，VD 继续活着
     *   surfaceCreated   → engine.setSurface(holder.getSurface())
     * </pre>
     *
     * @param newSurface 新目标 Surface；传 null 表示暂时摘掉输出（外屏黑屏，但投屏不中断）
     * @return 是否成功
     */
    public synchronized boolean setSurface(Surface newSurface) {
        if (virtualDisplay == null) {
            lastError = "镜像未运行，无法切换 Surface；请先 start";
            return false;
        }
        if (newSurface != null && !newSurface.isValid()) {
            lastError = "新 Surface 无效（可能刚 destroy）";
            return false;
        }
        try {
            virtualDisplay.setSurface(newSurface);
            this.targetSurface = newSurface;
            Log.i(TAG, "输出 Surface 已切换: " + (newSurface == null ? "null(暂停输出)" : "有效"));
            return true;
        } catch (RuntimeException e) {
            Log.w(TAG, "setSurface 失败", e);
            lastError = "切换输出 Surface 失败：" + e.getMessage();
            return false;
        }
    }

    /** 注册"镜像被动结束"回调；传 null 取消。回调在主线程。 */
    public void setOnStoppedListener(OnStoppedListener listener) {
        this.stoppedListener = listener;
    }

    // ======================================================================
    //  内部实现
    // ======================================================================

    /**
     * 释放 VirtualDisplay 与 callback，并清空引用。幂等，任何线程/任何状态下都可调用。
     * 注意：**不调用 projection.stop()** —— projection 由调用方持有，可能还要用于后续操作，
     * 引擎只负责注销自己的 callback。
     */
    private void teardownLocked() {
        // 先注销 callback，避免释放过程中再收到 onStop 造成重入。
        unregisterCallbackLocked();

        if (virtualDisplay != null) {
            try {
                // 先断开 Surface，再 release，能最大限度避免花屏帧残留在外屏上。
                virtualDisplay.setSurface(null);
            } catch (RuntimeException e) {
                Log.w(TAG, "VirtualDisplay.setSurface(null) 失败（可忽略）", e);
            }
            try {
                virtualDisplay.release();
            } catch (RuntimeException e) {
                Log.w(TAG, "VirtualDisplay.release() 失败（可忽略）", e);
            }
            virtualDisplay = null;
        }

        projection = null;
        targetSurface = null;
        width = 0;
        height = 0;
        densityDpi = 0;
    }

    private void registerCallbackLocked() {
        projectionCallback = new MediaProjection.Callback() {
            @Override
            public void onStop() {
                // 用户从系统悬浮 UI 停止投屏、或授权被回收时触发。
                int reason;
                String msg;
                synchronized (MirrorEngineImpl.this) {
                    teardownLocked();
                    reason = REASON_STATE;
                    msg = "录屏已被系统或用户停止";
                    lastError = msg;
                }
                Log.i(TAG, "MediaProjection.onStop");
                notifyStopped(reason, msg);
            }
        };
        try {
            projection.registerCallback(projectionCallback, mainHandler);
        } catch (RuntimeException e) {
            // 注册失败基本等同于"令牌已不可用"，按状态异常处理。
            projectionCallback = null;
            throw new MirrorException(REASON_STATE, "注册投屏回调失败，请重新申请录屏授权", e);
        }
    }

    private void unregisterCallbackLocked() {
        if (projection == null || projectionCallback == null) {
            projectionCallback = null;
            return;
        }
        try {
            projection.unregisterCallback(projectionCallback);
        } catch (RuntimeException e) {
            Log.w(TAG, "unregisterCallback 失败（可忽略）", e);
        } finally {
            projectionCallback = null;
        }
    }

    private void notifyStopped(int reason, String message) {
        OnStoppedListener l = stoppedListener;
        if (l == null) {
            return;
        }
        // 无论当前在哪个线程，都切回主线程回调，方便 UI 操作。
        if (Looper.myLooper() == Looper.getMainLooper()) {
            l.onMirrorStopped(reason, message);
        } else {
            mainHandler.post(() -> l.onMirrorStopped(reason, message));
        }
    }

    /**
     * 计算 VirtualDisplay 的 densityDpi。
     *
     * <p>MediaProjection 的 VirtualDisplay 密度决定"内容被缩放到多大"：若直接把 748 宽的画面
     * 配源屏密度（如 480dpi），逻辑宽度只有 249dp，被镜像的 App 会当成超窄屏重新布局；
     * 按宽度等比例缩放密度（480 * 748/1080 ≈ 332dpi）则保持 360dp 逻辑宽度，
     * 画面就是源屏的等比缩小版，布局不变形。
     */
    private int resolveDensityLocked(int targetWidth) {
        DisplayMetrics src = readSourceMetrics();
        int dpi;
        if (src != null && src.widthPixels > 0 && src.densityDpi > 0) {
            dpi = Math.round(src.densityDpi * (targetWidth / (float) src.widthPixels));
        } else if (src != null && src.densityDpi > 0) {
            dpi = src.densityDpi;
        } else {
            dpi = DisplayMetrics.DENSITY_DEFAULT; // 兜底 160，保证 > 0
        }
        // 防御：厂商 ROM 偶尔返回 0 或极端值，钳制到合理区间，否则 createVirtualDisplay 直接抛异常。
        return Math.max(72, Math.min(dpi, 640));
    }

    /** 读源屏（display 0）的物理尺寸与密度；拿不到返回 null（调用方兜底）。 */
    private DisplayMetrics readSourceMetrics() {
        if (appContext != null) {
            try {
                DisplayManager dm =
                        (DisplayManager) appContext.getSystemService(Context.DISPLAY_SERVICE);
                if (dm != null) {
                    Display display = dm.getDisplay(Display.DEFAULT_DISPLAY);
                    if (display != null) {
                        DisplayMetrics metrics = new DisplayMetrics();
                        display.getRealMetrics(metrics);
                        if (metrics.widthPixels > 0 && metrics.densityDpi > 0) {
                            return metrics;
                        }
                    }
                }
            } catch (RuntimeException e) {
                Log.w(TAG, "读取 DisplayManager 失败，退化为系统密度", e);
            }
        }
        try {
            return Resources.getSystem().getDisplayMetrics();
        } catch (RuntimeException e) {
            Log.w(TAG, "读取系统 DisplayMetrics 失败", e);
            return null;
        }
    }
}
