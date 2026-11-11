package com.flip.mirror;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.Log;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 设置界面（第 1 步：只做外壳 —— 所有项都能存值，但还没接到镜像链路上）。
 *
 * 风格参考 FLIP 外屏助手：深色底 + 大圆角卡片 + 两行列表项。
 * 全程序化构建（项目没有 XML 布局资源），便于在小屏上直接改尺寸。
 */
public class SettingsActivity extends Activity {

    private static final String TAG = "FlipMirror";


    private static final int BG          = 0xFF0F1216;
    private static final int CARD        = 0xFF1A1F26;
    private static final int DIVIDER     = 0xFF272E37;
    private static final int TEXT_MAIN   = 0xFFE8EDF3;
    private static final int TEXT_SUB    = 0xFF9AA4B2;
    private static final int ACCENT      = 0xFFA8C7FA;

    private SettingsStore store;
    private LinearLayout content;
    private float density;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 已移除：以前这里会 exec `cmd device_state state 4` 并把本页复制到 display 1。
        // 但从入口页点「设置」时本来就已经在外屏，再切一次双屏会触发配置变更把本页顶掉，
        // 用户看到的就是"点设置卡在壁纸"（实测）。
        // 设置页现在就地显示，不碰屏幕状态、不复制自己。
        store = new SettingsStore(this);

