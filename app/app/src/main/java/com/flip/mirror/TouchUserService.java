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
                // 🔴 必须标明事件来源是触摸屏。obtain() 默认 source=SOURCE_UNKNOWN(0)，
                // 系统对未知来源的手势判定不稳定（点按能过，滑动时灵时不灵）。
                try {
                    ev.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
                } catch (Throwable ignored) { }
                try {
                    Method m = MotionEvent.class.getDeclaredMethod("setDisplayId", int.class);
                    m.setAccessible(true);
                    m.invoke(ev, displayId);
                } catch (Throwable ignored) { }
                // 回退成 0 = ASYNC：binder 调用本身是串行发的，顺序有保证；
                // 用 1(WAIT_FOR_FINISH) 会让每个 MOVE 等一次往返，整条手势被拉长，
                // launcher 判不出 fling ⇒ 翻页失效（实测）。
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

    /**
     * 多指注入（双指缩放/双指滑动）。
     *
     * <p>单指那条 {@link #inject} 用 {@code MotionEvent.obtain(down,ev,action,x,y,meta)} 构造，
     * 天生只能带一个点。多指必须走 PointerProperties/PointerCoords 那一版 obtain，
     * 并且 POINTER_DOWN/POINTER_UP 要把「动的是第几个点」编进 action 高位，否则系统判不出。
     */
    @Override
    public void injectMulti(int action, int[] ids, float[] xs, float[] ys,
                            int actionIndex, long downTime, long eventTime, int displayId) {
        if (!mReady || ids == null || xs == null || ys == null) {
            return;
        }
        int n = Math.min(ids.length, Math.min(xs.length, ys.length));
        if (n <= 0) {
            return;
        }
        if (n == 1) {
            // 单点直接走老通道，行为完全不变
            inject(action, xs[0], ys[0], downTime, eventTime, displayId);
            return;
        }
        try {
            MotionEvent.PointerProperties[] props = new MotionEvent.PointerProperties[n];
            MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[n];
            for (int i = 0; i < n; i++) {
                MotionEvent.PointerProperties pp = new MotionEvent.PointerProperties();
                pp.id = ids[i];
                pp.toolType = MotionEvent.TOOL_TYPE_FINGER;
                props[i] = pp;
                MotionEvent.PointerCoords pc = new MotionEvent.PointerCoords();
                pc.x = xs[i];
                pc.y = ys[i];
                pc.pressure = 1f;
                pc.size = 1f;
                coords[i] = pc;
            }
            int act = action;
            if (action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_POINTER_UP) {
                act = action | (actionIndex << MotionEvent.ACTION_POINTER_INDEX_SHIFT);
            }
            MotionEvent ev = MotionEvent.obtain(downTime, eventTime, act, n, props, coords,
                    0, 0, 1f, 1f, 0, 0,
                    android.view.InputDevice.SOURCE_TOUCHSCREEN, 0);
            try {
                Method m = MotionEvent.class.getDeclaredMethod("setDisplayId", int.class);
                m.setAccessible(true);
                m.invoke(ev, displayId);
            } catch (Throwable ignored) { }
            mInject.invoke(mInputManager, ev, 0);
            ev.recycle();
        } catch (Throwable t) {
            Log.w(TAG, "injectMulti failed: " + t);
        }
    }

    /** 兜底：本进程是 shell uid，直接跑 input 命令。 */    private void fallbackCommand(int action, float x, float y, int displayId) {
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
