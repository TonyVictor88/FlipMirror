package com.flip.mirror;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

/**
 * 外屏悬浮控件条（One UI 风格）。
 *
 * <p>外屏 748x720 没有系统导航键，这条控件条自绘「返回 / 主页 / 多任务 / 收起」四个按钮，
 * 贴在底部右侧，半透明黑底 + 白色线性图标，尽量不遮挡镜像内容。</p>
 *
 * <p>设计要点：
 * <ul>
 *   <li>纯 Java 构建 View，不依赖任何 XML 资源 / androidx / drawable 资源文件；</li>
 *   <li>每个按钮 56dp 正圆（GradientDrawable 圆角 = 边长一半 = 28dp），
 *       常态 #66000000，按下 #AA000000；</li>
 *   <li>图标全部用 Canvas + Path 在 24x24 坐标系内绘制，随控件尺寸等比缩放；</li>
 *   <li>点「收起」整条在 200ms 内收成一个 24dp 小圆点（ValueAnimator 过渡宽度 +
 *       按钮行/圆点交叉淡入淡出），点小圆点再展开。</li>
 * </ul></p>
 */
public class ControlBar extends FrameLayout {

    /* ---------------------------------------------------------------- 回调 */

    /** 控件条事件回调。宿主（Activity / 视图层）实现它来真正执行返回、主页等动作。 */
    public interface Listener {
        /** 点击「返回」。 */
        void onBack();

        /** 点击「主页」。 */
        void onHome();

        /** 点击「多任务」。 */
        void onRecents();

        /** 控件条已开始收起（同时播放收缩动画）。 */
        void onCollapse();
        void onExit();

        /** 控件条已开始展开（同时播放展开动画）。 */
        void onExpand();
    }

    /* ------------------------------------------------------------ 常量参数 */

    /** 按钮常态背景：40% 黑。 */
    private static final int COLOR_BTN_NORMAL = 0x66000000;
    /** 按钮按下背景：67% 黑。 */
    private static final int COLOR_BTN_PRESSED = 0xAA000000;
    /** 图标颜色。 */
    private static final int COLOR_ICON = 0xFFFFFFFF;

    /** 收起 / 展开动画时长（毫秒）。 */
    private static final int ANIM_DURATION = 200;

    /** 按钮直径 56dp。 */
    private static final int BTN_DP = 32;   // 缩小以适配顶部状态栏高度
    /** 收起后的小圆点直径 24dp。 */
    private static final int DOT_DP = 24;
    /** 按钮之间的间距 8dp。 */
    private static final int GAP_DP = 8;
    /** 控件条内边距 6dp。 */
    private static final int PAD_DP = 6;
    /** 控件条贴边外边距 12dp。 */
    private static final int MARGIN_DP = 12;
    /** 底部安全区 8dp（叠加在外边距之上）。 */
    private static final int SAFE_BOTTOM_DP = 8;

    /** 控件条整体默认透明度（半透明，不抢内容）。 */
    private static final float BAR_ALPHA = 0.96f;

    /* ------------------------------------------------------------ 图标类型 */

    private static final int ICON_BACK = 0;
    private static final int ICON_HOME = 1;
    private static final int ICON_RECENTS = 2;
    private static final int ICON_COLLAPSE = 3;
    private static final int ICON_DOT = 4;

    /* ---------------------------------------------------------------- 状态 */

    private Listener mListener;

    /** 四个按钮所在的横向容器。 */
    private LinearLayout mRow;
    /** 收起后显示的 24dp 小圆点（展开把手）。 */
    private IconView mDot;

    private boolean mCollapsed = false;
    /** 第 4 个按钮的含义：false=收起成本条（旧行为），true=直接退出（悬浮层用） */
    private boolean mCollapseButtonAsExit = false;
    private ValueAnimator mAnimator;

    /** 尺寸（px），构造时按 density 换算好，动画直接走整数像素。 */
    private final int mBtnSize;
    private final int mGap;
    private final int mPad;
    private final int mDotSize;
    private int mExpandedW;   // attach 后按实际内容重算，故非 final
    private final int mExpandedH;
    private final int mCollapsedW;

    /** 图标占按钮直径比例（0~1），由设置决定。 */
    private final float mIconRatio;
    /** 是否显示网速。 */
    private final boolean mShowSpeed;
    private final SettingsStore mStore;
    private android.widget.TextView mSpeedView;
    private android.os.Handler mSpeedHandler;
    private long mLastRx, mLastTx, mLastTick;

