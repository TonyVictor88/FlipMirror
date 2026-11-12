package com.flip.mirror;

import android.content.pm.PackageManager;
import android.util.Log;
import android.view.MotionEvent;

import rikka.shizuku.Shizuku;

/**
 * TouchInjector 实现 —— 通过 Shizuku 拿到 shell 权限执行 `input` 命令，注入到 display 0。
 *
 * 为什么走 Shizuku 而不是 su：
 *   KernelSU 的 su 对 App 进程做了挂载隔离（/system/bin/su 在 App 视角下不存在，
 *   /data/adb/ksu/bin/su 报 Permission denied），App 无法直接 fork su。
 *   而 Shizuku 以 shell(uid 2000) 身份运行，App 通过它的 API 可以直接 newProcess 执行命令。
 *
 * 安全约束：
 *   - 进 shell 的参数全部由数值格式化而来（%.0f），不接受原始字符串
 *   - 用 newProcess(String[]) 数组形式，不拼整串
 */
public class TouchInjectorImpl implements TouchInjector {

    private static final String TAG = "FlipMirror";

    private int mSrcW = 748, mSrcH = 626;     // 外屏可见区（SurfaceView）
    private int mDstW = 748, mDstH = 626;     // 内屏逻辑尺寸
    private boolean mReady = false;
    /** 上次尝试重新绑定 UserService 的时间（断线自愈用）。 */
    private long mLastBindTry = 0;
    private long mLastInitTry = 0;            // 惰性重试节流
    private boolean mRequested = false;       // 授权请求只弹一次


    private float mDownX = -1, mDownY = -1;
    private long mDownTimeReal = 0L;
    private long mDownTime = 0;
    private long mLastMove = 0;
    /** 本次手势的 DOWN 是否真的送出去了（没送出去就别再发 MOVE/UP）。 */
    private boolean mGestureSent = false;

    // 常驻 shell 进程里的注入服务（反射经 Proxy 绑定，规避不同 Shizuku 版本的接口签名差异）
    private Object mSvc = null;
    private boolean mBinding = false;

    @Override
    public boolean init() {
        boolean shizukuOk = false;
        try {
            boolean ping = Shizuku.pingBinder();
            boolean granted = Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
            shizukuOk = ping && granted;
            Log.i(TAG, "shizuku ping=" + ping + " granted=" + granted);
            if (ping && !granted && !mRequested) {
                mRequested = true;
                Shizuku.requestPermission(1001);
                Log.i(TAG, "requested shizuku permission");
            }
        } catch (Throwable t) {
            Log.w(TAG, "shizuku init error: " + t.getMessage());
        }
        mReady = shizukuOk;
        if (shizukuOk && mSvc == null && !mBinding) {
            bindUserServiceReflect();
        }
        Log.i(TAG, "TouchInjector init ok=" + mReady + " svc=" + (mSvc != null));
        return mReady;
    }

    @Override
    public void setGeometry(int srcW, int srcH, int dstW, int dstH) {
        if (srcW > 0) mSrcW = srcW;
        if (srcH > 0) mSrcH = srcH;
        if (dstW > 0) mDstW = dstW;
        if (dstH > 0) mDstH = dstH;
        Log.i(TAG, "geometry src=" + mSrcW + "x" + mSrcH + " dst=" + mDstW + "x" + mDstH);
    }

