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
}