    /** 尚未 attach 时暂存的宽度，attach 后补应用。 */
    private int mPendingW = -1;

    /* ------------------------------------------------------------ 构造入口 */

    public ControlBar(Context context) {
        super(context);

        float d = getResources().getDisplayMetrics().density;
        // 按钮直径 / 图标比例 / 显示哪些按钮 / 是否显示网速 —— 全部来自设置
        mStore = new SettingsStore(context);
        mBtnSize = Math.round(mStore.buttonSizeDp() * d);
        mIconRatio = Math.max(0.30f, Math.min(0.90f, mStore.iconRatioPct() / 100f));
        // 网速改由 MirrorActivity 画在顶部状态栏区（黑带左侧），控件条只负责按钮
        mShowSpeed = false;
        mGap = Math.round(GAP_DP * d);
        mPad = Math.round(PAD_DP * d);
        mDotSize = Math.round(DOT_DP * d);
        // 展开宽度先给估值，attach 后按实际测量重算（网速文字宽度是动态的）
        mExpandedW = mPad * 2 + mBtnSize * 4 + mGap * 3;
        mExpandedH = mPad * 2 + mBtnSize;
        // 收起宽度 = 左右内边距 + 一个小圆点（高度保持不变，圆点在中间）
        mCollapsedW = mPad * 2 + mDotSize;

        setAlpha(BAR_ALPHA);
        setClipChildren(true);   // 收缩时把溢出的按钮裁掉
        setClipToPadding(true);

        buildRow(context);
        buildDot(context);
        applyDefaultPosition();  // 先定位置，再定尺寸
        applyWidth(mExpandedW);
    }

    /* ------------------------------------------------------------ 公开 API */

    /** 设置事件回调（可传 null 解除）。 */
    public void setListener(Listener l) {
        mListener = l;
    }

    /** 把第 4 个按钮改成「退出」语义（悬浮层用：外屏上没有其它退出入口）。 */
    public void setCollapseButtonAsExit(boolean asExit) {
        mCollapseButtonAsExit = asExit;
    }

    /** 当前是否处于收起状态。 */
    public boolean isCollapsed() {
        return mCollapsed;
    }

    /** 收起 / 展开切换。 */
    public void toggle() {
        if (mCollapsed) {
            expand();
        } else {
            collapse();
        }
    }

    /** 收起成小圆点（带 200ms 动画）。 */
    public void collapse() {
        if (mCollapsed) {
            return;
        }
        int fromW = currentWidth();    // 支持动画中途反向：从当前实际宽度起步
        mCollapsed = true;
        mDot.setVisibility(VISIBLE);   // 先可见，靠 alpha 交叉淡入
        if (isAttachedToWindow()) {
            animateWidth(fromW, mCollapsedW, true);
        } else {
            applyCollapsedState(true);
        }
        if (mListener != null) {
            mListener.onCollapse();
        }
    }

    /** 从 24dp 小圆点展开成整条。 */
    public void expand() {
        if (!mCollapsed) {
            return;
        }
        int fromW = currentWidth();
        mCollapsed = false;
        mRow.setVisibility(VISIBLE);   // 先可见，靠 alpha 交叉淡入
        if (isAttachedToWindow()) {
            animateWidth(fromW, mExpandedW, false);
        } else {
            applyCollapsedState(false);
        }
        if (mListener != null) {
            mListener.onExpand();
        }
    }

    /** 设置整条透明度（0.3~1.0），用于「不遮挡内容」的轻量调节。 */
    public void setBarAlpha(float alpha) {
        setAlpha(Math.max(0.3f, Math.min(1f, alpha)));
    }

    /**
     * 便捷挂载：新建一条控件条并按「底部右侧 + 安全区」摆好后加进父容器。
     *
     * @param parent 通常是 Activity 的 content 根（FrameLayout / ContentFrameLayout）
     * @return 已挂载的控件条
     */
    public static ControlBar attach(FrameLayout parent) {
        ControlBar bar = new ControlBar(parent.getContext());
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(bar.mExpandedW, bar.mExpandedH);
        lp.gravity = Gravity.TOP | Gravity.END;
        int m = bar.dp(MARGIN_DP);
        lp.setMargins(m, m, m, m + bar.dp(SAFE_BOTTOM_DP));
        parent.addView(bar, lp);
        return bar;
    }

    /* ------------------------------------------------------------ 内部构建 */

