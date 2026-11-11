import io

base = 'H:/AI PG/FlipMirror/app/app/src/main/java/com/flip/mirror/'

# 1) MainActivity：一律走悬浮层通道（不再分主/备，避免两条通道同时渲染）
p = base + 'MainActivity.java'
s = io.open(p, encoding='utf-8').read()
old = '''        boolean forceOverlay = getIntent() != null
                && getIntent().getBooleanExtra("force_overlay", false);
        if (forceOverlay || !Shell.available()) {'''
new = '''        // 单一通道：一律用悬浮层渲染。
        // 之前分"主通道（MirrorActivity）/ 兜底（悬浮层）"，结果关屏恢复时两条都在跑，
        // 窗口列表里同时出现 3 个悬浮窗 + 1 个 MirrorActivity，画面直接重影。
        if (true) {'''
assert s.count(old) == 1, 'main anchor'
s = s.replace(old, new, 1)
io.open(p, 'w', encoding='utf-8').write(s)
print('1 ok: MainActivity always uses overlay')

# 2) MirrorService：停用亮屏恢复（它会把 Activity 通道拉起来）
p2 = base + 'MirrorService.java'
s2 = io.open(p2, encoding='utf-8').read()
old2 = '''        super.onCreate();
        setupScreenOnReceiver();'''
new2 = '''        super.onCreate();
        // ⚠️ 亮屏恢复已停用：恢复统一由 CoverOverlayMirrorService 负责。
        //    两处都恢复会把 MirrorActivity 通道也拉起来，和悬浮层叠在一起（重影）。'''
if old2 in s2:
    s2 = s2.replace(old2, new2, 1)
    io.open(p2, 'w', encoding='utf-8').write(s2)
    print('2 ok: MirrorService screen-on recovery disabled')
else:
    print('2 skip: anchor not found')
