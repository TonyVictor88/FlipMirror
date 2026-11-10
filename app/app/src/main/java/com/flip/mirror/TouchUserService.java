package com.flip.mirror;

import android.util.Log;
import android.view.MotionEvent;

import java.lang.reflect.Method;

/**
 * 触摸注入服务实现 —— **不是 Android Service**，而是直接继承 AIDL 的 Stub。
 *
 * <h3>为什么不是 Service</h3>
 * Shizuku 的 UserService 机制是：由 Shizuku 的 starter 进程（shell uid）
 * **直接 new 出这个类的实例**并当作 IBinder 绑定给 App。
 * 所以它必须实现 {@link android.os.IBinder}，也就是继承 {@code ITouchUserService.Stub}。
 * 若写成 android.app.Service，Shizuku 会报
 * {@code ClassCastException: ... cannot be cast to android.os.IBinder}（实测踩过）。
 *
 * <h3>为什么这样快</h3>
 * 这个实例运行在 shell uid 的常驻进程里，可直接调用系统隐藏 API
 * {@code InputManager.injectInputEvent}，事件经 binder 直达 InputDispatcher，
 * 不再为每个触摸事件 fork 一个 {@code input} 进程（100ms 级开销，是手感发飘的根因）。
 *
 * <h3>双通道</h3>
 * 隐藏 API 若被 hiddenapi 策略拦，则退回本进程直接 exec {@code input} 命令
 * （本进程是 shell uid，能直接跑）。
 */
public class TouchUserService extends ITouchUserService.Stub {

    private static final String TAG = "FlipTouchSvc";

    private Object mInputManager;
    private Method mInject;
    private boolean mReady = false;

    public TouchUserService() {
        initInputManager();
    }

    private void initInputManager() {
        try {
            Class<?> cls = Class.forName("android.hardware.input.InputManager");
            Method getInstance = cls.getDeclaredMethod("getInstance");
            getInstance.setAccessible(true);
            mInputManager = getInstance.invoke(null);
            mInject = cls.getDeclaredMethod("injectInputEvent",
                    android.view.InputEvent.class, int.class);
            mInject.setAccessible(true);
            mReady = true;
            Log.i(TAG, "InputManager ready (fast path)");
        } catch (Throwable t) {
            mReady = false;
            Log.w(TAG, "hidden API blocked, fallback to input command: " + t);
        }
    }

    @Override
    public boolean ready() {
        return mReady;
    }

    @Override
    public void inject(int action, float x, float y, long downTime, long eventTime, int displayId) {
        if (mReady) {
            try {
                MotionEvent ev = MotionEvent.obtain(downTime, eventTime, action, x, y, 0);
                try {
                    Method m = MotionEvent.class.getDeclaredMethod("setDisplayId", int.class);
                    m.setAccessible(true);
                    m.invoke(ev, displayId);
                } catch (Throwable ignored) { }
                // 0 = INJECT_INPUT_EVENT_MODE_ASYNC，不等待，避免阻塞注入线程
                mInject.invoke(mInputManager, ev, 0);
                ev.recycle();
                return;
            } catch (Throwable t) {
                Log.w(TAG, "inject failed once, fallback: " + t);
                mReady = false;
            }
        }
        fallbackCommand(action, x, y, displayId);
    }

    @Override
    public void injectBatch(int action, float[] xs, float[] ys,
                            long downTime, long eventTime, int displayId) {
        if (xs == null || ys == null) {
            return;
        }
        int n = Math.min(xs.length, ys.length);
        for (int i = 0; i < n; i++) {
            inject(action, xs[i], ys[i], downTime, eventTime + i, displayId);
        }
    }

    /** 兜底：本进程是 shell uid，直接跑 input 命令。 */
    private void fallbackCommand(int action, float x, float y, int displayId) {
        String a;
        switch (action) {
            case MotionEvent.ACTION_DOWN: a = "DOWN"; break;
            case MotionEvent.ACTION_UP:   a = "UP";   break;
            default:                      a = "MOVE"; break;
        }
        String cmd = "input -d " + displayId + " motionevent " + a
                + " " + (int) x + " " + (int) y;
        try {
            Runtime.getRuntime().exec(new String[]{"/system/bin/sh", "-c", cmd});
        } catch (Throwable ignored) { }
    }
}
