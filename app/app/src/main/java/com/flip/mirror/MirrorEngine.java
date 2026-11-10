package com.flip.mirror;

import android.view.Surface;

/**
 * 镜像引擎：把源 display（内屏 0）的图像，**不编码**直接送到目标 Surface（外屏上的 SurfaceView）。
 *
 * 实现要求（核心：不经过 MediaCodec，不花屏）：
 * 1. 用 MediaProjection 创建 VirtualDisplay，其 outputSurface 就是**目标 SurfaceView 的 Surface**。
 * 2. VirtualDisplay 的尺寸 = 目标 Surface 尺寸（748x720 或 SurfaceView 实际尺寸）。
 * 3. 不创建 ImageReader、不用 MediaCodec、不做 H.264/H.265 编码 —— 全程 GPU 直通。
 * 4. 延迟目标 < 50ms。
 */
public interface MirrorEngine {

    /**
     * 开始镜像。
     * @param projection MediaProjection（调用方已拿到授权）
     * @param targetSurface 外屏上 SurfaceView 的 Surface（最终显示目标）
     * @param width 目标宽
     * @param height 目标高
     * @return 是否成功
     */
    boolean start(android.media.projection.MediaProjection projection,
                  Surface targetSurface, int width, int height);

    /** 停止镜像，释放 VirtualDisplay。 */
    void stop();

    boolean isRunning();

    /** Surface 尺寸变化时重建 VirtualDisplay（不重新申请授权）。 */
    boolean resize(int width, int height);

    /**
     * 切换输出 Surface（关屏/亮屏导致 surface 重建时用）。
     *
     * <p>与 {@link #resize} 同理：**不能** stop 再 start —— Android 14 起同一个 MediaProjection
     * 令牌只允许创建一次 VirtualDisplay，重建会抛 SecurityException。
     * 正确姿势：
     * <pre>
     *   surfaceDestroyed → setSurface(null)              // 摘掉输出，VD 继续活着
     *   surfaceCreated   → setSurface(holder.getSurface()) // 换绑新 surface
     * </pre>
     *
     * @param newSurface 新目标 Surface；传 null 表示暂时摘掉输出
     * @return 是否成功
     */
    boolean setSurface(Surface newSurface);
}
