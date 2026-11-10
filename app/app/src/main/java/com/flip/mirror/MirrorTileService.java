package com.flip.mirror;

import android.app.PendingIntent;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/**
 * 快捷设置磁贴：下拉通知栏点一下，直接回到投屏。
 *
 * <h3>为什么用磁贴而不是长按电源键</h3>
 * 长按电源键的事件在**屏幕熄灭时根本到不了应用层**（AccessibilityService 的
 * 按键过滤也收不到），而且拦截电源键会破坏正常的息屏/亮屏行为。
 * 磁贴是系统级入口，任何状态下都能点，且不干扰电源键语义。
 *
 * <h3>点了做什么</h3>
 * 拉起 {@link MainActivity} 并带 {@code auto_start=true} ——
 * MainActivity 会自己挪到外屏、走授权、拉起镜像页，全程不需要再点按钮。
 */
public class MirrorTileService extends TileService {

    @Override
    public void onClick() {
        super.onClick();
        try {
            Intent it = new Intent(this, MainActivity.class);
            it.putExtra("auto_start", true);
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivityAndCollapse(
                    PendingIntent.getActivity(this, 0, it,
                            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        } catch (Throwable t) {
            // 老系统没有带 PendingIntent 的版本，退回直接 start
            Intent it = new Intent(this, MainActivity.class);
            it.putExtra("auto_start", true);
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(it);
        }
    }

    @Override
    public void onStartListening() {
        super.onStartListening();
        try {
            Tile tile = getQsTile();
            if (tile != null) {
                tile.setState(Tile.STATE_INACTIVE);
                tile.setLabel("投屏到外屏");
                tile.setIcon(Icon.createWithResource(this, android.R.drawable.ic_menu_slideshow));
                tile.updateTile();
            }
        } catch (Throwable ignored) { }
    }
}
