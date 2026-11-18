package rootcap;

import android.content.Context;
import android.content.ContextWrapper;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;

import java.io.FileOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;

/**
 * 最小可行性实验 v2：建 VIRTUAL_DISPLAY_FLAG_SECURE 虚拟屏并抓一帧。
 *
 * v1 失败于 `SecurityException: packageName must match the calling uid`
 * （app_process 以 shell uid 跑，但系统 Context 的包名是 android）。
 * 这里用「包名可控的 ContextWrapper + 反射自建 DisplayManager」绕开，包名填 com.android.shell
 * —— 只有它才和 adb shell 的 uid 2000 对得上。
 *
 * 判据：抓出的 raw 像素**非全黑**才算可行（全黑 = secure 层没进这块屏）。
 * 注意 PT-632：ImageReader 尺寸必须与虚拟屏完全一致，否则一帧都不来。
 */
public class SecureCap {

    private static final String PKG = "com.android.shell";

    public static void main(String[] args) {
        try {
            Looper.prepareMainLooper();
            int w = args.length > 0 ? Integer.parseInt(args[0]) : 1080;
            int h = args.length > 1 ? Integer.parseInt(args[1]) : 944;
            int dpi = args.length > 2 ? Integer.parseInt(args[2]) : 340;
            String out = args.length > 3 ? args[3] : "/data/local/tmp/sec.raw";

            Context base = systemContext();
            Context fake = new ContextWrapper(base) {
                @Override
                public String getPackageName() {
                    return PKG;
                }
            };

            Class<?> dmCls = Class.forName("android.hardware.display.DisplayManager");
            Constructor<?> ctor = dmCls.getDeclaredConstructor(Context.class);
            ctor.setAccessible(true);
            Object dm = ctor.newInstance(fake);

            ImageReader reader = ImageReader.newInstance(
                    w, h, android.graphics.PixelFormat.RGBA_8888, 2);
            Surface surface = reader.getSurface();
            int flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR
                    | DisplayManager.VIRTUAL_DISPLAY_FLAG_SECURE;
            System.out.println("try pkg=" + PKG + " " + w + "x" + h + "@" + dpi + " flags=" + flags);

            // 运行时只有 (String,int,int,int,Surface,int) 这个公开重载；
            // 关键是 dm 是用「假包名 Context」建的，所以 packageName 校验能过。
            Method create = null;
            for (Method m : dmCls.getDeclaredMethods()) {
                Class<?>[] ps = m.getParameterTypes();
                if (m.getName().equals("createVirtualDisplay") && ps.length == 6
                        && ps[5] == int.class && ps[0] == String.class) {
                    create = m;
                    break;
                }
            }
            if (create == null) {
                System.out.println("FAIL: 6-arg createVirtualDisplay not found");
                System.exit(3);
            }
            create.setAccessible(true);
            Object vd = create.invoke(dm, "seccap", w, h, dpi, surface, flags);
            System.out.println("OK created vd=" + (vd instanceof VirtualDisplay));

            Image img = null;
            for (int i = 0; i < 60 && img == null; i++) {
                Thread.sleep(100);
                img = reader.acquireLatestImage();
            }
            if (img == null) {
                System.out.println("FAIL: NO FRAME (60 tries)");
                System.exit(2);
            }
            Image.Plane p = img.getPlanes()[0];
            ByteBuffer buf = p.getBuffer();
            byte[] data = new byte[buf.remaining()];
            buf.get(data);
            FileOutputStream fos = new FileOutputStream(out);
            fos.write(data);
            fos.close();
            System.out.println("OK wrote " + data.length + " bytes stride=" + p.getRowStride());
            img.close();
            System.exit(0);
        } catch (Throwable t) {
            Throwable c = t;
            while (c.getCause() != null) {
                c = c.getCause();
            }
            System.out.println("FAIL: " + c.getClass().getName() + ": " + c.getMessage());
            System.exit(1);
        }
    }

    private static Context systemContext() throws Exception {
        Class<?> at = Class.forName("android.app.ActivityThread");
        Method systemMain = at.getDeclaredMethod("systemMain");
        systemMain.setAccessible(true);
        Object thread = systemMain.invoke(null);
        Method getSystemContext = at.getDeclaredMethod("getSystemContext");
        getSystemContext.setAccessible(true);
        return (Context) getSystemContext.invoke(thread);
    }
}
