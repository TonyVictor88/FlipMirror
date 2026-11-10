package com.flip.mirror;

import android.util.Log;

/**
 * 统一 shell 执行层：**优先 root，其次 Shizuku**。
 *
 * <h3>为什么改成优先 root</h3>
 * 这台机器开机就是临时 root（KernelSU）。而 Shizuku 的服务端（shizuku_server）是个
 * 靠 adb/root 启动的独立进程，被系统回收后整个 App 的 shell 能力就全废了 ——
 * 触摸注入、✕ 退出、切分辨率、拉镜像全部静默失败，用户看到的就是"卡住"
 * （2026-11-10 实测：shizuku_server 进程消失，日志里刷 InvocationTargetException）。
 *
 * 所以改成：能 root 就用 root（`su -c`），root 不可用时才退回 Shizuku。
 * 两条路都失败时**打印明确日志**，方便定位，而不是静默什么都不做。
 */
public final class Shell {

    private static final String TAG = "FlipMirrorShell";

    /** 最近一次执行是否成功（供 UI 判断要不要提示用户）。 */
    public static volatile boolean lastOk = true;
    /** 最近一次失败原因。 */
    public static volatile String lastError = null;

    private Shell() { }

    /** 执行一条 shell 命令（异步，不阻塞调用线程）。 */
    public static void exec(final String cmd) {
        new Thread(() -> execSync(cmd), "shell-exec").start();
    }

    /** 同步执行，返回是否成功。 */
    public static boolean execSync(String cmd) {
        if (tryRoot(cmd)) {
            lastOk = true;
            lastError = null;
            Log.i(TAG, "root: " + cmd);
            return true;
        }
        if (tryShizuku(cmd)) {
            lastOk = true;
            lastError = null;
            Log.i(TAG, "shizuku: " + cmd);
            return true;
        }
        lastOk = false;
        Log.w(TAG, "shell failed (no root, no shizuku): " + cmd);
        return false;
    }

    /** KernelSU / Magisk 的 su。 */
    private static boolean tryRoot(String cmd) {
        // 临时 root 的 su 不一定在 App 的 PATH 里，逐个绝对路径试
        String[] bins = {"su", "/system/bin/su", "/system/xbin/su",
                "/data/adb/ksu/bin/su", "/sbin/su", "/debug_ramdisk/su"};
        for (String bin : bins) {
            try {
                Process p = Runtime.getRuntime().exec(new String[]{bin, "-c", cmd});
                int rc = p.waitFor();
                if (rc == 0) {
                    return true;
                }
            } catch (Throwable t) {
                lastError = bin + ": " + t.getMessage();
            }
        }
        return false;
    }

    /**
     * 备用通道：Shizuku。
     *
     * 🔴 必须先 pingBinder 再调用，且失败重试一次。
     *    服务端（shizuku_server）被杀或重启后，App 手里的 binder 会变成死引用，
     *    此时直接 newProcess 会抛异常；而异常被吞掉后表现为进程在、但一切静默失败 ——
     *    这是本项目反复点了没反应的根因之一。ping 一次能让客户端重新绑定。
     */
    private static boolean tryShizuku(String cmd) {
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                if (!rikka.shizuku.Shizuku.pingBinder()) {
                    lastError = "shizuku: binder dead (attempt " + attempt + ")";
                    Thread.sleep(300);
                    continue;
                }
                if (rikka.shizuku.Shizuku.checkSelfPermission() != 0) {
                    lastError = "shizuku: permission not granted";
                    return false;
                }
                java.lang.reflect.Method m = rikka.shizuku.Shizuku.class.getDeclaredMethod(
                        "newProcess", String[].class, String[].class, String.class);
                m.setAccessible(true);
                m.invoke(null, new String[]{"/system/bin/sh", "-c", cmd}, null, null);
                return true;
            } catch (Throwable t) {
                lastError = "shizuku: " + t;
                try {
                    Thread.sleep(300);
                } catch (InterruptedException ignored) { }
            }
        }
        return false;
    }

    /** 当前是否有可用通道（给 UI 提示用）。 */
    public static boolean available() {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", "id"});
            p.waitFor();
            return true;
        } catch (Throwable ignored) { }
        try {
            return rikka.shizuku.Shizuku.pingBinder()
                    && rikka.shizuku.Shizuku.checkSelfPermission() == 0;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
