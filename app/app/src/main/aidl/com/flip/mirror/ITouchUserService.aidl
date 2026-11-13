package com.flip.mirror;

/**
 * Touch injection service interface.
 *
 * The implementation runs in a separate process with shell uid (launched by Shizuku),
 * so it can call the hidden InputManager.injectInputEvent directly.
 * That avoids forking one "input" process per touch event.
 *
 * NOTE: keep this file ASCII-only. aidl.exe fails on non-ASCII comments.
 */
interface ITouchUserService {

    /** Ready (InputManager obtained). */
    boolean ready();

    /**
     * Inject one touch event.
     *
     * @param action    MotionEvent action (0=DOWN 1=UP 2=MOVE)
     * @param x         x in target display coordinates
     * @param y         y in target display coordinates
     * @param downTime  gesture down time
     * @param eventTime event time
     * @param displayId target display (inner screen = 0)
     */
    void inject(int action, float x, float y, long downTime, long eventTime, int displayId);

    /** Inject a batch of points for dragging (fewer binder round trips). */
    void injectBatch(int action, in float[] xs, in float[] ys, long downTime, long eventTime, int displayId);

    /**
     * Inject a multi-pointer touch event (two-finger pinch / scroll).
     *
     * @param action      MotionEvent action MASKED (0=DOWN 1=UP 2=MOVE 5=POINTER_DOWN 6=POINTER_UP)
     * @param ids         pointer ids (parallel arrays with xs/ys)
     * @param xs          x in target display coordinates
     * @param ys          y in target display coordinates
     * @param actionIndex which pointer the POINTER_DOWN/UP refers to (ignored for other actions)
     * @param downTime    gesture down time
     * @param eventTime   event time
     * @param displayId   target display (inner screen = 0)
     */
    void injectMulti(int action, in int[] ids, in float[] xs, in float[] ys,
                     int actionIndex, long downTime, long eventTime, int displayId);
}
