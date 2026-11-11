import io

p = 'H:/AI PG/FlipMirror/app/app/src/main/java/com/flip/mirror/MainActivity.java'
s = io.open(p, encoding='utf-8').read()

# 1) 自重开：加 MULTIPLE_TASK 标志
old1 = '''        final String cmd = "am start --display 1 -n com.flip.mirror/.MainActivity"
                + (auto ? " --ez auto_start true" : "");'''
new1 = '''        // 🔴 必须带 -f 0x18000000（FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_MULTIPLE_TASK）：
        //    只给 --display 1 时，系统会去复用 display 0 上已存在的 task（affinity 相同），
        //    窗口仍然落在 d0，然后被三星 Freecess 冻结 —— 表现就是"点进去静止、没有崩溃日志"。
        //    MULTIPLE_TASK 才会在 display 1 上真正新建 task。
        final String cmd = "am start --display 1 -f 0x18000000 -n com.flip.mirror/.MainActivity"
                + (auto ? " --ez auto_start true" : "");'''
assert s.count(old1) == 1, 'anchor1'
s = s.replace(old1, new1, 1)

# 2) 拉起镜像页：同样加标志
old2 = '''        final String cmd = "am start --display 1 -n com.flip.mirror/.MirrorActivity";'''
new2 = '''        // 同上：不加 MULTIPLE_TASK 会复用 d0 的 task，镜像页窗口落在内屏（看不见）
        final String cmd = "am start --display 1 -f 0x18000000 -n com.flip.mirror/.MirrorActivity";'''
assert s.count(old2) == 1, 'anchor2'
s = s.replace(old2, new2, 1)

io.open(p, 'w', encoding='utf-8').write(s)
print('flags added to both launches')
