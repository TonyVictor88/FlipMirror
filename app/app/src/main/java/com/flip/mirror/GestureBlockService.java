package com.flip.mirror;

import android.accessibilityservice.AccessibilityService;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

/**
 * 无障碍服务 —— 现在【完全被动】，不做任何动作。
 *
 * <h3>为什么留着一个空服务</h3>
 * 历史上它做过两件事，都被证明是错的：
 *
 * <ol>
 *   <li><b>吞边缘手势</b>（{@code onGesture} 返回 true）：本意是防止全屏手势退出镜像，
 *       实际有两个问题 —— ① 系统的 HOME 上滑是保留手势，根本不受它管，拦不住；
 *       ② 它会把**所有**手势一起吞掉，导致多任务页面里"返回手势"失效（2026-11-10 用户实测报障）。</li>
 *   <li><b>自动从多任务拉回首页</b>：用户明确否掉了 —— 多任务页面本来点空白/返回手势
 *       就该回主界面，自动弹回不符合交互逻辑。</li>
 * </ol>
 *
 * <h3>⚠️ 配置红线</h3>
 * 绝对不能开 {@code flagRequestTouchExplorationMode} / {@code canRequestTouchExplorationMode="true"}。
 * 一开，{@code dumpsys accessibility} 里 {@code touchExplorationEnabled=true}，
 * 全屏点击变成"先聚焦再双击"，用户会以为触控坏了（2026-11-10 踩过）。
 *
 * <p>正常情况下这个服务应当**保持关闭**：镜像的退出/控制全部走悬浮控件条。</p>
 */
public class GestureBlockService extends AccessibilityService {

    private static final String TAG = "FlipMirrorA11y";

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        Log.i(TAG, "connected (passive)");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // 被动：不处理任何事件
    }

    @Override
    public void onInterrupt() {
    }

    /**
     * 绝不吞手势 —— 返回 false 表示"我不处理，交给系统"。
     * 这样多任务页面的返回手势、边缘滑动都会恢复正常。
     */
    @Override
    protected boolean onGesture(int gestureId) {
        return false;
    }
}
