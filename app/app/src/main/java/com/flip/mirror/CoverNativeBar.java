package com.flip.mirror;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.os.Build;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;

/**
 * 外屏「原生模式」下的导航浮层（独立悬浮在 display 1 顶层）。
 *
 * <p>实测（SM-F731N / Android 16 / state 4 双屏并发）：系统将主焦点锁在 display 0，
 * display 1 没有 mCurrentFocus，因此 {@code input -d 1 keyevent 4} 无法触发原生页面返回。
 * 本浮层只保留在 display 1 上 100% 确定可用的交互：</p>
 * <ul>
 *   <li>右上角 66px 黑带区 2 键极简条：⇄ 无损回到镜像、✕ 退回外屏首页；</li>
 *   <li>底部居中 280×24px 手势条：上滑即无损回到镜像（轻触透传给下方应用）。</li>
 * </ul>
 */
public final class CoverNativeBar {

    public interface Listener {
        void onReturnToMirror();
        void onExit();
    }

    private static final int TOP_BAND_H = 66;
    private static final int BTN_SIZE = 44;
    private static final int BTN_GAP = 10;
    private static final int BOTTOM_W = 280;
    private static final int BOTTOM_H = 24;
    private static final float SWIPE_HOME_PX = 36f;

    private final Context mCtx;
    private final WindowManager mWm;
    private final Listener mListener;

    private LinearLayout mPill;
    private BottomGestureView mBottomEdge;
    private boolean mShown = false;

    public CoverNativeBar(Context display1Context, WindowManager wm1, Listener listener) {
        mCtx = display1Context;
        mWm = wm1;
        mListener = listener;
    }

    public boolean isShown() {
        return mShown;
    }

    public void show() {
        if (mShown || mWm == null) {
            return;
        }
        // ① 右上角 66px 黑带区两键条（⇄ 回镜像 / ✕ 退出），不压下方应用区域
        mPill = new LinearLayout(mCtx);
        mPill.setOrientation(LinearLayout.HORIZONTAL);
        mPill.setGravity(Gravity.CENTER_VERTICAL);
        mPill.setPadding(8, 0, 12, 0);

        mPill.addView(makeBtn(NavBtnView.TYPE_MIRROR, "回镜像", v -> {
            if (mListener != null) mListener.onReturnToMirror();
        }), btnLp(0));
        mPill.addView(makeBtn(NavBtnView.TYPE_EXIT, "退出", v -> {
            if (mListener != null) mListener.onExit();
        }), btnLp(BTN_GAP));

        WindowManager.LayoutParams pillLp = baseOverlayLp(
                WindowManager.LayoutParams.WRAP_CONTENT, TOP_BAND_H,
                Gravity.TOP | Gravity.END);
        mWm.addView(mPill, pillLp);

        // ② 底部上滑回镜像手势热区
        mBottomEdge = new BottomGestureView(mCtx);
        WindowManager.LayoutParams botLp = baseOverlayLp(
                BOTTOM_W, BOTTOM_H, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        mWm.addView(mBottomEdge, botLp);

        mShown = true;
    }

    public void hide() {
        if (!mShown) {
            return;
        }
        mShown = false;
        removeQuietly(mPill);
        removeQuietly(mBottomEdge);
        mPill = null;
        mBottomEdge = null;
    }

    private void removeQuietly(View v) {
        if (v != null && mWm != null) {
            try {
                mWm.removeView(v);
            } catch (Throwable ignored) { }
        }
    }

    private static WindowManager.LayoutParams baseOverlayLp(int w, int h, int gravity) {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                w, h,
                Build.VERSION.SDK_INT >= 26
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = gravity;
        if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        }
        return lp;
    }

