import io

p = 'H:/AI PG/FlipMirror/app/app/src/main/java/com/flip/mirror/CoverOverlayMirrorService.java'
s = io.open(p, encoding='utf-8').read()

old = '''            mVirtualDisplay = mProjection.createVirtualDisplay(
                    "flip-overlay",
                    capW, capH, getResources().getDisplayMetrics().densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    mReader.getSurface(), null, h);'''

new = '''            // 🔴 必须用 VirtualDisplayConfig 显式指定"镜像 display 0（内屏）"。
            //    只用 VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR 时，镜像的是系统"默认屏"；
            //    而 device_state 4（双屏并发）下默认屏会被切到外屏，
            //    结果把外屏自己拍进去 → 画面无限递归嵌套（实测踩过：满屏自己的控件条）。
            android.hardware.display.VirtualDisplayConfig vdConfig =
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

assert s.count(old) == 1, 'vd anchor'
s = s.replace(old, new, 1)
io.open(p, 'w', encoding='utf-8').write(s)
print('explicit mirror display')
