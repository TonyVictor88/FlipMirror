import io

p = 'H:/AI PG/FlipMirror/app/app/src/main/java/com/flip/mirror/QuickMirrorActivity.java'
s = io.open(p, encoding='utf-8').read()

old = '''        Intent it = new Intent(this, MainActivity.class);
        it.putExtra("auto_start", true);
        it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        try {
            startActivity(it);
        } catch (Throwable t) {
            Log.w(TAG, "quick start failed: " + t.getMessage());
        }
        finish();'''

new = '''        // 🔴 必须用 shell 直接指定 display 1 拉起，不能 startActivity。
        //    用 startActivity 时系统会先把它放到 display 0，再由 MainActivity 自重开到外屏；
        //    这条路径会产生多份实例（实测任务栈 sz=5），互相抢录屏授权，
        //    首次进入经常失败、要等一次亮屏恢复才正常。
        //    而亮屏恢复走的就是 "am start --display 1"，单实例、稳定。
        new Thread(() -> {
            boolean ok = Shell.execSync(
                    "am start --display 1 -n com.flip.mirror/.MainActivity --ez auto_start true");
            if (!ok) {
                Log.w(TAG, "quick start via shell failed: " + Shell.lastError);
                // 兜底：退回普通启动（落屏可能不准，但至少能进去）
                runOnUiThread(() -> {
                    Intent it = new Intent(this, MainActivity.class);
                    it.putExtra("auto_start", true);
                    it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                    try {
                        startActivity(it);
                    } catch (Throwable t) {
                        Log.w(TAG, "fallback start failed: " + t.getMessage());
                    }
                });
            }
        }, "quick-start").start();

        finish();'''

assert s.count(old) == 1, 'anchor'
s = s.replace(old, new, 1)
io.open(p, 'w', encoding='utf-8').write(s)
print('QuickMirror now uses shell --display 1')