    /** 构建 4 个按钮的横向容器。 */
    private void buildRow(Context context) {
        mRow = new LinearLayout(context);
        mRow.setOrientation(LinearLayout.HORIZONTAL);
        mRow.setGravity(Gravity.CENTER_VERTICAL);

        // 网速显示（最左边）
        if (mShowSpeed) {
            mSpeedView = new android.widget.TextView(context);
            mSpeedView.setTextSize(9);
            mSpeedView.setTextColor(0xCCFFFFFF);
            mSpeedView.setSingleLine(true);
            mSpeedView.setText("0 B/s");
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            slp.rightMargin = mGap;
            mRow.addView(mSpeedView, slp);
        }

        addButton(context, ICON_BACK, "返回", 0, new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mListener != null) {
                    mListener.onBack();
                }
            }
        });
        if (!mStore.showBack()) { mRow.getChildAt(mRow.getChildCount() - 1).setVisibility(GONE); }
        addButton(context, ICON_HOME, "主页", mGap, new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mListener != null) {
                    mListener.onHome();
                }
            }
        });
        if (!mStore.showHome()) { mRow.getChildAt(mRow.getChildCount() - 1).setVisibility(GONE); }
        addButton(context, ICON_RECENTS, "多任务", mGap, new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mListener != null) {
                    mListener.onRecents();
                }
            }
        });
        if (!mStore.showRecents()) { mRow.getChildAt(mRow.getChildCount() - 1).setVisibility(GONE); }
        addButton(context, ICON_COLLAPSE, "退出镜像", mGap, new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mCollapseButtonAsExit) {
                    if (mListener != null) {
                        mListener.onExit();
                    }
                } else {
                    collapse();
                }
            }
        });
        if (!mStore.showCollapse()) { mRow.getChildAt(mRow.getChildCount() - 1).setVisibility(GONE); }

        // 左对齐（不是 END）：宽度收窄时配 translationX 让按钮行原地不动，
        // 视觉上从左侧被「吃掉」，最后只剩右侧的收起把手 —— 符合收进角落的直觉。
        // 宽度交给内容决定：按钮有无显隐 + 网速文字宽度都是动态的，
        // 写死估值会把「收起」按钮挤出可视区（实测踩过）。
        FrameLayout.LayoutParams rowLp =
                new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT, mBtnSize);
        rowLp.gravity = Gravity.START | Gravity.CENTER_VERTICAL;
        rowLp.leftMargin = mPad;
        addView(mRow, rowLp);
    }

    /** 往横向容器里加一个圆形按钮。 */
    private void addButton(Context context, int iconType, String desc,
                           int startMargin, OnClickListener click) {
        IconView btn = new IconView(context, iconType, mBtnSize, mIconRatio);
        btn.setContentDescription(desc);
        btn.setOnClickListener(click);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(mBtnSize, mBtnSize);
        lp.leftMargin = startMargin;
        mRow.addView(btn, lp);
    }

    /** 构建收起后的小圆点（点击展开）。 */
    private void buildDot(Context context) {
        mDot = new IconView(context, ICON_DOT, mDotSize, 0.50f);
        mDot.setContentDescription("展开控件条");
        mDot.setAlpha(0f);
        mDot.setVisibility(GONE);
        mDot.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                expand();
            }
        });

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(mDotSize, mDotSize);
        lp.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
        lp.rightMargin = mPad;
        addView(mDot, lp);
    }

    /**
     * 作为「外屏顶部悬浮层」使用时调用：清掉自带的四周边距。
     *
     * 默认布局是给镜像页内嵌用的（贴底 + 安全区），带 MARGIN_DP 边距。
     * 一旦放进 {@code TYPE_APPLICATION_OVERLAY} 的 wrap_content 窗口，
     * 这些边距会叠加进窗口尺寸，导致控件条的深色底板向下溢出几像素、压到画面内容区。
     */
    /** 翻转模式下：只把「返回」箭头方向反过来，其它一律不动。 */
    public void setBackArrowMirror(boolean mirror) {
        if (mRow == null) {
            return;
        }
        for (int i = 0; i < mRow.getChildCount(); i++) {
            android.view.View v = mRow.getChildAt(i);
            if (v instanceof IconView) {
                ((IconView) v).setMirrorBack(mirror);
            }
        }
    }

    public void useAsOverlay() {
        android.view.ViewGroup.LayoutParams lp = getLayoutParams();
        if (lp instanceof FrameLayout.LayoutParams) {
            ((FrameLayout.LayoutParams) lp).setMargins(0, 0, 0, 0);
            setLayoutParams(lp);
        }
    }

    /** 默认位置：贴底 + 贴右（END），并留出底部安全区。 */
    private void applyDefaultPosition() {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(mExpandedW, mExpandedH);
        lp.gravity = Gravity.TOP | Gravity.END;
        int m = dp(MARGIN_DP);
        lp.setMargins(m, m, m, m + dp(SAFE_BOTTOM_DP));
        setLayoutParams(lp);
    }

    /* ------------------------------------------------------------ 收缩动画 */

    /**
     * 宽度过渡（200ms）+ 按钮行 / 小圆点交叉淡入淡出。
     *
     * <p>高度保持不变：圆点垂直居中，正好落在「收起」按钮原来的位置上，
     * 所以只做宽度过渡就够了，也不会出现纵向裁切。</p>
     *
     * @param fromW     起始宽（px）
     * @param toW       目标宽（px）
     * @param collapsed 目标是否为收起态
     */
    private void animateWidth(int fromW, int toW, final boolean collapsed) {
        cancelAnim();

        // 取当前实际 alpha 做起点，支持动画中途反向（连点收起 / 展开不会跳变）
        final float rowFrom = mRow.getAlpha();
        final float rowTo = collapsed ? 0f : 1f;
        final float dotFrom = mDot.getAlpha();
        final float dotTo = collapsed ? 1f : 0f;

        final ValueAnimator anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(ANIM_DURATION);
        // 减速插值：先快后慢，接近 One UI 的手感
        anim.setInterpolator(new DecelerateInterpolator());
        anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator a) {
                float t = (float) a.getAnimatedValue();
                applyWidth(Math.round(fromW + (toW - fromW) * t));
                mRow.setAlpha(rowFrom + (rowTo - rowFrom) * t);
                mDot.setAlpha(dotFrom + (dotTo - dotFrom) * t);
            }
        });
        anim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator a) {
                // 被新动画 cancel 掉时 mAnimator 已经换人 → 直接退出，避免状态错乱
                if (mAnimator != a) {
                    return;
                }
                mAnimator = null;
                applyCollapsedState(collapsed);
            }
        });
        mAnimator = anim;
        anim.start();
    }

    /** 当前实际宽度（px）；还没布局时退回展开宽度。 */
    private int currentWidth() {
        ViewGroup.LayoutParams lp = getLayoutParams();
        if (lp != null && lp.width > 0) {
            return lp.width;
        }
        return mExpandedW;
    }

    /** 无动画地落到某个状态（首次 attach 前 / 动画结束时的收尾）。 */
    private void applyCollapsedState(boolean collapsed) {
        applyWidth(collapsed ? mCollapsedW : mExpandedW);
        mRow.setAlpha(collapsed ? 0f : 1f);
        mDot.setAlpha(collapsed ? 1f : 0f);
        mRow.setVisibility(collapsed ? GONE : VISIBLE);
        mDot.setVisibility(collapsed ? VISIBLE : GONE);
    }

    private void cancelAnim() {
        if (mAnimator != null) {
            ValueAnimator a = mAnimator;
            mAnimator = null;   // 先摘引用，旧动画的 onAnimationEnd 就会直接 return
            a.cancel();
        }
    }

    /**
     * 应用宽度：改自身 LayoutParams 宽度 + 同步按钮行的 translationX。
     *
     * <p>控件条贴着屏幕右边，宽度收窄时左边界右移；按钮行要保持绝对位置不变
     * （否则会跟着一起跑），所以用 translationX = 当前宽 - 展开宽 抵消掉。</p>
     */
    private void applyWidth(int w) {
        ViewGroup.LayoutParams lp = getLayoutParams();
        if (lp == null) {
            mPendingW = w;
            mRow.setTranslationX(w - mExpandedW);
            return;
        }
        if (lp.width != w) {
            lp.width = w;
            requestLayout();
        }
        mRow.setTranslationX(w - mExpandedW);
    }

    /* ---------------------------------------------------------- 生命周期 */

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        // 按实际内容重算展开宽度（网速文字宽度动态，不能用估值）
        post(new Runnable() {
            @Override
            public void run() {
                mRow.measure(
                        android.view.View.MeasureSpec.makeMeasureSpec(0,
                                android.view.View.MeasureSpec.UNSPECIFIED),
                        android.view.View.MeasureSpec.makeMeasureSpec(mBtnSize,
                                android.view.View.MeasureSpec.EXACTLY));
                int w = mRow.getMeasuredWidth() + mPad * 2;
                if (w > 0 && w != mExpandedW) {
                    mExpandedW = w;
                }
                if (!mCollapsed) {
                    applyWidth(mExpandedW);
                }
            }
        });
        if (mPendingW > 0) {
            int w = mPendingW;
            mPendingW = -1;
            applyWidth(w);
        }
        startSpeedUpdates();
    }

    /** 每秒刷新一次网速（TrafficStats 全机计数，不需要权限）。 */
    private void startSpeedUpdates() {
        if (!mShowSpeed || mSpeedView == null) {
            return;
        }
        mLastRx = Math.max(0, android.net.TrafficStats.getTotalRxBytes());
        mLastTx = Math.max(0, android.net.TrafficStats.getTotalTxBytes());
        mLastTick = System.currentTimeMillis();
        if (mSpeedHandler == null) {
            mSpeedHandler = new android.os.Handler(android.os.Looper.getMainLooper());
        }
        mSpeedHandler.removeCallbacks(mSpeedTick);
        mSpeedHandler.postDelayed(mSpeedTick, 1000);
    }

    private final Runnable mSpeedTick = new Runnable() {
        @Override
        public void run() {
            if (mSpeedView == null) {
                return;
            }
            long rx = android.net.TrafficStats.getTotalRxBytes();
            long tx = android.net.TrafficStats.getTotalTxBytes();
            if (rx < 0 || tx < 0) {
                // 部分设备/内核不提供 TrafficStats 计数器
                mSpeedView.setText("--");
                if (mSpeedHandler != null) {
                    mSpeedHandler.postDelayed(this, 2000);
                }
                return;
            }
            long now = System.currentTimeMillis();
            long dt = Math.max(1, now - mLastTick);
            double rxB = (rx - mLastRx) * 1000.0 / dt;
            double txB = (tx - mLastTx) * 1000.0 / dt;
            mLastRx = rx;
            mLastTx = tx;
            mLastTick = now;
            mSpeedView.setText(fmt(rxB) + " / " + fmt(txB));
            if (mSpeedHandler != null) {
                mSpeedHandler.postDelayed(this, 1000);
            }
        }
    };

    private static String fmt(double bps) {
        if (bps < 1024) {
            return String.format(java.util.Locale.US, "%.0fB", bps);
        }
        if (bps < 1024 * 1024) {
            return String.format(java.util.Locale.US, "%.1fK", bps / 1024);
        }
        return String.format(java.util.Locale.US, "%.1fM", bps / (1024 * 1024));
    }

    @Override
    protected void onDetachedFromWindow() {
        cancelAnim();
        if (mSpeedHandler != null) {
            mSpeedHandler.removeCallbacks(mSpeedTick);
        }
        super.onDetachedFromWindow();
    }

    /* ------------------------------------------------------------ 小工具 */

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    /* ================================================================ 图标 */

    /**
     * 单个圆形图标按钮。
     *
     * <p>背景用 {@link GradientDrawable} 画圆角矩形（圆角 = 边长一半 → 正圆），
     * 按下时在 {@link #drawableStateChanged()} 里换深一档的颜色；
     * 图标全部用 Path 在 24x24 坐标系内绘制，随控件尺寸等比缩放。</p>
     */
    private static final class IconView extends View {

        /** 只扩大触摸命中区（不改尺寸、不改布局）：外屏小，点偏一点也能命中。 */
        @Override
        public void getHitRect(android.graphics.Rect outRect) {
            super.getHitRect(outRect);
            // 🔴 外扩量必须 < 按钮间距的一半，否则相邻按钮的命中区互相重叠，
            //    点在缝里会命中"上一个"按钮（实测点关闭却退了返回）。间距见 mGap，默认 8dp。
            int pad = Math.max(1, Math.round(3 * getResources().getDisplayMetrics().density));
            outRect.inset(-pad, -pad);
        }


        /** 24 单位坐标系内的线宽。 */
        private static final float ICON_STROKE = 2.2f;
        /** 图标区域占按钮边长的比例。 */
        private static final float ICON_BOX_RATIO = 0.42f;

        private final int mType;
        private final int mSize;
        private final float mBoxRatio;
        private final GradientDrawable mBg;
        private final Paint mPaint;
        private final Path mPath = new Path();

        /** 返回箭头是否左右镜像（翻转模式下，后退方向要跟着反过来）。 */
        private boolean mMirrorBack = false;

        void setMirrorBack(boolean m) {
            if (mMirrorBack != m) {
                mMirrorBack = m;
                invalidate();
            }
        }
        private final RectF mRect = new RectF();

        IconView(Context context, int type, int size, float boxRatio) {
            super(context);
            mType = type;
            mSize = size;
            mBoxRatio = boxRatio;

            // 圆形半透明底：圆角 = size / 2
            mBg = new GradientDrawable();
            mBg.setShape(GradientDrawable.RECTANGLE);
            mBg.setCornerRadius(size / 2f);
            mBg.setColor(COLOR_BTN_NORMAL);
            setBackground(mBg);

            mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            mPaint.setStyle(Paint.Style.STROKE);
            mPaint.setStrokeCap(Paint.Cap.ROUND);
            mPaint.setStrokeJoin(Paint.Join.ROUND);
            mPaint.setStrokeWidth(ICON_STROKE);
            mPaint.setColor(COLOR_ICON);

            buildPath();
            setClickable(true);
            setFocusable(true);
        }

        /** 在 24x24 坐标系里构建图标路径（只建一次，绘制时复用）。 */
        private void buildPath() {
            mPath.reset();
            switch (mType) {
                // 🔴 四个图标统一到 **16×16 视觉盒**（中心 12,12），并按光学重量做微调：
                //    旧版是 6.5x14 / 14x14 / 9x9 / 10x5，肉眼可见不齐；
                //    上一版虽然都收进 16 盒，但"圆环"仍显胖、"折线箭头"仍显瘦、"✕"略小。
                //    规则：外接盒一律 16；**圆形缩 5%（d=15.2）**补偿圆的视觉膨胀；
                //    折线箭头加宽到 9.6（原来只有 8.4）；✕ 撑满 16。
                case ICON_BACK:
                    // 左向折线：高 16（4→20），宽 9.6（7.2→16.8）
                    mPath.moveTo(16.8f, 4f);
                    mPath.lineTo(7.2f, 12f);
                    mPath.lineTo(16.8f, 20f);
                    break;
                case ICON_HOME:
                    // 圆环：d=15.2（光学补偿后与 16 的方框读起来一样大）
                    mPath.addCircle(12f, 12f, 7.6f, Path.Direction.CW);
                    break;
                case ICON_RECENTS:
                    // 圆角方框：16x16
                    mRect.set(4f, 4f, 20f, 20f);
                    mPath.addRoundRect(mRect, 4.5f, 4.5f, Path.Direction.CW);
                    break;
                case ICON_COLLAPSE:
                    // ✕：撑满 16x16（原来 4.5→19.5 只有 15）
                    mPath.moveTo(4f, 4f);
                    mPath.lineTo(20f, 20f);
                    mPath.moveTo(20f, 4f);
                    mPath.lineTo(4f, 20f);
                    break;
                case ICON_DOT:
                default:
                    // 小圆点：展开把手
                    mPath.addCircle(12f, 12f, 5f, Path.Direction.CW);
                    break;
            }
        }

        @Override
        protected void drawableStateChanged() {
            super.drawableStateChanged();
            // pressed 状态由 View 默认触摸逻辑维护（setClickable(true) 之后有效）
            mBg.setColor(isPressed() ? COLOR_BTN_PRESSED : COLOR_BTN_NORMAL);
            invalidate();
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            // 固定尺寸：父容器给的都是精确尺寸，这里只做一次兜底
            setMeasuredDimension(resolveSize(mSize, widthMeasureSpec),
                    resolveSize(mSize, heightMeasureSpec));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float side = mSize * mBoxRatio;
            float offset = (mSize - side) / 2f;
            int save = canvas.save();
            canvas.translate(offset, offset);
            // 缩放到 24 单位坐标系；canvas.scale 会同时缩放线宽，strokeWidth 不用改
            float s = side / 24f;
            canvas.scale(s, s);
            // 翻转模式下把「返回」箭头左右镜像（围绕 24 单位框中心）
            if (mMirrorBack && mType == ICON_BACK) {
                canvas.scale(-1f, 1f, 12f, 12f);
            }
            canvas.drawPath(mPath, mPaint);
            canvas.restoreToCount(save);
        }
    }
}