    private static LinearLayout.LayoutParams btnLp(int leftMargin) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(BTN_SIZE, BTN_SIZE);
        lp.leftMargin = leftMargin;
        return lp;
    }

    private NavBtnView makeBtn(int type, String desc, View.OnClickListener click) {
        NavBtnView b = new NavBtnView(mCtx, type);
        b.setContentDescription(desc);
        b.setOnClickListener(click);
        return b;
    }

    /** 极简圆形按钮（半透明黑底 + 白色线性图标）。 */
    private static final class NavBtnView extends View {
        static final int TYPE_MIRROR = 1;
        static final int TYPE_EXIT = 2;

        private final int mType;
        private final Paint mBg = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mFg = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path mPath = new Path();
        private boolean mPressed = false;

        NavBtnView(Context c, int type) {
            super(c);
            mType = type;
            mBg.setStyle(Paint.Style.FILL);
            mBg.setColor(0x88000000);
            mFg.setStyle(Paint.Style.STROKE);
            mFg.setStrokeCap(Paint.Cap.ROUND);
            mFg.setStrokeJoin(Paint.Join.ROUND);
            mFg.setStrokeWidth(3.2f);
            mFg.setColor(0xFFFFFFFF);
            setClickable(true);
        }

        @Override
        protected void drawableStateChanged() {
            super.drawableStateChanged();
            boolean p = isPressed();
            if (p != mPressed) {
                mPressed = p;
                mBg.setColor(p ? 0xCC2E6BE6 : 0x88000000);
                invalidate();
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth(), h = getHeight();
            float r = Math.min(w, h) / 2f;
            canvas.drawCircle(w / 2f, h / 2f, r - 1f, mBg);

            float cx = w / 2f, cy = h / 2f;
            float s = r * 0.42f;
            mPath.reset();
            if (mType == TYPE_MIRROR) {
                mPath.moveTo(cx - s * 0.85f, cy - s * 0.35f);
                mPath.lineTo(cx + s * 0.75f, cy - s * 0.35f);
                mPath.lineTo(cx + s * 0.30f, cy - s * 0.80f);
                mPath.moveTo(cx + s * 0.85f, cy + s * 0.35f);
                mPath.lineTo(cx - s * 0.75f, cy + s * 0.35f);
                mPath.lineTo(cx - s * 0.30f, cy + s * 0.80f);
            } else {
                mPath.moveTo(cx - s * 0.65f, cy - s * 0.65f);
                mPath.lineTo(cx + s * 0.65f, cy + s * 0.65f);
                mPath.moveTo(cx + s * 0.65f, cy - s * 0.65f);
                mPath.lineTo(cx - s * 0.65f, cy + s * 0.65f);
            }
            canvas.drawPath(mPath, mFg);
        }
    }

    /** 底部上滑手势热区：上滑即无损切回镜像；轻触则透传坐标到 display 1。 */
    private final class BottomGestureView extends View {
        private float mDownX, mDownY;
        private long mDownAt;
        private float mPull = 0f;
        private final Paint mBarPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        BottomGestureView(Context c) {
            super(c);
            mBarPaint.setColor(0x55FFFFFF);
            mBarPaint.setStyle(Paint.Style.FILL);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    mDownX = e.getRawX();
                    mDownY = e.getRawY();
                    mDownAt = android.os.SystemClock.elapsedRealtime();
                    mPull = 0f;
                    invalidate();
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float dy = mDownY - e.getRawY();
                    float dx = Math.abs(e.getRawX() - mDownX);
                    if (dy > 8f && dy > dx * 1.2f) {
                        mPull = Math.min(1f, dy / SWIPE_HOME_PX);
                        invalidate();
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    float dy = mDownY - e.getRawY();
                    float dx = Math.abs(e.getRawX() - mDownX);
                    boolean fired = e.getActionMasked() == MotionEvent.ACTION_UP
                            && dy >= SWIPE_HOME_PX && dy > dx * 1.3f;
                    mPull = 0f;
                    invalidate();
                    if (fired) {
                        if (mListener != null) mListener.onReturnToMirror();
                    } else if (e.getActionMasked() == MotionEvent.ACTION_UP
                            && dx < 12f && Math.abs(dy) < 12f
                            && android.os.SystemClock.elapsedRealtime() - mDownAt < 250) {
                        final int tx = Math.round(e.getRawX());
                        final int ty = Math.round(e.getRawY());
                        Shell.exec("input -d 1 tap " + tx + " " + ty);
                    }
                    return true;
                }
            }
            return super.onTouchEvent(e);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth(), h = getHeight();
            float bw = 72f + 44f * mPull;
            float bh = 4f + 2f * mPull;
            float l = (w - bw) / 2f;
            float t = h - 7f - 6f * mPull;
            mBarPaint.setColor(mPull > 0.1f ? 0xEE35E0C0 : 0x55FFFFFF);
            canvas.drawRoundRect(l, t, l + bw, t + bh, bh / 2f, bh / 2f, mBarPaint);
        }
    }
}
