package com.flip.mirror;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 外屏「受保护内容 · 搬到外屏看」提示条（D 方案，卡 261118163954-607d）。
 *
 * <h3>为什么自绘而不是用系统控件</h3>
 * 提示条要挂在外屏（display 1）的全屏悬浮窗里，和控件条同一个根容器。
 * 那个根容器用的是**物理像素**几何（控件条 66px、圆角写死等），所以这里所有尺寸
 * 也一律用 px，不参与 density 换算 —— 换 DPI 不会让提示条漂走。
 *
 * <p>纯代码构建，不依赖 XML / drawable 资源（与 {@link ControlBar} 同口径）。</p>
 */
public class SecurePromptBar extends FrameLayout {

    /** 提示条按钮回调。 */
    public interface Listener {
        /** 「搬过去」：把当前页面原生起到外屏。 */
        void onMove();

        /** 「别烦我」：同一页面 30 分钟内不再提示。 */
        void onDismiss();
    }

    private static final int COLOR_BG = 0xCC000000;
    private static final int COLOR_TITLE = 0xFFFFFFFF;
    private static final int COLOR_PRIMARY = 0xFF2E6BE6;
    private static final int COLOR_SECONDARY_BG = 0x33FFFFFF;
    private static final int COLOR_SECONDARY_TEXT = 0xFFDDDDDD;

    /** 提示条左右内边距（px）。 */
    private static final int PAD_L = 14;
    private static final int PAD_R = 10;
    /** 按钮之间的间距（px）。 */
    private static final int BTN_GAP = 10;
    /** 按钮高度（px）：提示条 56px 内留 8px 上下呼吸。 */
    private static final int BTN_H = 40;
    /** 按钮左右内边距（px）。 */
    private static final int BTN_PAD_H = 14;

    public SecurePromptBar(Context c, Listener l) {
        super(c);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(COLOR_BG);
        bg.setCornerRadius(8f);
        setBackground(bg);
        // 空白处的触摸自己吃掉，避免穿透到下面的镜像层被当成手势注入内屏
        setClickable(true);

        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(PAD_L, 0, PAD_R, 0);
        addView(row, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        TextView title = new TextView(c);
        title.setText("受保护内容 · 搬到外屏看");
        title.setTextColor(COLOR_TITLE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, 24f);
        title.setSingleLine(true);
        title.setIncludeFontPadding(false);
        row.addView(title, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView move = button(c, "搬过去", COLOR_PRIMARY, COLOR_TITLE, 0);
        move.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                l.onMove();
            }
        });
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, BTN_H);
        mlp.leftMargin = BTN_GAP;
        row.addView(move, mlp);

        TextView mute = button(c, "别烦我", COLOR_SECONDARY_BG, COLOR_SECONDARY_TEXT, 0);
        mute.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                l.onDismiss();
            }
        });
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, BTN_H);
        dlp.leftMargin = BTN_GAP;
        row.addView(mute, dlp);
    }

    /** 生成一个圆角按钮（不用 drawable 资源，直接用 GradientDrawable）。 */
    private static TextView button(Context c, String text, int bgColor, int textColor, int strokePx) {
        TextView b = new TextView(c);
        b.setText(text);
        b.setTextColor(textColor);
        b.setTextSize(TypedValue.COMPLEX_UNIT_PX, 22f);
        b.setGravity(Gravity.CENTER);
        b.setSingleLine(true);
        b.setIncludeFontPadding(false);
        b.setPadding(BTN_PAD_H, 0, BTN_PAD_H, 0);
        GradientDrawable d = new GradientDrawable();
        d.setColor(bgColor);
        d.setCornerRadius(6f);
        if (strokePx > 0) {
            d.setStroke(1, 0x66FFFFFF);
        }
        b.setBackground(d);
        b.setClickable(true);
        return b;
    }
}
