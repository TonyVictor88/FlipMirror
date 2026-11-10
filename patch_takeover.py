import io

p = 'H:/AI PG/FlipMirror/app/app/src/main/java/com/flip/mirror/MirrorActivity.java'
s = io.open(p, encoding='utf-8').read()

old = '''        // 同一时间只允许一个镜像实例：多份实例会共用同一份 resultData，
        // 第二次 createVirtualDisplay 必定抛 SecurityException 把进程崩掉。
        if (sOwner != null && sOwner != this) {
            Log.w(TAG, "another mirror instance owns the projection, finishing this one");
            finish();
            return;
        }
        sOwner = this;'''

new = '''        // 同一时间只允许一个"活跃"镜像实例：多份实例共用同一份 resultData 时，
        // 第二次 createVirtualDisplay 必定抛 SecurityException。
        //
        // 但**不能一见到旧实例就退出**：关屏后旧实例只是 paused，
        // 它的 surface 已被销毁（日志里的 surfaceDestroyed / 镜像已停止），
        // 此时亮屏恢复会创建新实例 —— 若直接 finish，就会来回踢皮球、永远回不到镜像。
        // 所以：旧实例存在就**接管**（清掉 sOwner），而不是退出。
        if (sOwner != null && sOwner != this) {
            Log.i(TAG, "taking over mirror from previous instance");
            try {
                sOwner.mTakeover = true;
            } catch (Throwable ignored) { }
            sOwner = null;
        }
        sOwner = this;'''

assert s.count(old) == 1, 'guard anchor'
s = s.replace(old, new, 1)

# 加一个标记字段（被接管的旧实例在自己的生命周期里尽快结束，不再抢回控制权）
if 'mTakeover' not in s:
    s = s.replace('    private static MirrorActivity sOwner;',
                  '    private static MirrorActivity sOwner;\n    /** 已被新实例接管：自己的回调应尽快收尾。 */\n    private boolean mTakeover = false;', 1)

io.open(p, 'w', encoding='utf-8').write(s)
print('takeover logic patched')
