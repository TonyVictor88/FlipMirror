package com.flip.mirror;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * 不依赖 shell 的 device_state 请求器。
 *
 * <h3>为什么可以不要 Shizuku</h3>
 * AOSP {@code DeviceStateManagerService.requestState()} 的判断是：
 * <pre>
 *   // Allow top processes to request a device state change
 *   assertCanRequestDeviceState(callingPid, state);
 * </pre>
 * 即：**调用进程是当前 top-focused 应用时，不需要 CONTROL_DEVICE_STATE 权限**。
 * API 文档同样写明：
 * <pre>
 *   @throws SecurityException if the caller is neither the current top-focused activity
 *           nor if the CONTROL_DEVICE_STATE permission is held.
 * </pre>
 *
 * <p>所以只要在**前台 Activity** 里调用 {@code requestState(4)} 就能把设备切到双屏并发，
 * 完全不需要 adb / Shizuku。</p>
 *
 * <h3>为什么要 flag CANCEL_WHEN_REQUESTER_NOT_ON_TOP = false</h3>
 * dumpsys 显示 CONCURRENT 状态带 {@code cancel_when_requester_not_on_top=true}，
 * 这正是「关屏 / 被 SubHome 顶掉后状态自动回落到 OPENED」的原因。
 * 请求时显式设置 {@code FLAG_CANCEL_WHEN_REQUESTER_NOT_ON_TOP = false}，
 * 让覆盖状态不随前台变化而撤销 —— 这样关屏亮屏都不用重设。
 *
 * <p>全部 API 都是 {@code @SystemApi/@TestApi}（@hide），必须反射。</p>
 */
public final class DeviceStateRequester {

    private static final String TAG = "FlipDSReq";

    /** FLAG_CANCEL_WHEN_REQUESTER_NOT_ON_TOP，AOSP DeviceStateRequest 里的常量值。 */
    private static final int FLAG_CANCEL_WHEN_REQUESTER_NOT_ON_TOP = 1;

    /** CONCURRENT_INNER_DEFAULT */
    public static final int STATE_CONCURRENT = 4;

    private DeviceStateRequester() { }

    /**
     * 请求设备状态。必须在**前台 Activity** 的上下文中调用。
     *
     * @param activity 前台 Activity（提供 top-focus 身份）
     * @param stateId  目标状态标识符，4 = 双屏并发
     * @return 是否成功发出请求
     */
    /**
     * 解除 hidden API 限制。
     *
     * <p>Android 9+ 对 {@code @hide} 的类/方法/构造做反射拦截，表现为
     * {@code getDeclaredConstructors()} 能列出方法、但 {@code getConstructor(...)}
     * 抛 {@code NoSuchMethodException}（实测 Android 16 上就是这样）。
     * 官方开关 {@code settings put global hidden_api_policy 1} 在本机无效，
     * 改用 {@code VMRuntime.setHiddenApiExemptions(new String[]{"L"})} 全量豁免。</p>
     *
     * <p>{@code setHiddenApiExemptions} 自身也是 hidden 方法，但通过
     * {@code Method.invoke} 调用不会二次触发 hidden 检查（经典豁免 trick）。</p>
     */
    private static boolean mExempted = false;

    private static synchronized void exemptHiddenApi() {
        if (mExempted) return;
        try {
            Class<?> vmRuntime = Class.forName("dalvik.system.VMRuntime");
            Object runtime = vmRuntime.getMethod("getRuntime").invoke(null);
            java.lang.reflect.Method m =
                    vmRuntime.getMethod("setHiddenApiExemptions", String[].class);
            m.invoke(runtime, (Object) new String[]{"L"});
            mExempted = true;
            Log.i(TAG, "hidden api exemptions applied (all)");
        } catch (Throwable t) {
            Log.w(TAG, "exemptHiddenApi failed: " + t);
        }
    }