    /**
     * 用反射 + 动态代理绑定 Shizuku UserService。
     *
     * 不直接实现 {@code Shizuku.ServiceConnection}：不同 api 版本的接口签名有差异，
     * 编译期实现会报"方法不会覆盖超类型的方法"。Proxy 运行时按方法名分发，绕开这个坑。
     */
    private void bindUserServiceReflect() {
        mBinding = true;
        try {
            ClassLoader cl = Shizuku.class.getClassLoader();
            Class<?> argsCls = Class.forName("rikka.shizuku.Shizuku$UserServiceArgs", true, cl);
            // 不同版本的类名不一样，逐个试
            Class<?> connCls = null;
            for (String cn : new String[]{"rikka.shizuku.Shizuku$ServiceConnection",
                    "rikka.shizuku.ServiceConnection", "android.content.ServiceConnection"}) {
                try {
                    connCls = Class.forName(cn, true, cl);
                    Log.i(TAG, "ServiceConnection = " + cn);
                    break;
                } catch (Throwable ignored) { }
            }
            if (connCls == null) {
                throw new ClassNotFoundException("no ServiceConnection class found");
            }

            Object args = argsCls
                    .getConstructor(android.content.ComponentName.class)
                    .newInstance(new android.content.ComponentName("com.flip.mirror",
                            TouchUserService.class.getName()));
            try {
                argsCls.getMethod("daemon", boolean.class).invoke(args, false);
                argsCls.getMethod("processNameSuffix", String.class).invoke(args, "touch");
                argsCls.getMethod("debuggable", boolean.class).invoke(args, false);
                argsCls.getMethod("version", int.class).invoke(args, 1);
            } catch (Throwable ignored) { }

            Object conn = java.lang.reflect.Proxy.newProxyInstance(cl, new Class<?>[]{connCls},
                    (proxy, method, argv) -> {
                        String n = method.getName();
                        // 🔴 动态代理必须实现 Object 的三个方法：
                        //    Shizuku 把 connection 塞进 HashSet，会调 hashCode()，
                        //    返回 null 会 NullPointerException（int 不能拆箱 null）。
                        if (n.equals("hashCode")) {
                            return System.identityHashCode(proxy);
                        }
                        if (n.equals("equals")) {
                            return argv != null && argv.length > 0 && proxy == argv[0];
                        }
                        if (n.equals("toString")) {
                            return "FlipTouchServiceConnection";
                        }
                        if (n.equals("onServiceConnected") && argv != null && argv.length >= 2
                                && argv[1] instanceof android.os.IBinder) {
                            mSvc = ITouchUserService.Stub.asInterface((android.os.IBinder) argv[1]);
                            mBinding = false;
                            Log.i(TAG, "touch userService connected");
                        } else if (n.equals("onServiceDisconnected")) {
                            mSvc = null;
                            mBinding = false;
                            Log.w(TAG, "touch userService disconnected");
                        }
                        return null;
                    });

            Shizuku.class.getMethod("bindUserService", argsCls, connCls)
                    .invoke(null, args, conn);
        } catch (Throwable t) {
            mBinding = false;
            Throwable c = t;
            while (c.getCause() != null && c.getCause() != c) { c = c.getCause(); }
            Log.w(TAG, "bindUserService(reflect) failed: " + t + " | cause=" + c
                    + " | " + android.util.Log.getStackTraceString(c));
        }
    }

    private float[] map(float sx, float sy) {
        float x = sx * mDstW / (float) mSrcW;
        float y = sy * mDstH / (float) mSrcH;
        if (x < 0) x = 0;
        if (y < 0) y = 0;
        if (x > mDstW) x = mDstW;
        if (y > mDstH) y = mDstH;
        return new float[]{x, y};
    }

    @Override
    public boolean send(float srcX, float srcY, int action) {
        return send(srcX, srcY, action, 0L);
    }