        float d = getResources().getDisplayMetrics().density;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        // ---------- 顶部栏 ----------
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        int barH = Math.round(48 * d);
        bar.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, barH));
        bar.setPadding(Math.round(12 * d), 0, Math.round(12 * d), 0);

        TextView back = new TextView(this);
        back.setText("←");
        back.setTextColor(TEXT_MAIN);
        back.setTextSize(22);
        back.setPadding(0, 0, Math.round(14 * d), 0);
        back.setOnClickListener(v -> finish());
        bar.addView(back);

        TextView title = new TextView(this);
        title.setText("FlipMirror 设置");
        title.setTextColor(TEXT_MAIN);
        title.setTextSize(17);
        title.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        bar.addView(title);

        TextView reset = new TextView(this);
        reset.setText("重置");
        reset.setTextColor(ACCENT);
        reset.setTextSize(14);
        reset.setPadding(Math.round(10 * d), Math.round(6 * d), 0, Math.round(6 * d));
        reset.setOnClickListener(v -> confirmReset());
        bar.addView(reset);

        root.addView(bar);

        // ---------- 可滚动内容 ----------
        ScrollView sv = new ScrollView(this);
        sv.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, Math.round(4 * d), 0, Math.round(24 * d));
        sv.addView(content);
        root.addView(sv);

        setContentView(root);

        buildCards();
    }

    // ==================================================================
    //  卡片构建
    // ==================================================================
    private void buildCards() {
        float d = getResources().getDisplayMetrics().density;
        density = d;

        // ---- 卡片 1：镜像参数 ----
        LinearLayout c1 = addCard("镜像参数");
        addText(c1, SettingsStore.K_INNER_W, "内屏宽度", "wm size 的宽（改了点「应用分辨率」生效）", 748);
        addText(c1, SettingsStore.K_INNER_H, "内屏高度", "654 = 720 − 66（顶部控件条）", 654);
        // DPI 用滑块：调小 = 逻辑尺寸变大 = 界面元素变小、一屏显示更多（通知栏不再被裁）
        addSlider(c1, SettingsStore.K_INNER_DPI, "内屏 DPI", "240~480：调小则界面变小、显示更多", 240, 480, 340, "");
        addButton(c1, "应用分辨率", "执行 wm size / wm density（本版未接线）");
        addButton(c1, "恢复原始分辨率", "执行 wm size reset / wm density reset（本版未接线）");
        addChoice(c1, SettingsStore.K_SCALE_MODE, "画面缩放模式", "内容与外屏比例不一致时怎么处理",
                new String[]{"适应", "拉伸", "1:1"});
        addSlider(c1, SettingsStore.K_FPS, "镜像帧率上限", "省电可降到 30", 30, 60, 60, "fps");

        // ---- 卡片 2：显示与布局 ----
        LinearLayout c2 = addCard("控件条");
        addSlider(c2, SettingsStore.K_BTN_SIZE, "按钮直径", "圆钮大小", 24, 56, 32, "dp");
        addSlider(c2, SettingsStore.K_ICON_RATIO, "图标大小", "图标占按钮直径的百分比", 30, 90, 46, "%");
        addSwitch(c2, SettingsStore.K_BTN_BACK, "显示「返回」", null, true);
        addSwitch(c2, SettingsStore.K_BTN_HOME, "显示「主页」", null, true);
        addSwitch(c2, SettingsStore.K_BTN_RECENTS, "显示「多任务」", null, true);
        addSwitch(c2, SettingsStore.K_BTN_COLLAPSE, "显示「收起」", "收起后变成小圆点，点圆点展开", true);
        addSwitch(c2, SettingsStore.K_CLEAN_UI, "纯净模式",
                "不画控件条和圆角遮罩，避免和第三方外屏 UI 冲突", false);
        addSwitch(c2, SettingsStore.K_FLIP_180, "界面上下翻转",
                "整个界面（含控件条）旋转 180°，外屏换个方向用", false);
        addSwitch(c2, SettingsStore.K_CORNER_MASK, "右上角补圆角",
                "外屏左上/左下/右下自带圆弧，只补右上这一个角", true);
        addSlider(c2, SettingsStore.K_BAR_ALPHA, "控件条透明度", "30% ~ 100%（本版未接线）", 30, 100, 96, "%");

        LinearLayout c2b = addCard("已标定 · 勿改");
        addInfoRow(c2b, "内容区起始 Y", "66px（已与正常外屏逐像素对齐）");
        addInfoRow(c2b, "四边圆角半径", "48px（参照正常外屏圆弧）");

        // ---- 卡片 3：触摸与输入 ----
        LinearLayout c3 = addCard("触摸与输入");
        addSwitch(c3, SettingsStore.K_TOUCH_ENABLE, "启用触摸注入", "点外屏 = 点内屏（走 Shizuku）", true);
        addSlider(c3, SettingsStore.K_TOUCH_SCALE, "触摸坐标缩放", "注入点整体缩放，修正偏移用", 80, 120, 100, "%");
        addSlider(c3, SettingsStore.K_TAP_DELAY, "点击延迟", "部分 App 需要延迟才认", 0, 200, 0, "ms");

        // ---- 卡片 4：行为 ----
        LinearLayout c4 = addCard("行为");
        addSwitch(c4, SettingsStore.K_AUTO_START, "开机自动启动", "需要授予自启权限", false);
        addSwitch(c4, SettingsStore.K_KEEP_AWAKE, "镜像时保持屏幕常亮", "防止外屏息屏", true);
        addSwitch(c4, SettingsStore.K_EXIT_LONGPRESS, "长按控件条退出镜像", "兜底退出方式", true);

        // ---- 卡片 5：高级 / 调试 ----
        LinearLayout c5 = addCard("高级 / 调试");
        addSwitch(c5, SettingsStore.K_DEBUG_LOG, "详细日志", "输出到 logcat（tag: FlipMirror）", false);
        addButton(c5, "导出当前配置", "把全部设置以文本形式显示（本版未接线）");

        // ---- 底部信息 ----
        TextView info = new TextView(this);
        info.setText(deviceInfo());
        info.setTextColor(TEXT_SUB);
        info.setTextSize(11);
        info.setPadding(Math.round(20 * d), Math.round(16 * d), Math.round(20 * d), 0);
        content.addView(info);
    }

    /** 设备实际尺寸（只读）。 */
    private String deviceInfo() {
        StringBuilder sb = new StringBuilder();
        sb.append("FlipMirror 0.1.0\n");
        try {
            android.hardware.display.DisplayManager dm =
                    (android.hardware.display.DisplayManager) getSystemService(DISPLAY_SERVICE);
            for (int i = 0; i < 2; i++) {
                android.view.Display dd = dm == null ? null : dm.getDisplay(i);
                if (dd == null) continue;
                android.util.DisplayMetrics m = new android.util.DisplayMetrics();
                dd.getRealMetrics(m);
                sb.append(i == 0 ? "内屏 " : "外屏 ")
                        .append(i).append(": ")
                        .append(m.widthPixels).append("×").append(m.heightPixels)
                        .append(" @").append(m.densityDpi).append("dpi\n");
            }
        } catch (Throwable ignored) { }
        return sb.toString().trim();
    }

    // ==================================================================
    //  行控件
    // ==================================================================
    private LinearLayout addCard(String sectionTitle) {
        float d = density;

        TextView st = new TextView(this);
        st.setText(sectionTitle);
        st.setTextColor(TEXT_SUB);
        st.setTextSize(13);
        st.setPadding(Math.round(20 * d), Math.round(18 * d), 0, Math.round(8 * d));
        content.addView(st);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(CARD);
        bg.setCornerRadius(20 * d);
        card.setBackground(bg);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(Math.round(12 * d), 0, Math.round(12 * d), Math.round(4 * d));
        card.setLayoutParams(lp);
        content.addView(card);
        return card;
    }

    private void addDivider(LinearLayout card) {
        View v = new View(this);
        v.setBackgroundColor(DIVIDER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, Math.round(density)));
        lp.setMargins(Math.round(16 * density), 0, 0, 0);
        v.setLayoutParams(lp);
        card.addView(v);
    }

    /** 一行：左侧标题+副标题，右侧控件。 */
    private LinearLayout addRow(LinearLayout card, String title, String sub) {
        float d = density;
        if (card.getChildCount() > 0) addDivider(card);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Math.round(16 * d), Math.round(10 * d), Math.round(16 * d), Math.round(10 * d));

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(TEXT_MAIN);
        t.setTextSize(15);
        left.addView(t);

        if (sub != null && !sub.isEmpty()) {
            TextView s = new TextView(this);
            s.setText(sub);
            s.setTextColor(TEXT_SUB);
            s.setTextSize(11);
            s.setPadding(0, Math.round(2 * d), 0, 0);
            left.addView(s);
        }
        row.addView(left);
        // 行必须占满卡片宽度：默认 wrap_content 时行宽=子项之和，
        // 右侧开关会被挤到卡片外面（截图里只剩一条"关"字边），点也点不到。
        card.addView(row, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        return row;
    }

    private void addSwitch(LinearLayout card, final String key, String title, String sub, boolean def) {
        LinearLayout row = addRow(card, title, sub);

        // 不用系统 Switch：它在小屏 + weight 布局里会被压成一条 8px 竖线，
        // 截图实测完全看不见（等于没做开关）。
        // 改成自绘开关：固定像素宽高 + 纯文字「开/关」，不依赖任何主题度量。
        final TextView sw = new TextView(this);
        final int w = Math.round(56 * density);
        final int h = Math.round(32 * density);
        sw.setLayoutParams(new LinearLayout.LayoutParams(w, h));
        sw.setGravity(android.view.Gravity.CENTER);
        sw.setTextSize(13);
        sw.setTextColor(0xFFFFFFFF);
        final Runnable paint = () -> {
            boolean on = store.getBool(key, def);
            sw.setText(on ? "开" : "关");
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setCornerRadius(16 * density);
            bg.setColor(on ? 0xFF2FA84F : 0xFF4A4F57);
            sw.setBackground(bg);
        };
        paint.run();
        sw.setOnClickListener(v -> {
            store.setBool(key, !store.getBool(key, def));
            paint.run();
        });
        row.addView(sw);

        // 整行也可点
        row.setClickable(true);
        row.setOnClickListener(v -> {
            store.setBool(key, !store.getBool(key, def));
            paint.run();
        });
    }

    private void addSlider(LinearLayout card, final String key, String title, String sub,
                           final int min, int max, int def, final String unit) {
        LinearLayout row = addRow(card, title, sub);
        final TextView val = new TextView(this);
        val.setText(store.getInt(key, def) + unit);
        val.setTextColor(ACCENT);
        val.setTextSize(13);
        row.addView(val);

        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(Math.round(16 * density), 0, Math.round(16 * density), Math.round(10 * density));
        card.addView(wrap);

        SeekBar sb = new SeekBar(this);
        sb.setMax(max - min);
        sb.setProgress(Math.max(0, Math.min(max - min, store.getInt(key, def) - min)));
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                int v = min + p;
                val.setText(v + unit);
                if (fromUser) store.setInt(key, v);
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
        wrap.addView(sb);
    }

    private void addText(final LinearLayout card, final String key, String title, String sub, int def) {
        LinearLayout row = addRow(card, title, sub);
        final TextView val = new TextView(this);
        val.setText(String.valueOf(store.getInt(key, def)));
        val.setTextColor(ACCENT);
        val.setTextSize(15);
        val.setPadding(Math.round(8 * density), 0, 0, 0);
        row.addView(val);
        row.setOnClickListener(v -> {
            final EditText et = new EditText(this);
            et.setInputType(InputType.TYPE_CLASS_NUMBER);
            et.setText(String.valueOf(store.getInt(key, def)));
            new AlertDialog.Builder(this)
                    .setTitle(title)
                    .setView(et)
                    .setPositiveButton("确定", (d1, w) -> {
                        try {
                            int parsed = Integer.parseInt(et.getText().toString().trim());
                            store.setInt(key, parsed);
                            val.setText(String.valueOf(parsed));
                        } catch (Exception ignored) { }
                    })
                    .setNegativeButton("取消", null)
                    .show();
        });
    }

    private void addChoice(final LinearLayout card, final String key, String title, String sub,
                           final String[] options) {
        LinearLayout row = addRow(card, title, sub);
        final TextView val = new TextView(this);
        val.setText(store.getStr(key, options[0]));
        val.setTextColor(ACCENT);
        val.setTextSize(14);
        row.addView(val);
        row.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle(title)
                .setItems(options, (d1, which) -> {
                    store.setStr(key, options[which]);
                    val.setText(options[which]);
                })
                .show());
    }

    /** 只读信息行（用来标注"已标定、勿改"的项）。 */
    private void addInfoRow(LinearLayout card, String title, String value) {
        LinearLayout row = addRow(card, title, null);
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextColor(TEXT_SUB);
        v.setTextSize(12);
        row.addView(v);
    }

    private void addButton(LinearLayout card, String title, String sub) {
        LinearLayout row = addRow(card, title, sub);
        TextView go = new TextView(this);
        go.setText("执行");
        go.setTextColor(ACCENT);
        go.setTextSize(14);
        row.addView(go);
        row.setOnClickListener(v ->
                Toast.makeText(this, "本版只保存设置，功能尚未接线", Toast.LENGTH_SHORT).show());
    }

    private void confirmReset() {
        new AlertDialog.Builder(this)
                .setTitle("重置所有设置？")
                .setPositiveButton("重置", (d, w) -> {
                    store.reset();
                    recreate();
                })
                .setNegativeButton("取消", null)
                .show();
    }
}
