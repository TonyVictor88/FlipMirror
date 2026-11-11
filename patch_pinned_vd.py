import io

p = 'H:/AI PG/FlipMirror/app/app/src/main/java/com/flip/mirror/CoverOverlayMirrorService.java'
s = io.open(p, encoding='utf-8').read()

old = '''            android.hardware.display.VirtualDisplayConfig vdConfig =
                    new android.hardware.display.VirtualDisplayConfig.Builder(
                            "flip-overlay", capW, capH,
                            getResources().getDisplayMetrics().densityDpi)
                            .setFlags(DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR)
                            .setSurface(mReader.getSurface())
                            .setDisplayIdToMirror(Display.DEFAULT_DISPLAY)
                            .build();
            Log.i(TAG, "capturing display " + Display.DEFAULT_DISPLAY + " explicitly");
            mVirtualDisplay = mProjection.createVirtualDisplay(
                    vdConfig, null, h);'''

new = '''            mVirtualDisplay = createVdMirroringInner(capW, capH, h);'''

assert s.count(old) == 1, 'vd anchor'
s = s.replace(old, new, 1)

# 追加实现方法
anchor = '''    /** 释放上一次的采集资源（虚拟屏 / ImageReader / 线程）。 */'''
impl = '''    /**
     * 创建「明确镜像内屏(display 0)」的虚拟屏。
     *
     * 只用 {@code VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR} 时镜像的是系统"默认屏"；
     * 双屏并发（device_state 4）下默认屏会被切到外屏，于是把外屏自己拍进去，
     * 画面就成了无限递归嵌套（实测：满屏都是我们自己的控件条）。
     *
     * {@code VirtualDisplayConfig.Builder#setDisplayIdToMirror} 是隐藏 API，
     * 所以整条链走反射；任何一步失败就退回旧调用（能显示，但可能递归）。
     */
    private VirtualDisplay createVdMirroringInner(int capW, int capH, Handler h) {
        try {
            Class<?> builderCls = Class.forName("android.hardware.display.VirtualDisplayConfig$Builder");
            Object builder = builderCls
                    .getConstructor(String.class, int.class, int.class, int.class)
                    .newInstance("flip-overlay", capW, capH,
                            getResources().getDisplayMetrics().densityDpi);
            builderCls.getMethod("setFlags", int.class)
                    .invoke(builder, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR);
            builderCls.getMethod("setSurface", android.view.Surface.class)
                    .invoke(builder, mReader.getSurface());

            boolean pinned = false;
            try {
                builderCls.getMethod("setDisplayIdToMirror", int.class)
                        .invoke(builder, Display.DEFAULT_DISPLAY);
                pinned = true;
            } catch (Throwable t) {
                Log.w(TAG, "setDisplayIdToMirror unavailable: " + t);
            }

            Object cfg = builderCls.getMethod("build").invoke(builder);
            Class<?> cfgCls = Class.forName("android.hardware.display.VirtualDisplayConfig");

            java.lang.reflect.Method m = MediaProjection.class.getMethod(
                    "createVirtualDisplay", cfgCls, MediaProjection.Callback.class, Handler.class);
            Object vd = m.invoke(mProjection, cfg, null, h);
            Log.i(TAG, "VD created, pinned to display 0 = " + pinned);
            return (VirtualDisplay) vd;
        } catch (Throwable t) {
            Log.w(TAG, "pinned VD failed, fallback to AUTO_MIRROR: " + t);
        }
        return mProjection.createVirtualDisplay(
                "flip-overlay", capW, capH,
                getResources().getDisplayMetrics().densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                mReader.getSurface(), null, h);
    }

'''
assert s.count(anchor) == 1, 'impl anchor'
s = s.replace(anchor, impl + anchor, 1)

io.open(p, 'w', encoding='utf-8').write(s)
print('reflective pinned VD')