    @Override
    public boolean send(float srcX, float srcY, int action, long realEventTime) {
        // 惰性重试：Shizuku 授权可能晚到，别只判一次
        if (!mReady) {
            long now = System.currentTimeMillis();
            if (now - mLastInitTry > 2000) {
                mLastInitTry = now;
                init();
            }
            if (!mReady) return false;
        }
        // 🔴 断线自愈：UserService 挂了之后 mSvc 变 null，若不重绑会永久退回
        //    `input` 慢通道（每事件 fork 一次）—— 系统会把 DOWN/MOVE/UP 当成
        //    互不相干的三次点击，表现就是"触控失效 / 拖动不跟手"。
        if (mSvc == null && !mBinding) {
            long now2 = System.currentTimeMillis();
            if (now2 - mLastBindTry > 3000) {
                mLastBindTry = now2;
                bindUserServiceReflect();
            }
        }
        float[] p = map(srcX, srcY);

        // 🔴 DOWN 没送出去时，后面的 MOVE / UP 都是孤儿事件：目标 App 收到"没有按下的移动"，
        //    要么丢弃、要么把它当新的一次点击。整段直接丢掉，避免半截手势把内屏状态机搅乱
        //    （表现就是"时灵时不灵"：启动/重连那一下 DOWN 丢了，整次滑动就废了）。
        if (action != MotionEvent.ACTION_DOWN && !mGestureSent) {
            return false;
        }

        // 快通道：直连 UserService，事件走 binder 进 InputDispatcher，无进程 fork
        if (mSvc != null) {
            try {
                // 🔴 关键：用**原始触摸事件的时间戳**，不是发送时刻。
                // 之前每个事件都取 uptimeMillis()，一批 MOVE 落在同一毫秒里，
                // 内屏算出的滑动速度是假的（要么无穷大要么 0）→ 翻页判定时灵时不灵，
                // 且方向不对称（超速冲到底页就"回不去"）。
                long t = realEventTime > 0 ? realEventTime
                        : android.os.SystemClock.uptimeMillis();
                int act = action == MotionEvent.ACTION_DOWN ? 0
                        : action == MotionEvent.ACTION_UP ? 1
                        : action == MotionEvent.ACTION_CANCEL ? 3 : 2;
                if (action == MotionEvent.ACTION_DOWN) {
                    mDownTime = t;
                    mDownTimeReal = t;
                    mDownX = p[0];
                    mDownY = p[1];
                }
                java.lang.reflect.Method m = mSvc.getClass().getMethod("inject",
                        int.class, float.class, float.class, long.class, long.class, int.class);
                m.invoke(mSvc, act, p[0], p[1], mDownTime, t, 0);
                if (action == MotionEvent.ACTION_DOWN) {
                    mGestureSent = true;
                } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    mGestureSent = false;
                }
                return true;
            } catch (Throwable e) {
                Log.w(TAG, "svc inject failed: " + e.getMessage());
                mSvc = null;
            }
        }

        final String x = String.format(java.util.Locale.US, "%.0f", p[0]);
        final String y = String.format(java.util.Locale.US, "%.0f", p[1]);

        // 🔴 实时注入：DOWN / MOVE / UP 三个事件都立刻发。
        //    旧实现只在抬手时发一条 input tap/swipe，中间的 MOVE 全丢 → 拖动完全不跟手。
        try {
            if (action == MotionEvent.ACTION_DOWN) {
                mDownX = p[0];
                mDownY = p[1];
                // 🔴 必须和快通道/上报的 eventTime 用同一个时钟（uptimeMillis）。
                //    以前这里写 currentTimeMillis()，一旦手势中途从慢通道切到快通道，
                //    downTime 是 epoch 毫秒、eventTime 是 uptime 毫秒，相差 1.7e12ms，
                //    InputDispatcher 会把手势判成非法/新手势 → 滑到一半断掉。
                mDownTime = android.os.SystemClock.uptimeMillis();
                mLastMove = 0;
                run("input -d 0 motionevent DOWN " + x + " " + y);
                mGestureSent = true;
                return true;
            }
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                run("input -d 0 motionevent UP " + x + " " + y);
                mGestureSent = false;
                return true;
            }
            // MOVE：节流。每条命令约 100ms 开销，40ms 一次不至于把队列压死
            long now = System.currentTimeMillis();
            if (now - mLastMove >= 40) {
                mLastMove = now;
                run("input -d 0 motionevent MOVE " + x + " " + y);
            }
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "send failed: " + t.getMessage());
            return false;
        }
    }

    /** 执行注入命令（优先 root，其次 Shizuku，见 {@link Shell}）。 */
    private void run(String cmd) {
        Shell.exec(cmd);
        Log.i(TAG, "inject: " + cmd);
    }

    @Override
    public void release() {
        mReady = false;
    }
}