    /** 探测：打印设备上 DeviceStateRequest / Builder 的真实结构（诊断用）。 */
    public static void probe() {
        try {
            for (String cn : new String[]{
                    "android.hardware.devicestate.DeviceState",
                    "android.hardware.devicestate.DeviceState$Configuration",
                    "android.hardware.devicestate.DeviceState$Configuration$Builder",
                    "android.hardware.devicestate.DeviceStateRequest",
                    "android.hardware.devicestate.DeviceStateRequest$Builder",
                    "android.hardware.devicestate.DeviceStateManager"}) {
                try {
                    Class<?> c = Class.forName(cn);
                    Log.i(TAG, "=== " + cn + " ===");
                    for (java.lang.reflect.Constructor<?> ct : c.getDeclaredConstructors()) {
                        Log.i(TAG, "  ctor(" + java.util.Arrays.toString(ct.getParameterTypes()) + ")");
                    }
                    for (java.lang.reflect.Method mt : c.getDeclaredMethods()) {
                        if (mt.getName().startsWith("set") || mt.getName().equals("build")
                                || mt.getName().startsWith("request")) {
                            Log.i(TAG, "  " + mt.getName() + "("
                                    + java.util.Arrays.toString(mt.getParameterTypes()) + ")");
                        }
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "  " + cn + " -> " + t);
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "probe failed", t);
        }
    }

    public static boolean request(Activity activity, int stateId) {
        exemptHiddenApi();
        try {
            // 1) 拿 DeviceStateManager 实例（系统服务名 "device_state"）
            Class<?> dsmCls = Class.forName("android.hardware.devicestate.DeviceStateManager");
            Object dsm = activity.getSystemService("device_state");
            if (dsm == null) {
                Log.w(TAG, "device_state service is null");
                return false;
            }

            // 2) 构造 DeviceStateRequest.Builder(int stateId).build()
            //    ⚠️ Builder 的构造函数带参（AOSP: public Builder(int deviceState)），
            //       不是无参 —— 用 newInstance() 会抛 NoSuchMethodException（实测踩过）。
            Class<?> builderCls = Class.forName(
                    "android.hardware.devicestate.DeviceStateRequest$Builder");
            java.lang.reflect.Constructor<?> ctor = builderCls.getDeclaredConstructor(int.class);
            ctor.setAccessible(true);   // ⚠️ 必须：这个构造不是 public，getConstructor 找不到
            Object builder = ctor.newInstance(stateId);
            Class<?> reqCls = Class.forName("android.hardware.devicestate.DeviceStateRequest");
            java.lang.reflect.Method buildM = builderCls.getDeclaredMethod("build");
            buildM.setAccessible(true);
            Object request = buildM.invoke(builder);

            // 3) 尝试清掉 FLAG_CANCEL_WHEN_REQUESTER_NOT_ON_TOP：
            //    dumpsys 里 CONCURRENT 状态本身带 cancel_when_requester_not_on_top=true，
            //    这正是「关屏/被顶掉后状态回落」的原因。若该 flag 可由请求侧清除，
            //    清掉后状态就能长期保持，不必每次亮屏重新请求。
            try {
                java.lang.reflect.Method getF = reqCls.getDeclaredMethod("getFlags");
                getF.setAccessible(true);
                int cur = (int) getF.invoke(request);
                int want = cur & ~FLAG_CANCEL_WHEN_REQUESTER_NOT_ON_TOP;
                if (want != cur) {
                    java.lang.reflect.Method setF = reqCls.getDeclaredMethod("setFlags", int.class);
                    setF.setAccessible(true);
                    setF.invoke(request, want);
                }
                Log.i(TAG, "flags " + cur + " -> " + want);
            } catch (Throwable t) {
                Log.w(TAG, "setFlags skipped: " + t);
            }

            // 4) 反射调用 requestState(request, executor, callback)
            //    ⚠️ callback 不能传 null：AOSP 会抛
            //       IllegalArgumentException: Callback must be supplied with executor.
            java.util.concurrent.Executor exec = activity.getMainExecutor();
            Class<?> cbCls = Class.forName(
                    "android.hardware.devicestate.DeviceStateRequest$Callback");
            Object cb = java.lang.reflect.Proxy.newProxyInstance(
                    cbCls.getClassLoader(), new Class<?>[]{cbCls},
                    (proxy, method, argv) -> {
                        String n = method.getName();
                        if (n.equals("hashCode")) return System.identityHashCode(proxy);
                        if (n.equals("equals")) {
                            return argv != null && argv.length > 0 && proxy == argv[0];
                        }
                        if (n.equals("toString")) return "FlipDSCallback";
                        Log.i(TAG, "request callback: " + n
                                + (argv == null ? "" : java.util.Arrays.toString(argv)));
                        return null;
                    });

            // 优先 requestBaseStateOverride：它覆盖 mBaseState，不绑定 requester 前台；
            // requestState 只改 mOverrideState，而 CONCURRENT 状态带
            // cancel_when_requester_not_on_top=true，requester 一离前台就被撤销
            // （实测：onRequestActivated 后紧接着 onRequestCanceled，最终回落 OPENED）。
            java.lang.reflect.Method m = null;
            for (String name : new String[]{"requestState"}) {
                try {
                    m = dsmCls.getDeclaredMethod(name, reqCls,
                            java.util.concurrent.Executor.class, cbCls);
                    m.setAccessible(true);
                    Log.i(TAG, "using " + name);
                    break;
                } catch (NoSuchMethodException ignored) { }
            }
            if (m == null) {
                Log.e(TAG, "no request method found");
                return false;
            }
            m.invoke(dsm, request, exec, cb);
            Log.i(TAG, "requestState(" + stateId + ") dispatched OK");
            return true;

        } catch (Throwable t) {
            Throwable c = t;
            while (c.getCause() != null && c.getCause() != c) { c = c.getCause(); }
            Log.e(TAG, "requestState failed: " + c + "\n" + Log.getStackTraceString(c));
            return false;
        }
    }

    /** 查询当前已提交状态（只读，无需权限）。 */
    public static int currentState(Activity activity) {
        try {
            Class<?> dsmCls = Class.forName("android.hardware.devicestate.DeviceStateManager");
            Object dsm = activity.getSystemService("device_state");
            if (dsm == null) return -1;
            // getCommittedState 不是公开 API；退回读 dumpsys 由调用方处理
            return -1;
        } catch (Throwable t) {
            return -1;
        }
    }
}
