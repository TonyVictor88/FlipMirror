package com.flip.mirror;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 设置存储（第 1 步：只存值，不生效）。
 *
 * 设计原则：
 *  - 所有键集中在这里，Activity 只按 key 读写，避免散落字符串
 *  - 每个 getter 自带默认值，读不到就是默认
 *  - 后续"接功能"时，各模块从这里取值即可，不需要再改界面
 */
public class SettingsStore {

    private static final String PREF = "flipmirror_settings";

    public static final String K_INNER_W      = "inner_w";
    public static final String K_INNER_H      = "inner_h";
    public static final String K_INNER_DPI    = "inner_dpi";
    public static final String K_SCALE_MODE   = "scale_mode";
    public static final String K_FPS          = "fps";

    public static final String K_BAR_POS      = "bar_pos";
    public static final String K_BAR_H        = "bar_h";
    public static final String K_CONTENT_TOP  = "content_top";
    public static final String K_BAR_ALPHA    = "bar_alpha";
    public static final String K_BTN_SIZE     = "btn_size";
    public static final String K_CORNER_R     = "corner_r";
    /** 是否给外屏右上角补圆角（左上/左下/右下物理屏自带，无需处理）。 */
    public static final String K_CORNER_MASK  = "corner_mask_on";
    public static final String K_ICON_RATIO   = "icon_ratio";   // 图标占按钮直径的百分比
    public static final String K_BTN_BACK     = "btn_back";
    public static final String K_BTN_HOME     = "btn_home";
    public static final String K_BTN_RECENTS  = "btn_recents";
    public static final String K_BTN_COLLAPSE = "btn_collapse";
    public static final String K_SHOW_SPEED   = "show_speed";

    public static final String K_TOUCH_ENABLE = "touch_enable";
    public static final String K_TOUCH_SCALE  = "touch_scale";
    public static final String K_TAP_DELAY    = "tap_delay";

    public static final String K_AUTO_START   = "auto_start";
    public static final String K_KEEP_AWAKE   = "keep_awake";
    public static final String K_EXIT_LONGPRESS = "exit_longpress";

    public static final String K_DEBUG_LOG    = "debug_log";

    private final SharedPreferences sp;

    public SettingsStore(Context c) {
        sp = c.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    // ---------- 通用 ----------
    public int getInt(String key, int def)      { return sp.getInt(key, def); }
    public void setInt(String key, int v)       { sp.edit().putInt(key, v).apply(); }
    public boolean getBool(String key, boolean def) { return sp.getBoolean(key, def); }
    public void setBool(String key, boolean v)  { sp.edit().putBoolean(key, v).apply(); }
    public String getStr(String key, String def){ return sp.getString(key, def); }
    public void setStr(String key, String v)    { sp.edit().putString(key, v).apply(); }
    public void reset()                          { sp.edit().clear().apply(); }

    // ---------- 镜像参数 ----------
    // 🔴 1080x944 不是随便定的（2026-11-12 实测）：
    //    物理面板 1080x1920，wm size 的宽 = 1080 ⇒ 系统缩放因子 min(1080/1080, 1920/944) = 1.0，
    //    也就是「1:1 渲染 + 上下 letterbox」，不做整屏缩放。
    //    而比例 1080/944 = 1.144 ≈ 外屏内容区 748/654 = 1.144，镜像时不裁不糊。
    //    旧值 1600x1398 比例一样，但宽度 1600 > 物理 1080 ⇒ 系统整屏缩放 0.675，
    //    实测该缩放下**横向滑动在屏幕上半区失效**（左右滑不动桌面的根因）。
    public int innerWidth()   { return getInt(K_INNER_W, 1080); }
    public int innerHeight()  { return getInt(K_INNER_H, 944); }
    // 🔴 324 是为了「观感等同于旧的 1600×1398@480」而算出来的，不是随手取的：
    //    dp 宽度 = 逻辑宽 ÷ (dpi/160)。旧配置 1600 ÷ 3 = 533dp；新配置 1080 ÷ (324/160) = 533dp。
    //    两者布局完全一致（状态栏不挤、网格同列数），但新配置是 1:1 渲染、无系统缩放。
    public int innerDpi()     { return getInt(K_INNER_DPI, 324); }
    public String scaleMode() { return getStr(K_SCALE_MODE, "适应"); }
    public int fps()          { return getInt(K_FPS, 60); }

    // ---------- 显示与布局 ----------
    public String barPosition() { return getStr(K_BAR_POS, "顶部"); }
    public int barHeight()      { return getInt(K_BAR_H, 66); }
    public int contentTop()     { return getInt(K_CONTENT_TOP, 66); }
    public int barAlpha()       { return getInt(K_BAR_ALPHA, 96); }
    public int buttonSizeDp()   { return getInt(K_BTN_SIZE, 32); }
    /** 圆角半径（物理像素，基准 748 宽） */
    /** 右上角圆角开关（默认开）。 */
    /** 纯净模式：不画控件条、不画圆角遮罩（避免和第三方外屏 UI 打架）。 */
    public static final String K_CLEAN_UI = "clean_ui";

    /** 界面上下翻转（整个画面 + 控件条一起转 180°）。 */
    public static final String K_FLIP_180 = "flip180";

    public boolean cornerMaskOn() { return getBool(K_CORNER_MASK, true); }

    public int cornerRadius()   { return getInt(K_CORNER_R, 48); }
    /** 图标占按钮直径的百分比（30~90） */
    public int iconRatioPct()   { return getInt(K_ICON_RATIO, 46); }
    public boolean showBack()    { return getBool(K_BTN_BACK, true); }
    public boolean showHome()    { return getBool(K_BTN_HOME, true); }
    public boolean showRecents() { return getBool(K_BTN_RECENTS, true); }
    public boolean showCollapse(){ return getBool(K_BTN_COLLAPSE, true); }
    public boolean showSpeed()   { return getBool(K_SHOW_SPEED, true); }

    // ---------- 触摸与输入 ----------
    public boolean flip180() { return getBool(K_FLIP_180, false); }
    public boolean cleanUi() { return getBool(K_CLEAN_UI, false); }

    public boolean touchEnabled() { return getBool(K_TOUCH_ENABLE, true); }
    public int touchScalePct()     { return getInt(K_TOUCH_SCALE, 100); }
    public int tapDelayMs()        { return getInt(K_TAP_DELAY, 0); }

    // ---------- 行为 ----------
    public boolean autoStart()     { return getBool(K_AUTO_START, false); }
    public boolean keepAwake()     { return getBool(K_KEEP_AWAKE, true); }
    public boolean longPressExit() { return getBool(K_EXIT_LONGPRESS, true); }

    // ---------- 高级 ----------
    public boolean debugLog()      { return getBool(K_DEBUG_LOG, false); }
}
