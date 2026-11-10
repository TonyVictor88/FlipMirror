package com.flip.mirror;

/**
 * 触摸注入器：把外屏坐标映射成内屏坐标，再注入触摸事件。
 *
 * 实现要求：
 * 1. 坐标映射：外屏 (748x720 物理，实际可用区 748x654，顶部 66px 挖孔)
 *    → 内屏 (1080x1920)，保持等比缩放。
 * 2. 注入方式：优先 root + uinput（/dev/uinput 创建虚拟触摸设备），
 *    退化方案：反射调用 InputManager.hiddenApiInjectInputEvent。
 * 3. 注入到 display 0（内屏），因为被镜像的内容属于 display 0。
 */
public interface TouchInjector {

    /** 初始化（root 可用时创建 uinput 设备）。返回是否成功。 */
    boolean init();

    /**
     * 注入一次触摸。
     * @param srcX 外屏原生坐标 x（0..srcW）
     * @param srcY 外屏原生坐标 y（0..srcH）
     * @param action ACTION_DOWN / ACTION_MOVE / ACTION_UP
     * @return 是否注入成功
     */
    boolean send(float srcX, float srcY, int action);

    /** 释放资源（关闭 uinput 设备）。 */
    void release();

    /** 设置源屏尺寸（外屏可视区域）与目标屏尺寸（内屏）。 */
    void setGeometry(int srcW, int srcH, int dstW, int dstH);
}
